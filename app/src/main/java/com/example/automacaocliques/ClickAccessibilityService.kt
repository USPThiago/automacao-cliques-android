package com.example.automacaocliques

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servico de acessibilidade que executa o roteiro de sessoes: captura a tela,
 * localiza os templates de cada acao e despacha os toques via
 * [dispatchGesture]. A arvore de acessibilidade e usada apenas para saber qual
 * app esta em primeiro plano.
 */
class ClickAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Thread unica onde o roteiro roda: captura e casamento custam segundos e
     * travariam a interface (ANR) se rodassem na thread principal.
     */
    private val runnerExecutor = Executors.newSingleThreadExecutor()

    /**
     * Thread separada para as respostas de [takeScreenshot]: a thread do roteiro
     * fica bloqueada esperando a captura e nao pode receber o proprio callback.
     */
    private val captureExecutor = Executors.newSingleThreadExecutor()

    /** Recortes usados no reconhecimento visual das telas. */
    val templates: TemplateStore by lazy { TemplateStore(this) }

    /** Arquivos de sessao em `files/sessions/`. */
    val sessions: SessionStore by lazy { SessionStore(this) }

    private val running = AtomicBoolean(false)

    /**
     * Pedido de parada valido durante toda a execucao, inclusive antes de o
     * [SessionRunner] existir: Parar logo depois de Iniciar tem de valer.
     */
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var runner: SessionRunner? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        Log.i(TAG, "Servico conectado")
    }

    /** Indica se um roteiro esta em execucao. */
    fun isExecuting(): Boolean = running.get()

    /**
     * Valida a carga inicial e executa o roteiro a partir de
     * `sessions/mainSession.json`. Devolve `false` se ja houver uma execucao em
     * andamento (duas execucoes simultaneas sao impedidas).
     */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) {
            log.add("Execucao", "ja existe uma execucao em andamento")
            return false
        }
        stopRequested.set(false)
        templates.invalidate()
        runnerExecutor.execute {
            try {
                execute()
            } finally {
                running.set(false)
                runner = null
            }
        }
        return true
    }

    /** Cancela a execucao em andamento, se houver. */
    fun stop() {
        stopRequested.set(true)
        runner?.cancel()
    }

    private fun execute() {
        if (!awaitForeignForeground()) {
            if (stopRequested.get()) {
                log.add("Execucao", "parada")
            } else {
                log.add("Transicao", "NOK - app em primeiro plano")
            }
            return
        }
        // A validacao vem depois da troca de app porque as dimensoes e a
        // orientacao usadas nela precisam ser as do app alvo, e nao as da
        // interface de automacao, que e sempre paisagem.
        val screen = screenSize()
        when (val load = SessionValidator.load(sessions, templates::sizeOf, screen)) {
            is SessionLoad.Failure -> {
                log.add("Carga inicial", "NOK - ${load.reason}")
                return
            }
            is SessionLoad.Ok -> {
                log.add("Carga inicial", "OK")
                val sessionRunner = SessionRunner(ServiceEnvironment(), log)
                runner = sessionRunner
                // Parada pedida enquanto o executor era criado ou durante a
                // validacao: o cancelamento e transferido para ele.
                if (stopRequested.get()) sessionRunner.cancel()
                when (val outcome = sessionRunner.run(load.main, load.sessions)) {
                    RunOutcome.Success -> log.add("Execucao", "concluida com sucesso")
                    RunOutcome.Cancelled -> log.add("Execucao", "parada")
                    is RunOutcome.Failure -> log.add("Execucao", "encerrada: ${outcome.reason}")
                }
            }
        }
    }

    /**
     * Espera a janela ativa deixar de pertencer a este app, para nao capturar e
     * clicar na propria interface de automacao. Substitui o atraso fixo do MVP 2.
     */
    private fun awaitForeignForeground(timeoutMs: Long = FOREGROUND_TIMEOUT_MS): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (stopRequested.get()) return false
            if (!isOwnAppInForeground()) return true
            SystemClock.sleep(FOREGROUND_POLL_MS)
        }
        return !stopRequested.get() && !isOwnAppInForeground()
    }

    private fun isOwnAppInForeground(): Boolean =
        rootInActiveWindow?.packageName == packageName

    /** Resolucao real da tela, incluindo status bar e barra de navegacao. */
    fun screenSize(): Size {
        val bounds = screenBounds()
        return Size(bounds.width(), bounds.height())
    }

    private fun screenBounds(): Rect {
        val windowManager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Rect(windowManager.currentWindowMetrics.bounds)
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Captura a tela via [takeScreenshot] e entrega o bitmap (ou `null` em caso
     * de falha) em [captureExecutor], nunca na thread principal. Requer Android
     * 11 (API 30); em versoes anteriores nao ha captura pela API de
     * acessibilidade.
     */
    private fun captureScreen(onResult: (Bitmap?, Int) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Captura de tela exige Android 11 (API 30); atual=${Build.VERSION.SDK_INT}")
            onResult(null, UNSUPPORTED_API_ERROR)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            captureExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = screenshot.hardwareBuffer.use { buffer ->
                        Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    onResult(bitmap, if (bitmap == null) CONVERSION_ERROR else 0)
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null, errorCode)
                }
            }
        )
    }

    /** Constroi e despacha um gesto de toque em ([x], [y]). */
    fun click(x: Float, y: Float, onOutcome: (ClickOutcome) -> Unit = {}) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, CLICK_DURATION_MS))
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onOutcome(ClickOutcome.COMPLETED)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onOutcome(ClickOutcome.CANCELLED)
                }
            },
            null
        )
        if (!dispatched) onOutcome(ClickOutcome.REJECTED)
    }

    /** Ponte entre o executor de sessoes e as APIs do aparelho. */
    private inner class ServiceEnvironment : RunnerEnvironment {

        override fun capture(): Capture {
            val latch = CountDownLatch(1)
            var result: Capture = Capture.Failed(TIMEOUT_ERROR)
            captureScreen { bitmap, errorCode ->
                result = if (bitmap == null) {
                    Capture.Failed(errorCode)
                } else {
                    Capture.Ok(bitmap.toGrayImage()).also { bitmap.recycle() }
                }
                latch.countDown()
            }
            if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Capture.Failed(TIMEOUT_ERROR)
            }
            return result
        }

        override fun click(x: Float, y: Float): ClickOutcome {
            val latch = CountDownLatch(1)
            var outcome = ClickOutcome.REJECTED
            mainHandler.post {
                this@ClickAccessibilityService.click(x, y) {
                    outcome = it
                    latch.countDown()
                }
            }
            if (!latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return ClickOutcome.CANCELLED
            }
            return outcome
        }

        override fun templateOf(name: String): GrayImage? = templates.get(name)?.image

        override fun sleep(ms: Long) = SystemClock.sleep(ms)

        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "Servico interrompido")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        runnerExecutor.shutdownNow()
        captureExecutor.shutdownNow()
        isRunning = false
        instance = null
        Log.i(TAG, "Servico desconectado")
        return super.onUnbind(intent)
    }

    companion object {
        const val TAG = "ClickService"

        /** Duracao do toque despachado. */
        private const val CLICK_DURATION_MS = 50L

        /** Espera maxima ate o app sair do primeiro plano depois de Iniciar. */
        private const val FOREGROUND_TIMEOUT_MS = 15_000L

        private const val FOREGROUND_POLL_MS = 200L

        private const val CAPTURE_TIMEOUT_MS = 10_000L

        private const val GESTURE_TIMEOUT_MS = 10_000L

        private const val UNSUPPORTED_API_ERROR = -1

        private const val CONVERSION_ERROR = -2

        private const val TIMEOUT_ERROR = -3

        /**
         * Linhas de execucao mostradas na interface. Fica no servico (e nao na
         * Activity) porque o app roda em segundo plano durante a execucao.
         */
        val log = ExecutionLog()

        /**
         * Indica se o servico esta conectado nesta instancia do processo. Reflete o
         * estado real apenas enquanto o processo do app vive; para consultar a
         * configuracao do sistema use [AccessibilityUtils.isServiceEnabled].
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Instancia conectada do servico, usada pela UI para acionar acoes. */
        @Volatile
        var instance: ClickAccessibilityService? = null
            private set
    }
}

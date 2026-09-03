package com.example.automacaocliques

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
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
    @Volatile
    private var runnerExecutor = Executors.newSingleThreadExecutor()

    /**
     * Thread separada para as respostas de [takeScreenshot]: a thread do roteiro
     * fica bloqueada esperando a captura e nao pode receber o proprio callback.
     */
    @Volatile
    private var captureExecutor = Executors.newSingleThreadExecutor()

    /** Recortes usados no reconhecimento visual das telas. */
    val templates: TemplateStore by lazy { TemplateStore(this) }

    /** Arquivos de sessao em `files/sessions/`. */
    val sessions: SessionStore by lazy { SessionStore(this) }

    private val running = AtomicBoolean(false)

    private val prefs by lazy { AppPreferences(this) }

    /** Popup do modo debug; so e tocado na thread principal. */
    private val debugOverlay by lazy { DebugOverlay(this) }

    /**
     * Pedido de parada valido durante toda a execucao, inclusive antes de o
     * [SessionRunner] existir: Parar logo depois de Iniciar tem de valer.
     */
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var runner: SessionRunner? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        ensureExecutors()
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
        ensureExecutors()
        // Lida uma vez por execucao, para nao pagar I/O a cada acao.
        val debugEnabled = prefs.debugEnabled
        runnerExecutor.execute {
            try {
                execute(debugEnabled)
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
        // Um popup de debug aberto e fechado como Cancel para liberar a thread do roteiro.
        mainHandler.post { debugOverlay.dismiss() }
    }

    private fun execute(debugEnabled: Boolean) {
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
                if (debugEnabled) log.add("Modo debug", "ligado")
                val sessionRunner = SessionRunner(ServiceEnvironment(debugEnabled), log)
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

    private fun ensureExecutors() {
        synchronized(this) {
            if (runnerExecutor.isShutdown) {
                runnerExecutor = Executors.newSingleThreadExecutor()
            }
            if (captureExecutor.isShutdown) {
                captureExecutor = Executors.newSingleThreadExecutor()
            }
        }
    }

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
    private inner class ServiceEnvironment(private val debugEnabled: Boolean) : RunnerEnvironment {

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

        override fun debugEnabled(): Boolean = debugEnabled

        /**
         * Mostra a sobreposicao na thread principal e espera a resposta. Sem
         * resposta em [DEBUG_TIMEOUT_MS] a execucao e cancelada, para nao ficar
         * presa caso a sobreposicao falhe.
         */
        override fun confirmStep(step: DebugStep): DebugChoice {
            val latch = CountDownLatch(1)
            var choice = DebugChoice.CONTINUE
            mainHandler.post {
                debugOverlay.show(step) {
                    choice = it
                    latch.countDown()
                }
            }
            val answered = latch.await(DEBUG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val hidden = CountDownLatch(1)
            mainHandler.post {
                debugOverlay.hide()
                hidden.countDown()
            }
            // A proxima captura so pode acontecer com a sobreposicao ja removida.
            hidden.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!answered) {
                log.add("Debug", "sem resposta - execucao parada")
                return DebugChoice.CANCEL
            }
            if (choice == DebugChoice.CANCEL) bringAppToFront()
            return choice
        }
    }

    /** Traz a tela do app de volta ao primeiro plano depois do Cancel do modo debug. */
    private fun bringAppToFront() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Falha ao trazer o app para o primeiro plano", e)
            log.add("Debug", "nao foi possivel trazer o app para o primeiro plano")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "Servico interrompido")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        debugOverlay.hide()
        synchronized(this) {
            runnerExecutor.shutdownNow()
            captureExecutor.shutdownNow()
        }
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

        /** Espera maxima pela resposta ao popup do modo debug. */
        private const val DEBUG_TIMEOUT_MS = 5 * 60_000L

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

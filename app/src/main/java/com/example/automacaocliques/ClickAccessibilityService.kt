package com.example.automacaocliques

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Servico de acessibilidade responsavel por ler a tela e despachar gestos de
 * toque. Expoe [readScreen] / [findNode] (leitura da arvore de acessibilidade) e
 * [click] / [clickNode] / [runSequence] (execucao de um ou mais toques).
 */
class ClickAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Recortes usados no reconhecimento visual das telas. */
    val templates: TemplateStore by lazy { TemplateStore(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this
        Log.i(TAG, "Servico conectado")
        mainHandler.postDelayed({ clickAtScreenCenter() }, INITIAL_CLICK_DELAY_MS)
    }

    /** Dispara um toque unico no centro real da tela. */
    fun clickAtScreenCenter() {
        val bounds = screenBounds()
        click(bounds.exactCenterX(), bounds.exactCenterY())
    }

    /** Limites reais da tela, incluindo status bar e barra de navegacao. */
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

    /** Nos visiveis da janela ativa, achatados em ordem de arvore. */
    fun readScreen(): List<ScreenNode> = ScreenReader.flatten(rootInActiveWindow)

    /** Registra no Logcat todos os nos visiveis da janela ativa. */
    fun logScreen() {
        val nodes = readScreen()
        if (nodes.isEmpty()) {
            Log.w(TAG, "Leitura de tela vazia (janela ativa sem conteudo acessivel)")
            return
        }
        Log.i(TAG, "Tela de ${nodes.first().packageName}: ${nodes.size} nos visiveis")
        nodes.forEach { Log.d(TAG, it.describe()) }
    }

    /** Primeiro no da tela atual que satisfaz [selector], se houver. */
    fun findNode(selector: NodeSelector): ScreenNode? =
        ScreenReader.find(readScreen(), selector)

    /** Clica no centro dos limites de [node]. */
    fun clickNode(node: ScreenNode) {
        Log.i(TAG, "Clicando no no ${node.describe().trim()}")
        click(node.centerX, node.centerY)
    }

    /**
     * Executa [steps] em ordem, esperando o intervalo de cada passo antes de
     * executa-lo. A tela e lida novamente a cada passo, de modo que uma mesma
     * tela pode receber varios cliques e passos seguintes podem depender do
     * resultado dos anteriores.
     */
    fun runSequence(steps: List<ClickStep>) {
        if (steps.isEmpty()) {
            Log.w(TAG, "Sequencia vazia, nada a executar")
            return
        }
        Log.i(TAG, "Iniciando sequencia com ${steps.size} passo(s)")
        scheduleStep(steps, 0)
    }

    /**
     * Captura a tela via [takeScreenshot] e entrega o bitmap (ou `null` em caso
     * de falha) na thread principal. Requer Android 11 (API 30); em versoes
     * anteriores nao ha captura de tela pela API de acessibilidade.
     */
    fun captureScreen(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Captura de tela exige Android 11 (API 30); atual=${Build.VERSION.SDK_INT}")
            onResult(null)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = screenshot.hardwareBuffer.use { buffer ->
                        Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    if (bitmap == null) Log.w(TAG, "Nao foi possivel converter a captura de tela")
                    onResult(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Falha na captura de tela (codigo=$errorCode)")
                    onResult(null)
                }
            }
        )
    }

    /**
     * Procura o template [name] na tela atual e entrega a melhor ocorrencia com
     * escore acima de [threshold], ou `null`.
     */
    fun findTemplate(
        name: String,
        threshold: Double = TemplateMatcher.DEFAULT_THRESHOLD,
        onResult: (TemplateMatch?) -> Unit
    ) {
        val template = templates.get(name)
        if (template == null) {
            onResult(null)
            return
        }
        captureScreen { bitmap ->
            if (bitmap == null) {
                onResult(null)
                return@captureScreen
            }
            val match = TemplateMatcher.findBest(bitmap.toGrayImage(), template.image)
            when {
                match == null -> Log.w(TAG, "Template '$name' nao cabe na tela")
                match.score < threshold ->
                    Log.w(TAG, "Template '$name' abaixo do limite: ${match.describe()}")
                else -> Log.i(TAG, "Template '$name' encontrado: ${match.describe()}")
            }
            onResult(match?.takeIf { it.score >= threshold })
        }
    }

    /** Clica no centro da ocorrencia do template [name], se encontrada. */
    fun clickTemplate(
        name: String,
        threshold: Double = TemplateMatcher.DEFAULT_THRESHOLD,
        onDone: () -> Unit = {}
    ) {
        findTemplate(name, threshold) { match ->
            if (match == null) {
                Log.w(TAG, "Template '$name' nao encontrado; nenhum clique despachado")
            } else {
                click(match.centerX, match.centerY)
            }
            onDone()
        }
    }

    /**
     * Compara todos os templates disponiveis com a tela atual e registra os
     * escores em ordem decrescente, identificando a variante de tela mais
     * provavel. Entrega o nome do melhor template acima de [threshold].
     */
    fun identifyScreen(
        threshold: Double = TemplateMatcher.DEFAULT_THRESHOLD,
        onResult: (String?) -> Unit = {}
    ) {
        val available = templates.all()
        if (available.isEmpty()) {
            Log.w(TAG, "Nenhum template em ${templates.directory()}")
            onResult(null)
            return
        }
        captureScreen { bitmap ->
            if (bitmap == null) {
                onResult(null)
                return@captureScreen
            }
            val screen = bitmap.toGrayImage()
            Log.i(TAG, "Reconhecendo tela ${screen.width}x${screen.height} com ${available.size} template(s)")
            val scored = available
                .mapNotNull { template ->
                    TemplateMatcher.findBest(screen, template.image)?.let { template.name to it }
                }
                .sortedByDescending { it.second.score }
            scored.forEach { (name, match) -> Log.i(TAG, "  $name -> ${match.describe()}") }

            val best = scored.firstOrNull()?.takeIf { it.second.score >= threshold }
            if (best == null) {
                Log.w(TAG, "Nenhum template acima do limite $threshold")
            } else {
                Log.i(TAG, "Tela reconhecida como '${best.first}'")
            }
            onResult(best?.first)
        }
    }

    /** Cancela uma sequencia agendada e cliques pendentes. */
    fun cancelSequence() {
        mainHandler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Sequencia cancelada")
    }

    private fun scheduleStep(steps: List<ClickStep>, index: Int) {
        if (index !in steps.indices) {
            Log.i(TAG, "Sequencia finalizada")
            return
        }
        val step = steps[index]
        mainHandler.postDelayed({
            executeStep(step, index, steps.size) { scheduleStep(steps, index + 1) }
        }, step.delayMs)
    }

    /** Executa um passo e chama [onDone] quando ele termina. */
    private fun executeStep(step: ClickStep, index: Int, total: Int, onDone: () -> Unit) {
        val label = "passo ${index + 1}/$total"
        when (step) {
            is ClickStep.AtPoint -> {
                Log.i(TAG, "$label: clique em (${step.x}, ${step.y})")
                click(step.x, step.y)
                onDone()
            }
            is ClickStep.OnNode -> {
                val node = findNode(step.selector)
                if (node == null) {
                    Log.w(TAG, "$label: nenhum no encontrado para ${step.selector.describe()}")
                } else {
                    Log.i(TAG, "$label: ${step.selector.describe()}")
                    clickNode(node)
                }
                onDone()
            }
            is ClickStep.OnTemplate -> {
                Log.i(TAG, "$label: template '${step.name}' (limite ${step.threshold})")
                clickTemplate(step.name, step.threshold, onDone)
            }
        }
    }

    /** Constroi e despacha um gesto de toque em ([x], [y]). */
    fun click(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, CLICK_DURATION_MS))
            .build()

        Log.i(TAG, "Despachando clique em x=$x y=$y")
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Gesto concluido em x=$x y=$y")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesto cancelado em x=$x y=$y")
                }
            },
            null
        )
        if (!dispatched) {
            Log.w(TAG, "dispatchGesture retornou false para x=$x y=$y")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        Log.d(
            TAG,
            "Evento ${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "pacote=${event.packageName} classe=${event.className}"
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "Servico interrompido")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        mainHandler.removeCallbacksAndMessages(null)
        isRunning = false
        instance = null
        Log.i(TAG, "Servico desconectado")
        return super.onUnbind(intent)
    }

    companion object {
        const val TAG = "ClickService"

        /** Atraso antes do clique automatico, para o usuario sair das Configuracoes. */
        private const val INITIAL_CLICK_DELAY_MS = 6_000L

        /** Duracao do toque despachado. */
        private const val CLICK_DURATION_MS = 50L

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

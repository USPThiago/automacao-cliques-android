package com.example.automacaocliques

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Servico de acessibilidade responsavel por ler a tela e despachar gestos de
 * toque. Expoe [readScreen] / [findNode] (leitura da arvore de acessibilidade) e
 * [click] / [clickNode] / [runSequence] (execucao de um ou mais toques).
 */
class ClickAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

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
            executeStep(step, index, steps.size)
            scheduleStep(steps, index + 1)
        }, step.delayMs)
    }

    private fun executeStep(step: ClickStep, index: Int, total: Int) {
        val label = "passo ${index + 1}/$total"
        when (step) {
            is ClickStep.AtPoint -> {
                Log.i(TAG, "$label: clique em (${step.x}, ${step.y})")
                click(step.x, step.y)
            }
            is ClickStep.OnNode -> {
                val node = findNode(step.selector)
                if (node == null) {
                    Log.w(TAG, "$label: nenhum no encontrado para ${step.selector.describe()}")
                } else {
                    Log.i(TAG, "$label: ${step.selector.describe()}")
                    clickNode(node)
                }
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
        private const val INITIAL_CLICK_DELAY_MS = 3_000L

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

package com.example.automacaocliques

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Servico de acessibilidade responsavel por despachar gestos de toque. Neste MVP
 * ele dispara um clique unico no centro da tela alguns segundos depois de ser
 * conectado, registrando o resultado no Logcat.
 */
class ClickAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "Servico conectado")
        mainHandler.postDelayed({ clickAtScreenCenter() }, INITIAL_CLICK_DELAY_MS)
    }

    /** Dispara um toque unico no centro da tela. */
    fun clickAtScreenCenter() {
        val metrics = Resources.getSystem().displayMetrics
        click(metrics.widthPixels / 2f, metrics.heightPixels / 2f)
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
    }
}

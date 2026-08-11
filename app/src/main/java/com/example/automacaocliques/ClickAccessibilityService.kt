package com.example.automacaocliques

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Servico de acessibilidade que servira de base para o disparo de gestos nos
 * proximos MVPs. Neste MVP ele apenas registra o ciclo de vida e os eventos
 * recebidos no Logcat.
 */
class ClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.i(TAG, "Servico conectado")
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
        isRunning = false
        Log.i(TAG, "Servico desconectado")
        return super.onUnbind(intent)
    }

    companion object {
        const val TAG = "ClickService"

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

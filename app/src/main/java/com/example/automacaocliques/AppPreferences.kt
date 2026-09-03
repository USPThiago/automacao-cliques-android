package com.example.automacaocliques

import android.content.Context
import android.content.SharedPreferences

/** Preferencias do app compartilhadas entre a Activity e o servico. */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Modo debug: popup de confirmacao depois dos cliques de cada acao. */
    var debugEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_ENABLED, value).apply()

    private companion object {
        const val NAME = "automacao"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
    }
}

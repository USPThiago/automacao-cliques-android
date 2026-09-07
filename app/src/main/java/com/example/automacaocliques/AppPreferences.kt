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

    /** Retangulos de busca/match desenhados na tela durante a execucao. */
    var highlightsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HIGHLIGHTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HIGHLIGHTS_ENABLED, value).apply()

    /** Gravacao das linhas na caixa de log da tela (Logcat e erros continuam). */
    var logEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOG_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LOG_ENABLED, value).apply()

    /** Modo teste: le sessoes e templates das pastas `*_teste`. */
    var testMode: Boolean
        get() = prefs.getBoolean(KEY_TEST_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_TEST_MODE, value).apply()

    private companion object {
        const val NAME = "automacao"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
        const val KEY_HIGHLIGHTS_ENABLED = "highlights_enabled"
        const val KEY_LOG_ENABLED = "log_enabled"
        const val KEY_TEST_MODE = "test_mode"
    }
}

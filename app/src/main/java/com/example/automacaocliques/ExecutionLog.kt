package com.example.automacaocliques

import android.util.Log

/**
 * Buffer circular das linhas de execucao, com rotulo e valor. Vive no servico
 * (o app fica em segundo plano durante a execucao) e a interface apenas reflete
 * o conteudo quando volta ao primeiro plano.
 */
class ExecutionLog(private val limit: Int = MAX_LINES) {

    private val lines = ArrayDeque<String>(limit)

    /** Chamado a cada mudanca, para a interface se atualizar. */
    @Volatile
    var listener: (() -> Unit)? = null

    /**
     * `false` interrompe a gravacao na caixa de log da tela; o Logcat e as
     * linhas de erro/resultado ([addError]) continuam sempre.
     */
    @Volatile
    var enabled = true

    /** Acrescenta a linha `rotulo: valor`. */
    fun add(label: String, value: String) = add("$label: $value")

    /** Acrescenta uma linha ja formatada. */
    fun add(line: String) = record(line, important = false)

    /** Acrescenta a linha `rotulo: valor` mesmo com a gravacao desligada. */
    fun addError(label: String, value: String) = record("$label: $value", important = true)

    /** Acrescenta uma linha ja formatada mesmo com a gravacao desligada. */
    fun addError(line: String) = record(line, important = true)

    private fun record(line: String, important: Boolean) {
        if (enabled || important) {
            synchronized(lines) {
                lines.addLast(line)
                while (lines.size > limit) lines.removeFirst()
            }
            listener?.invoke()
        }
        Log.i(ClickAccessibilityService.TAG, line)
    }

    /** Linhas atuais, da mais antiga para a mais recente. */
    fun lines(): List<String> = synchronized(lines) { lines.toList() }

    /** Conteudo completo, pronto para exibir ou copiar. */
    fun text(): String = lines().joinToString("\n")

    fun clear() {
        synchronized(lines) { lines.clear() }
        listener?.invoke()
    }

    companion object {
        const val MAX_LINES = 500
    }
}

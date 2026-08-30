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

    /** Acrescenta a linha `rotulo: valor`. */
    fun add(label: String, value: String) = add("$label: $value")

    /** Acrescenta uma linha ja formatada. */
    fun add(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > limit) lines.removeFirst()
        }
        Log.i(ClickAccessibilityService.TAG, line)
        listener?.invoke()
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

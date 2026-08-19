package com.example.automacaocliques

/**
 * Passo de uma sequencia de cliques. [delayMs] e a espera antes de executar o
 * passo, contada a partir do passo anterior.
 */
sealed class ClickStep {
    abstract val delayMs: Long

    /** Clique em coordenadas absolutas da tela. */
    data class AtPoint(
        val x: Float,
        val y: Float,
        override val delayMs: Long = DEFAULT_DELAY_MS
    ) : ClickStep()

    /** Clique no elemento da tela que satisfaz [selector], resolvido na hora. */
    data class OnNode(
        val selector: NodeSelector,
        override val delayMs: Long = DEFAULT_DELAY_MS
    ) : ClickStep()

    companion object {
        const val DEFAULT_DELAY_MS = 1_000L

        /**
         * Sequencia a partir de termos separados por virgula (casando texto,
         * content description ou view id), um clique por item, todos com o mesmo
         * [delayMs]. O primeiro passo pode ter um atraso maior ([firstDelayMs])
         * para dar tempo de abrir a tela alvo.
         */
        fun fromTerms(
            input: String,
            delayMs: Long = DEFAULT_DELAY_MS,
            firstDelayMs: Long = delayMs
        ): List<ClickStep> = input.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapIndexed { index, term ->
                OnNode(
                    selector = NodeSelector(term = term),
                    delayMs = if (index == 0) firstDelayMs else delayMs
                )
            }
    }
}

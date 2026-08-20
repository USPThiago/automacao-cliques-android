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

    /**
     * Clique no centro da regiao onde o template [name] for reconhecido na
     * captura de tela. Usado em telas sem arvore de acessibilidade (jogos).
     */
    data class OnTemplate(
        val name: String,
        val threshold: Double = TemplateMatcher.DEFAULT_THRESHOLD,
        override val delayMs: Long = DEFAULT_DELAY_MS
    ) : ClickStep()

    companion object {
        const val DEFAULT_DELAY_MS = 1_000L

        /** Prefixo que identifica um termo como nome de template visual. */
        const val TEMPLATE_PREFIX = "@"

        /**
         * Sequencia a partir de termos separados por virgula, um clique por item,
         * todos com o mesmo [delayMs]. Termos comuns casam texto, content
         * description ou view id; termos com o prefixo `@` sao reconhecidos por
         * imagem (ex.: `@a_batalha`). O primeiro passo pode ter um atraso maior
         * ([firstDelayMs]) para dar tempo de abrir a tela alvo.
         */
        fun fromTerms(
            input: String,
            delayMs: Long = DEFAULT_DELAY_MS,
            firstDelayMs: Long = delayMs
        ): List<ClickStep> = input.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapIndexed { index, term ->
                val stepDelay = if (index == 0) firstDelayMs else delayMs
                if (term.startsWith(TEMPLATE_PREFIX)) {
                    OnTemplate(
                        name = term.removePrefix(TEMPLATE_PREFIX).trim(),
                        delayMs = stepDelay
                    )
                } else {
                    OnNode(selector = NodeSelector(term = term), delayMs = stepDelay)
                }
            }
    }
}

package com.example.automacaocliques

import org.junit.Assert.assertEquals
import org.junit.Test

class ClickStepTest {

    @Test
    fun `fromTerms ignora itens vazios e aplica atraso inicial diferente`() {
        val steps = ClickStep.fromTerms(" 5, ,0 , Iniciar ", delayMs = 500, firstDelayMs = 3_000)

        assertEquals(3, steps.size)
        assertEquals(
            listOf("5", "0", "Iniciar"),
            steps.map { (it as ClickStep.OnNode).selector.term }
        )
        assertEquals(listOf(3_000L, 500L, 500L), steps.map { it.delayMs })
    }

    @Test
    fun `fromTerms trata termos com arroba como templates visuais`() {
        val steps = ClickStep.fromTerms("@ batalha , Loja, @loja_fechar", delayMs = 500, firstDelayMs = 6_000)

        assertEquals(
            listOf("batalha", "loja_fechar"),
            steps.filterIsInstance<ClickStep.OnTemplate>().map { it.name }
        )
        assertEquals(
            listOf("Loja"),
            steps.filterIsInstance<ClickStep.OnNode>().map { it.selector.term }
        )
        assertEquals(
            TemplateMatcher.DEFAULT_THRESHOLD,
            (steps.first() as ClickStep.OnTemplate).threshold,
            1e-9
        )
        assertEquals(listOf(6_000L, 500L, 500L), steps.map { it.delayMs })
    }

    @Test
    fun `fromTerms retorna lista vazia para entrada sem termos`() {
        assertEquals(emptyList<ClickStep>(), ClickStep.fromTerms("  ,  , "))
    }
}

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
    fun `fromTerms retorna lista vazia para entrada sem termos`() {
        assertEquals(emptyList<ClickStep>(), ClickStep.fromTerms("  ,  , "))
    }
}

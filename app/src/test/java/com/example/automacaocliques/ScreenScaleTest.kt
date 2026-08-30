package com.example.automacaocliques

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenScaleTest {

    @Test
    fun `sem referencia o fator e 1`() {
        val scale = ScreenScale(real = Size(1080, 2400), reference = null)
        assertEquals(1.0, scale.factorX, 1e-9)
        assertEquals(1.0, scale.factorY, 1e-9)
        assertEquals(100, scale.scaleX(100))
        assertEquals(Area(1, 2, 3, 4), scale.scale(Area(1, 2, 3, 4)))
    }

    @Test
    fun `converte pontos e areas para a resolucao real`() {
        val scale = ScreenScale(real = Size(1080, 2400), reference = Size(540, 1200))
        assertEquals(2.0, scale.factorX, 1e-9)
        assertEquals(2.0, scale.factorY, 1e-9)
        assertEquals(200, scale.scaleX(100))
        assertEquals(400, scale.scaleY(200))
        assertEquals(Area(20, 40, 200, 400), scale.scale(Area(10, 20, 100, 200)))
        assertEquals(Size(64, 32), scale.scale(Size(32, 16)))
    }

    @Test
    fun `arredonda pelo vizinho mais proximo`() {
        val scale = ScreenScale(real = Size(1080, 2400), reference = Size(720, 1600))
        assertEquals(15, scale.scaleX(10))
        assertEquals(17, scale.scaleY(11))
    }
}

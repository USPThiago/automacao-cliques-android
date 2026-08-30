package com.example.automacaocliques

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Busca restrita a uma regiao e parada antecipada por escore (fase 1). */
class TemplateMatcherAreaTest {

    @Test
    fun `resultado dentro da area vem com as coordenadas da tela inteira`() {
        val screen = uiLike(160, 240)
        val template = crop(screen, left = 96, top = 160, width = 32, height = 32)

        val match = TemplateMatcher.findBest(
            screen,
            template,
            area = Area(80, 144, 160, 240)
        )

        assertNotNull(match)
        assertEquals(96, match!!.left)
        assertEquals(160, match.top)
        assertTrue("escore baixo: ${match.score}", match.score > 0.99)
    }

    @Test
    fun `ocorrencia fora da area nao e encontrada`() {
        val screen = uiLike(160, 240)
        val template = crop(screen, left = 96, top = 160, width = 32, height = 32)

        val match = TemplateMatcher.findBest(
            screen,
            template,
            area = Area(0, 0, 80, 80)
        )

        // O resultado, se houver, precisa estar dentro da area pedida.
        assertTrue(
            "casou fora da area: ${match?.describe()}",
            match == null || (match.left + match.width <= 80 && match.top + match.height <= 80)
        )
    }

    @Test
    fun `area e recortada aos limites da tela`() {
        val screen = uiLike(120, 160)
        val template = crop(screen, left = 8, top = 8, width = 32, height = 32)

        val match = TemplateMatcher.findBest(
            screen,
            template,
            area = Area(-50, -50, 500, 500)
        )

        assertNotNull(match)
        assertEquals(8, match!!.left)
        assertEquals(8, match.top)
    }

    @Test
    fun `area degenerada nao produz resultado`() {
        val screen = uiLike(120, 160)
        assertNull(TemplateMatcher.findBest(screen, crop(screen, 0, 0, 16, 16), area = Area(10, 10, 11, 11)))
    }

    @Test
    fun `early exit interrompe a busca no primeiro escore suficiente`() {
        val screen = uiLike(160, 240)
        val template = crop(screen, left = 48, top = 96, width = 32, height = 32)

        // Com o limite em 1.1 nenhum candidato satisfaz a parada antecipada, logo
        // todas as escalas sao refinadas; o resultado precisa ser o mesmo.
        val comExit = TemplateMatcher.findBest(screen, template, listOf(1.0), null, 0.90)
        val semExit = TemplateMatcher.findBest(screen, template, listOf(1.0), null, 1.1)

        assertNotNull(comExit)
        assertNotNull(semExit)
        assertEquals(semExit!!.left, comExit!!.left)
        assertEquals(semExit.top, comExit.top)
    }

    @Test
    fun `early exit devolve a primeira escala boa o bastante`() {
        val screen = uiLike(160, 240)
        val template = crop(screen, left = 48, top = 96, width = 32, height = 32)

        val match = TemplateMatcher.findBest(screen, template, listOf(1.0, 2.0), null, 0.95)

        assertNotNull(match)
        assertEquals(1.0, match!!.scale, 1e-9)
    }

    private fun uiLike(width: Int, height: Int, seed: Int = 3): GrayImage {
        val random = Random(seed)
        val blocksX = (width + BLOCK - 1) / BLOCK
        val blocks = IntArray(blocksX * ((height + BLOCK - 1) / BLOCK)) { random.nextInt(256) }
        return GrayImage(width, height, IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            blocks[(y / BLOCK) * blocksX + (x / BLOCK)]
        })
    }

    private fun crop(image: GrayImage, left: Int, top: Int, width: Int, height: Int): GrayImage =
        image.crop(Area(left, top, left + width, top + height))

    private companion object {
        const val BLOCK = 16
    }
}

package com.example.automacaocliques

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMatcherTest {

    @Test
    fun `template identico a tela tem escore proximo de 1`() {
        val screen = uiLike(120, 200)
        val match = TemplateMatcher.findBest(screen, screen, scales = listOf(1.0))

        assertNotNull(match)
        assertEquals(1.0, match!!.score, 1e-6)
        assertEquals(0, match.left)
        assertEquals(0, match.top)
    }

    @Test
    fun `encontra recorte na posicao esperada`() {
        val screen = uiLike(160, 240)
        val template = crop(screen, left = 48, top = 96, width = 40, height = 32)

        val match = TemplateMatcher.findBest(screen, template, scales = listOf(1.0))

        assertNotNull(match)
        assertTrue("escore baixo: ${match!!.score}", match.score > 0.99)
        assertEquals(48, match.left)
        assertEquals(96, match.top)
        assertEquals(68f, match.centerX, 0.01f)
        assertEquals(112f, match.centerY, 0.01f)
    }

    @Test
    fun `escore cai quando o alvo nao esta na tela`() {
        val screen = noise(120, 120, seed = 1)
        val template = noise(24, 24, seed = 99)

        val match = TemplateMatcher.findBest(screen, template, scales = listOf(1.0))

        assertNotNull(match)
        assertTrue("escore alto demais: ${match!!.score}", match.score < TemplateMatcher.DEFAULT_THRESHOLD)
    }

    @Test
    fun `brilho uniforme nao altera o casamento`() {
        val screen = uiLike(120, 160)
        val template = crop(screen, left = 30, top = 40, width = 32, height = 32)
        val darker = GrayImage(
            template.width,
            template.height,
            IntArray(template.pixels.size) { (template.pixels[it] / 2).coerceAtLeast(0) }
        )

        val match = TemplateMatcher.findBest(screen, darker, scales = listOf(1.0))

        assertNotNull(match)
        assertTrue("escore baixo: ${match!!.score}", match.score > 0.99)
        assertEquals(30, match.left)
        assertEquals(40, match.top)
    }

    @Test
    fun `template maior que a tela nao produz resultado`() {
        val match = TemplateMatcher.findBest(noise(32, 32), noise(64, 64), scales = listOf(1.0))
        assertNull(match)
    }

    @Test
    fun `template sem variacao nao produz resultado`() {
        val flat = GrayImage(16, 16, IntArray(16 * 16) { 200 })
        assertNull(TemplateMatcher.findBest(noise(64, 64), flat, scales = listOf(1.0)))
    }

    @Test
    fun `encontra recorte redimensionado por escala`() {
        val screen = uiLike(160, 240)
        val template = crop(screen, left = 40, top = 80, width = 48, height = 48)
            .resize(24, 24)

        val match = TemplateMatcher.findBest(screen, template, scales = listOf(1.0, 2.0))

        assertNotNull(match)
        assertTrue("escore baixo: ${match!!.score}", match.score > TemplateMatcher.DEFAULT_THRESHOLD)
        assertEquals(2.0, match.scale, 1e-9)
        assertEquals(64f, match.centerX, 4f)
        assertEquals(104f, match.centerY, 4f)
    }

    private fun noise(width: Int, height: Int, seed: Int = 7): GrayImage {
        val random = Random(seed)
        return GrayImage(width, height, IntArray(width * height) { random.nextInt(256) })
    }

    /**
     * Imagem com estrutura em blocos, parecida com uma interface real: a busca
     * grosseira depende de detalhes que sobrevivam a reducao da imagem.
     */
    private fun uiLike(width: Int, height: Int, seed: Int = 7): GrayImage {
        val random = Random(seed)
        val blocksX = (width + BLOCK - 1) / BLOCK
        val blocks = IntArray(blocksX * ((height + BLOCK - 1) / BLOCK)) { random.nextInt(256) }
        return GrayImage(width, height, IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            blocks[(y / BLOCK) * blocksX + (x / BLOCK)]
        })
    }

    private fun crop(image: GrayImage, left: Int, top: Int, width: Int, height: Int): GrayImage {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = image[left + x, top + y]
            }
        }
        return GrayImage(width, height, pixels)
    }

    private companion object {
        const val BLOCK = 16
    }
}

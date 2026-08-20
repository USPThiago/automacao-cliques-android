package com.example.automacaocliques

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GrayImageTest {

    @Test
    fun `converte argb em luminancia`() {
        val image = GrayImage.fromArgb(
            width = 3,
            height = 1,
            argb = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt())
        )

        assertEquals(255, image[0, 0])
        assertEquals(0, image[1, 0])
        assertEquals(76, image[2, 0])
    }

    @Test
    fun `rejeita quantidade de pixels incompativel`() {
        assertThrows(IllegalArgumentException::class.java) {
            GrayImage(2, 2, IntArray(3))
        }
    }

    @Test
    fun `downscale reduz as dimensoes pelo fator`() {
        val image = GrayImage(8, 4, IntArray(32) { it })
        val reduced = image.downscale(2)

        assertEquals(4, reduced.width)
        assertEquals(2, reduced.height)
        assertSame(image, image.downscale(1))
    }

    @Test
    fun `resize amostra o pixel mais proximo`() {
        val image = GrayImage(2, 2, intArrayOf(10, 20, 30, 40))
        val bigger = image.resize(4, 4)

        assertEquals(4, bigger.width)
        assertEquals(10, bigger[0, 0])
        assertEquals(20, bigger[3, 0])
        assertEquals(30, bigger[0, 3])
        assertEquals(40, bigger[3, 3])
        assertSame(image, image.resize(2, 2))
    }
}

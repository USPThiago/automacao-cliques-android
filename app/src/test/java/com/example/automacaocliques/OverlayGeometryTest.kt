package com.example.automacaocliques

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    private val portrait = Size(1080, 2400)

    @Test
    fun `janela na origem e do tamanho do display nao altera as coordenadas`() {
        val geometry = OverlayGeometry.identity(portrait)
        assertTrue(geometry.isIdentity)
        assertEquals(Area(100, 200, 300, 400), geometry.toCanvas(Area(100, 200, 300, 400)))
        assertEquals(ClickPoint(540, 1200), geometry.toCanvas(ClickPoint(540, 1200)))
    }

    @Test
    fun `janela abaixo da barra de status desloca as coordenadas para cima`() {
        // A janela comeca 120 px abaixo do topo (status bar) e termina antes da
        // barra de navegacao: o canvas e menor que o display.
        val geometry = OverlayGeometry(
            capture = portrait,
            display = portrait,
            window = Area(0, 120, 1080, 2400 - 150)
        )
        assertFalse(geometry.isIdentity)
        assertEquals(Area(100, 80, 300, 280), geometry.toCanvas(Area(100, 200, 300, 400)))
        assertEquals(ClickPoint(540, 1080), geometry.toCanvas(ClickPoint(540, 1200)))
    }

    @Test
    fun `janela afastada de um recorte lateral desloca no eixo x`() {
        val geometry = OverlayGeometry(
            capture = portrait,
            display = portrait,
            window = Area(80, 0, 1080, 2400)
        )
        assertEquals(Area(20, 200, 220, 400), geometry.toCanvas(Area(100, 200, 300, 400)))
    }

    @Test
    fun `paisagem com barras nas duas laterais`() {
        val landscape = Size(2400, 1080)
        val geometry = OverlayGeometry(
            capture = landscape,
            display = landscape,
            window = Area(120, 0, 2400 - 150, 1080)
        )
        assertEquals(Area(0, 0, 2130, 1080), geometry.toCanvas(Area(120, 0, 2250, 1080)))
        assertEquals(ClickPoint(-120, 540, 7L), geometry.toCanvas(ClickPoint(0, 540, 7L)))
    }

    @Test
    fun `captura menor que o display e escalonada antes do deslocamento`() {
        // Captura na metade da resolucao do display; a janela ainda comeca 100 px abaixo.
        val geometry = OverlayGeometry(
            capture = Size(540, 1200),
            display = portrait,
            window = Area(0, 100, 1080, 2400)
        )
        assertFalse(geometry.isIdentity)
        assertEquals(Area(200, 300, 600, 700), geometry.toCanvas(Area(100, 200, 300, 400)))
    }

    @Test
    fun `captura do tamanho do display nao reaplica escala`() {
        // A conversao depende apenas da captura e da janela: nao usa a resolucao
        // de referencia da sessao (ScreenScale), que ja foi aplicada ao match.
        val geometry = OverlayGeometry(
            capture = portrait,
            display = portrait,
            window = Area(0, 0, 1080, 2400)
        )
        assertEquals(Area(0, 0, 1080, 2400), geometry.toCanvas(Area(0, 0, 1080, 2400)))
    }
}

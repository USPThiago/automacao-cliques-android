package com.example.automacaocliques

import kotlin.math.roundToInt

/**
 * Conversao das coordenadas da captura (espaco do display, com origem no canto
 * superior esquerdo fisico) para o `Canvas` de uma janela de sobreposicao.
 *
 * A janela pode nao comecar em `(0, 0)` do display - quando o sistema a afasta
 * da barra de status, da navegacao ou de um recorte - e a captura pode ter
 * dimensoes diferentes do display. [window] e o retangulo que a janela ocupa no
 * display, em pixels; `(0, 0)` do canvas coincide com `(window.left, window.top)`.
 */
class OverlayGeometry(
    /** Dimensoes da captura em que as areas foram medidas. */
    val capture: Size,
    /** Dimensoes reais do display, incluindo as barras do sistema. */
    val display: Size,
    /** Posicao e tamanho da janela de sobreposicao no display. */
    val window: Area
) {

    private val factorX: Double = display.width.toDouble() / capture.width
    private val factorY: Double = display.height.toDouble() / capture.height

    /** `true` quando o canvas e o display coincidem e nenhuma conversao e necessaria. */
    val isIdentity: Boolean
        get() = capture == display && window == Area(0, 0, display.width, display.height)

    fun toCanvasX(x: Int): Int = (x * factorX).roundToInt() - window.left

    fun toCanvasY(y: Int): Int = (y * factorY).roundToInt() - window.top

    /** [area] da captura expressa nas coordenadas do canvas. */
    fun toCanvas(area: Area): Area = Area(
        left = toCanvasX(area.left),
        top = toCanvasY(area.top),
        right = toCanvasX(area.right),
        bottom = toCanvasY(area.bottom)
    )

    /** Ponto da captura expresso nas coordenadas do canvas. */
    fun toCanvas(point: ClickPoint): ClickPoint =
        ClickPoint(toCanvasX(point.x), toCanvasY(point.y), point.delayMs)

    companion object {
        /** Janela que cobre o display inteiro, para uma captura do mesmo tamanho. */
        fun identity(size: Size): OverlayGeometry =
            OverlayGeometry(size, size, Area(0, 0, size.width, size.height))
    }
}

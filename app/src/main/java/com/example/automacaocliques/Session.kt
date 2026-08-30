package com.example.automacaocliques

import java.util.Locale
import kotlin.math.roundToInt

/** Dimensoes em pixels. */
data class Size(val width: Int, val height: Int) {
    fun describe(): String = "${width}x$height"
}

/** Retangulo em pixels, com [right] e [bottom] exclusivos. */
data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** Recorta a area aos limites de [size]. */
    fun clipTo(size: Size): Area = Area(
        left = left.coerceIn(0, size.width),
        top = top.coerceIn(0, size.height),
        right = right.coerceIn(0, size.width),
        bottom = bottom.coerceIn(0, size.height)
    )

    fun describe(): String = "[$left,$top][$right,$bottom]"
}

/** Um toque de uma acao; [delayMs] tem precedencia sobre `clickIntervalMs`. */
data class ClickPoint(val x: Int, val y: Int, val delayMs: Long? = null)

/** Acao de uma sessao: localizar uma imagem e, se achar, clicar. */
data class SessionAction(
    val name: String,
    val locate: String,
    val threshold: Double = TemplateMatcher.DEFAULT_THRESHOLD,
    val scales: List<Double> = TemplateMatcher.DEFAULT_SCALES,
    val searchArea: Area? = null,
    val clicks: List<ClickPoint> = emptyList(),
    val clickIntervalMs: Long = DEFAULT_CLICK_INTERVAL_MS,
    val waitAfterMs: Long = DEFAULT_WAIT_AFTER_MS,
    val call: String? = null
) {
    companion object {
        const val DEFAULT_CLICK_INTERVAL_MS = 300L
        const val DEFAULT_WAIT_AFTER_MS = 1_000L
    }
}

/** Uma tela do roteiro: um conjunto de acoes avaliadas em ordem. */
data class Session(
    val name: String,
    val screen: Size? = null,
    val retries: Int = DEFAULT_RETRIES,
    val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    val actions: List<SessionAction>,
    /** Nome do arquivo de origem, usado nas mensagens de erro. */
    val fileName: String = ""
) {
    /** Total de tentativas: a primeira mais as retentativas. */
    val attempts: Int get() = 1 + retries

    companion object {
        const val DEFAULT_RETRIES = 3
        const val DEFAULT_RETRY_DELAY_MS = 1_000L
    }
}

/**
 * Conversao das coordenadas medidas em [reference] para a resolucao [real] da
 * tela. Quando a sessao nao declara `screen`, o fator e 1.0.
 */
class ScreenScale(val real: Size, val reference: Size?) {

    val factorX: Double = reference?.let { real.width.toDouble() / it.width } ?: 1.0
    val factorY: Double = reference?.let { real.height.toDouble() / it.height } ?: 1.0

    fun scaleX(value: Int): Int = (value * factorX).roundToInt()

    fun scaleY(value: Int): Int = (value * factorY).roundToInt()

    fun scale(area: Area): Area = Area(
        left = scaleX(area.left),
        top = scaleY(area.top),
        right = scaleX(area.right),
        bottom = scaleY(area.bottom)
    )

    /** Tamanho de um template apos a escala; usado para saber se ele cabe. */
    fun scale(size: Size): Size = Size(scaleX(size.width), scaleY(size.height))

    /** Valor da linha `Escala` do log. */
    fun describe(): String {
        if (reference == null) return String.format(Locale.ROOT, "%.3f (sem referencia)", 1.0)
        return String.format(
            Locale.ROOT,
            "%.3f (%s -> %s)",
            factorX,
            reference.describe(),
            real.describe()
        )
    }
}

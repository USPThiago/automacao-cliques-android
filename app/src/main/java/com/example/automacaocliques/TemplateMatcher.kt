package com.example.automacaocliques

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Regiao da tela onde um template foi encontrado, com o escore de similaridade. */
data class TemplateMatch(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** Correlacao cruzada normalizada, de -1 a 1 (1 = identico). */
    val score: Double,
    /** Escala aplicada ao template para casar com a tela. */
    val scale: Double
) {
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    fun describe(): String =
        "score=%.3f escala=%.2f centro=(%.1f, %.1f) regiao=[%d,%d][%d,%d]".format(
            score, scale, centerX, centerY, left, top, left + width, top + height
        )
}

/**
 * Casamento de template por correlacao cruzada normalizada (NCC), robusta a
 * mudancas de brilho. A busca e feita em duas etapas: uma varredura grosseira
 * na imagem reduzida (testando as escalas pedidas, para tolerar telas de
 * resolucao diferente daquela em que o recorte foi feito) e um refinamento em
 * resolucao cheia ao redor dos melhores candidatos.
 *
 * O custo cresce com a area varrida e com o numero de escalas, entao o padrao e
 * uma unica escala e a busca pode ser restrita a uma regiao da tela.
 */
object TemplateMatcher {

    /** Escore minimo tratado como "encontrado" por padrao. */
    const val DEFAULT_THRESHOLD = 0.80

    /** Escalas testadas na busca grosseira. */
    val DEFAULT_SCALES = listOf(1.0)

    /** Escalas uteis quando o recorte veio de um aparelho de outra resolucao. */
    val WIDE_SCALES = listOf(1.0, 0.9, 1.1, 0.8, 1.25, 0.66, 1.5)

    /** Escore a partir do qual os demais candidatos deixam de ser refinados. */
    const val EARLY_EXIT_SCORE = 0.95

    private const val COARSE_FACTOR = 8

    /**
     * Candidatos da busca grosseira refinados por escala. Refinar mais de um
     * evita perder o alvo quando a reducao confunde regioes parecidas.
     */
    private const val COARSE_CANDIDATES = 8

    /**
     * Melhor ocorrencia de [template] dentro de [area] (tela inteira quando
     * ausente), ou `null` se o template nao couber. Ao encontrar um escore
     * maior ou igual a [earlyExitScore] a busca para: em telas reais nao ha
     * ocorrencia melhor a procurar e o refinamento dos demais candidatos so
     * consumiria tempo.
     */
    fun findBest(
        screen: GrayImage,
        template: GrayImage,
        scales: List<Double> = DEFAULT_SCALES,
        area: Area? = null,
        earlyExitScore: Double = EARLY_EXIT_SCORE
    ): TemplateMatch? {
        val clipped = area?.clipTo(Size(screen.width, screen.height))
        if (clipped != null && (clipped.width < 2 || clipped.height < 2)) return null
        val region = clipped?.let { screen.crop(it) } ?: screen
        val offsetX = clipped?.left ?: 0
        val offsetY = clipped?.top ?: 0
        return searchRegion(region, template, scales, earlyExitScore)
            ?.let { it.copy(left = it.left + offsetX, top = it.top + offsetY) }
    }

    private fun searchRegion(
        screen: GrayImage,
        template: GrayImage,
        scales: List<Double>,
        earlyExitScore: Double
    ): TemplateMatch? {
        val coarseScreen = screen.downscale(COARSE_FACTOR)
        var best: TemplateMatch? = null

        for (scale in scales) {
            val coarseWidth = (template.width * scale / COARSE_FACTOR).roundToInt()
            val coarseHeight = (template.height * scale / COARSE_FACTOR).roundToInt()
            if (coarseWidth < 2 || coarseHeight < 2) continue
            if (coarseWidth > coarseScreen.width || coarseHeight > coarseScreen.height) continue

            val coarseTemplate = template.resize(coarseWidth, coarseHeight)
            val candidates = searchTop(
                coarseScreen,
                coarseTemplate,
                0,
                0,
                coarseScreen.width,
                coarseScreen.height,
                COARSE_CANDIDATES
            )

            for (candidate in candidates) {
                val refined = refine(screen, template, scale, candidate.left, candidate.top) ?: continue
                if (best == null || refined.score > best.score) best = refined
                if (refined.score >= earlyExitScore) return best
            }
        }
        return best
    }

    /**
     * Refina o candidato grosseiro em resolucao cheia, buscando numa janela de
     * +-[COARSE_FACTOR] pixels ao redor da posicao aproximada.
     */
    private fun refine(
        screen: GrayImage,
        template: GrayImage,
        scale: Double,
        coarseLeft: Int,
        coarseTop: Int
    ): TemplateMatch? {
        val width = (template.width * scale).roundToInt()
        val height = (template.height * scale).roundToInt()
        if (width < 2 || height < 2 || width > screen.width || height > screen.height) return null

        val scaled = template.resize(width, height)
        val fromX = (coarseLeft * COARSE_FACTOR - COARSE_FACTOR).coerceIn(0, screen.width - width)
        val fromY = (coarseTop * COARSE_FACTOR - COARSE_FACTOR).coerceIn(0, screen.height - height)
        val toX = (fromX + 2 * COARSE_FACTOR).coerceAtMost(screen.width - width)
        val toY = (fromY + 2 * COARSE_FACTOR).coerceAtMost(screen.height - height)

        return search(screen, scaled, fromX, fromY, toX + width, toY + height)
            ?.copy(scale = scale)
    }

    /**
     * Varre as posicoes cujo canto superior esquerdo esta em
     * `[fromX, limitX - template.width]` x `[fromY, limitY - template.height]`.
     */
    private fun search(
        image: GrayImage,
        template: GrayImage,
        fromX: Int,
        fromY: Int,
        limitX: Int,
        limitY: Int
    ): TemplateMatch? = searchTop(image, template, fromX, fromY, limitX, limitY, 1).firstOrNull()

    /** Como [search], mas devolve os [limit] melhores candidatos em ordem de escore. */
    private fun searchTop(
        image: GrayImage,
        template: GrayImage,
        fromX: Int,
        fromY: Int,
        limitX: Int,
        limitY: Int,
        limit: Int
    ): List<TemplateMatch> {
        val lastX = limitX.coerceAtMost(image.width) - template.width
        val lastY = limitY.coerceAtMost(image.height) - template.height
        if (lastX < fromX || lastY < fromY) return emptyList()

        val count = template.width * template.height
        var sumT = 0L
        var sumTT = 0L
        for (value in template.pixels) {
            sumT += value
            sumTT += value.toLong() * value
        }
        val meanT = sumT.toDouble() / count
        val varianceT = sumTT.toDouble() / count - meanT * meanT
        if (varianceT <= 1e-6) return emptyList()

        val found = ArrayList<TemplateMatch>((lastX - fromX + 1) * (lastY - fromY + 1))
        for (top in fromY..lastY) {
            for (left in fromX..lastX) {
                found += TemplateMatch(
                    left = left,
                    top = top,
                    width = template.width,
                    height = template.height,
                    score = correlate(image, template, left, top, count, meanT, varianceT),
                    scale = 1.0
                )
            }
        }
        return found.sortedByDescending { it.score }.take(limit)
    }

    private fun correlate(
        image: GrayImage,
        template: GrayImage,
        left: Int,
        top: Int,
        count: Int,
        meanT: Double,
        varianceT: Double
    ): Double {
        var sumS = 0L
        var sumSS = 0L
        var sumST = 0L
        for (y in 0 until template.height) {
            var imageIndex = (top + y) * image.width + left
            var templateIndex = y * template.width
            for (x in 0 until template.width) {
                val s = image.pixels[imageIndex++]
                val t = template.pixels[templateIndex++]
                sumS += s
                sumSS += s.toLong() * s
                sumST += s.toLong() * t
            }
        }
        val meanS = sumS.toDouble() / count
        val varianceS = sumSS.toDouble() / count - meanS * meanS
        if (varianceS <= 1e-6) return 0.0
        val covariance = sumST.toDouble() / count - meanS * meanT
        return covariance / sqrt(varianceS * varianceT)
    }
}

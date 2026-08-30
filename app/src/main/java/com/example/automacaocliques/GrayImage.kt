package com.example.automacaocliques

/**
 * Imagem em tons de cinza (0..255), sem dependencia do Android, para permitir
 * o casamento de templates com custo previsivel e testes unitarios.
 */
class GrayImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    init {
        require(width > 0 && height > 0) { "dimensoes invalidas: ${width}x$height" }
        require(pixels.size == width * height) {
            "esperados ${width * height} pixels, recebidos ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    /** Reducao por um fator inteiro, usada nas buscas grosseiras. */
    fun downscale(factor: Int): GrayImage {
        require(factor >= 1) { "fator invalido: $factor" }
        if (factor == 1) return this
        return resize(
            newWidth = (width / factor).coerceAtLeast(1),
            newHeight = (height / factor).coerceAtLeast(1)
        )
    }

    /** Recorte de [area], que precisa estar contida na imagem. */
    fun crop(area: Area): GrayImage {
        require(area.left >= 0 && area.top >= 0 && area.right <= width && area.bottom <= height) {
            "area ${area.describe()} fora da imagem ${width}x$height"
        }
        require(area.width > 0 && area.height > 0) { "area vazia: ${area.describe()}" }
        if (area.width == width && area.height == height) return this
        val out = IntArray(area.width * area.height)
        for (y in 0 until area.height) {
            System.arraycopy(pixels, (area.top + y) * width + area.left, out, y * area.width, area.width)
        }
        return GrayImage(area.width, area.height, out)
    }

    /**
     * Redimensiona a imagem. Ao reduzir, cada pixel de saida e a media da area
     * correspondente na entrada (caso contrario detalhes finos desapareceriam e a
     * busca grosseira perderia o alvo); ao ampliar, usa o vizinho mais proximo.
     */
    fun resize(newWidth: Int, newHeight: Int): GrayImage {
        require(newWidth > 0 && newHeight > 0) { "dimensoes invalidas" }
        if (newWidth == width && newHeight == height) return this
        if (newWidth < width || newHeight < height) return average(newWidth, newHeight)
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val srcY = (y.toLong() * height / newHeight).toInt().coerceAtMost(height - 1)
            for (x in 0 until newWidth) {
                val srcX = (x.toLong() * width / newWidth).toInt().coerceAtMost(width - 1)
                out[y * newWidth + x] = pixels[srcY * width + srcX]
            }
        }
        return GrayImage(newWidth, newHeight, out)
    }

    /** Reducao por media de area (box filter). */
    private fun average(newWidth: Int, newHeight: Int): GrayImage {
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val startY = (y.toLong() * height / newHeight).toInt()
            val endY = (((y + 1).toLong() * height / newHeight).toInt()).coerceAtLeast(startY + 1)
            for (x in 0 until newWidth) {
                val startX = (x.toLong() * width / newWidth).toInt()
                val endX = (((x + 1).toLong() * width / newWidth).toInt()).coerceAtLeast(startX + 1)
                var sum = 0
                for (srcY in startY until endY.coerceAtMost(height)) {
                    var index = srcY * width + startX
                    for (srcX in startX until endX.coerceAtMost(width)) {
                        sum += pixels[index++]
                    }
                }
                val area = (endY.coerceAtMost(height) - startY) * (endX.coerceAtMost(width) - startX)
                out[y * newWidth + x] = sum / area
            }
        }
        return GrayImage(newWidth, newHeight, out)
    }

    companion object {
        /** Converte pixels ARGB (ordem de [android.graphics.Bitmap.getPixels]) em luminancia. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            val gray = IntArray(width * height)
            for (i in gray.indices) {
                val color = argb[i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                gray[i] = (r * 299 + g * 587 + b * 114) / 1000
            }
            return GrayImage(width, height, gray)
        }
    }
}

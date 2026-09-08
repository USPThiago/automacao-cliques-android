package com.example.automacaocliques

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Sobreposicao nao interativa criada pelo proprio servico de acessibilidade
 * ([WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]), desenhando em
 * amarelo a area onde o template foi pesquisado e em vermelho a regiao exata
 * onde ele foi localizado. Nao captura toques: cada [show] substitui o desenho
 * anterior e [hide] o remove. Todos os metodos devem rodar na thread principal.
 *
 * As areas chegam em coordenadas da captura; a janela e criada em tela cheia
 * ([DisplayOverlay.layoutParams]) e, por seguranca, [OverlayGeometry] corrige
 * qualquer deslocamento entre o canvas e o display antes de desenhar.
 */
class HighlightOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)

    private var root: HighlightView? = null

    /**
     * Desenha [search] (amarelo) e [match] (vermelho), substituindo o desenho
     * anterior. [screen] e o tamanho da captura em que as areas foram medidas.
     */
    fun show(search: Area, match: Area, screen: Size) {
        val view = root ?: createView() ?: return
        view.search = search
        view.match = match
        view.screen = screen
        view.invalidate()
    }

    private fun createView(): HighlightView? {
        val view = HighlightView(context)
        val params = DisplayOverlay.layoutParams(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        return try {
            windowManager.addView(view, params)
            root = view
            view
        } catch (e: RuntimeException) {
            Log.e(ClickAccessibilityService.TAG, "Falha ao mostrar os retangulos", e)
            null
        }
    }

    /** Remove a sobreposicao, se existir. Pode ser chamada mais de uma vez. */
    fun hide() {
        val view = root ?: return
        root = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            Log.w(ClickAccessibilityService.TAG, "Sobreposicao de retangulos ja removida", e)
        }
    }

    /** Tela cheia transparente com o retangulo da busca e o do match. */
    private class HighlightView(context: Context) : View(context) {

        var search: Area? = null
        var match: Area? = null
        var screen: Size? = null

        private val density = context.resources.displayMetrics.density

        private val searchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = Color.YELLOW
        }

        private val matchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = Color.RED
        }

        override fun onDraw(canvas: Canvas) {
            val geometry = DisplayOverlay.geometryOf(this, screen)
            search?.let { canvas.drawRect(geometry.toCanvas(it).rect(), searchPaint) }
            match?.let { canvas.drawRect(geometry.toCanvas(it).rect(), matchPaint) }
        }

        private fun Area.rect() = Rect(left, top, right, bottom)
    }
}

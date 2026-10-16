package com.example.automacaocliques

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.google.android.material.button.MaterialButton

/** Coordenadas de uma acao em edicao, medidas na tela [reference] (`null` = este aparelho). */
data class CoordinatePreview(
    val searchArea: Area?,
    val clickArea: Area?,
    val clicks: List<ClickPoint>,
    val reference: Size?,
    /** Numero exibido no primeiro marcador de [clicks]. */
    val firstNumber: Int = 1
)

/**
 * Previa somente leitura das coordenadas de uma acao, desenhada pelo servico de
 * acessibilidade ([WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]) no
 * mesmo padrao de [HighlightOverlay]: o desenho nao captura toques (o usuario
 * pode abrir o app alvo por baixo) e um botao flutuante separado fecha a previa.
 * Todos os metodos devem rodar na thread principal.
 */
class CoordinatePreviewOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var drawing: PreviewView? = null
    private var closeButton: View? = null

    fun show(preview: CoordinatePreview, onClose: () -> Unit) {
        hide()
        val view = PreviewView(context, preview)
        val button = MaterialButton(ContextThemeWrapper(context, R.style.Theme_AutomacaoCliques)).apply {
            setText(R.string.preview_close)
            setOnClickListener {
                hide()
                onClose()
            }
        }
        val buttonParams = DisplayOverlay.layoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96 * context.resources.displayMetrics.density).toInt()
        }
        try {
            windowManager.addView(view, DisplayOverlay.layoutParams(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))
            drawing = view
            windowManager.addView(button, buttonParams)
            closeButton = button
        } catch (e: RuntimeException) {
            Log.e(ClickAccessibilityService.TAG, "Falha ao mostrar a previa de coordenadas", e)
            hide()
        }
    }

    /** Remove a previa, se existir. Pode ser chamada mais de uma vez. */
    fun hide() {
        listOfNotNull(drawing, closeButton).forEach {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: RuntimeException) {
                Log.w(ClickAccessibilityService.TAG, "Previa de coordenadas ja removida", e)
            }
        }
        drawing = null
        closeButton = null
    }

    private class PreviewView(context: Context, private val preview: CoordinatePreview) : View(context) {

        private val density = context.resources.displayMetrics.density
        // Recalculados a cada desenho: a tela pode girar com a previa aberta.
        private val display get() = DisplayOverlay.displaySize(context)
        private val scale get() = ScreenScale(real = display, reference = preview.reference)

        private val searchFill = fill(Color.argb(60, 255, 214, 0))
        private val searchStroke = stroke(Color.rgb(255, 214, 0))
        private val clickFill = fill(Color.argb(60, 33, 150, 243))
        private val clickStroke = stroke(Color.rgb(33, 150, 243))
        private val markerFill = fill(Color.argb(220, 229, 57, 53))
        private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14 * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val bannerFill = fill(Color.argb(200, 0, 0, 0))
        private val bannerText = Paint(markerText).apply { textSize = 13 * density }

        private val warning: String? get() = preview.reference?.takeIf { it != display }?.let {
            context.getString(R.string.preview_scaled_warning, it.describe(), display.describe())
        }

        override fun onDraw(canvas: Canvas) {
            val display = display
            val scale = scale
            val geometry = DisplayOverlay.geometryOf(this, display)
            preview.searchArea?.let { draw(canvas, geometry.toCanvas(scale.scale(it)), searchFill, searchStroke) }
            preview.clickArea?.let { draw(canvas, geometry.toCanvas(scale.scale(it)), clickFill, clickStroke) }
            val radius = 12 * density
            preview.clicks.forEachIndexed { index, point ->
                val x = geometry.toCanvasX(scale.scaleX(point.x)).toFloat()
                val y = geometry.toCanvasY(scale.scaleY(point.y)).toFloat()
                canvas.drawCircle(x, y, radius, markerFill)
                canvas.drawText((preview.firstNumber + index).toString(), x, y - (markerText.ascent() + markerText.descent()) / 2, markerText)
            }
            warning?.let {
                val top = geometry.toCanvasY(0) + 48 * density
                canvas.drawRect(0f, top, width.toFloat(), top + 32 * density, bannerFill)
                canvas.drawText(it, width / 2f, top + 21 * density, bannerText)
            }
        }

        private fun draw(canvas: Canvas, area: Area, fill: Paint, stroke: Paint) {
            val rect = RectF(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat())
            canvas.drawRect(rect, fill)
            canvas.drawRect(rect, stroke)
        }

        private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

        private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            this.color = color
        }
    }
}

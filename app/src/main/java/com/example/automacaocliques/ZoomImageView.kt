package com.example.automacaocliques

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/** Imagem em tamanho real (1 px = 1 px) com zoom por pinca, arraste e toque duplo para restaurar. */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val transform = Matrix()
    private var zoom = 1f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                val factor = target / zoom
                zoom = target
                transform.postScale(factor, factor, detector.focusX, detector.focusY)
                imageMatrix = transform
                return true
            }
        }
    )

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                transform.postTranslate(-distanceX, -distanceY)
                imageMatrix = transform
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                reset()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    /** Volta ao tamanho real, centralizado (ou alinhado ao topo/esquerda se maior que a view). */
    fun reset() {
        val image = drawable ?: return
        zoom = 1f
        transform.reset()
        transform.postTranslate(
            ((width - image.intrinsicWidth) / 2f).coerceAtLeast(0f),
            ((height - image.intrinsicHeight) / 2f).coerceAtLeast(0f)
        )
        imageMatrix = transform
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    private companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 10f
    }
}

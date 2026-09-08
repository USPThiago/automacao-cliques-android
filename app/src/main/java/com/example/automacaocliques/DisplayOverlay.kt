package com.example.automacaocliques

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Janelas de sobreposicao que desenham em coordenadas do display: a janela deve
 * cobrir a tela inteira, incluindo barras do sistema e recortes, para que o
 * `(0, 0)` do canvas coincida com o `(0, 0)` da captura.
 */
object DisplayOverlay {

    /**
     * Parametros de uma janela `TYPE_ACCESSIBILITY_OVERLAY` em tela cheia. Em
     * API 30+ a janela nao e ajustada a nenhum inset e pode entrar nos recortes;
     * nas anteriores valem as flags `LAYOUT_IN_SCREEN`/`LAYOUT_NO_LIMITS`.
     */
    fun layoutParams(extraFlags: Int = 0): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                extraFlags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0)
            params.setFitInsetsSides(0)
            params.setFitInsetsIgnoringVisibility(true)
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return params
    }

    /** Dimensoes reais do display, incluindo status bar e navegacao. */
    fun displaySize(context: Context): Size {
        val windowManager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return Size(bounds.width(), bounds.height())
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Size(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Conversao das coordenadas da captura [capture] para o canvas de [view], a
     * partir da posicao real que a view ocupa no display depois do layout. Sem
     * layout ainda (largura zero), assume a identidade.
     */
    fun geometryOf(view: View, capture: Size?): OverlayGeometry {
        val display = displaySize(view.context)
        val measured = capture ?: display
        val window = if (view.width <= 0 || view.height <= 0) {
            Area(0, 0, display.width, display.height)
        } else {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            Area(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        return OverlayGeometry(capture = measured, display = display, window = window)
    }
}

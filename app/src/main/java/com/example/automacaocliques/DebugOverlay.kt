package com.example.automacaocliques

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.example.automacaocliques.databinding.DebugOverlayBinding

/**
 * Sobreposicao do modo debug, criada pelo proprio servico de acessibilidade
 * ([WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]) porque durante a
 * execucao a Activity esta em segundo plano. Em tela cheia desenha o retangulo
 * do template e um marcador em cada clique; o card com os dados fica na metade
 * da tela oposta ao clique. Todos os metodos devem rodar na thread principal.
 */
class DebugOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)

    private var root: FrameLayout? = null

    /** Resposta ainda nao dada do popup em exibicao. */
    private var pending: ((DebugChoice) -> Unit)? = null

    /** Mostra o popup de [step]; [onChoice] e chamado uma unica vez na resposta. */
    fun show(step: DebugStep, onChoice: (DebugChoice) -> Unit) {
        hide()
        val container = FrameLayout(context)
        container.addView(
            MarkerView(context, step),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val binding = DebugOverlayBinding.inflate(LayoutInflater.from(context), container, false)
        binding.sessionValue.text = step.sessionName
        binding.attemptValue.text = "${step.attempt} de ${step.attempts}"
        binding.actionValue.text = step.actionName
        binding.matchStartValue.text = "left=${step.match.left},top=${step.match.top}"
        binding.matchEndValue.text = "right=${step.match.right},bottom=${step.match.bottom}"
        binding.clicksValue.text = step.clicks.joinToString("\n") { "x=${it.x},y=${it.y}" }
        binding.nextValue.text = step.nextSession ?: context.getString(R.string.debug_end)

        pending = onChoice
        binding.okButton.setOnClickListener { answer(DebugChoice.CONTINUE) }
        binding.cancelButton.setOnClickListener { answer(DebugChoice.CANCEL) }

        val screenHeight = context.resources.displayMetrics.heightPixels
        val clickOnTop = step.clicks.all { it.y < screenHeight / 2 }
        container.addView(
            binding.root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or (if (clickOnTop) Gravity.BOTTOM else Gravity.TOP)
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(container, params)
            root = container
        } catch (e: RuntimeException) {
            Log.e(ClickAccessibilityService.TAG, "Falha ao mostrar a sobreposicao de debug", e)
            answer(DebugChoice.CANCEL)
        }
    }

    /** Responde [choice] ao popup em exibicao (uma unica vez) e o remove. */
    private fun answer(choice: DebugChoice) {
        val callback = pending
        pending = null
        hide()
        callback?.invoke(choice)
    }

    /** Fecha o popup como se o usuario tivesse tocado em Cancel (usado por Parar). */
    fun dismiss() {
        answer(DebugChoice.CANCEL)
    }

    /** Remove a sobreposicao, se existir. Pode ser chamado mais de uma vez. */
    fun hide() {
        val view = root ?: return
        root = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            Log.w(ClickAccessibilityService.TAG, "Sobreposicao de debug ja removida", e)
        }
    }

    /** Fundo escurecido, retangulo do template e marcador de cada clique. */
    private class MarkerView(context: Context, private val step: DebugStep) : View(context) {

        private val density = context.resources.displayMetrics.density

        private val dim = Paint().apply { color = 0x66000000 }

        private val matchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = Color.YELLOW
        }

        private val clickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            color = Color.RED
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val match = step.match
            canvas.drawRect(
                match.left.toFloat(),
                match.top.toFloat(),
                match.right.toFloat(),
                match.bottom.toFloat(),
                matchPaint
            )
            val radius = 16 * density
            val arm = 24 * density
            step.clicks.forEach { click ->
                val x = click.x.toFloat()
                val y = click.y.toFloat()
                canvas.drawCircle(x, y, radius, clickPaint)
                canvas.drawLine(x - arm, y, x + arm, y, clickPaint)
                canvas.drawLine(x, y - arm, x, y + arm, clickPaint)
            }
        }
    }
}

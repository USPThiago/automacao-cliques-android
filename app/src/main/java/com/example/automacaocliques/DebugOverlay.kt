package com.example.automacaocliques

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * do template; o card com os dados fica na metade da tela oposta ao clique.
 * Todos os metodos devem rodar na thread principal.
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

        // Os cliques estao em coordenadas da captura: a metade e a da captura.
        val clickOnTop = step.clicks.all { it.y < step.screen.height / 2 }
        container.addView(
            binding.root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or (if (clickOnTop) Gravity.BOTTOM else Gravity.TOP)
            )
        )

        val params = DisplayOverlay.layoutParams()
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

    /** Fundo escurecido e retangulo do template localizado. */
    private class MarkerView(context: Context, private val step: DebugStep) : View(context) {

        private val density = context.resources.displayMetrics.density

        private val dim = Paint().apply { color = 0x66000000 }

        private val matchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = Color.YELLOW
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            val match = DisplayOverlay.geometryOf(this, step.screen).toCanvas(step.match)
            canvas.drawRect(
                match.left.toFloat(),
                match.top.toFloat(),
                match.right.toFloat(),
                match.bottom.toFloat(),
                matchPaint
            )
        }
    }
}

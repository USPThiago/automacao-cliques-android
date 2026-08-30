package com.example.automacaocliques

/** Resultado de uma captura de tela. */
sealed class Capture {

    data class Ok(val image: GrayImage) : Capture() {
        val size: Size get() = Size(image.width, image.height)
    }

    data class Failed(val errorCode: Int) : Capture()
}

/** Desfecho de um toque despachado. */
enum class ClickOutcome { COMPLETED, REJECTED, CANCELLED }

/** Desfecho de uma execucao completa. */
sealed class RunOutcome {

    object Success : RunOutcome()

    object Cancelled : RunOutcome()

    data class Failure(val reason: String) : RunOutcome()
}

/**
 * Tudo que o executor precisa do aparelho. Isolado numa interface para que a
 * maquina de estados possa ser testada na JVM com capturas simuladas.
 */
interface RunnerEnvironment {

    /** Captura sincrona da tela, ja em tons de cinza. */
    fun capture(): Capture

    /** Despacha um toque e espera o desfecho. */
    fun click(x: Float, y: Float): ClickOutcome

    /** Template [name] em tons de cinza, ou `null` se ausente. */
    fun templateOf(name: String): GrayImage?

    /** Sessao do arquivo [fileName], ou `null` se ilegivel. */
    fun loadSession(fileName: String): Session?

    fun sleep(ms: Long)

    /** Relogio monotonico, em milissegundos. */
    fun elapsedMs(): Long
}

/**
 * Percorre o grafo de sessoes: a cada tentativa captura a tela uma unica vez,
 * avalia as acoes em ordem e executa **apenas a primeira** cuja imagem for
 * localizada (opcao A). Ciclos entre sessoes sao permitidos; a parada natural e
 * a exaustao das tentativas de uma sessao.
 */
class SessionRunner(
    private val env: RunnerEnvironment,
    private val log: ExecutionLog
) {

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun run(main: Session): RunOutcome {
        cancelled = false
        var session = main

        while (true) {
            val sessionStart = env.elapsedMs()
            log.add("Sessao", session.name)

            var next: Session? = null
            var attempt = 1
            while (attempt <= session.attempts) {
                if (cancelled) return cancelledOutcome(sessionStart)
                log.add("Tentativa", "$attempt de ${session.attempts}")

                when (val outcome = attempt(session)) {
                    is AttemptOutcome.Executed -> {
                        log.add("Tempo total", "${env.elapsedMs() - sessionStart} ms")
                        val call = outcome.call
                            ?: return RunOutcome.Success
                        val fileName = SessionValidator.fileNameOf(call)
                        next = env.loadSession(fileName) ?: run {
                            log.add("Transicao", "NOK - sessao $call ilegivel")
                            return RunOutcome.Failure("sessao $call ilegivel")
                        }
                    }
                    is AttemptOutcome.Aborted -> {
                        if (cancelled) return cancelledOutcome(sessionStart)
                        log.add("Tempo total", "${env.elapsedMs() - sessionStart} ms")
                        return RunOutcome.Failure(outcome.reason)
                    }
                    AttemptOutcome.NothingFound -> {
                        attempt++
                        if (attempt <= session.attempts) pause(session.retryDelayMs)
                    }
                }
                if (next != null) break
            }

            if (next == null) {
                if (cancelled) return cancelledOutcome(sessionStart)
                val reason = "Sessao ${session.name}: nenhuma acao localizada em " +
                    "${session.attempts} tentativa(s) - encerrado"
                log.add(reason)
                log.add("Tempo total", "${env.elapsedMs() - sessionStart} ms")
                return RunOutcome.Failure(reason)
            }
            session = next
        }
    }

    private fun cancelledOutcome(sessionStart: Long): RunOutcome {
        log.add("Execucao", "interrompida pelo usuario")
        log.add("Tempo total", "${env.elapsedMs() - sessionStart} ms")
        return RunOutcome.Cancelled
    }

    /** Desfecho de uma tentativa (uma captura + a avaliacao das acoes). */
    private sealed class AttemptOutcome {

        /** Uma acao foi executada; [call] e a proxima sessao, se houver. */
        data class Executed(val call: String?) : AttemptOutcome()

        /** Falha que encerra a execucao (gesto rejeitado, captura impossivel). */
        data class Aborted(val reason: String) : AttemptOutcome()

        /** Nenhuma acao localizada: cabe retentativa. */
        object NothingFound : AttemptOutcome()
    }

    private fun attempt(session: Session): AttemptOutcome {
        val captureStart = env.elapsedMs()
        val capture = env.capture()
        log.add("Tempo captura", "${env.elapsedMs() - captureStart} ms")
        if (capture is Capture.Failed) {
            log.add("Transicao", "NOK - captura falhou (codigo=${capture.errorCode})")
            return AttemptOutcome.NothingFound
        }
        val screen = (capture as Capture.Ok).image
        val scale = ScreenScale(real = capture.size, reference = session.screen)

        for (action in session.actions) {
            if (cancelled) return AttemptOutcome.Aborted("interrompido")
            val actionStart = env.elapsedMs()
            log.add("Acao", action.name)

            val match = locate(screen, action, scale) ?: continue

            log.add("Escala", scale.describe())
            log.add("Posicao inicial", "x=${match.left},y=${match.top}")
            log.add("Posicao final", "x=${match.left + match.width},y=${match.top + match.height}")

            val clickOutcome = dispatchClicks(action, match, scale)
            if (clickOutcome != null) {
                log.add("Tempo acao", "${env.elapsedMs() - actionStart} ms")
                return AttemptOutcome.Aborted(clickOutcome)
            }
            pause(action.waitAfterMs)
            log.add("Transicao", "OK")
            log.add("Tempo acao", "${env.elapsedMs() - actionStart} ms")
            return AttemptOutcome.Executed(action.call)
        }
        return AttemptOutcome.NothingFound
    }

    /** Ocorrencia aceita do template da acao, ou `null` se nao localizada. */
    private fun locate(
        screen: GrayImage,
        action: SessionAction,
        scale: ScreenScale
    ): TemplateMatch? {
        val template = env.templateOf(action.locate)
        if (template == null) {
            log.add("Acao ${action.name}", "template '${action.locate}' ausente")
            return null
        }
        val screenSize = Size(screen.width, screen.height)
        val area = (action.searchArea?.let { scale.scale(it) } ?: Area(0, 0, screen.width, screen.height))
            .clipTo(screenSize)
        val scales = action.scales.map { it * scale.factorX }
        val smallest = scales.min()
        if (template.width * smallest > area.width || template.height * smallest > area.height) {
            log.add(
                "Acao ${action.name}",
                "template '${action.locate}' (${template.width}x${template.height}) " +
                    "nao cabe na area ${area.describe()}"
            )
            return null
        }

        val start = env.elapsedMs()
        val match = TemplateMatcher.findBest(screen, template, scales, area)
        val elapsed = env.elapsedMs() - start
        log.add("Tempo localizacao", "$elapsed ms")

        if (match == null || match.score < action.threshold) {
            log.add(
                "Acao ${action.name}",
                "nao localizada (melhor escore=%.3f, limite=%.2f)".format(
                    match?.score ?: 0.0,
                    action.threshold
                )
            )
            return null
        }
        return match
    }

    /** Despacha os cliques da acao; devolve o motivo da falha ou `null` se todos sairam. */
    private fun dispatchClicks(
        action: SessionAction,
        match: TemplateMatch,
        scale: ScreenScale
    ): String? {
        val points = action.clicks
        if (points.isEmpty()) {
            return dispatch(match.centerX, match.centerY)
        }
        points.forEachIndexed { index, point ->
            if (index > 0) pause(point.delayMs ?: action.clickIntervalMs)
            if (cancelled) return "interrompido"
            val failure = dispatch(scale.scaleX(point.x).toFloat(), scale.scaleY(point.y).toFloat())
            if (failure != null) return failure
        }
        return null
    }

    private fun dispatch(x: Float, y: Float): String? {
        log.add("Clique", "x=${x.toInt()},y=${y.toInt()}")
        return when (env.click(x, y)) {
            ClickOutcome.COMPLETED -> null
            ClickOutcome.REJECTED -> {
                log.add("Transicao", "NOK - gesto rejeitado")
                "gesto rejeitado"
            }
            ClickOutcome.CANCELLED -> {
                log.add("Transicao", "NOK - gesto cancelado")
                "gesto cancelado"
            }
        }
    }

    /** Espera [ms] em fatias, para que Parar tenha efeito rapido. */
    private fun pause(ms: Long) {
        var remaining = ms
        while (remaining > 0 && !cancelled) {
            val slice = minOf(remaining, PAUSE_SLICE_MS)
            env.sleep(slice)
            remaining -= slice
        }
    }

    private companion object {
        const val PAUSE_SLICE_MS = 100L
    }
}

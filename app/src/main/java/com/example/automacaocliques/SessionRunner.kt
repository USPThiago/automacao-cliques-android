package com.example.automacaocliques

import kotlin.random.Random

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

/** Informacoes mostradas no popup do modo debug depois dos cliques de uma acao. */
data class DebugStep(
    val sessionName: String,
    val attempt: Int,
    val attempts: Int,
    val actionName: String,
    /** Posicao do template na tela real. */
    val match: Area,
    /** Pontos efetivamente despachados, ja escalonados, na ordem. */
    val clicks: List<ClickPoint>,
    /** Proxima sessao (`call`), ou `null` quando a acao encerra o roteiro. */
    val nextSession: String?
)

/** Resposta do usuario ao popup do modo debug. */
enum class DebugChoice { CONTINUE, CANCEL }

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

    fun sleep(ms: Long)

    /** Relogio monotonico, em milissegundos. */
    fun elapsedMs(): Long

    /** `true` quando o modo debug esta ligado. */
    fun debugEnabled(): Boolean

    /** Mostra o popup de debug e bloqueia ate o usuario responder. */
    fun confirmStep(step: DebugStep): DebugChoice

    /** `true` quando os retangulos de busca/match devem ser desenhados na tela. */
    fun highlightsEnabled(): Boolean = false

    /**
     * Desenha [search] (area onde o template foi pesquisado) e [match] (regiao
     * localizada), substituindo o desenho anterior. Coordenadas na tela real.
     */
    fun showHighlight(search: Area, match: Area) = Unit

    /** Remove os retangulos, se exibidos. Chamado ao encerrar a execucao. */
    fun hideHighlights() = Unit
}

/**
 * Percorre o grafo de sessoes: a cada tentativa captura a tela uma unica vez,
 * avalia as acoes em ordem e executa **apenas a primeira** cuja imagem for
 * localizada (opcao A). Ciclos entre sessoes sao permitidos; a parada natural e
 * a exaustao das tentativas de uma sessao.
 */
class SessionRunner(
    private val env: RunnerEnvironment,
    private val log: ExecutionLog,
    /** Fonte de aleatoriedade do `clickArea`, injetavel nos testes. */
    private val random: Random = Random.Default
) {

    @Volatile
    private var cancelled = false

    /** Ultima resolucao registrada no log, para nao repetir a linha a cada captura. */
    private var loggedScreen: Size? = null

    /** Marca do inicio do processamento, para o `Tempo total` do resumo final. */
    private var runStart = 0L

    /** Instante do ultimo clique despachado; `null` antes do primeiro. */
    private var lastClickAt: Long? = null

    /** Cliques despachados no processamento inteiro, para o resumo final. */
    private var clicksSent = 0

    /** Sessoes `Resultado` iniciadas (cada passagem por ela conta), para o resumo. */
    private var resultadoSessions = 0

    fun cancel() {
        cancelled = true
    }

    /** Contadores da execucao, usados no resumo final do log. */
    data class RunStats(
        val resultadoSessions: Int,
        val clicksSent: Int,
        val elapsedMs: Long
    )

    /** Contadores do processamento; validos mesmo apos falha ou cancelamento. */
    fun stats(): RunStats =
        RunStats(resultadoSessions, clicksSent, env.elapsedMs() - runStart)

    /**
     * Executa o grafo ja validado na carga inicial: [sessions] mapeia nome de
     * arquivo para sessao e e a unica origem das transicoes, para que edicoes
     * feitas no aparelho durante a execucao nao escapem da validacao.
     */
    fun run(main: Session, sessions: Map<String, Session> = emptyMap()): RunOutcome {
        return try {
            runInternal(main, sessions)
        } catch (e: Exception) {
            val reason = "erro inesperado na execucao: ${e.message}"
            log.addError("Execucao", reason)
            RunOutcome.Failure(reason)
        } finally {
            if (env.highlightsEnabled()) env.hideHighlights()
        }
    }

    private fun runInternal(main: Session, sessions: Map<String, Session>): RunOutcome {
        var session = main
        runStart = env.elapsedMs()

        while (true) {
            if (session.name == RESULTADO_SESSION) resultadoSessions++
            log.add("Sessao", session.name)

            var next: Session? = null
            var attempt = 1
            while (attempt <= session.attempts) {
                if (cancelled) return cancelledOutcome()
                log.add("Tentativa", "$attempt de ${session.attempts}")

                when (val outcome = attempt(session, attempt)) {
                    is AttemptOutcome.Executed -> {
                        val call = outcome.call
                            ?: return RunOutcome.Success
                        val fileName = SessionValidator.fileNameOf(call)
                        next = sessions[fileName] ?: run {
                            log.addError("Transicao", "NOK - sessao $call ilegivel")
                            return RunOutcome.Failure("sessao $call ilegivel")
                        }
                    }
                    is AttemptOutcome.Aborted -> {
                        if (cancelled) return cancelledOutcome()
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
                if (cancelled) return cancelledOutcome()
                val reason = "Sessao ${session.name}: nenhuma acao localizada em " +
                    "${session.attempts} tentativa(s) - encerrado"
                log.addError(reason)
                return RunOutcome.Failure(reason)
            }
            session = next
        }
    }

    private fun cancelledOutcome(): RunOutcome {
        log.addError("Execucao", "interrompida pelo usuario")
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

    private fun attempt(session: Session, attempt: Int): AttemptOutcome {
        // Os retangulos nao podem aparecer na captura: tiram-se antes e o
        // proximo match os desenha de novo.
        if (env.highlightsEnabled()) env.hideHighlights()
        val captureStart = env.elapsedMs()
        val capture = env.capture()
        log.add("Tempo captura", "${env.elapsedMs() - captureStart} ms")
        if (capture is Capture.Failed) {
            log.addError("Transicao", "NOK - captura falhou (codigo=${capture.errorCode})")
            return AttemptOutcome.NothingFound
        }
        val screen = (capture as Capture.Ok).image
        if (loggedScreen != capture.size) {
            log.add("Resolucao da tela", capture.size.describe())
            loggedScreen = capture.size
        }
        val scale = ScreenScale(real = capture.size, reference = session.screen)

        for (action in session.actions) {
            if (cancelled) return AttemptOutcome.Aborted("interrompido")

            // Acoes que nao localizam o template nao geram linhas no log.
            val located = locate(screen, action, scale) ?: continue
            val match = located.match

            log.add("Acao", action.name)
            log.add("Tempo localizacao", "${located.elapsedMs} ms")
            if (env.highlightsEnabled()) {
                env.showHighlight(located.area, match.area())
            }
            log.add("Escala", scale.describe())
            log.add(
                "Posicao",
                "left=${match.left},top=${match.top}," +
                    "right=${match.left + match.width},bottom=${match.top + match.height}"
            )

            val clicks = when (
                val outcome = dispatchClicks(action, match, scale, capture.size)
            ) {
                is ClicksOutcome.Failed -> return AttemptOutcome.Aborted(outcome.reason)
                is ClicksOutcome.Ok -> outcome.points
            }
            if (env.debugEnabled()) {
                log.add("Debug", "aguardando confirmacao")
                val step = DebugStep(
                    sessionName = session.name,
                    attempt = attempt,
                    attempts = session.attempts,
                    actionName = action.name,
                    match = match.area(),
                    clicks = clicks,
                    nextSession = action.call
                )
                if (env.confirmStep(step) == DebugChoice.CANCEL) {
                    cancel()
                    log.addError("Debug", "cancelado pelo usuario")
                    return AttemptOutcome.Aborted("cancelado no modo debug")
                }
            }
            pause(action.waitAfterMs)
            log.add("Transicao", "OK")
            return AttemptOutcome.Executed(action.call)
        }
        return AttemptOutcome.NothingFound
    }

    /** Match aceito do template da acao, a area varrida e o tempo da busca. */
    private data class Located(val match: TemplateMatch, val area: Area, val elapsedMs: Long)

    /** Ocorrencia aceita do template da acao, ou `null` se nao localizada. */
    private fun locate(
        screen: GrayImage,
        action: SessionAction,
        scale: ScreenScale
    ): Located? {
        val template = env.templateOf(action.locate)
        if (template == null) {
            log.addError("Acao ${action.name}", "template '${action.locate}' ausente")
            return null
        }
        val screenSize = Size(screen.width, screen.height)
        val area = (action.searchArea?.let { scale.scale(it) } ?: Area(0, 0, screen.width, screen.height))
            .clipTo(screenSize)
        val scales = action.scales.map { it * scale.templateFactor }
        val smallest = scales.min()
        if (template.width * smallest > area.width || template.height * smallest > area.height) {
            log.addError(
                "Acao ${action.name}",
                "template '${action.locate}' (${template.width}x${template.height}) " +
                    "nao cabe na area ${area.describe()}"
            )
            return null
        }

        val start = env.elapsedMs()
        val match = TemplateMatcher.findBest(
            screen,
            template,
            scales,
            area,
            maxOf(TemplateMatcher.EARLY_EXIT_SCORE, action.threshold)
        )

        if (match == null || match.score < action.threshold) return null
        return Located(match, area, env.elapsedMs() - start)
    }

    /** Desfecho dos cliques de uma acao. */
    private sealed class ClicksOutcome {

        /** Todos sairam; [points] sao as coordenadas reais despachadas, na ordem. */
        data class Ok(val points: List<ClickPoint>) : ClicksOutcome()

        data class Failed(val reason: String) : ClicksOutcome()
    }

    /**
     * Despacha os cliques da acao: um ponto aleatorio de `clickArea`, a lista
     * `clicks` ou, sem os dois, o centro do template localizado.
     */
    private fun dispatchClicks(
        action: SessionAction,
        match: TemplateMatch,
        scale: ScreenScale,
        screenSize: Size
    ): ClicksOutcome {
        val dispatched = mutableListOf<ClickPoint>()
        var first = true
        val clickArea = action.clickArea
        if (clickArea != null) {
            val area = scale.scale(clickArea).clipTo(screenSize)
            if (area.width <= 0 || area.height <= 0) {
                return ClicksOutcome.Failed("clickArea ${area.describe()} fora da tela")
            }
            val x = random.nextInt(area.left, area.right).toFloat()
            val y = random.nextInt(area.top, area.bottom).toFloat()
            dispatch(x, y, reportInterval = true)?.let { return ClicksOutcome.Failed(it) }
            dispatched += ClickPoint(x.toInt(), y.toInt())
            return ClicksOutcome.Ok(dispatched)
        }
        val points = action.clicks
        if (points.isEmpty()) {
            dispatch(match.centerX, match.centerY, reportInterval = true)
                ?.let { return ClicksOutcome.Failed(it) }
            dispatched += ClickPoint(match.centerX.toInt(), match.centerY.toInt())
            return ClicksOutcome.Ok(dispatched)
        }
        points.forEachIndexed { index, point ->
            if (index > 0) pause(point.delayMs ?: action.clickIntervalMs)
            if (cancelled) return ClicksOutcome.Failed("interrompido")
            val x = scale.scaleX(point.x).toFloat()
            val y = scale.scaleY(point.y).toFloat()
            dispatch(x, y, reportInterval = first)?.let { return ClicksOutcome.Failed(it) }
            first = false
            dispatched += ClickPoint(x.toInt(), y.toInt())
        }
        return ClicksOutcome.Ok(dispatched)
    }

    /**
     * Despacha um toque, contando-o e registrando a linha `Clique`. Com
     * [reportInterval], registra antes o intervalo desde o ultimo clique da
     * sessao/acao anterior (omitido quando nao houve clique anterior).
     */
    private fun dispatch(x: Float, y: Float, reportInterval: Boolean): String? {
        val now = env.elapsedMs()
        if (reportInterval) {
            lastClickAt?.let { log.add("Tempo desde ultimo clique", "${now - it} ms") }
        }
        log.add("Clique", "x=${x.toInt()},y=${y.toInt()}")
        return when (env.click(x, y)) {
            ClickOutcome.COMPLETED -> {
                // So o gesto aceito conta como clique enviado e vira
                // referencia para o proximo intervalo.
                lastClickAt = now
                clicksSent++
                null
            }
            ClickOutcome.REJECTED -> {
                log.addError("Transicao", "NOK - gesto rejeitado")
                "gesto rejeitado"
            }
            ClickOutcome.CANCELLED -> {
                log.addError("Transicao", "NOK - gesto cancelado")
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

        /** Nome de sessao contado no `Total de salas` do resumo final. */
        const val RESULTADO_SESSION = "Resultado"
    }
}

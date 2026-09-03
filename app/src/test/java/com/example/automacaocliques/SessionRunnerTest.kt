package com.example.automacaocliques

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maquina de estados do executor com capturas simuladas: cada nome de template
 * ocupa uma posicao fixa na tela sintetica e a captura decide quais estao
 * visiveis naquele instante.
 */
class SessionRunnerTest {

    private val screenSize = Size(160, 240)

    private val slots = mapOf(
        "alvo_a" to Area(0, 0, 32, 32),
        "alvo_b" to Area(64, 96, 96, 128),
        "alvo_c" to Area(112, 176, 144, 208)
    )

    private val log = ExecutionLog()

    // --- ambiente simulado ---------------------------------------------------

    private class FakeEnv(
        val captures: MutableList<Capture>,
        val templates: Map<String, GrayImage>,
        val sessions: Map<String, Session>,
        val clickOutcome: (Int) -> ClickOutcome = { ClickOutcome.COMPLETED },
        val debugEnabled: Boolean = false,
        val debugChoice: (DebugStep) -> DebugChoice = { DebugChoice.CONTINUE }
    ) : RunnerEnvironment {

        val clicks = mutableListOf<Pair<Int, Int>>()
        val sleeps = mutableListOf<Long>()
        val debugSteps = mutableListOf<DebugStep>()

        /** Ordem dos eventos relevantes ao modo debug: `captura`, `clique`, `debug`. */
        val events = mutableListOf<String>()
        var captureCount = 0
        private var clock = 0L
        var onCapture: (() -> Unit)? = null

        override fun capture(): Capture {
            onCapture?.invoke()
            captureCount++
            events += "captura"
            return if (captures.size == 1) captures[0] else captures.removeAt(0)
        }

        override fun click(x: Float, y: Float): ClickOutcome {
            clicks += x.toInt() to y.toInt()
            events += "clique"
            return clickOutcome(clicks.size - 1)
        }

        override fun debugEnabled(): Boolean = debugEnabled

        override fun confirmStep(step: DebugStep): DebugChoice {
            debugSteps += step
            events += "debug"
            return debugChoice(step)
        }

        override fun templateOf(name: String): GrayImage? = templates[name]

        override fun sleep(ms: Long) {
            sleeps += ms
            clock += ms
        }

        override fun elapsedMs(): Long = clock++
    }

    private fun base(seed: Int = 5): GrayImage {
        val random = Random(seed)
        val blocksX = screenSize.width / BLOCK
        val blocks = IntArray(blocksX * (screenSize.height / BLOCK)) { random.nextInt(256) }
        return GrayImage(
            screenSize.width,
            screenSize.height,
            IntArray(screenSize.width * screenSize.height) { index ->
                val x = index % screenSize.width
                val y = index / screenSize.width
                blocks[(y / BLOCK) * blocksX + (x / BLOCK)]
            }
        )
    }

    /** Tela em que apenas os templates listados estao visiveis. */
    private fun screenWith(vararg visible: String): Capture.Ok {
        val pixels = base().pixels.copyOf()
        slots.forEach { (name, area) ->
            val patch = patchOf(name, visible.contains(name))
            for (y in 0 until area.height) {
                System.arraycopy(
                    patch.pixels,
                    y * area.width,
                    pixels,
                    (area.top + y) * screenSize.width + area.left,
                    area.width
                )
            }
        }
        return Capture.Ok(GrayImage(screenSize.width, screenSize.height, pixels))
    }

    /**
     * Mesma tela, mas com o dobro da resolucao em que os templates foram
     * recortados: e o caso que exercita o escalonamento por `screen`.
     */
    private fun screenAtDoubleResolution(vararg visible: String): Capture.Ok =
        Capture.Ok(screenWith(*visible).image.resize(screenSize.width * 2, screenSize.height * 2))

    /** Recorte caracteristico de [name]; quando ausente, ruido nao correlacionado. */
    private fun patchOf(name: String, present: Boolean): GrayImage {
        val area = slots.getValue(name)
        val random = Random(if (present) name.hashCode() else -name.hashCode())
        return GrayImage(
            area.width,
            area.height,
            IntArray(area.width * area.height) { random.nextInt(256) }
        )
    }

    private fun templates(): Map<String, GrayImage> =
        slots.keys.associateWith { patchOf(it, present = true) }

    private fun action(
        name: String,
        locate: String,
        call: String? = null,
        clicks: List<ClickPoint> = emptyList()
    ) = SessionAction(name = name, locate = locate, call = call, clicks = clicks, waitAfterMs = 0)

    private fun session(
        name: String,
        vararg actions: SessionAction,
        retries: Int = 0
    ) = Session(
        name = name,
        retries = retries,
        retryDelayMs = 10,
        actions = actions.toList(),
        fileName = "$name.json"
    )

    // --- testes --------------------------------------------------------------

    @Test
    fun `executa apenas a primeira acao localizada`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a", "alvo_b")),
            templates = templates(),
            sessions = emptyMap()
        )
        val main = session(
            "menu",
            action("primeira", "alvo_a"),
            action("segunda", "alvo_b")
        )

        val outcome = SessionRunner(env, log).run(main, env.sessions)

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(1, env.clicks.size)
        assertEquals(16 to 16, env.clicks.single())
    }

    @Test
    fun `pula acoes nao localizadas e executa a seguinte`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_b")),
            templates = templates(),
            sessions = emptyMap()
        )
        val main = session(
            "menu",
            action("primeira", "alvo_a"),
            action("segunda", "alvo_b")
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main, env.sessions))
        assertEquals(80 to 112, env.clicks.single())
    }

    @Test
    fun `clica nas coordenadas declaradas escalonadas pela tela`() {
        val env = FakeEnv(
            captures = mutableListOf(screenAtDoubleResolution("alvo_a")),
            templates = templates(),
            sessions = emptyMap()
        )
        val main = Session(
            name = "menu",
            screen = screenSize,
            retries = 0,
            actions = listOf(
                action(
                    "toque",
                    "alvo_a",
                    clicks = listOf(ClickPoint(10, 20), ClickPoint(30, 40, delayMs = 5))
                )
            ),
            fileName = "menu.json"
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main, env.sessions))
        assertEquals(listOf(20 to 40, 60 to 80), env.clicks)
        assertTrue("delayMs deveria prevalecer: ${env.sleeps}", env.sleeps.contains(5L))
    }

    @Test
    fun `log registra resolucao real posicao e cliques`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a")),
            templates = templates(),
            sessions = emptyMap()
        )
        val main = session(
            "menu",
            action("toque", "alvo_a", clicks = listOf(ClickPoint(10, 20), ClickPoint(30, 40)))
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main, env.sessions))
        val lines = log.lines()
        assertEquals(
            1,
            lines.count { it == "Resolucao da tela: ${screenSize.describe()}" }
        )
        assertTrue(lines.toString(), lines.contains("Posicao: left=0,top=0,right=32,bottom=32"))
        assertEquals(
            listOf("Clique: x=10,y=20", "Clique: x=30,y=40"),
            lines.filter { it.startsWith("Clique: ") }
        )
    }

    @Test
    fun `refaz a captura a cada retentativa ate localizar`() {
        val env = FakeEnv(
            captures = mutableListOf(
                screenWith(),
                screenWith(),
                screenWith("alvo_a")
            ),
            templates = templates(),
            sessions = emptyMap()
        )
        val main = session("menu", action("primeira", "alvo_a"), retries = 3)

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main, env.sessions))
        assertEquals(3, env.captureCount)
        assertEquals(1, env.clicks.size)
    }

    @Test
    fun `encerra quando as tentativas se esgotam`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith()),
            templates = templates(),
            sessions = emptyMap()
        )
        val main = session("menu", action("primeira", "alvo_a"), retries = 2)

        val outcome = SessionRunner(env, log).run(main, env.sessions)

        assertTrue(outcome.toString(), outcome is RunOutcome.Failure)
        assertEquals(3, env.captureCount)
        assertTrue(env.clicks.isEmpty())
    }

    @Test
    fun `segue o ciclo A B A e para na exaustao`() {
        val sessionB = session("b", action("volta", "alvo_b", call = "a"))
        val sessionA = session("a", action("vai", "alvo_a", call = "b"))
        val env = FakeEnv(
            captures = mutableListOf(
                screenWith("alvo_a"),
                screenWith("alvo_b"),
                screenWith("alvo_a"),
                screenWith()
            ),
            templates = templates(),
            sessions = mapOf("a.json" to sessionA, "b.json" to sessionB)
        )

        val outcome = SessionRunner(env, log).run(sessionA, env.sessions)

        assertTrue(outcome.toString(), outcome is RunOutcome.Failure)
        assertEquals(4, env.captureCount)
        assertEquals(3, env.clicks.size)
    }

    @Test
    fun `call ausente encerra com sucesso`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a")),
            templates = templates(),
            sessions = emptyMap()
        )
        assertEquals(
            RunOutcome.Success,
            SessionRunner(env, log).run(session("menu", action("fim", "alvo_a")), env.sessions)
        )
    }

    @Test
    fun `call para sessao ilegivel falha`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a")),
            templates = templates(),
            sessions = emptyMap()
        )
        val outcome = SessionRunner(env, log)
            .run(session("menu", action("vai", "alvo_a", call = "sumida")), env.sessions)

        assertTrue(outcome.toString(), outcome is RunOutcome.Failure)
        assertTrue((outcome as RunOutcome.Failure).reason.contains("sumida"))
    }

    @Test
    fun `gesto rejeitado encerra a execucao`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a")),
            templates = templates(),
            sessions = emptyMap(),
            clickOutcome = { ClickOutcome.REJECTED }
        )
        val outcome = SessionRunner(env, log)
            .run(session("menu", action("vai", "alvo_a")), env.sessions)

        assertTrue(outcome.toString(), outcome is RunOutcome.Failure)
        assertEquals("gesto rejeitado", (outcome as RunOutcome.Failure).reason)
    }

    @Test
    fun `captura que falha vira retentativa`() {
        val env = FakeEnv(
            captures = mutableListOf(Capture.Failed(2), screenWith("alvo_a")),
            templates = templates(),
            sessions = emptyMap()
        )
        val outcome = SessionRunner(env, log).run(
            session("menu", action("vai", "alvo_a"), retries = 1),
            env.sessions
        )

        assertEquals(RunOutcome.Success, outcome)
        assertTrue(log.text().contains("captura falhou (codigo=2)"))
    }

    @Test
    fun `parar interrompe a execucao`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith()),
            templates = templates(),
            sessions = emptyMap()
        )
        val runner = SessionRunner(env, log)
        env.onCapture = { runner.cancel() }

        assertEquals(
            RunOutcome.Cancelled,
            runner.run(session("menu", action("vai", "alvo_a"), retries = 5), env.sessions)
        )
        assertEquals(1, env.captureCount)
    }

    @Test
    fun `parar antes de run impede qualquer captura`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a")),
            templates = templates(),
            sessions = emptyMap()
        )
        val runner = SessionRunner(env, log)
        runner.cancel()

        assertEquals(
            RunOutcome.Cancelled,
            runner.run(session("menu", action("vai", "alvo_a")), env.sessions)
        )
        assertEquals(0, env.captureCount)
        assertTrue(env.clicks.isEmpty())
    }

    @Test
    fun `transicao usa o grafo validado e ignora o armazenamento`() {
        val sessionB = session("b", action("fim", "alvo_b"))
        val sessionA = session("a", action("vai", "alvo_a", call = "b"))
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a"), screenWith("alvo_b")),
            templates = templates(),
            // O armazenamento foi alterado depois da validacao: a sessao 'b'
            // desapareceu, mas o snapshot validado continua valendo.
            sessions = emptyMap()
        )

        val outcome = SessionRunner(env, log)
            .run(sessionA, mapOf("a.json" to sessionA, "b.json" to sessionB))

        assertEquals(RunOutcome.Success, outcome)
        assertEquals(2, env.clicks.size)
    }

    @Test
    fun `threshold alto nao para no early exit`() {
        // Um template identico a regiao correspondente da tela: com o early exit
        // limitado a 0.95 a busca poderia devolver um escore abaixo do exigido.
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_c")),
            templates = templates(),
            sessions = emptyMap()
        )
        val main = session(
            "menu",
            SessionAction(
                name = "exato",
                locate = "alvo_c",
                threshold = 0.999,
                waitAfterMs = 0
            )
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main, env.sessions))
        assertEquals(128 to 192, env.clicks.single())
    }

    // --- modo debug ----------------------------------------------------------

    @Test
    fun `debug desligado nao pede confirmacao`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a")),
            templates = templates(),
            sessions = emptyMap()
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(session("menu", action("vai", "alvo_a"))))
        assertTrue(env.debugSteps.isEmpty())
    }

    @Test
    fun `debug com OK segue para a sessao chamada com um popup por acao`() {
        val sessionB = session("b", action("fim", "alvo_b"))
        val sessionA = session("a", action("vai", "alvo_a", call = "b"))
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a"), screenWith("alvo_b")),
            templates = templates(),
            sessions = mapOf("a.json" to sessionA, "b.json" to sessionB),
            debugEnabled = true
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(sessionA, env.sessions))
        assertEquals(2, env.clicks.size)
        assertEquals(listOf("a", "b"), env.debugSteps.map { it.sessionName })
        assertEquals(listOf("vai", "fim"), env.debugSteps.map { it.actionName })
        assertEquals(listOf("b", null), env.debugSteps.map { it.nextSession })
        assertEquals(1, env.debugSteps.first().attempt)
        assertEquals(1, env.debugSteps.first().attempts)
    }

    @Test
    fun `debug com Cancel interrompe sem clicar nem carregar outra sessao`() {
        val sessionB = session("b", action("fim", "alvo_b"))
        val sessionA = session("a", action("vai", "alvo_a", call = "b"))
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a"), screenWith("alvo_b")),
            templates = templates(),
            sessions = mapOf("a.json" to sessionA, "b.json" to sessionB),
            debugEnabled = true,
            debugChoice = { DebugChoice.CANCEL }
        )

        assertEquals(RunOutcome.Cancelled, SessionRunner(env, log).run(sessionA, env.sessions))
        assertEquals(1, env.clicks.size)
        assertEquals(1, env.captureCount)
        assertEquals(1, env.debugSteps.size)
        assertTrue(log.text(), log.lines().contains("Debug: cancelado pelo usuario"))
        assertTrue(log.text(), log.lines().contains("Execucao: interrompida pelo usuario"))
    }

    @Test
    fun `debug com varios cliques pede confirmacao uma vez com todos os pontos`() {
        val env = FakeEnv(
            captures = mutableListOf(screenAtDoubleResolution("alvo_a")),
            templates = templates(),
            sessions = emptyMap(),
            debugEnabled = true
        )
        val main = Session(
            name = "menu",
            screen = screenSize,
            retries = 0,
            actions = listOf(
                action(
                    "toque",
                    "alvo_a",
                    clicks = listOf(ClickPoint(10, 20), ClickPoint(30, 40), ClickPoint(5, 5))
                )
            ),
            fileName = "menu.json"
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main, env.sessions))
        assertEquals(3, env.clicks.size)
        val step = env.debugSteps.single()
        assertEquals(
            listOf(ClickPoint(20, 40), ClickPoint(60, 80), ClickPoint(10, 10)),
            step.clicks
        )
    }

    @Test
    fun `debug com cliques implicitos traz o centro do template`() {
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_b")),
            templates = templates(),
            sessions = emptyMap(),
            debugEnabled = true
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(session("menu", action("vai", "alvo_b"))))
        assertEquals(listOf(ClickPoint(80, 112)), env.debugSteps.single().clicks)
    }

    @Test
    fun `debug informa a posicao escalonada do template na tela real`() {
        val env = FakeEnv(
            captures = mutableListOf(screenAtDoubleResolution("alvo_b")),
            templates = templates(),
            sessions = emptyMap(),
            debugEnabled = true
        )
        val main = Session(
            name = "menu",
            screen = screenSize,
            retries = 0,
            actions = listOf(action("vai", "alvo_b")),
            fileName = "menu.json"
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main, env.sessions))
        assertEquals(Area(128, 192, 192, 256), env.debugSteps.single().match)
    }

    @Test
    fun `debug ocorre depois do ultimo clique e antes da proxima sessao`() {
        val sessionB = session("b", action("fim", "alvo_b"))
        val sessionA = session(
            "a",
            action("vai", "alvo_a", call = "b", clicks = listOf(ClickPoint(10, 20), ClickPoint(30, 40)))
        )
        val env = FakeEnv(
            captures = mutableListOf(screenWith("alvo_a"), screenWith("alvo_b")),
            templates = templates(),
            sessions = mapOf("a.json" to sessionA, "b.json" to sessionB),
            debugEnabled = true
        )

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(sessionA, env.sessions))
        assertEquals(
            listOf("captura", "clique", "clique", "debug", "captura", "clique", "debug"),
            env.events
        )
    }

    private companion object {
        const val BLOCK = 16
    }
}

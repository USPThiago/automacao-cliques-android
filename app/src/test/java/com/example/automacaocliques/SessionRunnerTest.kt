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
        val clickOutcome: (Int) -> ClickOutcome = { ClickOutcome.COMPLETED }
    ) : RunnerEnvironment {

        val clicks = mutableListOf<Pair<Int, Int>>()
        val sleeps = mutableListOf<Long>()
        var captureCount = 0
        private var clock = 0L
        var onCapture: (() -> Unit)? = null

        override fun capture(): Capture {
            onCapture?.invoke()
            captureCount++
            return if (captures.size == 1) captures[0] else captures.removeAt(0)
        }

        override fun click(x: Float, y: Float): ClickOutcome {
            clicks += x.toInt() to y.toInt()
            return clickOutcome(clicks.size - 1)
        }

        override fun templateOf(name: String): GrayImage? = templates[name]

        override fun loadSession(fileName: String): Session? = sessions[fileName]

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

        val outcome = SessionRunner(env, log).run(main)

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

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main))
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

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main))
        assertEquals(listOf(20 to 40, 60 to 80), env.clicks)
        assertTrue("delayMs deveria prevalecer: ${env.sleeps}", env.sleeps.contains(5L))
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

        assertEquals(RunOutcome.Success, SessionRunner(env, log).run(main))
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

        val outcome = SessionRunner(env, log).run(main)

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

        val outcome = SessionRunner(env, log).run(sessionA)

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
            SessionRunner(env, log).run(session("menu", action("fim", "alvo_a")))
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
            .run(session("menu", action("vai", "alvo_a", call = "sumida")))

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
        val outcome = SessionRunner(env, log).run(session("menu", action("vai", "alvo_a")))

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
            session("menu", action("vai", "alvo_a"), retries = 1)
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

        assertEquals(RunOutcome.Cancelled, runner.run(session("menu", action("vai", "alvo_a"), retries = 5)))
        assertEquals(1, env.captureCount)
    }

    private companion object {
        const val BLOCK = 16
    }
}

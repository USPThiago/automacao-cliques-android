package com.example.automacaocliques

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionValidatorTest {

    private val screen = Size(1080, 2400)

    private fun load(
        files: Map<String, String>,
        templates: Map<String, Size> = mapOf("botao" to Size(100, 50)),
        screen: Size = this.screen
    ): SessionLoad = SessionValidator.load(
        source = { files[it] },
        templates = { templates[it] },
        screen = screen
    )

    private fun reasonOf(result: SessionLoad): String =
        (result as? SessionLoad.Failure)?.reason
            ?: throw AssertionError("esperada falha, veio $result")

    @Test
    fun `carrega a sessao principal e as chamadas`() {
        val result = load(
            mapOf(
                "mainSession.json" to """
                    { "name": "menu", "actions": [
                      { "name": "jogar", "locate": "botao", "call": "batalha" } ] }
                """.trimIndent(),
                "batalha.json" to """
                    { "name": "batalha", "actions": [ { "name": "atacar", "locate": "botao" } ] }
                """.trimIndent()
            )
        )

        val ok = result as SessionLoad.Ok
        assertEquals("menu", ok.main.name)
        assertEquals(setOf("mainSession.json", "batalha.json"), ok.sessions.keys)
    }

    @Test
    fun `aceita ciclo entre sessoes`() {
        val result = load(
            mapOf(
                "mainSession.json" to """
                    { "name": "a", "actions": [ { "name": "x", "locate": "botao", "call": "b" } ] }
                """.trimIndent(),
                "b.json" to """
                    { "name": "b", "actions": [
                      { "name": "y", "locate": "botao", "call": "mainSession" } ] }
                """.trimIndent()
            )
        )
        assertTrue(result is SessionLoad.Ok)
    }

    @Test
    fun `recusa quando a sessao principal nao existe`() {
        val reason = reasonOf(load(emptyMap()))
        assertTrue(reason, reason.contains("mainSession.json"))
    }

    @Test
    fun `recusa json invalido`() {
        val reason = reasonOf(load(mapOf("mainSession.json" to "{")))
        assertTrue(reason, reason.contains("mainSession.json"))
    }

    @Test
    fun `recusa call para sessao inexistente citando quem chamou`() {
        val reason = reasonOf(
            load(
                mapOf(
                    "mainSession.json" to """
                        { "name": "menu", "actions": [
                          { "name": "jogar", "locate": "botao", "call": "sumida" } ] }
                    """.trimIndent()
                )
            )
        )
        assertEquals(
            "sumida.json nao encontrado ou ilegivel " +
                "(chamado em mainSession.json:2, campo 'call')",
            reason
        )
    }

    @Test
    fun `recusa template ausente`() {
        val reason = reasonOf(
            load(
                mapOf(
                    "mainSession.json" to """
                        { "name": "menu", "actions": [ { "name": "a", "locate": "fantasma" } ] }
                    """.trimIndent()
                )
            )
        )
        assertTrue(reason, reason.contains("fantasma"))
        assertTrue(reason, reason.startsWith("mainSession.json:1:"))
    }

    @Test
    fun `recusa searchArea fora da tela`() {
        val reason = reasonOf(
            load(
                mapOf(
                    "mainSession.json" to """
                        { "name": "menu", "actions": [ { "name": "a", "locate": "botao",
                          "searchArea": { "left": 0, "top": 0, "right": 5000, "bottom": 100 } } ] }
                    """.trimIndent()
                )
            )
        )
        assertTrue(reason, reason.contains("searchArea"))
    }

    @Test
    fun `recusa template maior que a searchArea`() {
        val reason = reasonOf(
            load(
                mapOf(
                    "mainSession.json" to """
                        { "name": "menu", "actions": [ { "name": "a", "locate": "botao",
                          "searchArea": { "left": 0, "top": 0, "right": 40, "bottom": 40 } } ] }
                    """.trimIndent()
                )
            )
        )
        assertTrue(reason, reason.contains("nao cabe"))
    }

    @Test
    fun `escalona a searchArea pela resolucao declarada`() {
        // A area 0..540 de uma tela de 540x1200 vira 0..1080 numa tela de 1080x2400.
        val result = load(
            mapOf(
                "mainSession.json" to """
                    { "name": "menu", "screen": { "width": 540, "height": 1200 },
                      "actions": [ { "name": "a", "locate": "botao",
                        "searchArea": { "left": 0, "top": 0, "right": 540, "bottom": 600 } } ] }
                """.trimIndent()
            )
        )
        assertTrue(result.toString(), result is SessionLoad.Ok)
    }

    @Test
    fun `usa o fator unico do template quando as proporcoes diferem`() {
        // 540x1200 -> 1080x2160: factorX 2.0, factorY 1.8. O template de 100x50
        // e casado a 1.8 (fator unico), logo 180x90, e cabe na area 190x108.
        val result = load(
            mapOf(
                "mainSession.json" to """
                    { "name": "menu", "screen": { "width": 540, "height": 1200 },
                      "actions": [ { "name": "a", "locate": "botao",
                        "searchArea": { "left": 0, "top": 0, "right": 95, "bottom": 60 } } ] }
                """.trimIndent()
            ),
            screen = Size(1080, 2160)
        )
        assertTrue(result.toString(), result is SessionLoad.Ok)
    }

    @Test
    fun `valida areas contra a tela em paisagem`() {
        val result = load(
            mapOf(
                "mainSession.json" to """
                    { "name": "menu", "actions": [ { "name": "a", "locate": "botao",
                      "searchArea": { "left": 0, "top": 0, "right": 2000, "bottom": 900 } } ] }
                """.trimIndent()
            ),
            screen = Size(2400, 1080)
        )
        assertTrue(result.toString(), result is SessionLoad.Ok)
    }

    @Test
    fun `recusa searchArea que so estoura depois da escala`() {
        val reason = reasonOf(
            load(
                mapOf(
                    "mainSession.json" to """
                        { "name": "menu", "screen": { "width": 540, "height": 1200 },
                          "actions": [ { "name": "a", "locate": "botao",
                            "searchArea": { "left": 0, "top": 0, "right": 540, "bottom": 1300 } } ] }
                    """.trimIndent()
                )
            )
        )
        assertTrue(reason, reason.contains("fora da tela"))
    }
}

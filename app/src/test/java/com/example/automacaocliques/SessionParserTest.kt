package com.example.automacaocliques

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionParserTest {

    @Test
    fun `aplica os valores padrao dos campos ausentes`() {
        val session = SessionParser.parse(
            "mainSession.json",
            """
            {
              "name": "menu",
              "actions": [ { "name": "jogar", "locate": "botao_jogar" } ]
            }
            """.trimIndent()
        )

        assertEquals("menu", session.name)
        assertNull(session.screen)
        assertEquals(3, session.retries)
        assertEquals(4, session.attempts)
        assertEquals(1_000L, session.retryDelayMs)

        val action = session.actions.single()
        assertEquals(0.80, action.threshold, 1e-9)
        assertEquals(listOf(1.0), action.scales)
        assertNull(action.searchArea)
        assertTrue(action.clicks.isEmpty())
        assertEquals(300L, action.clickIntervalMs)
        assertEquals(1_000L, action.waitAfterMs)
        assertNull(action.call)
    }

    @Test
    fun `le coordenadas nomeadas de area e de cliques`() {
        val session = SessionParser.parse(
            "mainSession.json",
            """
            {
              "name": "menu",
              "screen": { "width": 1080, "height": 2400 },
              "retries": 1,
              "retryDelayMs": 500,
              "actions": [
                {
                  "name": "jogar",
                  "locate": "botao_jogar",
                  "threshold": 0.9,
                  "scales": [1.0, 0.9],
                  "searchArea": { "left": 10, "top": 20, "right": 300, "bottom": 400 },
                  "clicks": [
                    { "x": 100, "y": 200 },
                    { "x": 150, "y": 250, "delayMs": 50 }
                  ],
                  "clickIntervalMs": 120,
                  "waitAfterMs": 2000,
                  "call": "batalha"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(Size(1080, 2400), session.screen)
        assertEquals(1, session.retries)
        assertEquals(500L, session.retryDelayMs)

        val action = session.actions.single()
        assertEquals(0.9, action.threshold, 1e-9)
        assertEquals(listOf(1.0, 0.9), action.scales)
        assertEquals(Area(10, 20, 300, 400), action.searchArea)
        assertEquals(ClickPoint(100, 200), action.clicks[0])
        assertEquals(ClickPoint(150, 250, 50L), action.clicks[1])
        assertEquals(120L, action.clickIntervalMs)
        assertEquals(2_000L, action.waitAfterMs)
        assertEquals("batalha", action.call)
    }

    @Test
    fun `recusa json invalido apontando o arquivo`() {
        val error = fails("mainSession.json", "{ \"name\": ")
        assertTrue(error, error.startsWith("mainSession.json:"))
    }

    @Test
    fun `recusa sessao sem nome`() {
        val error = fails(
            "mainSession.json",
            """{ "actions": [ { "name": "a", "locate": "t" } ] }"""
        )
        assertTrue(error, error.contains("'name'"))
    }

    @Test
    fun `recusa sessao sem acoes`() {
        val error = fails("mainSession.json", """{ "name": "menu", "actions": [] }""")
        assertTrue(error, error.contains("'actions'"))
    }

    @Test
    fun `recusa acao sem template`() {
        val error = fails(
            "mainSession.json",
            """{ "name": "menu", "actions": [ { "name": "jogar" } ] }"""
        )
        assertTrue(error, error.contains("actions[0].locate"))
    }

    @Test
    fun `recusa threshold fora da faixa`() {
        val error = fails(
            "mainSession.json",
            """{ "name": "m", "actions": [ { "name": "a", "locate": "t", "threshold": 1.5 } ] }"""
        )
        assertTrue(error, error.contains("threshold"))
    }

    @Test
    fun `recusa tempos negativos`() {
        val error = fails(
            "mainSession.json",
            """{ "name": "m", "retryDelayMs": -1, "actions": [ { "name": "a", "locate": "t" } ] }"""
        )
        assertTrue(error, error.contains("retryDelayMs"))
    }

    @Test
    fun `recusa retries negativo`() {
        val error = fails(
            "mainSession.json",
            """{ "name": "m", "retries": -2, "actions": [ { "name": "a", "locate": "t" } ] }"""
        )
        assertTrue(error, error.contains("retries"))
    }

    @Test
    fun `recusa searchArea invertida`() {
        val error = fails(
            "mainSession.json",
            """
            { "name": "m", "actions": [ { "name": "a", "locate": "t",
              "searchArea": { "left": 300, "top": 0, "right": 100, "bottom": 50 } } ] }
            """.trimIndent()
        )
        assertTrue(error, error.contains("searchArea"))
    }

    @Test
    fun `recusa searchArea incompleta`() {
        val error = fails(
            "mainSession.json",
            """
            { "name": "m", "actions": [ { "name": "a", "locate": "t",
              "searchArea": { "left": 0, "top": 0, "right": 100 } } ] }
            """.trimIndent()
        )
        assertTrue(error, error.contains("bottom"))
    }

    private fun fails(fileName: String, text: String): String =
        try {
            SessionParser.parse(fileName, text)
            throw AssertionError("esperado SessionFormatException")
        } catch (e: SessionFormatException) {
            e.message.orEmpty()
        }
}

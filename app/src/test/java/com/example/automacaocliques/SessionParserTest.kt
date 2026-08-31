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
    fun `recusa json invalido apontando arquivo linha e coluna`() {
        val error = fails(
            "mainSession.json",
            """
            {
              "name": "menu",
              "actions": [
            """.trimIndent()
        )
        assertTrue(error, error.startsWith("mainSession.json:3:14:"))
        assertTrue(error, error.contains("linha 3, coluna 14"))
        assertTrue(error, error.contains("posicao "))
        assertTrue(error, error.contains("lista nao fechada"))
    }

    @Test
    fun `aponta a linha do valor invalido dentro de uma acao`() {
        val error = fails(
            "mainSession.json",
            """
            {
              "name": "menu",
              "actions": [
                {
                  "name": "jogar",
                  "locate": "botao",
                  "threshold": 1.5
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(
            "mainSession.json:7:20: campo 'actions[0].threshold': " +
                "fora da faixa 0.0-1.0 (recebido: numero 1.5)",
            error
        )
    }

    @Test
    fun `aponta a linha e o valor de um campo com tipo errado`() {
        val error = fails(
            "mainSession.json",
            """
            {
              "name": "menu",
              "actions": [
                { "name": "jogar", "locate": true }
              ]
            }
            """.trimIndent()
        )
        assertEquals(
            "mainSession.json:4:34: campo 'actions[0].locate': " +
                "esperado um texto (recebido: booleano true)",
            error
        )
    }

    @Test
    fun `aponta a linha do objeto quando o campo obrigatorio esta ausente`() {
        val error = fails(
            "mainSession.json",
            """
            {
              "name": "menu",
              "actions": [
                { "name": "jogar" }
              ]
            }
            """.trimIndent()
        )
        assertEquals(
            "mainSession.json:4:5: campo 'actions[0].locate': campo obrigatorio ausente",
            error
        )
    }

    @Test
    fun `aponta o indice do clique e a coordenada culpada`() {
        val error = fails(
            "mainSession.json",
            """
            {
              "name": "menu",
              "actions": [
                {
                  "name": "jogar",
                  "locate": "botao",
                  "clicks": [
                    { "x": 10, "y": 20 },
                    { "x": 30, "y": 40.5 }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(
            "mainSession.json:9:25: campo 'actions[0].clicks[1].y': " +
                "esperado um numero inteiro (recebido: numero 40.5)",
            error
        )
    }

    @Test
    fun `expoe a linha e o campo na excecao`() {
        val error = try {
            SessionParser.parse(
                "mainSession.json",
                """
                {
                  "name": "menu",
                  "retries": -2,
                  "actions": [ { "name": "a", "locate": "t" } ]
                }
                """.trimIndent()
            )
            throw AssertionError("esperado SessionFormatException")
        } catch (e: SessionFormatException) {
            e
        }

        assertEquals(3, error.line)
        assertEquals("retries", error.field)
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
        assertTrue(error, error.contains("recebido: numero 1.5"))
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
    fun `recusa tempos negativos fracionarios`() {
        // -0.5 truncava para 0 e passava pela faixa "deve ser >= 0".
        listOf(
            """{ "name": "m", "retryDelayMs": -0.5, "actions": [ { "name": "a", "locate": "t" } ] }"""
                to "retryDelayMs",
            """{ "name": "m", "actions": [ { "name": "a", "locate": "t",
                 "clickIntervalMs": -0.5 } ] }""" to "clickIntervalMs",
            """{ "name": "m", "actions": [ { "name": "a", "locate": "t",
                 "waitAfterMs": -0.5 } ] }""" to "waitAfterMs",
            """{ "name": "m", "retries": -0.5, "actions": [ { "name": "a", "locate": "t" } ] }"""
                to "retries",
            """{ "name": "m", "actions": [ { "name": "a", "locate": "t",
                 "clicks": [ { "x": 1, "y": 2, "delayMs": -0.5 } ] } ] }""" to "delayMs"
        ).forEach { (text, field) ->
            val error = fails("mainSession.json", text.trimIndent())
            assertTrue("$field: $error", error.contains(field))
            assertTrue("$field: $error", error.contains("recebido: numero -0.5"))
        }
    }

    @Test
    fun `recusa fracoes em campos inteiros`() {
        val error = fails(
            "mainSession.json",
            """
            { "name": "m", "actions": [ { "name": "a", "locate": "t",
              "clicks": [ { "x": 10.5, "y": 20 } ] } ] }
            """.trimIndent()
        )
        assertTrue(error, error.contains("inteiro"))
    }

    @Test
    fun `recusa searchArea invertida citando os dois valores`() {
        val error = fails(
            "mainSession.json",
            """
            { "name": "m", "actions": [ { "name": "a", "locate": "t",
              "searchArea": { "left": 300, "top": 0, "right": 100, "bottom": 50 } } ] }
            """.trimIndent()
        )
        assertEquals(
            "mainSession.json:2:51: campo 'actions[0].searchArea.right': " +
                "right (100) deve ser maior que left (300) (recebido: numero 100)",
            error
        )
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

package com.example.automacaocliques

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDraftTest {

    private fun validDraft() = SessionDraft(
        name = "menu",
        file = "menu",
        screenWidth = "1080",
        screenHeight = "2400",
        onLocateFailure = "recupera",
        actions = mutableListOf(
            ActionDraft(
                name = "jogar",
                locate = "botao",
                scales = "1.0, 0.9",
                searchArea = AreaDraft("0", "0", "540", "1200"),
                clickMode = ClickMode.POINTS,
                clicks = mutableListOf(ClickDraft("10", "20"), ClickDraft("30", "40", "500")),
                call = "batalha"
            ),
            ActionDraft(
                name = "area",
                locate = "x",
                clickMode = ClickMode.AREA,
                clickArea = AreaDraft("1", "2", "3", "4")
            )
        )
    )

    @Test
    fun `serializa conforme o esquema e o parser aceita`() {
        val json = SessionDrafts.toJson(validDraft())
        val session = SessionParser.parse("menu.json", json)

        assertEquals("menu", session.name)
        assertEquals(Size(1080, 2400), session.screen)
        assertEquals("recupera", session.onLocateFailure)
        val first = session.actions[0]
        assertEquals(listOf(1.0, 0.9), first.scales)
        assertEquals(0.8, first.threshold, 1e-9)
        assertEquals(Area(0, 0, 540, 1200), first.searchArea)
        assertEquals(listOf(ClickPoint(10, 20), ClickPoint(30, 40, 500)), first.clicks)
        assertEquals("batalha", first.call)
        assertEquals(Area(1, 2, 3, 4), session.actions[1].clickArea)
        assertTrue(session.actions[1].clicks.isEmpty())
    }

    @Test
    fun `ida e volta preserva o rascunho`() {
        val draft = validDraft()
        val back = SessionDrafts.fromJson("menu.json", SessionDrafts.toJson(draft))
        assertEquals(draft.name, back.name)
        assertEquals("menu", back.file)
        assertEquals(ClickMode.POINTS, back.actions[0].clickMode)
        assertEquals(ClickMode.AREA, back.actions[1].clickMode)
        assertEquals("500", back.actions[0].clicks[1].delayMs)
        assertEquals("1.0, 0.9", back.actions[0].scales)
    }

    @Test
    fun `campos vazios sao omitidos`() {
        val draft = SessionDraft(
            name = "a",
            file = "a",
            retries = "",
            retryDelayMs = "",
            actions = mutableListOf(
                ActionDraft(name = "x", locate = "t", threshold = "", scales = "", clickIntervalMs = "", waitAfterMs = "")
            )
        )
        val json = SessionDrafts.toJson(draft)
        listOf("screen", "retries", "threshold", "scales", "clicks", "clickArea", "call").forEach {
            assertTrue("$it nao deveria aparecer", "\"$it\"" !in json)
        }
        SessionParser.parse("a.json", json)
    }

    @Test
    fun `validacao aponta os campos com os caminhos do parser`() {
        val draft = SessionDraft(
            name = "",
            file = "nome invalido",
            screenWidth = "100",
            retries = "-1",
            actions = mutableListOf(
                ActionDraft(
                    name = "x",
                    locate = "",
                    threshold = "1.5",
                    clickMode = ClickMode.POINTS,
                    clicks = mutableListOf(ClickDraft("a", "1"))
                )
            )
        )
        val fields = SessionDrafts.validate(draft).map { it.field }
        assertEquals(
            listOf(
                "name", "file", "screen.height", "retries",
                "actions[0].locate", "actions[0].threshold", "actions[0].clicks[0].x"
            ),
            fields
        )
    }

    @Test
    fun `sessao sem acoes e area invertida sao recusadas`() {
        assertEquals(
            listOf("actions"),
            SessionDrafts.validate(SessionDraft(name = "a", file = "a")).map { it.field }
        )
        val action = ActionDraft(name = "x", locate = "t", searchArea = AreaDraft("10", "0", "5", "10"))
        assertEquals(listOf("searchArea.right"), SessionDrafts.validateAction(action).map { it.field })
    }

    @Test
    fun `leitura tolerante abre arquivo com valores invalidos`() {
        val draft = SessionDrafts.fromJson(
            "b.json",
            """{ "name": "b", "retries": -2, "actions": [ { "name": "x", "threshold": 3 } ] }"""
        )
        assertEquals("-2", draft.retries)
        assertEquals("3", draft.actions[0].threshold)
        assertEquals("", draft.actions[0].locate)
    }
}

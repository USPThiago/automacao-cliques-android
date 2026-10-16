package com.example.automacaocliques

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReferencesTest {

    private val text = """
        {
          "name": "menu",
          "onLocateFailure": "velha.json",
          "actions": [
            { "name": "a", "locate": "t", "call": "velha" },
            { "name": "b", "locate": "t", "call": "outra" }
          ]
        }
    """.trimIndent()

    @Test
    fun `encontra call e onLocateFailure com a linha`() {
        val refs = SessionReferences.find("menu.json", text)
        assertEquals(
            listOf(
                SessionReference("menu.json", "velha.json", "onLocateFailure", 3),
                SessionReference("menu.json", "velha.json", "call", 5),
                SessionReference("menu.json", "outra.json", "call", 6)
            ),
            refs
        )
    }

    @Test
    fun `renomeia mantendo o estilo e o resto do arquivo`() {
        val renamed = SessionReferences.rename(text, "velha.json", "nova")
        assertEquals(
            text.replace("\"velha.json\"", "\"nova.json\"").replace("\"velha\"", "\"nova\""),
            renamed
        )
    }

    @Test
    fun `json ilegivel nao tem referencias`() {
        assertEquals(emptyList<SessionReference>(), SessionReferences.find("x.json", "{"))
        assertEquals("{", SessionReferences.rename("{", "a", "b"))
    }

    @Test
    fun `links dos erros do validador`() {
        assertEquals(
            ErrorLink("a.json", 4, "actions[0].threshold"),
            ValidationErrors.linkOf("a.json:4:7: campo 'actions[0].threshold': fora da faixa")
        )
        assertEquals(
            ErrorLink("a.json", 5, "locate"),
            ValidationErrors.linkOf("a.json:5: acao 'x': template 'y' nao encontrado em templates/")
        )
        assertEquals(
            ErrorLink("menu.json", 9, "call"),
            ValidationErrors.linkOf("b.json nao encontrado ou ilegivel (chamado em menu.json:9, campo 'call')")
        )
        assertEquals(
            ErrorLink("menu.json", null, "onLocateFailure"),
            ValidationErrors.linkOf("b.json nao encontrado ou ilegivel (chamado em menu.json, campo 'onLocateFailure')")
        )
    }

    @Test
    fun `alvo do erro por linha cai na acao certa`() {
        val draft = SessionDrafts.fromJson("menu.json", text)
        assertEquals(ErrorTarget(1, "call"), ValidationErrors.targetOf(draft, ErrorLink("menu.json", 6, "call")))
        assertEquals(
            ErrorTarget(null, "onLocateFailure"),
            ValidationErrors.targetOf(draft, ErrorLink("menu.json", null, "onLocateFailure"))
        )
        assertEquals(
            ErrorTarget(0, "threshold"),
            ValidationErrors.targetOf(draft, ErrorLink("menu.json", 5, "actions[0].threshold"))
        )
    }
}

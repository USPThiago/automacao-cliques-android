package com.example.automacaocliques

/** Campo `call`/`onLocateFailure` de [sourceFile] que aponta para a sessao [target]. */
data class SessionReference(
    val sourceFile: String,
    /** Nome de arquivo da sessao referenciada, sempre com `.json`. */
    val target: String,
    val field: String,
    val line: Int
) {
    /** `arquivo.json:12 (call)`, usado nos dialogos. */
    fun describe(): String = "$sourceFile:$line ($field)"
}

/** Busca e renomeia referencias entre sessoes sem reformatar os arquivos. */
object SessionReferences {

    /** Referencias de [text]; vazio se o JSON for ilegivel. */
    fun find(sourceFile: String, text: String): List<SessionReference> =
        refValues(text).map { (field, value) ->
            SessionReference(sourceFile, SessionValidator.fileNameOf(value.value), field, value.location.line)
        }

    /**
     * Troca em [text] as referencias a [oldFile] por [newFile] (com ou sem
     * `.json`, mantendo o estilo de cada ocorrencia). O resto do arquivo fica
     * intacto. Devolve [text] sem mudancas se nada apontar para [oldFile].
     */
    fun rename(text: String, oldFile: String, newFile: String): String {
        val old = SessionValidator.fileNameOf(oldFile)
        val newBase = SessionDrafts.baseName(newFile)
        val targets = refValues(text)
            .map { it.second }
            .filter { SessionValidator.fileNameOf(it.value) == old }
        if (targets.isEmpty()) return text
        val starts = lineStarts(text)
        val out = StringBuilder(text)
        targets
            .map { it to starts[it.location.line - 1] + it.location.column - 1 }
            .sortedByDescending { it.second }
            .forEach { (value, start) ->
                val end = stringEnd(text, start)
                val replacement = if (value.value.endsWith(".json", ignoreCase = true)) {
                    "$newBase.json"
                } else {
                    newBase
                }
                out.replace(start, end, JsonWriter.quote(replacement))
            }
        return out.toString()
    }

    private fun refValues(text: String): List<Pair<String, JsonValue.Str>> {
        val root = try {
            Json.parse(text) as? JsonValue.Obj
        } catch (e: JsonSyntaxException) {
            null
        } ?: return emptyList()
        val refs = mutableListOf<Pair<String, JsonValue.Str>>()
        (root.entries["onLocateFailure"] as? JsonValue.Str)?.let { refs += "onLocateFailure" to it }
        (root.entries["actions"] as? JsonValue.Arr)?.items?.forEach { item ->
            ((item as? JsonValue.Obj)?.entries?.get("call") as? JsonValue.Str)?.let { refs += "call" to it }
        }
        return refs.filter { it.second.value.isNotBlank() }
    }

    private fun lineStarts(text: String): IntArray {
        val starts = mutableListOf(0)
        text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        return starts.toIntArray()
    }

    /** Indice logo apos as aspas de fechamento do texto que comeca em [start]. */
    private fun stringEnd(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return text.length
    }
}

/** Destino de um erro de validacao: arquivo, linha e campo, quando conhecidos. */
data class ErrorLink(val fileName: String, val line: Int?, val field: String?)

/** Onde abrir o editor para um erro: acao (indice) e campo dentro dela ou da sessao. */
data class ErrorTarget(val actionIndex: Int?, val field: String?)

object ValidationErrors {

    private val CALLED_FROM = Regex("""\(chamado em ([\w.-]+\.json)(?::(\d+))?, campo '([^']+)'\)""")
    private val LOCATION = Regex("""^([\w.-]+\.json)(?::(\d+))?(?::\d+)?""")
    private val FIELD = Regex("""campo '([^']+)'""")
    private val ACTION_FIELDS = setOf("locate", "searchArea", "clickArea", "call", "scales")

    /** Arquivo/linha/campo de uma mensagem do [SessionValidator], ou `null`. */
    fun linkOf(message: String): ErrorLink? {
        CALLED_FROM.find(message)?.let { m ->
            return ErrorLink(m.groupValues[1], m.groupValues[2].toIntOrNull(), m.groupValues[3])
        }
        val location = LOCATION.find(message) ?: return null
        val field = FIELD.find(message)?.groupValues?.get(1) ?: when {
            "template" in message && "nao cabe" in message -> "searchArea"
            "template" in message -> "locate"
            "searchArea" in message -> "searchArea"
            "clickArea" in message -> "clickArea"
            else -> null
        }
        return ErrorLink(location.groupValues[1], location.groupValues[2].toIntOrNull(), field)
    }

    /**
     * Resolve [link] no rascunho: `actions[2].threshold` vira acao 2 / campo
     * `threshold`; campos de acao sem indice (erros por linha) vao para a
     * acao que comeca na linha mais proxima antes de [ErrorLink.line].
     */
    fun targetOf(draft: SessionDraft, link: ErrorLink): ErrorTarget {
        val field = link.field
        val indexed = field?.let { Regex("""^actions\[(\d+)]\.?(.*)$""").find(it) }
        if (indexed != null) {
            val index = indexed.groupValues[1].toInt().takeIf { it in draft.actions.indices }
            return ErrorTarget(index, indexed.groupValues[2].ifEmpty { null })
        }
        if (field == "actions") return ErrorTarget(null, "actions")
        val line = link.line
        if (line != null && (field == null || field in ACTION_FIELDS)) {
            val index = draft.actions.indices
                .filter { (draft.actions[it].sourceLine ?: Int.MAX_VALUE) <= line }
                .maxByOrNull { draft.actions[it].sourceLine ?: 0 }
            if (index != null) return ErrorTarget(index, field)
        }
        return ErrorTarget(null, field)
    }
}

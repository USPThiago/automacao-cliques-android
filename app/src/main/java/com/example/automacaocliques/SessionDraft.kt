package com.example.automacaocliques

/**
 * Rascunhos editaveis de sessao e acao: guardam o texto digitado em cada campo
 * (inclusive invalido), para que o editor possa abrir arquivos com erro e
 * apontar o campo culpado. Campo vazio = omitido no arquivo (vale o default).
 */
data class AreaDraft(
    var left: String = "",
    var top: String = "",
    var right: String = "",
    var bottom: String = ""
) {
    val isBlank: Boolean
        get() = left.isBlank() && top.isBlank() && right.isBlank() && bottom.isBlank()

    /** Area quando os quatro campos sao inteiros, senao `null`. */
    fun toAreaOrNull(): Area? {
        val l = left.trim().toIntOrNull() ?: return null
        val t = top.trim().toIntOrNull() ?: return null
        val r = right.trim().toIntOrNull() ?: return null
        val b = bottom.trim().toIntOrNull() ?: return null
        return Area(l, t, r, b)
    }

    companion object {
        fun of(area: Area?): AreaDraft = if (area == null) AreaDraft() else AreaDraft(
            area.left.toString(),
            area.top.toString(),
            area.right.toString(),
            area.bottom.toString()
        )
    }
}

data class ClickDraft(var x: String = "", var y: String = "", var delayMs: String = "")

/** Como a acao clica: centro do template, lista `clicks` ou `clickArea` (exclusivos). */
enum class ClickMode { CENTER, POINTS, AREA }

data class ActionDraft(
    var name: String = "",
    var locate: String = "",
    var threshold: String = DEFAULT_THRESHOLD,
    var scales: String = DEFAULT_SCALES,
    var searchArea: AreaDraft = AreaDraft(),
    var clickMode: ClickMode = ClickMode.CENTER,
    val clicks: MutableList<ClickDraft> = mutableListOf(),
    var clickArea: AreaDraft = AreaDraft(),
    var clickIntervalMs: String = SessionAction.DEFAULT_CLICK_INTERVAL_MS.toString(),
    var waitAfterMs: String = SessionAction.DEFAULT_WAIT_AFTER_MS.toString(),
    var call: String = "",
    /** Linha do arquivo onde a acao comeca (para o deep-link dos erros). */
    val sourceLine: Int? = null
) {
    fun deepCopy(): ActionDraft = copy(
        searchArea = searchArea.copy(),
        clicks = clicks.mapTo(mutableListOf()) { it.copy() },
        clickArea = clickArea.copy()
    )

    companion object {
        const val DEFAULT_THRESHOLD = "0.80"
        const val DEFAULT_SCALES = "1.0"
    }
}

data class SessionDraft(
    var name: String = "",
    /** Nome do arquivo sem a extensao `.json`. */
    var file: String = "",
    var screenWidth: String = "",
    var screenHeight: String = "",
    var retries: String = Session.DEFAULT_RETRIES.toString(),
    var retryDelayMs: String = Session.DEFAULT_RETRY_DELAY_MS.toString(),
    var onLocateFailure: String = "",
    val actions: MutableList<ActionDraft> = mutableListOf()
) {
    fun deepCopy(): SessionDraft = copy(actions = actions.mapTo(mutableListOf()) { it.deepCopy() })
}

/** Erro de um campo do rascunho; [field] usa os mesmos caminhos do [SessionParser]. */
data class DraftError(val field: String, val message: String)

/** Conversao entre rascunho e arquivo JSON, e validacao campo a campo. */
object SessionDrafts {

    private val FILE_NAME = Regex("[a-zA-Z0-9_-]+")

    /** Nome de arquivo (sem extensao) aceito pelo editor. */
    fun isValidFileName(file: String): Boolean = FILE_NAME.matches(file)

    /** `nome` sem a extensao `.json`, se houver. */
    fun baseName(fileName: String): String =
        if (fileName.endsWith(".json", ignoreCase = true)) fileName.dropLast(5) else fileName

    // --- leitura tolerante ---------------------------------------------------

    /**
     * Le [text] sem validar valores: cada campo vira o texto encontrado, para
     * que o editor mostre o arquivo mesmo com erros. Lanca
     * [JsonSyntaxException] apenas se o JSON for ilegivel.
     */
    fun fromJson(fileName: String, text: String): SessionDraft {
        val root = Json.parse(text) as? JsonValue.Obj
            ?: throw JsonSyntaxException("a raiz deve ser um objeto", JsonLocation(1, 1), 0)
        val e = root.entries
        val screen = e["screen"] as? JsonValue.Obj
        val actions = (e["actions"] as? JsonValue.Arr)?.items.orEmpty()
            .mapTo(mutableListOf()) { readAction(it as? JsonValue.Obj) }
        return SessionDraft(
            name = textOf(e["name"]),
            file = baseName(fileName),
            screenWidth = textOf(screen?.entries?.get("width")),
            screenHeight = textOf(screen?.entries?.get("height")),
            retries = textOf(e["retries"]),
            retryDelayMs = textOf(e["retryDelayMs"]),
            onLocateFailure = textOf(e["onLocateFailure"]),
            actions = actions
        )
    }

    private fun readAction(obj: JsonValue.Obj?): ActionDraft {
        if (obj == null) return ActionDraft(threshold = "", scales = "", clickIntervalMs = "", waitAfterMs = "")
        val e = obj.entries
        val clicks = (e["clicks"] as? JsonValue.Arr)?.items.orEmpty().mapTo(mutableListOf()) {
            val p = (it as? JsonValue.Obj)?.entries.orEmpty()
            ClickDraft(textOf(p["x"]), textOf(p["y"]), textOf(p["delayMs"]))
        }
        val mode = when {
            e.containsKey("clicks") -> ClickMode.POINTS
            e.containsKey("clickArea") -> ClickMode.AREA
            else -> ClickMode.CENTER
        }
        return ActionDraft(
            name = textOf(e["name"]),
            locate = textOf(e["locate"]),
            threshold = textOf(e["threshold"]),
            scales = (e["scales"] as? JsonValue.Arr)?.items?.joinToString(", ") { textOf(it) }
                ?: textOf(e["scales"]),
            searchArea = readArea(e["searchArea"]),
            clickMode = mode,
            clicks = clicks,
            clickArea = readArea(e["clickArea"]),
            clickIntervalMs = textOf(e["clickIntervalMs"]),
            waitAfterMs = textOf(e["waitAfterMs"]),
            call = textOf(e["call"]),
            sourceLine = obj.location.line
        )
    }

    private fun readArea(value: JsonValue?): AreaDraft {
        val e = (value as? JsonValue.Obj)?.entries ?: return AreaDraft()
        return AreaDraft(textOf(e["left"]), textOf(e["top"]), textOf(e["right"]), textOf(e["bottom"]))
    }

    private fun textOf(value: JsonValue?): String = when (value) {
        null, is JsonValue.Null -> ""
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.raw
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Obj, is JsonValue.Arr -> ""
    }

    // --- validacao -----------------------------------------------------------

    /** Problemas da sessao inteira (inclusive das acoes), na ordem dos campos. */
    fun validate(draft: SessionDraft): List<DraftError> {
        val errors = mutableListOf<DraftError>()
        if (draft.name.isBlank()) errors += DraftError("name", "campo obrigatório")
        if (draft.file.isBlank()) {
            errors += DraftError("file", "campo obrigatório")
        } else if (!isValidFileName(draft.file)) {
            errors += DraftError("file", "use apenas letras, números, _ e -")
        }
        val w = draft.screenWidth.isBlank()
        val h = draft.screenHeight.isBlank()
        if (w != h) {
            errors += DraftError(
                if (w) "screen.width" else "screen.height",
                "preencha largura e altura, ou deixe as duas vazias"
            )
        } else if (!w) {
            intError(draft.screenWidth, minimum = 1)?.let { errors += DraftError("screen.width", it) }
            intError(draft.screenHeight, minimum = 1)?.let { errors += DraftError("screen.height", it) }
        }
        optionalWholeError(draft.retries, Int.MAX_VALUE.toLong())?.let { errors += DraftError("retries", it) }
        optionalWholeError(draft.retryDelayMs)?.let { errors += DraftError("retryDelayMs", it) }
        if (draft.actions.isEmpty()) {
            errors += DraftError("actions", "a sessão precisa de ao menos uma ação")
        }
        draft.actions.forEachIndexed { index, action ->
            validateAction(action).forEach { errors += it.copy(field = "actions[$index].${it.field}") }
        }
        return errors
    }

    /** Problemas de uma acao; os campos nao trazem o prefixo `actions[i].`. */
    fun validateAction(action: ActionDraft): List<DraftError> {
        val errors = mutableListOf<DraftError>()
        if (action.name.isBlank()) errors += DraftError("name", "campo obrigatório")
        if (action.locate.isBlank()) errors += DraftError("locate", "campo obrigatório")
        if (action.threshold.isNotBlank()) {
            val value = action.threshold.trim().toDoubleOrNull()
            if (value == null || value.isNaN() || value < 0.0 || value > 1.0) {
                errors += DraftError("threshold", "informe um número de 0.0 a 1.0")
            }
        }
        if (action.scales.isNotBlank() && parseScales(action.scales) == null) {
            errors += DraftError("scales", "lista de números > 0 separados por vírgula")
        }
        if (!action.searchArea.isBlank) {
            areaErrors("searchArea", action.searchArea).let { errors += it }
        }
        when (action.clickMode) {
            ClickMode.CENTER -> Unit
            ClickMode.POINTS -> {
                if (action.clicks.isEmpty()) errors += DraftError("clicks", "adicione ao menos um ponto")
                action.clicks.forEachIndexed { i, point ->
                    intError(point.x, Int.MIN_VALUE)?.let { errors += DraftError("clicks[$i].x", it) }
                    intError(point.y, Int.MIN_VALUE)?.let { errors += DraftError("clicks[$i].y", it) }
                    optionalWholeError(point.delayMs)?.let { errors += DraftError("clicks[$i].delayMs", it) }
                }
            }
            ClickMode.AREA -> areaErrors("clickArea", action.clickArea).let { errors += it }
        }
        optionalWholeError(action.clickIntervalMs)?.let { errors += DraftError("clickIntervalMs", it) }
        optionalWholeError(action.waitAfterMs)?.let { errors += DraftError("waitAfterMs", it) }
        return errors
    }

    /** Escalas digitadas (`1.0, 0.9`) ou `null` se alguma for invalida. */
    fun parseScales(text: String): List<Double>? {
        val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val values = parts.map { it.toDoubleOrNull() ?: return null }
        return values.takeIf { list -> list.all { it > 0.0 && !it.isInfinite() } }
    }

    private fun areaErrors(label: String, area: AreaDraft): List<DraftError> {
        val errors = mutableListOf<DraftError>()
        intError(area.left, 0)?.let { errors += DraftError("$label.left", it) }
        intError(area.top, 0)?.let { errors += DraftError("$label.top", it) }
        intError(area.right, 0)?.let { errors += DraftError("$label.right", it) }
        intError(area.bottom, 0)?.let { errors += DraftError("$label.bottom", it) }
        if (errors.isNotEmpty()) return errors
        val a = area.toAreaOrNull() ?: return errors
        if (a.right <= a.left) errors += DraftError("$label.right", "deve ser maior que a esquerda")
        if (a.bottom <= a.top) errors += DraftError("$label.bottom", "deve ser maior que o topo")
        return errors
    }

    private fun intError(text: String, minimum: Int): String? {
        if (text.isBlank()) return "campo obrigatório"
        val value = text.trim().toIntOrNull() ?: return "informe um número inteiro"
        return if (value < minimum) "deve ser >= $minimum" else null
    }

    private fun optionalWholeError(text: String, maximum: Long = Long.MAX_VALUE): String? {
        if (text.isBlank()) return null
        val value = text.trim().toLongOrNull() ?: return "informe um número inteiro"
        return when {
            value < 0 -> "deve ser >= 0"
            value > maximum -> "valor muito grande"
            else -> null
        }
    }

    // --- serializacao --------------------------------------------------------

    /**
     * Serializa no esquema da spec (§2.2), na ordem dos campos da tabela.
     * Campos vazios sao omitidos. Exige um rascunho sem erros de [validate].
     */
    fun toJson(draft: SessionDraft): String {
        val w = JsonWriter()
        w.obj {
            field("name") { string(draft.name.trim()) }
            if (draft.screenWidth.isNotBlank() && draft.screenHeight.isNotBlank()) {
                field("screen") {
                    obj {
                        field("width") { raw(draft.screenWidth.trim().toInt().toString()) }
                        field("height") { raw(draft.screenHeight.trim().toInt().toString()) }
                    }
                }
            }
            long("retries", draft.retries)
            long("retryDelayMs", draft.retryDelayMs)
            if (draft.onLocateFailure.isNotBlank()) {
                field("onLocateFailure") { string(draft.onLocateFailure.trim()) }
            }
            field("actions") { array(draft.actions) { writeAction(it) } }
        }
        return w.toString()
    }

    private fun JsonWriter.writeAction(action: ActionDraft) = obj {
        field("name") { string(action.name.trim()) }
        field("locate") { string(action.locate.trim()) }
        if (action.threshold.isNotBlank()) {
            field("threshold") { raw(action.threshold.trim().toDouble().toString()) }
        }
        parseScales(action.scales)?.let { scales ->
            field("scales") { inlineArray(scales.map { it.toString() }) }
        }
        if (!action.searchArea.isBlank) field("searchArea") { area(action.searchArea) }
        when (action.clickMode) {
            ClickMode.CENTER -> Unit
            ClickMode.POINTS -> field("clicks") {
                array(action.clicks) { point ->
                    inlineObj(
                        buildList {
                            add("x" to point.x.trim().toInt().toString())
                            add("y" to point.y.trim().toInt().toString())
                            if (point.delayMs.isNotBlank()) {
                                add("delayMs" to point.delayMs.trim().toLong().toString())
                            }
                        }
                    )
                }
            }
            ClickMode.AREA -> field("clickArea") { area(action.clickArea) }
        }
        long("clickIntervalMs", action.clickIntervalMs)
        long("waitAfterMs", action.waitAfterMs)
        if (action.call.isNotBlank()) field("call") { string(action.call.trim()) }
    }

    private fun JsonWriter.area(area: AreaDraft) = inlineObj(
        listOf(
            "left" to area.left.trim().toInt().toString(),
            "top" to area.top.trim().toInt().toString(),
            "right" to area.right.trim().toInt().toString(),
            "bottom" to area.bottom.trim().toInt().toString()
        )
    )

    private fun JsonWriter.long(name: String, text: String) {
        if (text.isNotBlank()) field(name) { raw(text.trim().toLong().toString()) }
    }
}

/** Escritor de JSON indentado com 2 espacos, usado pelo editor de sessoes. */
class JsonWriter {
    private val out = StringBuilder()
    private var depth = 0
    private val first = ArrayDeque<Boolean>()

    override fun toString(): String = out.toString() + "\n"

    fun obj(body: JsonWriter.() -> Unit) {
        out.append('{')
        depth++
        first.addLast(true)
        body()
        val empty = first.removeLast()
        depth--
        if (!empty) newline()
        out.append('}')
    }

    fun field(name: String, value: JsonWriter.() -> Unit) {
        separator()
        out.append(quote(name)).append(": ")
        value()
    }

    fun <T> array(items: List<T>, item: JsonWriter.(T) -> Unit) {
        out.append('[')
        depth++
        first.addLast(true)
        items.forEach {
            separator()
            item(it)
        }
        val empty = first.removeLast()
        depth--
        if (!empty) newline()
        out.append(']')
    }

    /** `[1.0, 0.9]` numa linha so. */
    fun inlineArray(rawItems: List<String>) {
        out.append('[').append(rawItems.joinToString(", ")).append(']')
    }

    /** `{ "x": 1, "y": 2 }` numa linha so; valores ja em JSON. */
    fun inlineObj(rawFields: List<Pair<String, String>>) {
        out.append("{ ")
            .append(rawFields.joinToString(", ") { (k, v) -> "${quote(k)}: $v" })
            .append(" }")
    }

    fun string(value: String) {
        out.append(quote(value))
    }

    fun raw(value: String) {
        out.append(value)
    }

    private fun separator() {
        if (first.removeLast()) first.addLast(false) else {
            first.addLast(false)
            out.append(',')
        }
        newline()
    }

    private fun newline() {
        out.append('\n')
        repeat(depth) { out.append("  ") }
    }

    companion object {
        fun quote(value: String): String {
            val sb = StringBuilder("\"")
            value.forEach { c ->
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}

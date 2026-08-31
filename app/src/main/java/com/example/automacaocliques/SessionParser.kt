package com.example.automacaocliques

import kotlin.math.floor

/**
 * Erro de conteudo do arquivo de sessao. A mensagem traz o arquivo, a linha, o
 * campo culpado e, quando existe, o valor recebido.
 */
class SessionFormatException(
    message: String,
    /** Linha do arquivo onde o problema foi detectado, ou `null` se desconhecida. */
    val line: Int? = null,
    /** Caminho do campo culpado, como `actions[0].threshold`, ou `null`. */
    val field: String? = null
) : Exception(message)

/**
 * Converte o texto de um arquivo de sessao em [Session], aplicando os valores
 * padrao dos campos ausentes e recusando tipos e faixas invalidas.
 */
object SessionParser {

    /** Le [text] como a sessao do arquivo [fileName]. */
    fun parse(fileName: String, text: String): Session {
        val root = try {
            Json.parse(text)
        } catch (e: JsonSyntaxException) {
            throw SessionFormatException(
                "$fileName:${e.location.line}:${e.location.column}: ${e.message}",
                line = e.location.line
            )
        }
        val obj = root as? JsonValue.Obj
            ?: fail(fileName, "raiz", "esperado um objeto", root)

        val name = requireString(fileName, obj, "name", "name")
        val screen = obj.entries["screen"]?.let { readSize(fileName, it) }
        val retries = optionalInt(fileName, obj, "retries", Session.DEFAULT_RETRIES, minimum = 0)
        val retryDelayMs =
            optionalLong(fileName, obj, "retryDelayMs", Session.DEFAULT_RETRY_DELAY_MS)

        val actionsValue = obj.entries["actions"]
            ?: fail(fileName, "actions", "campo obrigatorio ausente", at = obj.location)
        val actionsList = actionsValue as? JsonValue.Arr
            ?: fail(fileName, "actions", "esperada uma lista de acoes", actionsValue)
        if (actionsList.items.isEmpty()) {
            fail(
                fileName,
                "actions",
                "a sessao precisa de ao menos uma acao",
                at = actionsList.location
            )
        }
        val actions = actionsList.items.mapIndexed { index, item ->
            val action = item as? JsonValue.Obj
                ?: fail(fileName, "actions[$index]", "esperado um objeto", item)
            readAction(fileName, index, action)
        }

        return Session(
            name = name,
            screen = screen,
            retries = retries,
            retryDelayMs = retryDelayMs,
            actions = actions,
            fileName = fileName
        )
    }

    private fun readAction(fileName: String, index: Int, obj: JsonValue.Obj): SessionAction {
        val prefix = "actions[$index]"
        val name = requireString(fileName, obj, "name", "$prefix.name")
        val locate = requireString(fileName, obj, "locate", "$prefix.locate")

        val threshold = when (val value = obj.entries["threshold"]) {
            null -> TemplateMatcher.DEFAULT_THRESHOLD
            is JsonValue.Num -> value.value
            else -> fail(
                fileName,
                "$prefix.threshold",
                "esperado um numero de 0.0 a 1.0",
                value
            )
        }
        if (threshold < 0.0 || threshold > 1.0) {
            fail(
                fileName,
                "$prefix.threshold",
                "fora da faixa 0.0-1.0",
                obj.entries["threshold"]
            )
        }

        val scales = when (val value = obj.entries["scales"]) {
            null -> TemplateMatcher.DEFAULT_SCALES
            is JsonValue.Arr -> value.items.mapIndexed { position, item ->
                (item as? JsonValue.Num)?.value
                    ?: fail(fileName, "$prefix.scales[$position]", "esperado um numero", item)
            }
            else -> fail(fileName, "$prefix.scales", "esperada uma lista de numeros", value)
        }
        val scalesValue = obj.entries["scales"]
        if (scales.isEmpty()) {
            fail(fileName, "$prefix.scales", "a lista nao pode ser vazia", scalesValue)
        }
        scales.forEachIndexed { position, scale ->
            if (scale <= 0.0) {
                fail(
                    fileName,
                    "$prefix.scales[$position]",
                    "escalas devem ser > 0",
                    (scalesValue as? JsonValue.Arr)?.items?.get(position)
                )
            }
        }

        val searchArea = obj.entries["searchArea"]?.let { readArea(fileName, prefix, it) }
        val clicks = readClicks(fileName, prefix, obj.entries["clicks"])

        return SessionAction(
            name = name,
            locate = locate,
            threshold = threshold,
            scales = scales,
            searchArea = searchArea,
            clicks = clicks,
            clickIntervalMs = optionalLong(
                fileName,
                obj,
                "clickIntervalMs",
                SessionAction.DEFAULT_CLICK_INTERVAL_MS,
                label = "$prefix.clickIntervalMs"
            ),
            waitAfterMs = optionalLong(
                fileName,
                obj,
                "waitAfterMs",
                SessionAction.DEFAULT_WAIT_AFTER_MS,
                label = "$prefix.waitAfterMs"
            ),
            call = when (val value = obj.entries["call"]) {
                null -> null
                is JsonValue.Null -> null
                is JsonValue.Str -> value.value.takeIf { it.isNotBlank() }
                    ?: fail(fileName, "$prefix.call", "nome de sessao vazio", value)
                else -> fail(fileName, "$prefix.call", "esperado um texto", value)
            },
            sourceLine = obj.location.line
        )
    }

    private fun readClicks(
        fileName: String,
        prefix: String,
        value: JsonValue?
    ): List<ClickPoint> {
        if (value == null) return emptyList()
        val list = value as? JsonValue.Arr
            ?: fail(fileName, "$prefix.clicks", "esperada uma lista de pontos", value)
        return list.items.mapIndexed { index, item ->
            val label = "$prefix.clicks[$index]"
            val point = item as? JsonValue.Obj
                ?: fail(fileName, label, "esperado um objeto {x, y}", item)
            ClickPoint(
                x = requireInt(fileName, point, "x", "$label.x"),
                y = requireInt(fileName, point, "y", "$label.y"),
                delayMs = when (val delay = point.entries["delayMs"]) {
                    null -> null
                    is JsonValue.Num -> whole(fileName, "$label.delayMs", delay).also {
                        if (it < 0) fail(fileName, "$label.delayMs", "deve ser >= 0", delay)
                    }
                    else -> fail(fileName, "$label.delayMs", "esperado um numero", delay)
                }
            )
        }
    }

    private fun readArea(fileName: String, prefix: String, value: JsonValue): Area {
        val label = "$prefix.searchArea"
        val obj = value as? JsonValue.Obj
            ?: fail(fileName, label, "esperado um objeto", value)
        val area = Area(
            left = requireInt(fileName, obj, "left", "$label.left"),
            top = requireInt(fileName, obj, "top", "$label.top"),
            right = requireInt(fileName, obj, "right", "$label.right"),
            bottom = requireInt(fileName, obj, "bottom", "$label.bottom")
        )
        if (area.right <= area.left) {
            fail(
                fileName,
                "$label.right",
                "right (${area.right}) deve ser maior que left (${area.left})",
                obj.entries["right"]
            )
        }
        if (area.bottom <= area.top) {
            fail(
                fileName,
                "$label.bottom",
                "bottom (${area.bottom}) deve ser maior que top (${area.top})",
                obj.entries["bottom"]
            )
        }
        if (area.left < 0) {
            fail(fileName, "$label.left", "coordenada negativa", obj.entries["left"])
        }
        if (area.top < 0) {
            fail(fileName, "$label.top", "coordenada negativa", obj.entries["top"])
        }
        return area
    }

    private fun readSize(fileName: String, value: JsonValue): Size {
        val obj = value as? JsonValue.Obj
            ?: fail(fileName, "screen", "esperado um objeto {width, height}", value)
        val size = Size(
            width = requireInt(fileName, obj, "width", "screen.width"),
            height = requireInt(fileName, obj, "height", "screen.height")
        )
        if (size.width <= 0) {
            fail(fileName, "screen.width", "deve ser > 0", obj.entries["width"])
        }
        if (size.height <= 0) {
            fail(fileName, "screen.height", "deve ser > 0", obj.entries["height"])
        }
        return size
    }

    private fun requireString(
        fileName: String,
        obj: JsonValue.Obj,
        field: String,
        label: String
    ): String {
        val value = obj.entries[field]
            ?: fail(fileName, label, "campo obrigatorio ausente", at = obj.location)
        val text = (value as? JsonValue.Str)?.value
            ?: fail(fileName, label, "esperado um texto", value)
        if (text.isBlank()) fail(fileName, label, "campo obrigatorio vazio", value)
        return text
    }

    private fun requireInt(
        fileName: String,
        obj: JsonValue.Obj,
        field: String,
        label: String
    ): Int {
        val value = obj.entries[field]
            ?: fail(fileName, label, "campo obrigatorio ausente", at = obj.location)
        val number = value as? JsonValue.Num
            ?: fail(fileName, label, "esperado um numero", value)
        val whole = whole(fileName, label, number)
        if (whole < Int.MIN_VALUE.toLong() || whole > Int.MAX_VALUE.toLong()) {
            fail(fileName, label, "fora da faixa de um inteiro de 32 bits", number)
        }
        return whole.toInt()
    }

    private fun optionalInt(
        fileName: String,
        obj: JsonValue.Obj,
        field: String,
        default: Int,
        minimum: Int
    ): Int {
        val value = obj.entries[field] ?: return default
        val number = value as? JsonValue.Num
            ?: fail(fileName, field, "esperado um numero", value)
        val result = whole(fileName, field, number)
        if (result < minimum) fail(fileName, field, "deve ser >= $minimum", number)
        if (result > Int.MAX_VALUE.toLong()) {
            fail(fileName, field, "fora da faixa de um inteiro de 32 bits", number)
        }
        return result.toInt()
    }

    private fun optionalLong(
        fileName: String,
        obj: JsonValue.Obj,
        field: String,
        default: Long,
        label: String = field
    ): Long {
        val value = obj.entries[field] ?: return default
        val number = value as? JsonValue.Num
            ?: fail(fileName, label, "esperado um numero de milissegundos", value)
        val result = whole(fileName, label, number)
        if (result < 0) fail(fileName, label, "deve ser >= 0", number)
        return result
    }

    /**
     * Converte para inteiro recusando fracoes: truncar aceitaria silenciosamente
     * coisas como `-0.5` (que virava 0) e coordenadas quebradas.
     */
    private fun whole(fileName: String, label: String, number: JsonValue.Num): Long {
        val value = number.value
        if (value.isNaN() || value != floor(value) ||
            value < Long.MIN_VALUE.toDouble() || value > Long.MAX_VALUE.toDouble()
        ) {
            fail(fileName, label, "esperado um numero inteiro", number)
        }
        return value.toLong()
    }

    /** Erro apontando o valor recebido e a linha onde ele aparece. */
    private fun fail(
        fileName: String,
        field: String,
        reason: String,
        value: JsonValue?
    ): Nothing = fail(
        fileName = fileName,
        field = field,
        reason = if (value == null) reason else "$reason (recebido: ${value.describe()})",
        at = value?.location
    )

    /** Erro sem valor culpado, apontando apenas a posicao [at]. */
    private fun fail(
        fileName: String,
        field: String,
        reason: String,
        at: JsonLocation?
    ): Nothing {
        val place = at?.let { "$fileName:${it.line}:${it.column}" } ?: fileName
        throw SessionFormatException(
            "$place: campo '$field': $reason",
            line = at?.line,
            field = field
        )
    }
}

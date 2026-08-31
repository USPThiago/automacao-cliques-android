package com.example.automacaocliques

import kotlin.math.floor

/** Erro de conteudo do arquivo de sessao, com o arquivo e o campo culpados. */
class SessionFormatException(message: String) : Exception(message)

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
            throw SessionFormatException("$fileName: ${e.message}")
        }
        val obj = root as? JsonValue.Obj
            ?: fail(fileName, "raiz", "esperado um objeto, veio ${root.typeName()}")

        val name = requireString(fileName, obj, "name")
        val screen = obj.entries["screen"]?.let { readSize(fileName, it) }
        val retries = optionalInt(fileName, obj, "retries", Session.DEFAULT_RETRIES, minimum = 0)
        val retryDelayMs =
            optionalLong(fileName, obj, "retryDelayMs", Session.DEFAULT_RETRY_DELAY_MS)

        val actionsValue = obj.entries["actions"] as? JsonValue.Arr
            ?: fail(fileName, "actions", "esperada uma lista de acoes")
        if (actionsValue.items.isEmpty()) {
            fail(fileName, "actions", "a sessao precisa de ao menos uma acao")
        }
        val actions = actionsValue.items.mapIndexed { index, item ->
            val action = item as? JsonValue.Obj
                ?: fail(fileName, "actions[$index]", "esperado um objeto")
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
        val name = requireString(fileName, obj, "$prefix.name", obj.entries["name"])
        val locate = requireString(fileName, obj, "$prefix.locate", obj.entries["locate"])

        val threshold = when (val value = obj.entries["threshold"]) {
            null -> TemplateMatcher.DEFAULT_THRESHOLD
            is JsonValue.Num -> value.value
            else -> fail(fileName, "$prefix.threshold", "esperado um numero de 0.0 a 1.0")
        }
        if (threshold < 0.0 || threshold > 1.0) {
            fail(fileName, "$prefix.threshold", "fora da faixa 0.0-1.0: $threshold")
        }

        val scales = when (val value = obj.entries["scales"]) {
            null -> TemplateMatcher.DEFAULT_SCALES
            is JsonValue.Arr -> value.items.map { item ->
                (item as? JsonValue.Num)?.value
                    ?: fail(fileName, "$prefix.scales", "esperada uma lista de numeros")
            }
            else -> fail(fileName, "$prefix.scales", "esperada uma lista de numeros")
        }
        if (scales.isEmpty()) fail(fileName, "$prefix.scales", "a lista nao pode ser vazia")
        if (scales.any { it <= 0.0 }) fail(fileName, "$prefix.scales", "escalas devem ser > 0")

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
                null, JsonValue.Null -> null
                is JsonValue.Str -> value.value.takeIf { it.isNotBlank() }
                    ?: fail(fileName, "$prefix.call", "nome de sessao vazio")
                else -> fail(fileName, "$prefix.call", "esperado um texto")
            }
        )
    }

    private fun readClicks(
        fileName: String,
        prefix: String,
        value: JsonValue?
    ): List<ClickPoint> {
        if (value == null) return emptyList()
        val list = value as? JsonValue.Arr
            ?: fail(fileName, "$prefix.clicks", "esperada uma lista de pontos")
        return list.items.mapIndexed { index, item ->
            val point = item as? JsonValue.Obj
                ?: fail(fileName, "$prefix.clicks[$index]", "esperado um objeto {x, y}")
            val label = "$prefix.clicks[$index]"
            ClickPoint(
                x = requireInt(fileName, point, "x", label),
                y = requireInt(fileName, point, "y", label),
                delayMs = when (val delay = point.entries["delayMs"]) {
                    null -> null
                    is JsonValue.Num -> whole(fileName, "$label.delayMs", delay.value).also {
                        if (it < 0) fail(fileName, "$label.delayMs", "deve ser >= 0")
                    }
                    else -> fail(fileName, "$label.delayMs", "esperado um numero")
                }
            )
        }
    }

    private fun readArea(fileName: String, prefix: String, value: JsonValue): Area {
        val obj = value as? JsonValue.Obj
            ?: fail(fileName, "$prefix.searchArea", "esperado um objeto")
        val label = "$prefix.searchArea"
        val area = Area(
            left = requireInt(fileName, obj, "left", label),
            top = requireInt(fileName, obj, "top", label),
            right = requireInt(fileName, obj, "right", label),
            bottom = requireInt(fileName, obj, "bottom", label)
        )
        if (area.right <= area.left) fail(fileName, label, "right deve ser maior que left")
        if (area.bottom <= area.top) fail(fileName, label, "bottom deve ser maior que top")
        if (area.left < 0 || area.top < 0) fail(fileName, label, "coordenadas negativas")
        return area
    }

    private fun readSize(fileName: String, value: JsonValue): Size {
        val obj = value as? JsonValue.Obj
            ?: fail(fileName, "screen", "esperado um objeto {width, height}")
        val size = Size(
            width = requireInt(fileName, obj, "width", "screen"),
            height = requireInt(fileName, obj, "height", "screen")
        )
        if (size.width <= 0 || size.height <= 0) fail(fileName, "screen", "dimensoes devem ser > 0")
        return size
    }

    private fun requireString(
        fileName: String,
        obj: JsonValue.Obj,
        field: String,
        value: JsonValue? = obj.entries[field]
    ): String {
        val text = (value as? JsonValue.Str)?.value
            ?: fail(fileName, field, "campo obrigatorio ausente ou nao textual")
        if (text.isBlank()) fail(fileName, field, "campo obrigatorio vazio")
        return text
    }

    private fun requireInt(
        fileName: String,
        obj: JsonValue.Obj,
        field: String,
        label: String
    ): Int {
        val value = obj.entries[field] as? JsonValue.Num
            ?: fail(fileName, "$label.$field", "campo obrigatorio ausente ou nao numerico")
        return whole(fileName, "$label.$field", value.value).toInt()
    }

    private fun optionalInt(
        fileName: String,
        obj: JsonValue.Obj,
        field: String,
        default: Int,
        minimum: Int
    ): Int {
        val value = obj.entries[field] ?: return default
        val number = (value as? JsonValue.Num)?.value
            ?: fail(fileName, field, "esperado um numero")
        val result = whole(fileName, field, number).toInt()
        if (result < minimum) fail(fileName, field, "deve ser >= $minimum")
        return result
    }

    private fun optionalLong(
        fileName: String,
        obj: JsonValue.Obj,
        field: String,
        default: Long,
        label: String = field
    ): Long {
        val value = obj.entries[field] ?: return default
        val number = (value as? JsonValue.Num)?.value
            ?: fail(fileName, label, "esperado um numero de milissegundos")
        val result = whole(fileName, label, number)
        if (result < 0) fail(fileName, label, "deve ser >= 0")
        return result
    }

    /**
     * Converte para inteiro recusando fracoes: truncar aceitaria silenciosamente
     * coisas como `-0.5` (que virava 0) e coordenadas quebradas.
     */
    private fun whole(fileName: String, label: String, number: Double): Long {
        if (number.isNaN() || number != floor(number) ||
            number < Long.MIN_VALUE.toDouble() || number > Long.MAX_VALUE.toDouble()
        ) {
            fail(fileName, label, "esperado um numero inteiro: $number")
        }
        return number.toLong()
    }

    private fun fail(fileName: String, field: String, reason: String): Nothing =
        throw SessionFormatException("$fileName: campo '$field': $reason")
}

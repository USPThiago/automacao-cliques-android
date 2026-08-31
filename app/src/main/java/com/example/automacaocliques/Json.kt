package com.example.automacaocliques

/**
 * Leitor de JSON minimo, sem dependencia do Android, para que o parser de
 * sessoes possa ser testado na JVM (o `org.json` do Android e apenas um esqueleto
 * nos testes unitarios).
 */

/** Posicao de um trecho do texto JSON, em linha e coluna contadas a partir de 1. */
data class JsonLocation(val line: Int, val column: Int) {
    /** Trecho `linha X, coluna Y` usado nas mensagens de erro. */
    fun describe(): String = "linha $line, coluna $column"
}

sealed class JsonValue {

    /** Onde o valor comeca no arquivo, para localizar o erro nas mensagens. */
    abstract val location: JsonLocation

    data class Obj(
        val entries: Map<String, JsonValue>,
        /** Posicao de cada nome de campo, para apontar o campo culpado. */
        val keyLocations: Map<String, JsonLocation>,
        override val location: JsonLocation
    ) : JsonValue()

    data class Arr(
        val items: List<JsonValue>,
        override val location: JsonLocation
    ) : JsonValue()

    data class Str(val value: String, override val location: JsonLocation) : JsonValue()

    data class Num(
        val value: Double,
        /** Texto original do numero, preservado para as mensagens de erro. */
        val raw: String,
        override val location: JsonLocation
    ) : JsonValue()

    data class Bool(val value: Boolean, override val location: JsonLocation) : JsonValue()

    data class Null(override val location: JsonLocation) : JsonValue()

    /** Nome do tipo em portugues, usado nas mensagens de erro. */
    fun typeName(): String = when (this) {
        is Obj -> "objeto"
        is Arr -> "lista"
        is Str -> "texto"
        is Num -> "numero"
        is Bool -> "booleano"
        is Null -> "nulo"
    }

    /** Tipo e valor recebidos, como `texto "abc"` ou `lista com 2 itens`. */
    fun describe(): String = when (this) {
        is Obj -> if (entries.isEmpty()) "objeto vazio" else "objeto com ${entries.size} campo(s)"
        is Arr -> if (items.isEmpty()) "lista vazia" else "lista com ${items.size} item(ns)"
        is Str -> "texto \"$value\""
        is Num -> "numero $raw"
        is Bool -> "booleano $value"
        is Null -> "nulo"
    }
}

/** Erro de sintaxe do JSON, com a linha, a coluna e a posicao do problema. */
class JsonSyntaxException(
    message: String,
    val location: JsonLocation,
    val index: Int
) : Exception(message)

/** Analisador recursivo descendente de JSON. */
object Json {

    /** Converte [text] em [JsonValue] ou lanca [JsonSyntaxException]. */
    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("conteudo extra apos o fim do JSON")
        return value
    }

    /** Mapa de deslocamento para linha/coluna, montado uma vez por arquivo. */
    private class LineIndex(text: String) {
        /** Deslocamento inicial de cada linha; `starts[0]` e sempre 0. */
        private val starts: IntArray = buildStarts(text)

        fun locationOf(index: Int): JsonLocation {
            val safeIndex = index.coerceAtLeast(0)
            var low = 0
            var high = starts.size - 1
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (starts[middle] <= safeIndex) low = middle else high = middle - 1
            }
            return JsonLocation(line = low + 1, column = safeIndex - starts[low] + 1)
        }

        private fun buildStarts(text: String): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            text.forEachIndexed { index, c ->
                if (c == '\n') starts += index + 1
            }
            return starts.toIntArray()
        }
    }

    private class Reader(private val text: String) {
        private val lines = LineIndex(text)
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun fail(reason: String): Nothing = failAt(index, reason)

        fun failAt(at: Int, reason: String): Nothing {
            val location = lines.locationOf(at)
            throw JsonSyntaxException(
                "JSON invalido na ${location.describe()} (posicao $at): $reason",
                location,
                at
            )
        }

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): JsonValue {
            if (atEnd()) fail("fim inesperado")
            val start = index
            return when (text[index]) {
                '{' -> readObject(start)
                '[' -> readArray(start)
                '"' -> JsonValue.Str(readString(), lines.locationOf(start))
                't' -> readLiteral("true", JsonValue.Bool(true, lines.locationOf(start)))
                'f' -> readLiteral("false", JsonValue.Bool(false, lines.locationOf(start)))
                'n' -> readLiteral("null", JsonValue.Null(lines.locationOf(start)))
                else -> readNumber()
            }
        }

        private fun readObject(start: Int): JsonValue {
            index++ // '{'
            val entries = LinkedHashMap<String, JsonValue>()
            val keyLocations = LinkedHashMap<String, JsonLocation>()
            skipWhitespace()
            if (!atEnd() && text[index] == '}') {
                index++
                return JsonValue.Obj(entries, keyLocations, lines.locationOf(start))
            }
            while (true) {
                skipWhitespace()
                if (atEnd()) failAt(start, "objeto nao fechado")
                if (text[index] != '"') fail("esperado nome de campo entre aspas")
                val keyStart = index
                val key = readString()
                skipWhitespace()
                if (atEnd() || text[index] != ':') fail("esperado ':' apos o campo '$key'")
                index++
                skipWhitespace()
                entries[key] = readValue()
                keyLocations[key] = lines.locationOf(keyStart)
                skipWhitespace()
                if (atEnd()) failAt(start, "objeto nao fechado")
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.Obj(entries, keyLocations, lines.locationOf(start))
                    }
                    else -> fail("esperado ',' ou '}'")
                }
            }
        }

        private fun readArray(start: Int): JsonValue {
            index++ // '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[index] == ']') {
                index++
                return JsonValue.Arr(items, lines.locationOf(start))
            }
            while (true) {
                skipWhitespace()
                if (atEnd()) failAt(start, "lista nao fechada")
                items += readValue()
                skipWhitespace()
                if (atEnd()) failAt(start, "lista nao fechada")
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.Arr(items, lines.locationOf(start))
                    }
                    else -> fail("esperado ',' ou ']'")
                }
            }
        }

        private fun readString(): String {
            val start = index
            index++ // '"'
            val out = StringBuilder()
            while (true) {
                if (atEnd()) failAt(start, "texto nao fechado")
                when (val c = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> out.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            val start = index - 1 // a barra invertida
            if (atEnd()) failAt(start, "escape incompleto")
            return when (val c = text[index++]) {
                '"', '\\', '/' -> c
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > text.length) failAt(start, "escape unicode incompleto")
                    val digits = text.substring(index, index + 4)
                    val code = digits.toIntOrNull(16)
                        ?: failAt(start, "escape unicode invalido: '\\u$digits'")
                    index += 4
                    code.toChar()
                }
                else -> failAt(start, "escape desconhecido '\\$c'")
            }
        }

        private fun readNumber(): JsonValue {
            val start = index
            if (!atEnd() && (text[index] == '-' || text[index] == '+')) index++
            while (!atEnd() && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val raw = text.substring(start, index)
            val value = raw.toDoubleOrNull()
                ?: failAt(start, if (raw.isEmpty()) "valor desconhecido" else "numero invalido: '$raw'")
            return JsonValue.Num(value, raw, lines.locationOf(start))
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, index)) fail("valor desconhecido")
            index += literal.length
            return value
        }
    }
}

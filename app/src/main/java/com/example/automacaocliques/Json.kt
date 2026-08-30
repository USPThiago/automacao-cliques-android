package com.example.automacaocliques

/**
 * Leitor de JSON minimo, sem dependencia do Android, para que o parser de
 * sessoes possa ser testado na JVM (o `org.json` do Android e apenas um esqueleto
 * nos testes unitarios).
 */
sealed class JsonValue {

    data class Obj(val entries: Map<String, JsonValue>) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Num(val value: Double) : JsonValue()

    data class Bool(val value: Boolean) : JsonValue()

    object Null : JsonValue()

    /** Nome do tipo em portugues, usado nas mensagens de erro. */
    fun typeName(): String = when (this) {
        is Obj -> "objeto"
        is Arr -> "lista"
        is Str -> "texto"
        is Num -> "numero"
        is Bool -> "booleano"
        Null -> "nulo"
    }
}

/** Erro de sintaxe do JSON, com a posicao aproximada do problema. */
class JsonSyntaxException(message: String) : Exception(message)

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

    private class Reader(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun fail(reason: String): Nothing =
            throw JsonSyntaxException("JSON invalido na posicao $index: $reason")

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): JsonValue {
            if (atEnd()) fail("fim inesperado")
            return when (text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> readNumber()
            }
        }

        private fun readObject(): JsonValue {
            index++ // '{'
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[index] == '}') {
                index++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[index] != '"') fail("esperado nome de campo entre aspas")
                val key = readString()
                skipWhitespace()
                if (atEnd() || text[index] != ':') fail("esperado ':' apos o campo '$key'")
                index++
                skipWhitespace()
                entries[key] = readValue()
                skipWhitespace()
                if (atEnd()) fail("objeto nao fechado")
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.Obj(entries)
                    }
                    else -> fail("esperado ',' ou '}'")
                }
            }
        }

        private fun readArray(): JsonValue {
            index++ // '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd() && text[index] == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue()
                skipWhitespace()
                if (atEnd()) fail("lista nao fechada")
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("esperado ',' ou ']'")
                }
            }
        }

        private fun readString(): String {
            index++ // '"'
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("texto nao fechado")
                when (val c = text[index++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(readEscape())
                    else -> out.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) fail("escape incompleto")
            return when (val c = text[index++]) {
                '"', '\\', '/' -> c
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > text.length) fail("escape unicode incompleto")
                    val code = text.substring(index, index + 4).toIntOrNull(16)
                        ?: fail("escape unicode invalido")
                    index += 4
                    code.toChar()
                }
                else -> fail("escape desconhecido '\\$c'")
            }
        }

        private fun readNumber(): JsonValue {
            val start = index
            if (!atEnd() && (text[index] == '-' || text[index] == '+')) index++
            while (!atEnd() && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val raw = text.substring(start, index)
            val value = raw.toDoubleOrNull() ?: run {
                index = start
                fail("numero invalido")
            }
            return JsonValue.Num(value)
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, index)) fail("valor desconhecido")
            index += literal.length
            return value
        }
    }
}

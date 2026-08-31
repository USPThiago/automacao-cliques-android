package com.example.automacaocliques

import kotlin.math.roundToInt

/** Origem do texto dos arquivos de sessao (disco no app, memoria nos testes). */
fun interface SessionSource {
    /** Conteudo de [fileName] ou `null` se o arquivo nao existir ou nao puder ser lido. */
    fun read(fileName: String): String?
}

/** Dimensoes dos templates disponiveis, sem carregar a imagem inteira. */
fun interface TemplateSizes {
    /** Tamanho do template [name] (com ou sem extensao) ou `null` se ausente. */
    fun sizeOf(name: String): Size?
}

/** Resultado da carga inicial: todas as sessoes alcancaveis ou o primeiro erro. */
sealed class SessionLoad {

    data class Ok(val main: Session, val sessions: Map<String, Session>) : SessionLoad()

    data class Failure(val reason: String) : SessionLoad()
}

/**
 * Carrega e valida o grafo de sessoes a partir de `mainSession.json`, aplicando
 * os sete itens da carga inicial (§4.1 da especificacao do MVP 4). Ciclos entre
 * sessoes sao permitidos e nao causam recursao infinita.
 */
object SessionValidator {

    const val MAIN_SESSION = "mainSession.json"

    private const val EXTENSION = ".json"

    /** Nome de arquivo de uma sessao referenciada em `call`. */
    fun fileNameOf(name: String): String =
        if (name.endsWith(EXTENSION, ignoreCase = true)) name else "$name$EXTENSION"

    fun load(
        source: SessionSource,
        templates: TemplateSizes,
        screen: Size,
        mainFileName: String = MAIN_SESSION
    ): SessionLoad {
        val loaded = LinkedHashMap<String, Session>()
        val pending = ArrayDeque(listOf(mainFileName))

        while (pending.isNotEmpty()) {
            val fileName = pending.removeFirst()
            if (loaded.containsKey(fileName)) continue

            val text = source.read(fileName)
                ?: return SessionLoad.Failure("$fileName nao encontrado ou ilegivel")
            val session = try {
                SessionParser.parse(fileName, text)
            } catch (e: SessionFormatException) {
                return SessionLoad.Failure(e.message.orEmpty())
            }
            validateActions(session, templates, screen)?.let { return SessionLoad.Failure(it) }

            loaded[fileName] = session
            session.actions.mapNotNull { it.call }.forEach { pending.addLast(fileNameOf(it)) }
        }

        val main = loaded[mainFileName]
            ?: return SessionLoad.Failure("$mainFileName nao encontrado ou ilegivel")
        return SessionLoad.Ok(main, loaded)
    }

    /** Primeiro problema das acoes de [session], ou `null` se estiverem todas validas. */
    private fun validateActions(
        session: Session,
        templates: TemplateSizes,
        screen: Size
    ): String? {
        val scale = ScreenScale(real = screen, reference = session.screen)
        for (action in session.actions) {
            val label = "${session.fileName}: acao '${action.name}'"
            val templateSize = templates.sizeOf(action.locate)
                ?: return "$label: template '${action.locate}' nao encontrado em templates/"

            val area = action.searchArea?.let { scale.scale(it) } ?: Area(0, 0, screen.width, screen.height)
            if (action.searchArea != null &&
                (area.right > screen.width || area.bottom > screen.height)
            ) {
                return "$label: searchArea ${area.describe()} fora da tela ${screen.describe()}"
            }

            val smallestScale = action.scales.min()
            val factor = scale.templateFactor * smallestScale
            val width = (templateSize.width * factor).roundToInt()
            val height = (templateSize.height * factor).roundToInt()
            if (width > area.width || height > area.height) {
                return "$label: template '${action.locate}' (${width}x$height apos escala) " +
                    "nao cabe na area ${area.describe()}"
            }
        }
        return null
    }
}

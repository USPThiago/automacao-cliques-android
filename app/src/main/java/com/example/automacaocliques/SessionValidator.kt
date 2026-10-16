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

    /** Nome de arquivo de uma sessao referenciada em `call` ou `onLocateFailure`. */
    fun fileNameOf(name: String): String =
        if (name.endsWith(EXTENSION, ignoreCase = true)) name else "$name$EXTENSION"

    /** Arquivo a carregar e de onde ele foi chamado, para localizar o erro. */
    private data class Pending(val fileName: String, val calledFrom: String? = null)

    fun load(
        source: SessionSource,
        templates: TemplateSizes,
        screen: Size,
        mainFileName: String = MAIN_SESSION
    ): SessionLoad {
        var failure: String? = null
        val loaded = walk(source, templates, screen, mainFileName) {
            failure = it
            false
        }
        failure?.let { return SessionLoad.Failure(it) }
        val main = loaded[mainFileName]
            ?: return SessionLoad.Failure("$mainFileName nao encontrado ou ilegivel")
        return SessionLoad.Ok(main, loaded)
    }

    /**
     * Todos os problemas do grafo a partir de [mainFileName], no mesmo formato
     * de [load], sem parar no primeiro. Lista vazia = carga OK.
     */
    fun errors(
        source: SessionSource,
        templates: TemplateSizes,
        screen: Size,
        mainFileName: String = MAIN_SESSION
    ): List<String> {
        val errors = mutableListOf<String>()
        walk(source, templates, screen, mainFileName) {
            errors += it
            true
        }
        return errors
    }

    /**
     * Percorre o grafo; [onError] recebe cada problema e devolve `true` para
     * continuar. Sessoes com erro nas acoes ainda tem as chamadas seguidas.
     */
    private fun walk(
        source: SessionSource,
        templates: TemplateSizes,
        screen: Size,
        mainFileName: String,
        onError: (String) -> Boolean
    ): Map<String, Session> {
        val loaded = LinkedHashMap<String, Session>()
        val visited = HashSet<String>()
        val pending = ArrayDeque(listOf(Pending(mainFileName)))

        while (pending.isNotEmpty()) {
            val (fileName, calledFrom) = pending.removeFirst()
            if (!visited.add(fileName)) continue

            val text = source.read(fileName)
            if (text == null) {
                val reason = "$fileName nao encontrado ou ilegivel" +
                    (calledFrom?.let { " (chamado em $it)" } ?: "")
                if (onError(reason)) continue else return loaded
            }
            val session = try {
                SessionParser.parse(fileName, text)
            } catch (e: SessionFormatException) {
                if (onError(e.message.orEmpty())) continue else return loaded
            }
            validateActions(session, templates, screen).forEach {
                if (!onError(it)) return loaded
            }

            loaded[fileName] = session
            session.onLocateFailure?.let { target ->
                pending.addLast(
                    Pending(
                        fileName = fileNameOf(target),
                        calledFrom = "${session.fileName}, campo 'onLocateFailure'"
                    )
                )
            }
            session.actions.forEach { action ->
                val call = action.call ?: return@forEach
                pending.addLast(
                    Pending(
                        fileName = fileNameOf(call),
                        calledFrom = "${locationOf(session, action)}, campo 'call'"
                    )
                )
            }
        }
        return loaded
    }

    /** Problemas das acoes de [session], no maximo um por acao. */
    private fun validateActions(
        session: Session,
        templates: TemplateSizes,
        screen: Size
    ): List<String> = session.actions.mapNotNull { validateAction(session, it, templates, screen) }

    private fun validateAction(
        session: Session,
        action: SessionAction,
        templates: TemplateSizes,
        screen: Size
    ): String? {
        val scale = ScreenScale(real = screen, reference = session.screen)
        val label = "${locationOf(session, action)}: acao '${action.name}'"
        val templateSize = templates.sizeOf(action.locate)
            ?: return "$label: template '${action.locate}' nao encontrado em templates/"

        val area = action.searchArea?.let { scale.scale(it) } ?: Area(0, 0, screen.width, screen.height)
        if (action.searchArea != null &&
            (area.right > screen.width || area.bottom > screen.height)
        ) {
            return "$label: searchArea ${area.describe()} fora da tela ${screen.describe()}"
        }
        if (action.clickArea != null) {
            val clickArea = scale.scale(action.clickArea)
            if (clickArea.width <= 0 || clickArea.height <= 0 ||
                clickArea.right > screen.width || clickArea.bottom > screen.height
            ) {
                return "$label: clickArea ${clickArea.describe()} fora da tela ${screen.describe()}"
            }
        }

        val smallestScale = action.scales.min()
        val factor = scale.templateFactor * smallestScale
        val width = (templateSize.width * factor).roundToInt()
        val height = (templateSize.height * factor).roundToInt()
        if (width > area.width || height > area.height) {
            return "$label: template '${action.locate}' (${width}x$height apos escala) " +
                "nao cabe na area ${area.describe()}"
        }
        return null
    }

    /** `arquivo.json:12` quando a linha e conhecida, senao apenas o arquivo. */
    private fun locationOf(session: Session, action: SessionAction): String =
        action.sourceLine?.let { "${session.fileName}:$it" } ?: session.fileName
}

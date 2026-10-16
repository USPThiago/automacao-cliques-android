package com.example.automacaocliques

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/** Marca de rodada de teste concluida com sucesso, em `sessions_teste/`. */
const val RUN_COMPLETE_FILE = ".run_complete"

/**
 * Assinatura dos `.json` de `sessions_teste/` (nome, data, tamanho): muda
 * quando o editor altera a pasta durante uma execucao.
 */
fun testSessionsRevision(context: Context): String =
    SessionStore(context, testMode = true).directory()
        .listFiles { f -> f.isFile && f.name.endsWith(".json") }
        .orEmpty()
        .sortedBy { it.name }
        .joinToString("|") { "${it.name}:${it.lastModified()}:${it.length()}" }

/**
 * Grava `sessions_teste/.run_complete` (fim com sucesso no modo teste) com a
 * resolucao usada na execucao, para validar a promocao com a mesma tela.
 */
fun markRunComplete(context: Context, screen: Size) {
    try {
        File(SessionStore(context, testMode = true).directory(), RUN_COMPLETE_FILE)
            .writeText("${screen.width} ${screen.height}")
    } catch (e: IOException) {
        android.util.Log.w(ClickAccessibilityService.TAG, "Falha ao gravar $RUN_COMPLETE_FILE", e)
    }
}

/**
 * Arquivos manipulados pelo editor: sessoes sempre em `sessions_teste/` e
 * templates sempre em `templates/`. Toda alteracao em `sessions_teste/` apaga
 * [RUN_COMPLETE_FILE]. Operacoes de disco; chamar fora da thread principal.
 */
class EditorStorage(private val context: Context) {

    val testDir: File get() = SessionStore(context, testMode = true).directory()
    val mainDir: File get() = SessionStore(context, testMode = false).directory()
    val templates: TemplateStore get() = TemplateStore(context, testMode = false)

    /** Arquivos `.json` de `sessions_teste/`, em ordem alfabetica. */
    fun sessionFiles(): List<String> = jsonFiles(testDir).map { it.name }

    fun read(fileName: String): String? = File(testDir, fileName).takeIf { it.isFile }?.readText()

    fun exists(fileName: String): Boolean = File(testDir, fileName).isFile

    fun write(fileName: String, text: String) {
        File(testDir, fileName).writeText(text)
        invalidateRunComplete()
    }

    fun delete(fileName: String) {
        File(testDir, fileName).delete()
        invalidateRunComplete()
    }

    fun runCompleteExists(): Boolean = File(testDir, RUN_COMPLETE_FILE).isFile

    fun invalidateRunComplete() {
        File(testDir, RUN_COMPLETE_FILE).delete()
    }

    /** Erros do roteiro de `sessions_teste/` (vazio = OK), sem parar no primeiro. */
    /** Tela do aparelho na orientacao do app alvo definida em T1. */
    fun targetScreen(): Size {
        val display = screenSizeOf(context)
        val long = maxOf(display.width, display.height)
        val short = minOf(display.width, display.height)
        return if (AppPreferences(context).targetLandscape) Size(long, short) else Size(short, long)
    }

    fun validateTestSessions(screen: Size = targetScreen()): List<String> = SessionValidator.errors(
        source = SessionStore(context, testMode = true),
        templates = TemplateStore(context, VALIDATION_TEMPLATES_TEST_MODE)::sizeOf,
        screen = screen
    )

    /** Referencias de outras sessoes a [fileName]. */
    fun referencesTo(fileName: String): List<SessionReference> {
        val target = SessionValidator.fileNameOf(fileName)
        return sessionFiles()
            .filter { it != target }
            .flatMap { source -> SessionReferences.find(source, read(source).orEmpty()) }
            .filter { it.target == target }
    }

    /** Aponta para [newFile] todas as referencias a [oldFile]; devolve os arquivos alterados. */
    fun renameReferences(oldFile: String, newFile: String): List<String> =
        sessionFiles().filter { source ->
            val text = read(source) ?: return@filter false
            val renamed = SessionReferences.rename(text, oldFile, newFile)
            if (renamed != text) write(source, renamed)
            renamed != text
        }

    /** Copia os `.json` de `sessions_teste/` para `sessions/`, sobrescrevendo. */
    fun copyTestToMain(): Int = copyJson(testDir, mainDir)

    /** Copia os `.json` de `sessions/` para `sessions_teste/`, sobrescrevendo. */
    fun copyMainToTest(): Int {
        invalidateRunComplete()
        return copyJson(mainDir, testDir)
    }

    private fun copyJson(from: File, to: File): Int {
        val files = jsonFiles(from)
        files.forEach { it.copyTo(File(to, it.name), overwrite = true) }
        return files.size
    }

    /** Nome exibido do documento [uri], ou `null`. */
    fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** Le o documento [uri] como texto UTF-8. */
    fun readUri(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("nao foi possivel abrir $uri")

    /** Copia [uri] para `sessions_teste/[fileName]`, sobrescrevendo. */
    fun importSession(uri: Uri, fileName: String) = write(File(fileName).name, readUri(uri))

    /**
     * Copia [uri] para `templates/[fileName]`. Arquivos com o mesmo nome de
     * template e outra extensao sao apagados, ja que [TemplateStore] ignora a
     * extensao.
     */
    fun importTemplate(uri: Uri, fileName: String) {
        val dir = templates.directory()
        val name = File(fileName).name
        val key = File(name).nameWithoutExtension.lowercase()
        // Copia primeiro para um temporario: em falha, o template antigo fica intacto.
        val temp = File(dir, ".$name.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("nao foi possivel abrir $uri")
            // rename(2) substitui o destino atomicamente; em falha, nada antigo foi apagado.
            val target = File(dir, name)
            if (!temp.renameTo(target)) throw IOException("nao foi possivel gravar $name")
            dir.listFiles()
                ?.filter { it.isFile && it != target && it.nameWithoutExtension.lowercase() == key }
                ?.forEach { it.delete() }
        } finally {
            temp.delete()
        }
    }

    /** `true` se ja existe um template com o nome de [fileName], qualquer extensao. */
    fun templateExists(fileName: String): Boolean = templates.sizeOf(fileName) != null

    /** Arquivo do template [name] (com extensao), ou `null`. */
    fun templateFile(name: String): File? {
        val key = File(name).nameWithoutExtension.lowercase()
        return templates.directory().listFiles()
            ?.filter { it.isFile && it.nameWithoutExtension.lowercase() == key }
            ?.firstOrNull { it.extension.lowercase() in TEMPLATE_EXTENSIONS }
    }

    /** Arquivos `.json` de `sessions/`. */
    fun mainSessionFiles(): List<String> = jsonFiles(mainDir).map { it.name }

    /** Imagens de `templates/` reconhecidas pelo [TemplateStore], com extensao. */
    fun templateFiles(): List<File> = templates.names().mapNotNull(::templateFile)

    /** Exporta os `.json` de `sessions/` para a pasta [tree] escolhida pelo usuario. */
    fun exportSessions(tree: Uri): Int = export(tree, jsonFiles(mainDir), "application/json")

    /** Exporta as imagens de `templates/` para a pasta [tree]. */
    fun exportTemplates(tree: Uri): Int {
        return templateFiles().count { export(tree, listOf(it), mimeOf(it)) == 1 }
    }

    private fun export(tree: Uri, files: List<File>, mime: String): Int {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        files.forEach { file ->
            val target = DocumentsContract.createDocument(resolver, parent, mime, file.name)
                ?: throw IOException("nao foi possivel criar ${file.name}")
            resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("nao foi possivel gravar ${file.name}")
        }
        return files.size
    }

    private fun jsonFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }.orEmpty()

    private fun mimeOf(file: File) = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    companion object {
        /**
         * Pasta de templates usada pela validacao do editor: `false` = `templates/`,
         * a mesma que o editor importa e lista.
         */
        const val VALIDATION_TEMPLATES_TEST_MODE = false

        /** Extensoes aceitas na importacao (PNG/JPG). */
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

        /** Extensoes lidas pelo [TemplateStore]. */
        private val TEMPLATE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}

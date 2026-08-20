package com.example.automacaocliques

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/** Template (recorte de tela) carregado do disco, em tons de cinza. */
class Template(val name: String, val image: GrayImage)

/**
 * Carrega os recortes usados no reconhecimento visual. Os arquivos PNG/JPG ficam
 * em `Android/data/<pacote>/files/templates/` no armazenamento do aparelho, de
 * modo que podem ser enviados por `adb push` ou por um gerenciador de arquivos
 * sem precisar recompilar o app.
 *
 * O cache e sincronizado porque o carregamento acontece na thread de visao e a
 * invalidacao vem da interface.
 */
class TemplateStore(private val context: Context) {

    private val cache = mutableMapOf<String, Template?>()

    /** Diretorio dos templates, criado se ainda nao existir. */
    fun directory(): File =
        File(context.getExternalFilesDir(null), DIRECTORY_NAME).apply { mkdirs() }

    /** Nomes disponiveis (nome do arquivo sem extensao, em minusculas). */
    fun names(): List<String> = files().map { it.nameWithoutExtension.lowercase() }.sorted()

    /** Todos os templates disponiveis, ignorando arquivos ilegiveis. */
    fun all(): List<Template> = names().mapNotNull(::get)

    /** Template chamado [name] (com ou sem extensao), ou `null` se ausente. */
    fun get(name: String): Template? {
        val key = File(name).nameWithoutExtension.lowercase()
        return synchronized(cache) { cache.getOrPut(key) { load(key) } }
    }

    /** Descarta os templates em memoria, para recarregar do disco. */
    fun invalidate() = synchronized(cache) { cache.clear() }

    private fun files(): List<File> = directory()
        .listFiles { file -> file.isFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS }
        ?.toList()
        .orEmpty()

    private fun load(key: String): Template? {
        val file = files().firstOrNull { it.nameWithoutExtension.lowercase() == key }
        if (file == null) {
            Log.w(TAG, "Template '$key' nao encontrado em ${directory()}")
            return null
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            Log.w(TAG, "Falha ao decodificar ${file.name}")
            return null
        }
        return Template(key, bitmap.toGrayImage()).also {
            Log.i(TAG, "Template '$key' carregado (${bitmap.width}x${bitmap.height})")
        }
    }

    companion object {
        private const val TAG = ClickAccessibilityService.TAG
        private const val DIRECTORY_NAME = "templates"
        private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}

/** Converte um bitmap em [GrayImage], copiando os pixels. */
fun Bitmap.toGrayImage(): GrayImage {
    val argb = IntArray(width * height)
    getPixels(argb, 0, width, 0, 0, width, height)
    return GrayImage.fromArgb(width, height, argb)
}

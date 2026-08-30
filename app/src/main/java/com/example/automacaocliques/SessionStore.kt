package com.example.automacaocliques

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.IOException

/**
 * Arquivos de sessao em `Android/data/<pacote>/files/sessions/`, no mesmo modelo
 * dos templates: podem ser enviados por `adb push` ou por um gerenciador de
 * arquivos, sem recompilar o app.
 */
class SessionStore(private val context: Context) : SessionSource {

    /** Diretorio das sessoes, criado se ainda nao existir. */
    fun directory(): File =
        File(context.getExternalFilesDir(null), DIRECTORY_NAME).apply { mkdirs() }

    override fun read(fileName: String): String? {
        val file = File(directory(), fileName)
        if (!file.isFile) {
            Log.w(ClickAccessibilityService.TAG, "Sessao '$fileName' nao encontrada em ${directory()}")
            return null
        }
        return try {
            file.readText()
        } catch (e: IOException) {
            Log.w(ClickAccessibilityService.TAG, "Falha ao ler ${file.name}: ${e.message}")
            null
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "sessions"
    }
}

/** Resolucao real da tela, incluindo status bar e barra de navegacao. */
fun screenSizeOf(context: Context): Size {
    val windowManager = context.getSystemService(WindowManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        return Size(bounds.width(), bounds.height())
    }
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    windowManager.defaultDisplay.getRealMetrics(metrics)
    return Size(metrics.widthPixels, metrics.heightPixels)
}

/** Valida o roteiro instalado no aparelho (arquivos de sessao + templates). */
fun validateInstalledSessions(context: Context): SessionLoad = SessionValidator.load(
    source = SessionStore(context),
    templates = TemplateStore(context)::sizeOf,
    screen = screenSizeOf(context)
)

package com.example.automacaocliques

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.automacaocliques.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Leitura dos arquivos de sessao e templates fora da thread principal. */
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val log = ClickAccessibilityService.log

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openSettingsButton.setOnClickListener { openAccessibilitySettings() }
        binding.startButton.setOnClickListener { start() }
        binding.stopButton.setOnClickListener { stop() }
        binding.clearLogButton.setOnClickListener { log.clear() }
        binding.copyLogButton.setOnClickListener { copyLog() }

        binding.templatesPath.text =
            getString(R.string.templates_dir, TemplateStore(this).directory().absolutePath)
        binding.sessionsPath.text =
            getString(R.string.sessions_dir, SessionStore(this).directory().absolutePath)

        validateLoad()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        log.listener = { runOnUiThread(::showLog) }
        showLog()
    }

    override fun onPause() {
        log.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Valida a carga inicial e registra o resultado no log. Roda ao abrir o app e
     * de novo no servico quando a execucao comeca.
     */
    private fun validateLoad() {
        ioExecutor.execute {
            val result = validateInstalledSessions(this)
            when (result) {
                is SessionLoad.Ok -> log.add("Carga inicial", "OK")
                is SessionLoad.Failure -> log.add("Carga inicial", "NOK - ${result.reason}")
            }
        }
    }

    /**
     * Inicia o roteiro. A troca para o app alvo e feita pelo usuario: o servico
     * espera o primeiro plano deixar de ser este app antes da primeira captura.
     */
    private fun start() {
        val service = ClickAccessibilityService.instance
        if (service == null) {
            toast(R.string.service_inactive_warning)
            return
        }
        if (!service.start()) {
            toast(R.string.execution_already_running)
            return
        }
        toast(R.string.execution_started)
    }

    private fun stop() {
        val service = ClickAccessibilityService.instance
        if (service == null) {
            toast(R.string.service_inactive_warning)
            return
        }
        service.stop()
        toast(R.string.execution_stopping)
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), log.text()))
        toast(R.string.log_copied)
    }

    /** Mostra o log do servico, rolando ate a ultima linha. */
    private fun showLog() {
        binding.logText.text = log.text()
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        val enabled = AccessibilityUtils.isServiceEnabled(this)
        binding.statusText.setText(
            if (enabled) R.string.service_status_enabled else R.string.service_status_disabled
        )
        binding.statusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.status_enabled else R.color.status_disabled
            )
        )
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.accessibility_settings_unavailable, Toast.LENGTH_LONG)
                .show()
        }
    }
}

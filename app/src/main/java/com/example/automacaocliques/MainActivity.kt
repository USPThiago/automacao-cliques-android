package com.example.automacaocliques

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.automacaocliques.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openSettingsButton.setOnClickListener { openAccessibilitySettings() }
        binding.readScreenButton.setOnClickListener { readScreen() }
        binding.runSequenceButton.setOnClickListener { runSequence() }
        binding.identifyScreenButton.setOnClickListener { identifyScreen() }

        binding.templatesPath.text =
            getString(R.string.templates_dir, TemplateStore(this).directory().absolutePath)
    }

    /** Compara a tela atual com os templates instalados e loga os escores. */
    private fun identifyScreen() {
        val service = ClickAccessibilityService.instance
        if (service == null) {
            toast(R.string.service_inactive_warning)
            return
        }
        service.templates.invalidate()
        service.identifyScreen()
        toast(R.string.identifying_screen)
    }

    /** Pede ao servico um dump da janela ativa no Logcat. */
    private fun readScreen() {
        val service = ClickAccessibilityService.instance
        if (service == null) {
            toast(R.string.service_inactive_warning)
            return
        }
        service.logScreen()
        toast(R.string.screen_logged)
    }

    /**
     * Agenda uma sequencia de cliques a partir dos termos digitados. O primeiro
     * clique acontece apos [START_DELAY_MS] para dar tempo de abrir a tela alvo.
     */
    private fun runSequence() {
        val service = ClickAccessibilityService.instance
        if (service == null) {
            toast(R.string.service_inactive_warning)
            return
        }
        val steps = ClickStep.fromTerms(
            input = binding.sequenceInput.text?.toString().orEmpty(),
            delayMs = STEP_DELAY_MS,
            firstDelayMs = START_DELAY_MS
        )
        if (steps.isEmpty()) {
            toast(R.string.sequence_empty)
            return
        }
        service.runSequence(steps)
        toast(R.string.sequence_scheduled)
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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

    private companion object {
        /** Espera antes do primeiro clique, para o usuario abrir a tela alvo. */
        const val START_DELAY_MS = 6_000L

        /** Intervalo entre os cliques seguintes. */
        const val STEP_DELAY_MS = 1_000L
    }
}

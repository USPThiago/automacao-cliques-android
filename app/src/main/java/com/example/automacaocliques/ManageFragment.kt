package com.example.automacaocliques

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.example.automacaocliques.databinding.FragmentManageBinding
import java.io.File

/** T1: gestao de pastas (modo, importar/exportar, mover, validar) e links para T2/T3. */
class ManageFragment : EditorFragment(R.layout.fragment_manage) {

    private var binding: FragmentManageBinding? = null
    private val prefs by lazy { AppPreferences(requireContext()) }

    /** Exportacao confirmada aguardando a pasta escolhida no seletor. */
    private var pendingExport: ((Uri) -> Int)? = null

    private val pickSession = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(::importSession)
    }
    private val pickTemplate = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(::importTemplate)
    }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val export = pendingExport
        pendingExport = null
        if (uri != null && export != null) runExport(uri, export)
    }

    override fun title() = R.string.manage_title

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentManageBinding.bind(view)
        binding = b
        b.testModeSwitch.isChecked = prefs.testMode
        b.testModeSwitch.setOnCheckedChangeListener { _, checked ->
            // Execucao em andamento nao e afetada: as pastas sao fixadas no inicio.
            prefs.testMode = checked
            ClickAccessibilityService.log.add("Modo teste", if (checked) "ligado" else "desligado")
            updateMode()
        }
        updateMode()
        b.importSessionButton.setOnClickListener { pickSession.launch("application/json") }
        b.importTemplateButton.setOnClickListener { pickTemplate.launch("image/*") }
        b.exportSessionsButton.setOnClickListener {
            confirmExport(storage.mainSessionFiles().size, R.string.export_sessions_message) {
                storage.exportSessions(it)
            }
        }
        b.exportTemplatesButton.setOnClickListener {
            confirmExport(storage.templateFiles().size, R.string.export_templates_message) {
                storage.exportTemplates(it)
            }
        }
        b.moveTestToMainButton.setOnClickListener { moveTestToMain() }
        b.moveMainToTestButton.setOnClickListener { moveMainToTest() }
        b.validateButton.setOnClickListener { validate() }
        b.editSessionsLink.setOnClickListener { editor.show(SessionListFragment()) }
        b.viewTemplatesLink.setOnClickListener { editor.show(TemplatesFragment()) }
    }

    override fun onResume() {
        super.onResume()
        binding?.moveTestToMainButton?.isEnabled = storage.runCompleteExists()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun updateMode() {
        binding?.modeIndicator?.setText(if (prefs.testMode) R.string.mode_active_test else R.string.mode_active_main)
    }

    private fun importSession(uri: Uri) {
        val name = storage.displayName(uri)?.let { File(it).name } ?: "sessao.json"
        val fileName = if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
        val copy = {
            editor.background({ storage.importSession(uri, fileName) }) { result ->
                result.fold(
                    { editor.toast(getString(R.string.import_done, fileName, "sessions_teste/")) },
                    { editor.toast(getString(R.string.import_failed, it.message.orEmpty())) }
                )
                onResume()
            }
        }
        if (storage.exists(fileName)) confirmReplace(fileName, "sessions_teste/", copy) else copy()
    }

    private fun importTemplate(uri: Uri) {
        val name = storage.displayName(uri)?.let { File(it).name } ?: "template"
        val extension = File(name).extension.lowercase()
        val fileName = when {
            extension in EditorStorage.IMAGE_EXTENSIONS -> name
            requireContext().contentResolver.getType(uri) == "image/png" -> "${File(name).nameWithoutExtension}.png"
            requireContext().contentResolver.getType(uri) == "image/jpeg" -> "${File(name).nameWithoutExtension}.jpg"
            else -> {
                editor.toast(getString(R.string.template_invalid_type))
                return
            }
        }
        val copy = {
            editor.background({ storage.importTemplate(uri, fileName) }) { result ->
                result.fold(
                    { editor.toast(getString(R.string.import_done, fileName, "templates/")) },
                    { editor.toast(getString(R.string.import_failed, it.message.orEmpty())) }
                )
            }
        }
        if (storage.templateExists(fileName)) confirmReplace(fileName, "templates/", copy) else copy()
    }

    private fun confirmReplace(fileName: String, folder: String, onConfirm: () -> Unit) = editor.confirm(
        getString(R.string.replace_file_title),
        getString(R.string.replace_file_message, fileName, folder),
        R.string.replace,
        onConfirm
    )

    private fun confirmExport(count: Int, message: Int, export: (Uri) -> Int) {
        if (count == 0) {
            editor.toast(getString(R.string.export_empty))
            return
        }
        editor.confirm(getString(R.string.export_title), getString(message, count), R.string.confirm) {
            pendingExport = export
            pickTree.launch(null)
        }
    }

    private fun runExport(tree: Uri, export: (Uri) -> Int) {
        editor.background({ export(tree) }) { result ->
            result.fold(
                { editor.toast(getString(R.string.export_done, it)) },
                { editor.toast(getString(R.string.export_failed, it.message.orEmpty())) }
            )
        }
    }

    private fun moveTestToMain() {
        editor.background({ storage.validateTestSessions() }) { result ->
            val errors = result.getOrElse { listOf(it.message.orEmpty()) }
            if (errors.isNotEmpty()) {
                editor.showValidationErrors(errors)
                return@background
            }
            editor.confirm(
                getString(R.string.move_title),
                getString(R.string.move_test_to_main_message, storage.sessionFiles().size),
                R.string.confirm
            ) { copy { storage.copyTestToMain() } }
        }
    }

    private fun moveMainToTest() {
        editor.confirm(
            getString(R.string.move_title),
            getString(R.string.move_main_to_test_message, storage.mainSessionFiles().size),
            R.string.confirm
        ) { copy { storage.copyMainToTest() } }
    }

    private fun copy(work: () -> Int) {
        editor.background(work) { result ->
            result.fold(
                { editor.toast(getString(R.string.move_done, it)) },
                { editor.toast(getString(R.string.move_failed, it.message.orEmpty())) }
            )
            onResume()
        }
    }

    private fun validate() {
        editor.background({ storage.validateTestSessions() }) { result ->
            val errors = result.getOrElse { listOf(it.message.orEmpty()) }
            if (errors.isEmpty()) {
                editor.confirm(
                    getString(R.string.validation_ok_title),
                    getString(R.string.validation_ok_message),
                    R.string.ok
                ) {}
            } else {
                editor.showValidationErrors(errors)
            }
        }
    }
}

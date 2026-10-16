package com.example.automacaocliques

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import com.example.automacaocliques.databinding.FragmentActionEditBinding
import com.example.automacaocliques.databinding.ItemClickBinding
import com.example.automacaocliques.databinding.ViewAreaFieldsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import kotlin.math.roundToInt

/** T5: campos de uma acao; Confirmar devolve a acao para a sessao em edicao (T4). */
class ActionEditFragment : EditorFragment(R.layout.fragment_action_edit) {

    private var binding: FragmentActionEditBinding? = null
    private val action: ActionDraft get() = checkNotNull(editor.model.action)
    private val clickRows = mutableListOf<ItemClickBinding>()

    override fun title() =
        if (editor.model.actionIndex == null) R.string.action_new_title else R.string.action_edit_title

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (editor.model.action == null || editor.model.session == null) {
            leave()
            return
        }
        val b = FragmentActionEditBinding.bind(view)
        binding = b
        bind(b.nameInput, action.name) { action.name = it }

        b.locateInput.setSimpleItems(storage.templates.names().toTypedArray())
        bind(b.locateInput, action.locate) {
            action.locate = it
            updateTemplateButton()
        }
        b.viewTemplateButton.setOnClickListener {
            storage.templateFile(action.locate)?.let { TemplateSheet.show(requireContext(), it) }
        }
        updateTemplateButton()

        action.threshold.toFloatOrNull()?.let { b.thresholdSlider.value = sliderValue(it) }
        b.thresholdSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) b.thresholdInput.setText(String.format(Locale.ROOT, "%.2f", value))
        }
        bind(b.thresholdInput, action.threshold) { text ->
            action.threshold = text
            text.trim().toFloatOrNull()?.let { b.thresholdSlider.value = sliderValue(it) }
        }
        bind(b.scalesInput, action.scales) { action.scales = it }

        bindArea(b.searchArea, action.searchArea) { preview(searchArea = it) }
        bindArea(b.clickArea, action.clickArea) { preview(clickArea = it) }
        b.clickModeGroup.check(
            when (action.clickMode) {
                ClickMode.CENTER -> R.id.modeCenter
                ClickMode.POINTS -> R.id.modePoints
                ClickMode.AREA -> R.id.modeArea
            }
        )
        b.clickModeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            action.clickMode = when (id) {
                R.id.modePoints -> ClickMode.POINTS
                R.id.modeArea -> ClickMode.AREA
                else -> ClickMode.CENTER
            }
            updateClickMode()
        }
        b.addClickButton.setOnClickListener {
            action.clicks += ClickDraft()
            renderClicks()
        }
        renderClicks()
        updateClickMode()

        bind(b.clickIntervalInput, action.clickIntervalMs) { action.clickIntervalMs = it }
        bind(b.waitAfterInput, action.waitAfterMs) { action.waitAfterMs = it }
        bindSessionDropdown(this, b.callInput, action.call) { action.call = it }

        b.cancelButton.setOnClickListener { goBack() }
        b.confirmButton.setOnClickListener { confirm() }
        guardBack { action != editor.model.actionSaved }

        editor.model.actionTarget?.let { (field, message) ->
            editor.model.actionTarget = null
            showErrors(SessionDrafts.validateAction(action))
            focus(field, message)
        }
    }

    override fun onDestroyView() {
        binding = null
        clickRows.clear()
        super.onDestroyView()
    }

    private fun bind(input: EditText, value: String, onChange: (String) -> Unit) {
        input.setText(value)
        input.doAfterTextChanged { onChange(it?.toString().orEmpty()) }
    }

    private fun bindArea(view: ViewAreaFieldsBinding, area: AreaDraft, onPreview: (Area) -> Unit) {
        bind(view.left, area.left) { area.left = it }
        bind(view.top, area.top) { area.top = it }
        bind(view.right, area.right) { area.right = it }
        bind(view.bottom, area.bottom) { area.bottom = it }
        view.previewButton.setOnClickListener {
            val parsed = area.toAreaOrNull()?.takeIf { it.right > it.left && it.bottom > it.top }
            if (parsed == null) editor.toast(getString(R.string.preview_invalid)) else onPreview(parsed)
        }
    }

    private fun sliderValue(value: Float) = (value.coerceIn(0f, 1f) * 100).roundToInt() / 100f

    private fun updateTemplateButton() {
        val b = binding ?: return
        val exists = action.locate.isNotBlank() && storage.templateFile(action.locate.trim()) != null
        b.viewTemplateButton.isEnabled = exists
        b.locateLayout.helperText =
            if (!exists && action.locate.isNotBlank()) getString(R.string.template_missing) else null
    }

    private fun updateClickMode() {
        val b = binding ?: return
        b.clicksSection.visibility = if (action.clickMode == ClickMode.POINTS) View.VISIBLE else View.GONE
        b.clickArea.root.visibility = if (action.clickMode == ClickMode.AREA) View.VISIBLE else View.GONE
    }

    private fun renderClicks() {
        val b = binding ?: return
        b.clicksList.removeAllViews()
        clickRows.clear()
        action.clicks.forEachIndexed { index, click ->
            val row = ItemClickBinding.inflate(layoutInflater, b.clicksList, true)
            clickRows += row
            row.number.text = getString(R.string.click_number, index + 1)
            bind(row.x, click.x) { click.x = it }
            bind(row.y, click.y) { click.y = it }
            bind(row.delay, click.delayMs) { click.delayMs = it }
            row.previewButton.setOnClickListener {
                val x = click.x.trim().toIntOrNull()
                val y = click.y.trim().toIntOrNull()
                if (x == null || y == null) {
                    editor.toast(getString(R.string.preview_invalid))
                } else {
                    preview(clicks = listOf(ClickPoint(x, y)), firstNumber = index + 1)
                }
            }
            row.deleteButton.setOnClickListener {
                action.clicks.remove(click)
                renderClicks()
            }
        }
    }

    /** Previa das coordenadas pelo servico de acessibilidade (overlay somente leitura). */
    private fun preview(
        searchArea: Area? = null,
        clickArea: Area? = null,
        clicks: List<ClickPoint> = emptyList(),
        firstNumber: Int = 1
    ) {
        val service = ClickAccessibilityService.instance
        if (service == null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.service_required_title)
                .setMessage(R.string.service_required_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.open_accessibility_settings) { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .onFailure { editor.toast(getString(R.string.accessibility_settings_unavailable)) }
                }
                .show()
            return
        }
        val session = checkNotNull(editor.model.session)
        val width = session.screenWidth.trim().toIntOrNull()
        val height = session.screenHeight.trim().toIntOrNull()
        val reference = if (width != null && height != null && width > 0 && height > 0) Size(width, height) else null
        val display = DisplayOverlay.displaySize(requireContext())
        if (reference != null && reference != display) {
            editor.toast(getString(R.string.preview_scaled_toast, reference.describe(), display.describe()))
        }
        service.showCoordinatePreview(CoordinatePreview(searchArea, clickArea, clicks, reference, firstNumber))
    }

    private fun layoutFor(field: String?): TextInputLayout? {
        val b = binding ?: return null
        clickField(field)?.let { (index, part) ->
            val row = clickRows.getOrNull(index) ?: return null
            return when (part) {
                "x" -> row.xLayout
                "y" -> row.yLayout
                else -> row.delayLayout
            }
        }
        areaField(field)?.let { (area, part) ->
            return when (part) {
                "left" -> area.leftLayout
                "top" -> area.topLayout
                "right" -> area.rightLayout
                else -> area.bottomLayout
            }
        }
        return when (field) {
            "name" -> b.nameLayout
            "locate" -> b.locateLayout
            "threshold" -> b.thresholdLayout
            "scales" -> b.scalesLayout
            "clickIntervalMs" -> b.clickIntervalLayout
            "waitAfterMs" -> b.waitAfterLayout
            "call" -> b.callLayout
            else -> null
        }
    }

    private fun clickField(field: String?): Pair<Int, String>? =
        field?.let { Regex("""^clicks\[(\d+)]\.(\w+)$""").find(it) }
            ?.let { it.groupValues[1].toInt() to it.groupValues[2] }

    private fun areaField(field: String?): Pair<ViewAreaFieldsBinding, String>? {
        val b = binding ?: return null
        val match = field?.let { Regex("""^(searchArea|clickArea)\.(left|top|right|bottom)$""").find(it) } ?: return null
        val area = if (match.groupValues[1] == "searchArea") b.searchArea else b.clickArea
        return area to match.groupValues[2]
    }

    /** Erros sem campo proprio (area inteira, lista de pontos) aparecem abaixo do grupo. */
    private fun groupErrorView(field: String?) = binding?.let { b ->
        when (field) {
            "searchArea" -> b.searchArea.error
            "clickArea" -> b.clickArea.error
            "clicks" -> b.clicksError
            else -> null
        }
    }

    private fun showErrors(errors: List<DraftError>) {
        val b = binding ?: return
        val layouts = listOf(
            b.nameLayout, b.locateLayout, b.thresholdLayout, b.scalesLayout, b.clickIntervalLayout,
            b.waitAfterLayout, b.callLayout
        ) + listOf(b.searchArea, b.clickArea).flatMap { listOf(it.leftLayout, it.topLayout, it.rightLayout, it.bottomLayout) } +
            clickRows.flatMap { listOf(it.xLayout, it.yLayout, it.delayLayout) }
        layouts.forEach { it.error = null }
        listOf(b.searchArea.error, b.clickArea.error, b.clicksError).forEach { it.visibility = View.GONE }
        errors.forEach { error ->
            val layout = layoutFor(error.field)
            if (layout != null) {
                if (layout.error == null) layout.error = error.message
            } else {
                groupErrorView(error.field)?.apply {
                    text = error.message
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun focus(field: String, message: String) {
        val b = binding ?: return
        val layout = layoutFor(field)
        val target: View? = layout ?: groupErrorView(field)
        if (layout != null && message.isNotEmpty()) layout.error = message
        if (layout == null && message.isNotEmpty()) {
            groupErrorView(field)?.apply {
                text = message
                visibility = View.VISIBLE
            } ?: editor.toast(message)
        }
        layout?.editText?.requestFocus()
        target?.let { v -> b.scroll.post { b.scroll.smoothScrollTo(0, offsetOf(v)) } }
    }

    private fun offsetOf(view: View): Int {
        var top = 0
        var current: View? = view
        val root = binding?.scroll
        while (current != null && current != root) {
            top += current.top
            current = current.parent as? View
        }
        return top
    }

    private fun confirm() {
        val errors = SessionDrafts.validateAction(action)
        showErrors(errors)
        if (errors.isNotEmpty()) {
            editor.toast(getString(R.string.session_has_errors))
            errors.firstOrNull()?.let { layoutFor(it.field)?.editText?.requestFocus() }
            return
        }
        val session = checkNotNull(editor.model.session)
        val index = editor.model.actionIndex
        if (index != null && index in session.actions.indices) {
            session.actions[index] = action.deepCopy()
        } else {
            session.actions += action.deepCopy()
        }
        editor.model.actionSaved = action.deepCopy()
        leave()
    }
}

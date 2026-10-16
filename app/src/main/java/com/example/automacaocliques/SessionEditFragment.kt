package com.example.automacaocliques

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.automacaocliques.databinding.FragmentSessionEditBinding
import com.example.automacaocliques.databinding.ItemSessionBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import java.util.Collections

/** T4: campos da sessao e lista de acoes; salva em `sessions_teste/<arquivo>.json`. */
class SessionEditFragment : EditorFragment(R.layout.fragment_session_edit) {

    private var binding: FragmentSessionEditBinding? = null
    private val draft: SessionDraft get() = checkNotNull(editor.model.session)
    private var actionErrors = mapOf<Int, String>()
    private val adapter = Adapter()

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = false

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            Collections.swap(draft.actions, from.bindingAdapterPosition, to.bindingAdapterPosition)
            actionErrors = emptyMap()
            adapter.notifyItemMoved(from.bindingAdapterPosition, to.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        @SuppressLint("NotifyDataSetChanged")
        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            rv.post { adapter.notifyDataSetChanged() }
        }
    })

    override fun title() =
        if (editor.model.sessionFile == null) R.string.session_new_title else R.string.session_edit_title

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (editor.model.session == null) {
            leave()
            return
        }
        val b = FragmentSessionEditBinding.bind(view)
        binding = b
        bind(b.nameInput, draft.name) { draft.name = it }
        bind(b.fileInput, draft.file) {
            draft.file = it
            updateFileWarning()
        }
        bind(b.screenWidthInput, draft.screenWidth) { draft.screenWidth = it }
        bind(b.screenHeightInput, draft.screenHeight) { draft.screenHeight = it }
        bind(b.retriesInput, draft.retries) { draft.retries = it }
        bind(b.retryDelayInput, draft.retryDelayMs) { draft.retryDelayMs = it }
        bindSessionDropdown(b.onLocateFailureInput, draft.onLocateFailure) { draft.onLocateFailure = it }
        updateFileWarning()

        b.actionsList.layoutManager = LinearLayoutManager(requireContext())
        b.actionsList.adapter = adapter
        touchHelper.attachToRecyclerView(b.actionsList)
        updateActionsEmpty()
        b.addButton.setOnClickListener { editor.openAction(null) }
        b.cancelButton.setOnClickListener { goBack() }
        b.confirmButton.setOnClickListener { save() }
        guardBack { draft != editor.model.sessionSaved }

        editor.model.sessionTarget?.let { (target, message) ->
            editor.model.sessionTarget = null
            showErrors(SessionDrafts.validate(draft))
            val index = target.actionIndex
            if (index != null) {
                view.post { editor.openAction(index, target.field, message) }
            } else {
                focus(target.field, message)
            }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bind(input: EditText, value: String, onChange: (String) -> Unit) {
        input.setText(value)
        input.doAfterTextChanged { onChange(it?.toString().orEmpty()) }
    }

    private fun bindSessionDropdown(input: MaterialAutoCompleteTextView, value: String, onChange: (String) -> Unit) =
        bindSessionDropdown(this, input, value, onChange)

    private fun updateFileWarning() {
        val b = binding ?: return
        val fileName = "${draft.file.trim()}.json"
        b.fileLayout.helperText = if (draft.file.isNotBlank() && fileName != editor.model.sessionFile &&
            storage.exists(fileName)
        ) {
            getString(R.string.file_exists_warning, fileName)
        } else {
            null
        }
    }

    private fun updateActionsEmpty() {
        binding?.actionsEmpty?.visibility = if (draft.actions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun layoutFor(field: String?): TextInputLayout? {
        val b = binding ?: return null
        return when (field) {
            "name" -> b.nameLayout
            "file" -> b.fileLayout
            "screen", "screen.width" -> b.screenWidthLayout
            "screen.height" -> b.screenHeightLayout
            "retries" -> b.retriesLayout
            "retryDelayMs" -> b.retryDelayLayout
            "onLocateFailure" -> b.onLocateFailureLayout
            else -> null
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showErrors(errors: List<DraftError>) {
        val b = binding ?: return
        listOf(
            b.nameLayout, b.fileLayout, b.screenWidthLayout, b.screenHeightLayout,
            b.retriesLayout, b.retryDelayLayout, b.onLocateFailureLayout
        ).forEach { it.error = null }
        b.actionsError.visibility = View.GONE
        val byAction = mutableMapOf<Int, String>()
        errors.forEach { error ->
            val index = Regex("""^actions\[(\d+)]""").find(error.field)?.groupValues?.get(1)?.toInt()
            when {
                index != null -> byAction.putIfAbsent(index, error.message)
                error.field == "actions" -> {
                    b.actionsError.text = error.message
                    b.actionsError.visibility = View.VISIBLE
                }
                else -> layoutFor(error.field)?.let { if (it.error == null) it.error = error.message }
            }
        }
        actionErrors = byAction
        adapter.notifyDataSetChanged()
    }

    private fun focus(field: String?, message: String) {
        val b = binding ?: return
        val layout = layoutFor(field)
        if (layout != null) {
            if (message.isNotEmpty()) layout.error = message
            layout.editText?.requestFocus()
            b.scroll.post { b.scroll.smoothScrollTo(0, layout.top) }
        } else if (field == "actions" || message.isNotEmpty()) {
            b.actionsError.text = message
            b.actionsError.visibility = View.VISIBLE
        }
    }

    private fun save() {
        val b = binding ?: return
        val errors = SessionDrafts.validate(draft)
        showErrors(errors)
        if (errors.isNotEmpty()) {
            editor.toast(getString(R.string.session_has_errors))
            errors.firstOrNull { !it.field.startsWith("actions") }?.let { layoutFor(it.field)?.editText?.requestFocus() }
            return
        }
        val newFile = "${draft.file.trim()}.json"
        val oldFile = editor.model.sessionFile
        if (oldFile == SessionValidator.MAIN_SESSION && newFile != oldFile) {
            b.fileLayout.error = getString(R.string.rename_main_blocked)
            return
        }
        if (newFile != oldFile && storage.exists(newFile)) {
            editor.confirm(
                getString(R.string.replace_file_title),
                getString(R.string.replace_file_message, newFile, "sessions_teste/"),
                R.string.replace
            ) { write(newFile, oldFile) }
        } else {
            write(newFile, oldFile)
        }
    }

    private fun write(newFile: String, oldFile: String?) {
        try {
            storage.write(newFile, SessionDrafts.toJson(draft))
            if (oldFile != null && oldFile != newFile) storage.delete(oldFile)
        } catch (e: java.io.IOException) {
            editor.toast(getString(R.string.save_failed, e.message.orEmpty()))
            return
        }
        editor.model.sessionSaved = draft.deepCopy()
        editor.model.sessionFile = newFile
        val refs = if (oldFile != null && oldFile != newFile) storage.referencesTo(oldFile) else emptyList()
        if (refs.isEmpty()) {
            finishSave(newFile)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename_refs_title)
            .setMessage(
                getString(
                    R.string.rename_refs_message,
                    oldFile,
                    refs.joinToString("\n") { "• ${it.describe()}" },
                    newFile
                )
            )
            .setCancelable(false)
            .setNegativeButton(R.string.dont_update) { _, _ -> finishSave(newFile) }
            .setPositiveButton(R.string.update) { _, _ ->
                storage.renameReferences(checkNotNull(oldFile), newFile)
                finishSave(newFile)
            }
            .show()
    }

    private fun finishSave(fileName: String) {
        editor.toast(getString(R.string.session_saved, fileName))
        leave()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = draft.actions.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val action = draft.actions[position]
            with(holder.binding) {
                title.text = action.name.ifBlank { getString(R.string.session_unnamed) }
                subtitle.text = getString(R.string.action_summary, action.locate)
                val message = actionErrors[position]
                error.text = message
                error.visibility = if (message == null) View.GONE else View.VISIBLE
                editButton.visibility = View.VISIBLE
                editButton.setOnClickListener { editor.openAction(holder.bindingAdapterPosition) }
                root.setOnClickListener { editor.openAction(holder.bindingAdapterPosition) }
                deleteButton.setOnClickListener {
                    editor.confirm(
                        getString(R.string.delete_action_title, action.name),
                        "",
                        R.string.delete
                    ) {
                        draft.actions.remove(action)
                        actionErrors = emptyMap()
                        @SuppressLint("NotifyDataSetChanged")
                        notifyDataSetChanged()
                        updateActionsEmpty()
                    }
                }
                dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                    false
                }
            }
        }
    }

    private class Holder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)
}

/** Dropdown "(nenhum)" + sessoes de `sessions_teste/` (sem extensao), usado em T4 e T5. */
fun bindSessionDropdown(
    fragment: EditorFragment,
    input: MaterialAutoCompleteTextView,
    value: String,
    onChange: (String) -> Unit
) {
    val none = fragment.getString(R.string.none_option)
    val sessions = (fragment.requireActivity() as ScriptEditorActivity).storage.sessionFiles()
        .map(SessionDrafts::baseName)
    input.setSimpleItems((listOf(none) + sessions).toTypedArray())
    input.setText(value.ifBlank { none }, false)
    input.setOnItemClickListener { _, _, position, _ -> onChange(if (position == 0) "" else sessions[position - 1]) }
}

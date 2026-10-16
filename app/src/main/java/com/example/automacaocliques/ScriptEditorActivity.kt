package com.example.automacaocliques

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModel
import com.example.automacaocliques.databinding.ActivityScriptEditorBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Rascunhos compartilhados entre T4 (sessao) e T5 (acao). */
class EditorViewModel : ViewModel() {
    var session: SessionDraft? = null
    var sessionSaved: SessionDraft? = null

    /** Arquivo de origem em `sessions_teste/` (com `.json`); `null` = sessao nova. */
    var sessionFile: String? = null

    /** Campo a destacar ao abrir T4 a partir do popup de erros, com a mensagem. */
    var sessionTarget: Pair<ErrorTarget, String>? = null

    var action: ActionDraft? = null
    var actionSaved: ActionDraft? = null

    /** Indice da acao editada em [session]; `null` = acao nova. */
    var actionIndex: Int? = null
    var actionTarget: Pair<String, String>? = null
}

/**
 * Editor de sessoes (retrato). T1 Gestao de pastas e a tela inicial; T2-T5 sao
 * fragments empilhados no back stack.
 */
class ScriptEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScriptEditorBinding
    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    val model: EditorViewModel by viewModels()
    val storage by lazy { EditorStorage(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.commit { replace(R.id.container, ManageFragment()) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** Executa [work] fora da thread principal e entrega o resultado em [done]. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val result = runCatching(work)
            mainHandler.post { if (!isDestroyed) done(result) }
        }
    }

    fun show(fragment: Fragment) {
        supportFragmentManager.commit {
            replace(R.id.container, fragment)
            addToBackStack(null)
        }
    }

    /** Abre T4 com [fileName] (`null` = sessao nova), destacando [link] se informado. */
    fun openSession(fileName: String?, link: ErrorLink? = null, message: String = "") {
        val text = fileName?.let { storage.read(it) }
        val draft = when {
            fileName != null && text != null -> runCatching { SessionDrafts.fromJson(fileName, text) }
                .getOrElse {
                    toast(getString(R.string.session_unreadable, fileName))
                    SessionDraft(file = SessionDrafts.baseName(fileName))
                }
            fileName != null -> SessionDraft(file = SessionDrafts.baseName(fileName))
            else -> SessionDraft()
        }
        model.session = draft
        model.sessionSaved = draft.deepCopy()
        model.sessionFile = fileName?.takeIf { text != null }
        model.sessionTarget = link?.let { ValidationErrors.targetOf(draft, it) to message }
        show(SessionEditFragment())
    }

    /** Abre T5 para a acao [index] da sessao em edicao (`null` = nova). */
    fun openAction(index: Int?, field: String? = null, message: String = "") {
        val session = model.session ?: return
        val existing = index?.let { session.actions.getOrNull(it) }
        val action = existing?.deepCopy() ?: ActionDraft()
        model.action = action
        model.actionSaved = action.deepCopy()
        model.actionIndex = if (existing != null) index else null
        model.actionTarget = field?.let { it to message }
        show(ActionEditFragment())
    }

    /** Deep-link do popup de erros: abre T4 no arquivo/campo da mensagem. */
    private fun openFromError(message: String) {
        val link = ValidationErrors.linkOf(message) ?: return
        openSession(link.fileName, link, message)
    }

    /**
     * Popup com a lista de erros do [SessionValidator]; erros com
     * `arquivo.json[:linha]` viram links para o editor.
     */
    fun showValidationErrors(errors: List<String>) {
        lateinit var dialog: AlertDialog
        val text = SpannableStringBuilder()
        errors.forEachIndexed { index, error ->
            if (index > 0) text.append("\n\n")
            text.append("• ")
            val start = text.length
            text.append(error)
            if (ValidationErrors.linkOf(error) != null) {
                text.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            dialog.dismiss()
                            openFromError(error)
                        }
                    },
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(TextView(context).apply {
                setText(R.string.validation_errors_help)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            })
            addView(TextView(context).apply {
                setPadding(0, padding / 2, 0, 0)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setText(text, TextView.BufferType.SPANNABLE)
                movementMethod = LinkMovementMethod.getInstance()
            })
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.validation_errors_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    fun confirm(title: CharSequence, message: CharSequence, @StringRes positive: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .show()
    }

    fun confirmDiscard(onDiscard: () -> Unit) = confirm(
        getString(R.string.discard_title),
        getString(R.string.discard_message),
        R.string.discard,
        onDiscard
    )

    fun toast(text: CharSequence) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

/** Base das telas do editor: titulo da Toolbar e acesso a atividade. */
abstract class EditorFragment(@LayoutRes layout: Int) : Fragment(layout) {

    protected val editor: ScriptEditorActivity get() = requireActivity() as ScriptEditorActivity
    protected val storage: EditorStorage get() = editor.storage

    @StringRes
    protected abstract fun title(): Int

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(title())
    }

    /** Voltar (Toolbar, gesto ou Cancelar) pede confirmacao se [isDirty]. */
    protected fun guardBack(isDirty: () -> Boolean) {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isDirty()) editor.confirmDiscard(::leave) else leave()
                }
            }
        )
    }

    protected fun leave() {
        parentFragmentManager.popBackStack()
    }

    protected fun goBack() = requireActivity().onBackPressedDispatcher.onBackPressed()
}

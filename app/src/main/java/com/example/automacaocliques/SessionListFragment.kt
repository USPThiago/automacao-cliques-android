package com.example.automacaocliques

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.automacaocliques.databinding.FragmentSessionListBinding
import com.example.automacaocliques.databinding.ItemSessionBinding
import com.google.android.material.divider.MaterialDividerItemDecoration
import java.util.Collections

/** T2: sessoes de `sessions_teste/`, com busca, ordem visual e exclusao. */
class SessionListFragment : EditorFragment(R.layout.fragment_session_list) {

    private data class Item(val file: String, val name: String)

    private val prefs by lazy { AppPreferences(requireContext()) }
    private val items = mutableListOf<Item>()
    private var shown = listOf<Item>()
    private var query = ""
    private var binding: FragmentSessionListBinding? = null
    private val adapter = Adapter()

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = false

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            if (query.isNotEmpty()) return false
            Collections.swap(items, from.bindingAdapterPosition, to.bindingAdapterPosition)
            shown = items.toList()
            adapter.notifyItemMoved(from.bindingAdapterPosition, to.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            prefs.editorSessionOrder = items.map { it.file }
        }
    })

    override fun title() = R.string.sessions_title

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentSessionListBinding.bind(view)
        binding = b
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.addItemDecoration(MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
        b.list.adapter = adapter
        touchHelper.attachToRecyclerView(b.list)
        b.searchInput.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            filter()
        }
        b.addButton.setOnClickListener { editor.openSession(null) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun reload() {
        editor.background({
            storage.sessionFiles().map { file ->
                val name = runCatching { SessionDrafts.fromJson(file, storage.read(file).orEmpty()).name }
                Item(file, name.getOrDefault(""))
            }
        }) { result ->
            val order = prefs.editorSessionOrder
            items.clear()
            items += result.getOrDefault(emptyList()).sortedBy { item ->
                order.indexOf(item.file).let { if (it < 0) Int.MAX_VALUE else it }
            }
            filter()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun filter() {
        shown = if (query.isEmpty()) {
            items.toList()
        } else {
            items.filter { it.name.contains(query, ignoreCase = true) || it.file.contains(query, ignoreCase = true) }
        }
        adapter.notifyDataSetChanged()
        binding?.emptyView?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun delete(item: Item) {
        editor.background({ storage.referencesTo(item.file) }) { result ->
            val refs = result.getOrDefault(emptyList())
            val message = if (refs.isEmpty()) {
                getString(R.string.delete_session_message)
            } else {
                getString(R.string.delete_session_refs, refs.joinToString("\n") { "• ${it.describe()}" })
            }
            editor.confirm(getString(R.string.delete_session_title, item.name.ifBlank { item.file }), message, R.string.delete) {
                storage.delete(item.file)
                prefs.editorSessionOrder = prefs.editorSessionOrder - item.file
                reload()
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = shown.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = shown[position]
            val isMain = item.file == SessionValidator.MAIN_SESSION
            with(holder.binding) {
                title.text = item.name.ifBlank { getString(R.string.session_unnamed) }
                subtitle.text = item.file
                badge.visibility = if (isMain) View.VISIBLE else View.GONE
                deleteButton.visibility = if (isMain) View.INVISIBLE else View.VISIBLE
                deleteButton.setOnClickListener { delete(item) }
                dragHandle.visibility = if (query.isEmpty()) View.VISIBLE else View.INVISIBLE
                dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
                    false
                }
                root.setOnClickListener { editor.openSession(item.file) }
            }
        }
    }

    private class Holder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)
}

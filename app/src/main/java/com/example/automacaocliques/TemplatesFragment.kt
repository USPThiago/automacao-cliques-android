package com.example.automacaocliques

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.automacaocliques.databinding.FragmentTemplatesBinding
import com.example.automacaocliques.databinding.ItemTemplateBinding
import com.example.automacaocliques.databinding.SheetTemplateBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.divider.MaterialDividerItemDecoration
import java.io.File

/** T3: templates de `templates/` (somente leitura). */
class TemplatesFragment : EditorFragment(R.layout.fragment_templates) {

    private class Item(val file: File, val size: Size?, val thumbnail: Bitmap?)

    private var items = emptyList<Item>()
    private var binding: FragmentTemplatesBinding? = null

    override fun title() = R.string.templates_title

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentTemplatesBinding.bind(view)
        binding = b
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.addItemDecoration(MaterialDividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
        b.list.adapter = Adapter()
        val thumbPx = (32 * resources.displayMetrics.density).toInt()
        editor.background({
            val store = storage.templates
            storage.templateFiles().sortedBy { it.nameWithoutExtension.lowercase() }.map { Item(it, store.sizeOf(it.name), decodeThumbnail(it, thumbPx)) }
        }) { result ->
            val view = binding ?: return@background
            items = result.getOrDefault(emptyList())
            view.list.adapter?.notifyDataSetChanged()
            view.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTemplateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.title.text = item.file.nameWithoutExtension.lowercase()
            holder.binding.subtitle.text = sizeText(requireContext(), item.size)
            holder.binding.thumbnail.setImageBitmap(item.thumbnail)
            holder.binding.viewButton.setOnClickListener { TemplateSheet.show(requireContext(), item.file) }
        }
    }

    private class Holder(val binding: ItemTemplateBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        fun sizeText(context: Context, size: Size?): String =
            size?.let { context.getString(R.string.template_size, it.width, it.height) }
                ?: context.getString(R.string.template_unreadable)

        private fun decodeThumbnail(file: File, targetPx: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
                sample *= 2
            }
            return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

/** BottomSheet com o template em tamanho real (zoom/scroll), nome e dimensoes. Usado por T3 e T5. */
object TemplateSheet {
    fun show(context: Context, file: File) {
        val binding = SheetTemplateBinding.inflate(LayoutInflater.from(context))
        binding.title.text = file.name
        binding.subtitle.text = TemplatesFragment.sizeText(context, TemplateStore(context, testMode = false).sizeOf(file.name))
        BitmapFactory.decodeFile(file.absolutePath)?.let(binding.image::setImageBitmap)
        BottomSheetDialog(context).apply {
            setContentView(binding.root)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.isDraggable = false
            show()
        }
    }
}

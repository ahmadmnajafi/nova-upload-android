package com.nova.upload

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.nova.upload.databinding.ItemCategoryBinding

class CategoryAdapter(
    private val onClick: (CategoryItem) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private var items: List<CategoryItem> = emptyList()
    var selectedId: Int = 0
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(list: List<CategoryItem>) {
        items = list
        notifyDataSetChanged()
    }

    fun selectedName(): String = items.firstOrNull { it.id == selectedId }?.name.orEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemCategoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CategoryItem) {
            val density = binding.root.resources.displayMetrics.density
            val extra = (item.depth.coerceAtMost(4) * 16 * density).toInt()
            binding.txtName.setPaddingRelative(extra, 0, 0, 0)
            binding.txtName.text = item.name
            val selected = item.id == selectedId
            val context = binding.root.context
            binding.root.strokeWidth = if (selected) (2 * density).toInt() else 0
            binding.root.strokeColor = ContextCompat.getColor(context, R.color.nova_primary)
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.nova_primary_soft else R.color.nova_surface
                )
            )
            binding.txtName.typeface = ResourcesCompat.getFont(
                context,
                if (selected) R.font.yekanbakh_bold else R.font.yekanbakh_regular
            )
            binding.imgCheck.visibility = if (selected) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}

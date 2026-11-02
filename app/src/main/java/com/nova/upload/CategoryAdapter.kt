package com.nova.upload

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
            val pad = "— ".repeat(item.depth.coerceAtMost(4))
            binding.txtName.text = pad + item.name
            val selected = item.id == selectedId
            binding.root.strokeColor = ContextCompat.getColor(
                binding.root.context,
                if (selected) R.color.nova_primary else R.color.nova_border
            )
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (selected) R.color.nova_primary_soft else R.color.nova_surface
                )
            )
            binding.txtName.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}

package sn.file.recover.ui.scan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import sn.file.recover.databinding.ItemScannedFileBinding
import sn.file.recover.model.ScannedFile

class ScanAdapter(
    private val onItemClick: (Int) -> Unit
) : ListAdapter<ScannedFile, ScanAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemScannedFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position, onItemClick)
    }
    
    class ViewHolder(
        private val binding: ItemScannedFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(file: ScannedFile, position: Int, onItemClick: (Int) -> Unit) {
            binding.apply {
                Glide.with(itemView.context)
                    .load(file.path)
                    .centerCrop()
                    .into(imageView)
                
                textSize.text = formatFileSize(file.size)
                checkBox.isChecked = file.isSelected
                
                root.setOnClickListener {
                    onItemClick(position)
                }
            }
        }
        
        private fun formatFileSize(bytes: Long): String {
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            
            return when {
                gb >= 1 -> String.format("%.1f GB", gb)
                mb >= 1 -> String.format("%.1f MB", mb)
                kb >= 1 -> String.format("%.1f KB", kb)
                else -> "$bytes B"
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<ScannedFile>() {
        override fun areItemsTheSame(oldItem: ScannedFile, newItem: ScannedFile): Boolean {
            return oldItem.path == newItem.path
        }
        
        override fun areContentsTheSame(oldItem: ScannedFile, newItem: ScannedFile): Boolean {
            return oldItem == newItem
        }
    }
}
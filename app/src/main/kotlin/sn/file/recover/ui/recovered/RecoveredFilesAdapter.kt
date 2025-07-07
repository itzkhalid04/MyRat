package sn.file.recover.ui.recovered

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import sn.file.recover.databinding.ItemRecoveredFileBinding
import sn.file.recover.model.RecoveredFile

class RecoveredFilesAdapter(
    private val onItemClick: (RecoveredFile) -> Unit,
    private val onShareClick: (RecoveredFile) -> Unit,
    private val onDeleteClick: (RecoveredFile) -> Unit
) : ListAdapter<RecoveredFile, RecoveredFilesAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecoveredFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onItemClick, onShareClick, onDeleteClick)
    }
    
    class ViewHolder(
        private val binding: ItemRecoveredFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(
            file: RecoveredFile,
            onItemClick: (RecoveredFile) -> Unit,
            onShareClick: (RecoveredFile) -> Unit,
            onDeleteClick: (RecoveredFile) -> Unit
        ) {
            binding.apply {
                Glide.with(itemView.context)
                    .load(file.path)
                    .centerCrop()
                    .into(imageView)
                
                root.setOnClickListener {
                    onItemClick(file)
                }
                
                btnShare.setOnClickListener {
                    onShareClick(file)
                }
                
                btnDelete.setOnClickListener {
                    onDeleteClick(file)
                }
            }
        }
    }
    
    private class DiffCallback : DiffUtil.ItemCallback<RecoveredFile>() {
        override fun areItemsTheSame(oldItem: RecoveredFile, newItem: RecoveredFile): Boolean {
            return oldItem.path == newItem.path
        }
        
        override fun areContentsTheSame(oldItem: RecoveredFile, newItem: RecoveredFile): Boolean {
            return oldItem == newItem
        }
    }
}
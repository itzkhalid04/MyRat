package sn.file.recover.ui.recovered

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.gms.ads.AdRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import sn.file.recover.R
import sn.file.recover.databinding.ActivityRecoveredFilesBinding

@AndroidEntryPoint
class RecoveredFilesActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityRecoveredFilesBinding
    private val viewModel: RecoveredFilesViewModel by viewModels()
    private lateinit var adapter: RecoveredFilesAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecoveredFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupRecyclerView()
        observeViewModel()
        
        viewModel.loadRecoveredFiles()
    }
    
    private fun setupUI() {
        binding.apply {
            toolbar.setNavigationOnClickListener { finish() }
            toolbar.title = getString(R.string.recovered_files)
            
            // Load banner ad
            val adRequest = AdRequest.Builder().build()
            adView.loadAd(adRequest)
        }
    }
    
    private fun setupRecyclerView() {
        adapter = RecoveredFilesAdapter(
            onItemClick = { file ->
                viewModel.openFile(file)
            },
            onShareClick = { file ->
                viewModel.shareFile(file)
            },
            onDeleteClick = { file ->
                viewModel.deleteFile(file)
            }
        )
        
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@RecoveredFilesActivity, 3)
            adapter = this@RecoveredFilesActivity.adapter
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                binding.apply {
                    progressBar.visibility = if (state.isLoading) android.view.View.VISIBLE else android.view.View.GONE
                    emptyView.visibility = if (state.files.isEmpty() && !state.isLoading) android.view.View.VISIBLE else android.view.View.GONE
                    
                    adapter.submitList(state.files)
                }
            }
        }
    }
}
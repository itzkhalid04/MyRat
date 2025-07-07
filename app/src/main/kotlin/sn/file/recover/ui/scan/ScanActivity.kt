package sn.file.recover.ui.scan

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.gms.ads.AdRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import sn.file.recover.R
import sn.file.recover.databinding.ActivityScanBinding
import sn.file.recover.ui.recovered.RecoveredFilesActivity
import sn.file.recover.utils.FileType

@AndroidEntryPoint
class ScanActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_FILE_TYPE = "extra_file_type"
    }
    
    private lateinit var binding: ActivityScanBinding
    private val viewModel: ScanViewModel by viewModels()
    private lateinit var adapter: ScanAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        val fileType = FileType.valueOf(intent.getStringExtra(EXTRA_FILE_TYPE) ?: FileType.IMAGE.name)
        
        setupUI(fileType)
        setupRecyclerView()
        observeViewModel()
        
        viewModel.startScan(fileType)
    }
    
    private fun setupUI(fileType: FileType) {
        binding.apply {
            toolbar.setNavigationOnClickListener { finish() }
            toolbar.title = when (fileType) {
                FileType.IMAGE -> getString(R.string.scan_photos)
                FileType.VIDEO -> getString(R.string.scan_videos)
                FileType.AUDIO -> "Scan Audio"
            }
            
            btnSelectAll.setOnClickListener {
                viewModel.toggleSelectAll()
            }
            
            btnRecover.setOnClickListener {
                viewModel.recoverSelectedFiles()
            }
            
            // Load banner ad
            val adRequest = AdRequest.Builder().build()
            adView.loadAd(adRequest)
        }
    }
    
    private fun setupRecyclerView() {
        adapter = ScanAdapter { position ->
            viewModel.toggleFileSelection(position)
        }
        
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@ScanActivity, 3)
            adapter = this@ScanActivity.adapter
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                binding.apply {
                    progressBar.visibility = if (state.isScanning) android.view.View.VISIBLE else android.view.View.GONE
                    tvProgress.text = if (state.isScanning) {
                        getString(R.string.scanning)
                    } else {
                        getString(R.string.files_found, state.files.size)
                    }
                    
                    btnSelectAll.text = if (state.allSelected) {
                        getString(R.string.deselect_all)
                    } else {
                        getString(R.string.select_all)
                    }
                    
                    btnRecover.isEnabled = state.selectedCount > 0
                    btnRecover.text = getString(R.string.selected_count, state.selectedCount)
                    
                    adapter.submitList(state.files)
                    
                    if (state.recoveryComplete) {
                        val intent = Intent(this@ScanActivity, RecoveredFilesActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                }
            }
        }
    }
}
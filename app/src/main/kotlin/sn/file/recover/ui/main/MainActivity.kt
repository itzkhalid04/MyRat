package sn.file.recover.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import sn.file.recover.R
import sn.file.recover.databinding.ActivityMainBinding
import sn.file.recover.ui.recovered.RecoveredFilesActivity
import sn.file.recover.ui.scan.ScanActivity
import sn.file.recover.utils.FileType

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var interstitialAd: InterstitialAd? = null
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.onPermissionsGranted()
        } else {
            showPermissionDeniedDialog()
        }
    }
    
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                viewModel.onPermissionsGranted()
            } else {
                showPermissionDeniedDialog()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        observeViewModel()
        checkPermissions()
        loadInterstitialAd()
    }
    
    private fun setupUI() {
        binding.apply {
            cardScanPhotos.setOnClickListener {
                startScanActivity(FileType.IMAGE)
            }
            
            cardScanVideos.setOnClickListener {
                startScanActivity(FileType.VIDEO)
            }
            
            cardRecoveredFiles.setOnClickListener {
                startActivity(Intent(this@MainActivity, RecoveredFilesActivity::class.java))
            }
            
            btnMore.setOnClickListener {
                showMoreOptions()
            }
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is MainUiState.Loading -> {
                        // Show loading if needed
                    }
                    is MainUiState.PermissionsRequired -> {
                        requestPermissions()
                    }
                    is MainUiState.Ready -> {
                        // UI is ready
                    }
                    is MainUiState.Error -> {
                        showError(state.message)
                    }
                }
            }
        }
    }
    
    private fun checkPermissions() {
        viewModel.checkPermissions()
    }
    
    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        
        // Check if we need MANAGE_EXTERNAL_STORAGE for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                showManageStoragePermissionDialog()
                return
            }
        }
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.onPermissionsGranted()
        }
    }
    
    private fun showManageStoragePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.manage_storage_permission_message)
            .setPositiveButton(R.string.grant) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    manageStorageLauncher.launch(intent)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                showPermissionDeniedDialog()
            }
            .show()
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_denied)
            .setMessage(R.string.permission_denied_message)
            .setPositiveButton(R.string.settings) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(R.string.exit) { _, _ ->
                finish()
            }
            .show()
    }
    
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    }
    
    private fun startScanActivity(fileType: FileType) {
        showInterstitialAd {
            val intent = Intent(this, ScanActivity::class.java)
            intent.putExtra(ScanActivity.EXTRA_FILE_TYPE, fileType.name)
            startActivity(intent)
        }
    }
    
    private fun loadInterstitialAd() {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            getString(R.string.admob_interstitial_id),
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }
                
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }
    
    private fun showInterstitialAd(onAdClosed: () -> Unit) {
        interstitialAd?.let { ad ->
            ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadInterstitialAd()
                    onAdClosed()
                }
                
                override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                    interstitialAd = null
                    onAdClosed()
                }
            }
            ad.show(this)
        } ?: onAdClosed()
    }
    
    private fun showMoreOptions() {
        // Implement more options menu
    }
    
    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
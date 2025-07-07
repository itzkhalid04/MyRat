package sn.file.recover.ui.main

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    fun checkPermissions() {
        viewModelScope.launch {
            val hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                hasPermission(Manifest.permission.READ_MEDIA_VIDEO) &&
                hasPermission(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE) &&
                hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            
            val hasManageStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                true
            }
            
            if (hasPermissions && hasManageStorage) {
                _uiState.value = MainUiState.Ready
            } else {
                _uiState.value = MainUiState.PermissionsRequired
            }
        }
    }
    
    fun onPermissionsGranted() {
        _uiState.value = MainUiState.Ready
    }
    
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }
}

sealed class MainUiState {
    object Loading : MainUiState()
    object PermissionsRequired : MainUiState()
    object Ready : MainUiState()
    data class Error(val message: String) : MainUiState()
}
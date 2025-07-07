package sn.file.recover.ui.recovered

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sn.file.recover.data.repository.FileRepository
import sn.file.recover.model.RecoveredFile
import java.io.File
import javax.inject.Inject

@HiltViewModel
class RecoveredFilesViewModel @Inject constructor(
    application: Application,
    private val fileRepository: FileRepository
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(RecoveredFilesUiState())
    val uiState: StateFlow<RecoveredFilesUiState> = _uiState.asStateFlow()
    
    fun loadRecoveredFiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            try {
                val files = fileRepository.getRecoveredFiles()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    files = files
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }
    
    fun openFile(file: RecoveredFile) {
        try {
            val context = getApplication<Application>()
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                File(file.path)
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, file.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message)
        }
    }
    
    fun shareFile(file: RecoveredFile) {
        try {
            val context = getApplication<Application>()
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                File(file.path)
            )
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = file.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message)
        }
    }
    
    fun deleteFile(file: RecoveredFile) {
        viewModelScope.launch {
            try {
                fileRepository.deleteRecoveredFile(file)
                loadRecoveredFiles() // Refresh the list
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}

data class RecoveredFilesUiState(
    val isLoading: Boolean = false,
    val files: List<RecoveredFile> = emptyList(),
    val error: String? = null
)
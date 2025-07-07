package sn.file.recover.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sn.file.recover.data.repository.FileRepository
import sn.file.recover.model.ScannedFile
import sn.file.recover.utils.FileType
import javax.inject.Inject

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val fileRepository: FileRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()
    
    fun startScan(fileType: FileType) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            
            try {
                val files = fileRepository.scanForFiles(fileType)
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    files = files
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    error = e.message
                )
            }
        }
    }
    
    fun toggleFileSelection(position: Int) {
        val currentState = _uiState.value
        val updatedFiles = currentState.files.toMutableList()
        updatedFiles[position] = updatedFiles[position].copy(
            isSelected = !updatedFiles[position].isSelected
        )
        
        _uiState.value = currentState.copy(
            files = updatedFiles,
            selectedCount = updatedFiles.count { it.isSelected },
            allSelected = updatedFiles.all { it.isSelected }
        )
    }
    
    fun toggleSelectAll() {
        val currentState = _uiState.value
        val newSelectionState = !currentState.allSelected
        
        val updatedFiles = currentState.files.map { file ->
            file.copy(isSelected = newSelectionState)
        }
        
        _uiState.value = currentState.copy(
            files = updatedFiles,
            selectedCount = if (newSelectionState) updatedFiles.size else 0,
            allSelected = newSelectionState
        )
    }
    
    fun recoverSelectedFiles() {
        viewModelScope.launch {
            val selectedFiles = _uiState.value.files.filter { it.isSelected }
            if (selectedFiles.isNotEmpty()) {
                try {
                    fileRepository.recoverFiles(selectedFiles)
                    _uiState.value = _uiState.value.copy(recoveryComplete = true)
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            }
        }
    }
}

data class ScanUiState(
    val isScanning: Boolean = false,
    val files: List<ScannedFile> = emptyList(),
    val selectedCount: Int = 0,
    val allSelected: Boolean = false,
    val recoveryComplete: Boolean = false,
    val error: String? = null
)
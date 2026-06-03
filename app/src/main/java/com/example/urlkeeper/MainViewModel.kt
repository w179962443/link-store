package com.example.urlkeeper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.urlkeeper.backup.BackupSettings
import com.example.urlkeeper.data.UrlEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UrlKeeperUiState(
    val entries: List<UrlEntry> = emptyList(),
    val backupSettings: BackupSettings = BackupSettings(),
    val input: String = "",
    val message: String? = null,
    val isSaving: Boolean = false,
    val isExporting: Boolean = false,
    val isSavingBackupSettings: Boolean = false
) {
    val isBusy: Boolean = isSaving || isExporting || isSavingBackupSettings
}

class MainViewModel(private val repository: UrlRepository) : ViewModel() {
    private val transientState = MutableStateFlow(UrlKeeperUiState())

    val uiState: StateFlow<UrlKeeperUiState> = combine(
        repository.entries,
        repository.backupSettings,
        transientState
    ) { entries, backupSettings, transient ->
        transient.copy(entries = entries, backupSettings = backupSettings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UrlKeeperUiState())

    fun onInputChanged(value: String) {
        transientState.update { it.copy(input = value, message = null) }
    }

    fun dismissMessage() {
        transientState.update { it.copy(message = null) }
    }

    fun sendUrl() {
        val rawInput = transientState.value.input
        if (rawInput.isBlank() || transientState.value.isSaving) return

        viewModelScope.launch {
            transientState.update { it.copy(isSaving = true, message = null) }
            runCatching { repository.saveUrl(rawInput) }
                .onSuccess { result ->
                    val backupMessage = result.backupMessage?.let { "\n$it" }.orEmpty()
                    transientState.update {
                        it.copy(
                            input = "",
                            isSaving = false,
                            message = "已保存: ${result.domain}$backupMessage"
                        )
                    }
                }
                .onFailure { throwable ->
                    transientState.update {
                        it.copy(isSaving = false, message = throwable.message ?: "保存失败")
                    }
                }
        }
    }

    fun exportAndClear() {
        if (transientState.value.isExporting) return

        viewModelScope.launch {
            transientState.update { it.copy(isExporting = true, message = null) }
            runCatching { repository.exportAndClear() }
                .onSuccess { result ->
                    transientState.update {
                        it.copy(
                            isExporting = false,
                            message = "已导出 ${result.fileName}\n位置: ${result.publicLocation}\n内部归档: ${result.archiveLocation}"
                        )
                    }
                }
                .onFailure { throwable ->
                    transientState.update {
                        it.copy(isExporting = false, message = throwable.message ?: "导出失败，记录未清空")
                    }
                }
        }
    }

    fun saveBackupSettings(settings: BackupSettings) {
        viewModelScope.launch {
            transientState.update { it.copy(isSavingBackupSettings = true, message = null) }
            runCatching { repository.saveBackupSettings(settings) }
                .onSuccess { gistId ->
                    val suffix = gistId?.let { "\nGist: $it" }.orEmpty()
                    transientState.update {
                        it.copy(isSavingBackupSettings = false, message = "备份设置已保存$suffix")
                    }
                }
                .onFailure { throwable ->
                    transientState.update {
                        it.copy(isSavingBackupSettings = false, message = throwable.message ?: "备份设置保存失败")
                    }
                }
        }
    }
}

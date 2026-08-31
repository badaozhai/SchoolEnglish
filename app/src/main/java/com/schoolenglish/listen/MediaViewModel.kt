package com.schoolenglish.listen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MediaRepository(application)
    private val _files = MutableStateFlow<List<MediaFile>>(emptyList())
    val files: StateFlow<List<MediaFile>> = _files.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.installBundledMediaIfNeeded() }
            _files.value = repository.listMedia()
        }
    }

    fun refresh() {
        viewModelScope.launch { _files.value = repository.listMedia() }
    }

    fun importFile(uri: Uri, displayName: String?, onResult: (Result<ImportResult>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { repository.importFile(uri, displayName) }
            if (result.isSuccess) refresh()
            onResult(result)
        }
    }

    fun delete(mediaFile: MediaFile, onDeleted: () -> Unit) {
        viewModelScope.launch {
            if (repository.delete(mediaFile)) {
                _files.value = _files.value.filterNot { it.file == mediaFile.file }
                onDeleted()
            }
        }
    }
}

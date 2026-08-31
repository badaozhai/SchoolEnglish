package com.schoolenglish.listen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranscriptViewModel(application: Application) : AndroidViewModel(application) {
    private val database = TranscriptDatabase(application)
    private val _lines = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val lines: StateFlow<List<TranscriptLine>> = _lines.asStateFlow()
    private var loadGeneration = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { database.seedIfNeeded() } }
    }

    fun loadFor(fileName: String) {
        val generation = ++loadGeneration
        _lines.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            val value = runCatching {
                database.seedIfNeeded()
                database.linesForMedia(fileName)
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (generation == loadGeneration) _lines.value = value
            }
        }
    }
}

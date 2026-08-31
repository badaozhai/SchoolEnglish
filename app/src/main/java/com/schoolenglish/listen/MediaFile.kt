package com.schoolenglish.listen

import java.io.File

enum class MediaType { AUDIO, VIDEO }

data class MediaFile(
    val file: File,
    val type: MediaType,
    val sizeBytes: Long,
    val lastModified: Long
)

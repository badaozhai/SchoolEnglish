package com.schoolenglish.listen

import android.content.Context
import android.net.Uri
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MediaRepository(private val context: Context) {
    private val mediaDir = File(context.filesDir, "media").apply { mkdirs() }

    suspend fun listMedia(): List<MediaFile> = withContext(Dispatchers.IO) {
        mediaDir.walkTopDown()
            .filter { it.isFile && mediaTypeFor(it.name) != null }
            .map { MediaFile(it, mediaTypeFor(it.name)!!, it.length(), it.lastModified()) }
            .sortedBy { it.file.name.lowercase(Locale.ROOT) }
            .toList()
    }

    suspend fun importFile(uri: Uri, displayName: String?): ImportResult = withContext(Dispatchers.IO) {
        val sourceName = displayName?.takeIf { it.isNotBlank() } ?: "导入文件"
        val extension = sourceName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val safeName = sourceName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        return@withContext when (extension) {
            "rar" -> importArchive(uri)
            "mp3", "mp4" -> {
                val target = uniqueFile(File(mediaDir, safeName))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: error("无法读取所选文件")
                ImportResult(1, emptyList())
            }
            else -> error("仅支持 RAR、MP3 和 MP4 文件")
        }
    }

    private fun importArchive(uri: Uri): ImportResult {
        val temp = File.createTempFile("archive_", ".rar", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: error("无法读取所选压缩包")

            var imported = 0
            val skipped = mutableListOf<String>()
            Archive(temp).use { archive ->
                for (header in archive.fileHeaders) {
                    if (header.isDirectory) continue
                    val original = header.fileNameString
                    val extension = original.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (extension != "mp3" && extension != "mp4") {
                        skipped += original
                        continue
                    }
                    val name = File(original).name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                    val target = uniqueFile(File(mediaDir, name))
                    FileOutputStream(target).use { output -> archive.extractFile(header, output) }
                    imported++
                }
            }
            if (imported == 0) error("压缩包中没有 MP3 或 MP4 文件")
            return ImportResult(imported, skipped)
        } finally {
            temp.delete()
        }
    }

    fun delete(mediaFile: MediaFile): Boolean = mediaFile.file.delete()

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val extension = file.extension
        var index = 2
        var candidate: File
        do {
            candidate = File(file.parentFile, "$base ($index).$extension")
            index++
        } while (candidate.exists())
        return candidate
    }

    private fun mediaTypeFor(name: String): MediaType? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp3" -> MediaType.AUDIO
        "mp4" -> MediaType.VIDEO
        else -> null
    }
}

data class ImportResult(val importedCount: Int, val skippedFiles: List<String>)

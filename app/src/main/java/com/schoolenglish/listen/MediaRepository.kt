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

    /** Copies the shipped lessons once so a fresh install is ready to use. */
    suspend fun installBundledMediaIfNeeded(): Int = withContext(Dispatchers.IO) {
        val marker = File(mediaDir, BUNDLED_MARKER)
        if (marker.takeIf { it.exists() }?.readText()?.trim() == BUNDLED_VERSION) return@withContext 0

        val assets = context.assets.list(BUNDLED_ASSET_DIR).orEmpty()
        var imported = 0
        assets.filter { mediaTypeFor(it) != null }.sorted().forEach { assetName ->
            val target = File(mediaDir, assetName)
            if (target.exists()) return@forEach
            val pending = File(mediaDir, ".$assetName.installing")
            pending.delete()
            context.assets.open("$BUNDLED_ASSET_DIR/$assetName").use { input ->
                FileOutputStream(pending).use { output -> input.copyTo(output) }
            }
            check(pending.renameTo(target)) { "无法安装内置音频：$assetName" }
            imported++
        }
        marker.writeText(BUNDLED_VERSION)
        imported
    }

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
                    val relativePath = original
                        .replace('\\', '/')
                        .split('/')
                        .filter { it.isNotBlank() && it != "." }
                    if (relativePath.any { it == ".." }) {
                        skipped += original
                        continue
                    }
                    val safePath = relativePath.joinToString(File.separator) {
                        it.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                    }
                    val target = uniqueFile(File(mediaDir, safePath))
                    target.parentFile?.mkdirs()
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

    companion object {
        private const val BUNDLED_ASSET_DIR = "preset_media"
        private const val BUNDLED_MARKER = ".bundled_media_v1"
        private const val BUNDLED_VERSION = "1"
    }
}

data class ImportResult(val importedCount: Int, val skippedFiles: List<String>)

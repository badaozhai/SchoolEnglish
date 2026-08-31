package com.schoolenglish.listen

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.Locale

class TranscriptDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE transcript_line (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_key TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                text TEXT NOT NULL,
                UNIQUE(media_key, ordinal)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX transcript_line_media_key ON transcript_line(media_key, ordinal)")
        db.execSQL("CREATE TABLE transcript_meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS transcript_line")
        db.execSQL("DROP TABLE IF EXISTS transcript_meta")
        onCreate(db)
    }

    @Synchronized
    fun seedIfNeeded() {
        val db = writableDatabase
        val seeded = db.query("transcript_meta", arrayOf("value"), "name = ?", arrayOf(SEED_NAME), null, null, null).use { it.moveToFirst() && it.getString(0) == SEED_VERSION }
        if (seeded) return
        PDFBoxResourceLoader.init(appContext)
        val parsed = appContext.assets.open(ASSET_NAME).use { input ->
            PDDocument.load(input).use { document -> parse(PDFTextStripper().getText(document)) }
        }
        db.beginTransaction()
        try {
            db.delete("transcript_line", null, null)
            db.delete("transcript_meta", null, null)
            parsed.forEach { (key, lines) ->
                lines.forEachIndexed { index, line ->
                    db.insertOrThrow("transcript_line", null, ContentValues().apply {
                        put("media_key", key)
                        put("ordinal", index)
                        put("text", line)
                    })
                }
            }
            db.insertOrThrow("transcript_meta", null, ContentValues().apply {
                put("name", SEED_NAME)
                put("value", SEED_VERSION)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun linesForMedia(fileName: String): List<TranscriptLine> {
        val key = mediaKey(fileName) ?: return emptyList()
        return readableDatabase.query(
            "transcript_line", arrayOf("id", "text", "ordinal"), "media_key = ?", arrayOf(key), null, null, "ordinal ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(TranscriptLine(cursor.getLong(0), cursor.getString(1), cursor.getInt(2)))
                }
            }
        }
    }

    private fun parse(raw: String): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var currentKey: String? = null
        var currentUnit: String? = null
        var pending = ""

        fun flush() {
            val key = currentKey
            val text = pending.trim()
            if (key != null && text.length >= 2) result.getOrPut(key) { mutableListOf() }.add(text)
            pending = ""
        }

        raw.replace('\u000C'.toString(), "\n").lineSequence().forEach { source ->
            val line = source.trim().replace(Regex("\\s+"), " ")
            if (line.isBlank() || line.matches(Regex("\\d+"))) return@forEach

            Regex("(?i)^Unit\\s+(\\d+)$").matchEntire(line)?.let {
                flush()
                currentUnit = it.groupValues[1]
                currentKey = null
                return@forEach
            }
            Regex("(?i)^Period\\s+(\\d+)$").matchEntire(line)?.let {
                flush()
                currentKey = currentUnit?.let { unit -> "unit-$unit-${it.groupValues[1]}" }
                return@forEach
            }
            Regex("(?i)^Assessment\\s+for\\s+Unit\\s+(\\d+)$").matchEntire(line)?.let {
                flush()
                currentKey = "assessment-${it.groupValues[1]}"
                return@forEach
            }
            if (currentKey == null) return@forEach
            if (line.matches(Regex("(?i)^[A-D]\\.\\s+Listen.*"))) return@forEach

            // Headings are emitted as a separate line by PDFTextStripper.
            if (line.matches(Regex("(?i)^Period\\s+\\d+.*"))) return@forEach
            val startsNew = line.matches(Regex("^(\\d+\\.|[A-D]:)\\s+.*"))
            if (startsNew) flush()
            pending = if (pending.isBlank()) line else "$pending $line"
            if (pending.endsWith('.') || pending.endsWith('!') || pending.endsWith('?')) flush()
        }
        flush()
        return result.mapValues { (_, lines) -> lines.distinct() }
    }

    companion object {
        private const val DB_NAME = "school_english.db"
        private const val DB_VERSION = 1
        private const val ASSET_NAME = "transcripts.pdf"
        private const val SEED_NAME = "pdf_seed"
        private const val SEED_VERSION = "2026-08-31-v1"

        fun mediaKey(fileName: String): String? {
            val normalized = fileName.lowercase(Locale.ROOT)
            Regex("unit\\s*(\\d+)\\s*period\\s*(\\d+)").find(normalized)?.let {
                return "unit-${it.groupValues[1]}-${it.groupValues[2]}"
            }
            Regex("assessment\\s*for\\s*unit\\s*(\\d+)").find(normalized)?.let {
                return "assessment-${it.groupValues[1]}"
            }
            return null
        }
    }
}

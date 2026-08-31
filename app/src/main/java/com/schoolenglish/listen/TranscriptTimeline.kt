package com.schoolenglish.listen

import kotlin.math.min

object TranscriptTimeline {
    private const val READ_COUNT = 2.0
    private const val TRANSITION_OVERHEAD = 6.0
    private const val INTRO_FRACTION = 0.18
    private const val MAX_INTRO_MS = 10_500L
    private const val OUTRO_FRACTION = 0.06
    private const val MAX_OUTRO_MS = 3_000L
    private val spokenWord = Regex("\\p{L}+(?:['-]\\p{L}+)*")

    fun indexAt(positionMs: Long, durationMs: Long, lines: List<TranscriptLine>): Int {
        if (lines.isEmpty()) return -1
        if (lines.size == 1 || durationMs <= 0L) return 0

        val introMs = min(MAX_INTRO_MS, (durationMs * INTRO_FRACTION).toLong())
        val outroMs = min(MAX_OUTRO_MS, (durationMs * OUTRO_FRACTION).toLong())
        val contentMs = (durationMs - introMs - outroMs).coerceAtLeast(1L)
        val weights = lines.map(::weightFor)
        val totalWeight = weights.sum()
        val position = positionMs.coerceIn(0L, durationMs).toDouble()
        var boundary = introMs.toDouble()

        for (index in 0 until lines.lastIndex) {
            boundary += contentMs * (weights[index] / totalWeight)
            if (position < boundary) return index
        }
        return lines.lastIndex
    }

    private fun weightFor(line: TranscriptLine): Double {
        val wordCount = spokenWord.findAll(line.text).count().coerceAtLeast(1)
        return wordCount * READ_COUNT + TRANSITION_OVERHEAD
    }
}

package com.schoolenglish.listen

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTimelineTest {
    private val repeatedLines = listOf(
        line(0, "1. Liu Jiajia listens to teachers carefully in class."),
        line(1, "2. Liu Tao often keeps his room clean and tidy."),
        line(2, "3. Su Hai often takes notes in class."),
        line(3, "4. Mike does exercise after school every day.")
    )

    @Test
    fun keepsFirstLineActiveDuringItsSecondReading() {
        assertEquals(0, TranscriptTimeline.indexAt(23_500L, 65_358L, repeatedLines))
        assertEquals(1, TranscriptTimeline.indexAt(24_000L, 65_358L, repeatedLines))
    }

    @Test
    fun followsObservedLaterBoundaries() {
        assertEquals(1, TranscriptTimeline.indexAt(38_000L, 65_358L, repeatedLines))
        assertEquals(2, TranscriptTimeline.indexAt(38_500L, 65_358L, repeatedLines))
        assertEquals(2, TranscriptTimeline.indexAt(50_000L, 65_358L, repeatedLines))
        assertEquals(3, TranscriptTimeline.indexAt(50_500L, 65_358L, repeatedLines))
    }

    @Test
    fun clampsStartAndEndToAvailableLines() {
        assertEquals(0, TranscriptTimeline.indexAt(-1L, 65_358L, repeatedLines))
        assertEquals(3, TranscriptTimeline.indexAt(80_000L, 65_358L, repeatedLines))
        assertEquals(-1, TranscriptTimeline.indexAt(0L, 65_358L, emptyList()))
    }

    private fun line(ordinal: Int, text: String) = TranscriptLine(ordinal.toLong(), text, ordinal)
}

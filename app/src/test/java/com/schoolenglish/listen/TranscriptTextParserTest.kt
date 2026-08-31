package com.schoolenglish.listen

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTextParserTest {
    @Test
    fun splitsParagraphIntoReadableSentences() {
        val text = "1. Lucy gets up early. Then she washes her face. After that, she reads English."

        assertEquals(
            listOf(
                "1. Lucy gets up early.",
                "Then she washes her face.",
                "After that, she reads English."
            ),
            TranscriptTextParser.splitSentences(text)
        )
    }

    @Test
    fun preservesQuestionAndChoiceLabels() {
        assertEquals(
            listOf("A. Where is Mike?", "B. He is in class."),
            TranscriptTextParser.splitSentences("A. Where is Mike? B. He is in class.")
        )
    }
}

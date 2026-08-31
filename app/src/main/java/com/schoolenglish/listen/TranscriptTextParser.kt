package com.schoolenglish.listen

object TranscriptTextParser {
    private val sentenceBoundary = Regex("(?<=[.!?])\\s+(?=(?:\\d+\\.|[A-D]\\.)?\\s*[A-Z])")
    private val standaloneLabel = Regex("^(?:\\d+|[A-D])\\.$")

    fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var pendingLabel: String? = null
        text.trim().split(sentenceBoundary).forEach { rawPart ->
            val part = rawPart.trim()
            if (part.isBlank()) return@forEach
            if (part.matches(standaloneLabel)) {
                pendingLabel = part
                return@forEach
            }
            result += pendingLabel?.let { "$it $part" } ?: part
            pendingLabel = null
        }
        return result
    }
}

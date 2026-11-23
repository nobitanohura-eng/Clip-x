package com.clipai.v2.domain

import java.text.BreakIterator
import java.util.Locale

/**
 * Pure Kotlin caption & hook builder enforcing ClipAI v2 rules:
 * - <= 3 words per line
 * - <= 42 grapheme clusters per line
 * - Devanagari-safe wrapping (never splits a word, matra, or consonant cluster)
 * - Unedited uncertain words (<0.60) render as `___` instead of hallucinating
 * - Verbatim opening hook strictly <= 10 words
 */
object CaptionEngine {
    const val DEFAULT_MAX_WORDS_PER_LINE = 3
    const val DEFAULT_MAX_CHARS_PER_LINE = 42
    const val DEFAULT_MAX_HOOK_WORDS = 10

    private val DEVANAGARI_REGEX = Regex("[\\u0900-\\u097F]")
    private val SENTENCE_END_REGEX = Regex("[.!?।]+$")
    private val FILLER_WORDS = setOf("um", "uh", "like", "so", "well", "actually", "basically", "toh", "matlab")

    fun containsDevanagari(text: String): Boolean = DEVANAGARI_REGEX.containsMatchIn(text)

    /**
     * Counts user-perceived grapheme clusters safely (prevents breaking Devanagari conjuncts/matras).
     */
    fun countGraphemes(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var count = 0
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            count++
            end = iterator.next()
        }
        return count
    }

    fun buildCaptionLines(
        words: List<WordToken>,
        clipStartSec: Double,
        clipEndSec: Double,
        maxWordsPerLine: Int = DEFAULT_MAX_WORDS_PER_LINE,
        maxCharsPerLine: Int = DEFAULT_MAX_CHARS_PER_LINE
    ): List<CaptionLine> {
        val clipWords = words
            .filter { it.endSec > clipStartSec && it.startSec < clipEndSec }
            .sortedBy { it.startSec }

        if (clipWords.isEmpty()) return emptyList()

        val lines = mutableListOf<CaptionLine>()
        val bucket = mutableListOf<WordToken>()

        fun flush() {
            if (bucket.isEmpty()) return
            val displayText = bucket.joinToString(" ") { it.displayText }
            lines.add(
                CaptionLine(
                    id = "cap-${lines.size + 1}",
                    startSec = maxOf(clipStartSec, bucket.first().startSec),
                    endSec = minOf(clipEndSec, bucket.last().endSec),
                    words = bucket.toList(),
                    displayText = displayText,
                    graphemeCount = countGraphemes(displayText),
                    hasDevanagari = containsDevanagari(displayText)
                )
            )
            bucket.clear()
        }

        for (word in clipWords) {
            val candidateBucket = bucket + word
            val candidateText = candidateBucket.joinToString(" ") { it.displayText }
            val candidateLen = countGraphemes(candidateText)
            val prev = bucket.lastOrNull()
            val bigPause = prev != null && (word.startSec - prev.endSec) > 0.65

            if (bucket.isNotEmpty() &&
                (candidateBucket.size > maxWordsPerLine || candidateLen > maxCharsPerLine || bigPause)
            ) {
                flush()
            }

            // Never split a single word across lines (preserves Devanagari clusters/matras intact)
            bucket.add(word)

            if (bucket.size >= 2 && SENTENCE_END_REGEX.containsMatchIn(word.text)) {
                flush()
            }
        }
        flush()

        return lines
    }

    /**
     * Extracts a verbatim speaker quote from the opening 3-4.5s, strictly <= [maxWords] words.
     * Never fabricates text.
     */
    fun extractVerbatimHook(
        words: List<WordToken>,
        clipStartSec: Double,
        maxWords: Int = DEFAULT_MAX_HOOK_WORDS
    ): String {
        val opening = words
            .filter { it.startSec >= clipStartSec - 0.05 && it.startSec <= clipStartSec + 4.5 }
            .sortedBy { it.startSec }
            .take(maxWords)

        if (opening.isEmpty()) return ""

        val picked = mutableListOf<WordToken>()
        for (w in opening) {
            picked.add(w)
            if (picked.size >= 4 && SENTENCE_END_REGEX.containsMatchIn(w.text)) break
        }

        return picked.joinToString(" ") { it.displayText }.trim()
    }

    /**
     * Deterministic offline title template used when LLM is disabled or unavailable.
     * Always rendered with explicit TITLE indicator (never disguised as a quote).
     */
    fun buildOfflineTemplateTitle(
        words: List<WordToken>,
        clipStartSec: Double,
        clipEndSec: Double,
        hookSignals: List<String>
    ): String {
        val keywords = words
            .filter { it.startSec >= clipStartSec && it.endSec <= clipEndSec }
            .map { ScoringEngine.cleanToken(it.text) }
            .filter { it.length > 3 && it !in FILLER_WORDS }
            .distinct()
            .take(3)

        if (keywords.isEmpty()) return "Key Highlight"

        val formatted = keywords.joinToString(" · ") { token ->
            if (containsDevanagari(token)) token
            else token.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

        return when {
            hookSignals.any { it.contains("Question", ignoreCase = true) } -> "Why $formatted"
            hookSignals.any { it.contains("number", ignoreCase = true) } -> "Key Rule: $formatted"
            else -> formatted
        }
    }
}

package com.clipai.v2.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionEngineTest {

    @Test
    fun linesRespectMax3WordsAndMax42Characters() {
        val tokens = listOf(
            "Never", "upload", "your", "raw", "footage",
            "to", "slow", "cloud", "queues", "again"
        ).mapIndexed { i, text ->
            WordToken("w$i", text, i * 0.35, i * 0.35 + 0.30, 0.94f)
        }

        val lines = CaptionEngine.buildCaptionLines(tokens, clipStartSec = 0.0, clipEndSec = 5.0)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.wordCount <= 3 })
        assertTrue(lines.all { it.graphemeCount <= 42 })
        assertEquals(tokens.joinToString(" ") { it.text }, lines.joinToString(" ") { it.displayText })
    }

    @Test
    fun devanagariWrappingNeverSplitsWordsOrMatras() {
        val devWords = listOf(
            "तीसरी", "सबसे", "बड़ी", "गलती", "निवेशकों", "को", "नुकसान", "देती", "है।"
        ).mapIndexed { i, text ->
            WordToken("d$i", text, i * 0.42, i * 0.42 + 0.36, 0.93f)
        }

        val lines = CaptionEngine.buildCaptionLines(devWords, clipStartSec = 0.0, clipEndSec = 6.0)
        assertTrue(lines.all { it.hasDevanagari })
        assertTrue(lines.all { it.wordCount <= 3 })
        assertTrue(lines.all { it.graphemeCount <= 42 })
        assertEquals(
            devWords.joinToString(" ") { it.text },
            lines.joinToString(" ") { it.displayText }
        )
    }

    @Test
    fun uncertainWordsRenderPlaceholderUnlessManuallyLocked() {
        val unlockedUncertain = WordToken("u1", "hallucinated", 0.0, 0.4, confidence = 0.42f, locked = false)
        val lockedEdit = unlockedUncertain.copy(text = "LiteRT", locked = true)

        assertEquals(ConfidenceLevel.UNCERTAIN, unlockedUncertain.confidenceLevel)
        assertEquals("___", unlockedUncertain.displayText)

        assertEquals(ConfidenceLevel.HIGH, lockedEdit.confidenceLevel)
        assertEquals("LiteRT", lockedEdit.displayText)
    }

    @Test
    fun verbatimHookStrictlyCappedAt10Words() {
        val words = (1..16).map { i ->
            WordToken("h$i", "token$i", i * 0.22, i * 0.22 + 0.18, 0.95f)
        }
        val hook = CaptionEngine.extractVerbatimHook(words, clipStartSec = 0.0, maxWords = 10)
        val count = hook.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        assertTrue("Expected hook <= 10 words, got $count", count <= 10)
        assertFalse(hook.isBlank())
    }
}

package com.clipai.v2.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowSelectorTest {

    private fun stubScore(total: Int) = ScoreBreakdown(
        hook = total,
        flow = total,
        value = total,
        audioVisual = total,
        total = total,
        hookSignals = listOf("Stub"),
        silenceFraction = 0.05f,
        faceVisibleFraction = 0.9f,
        infoDensityWps = 1.8f,
        energyRatio = 1.1f
    )

    private fun stubCandidate(id: String, start: Double, end: Double, total: Int) = ClipCandidate(
        id = id,
        rank = 0,
        startSec = start,
        endSec = end,
        scores = stubScore(total),
        verbatimHook = "Hook $id",
        titleHook = "Title $id"
    )

    @Test
    fun greedyDedupEnforcesStrict30SecondGapAndZeroOverlap() {
        val candidates = listOf(
            stubCandidate("c1", 10.0, 50.0, 94),   // Best -> kept (#1)
            stubCandidate("c2", 30.0, 70.0, 91),   // Overlaps c1 -> rejected
            stubCandidate("c3", 68.0, 108.0, 89),  // Gap = 18s < 30s -> rejected
            stubCandidate("c4", 80.0, 120.0, 85),  // Gap = 30s == 30s -> kept (#2)
            stubCandidate("c5", 160.0, 200.0, 80)  // Gap = 40s > 30s -> kept (#3)
        )

        val result = WindowSelector.selectGreedyDedup(candidates, maxClips = 5, minGapSec = 30.0)
        assertEquals(listOf("c1", "c4", "c5"), result.selected.map { it.id })
        assertEquals(listOf(1, 2, 3), result.selected.map { it.rank })

        // Verify zero overlaps and >= 30s gap across all selected pairs
        val sortedSelected = result.selected.sortedBy { it.startSec }
        for (i in 0 until sortedSelected.lastIndex) {
            val gap = sortedSelected[i + 1].startSec - sortedSelected[i].endSec
            assertTrue("Expected gap >= 30.0s, got $gap", gap >= 30.0)
        }
    }

    @Test
    fun greedyDedupRespectsMaxClipsCap() {
        val candidates = (0 until 8).map { i ->
            val start = i * 90.0
            stubCandidate("c$i", start, start + 35.0, 95 - i)
        }
        val result = WindowSelector.selectGreedyDedup(candidates, maxClips = 3, minGapSec = 30.0)
        assertEquals(3, result.selected.size)
        assertEquals(8, result.allAnnotated.size)
    }

    @Test
    fun buildSentencesSnapsAtPunctuationAndLongPauses() {
        val words = listOf(
            WordToken("1", "First", 0.0, 0.3, 0.95f),
            WordToken("2", "sentence.", 0.35, 0.8, 0.95f),
            WordToken("3", "Second", 1.0, 1.4, 0.95f),
            WordToken("4", "clause", 1.45, 1.9, 0.95f),
            // 0.7s pause without punctuation -> must split
            WordToken("5", "Third", 2.6, 3.0, 0.95f),
            WordToken("6", "part", 3.05, 3.4, 0.95f)
        )
        val sentences = WindowSelector.buildSentences(words, pauseThresholdSec = 0.45)
        assertEquals(3, sentences.size)
        assertEquals("First sentence.", sentences[0].text)
        assertEquals("Second clause", sentences[1].text)
        assertEquals("Third part", sentences[2].text)
    }
}

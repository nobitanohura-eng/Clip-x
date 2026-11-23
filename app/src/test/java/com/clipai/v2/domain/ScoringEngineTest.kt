package com.clipai.v2.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringEngineTest {

    @Test
    fun scoringWeightsSumStrictlyToOne() {
        assertEquals(1.0, ScoringEngine.totalWeights(), 1e-6)
    }

    @Test
    fun questionNumberAndBoldClaimOutscoreFillerOpening() {
        val strongWords = listOf(
            WordToken("w1", "Why", 0.0, 0.35, 0.96f),
            WordToken("w2", "do", 0.36, 0.60, 0.95f),
            WordToken("w3", "3", 0.62, 0.88, 0.98f),
            WordToken("w4", "mistakes", 0.90, 1.45, 0.94f),
            WordToken("w5", "never", 1.50, 1.95, 0.94f),
            WordToken("w6", "stop?", 2.00, 2.55, 0.97f),
            WordToken("w7", "Save", 3.20, 3.60, 0.95f),
            WordToken("w8", "real", 3.65, 4.00, 0.95f),
            WordToken("w9", "battery", 4.05, 4.60, 0.95f),
            WordToken("w10", "offline.", 4.65, 5.20, 0.95f)
        )
        val flatWords = listOf(
            WordToken("f1", "Well", 0.0, 0.45, 0.92f),
            WordToken("f2", "um", 0.50, 0.95, 0.90f),
            WordToken("f3", "basically", 1.00, 1.60, 0.90f),
            WordToken("f4", "today", 1.70, 2.20, 0.91f),
            WordToken("f5", "we", 2.30, 2.65, 0.91f),
            WordToken("f6", "talk.", 2.70, 3.10, 0.92f)
        )
        val rms = listOf(
            RmsSample(0.5, 0.42f, true),
            RmsSample(1.5, 0.40f, true),
            RmsSample(2.5, 0.38f, true),
            RmsSample(4.0, 0.24f, true)
        )
        val faces = listOf(FaceSample(1.0, 0.5f, 0.94f), FaceSample(3.0, 0.5f, 0.92f))

        val strongWin = CandidateWindow(
            startSec = 0.0,
            endSec = 36.0,
            sentences = WindowSelector.buildSentences(strongWords),
            cleanStartPause = true,
            cleanEndPause = true
        )
        val flatWin = CandidateWindow(
            startSec = 0.0,
            endSec = 36.0,
            sentences = WindowSelector.buildSentences(flatWords),
            cleanStartPause = false,
            cleanEndPause = false
        )

        val strongScore = ScoringEngine.scoreWindow(strongWin, strongWords, rms, faces)
        val flatScore = ScoringEngine.scoreWindow(flatWin, flatWords, rms, faces)

        assertTrue("Strong hook (${strongScore.hook}) must beat flat opening (${flatScore.hook})", strongScore.hook > flatScore.hook + 20)
        assertTrue("Strong total (${strongScore.total}) must beat flat total (${flatScore.total})", strongScore.total > flatScore.total + 15)
        assertTrue(strongScore.hookSignals.any { it.contains("Question", ignoreCase = true) })
        assertTrue(strongScore.hookSignals.any { it.contains("number", ignoreCase = true) })
    }

    @Test
    fun devanagariHookDetectsQuestionAndNumber() {
        val hindiWords = listOf(
            WordToken("h1", "क्या", 0.0, 0.4, 0.95f),
            WordToken("h2", "ये", 0.45, 0.7, 0.95f),
            WordToken("h3", "3", 0.75, 1.0, 0.98f),
            WordToken("h4", "बड़ी", 1.05, 1.45, 0.95f),
            WordToken("h5", "गलतियाँ", 1.50, 2.05, 0.94f),
            WordToken("h6", "हैं?", 2.10, 2.50, 0.95f)
        )
        val win = CandidateWindow(
            startSec = 0.0,
            endSec = 35.0,
            sentences = WindowSelector.buildSentences(hindiWords),
            cleanStartPause = true,
            cleanEndPause = true
        )

        val breakdown = ScoringEngine.scoreWindow(win, hindiWords, emptyList(), emptyList())
        assertTrue("Hindi hook should score >= 80, was ${breakdown.hook}", breakdown.hook >= 80)
        assertTrue(breakdown.hookSignals.any { it.contains("Question") })
        assertTrue(breakdown.hookSignals.any { it.contains("Bold claim") })
    }

    @Test
    fun excessiveSilencePenalizesFlowScore() {
        val words = listOf(
            WordToken("w1", "Start", 0.0, 0.4, 0.95f),
            WordToken("w2", "end.", 34.0, 34.5, 0.95f)
        )
        val silentRms = (0..35).map { sec ->
            RmsSample(sec.toDouble(), if (sec < 5) 0.30f else 0.01f, isSpeech = sec < 5)
        }
        val activeRms = (0..35).map { sec ->
            RmsSample(sec.toDouble(), 0.30f, isSpeech = true)
        }
        val win = CandidateWindow(0.0, 35.0, WindowSelector.buildSentences(words), true, true)

        val silentScore = ScoringEngine.scoreWindow(win, words, silentRms, emptyList())
        val activeScore = ScoringEngine.scoreWindow(win, words, activeRms, emptyList())

        assertTrue("Silent clip flow (${silentScore.flow}) must be penalized vs active (${activeScore.flow})", silentScore.flow < activeScore.flow - 20)
    }
}

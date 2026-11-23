package com.clipai.v2.domain

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Deterministic, explainable rule-based scoring engine.
 * Weights strictly sum to 1.0:
 *   Hook 35% + Flow 25% + Value 25% + Audio-Visual 15% = 100%.
 * Trend scoring is intentionally omitted offline.
 */
object ScoringEngine {
    const val WEIGHT_HOOK = 0.35
    const val WEIGHT_FLOW = 0.25
    const val WEIGHT_VALUE = 0.25
    const val WEIGHT_AUDIO_VISUAL = 0.15

    private val FILLER_WORDS = setOf(
        "um", "uh", "like", "basically", "literally", "actually",
        "so", "well", "hmm", "ah", "er", "matlab", "toh"
    )

    private val HOOK_QUESTIONS = setOf(
        "why", "how", "what", "when", "where", "who", "did", "do", "does", "can", "is", "are",
        "क्या", "कैसे", "क्यों", "कब"
    )

    private val HOOK_BOLD_CLAIMS = setOf(
        "never", "stop", "mistake", "mistakes", "secret", "truth", "wrong", "broken",
        "fails", "fail", "myth", "rule", "best", "worst", "nobody", "everyone",
        "गलती", "गलतियाँ", "सच", "बंद", "सीखो", "बर्बाद"
    )

    private val HOOK_CONTRAST = setOf(
        "but", "instead", "however", "except", "unlike", "versus", "vs",
        "लेकिन", "मगर", "बल्कि"
    )

    private val VALUE_WORDS = setOf(
        "real", "honest", "fast", "local", "free", "save", "money", "time",
        "battery", "memory", "phone", "privacy", "offline", "proven", "step",
        "exact", "simple", "critical", "risk", "compound",
        "पैसा", "बचत", "निवेश", "आसान", "सीधा", "फायदा", "नुकसान"
    )

    private val NUMBER_PATTERN = Regex("\\d+|\\b(three|five|ten|zero)\\b|तीन|पाँच|दो", RegexOption.IGNORE_CASE)
    private val HOW_TO_PATTERN = Regex("\\bhow to\\b|कैसे करें|सीखो", RegexOption.IGNORE_CASE)
    private val EDGE_PUNCT = Regex("^[.,!?;:\"'()\\[\\]।]+|[.,!?;:\"'()\\[\\]।]+$")

    fun totalWeights(): Double = WEIGHT_HOOK + WEIGHT_FLOW + WEIGHT_VALUE + WEIGHT_AUDIO_VISUAL

    fun cleanToken(raw: String): String = raw.lowercase().replace(EDGE_PUNCT, "").trim()

    fun scoreWindow(
        window: CandidateWindow,
        allWords: List<WordToken>,
        rmsSamples: List<RmsSample>,
        faceSamples: List<FaceSample>
    ): ScoreBreakdown {
        val duration = (window.endSec - window.startSec).coerceAtLeast(1.0)
        val clipWords = allWords.filter { it.startSec >= window.startSec - 0.05 && it.endSec <= window.endSec + 0.05 }
        val hookWords = clipWords.filter { it.startSec <= window.startSec + 3.2 }
        val hookText = hookWords.joinToString(" ") { it.text }

        // 1. HOOK (35%)
        var hookScore = 44
        val hookSignals = mutableListOf<String>()

        val hasQuestion = hookText.contains("?") || hookWords.any { cleanToken(it.text) in HOOK_QUESTIONS }
        if (hasQuestion) {
            hookScore += 18
            hookSignals += "Question in first 3s"
        }

        if (NUMBER_PATTERN.containsMatchIn(hookText)) {
            hookScore += 14
            hookSignals += "Specific number in opening"
        }

        if (hookWords.any { cleanToken(it.text) in HOOK_BOLD_CLAIMS }) {
            hookScore += 15
            hookSignals += "Bold claim / pattern interrupt"
        }

        if (HOW_TO_PATTERN.containsMatchIn(hookText)) {
            hookScore += 12
            hookSignals += "Actionable how-to framing"
        }

        if (hookWords.any { cleanToken(it.text) in HOOK_CONTRAST }) {
            hookScore += 10
            hookSignals += "Contrast pivot"
        }

        val globalAvgRms = if (rmsSamples.isNotEmpty()) {
            rmsSamples.map { it.rms.toDouble() }.average().toFloat()
        } else {
            0.25f
        }

        val hookRmsPoints = rmsSamples.filter { it.timeSec in window.startSec..(window.startSec + 3.2) }
        val hookAvgRms = if (hookRmsPoints.isNotEmpty()) {
            hookRmsPoints.map { it.rms.toDouble() }.average().toFloat()
        } else {
            globalAvgRms
        }

        if (globalAvgRms > 0f && hookAvgRms > globalAvgRms * 1.08f) {
            hookScore += 8
            hookSignals += "Elevated opening vocal energy"
        }

        if (hookSignals.isEmpty()) {
            hookSignals += "Standard spoken opening"
        }
        hookScore = hookScore.coerceIn(20, 99)

        // 2. FLOW (25%)
        val clipRms = rmsSamples.filter { it.timeSec in window.startSec..window.endSec }
        val silenceFraction = if (clipRms.isNotEmpty()) {
            clipRms.count { !it.isSpeech }.toFloat() / clipRms.size.toFloat()
        } else {
            0.08f
        }

        var flowScore = 62
        if (window.cleanStartPause) flowScore += 14
        if (window.cleanEndPause) flowScore += 14
        if (duration in 32.0..54.0) flowScore += 8
        if (silenceFraction > 0.22f) {
            flowScore -= ((silenceFraction - 0.22f) * 110f).roundToInt()
        } else if (silenceFraction < 0.14f) {
            flowScore += 5
        }
        flowScore = flowScore.coerceIn(25, 98)

        // 3. VALUE (25%)
        val contentWords = clipWords.filter {
            val cleaned = cleanToken(it.text)
            cleaned.length > 2 && cleaned !in FILLER_WORDS
        }
        val fillerCount = clipWords.count { cleanToken(it.text) in FILLER_WORDS }
        val infoDensityWps = (contentWords.size / duration).toFloat()
        val valueHits = clipWords.count { cleanToken(it.text) in VALUE_WORDS }
        val uniqueRatio = if (contentWords.isNotEmpty()) {
            contentWords.map { cleanToken(it.text) }.toSet().size.toFloat() / contentWords.size.toFloat()
        } else {
            0.5f
        }

        var valueScore = 46
        valueScore += when {
            infoDensityWps in 1.2f..2.6f -> 22
            infoDensityWps >= 0.85f -> 12
            else -> 0
        }
        valueScore += (valueHits * 4).coerceAtMost(18)
        valueScore += (uniqueRatio * 14f).roundToInt()
        valueScore -= (fillerCount * 2).coerceAtMost(16)
        valueScore = valueScore.coerceIn(25, 98)

        // 4. AUDIO-VISUAL (15%)
        val clipAvgRms = if (clipRms.isNotEmpty()) {
            clipRms.map { it.rms.toDouble() }.average().toFloat()
        } else {
            globalAvgRms
        }
        val energyRatio = if (globalAvgRms > 0.0001f) clipAvgRms / globalAvgRms else 1.0f
        val rmsVariance = if (clipRms.size > 1) {
            clipRms.map { (it.rms - clipAvgRms).toDouble().pow(2.0) }.average().toFloat()
        } else {
            0.01f
        }

        val clipFaces = faceSamples.filter { it.timeSec in window.startSec..window.endSec }
        val faceVisibleFraction = if (clipFaces.isNotEmpty()) {
            clipFaces.count { it.confidence >= 0.55f }.toFloat() / clipFaces.size.toFloat()
        } else {
            0.80f
        }

        var avScore = 48
        avScore += (faceVisibleFraction * 26f).roundToInt()
        if (energyRatio >= 1.0f) {
            avScore += ((energyRatio - 0.9f) * 30f).roundToInt().coerceAtMost(14)
        }
        if (rmsVariance > 0.004f) avScore += 8
        if (silenceFraction > 0.25f) avScore -= 14
        avScore = avScore.coerceIn(25, 99)

        val weightedTotal = (
            hookScore * WEIGHT_HOOK +
                flowScore * WEIGHT_FLOW +
                valueScore * WEIGHT_VALUE +
                avScore * WEIGHT_AUDIO_VISUAL
            ).roundToInt()

        return ScoreBreakdown(
            hook = hookScore,
            flow = flowScore,
            value = valueScore,
            audioVisual = avScore,
            total = weightedTotal,
            hookSignals = hookSignals,
            silenceFraction = silenceFraction,
            faceVisibleFraction = faceVisibleFraction,
            infoDensityWps = infoDensityWps,
            energyRatio = energyRatio
        )
    }
}

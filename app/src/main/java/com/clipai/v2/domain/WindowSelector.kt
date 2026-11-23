package com.clipai.v2.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CandidateWindow(
    val startSec: Double,
    val endSec: Double,
    val sentences: List<SentenceSegment>,
    val cleanStartPause: Boolean,
    val cleanEndPause: Boolean
)

data class DedupSelectionResult(
    val selected: List<ClipCandidate>,
    val allAnnotated: List<ClipCandidate>
)

/**
 * Sentence snapping, candidate window generation, and greedy deduplication.
 * Enforces the 30-second non-overlap rule strictly.
 */
object WindowSelector {
    private val SENTENCE_END_REGEX = Regex("[.!?।]+$")

    fun buildSentences(
        words: List<WordToken>,
        pauseThresholdSec: Double = 0.45
    ): List<SentenceSegment> {
        if (words.isEmpty()) return emptyList()
        val sorted = words.sortedBy { it.startSec }
        val segments = mutableListOf<SentenceSegment>()
        val bucket = mutableListOf<WordToken>()

        for (i in sorted.indices) {
            val w = sorted[i]
            val next = sorted.getOrNull(i + 1)
            bucket.add(w)

            val pauseAfter = if (next != null) max(0.0, next.startSec - w.endSec) else 1.0
            val hasPunct = SENTENCE_END_REGEX.containsMatchIn(w.text)
            val isLongPause = pauseAfter >= pauseThresholdSec
            val isOverLong = bucket.size >= 22 && pauseAfter >= 0.20

            if (hasPunct || isLongPause || isOverLong || next == null) {
                segments.add(
                    SentenceSegment(
                        id = "seg-${segments.size + 1}",
                        startSec = bucket.first().startSec,
                        endSec = bucket.last().endSec,
                        words = bucket.toList(),
                        pauseAfterSec = pauseAfter
                    )
                )
                bucket.clear()
            }
        }
        return segments
    }

    fun generateCandidateWindows(
        sentences: List<SentenceSegment>,
        sceneCuts: List<SceneCut>,
        sourceDurationSec: Double,
        minDurSec: Int = 30,
        maxDurSec: Int = 60,
        stepSec: Int = 12
    ): List<CandidateWindow> {
        if (sentences.isEmpty()) return emptyList()

        val effMin = if (sourceDurationSec < minDurSec + 4.0) {
            max(8.0, floor(sourceDurationSec * 0.45))
        } else {
            minDurSec.toDouble()
        }
        val effMax = min(maxDurSec.toDouble(), max(effMin + 5.0, sourceDurationSec))
        val effStep = if (sourceDurationSec < 45.0) max(4.0, stepSec / 2.0) else stepSec.toDouble()

        val windows = mutableListOf<CandidateWindow>()
        val seenKeys = mutableSetOf<String>()
        var nextAllowedStart = 0.0

        val targetDurs = listOf(
            ((effMin + effMax) * 0.5),
            effMin + 4.0,
            min(effMax, effMin + 22.0)
        )

        for (i in sentences.indices) {
            val startSeg = sentences[i]
            if (startSeg.startSec < nextAllowedStart) continue

            for (targetDur in targetDurs) {
                var bestEndIdx = -1
                var bestDist = Double.MAX_VALUE

                for (j in i until sentences.size) {
                    val dur = sentences[j].endSec - startSeg.startSec
                    if (dur in effMin..effMax) {
                        val endTime = sentences[j].endSec
                        val collidesWithCut = sceneCuts.any { abs(it.timeSec - endTime) < 0.35 }
                        val dist = abs(dur - targetDur) + if (collidesWithCut) 6.0 else 0.0
                        if (dist < bestDist) {
                            bestDist = dist
                            bestEndIdx = j
                        }
                    }
                    if (dur > effMax) break
                }

                if (bestEndIdx >= i) {
                    val slice = sentences.subList(i, bestEndIdx + 1)
                    val start = slice.first().startSec
                    val end = slice.last().endSec
                    val key = "${(start * 10).roundToInt()}-${(end * 10).roundToInt()}"
                    if (seenKeys.add(key)) {
                        val prev = sentences.getOrNull(i - 1)
                        val startGap = if (prev != null) start - prev.endSec else 1.0
                        val endGap = slice.last().pauseAfterSec
                        windows.add(
                            CandidateWindow(
                                startSec = start,
                                endSec = end,
                                sentences = slice.toList(),
                                cleanStartPause = startGap >= 0.30 || i == 0,
                                cleanEndPause = endGap >= 0.35 || bestEndIdx == sentences.lastIndex
                            )
                        )
                    }
                }
            }

            nextAllowedStart = startSeg.startSec + effStep
        }

        if (windows.isEmpty()) {
            windows.add(
                CandidateWindow(
                    startSec = sentences.first().startSec,
                    endSec = sentences.last().endSec,
                    sentences = sentences,
                    cleanStartPause = true,
                    cleanEndPause = true
                )
            )
        }

        return windows
    }

    /**
     * Greedy deduplication: sort candidates best-first, keep the highest-scoring clip,
     * discard any candidate overlapping it OR within [minGapSec] seconds of it,
     * and repeat until [maxClips] clips are selected.
     */
    fun selectGreedyDedup(
        candidates: List<ClipCandidate>,
        maxClips: Int = 5,
        minGapSec: Double = 30.0
    ): DedupSelectionResult {
        val sorted = candidates.sortedWith(
            compareByDescending<ClipCandidate> { it.scores.total }
                .thenBy { it.startSec }
        )

        val kept = mutableListOf<ClipCandidate>()
        val annotated = mutableListOf<ClipCandidate>()

        for (candidate in sorted) {
            var blockingClip: ClipCandidate? = null
            var blockingGap = 0

            for (existing in kept) {
                val overlaps = candidate.startSec <= existing.endSec && candidate.endSec >= existing.startSec
                val gap = if (overlaps) {
                    0.0
                } else {
                    min(
                        abs(candidate.startSec - existing.endSec),
                        abs(existing.startSec - candidate.endSec)
                    )
                }
                if (overlaps || gap < minGapSec) {
                    blockingClip = existing
                    blockingGap = gap.roundToInt()
                    break
                }
            }

            if (blockingClip == null && kept.size < maxClips) {
                val accepted = candidate.copy(
                    rank = kept.size + 1,
                    selected = true,
                    dedupedByClipId = null,
                    dedupGapSec = null
                )
                kept.add(accepted)
                annotated.add(accepted)
            } else {
                annotated.add(
                    candidate.copy(
                        rank = 0,
                        selected = false,
                        dedupedByClipId = blockingClip?.id ?: "limit-reached",
                        dedupGapSec = if (blockingClip != null) blockingGap else null
                    )
                )
            }
        }

        return DedupSelectionResult(selected = kept, allAnnotated = annotated)
    }
}

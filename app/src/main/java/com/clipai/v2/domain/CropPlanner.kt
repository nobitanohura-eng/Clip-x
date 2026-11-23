package com.clipai.v2.domain

import kotlin.math.abs

/**
 * Cut-aware BlazeFace crop planner:
 * - Exponential smoothing with micro-jitter deadband within continuous shots
 * - Instant snap across hard scene cuts so framing never drifts across angles
 * - Safe clamping of manual user X offset overrides
 */
object CropPlanner {
    const val MIN_CENTER_X = 0.15f
    const val MAX_CENTER_X = 0.85f
    const val DEFAULT_ALPHA = 0.22f
    const val DEFAULT_DEADBAND = 0.025f

    fun planSmoothedCrop(
        faceSamples: List<FaceSample>,
        sceneCuts: List<SceneCut>,
        clipStartSec: Double,
        clipEndSec: Double,
        alpha: Float = DEFAULT_ALPHA,
        deadband: Float = DEFAULT_DEADBAND
    ): List<CropKeyframe> {
        val relevant = faceSamples
            .filter { it.timeSec in (clipStartSec - 0.25)..(clipEndSec + 0.25) }
            .sortedBy { it.timeSec }

        if (relevant.isEmpty()) {
            return listOf(
                CropKeyframe(clipStartSec, 0.5f, 0f, isCutBoundary = true),
                CropKeyframe(clipEndSec, 0.5f, 0f, isCutBoundary = false)
            )
        }

        val keyframes = mutableListOf<CropKeyframe>()
        var smoothedX = relevant.first().centerX.coerceIn(MIN_CENTER_X, MAX_CENTER_X)

        for (i in relevant.indices) {
            val sample = relevant[i]
            val prev = relevant.getOrNull(i - 1)
            val crossedCut = prev != null &&
                sceneCuts.any { it.timeSec > prev.timeSec && it.timeSec <= sample.timeSec }

            if (i == 0 || crossedCut) {
                smoothedX = sample.centerX.coerceIn(MIN_CENTER_X, MAX_CENTER_X)
            } else if (sample.confidence >= 0.45f) {
                val delta = sample.centerX - smoothedX
                if (abs(delta) > deadband) {
                    smoothedX = (smoothedX + delta * alpha).coerceIn(MIN_CENTER_X, MAX_CENTER_X)
                }
            }

            keyframes.add(
                CropKeyframe(
                    timeSec = sample.timeSec,
                    centerX = smoothedX,
                    faceConfidence = sample.confidence,
                    isCutBoundary = crossedCut
                )
            )
        }

        return keyframes
    }

    fun resolveCenterXAt(
        keyframes: List<CropKeyframe>,
        timeSec: Double,
        manualOffsetX: Float = 0f
    ): Float {
        if (keyframes.isEmpty()) return (0.5f + manualOffsetX).coerceIn(MIN_CENTER_X, MAX_CENTER_X)
        if (timeSec <= keyframes.first().timeSec) {
            return (keyframes.first().centerX + manualOffsetX).coerceIn(MIN_CENTER_X, MAX_CENTER_X)
        }
        if (timeSec >= keyframes.last().timeSec) {
            return (keyframes.last().centerX + manualOffsetX).coerceIn(MIN_CENTER_X, MAX_CENTER_X)
        }

        for (i in 0 until keyframes.lastIndex) {
            val a = keyframes[i]
            val b = keyframes[i + 1]
            if (timeSec in a.timeSec..b.timeSec) {
                val base = if (b.isCutBoundary) {
                    a.centerX
                } else {
                    val span = (b.timeSec - a.timeSec).coerceAtLeast(0.001)
                    val ratio = ((timeSec - a.timeSec) / span).toFloat()
                    a.centerX + (b.centerX - a.centerX) * ratio
                }
                return (base + manualOffsetX).coerceIn(MIN_CENTER_X, MAX_CENTER_X)
            }
        }

        return (0.5f + manualOffsetX).coerceIn(MIN_CENTER_X, MAX_CENTER_X)
    }
}

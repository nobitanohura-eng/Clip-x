package com.clipai.v2.domain

/**
 * Post-export QA gate evaluator verifying output integrity before declaring export success.
 */
object QaGate {
    const val MIN_DURATION_SEC = 3.0
    const val MAX_DURATION_SEC = 180.0
    const val MIN_BITRATE_KBPS = 500
    const val TARGET_LUFS = -14.0f
    const val MAX_TRUE_PEAK_DBTP = -1.0f

    fun evaluate(
        durationSec: Double,
        width: Int,
        height: Int,
        fileSizeBytes: Long,
        thumbnailLumaMean: Float,
        measuredLufs: Float,
        measuredTruePeakDbtp: Float
    ): QaCheckResult {
        val durationOk = durationSec in MIN_DURATION_SEC..MAX_DURATION_SEC
        val resolutionOk = width >= 480 && height > width
        val bitrateKbps = if (durationSec > 0.1) {
            ((fileSizeBytes * 8.0) / durationSec / 1000.0).toInt()
        } else {
            0
        }
        val bitrateOk = bitrateKbps >= MIN_BITRATE_KBPS && fileSizeBytes > 0L
        val thumbnailOk = thumbnailLumaMean > 6.0f
        val loudnessOk = measuredLufs in -18.0f..-12.0f && measuredTruePeakDbtp <= MAX_TRUE_PEAK_DBTP

        val failures = buildList {
            if (!durationOk) add(String.format(java.util.Locale.ROOT, "Duration %.1fs outside 3–180s bounds", durationSec))
            if (!resolutionOk) add("Resolution ${width}x$height is not valid vertical 9:16")
            if (!bitrateOk) add("Bitrate ${bitrateKbps} kbps below minimum threshold")
            if (!thumbnailOk) add("Midpoint frame appears blank/black")
            if (!loudnessOk) add(String.format(java.util.Locale.ROOT, "Loudness %.1f LUFS / %.1f dBTP out of spec", measuredLufs, measuredTruePeakDbtp))
        }

        return QaCheckResult(
            durationSec = durationSec,
            durationOk = durationOk,
            width = width,
            height = height,
            resolutionOk = resolutionOk,
            bitrateKbps = bitrateKbps,
            bitrateOk = bitrateOk,
            thumbnailOk = thumbnailOk,
            loudnessLufs = measuredLufs,
            truePeakDbtp = measuredTruePeakDbtp,
            loudnessOk = loudnessOk,
            fileSizeBytes = fileSizeBytes,
            passed = failures.isEmpty(),
            failureSummary = failures.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        )
    }
}

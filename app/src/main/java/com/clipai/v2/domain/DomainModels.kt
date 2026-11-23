package com.clipai.v2.domain

enum class ConfidenceLevel {
    HIGH,
    REVIEW,
    UNCERTAIN
}

data class WordToken(
    val id: String,
    val text: String,
    val startSec: Double,
    val endSec: Double,
    val confidence: Float,
    val locked: Boolean = false
) {
    val confidenceLevel: ConfidenceLevel
        get() = when {
            locked || confidence >= 0.85f -> ConfidenceLevel.HIGH
            confidence >= 0.60f -> ConfidenceLevel.REVIEW
            else -> ConfidenceLevel.UNCERTAIN
        }

    val displayText: String
        get() = if (confidenceLevel == ConfidenceLevel.UNCERTAIN && !locked) "___" else text
}

data class SentenceSegment(
    val id: String,
    val startSec: Double,
    val endSec: Double,
    val words: List<WordToken>,
    val pauseAfterSec: Double
) {
    val text: String get() = words.joinToString(" ") { it.text }
}

data class SceneCut(
    val timeSec: Double,
    val delta: Float
)

data class RmsSample(
    val timeSec: Double,
    val rms: Float,
    val isSpeech: Boolean
)

data class FaceSample(
    val timeSec: Double,
    val centerX: Float, // 0f..1f normalized horizontal position
    val confidence: Float
)

data class CropKeyframe(
    val timeSec: Double,
    val centerX: Float,
    val faceConfidence: Float,
    val isCutBoundary: Boolean
)

data class CaptionLine(
    val id: String,
    val startSec: Double,
    val endSec: Double,
    val words: List<WordToken>,
    val displayText: String,
    val graphemeCount: Int,
    val hasDevanagari: Boolean
) {
    val wordCount: Int get() = words.size
}

data class ScoreBreakdown(
    val hook: Int,
    val flow: Int,
    val value: Int,
    val audioVisual: Int,
    val total: Int,
    val hookSignals: List<String>,
    val silenceFraction: Float,
    val faceVisibleFraction: Float,
    val infoDensityWps: Float,
    val energyRatio: Float,
    val llmDelta: Int? = null,
    val llmNote: String? = null
)

enum class HookMode {
    VERBATIM_QUOTE,
    MARKED_TITLE
}

enum class CropMode {
    SMART_FACE_TRACK,
    CENTER_CROP
}

enum class ExportPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val targetBitrateBps: Int
) {
    STANDARD_1080P("1080x1920 Standard (8–12 Mbps)", 1080, 1920, 9_500_000),
    FAST_720P("720x1280 Fast (4 GB phones)", 720, 1280, 4_500_000)
}

data class QaCheckResult(
    val durationSec: Double,
    val durationOk: Boolean,
    val width: Int,
    val height: Int,
    val resolutionOk: Boolean,
    val bitrateKbps: Int,
    val bitrateOk: Boolean,
    val thumbnailOk: Boolean,
    val loudnessLufs: Float,
    val truePeakDbtp: Float,
    val loudnessOk: Boolean,
    val fileSizeBytes: Long,
    val passed: Boolean,
    val failureSummary: String? = null
)

data class ClipCandidate(
    val id: String,
    val rank: Int,
    val startSec: Double,
    val endSec: Double,
    val scores: ScoreBreakdown,
    val verbatimHook: String,
    val titleHook: String,
    val hookMode: HookMode = HookMode.VERBATIM_QUOTE,
    val cropMode: CropMode = CropMode.SMART_FACE_TRACK,
    val manualCropOffsetX: Float = 0f,
    val cropKeyframes: List<CropKeyframe> = emptyList(),
    val selected: Boolean = false,
    val dedupedByClipId: String? = null,
    val dedupGapSec: Int? = null,
    val exportedFilePath: String? = null,
    val qaPassed: Boolean? = null
) {
    val durationSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)
}

data class VideoMetadata(
    val uriString: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val fileHashSha256: String,
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val fps: Float,
    val rotationDegrees: Int,
    val hasAudio: Boolean
)

enum class ModelCategory {
    ASR,
    VISION,
    LLM
}

enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    VERIFYING_SHA256,
    INSTALLED,
    FAILED
}

data class ModelDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val runtime: String,
    val category: ModelCategory,
    val sizeBytes: Long,
    val peakRamMb: Int,
    val minDeviceRamGb: Int,
    val speedNote: String,
    val role: String,
    val license: String,
    val downloadUrl: String,
    val fileName: String,
    val expectedSha256: String?, // Null only when user provides custom self-hosted mirror
    val gatedHuggingFace: Boolean = false
)

data class DeviceCapabilities(
    val totalRamGb: Int,
    val freeStorageGb: Double,
    val isWifiConnected: Boolean,
    val isCharging: Boolean,
    val batteryPercent: Int
)

data class FallbackPlan(
    val activeAsrModelId: String,
    val activeLlmModelId: String?,
    val recommendedPreset: ExportPreset,
    val llmDisabledReason: String?,
    val storageBlockedReason: String?,
    val batteryWarning: String?
)

enum class PipelineStage(val displayTitle: String) {
    PROBE("Probe source video"),
    AUDIO_VAD("Extract 16 kHz audio & VAD"),
    ASR("On-device speech recognition"),
    SENTENCES("Sentence boundary snapping"),
    SCENE_AND_FACE("Scene cuts & BlazeFace proxy"),
    WINDOW_SCORING("Window scoring & 30s dedup"),
    LLM_RERANK("Optional LLM top-10 re-rank"),
    CROP_PLAN("Cut-aware 9:16 crop plan")
}

enum class PipelineStageStatus {
    PENDING,
    RUNNING,
    DONE,
    SKIPPED,
    CANCELLED,
    ERROR
}

data class PipelineStageState(
    val stage: PipelineStage,
    val status: PipelineStageStatus = PipelineStageStatus.PENDING,
    val detail: String = "Queued",
    val progressPercent: Int? = null // Null represents honest indeterminate work (e.g. model load)
)

data class PipelineProgress(
    val isRunning: Boolean = false,
    val projectId: String? = null,
    val activeModelInRam: String? = null,
    val stages: List<PipelineStageState> = PipelineStage.entries.map { PipelineStageState(it) },
    val errorMessage: String? = null
)

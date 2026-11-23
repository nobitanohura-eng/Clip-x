package com.clipai.v2.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "projects",
    indices = [Index(value = ["fileHashSha256"])]
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val uriString: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val fileHashSha256: String,
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val fps: Float,
    val rotationDegrees: Int,
    val hasAudio: Boolean,
    val transcriptCached: Boolean,
    val llmReranked: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "words",
    indices = [Index(value = ["fileHashSha256"]), Index(value = ["projectId"])]
)
data class WordEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val fileHashSha256: String,
    val text: String,
    val startSec: Double,
    val endSec: Double,
    val confidence: Float,
    val locked: Boolean
)

@Entity(
    tableName = "clips",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["projectId"])]
)
data class ClipEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val rank: Int,
    val startSec: Double,
    val endSec: Double,
    val hookScore: Int,
    val flowScore: Int,
    val valueScore: Int,
    val audioVisualScore: Int,
    val totalScore: Int,
    val hookSignalsJoined: String,
    val silenceFraction: Float,
    val faceVisibleFraction: Float,
    val infoDensityWps: Float,
    val energyRatio: Float,
    val llmDelta: Int?,
    val llmNote: String?,
    val verbatimHook: String,
    val titleHook: String,
    val hookMode: String,
    val cropMode: String,
    val manualCropOffsetX: Float,
    val cropKeyframesCsv: String,
    val selected: Boolean,
    val dedupedByClipId: String?,
    val dedupGapSec: Int?,
    val exportedFilePath: String?,
    val qaPassed: Boolean?
)

@Entity(tableName = "model_states")
data class ModelStateEntity(
    @PrimaryKey val modelId: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val localFilePath: String?,
    val verifiedSha256: Boolean,
    val lastError: String?,
    val updatedAtMs: Long
)

@Entity(tableName = "pipeline_jobs")
data class PipelineJobEntity(
    @PrimaryKey val jobId: String,
    val projectId: String,
    val lastCompletedStage: String,
    val status: String,
    val updatedAtMs: Long
)

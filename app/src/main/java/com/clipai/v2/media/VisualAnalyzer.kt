package com.clipai.v2.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.clipai.v2.domain.FaceSample
import com.clipai.v2.domain.SceneCut
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.math.abs

data class VisualAnalysisResult(
    val sceneCuts: List<SceneCut>,
    val faceSamples: List<FaceSample>,
    val usedBlazeFaceModel: Boolean
)

/**
 * Extracts lightweight 480p proxy frames via MediaMetadataRetriever:
 * - Detects hard scene cuts via frame-to-frame L1 luminance/RGB delta
 * - Runs real MediaPipe BlazeFace Short-Range face detection when downloaded
 * - Releases native FaceDetector immediately in finally (RAM mutex)
 */
object VisualAnalyzer {

    suspend fun analyze(
        context: Context,
        uri: Uri,
        durationSec: Double,
        blazeFaceModelFile: File?,
        onProgress: (Int) -> Unit
    ): VisualAnalysisResult = withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        var faceDetector: FaceDetector? = null

        try {
            retriever.setDataSource(context, uri)

            if (blazeFaceModelFile != null && blazeFaceModelFile.exists() && blazeFaceModelFile.length() > 0L) {
                faceDetector = runCatching {
                    val mapped = RandomAccessFile(blazeFaceModelFile, "r").use { raf ->
                        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, blazeFaceModelFile.length())
                    }
                    val baseOptions = BaseOptions.builder()
                        .setModelAssetBuffer(mapped)
                        .build()
                    val options = FaceDetector.FaceDetectorOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.IMAGE)
                        .setMinDetectionConfidence(0.45f)
                        .build()
                    FaceDetector.createFromOptions(context, options)
                }.getOrNull()
            }

            // Adaptive sampling cadence (up to 120 proxy frames for responsiveness & battery safety)
            val stepSec = maxOf(0.5, durationSec / 120.0)
            val totalSteps = (durationSec / stepSec).toInt().coerceAtLeast(1)

            val sceneCuts = mutableListOf<SceneCut>()
            val faceSamples = mutableListOf<FaceSample>()
            var prevLumaGrid: IntArray? = null

            for (i in 0 until totalSteps) {
                currentCoroutineContext().ensureActive()
                val timeSec = (i * stepSec).coerceAtMost((durationSec - 0.1).coerceAtLeast(0.0))
                val timeUs = (timeSec * 1_000_000.0).toLong()

                val rawFrame = retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    480,
                    270
                ) ?: continue

                val argbFrame = if (rawFrame.config != Bitmap.Config.ARGB_8888) {
                    rawFrame.copy(Bitmap.Config.ARGB_8888, false).also { rawFrame.recycle() }
                } else {
                    rawFrame
                }

                try {
                    // 1. Scene-cut detection via compact 32x18 luminance grid
                    val grid = extractLumaGrid(argbFrame)
                    prevLumaGrid?.let { prev ->
                        var diff = 0L
                        for (idx in grid.indices) {
                            diff += abs(grid[idx] - prev[idx])
                        }
                        val normDelta = diff.toFloat() / (grid.size * 255f)
                        if (normDelta > 0.24f) {
                            sceneCuts.add(SceneCut(timeSec = timeSec, delta = normDelta))
                        }
                    }
                    prevLumaGrid = grid

                    // 2. BlazeFace subject tracking (or graceful center fallback if model not installed)
                    val detector = faceDetector
                    if (detector != null) {
                        val mpImage = BitmapImageBuilder(argbFrame).build()
                        val detectionResult = detector.detect(mpImage)
                        val primaryFace = detectionResult.detections()
                            .maxByOrNull { det ->
                                val box = det.boundingBox()
                                box.width() * box.height()
                            }

                        if (primaryFace != null) {
                            val box = primaryFace.boundingBox()
                            val normX = (box.centerX() / argbFrame.width.toFloat()).coerceIn(0.15f, 0.85f)
                            val conf = primaryFace.categories().firstOrNull()?.score() ?: 0.75f
                            faceSamples.add(FaceSample(timeSec, normX, conf))
                        } else {
                            faceSamples.add(FaceSample(timeSec, 0.5f, 0.2f))
                        }
                    } else {
                        faceSamples.add(FaceSample(timeSec, 0.5f, 0.5f))
                    }
                } finally {
                    argbFrame.recycle()
                }

                onProgress((((i + 1).toFloat() / totalSteps.toFloat()) * 100f).toInt().coerceIn(0, 100))
            }

            VisualAnalysisResult(
                sceneCuts = sceneCuts,
                faceSamples = faceSamples,
                usedBlazeFaceModel = faceDetector != null
            )
        } finally {
            runCatching { faceDetector?.close() }
            runCatching { retriever.release() }
        }
    }

    private fun extractLumaGrid(bitmap: Bitmap): IntArray {
        val cols = 32
        val rows = 18
        val out = IntArray(cols * rows)
        val stepX = (bitmap.width / cols).coerceAtLeast(1)
        val stepY = (bitmap.height / rows).coerceAtLeast(1)
        var ptr = 0
        for (y in 0 until rows) {
            val py = (y * stepY).coerceAtMost(bitmap.height - 1)
            for (x in 0 until cols) {
                val px = (x * stepX).coerceAtMost(bitmap.width - 1)
                val pixel = bitmap.getPixel(px, py)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                out[ptr++] = (r * 77 + g * 150 + b * 29) shr 8
            }
        }
        return out
    }
}

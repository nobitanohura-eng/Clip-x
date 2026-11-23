package com.clipai.v2.data.models

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.StatFs
import com.clipai.v2.domain.DeviceCapabilities
import com.clipai.v2.domain.FallbackLadder
import com.clipai.v2.domain.ModelCategory
import com.clipai.v2.domain.ModelDescriptor
import java.io.File
import kotlin.math.roundToInt

/**
 * Authoritative on-device model manifest (never bundled inside the APK).
 * All models download on demand into filesDir/models/<model-id>/<version>/.
 */
object ModelCatalog {
    val models: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = FallbackLadder.MODEL_WHISPER_TINY,
            name = "Whisper Tiny (INT8)",
            version = "v1-int8",
            runtime = "ONNX Runtime INT8",
            category = ModelCategory.ASR,
            sizeBytes = 44L * 1024L * 1024L,
            peakRamMb = 150,
            minDeviceRamGb = 4,
            speedNote = "Multilingual · Word timestamps · Default for all phones",
            role = "Default on-device ASR",
            license = "MIT",
            downloadUrl = "https://huggingface.co/onnx-community/whisper-tiny/resolve/main/onnx/encoder_model_quantized.onnx",
            fileName = "whisper-tiny-int8.onnx",
            expectedSha256 = null,
            gatedHuggingFace = false
        ),
        ModelDescriptor(
            id = FallbackLadder.MODEL_WHISPER_BASE,
            name = "Whisper Base (INT8)",
            version = "v1-int8",
            runtime = "ONNX Runtime INT8",
            category = ModelCategory.ASR,
            sizeBytes = 118L * 1024L * 1024L,
            peakRamMb = 250,
            minDeviceRamGb = 8,
            speedNote = "Higher multilingual accuracy · 8 GB+ flagship opt-in",
            role = "Opt-in high-accuracy ASR",
            license = "MIT",
            downloadUrl = "https://huggingface.co/onnx-community/whisper-base/resolve/main/onnx/encoder_model_quantized.onnx",
            fileName = "whisper-base-int8.onnx",
            expectedSha256 = null,
            gatedHuggingFace = false
        ),
        ModelDescriptor(
            id = FallbackLadder.MODEL_MOONSHINE_TINY,
            name = "Moonshine Tiny",
            version = "v1-int8",
            runtime = "ONNX Runtime INT8",
            category = ModelCategory.ASR,
            sizeBytes = 26L * 1024L * 1024L,
            peakRamMb = 110,
            minDeviceRamGb = 4,
            speedNote = "Fast low-compute English-first fallback",
            role = "Fast low-power ASR fallback",
            license = "MIT",
            downloadUrl = "https://huggingface.co/UsefulSensors/moonshine/resolve/main/onnx/tiny/encode.onnx",
            fileName = "moonshine-tiny.onnx",
            expectedSha256 = null,
            gatedHuggingFace = false
        ),
        ModelDescriptor(
            id = FallbackLadder.MODEL_BLAZEFACE,
            name = "BlazeFace Short-Range",
            version = "v1-fp16",
            runtime = "MediaPipe Tasks Vision",
            category = ModelCategory.VISION,
            sizeBytes = 230L * 1024L,
            peakRamMb = 45,
            minDeviceRamGb = 4,
            speedNote = "5 fps on 480p proxy · Cut-aware smoothed 9:16 crop",
            role = "Smart face-tracking crop planner",
            license = "Apache 2.0",
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite",
            fileName = "blaze_face_short_range.tflite",
            expectedSha256 = null,
            gatedHuggingFace = false
        ),
        ModelDescriptor(
            id = FallbackLadder.MODEL_GEMMA_4_E2B,
            name = "Gemma 4 E2B (INT4 .litertlm)",
            version = "e2b-int4",
            runtime = "LiteRT-LM 0.18.0",
            category = ModelCategory.LLM,
            sizeBytes = 2_580L * 1024L * 1024L,
            peakRamMb = 1450,
            minDeviceRamGb = 8,
            speedNote = "Top-10 candidate re-ranking & title refinement (GPU -> CPU)",
            role = "Optional flagship on-device LLM",
            license = "Apache 2.0 (HF terms gated)",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-e2b-it.litertlm",
            expectedSha256 = null,
            gatedHuggingFace = true
        ),
        ModelDescriptor(
            id = FallbackLadder.MODEL_GEMMA_3_1B,
            name = "Gemma 3 1B (INT4 .litertlm)",
            version = "1b-int4",
            runtime = "LiteRT-LM 0.18.0",
            category = ModelCategory.LLM,
            sizeBytes = 780L * 1024L * 1024L,
            peakRamMb = 1050,
            minDeviceRamGb = 6,
            speedNote = "Short titles & hook rewrites on 6 GB mid-range phones",
            role = "Optional low-RAM LLM fallback",
            license = "Gemma Terms (HF gated)",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            fileName = "gemma-3-1b-int4.litertlm",
            expectedSha256 = null,
            gatedHuggingFace = true
        )
    )

    fun findById(modelId: String): ModelDescriptor? = models.firstOrNull { it.id == modelId }

    fun targetFile(context: Context, descriptor: ModelDescriptor): File {
        val dir = File(context.filesDir, "models/${descriptor.id}/${descriptor.version}")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, descriptor.fileName)
    }

    fun partFile(context: Context, descriptor: ModelDescriptor): File {
        val finalFile = targetFile(context, descriptor)
        return File(finalFile.parentFile, "${finalFile.name}.part")
    }
}

/**
 * Queries genuine Android device hardware telemetry (never hardcoded or mocked).
 */
object DeviceInspector {
    fun inspect(context: Context): DeviceCapabilities {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val ramGb = (memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)).roundToInt().coerceAtLeast(2)

        val stat = StatFs(context.filesDir.absolutePath)
        val freeStorageGb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netCaps = cm.getNetworkCapabilities(cm.activeNetwork)
        val isWifi = netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt() else 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return DeviceCapabilities(
            totalRamGb = ramGb,
            freeStorageGb = freeStorageGb,
            isWifiConnected = isWifi,
            isCharging = charging,
            batteryPercent = pct
        )
    }
}

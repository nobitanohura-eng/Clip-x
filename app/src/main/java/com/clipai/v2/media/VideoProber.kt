package com.clipai.v2.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.clipai.v2.domain.VideoMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Probes local SAF video URIs directly without copying source media wholesale.
 * Computes a fast deterministic SHA-256 fingerprint from metadata + head/tail 256 KB
 * so re-clipping the same video hits the Room transcript cache immediately.
 */
object VideoProber {
    private const val HASH_WINDOW_BYTES = 256 * 1024

    suspend fun probe(context: Context, uri: Uri): VideoMetadata = withContext(Dispatchers.IO) {
        var displayName = "video_${System.currentTimeMillis()}.mp4"
        var fileSize = 0L

        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) cursor.getString(nameIdx)?.let { displayName = it }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) fileSize = cursor.getLong(sizeIdx)
            }
        }

        val sha256 = computeFastUriSha256(context, uri, displayName, fileSize)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 1920
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 1080
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull() ?: 30f

            val (effWidth, effHeight) = if (rotation == 90 || rotation == 270) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }

            if (durationMs <= 0L) {
                throw IllegalArgumentException("Selected file has zero or unreadable video duration.")
            }

            VideoMetadata(
                uriString = uri.toString(),
                fileName = displayName,
                fileSizeBytes = fileSize,
                fileHashSha256 = sha256,
                durationSec = durationMs / 1000.0,
                width = effWidth,
                height = effHeight,
                fps = captureFps.coerceIn(15f, 120f),
                rotationDegrees = rotation,
                hasAudio = hasAudio
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun computeFastUriSha256(
        context: Context,
        uri: Uri,
        fileName: String,
        fileSize: Long
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$fileName:$fileSize".toByteArray())

        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            val len = if (afd.length > 0) afd.length else fileSize
            afd.createInputStream().use { input ->
                val headBuf = ByteArray(HASH_WINDOW_BYTES)
                val headRead = input.read(headBuf)
                if (headRead > 0) digest.update(headBuf, 0, headRead)

                if (len > HASH_WINDOW_BYTES * 2L) {
                    val toSkip = len - HASH_WINDOW_BYTES - headRead
                    if (toSkip > 0) {
                        input.skip(toSkip)
                        val tailBuf = ByteArray(HASH_WINDOW_BYTES)
                        val tailRead = input.read(tailBuf)
                        if (tailRead > 0) digest.update(tailBuf, 0, tailRead)
                    }
                }
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

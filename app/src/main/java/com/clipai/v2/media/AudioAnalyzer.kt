package com.clipai.v2.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.clipai.v2.domain.RmsSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.sqrt

data class AudioAnalysisResult(
    val rmsSamples: List<RmsSample>,
    val averageRms: Float,
    val sampleRateHz: Int
)

/**
 * Decodes real audio track from local SAF URI via MediaExtractor + MediaCodec PCM,
 * downmixes to mono energy frames (0.5s windows), and computes adaptive VAD speech/silence flags.
 */
object AudioAnalyzer {
    private const val TIMEOUT_US = 10_000L

    suspend fun analyze(
        context: Context,
        uri: Uri,
        durationSec: Double,
        onProgress: (Int) -> Unit
    ): AudioAnalysisResult = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = fmt
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return@withContext AudioAnalysisResult(emptyList(), 0f, 16000)
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)
                ?: return@withContext AudioAnalysisResult(emptyList(), 0f, 16000)
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val windowSamples = (sampleRate * 0.5).toInt().coerceAtLeast(1)
            val rawWindows = mutableListOf<Pair<Double, Float>>()

            var sumSq = 0.0
            var countInWindow = 0
            var windowIndex = 0
            var sawInputEos = false
            var sawOutputEos = false
            val bufferInfo = MediaCodec.BufferInfo()
            var lastReportedPct = -1

            while (!sawOutputEos) {
                currentCoroutineContext().ensureActive()

                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                val ptsUs = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                                extractor.advance()

                                if (durationSec > 1.0 && ptsUs > 0L) {
                                    val pct = ((ptsUs / 1_000_000.0 / durationSec) * 100.0).toInt().coerceIn(0, 99)
                                    if (pct != lastReportedPct && pct % 4 == 0) {
                                        lastReportedPct = pct
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frameCount = shortBuf.remaining() / channels

                        for (f in 0 until frameCount) {
                            var mono = 0f
                            for (c in 0 until channels) {
                                mono += shortBuf.get() / 32768f
                            }
                            mono /= channels
                            sumSq += (mono * mono).toDouble()
                            countInWindow++

                            if (countInWindow >= windowSamples) {
                                val rms = sqrt(sumSq / countInWindow).toFloat()
                                rawWindows.add((windowIndex * 0.5) to rms)
                                windowIndex++
                                sumSq = 0.0
                                countInWindow = 0
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true
                    }
                }
            }

            if (countInWindow > 0) {
                val rms = sqrt(sumSq / countInWindow).toFloat()
                rawWindows.add((windowIndex * 0.5) to rms)
            }

            val avgRms = if (rawWindows.isNotEmpty()) {
                rawWindows.map { it.second.toDouble() }.average().toFloat()
            } else {
                0.0f
            }
            val vadThreshold = maxOf(0.012f, avgRms * 0.42f)

            val samples = rawWindows.map { (timeSec, rms) ->
                RmsSample(
                    timeSec = timeSec,
                    rms = rms,
                    isSpeech = rms >= vadThreshold
                )
            }

            onProgress(100)
            AudioAnalysisResult(
                rmsSamples = samples,
                averageRms = avgRms,
                sampleRateHz = sampleRate
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}

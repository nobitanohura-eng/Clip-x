package com.clipai.v2.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.clipai.v2.domain.WordToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class LinkSourceKind {
    YOUTUBE_VIDEO,
    YOUTUBE_LIVE,
    HLS_STREAM,
    DIRECT_VIDEO
}

data class ParsedLink(
    val normalizedUrl: String,
    val kind: LinkSourceKind,
    val youtubeVideoId: String? = null,
    val host: String
)

data class IngestedLinkMedia(
    val localFile: File,
    val localUri: Uri,
    val displayTitle: String,
    val sourceKind: LinkSourceKind,
    val extractedWords: List<WordToken>
)

/**
 * Pure URL parser & host validator (unit-testable on JVM without Android framework).
 */
object LinkUrlParser {
    private val youtubeHosts = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be",
        "www.youtu.be",
        "music.youtube.com"
    )
    private val youtubeIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

    fun parse(rawInput: String): Result<ParsedLink> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Paste a YouTube, video, or live stream link first."))
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("Enter a valid link starting with https:// or http://"))
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(IllegalArgumentException("Only http:// and https:// links are supported."))
        }

        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank() || host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) {
            return Result.failure(IllegalArgumentException("Local or private network links are blocked."))
        }

        val yt = extractYouTubeId(uri, host)
        if (yt != null) {
            return Result.success(
                ParsedLink(
                    normalizedUrl = trimmed,
                    kind = if (yt.second) LinkSourceKind.YOUTUBE_LIVE else LinkSourceKind.YOUTUBE_VIDEO,
                    youtubeVideoId = yt.first,
                    host = host
                )
            )
        }

        val path = uri.path?.lowercase().orEmpty()
        val query = uri.rawQuery?.lowercase().orEmpty()
        if (path.endsWith(".m3u8") || path.contains(".m3u8") || query.contains("m3u8")) {
            return Result.success(
                ParsedLink(
                    normalizedUrl = trimmed,
                    kind = LinkSourceKind.HLS_STREAM,
                    host = host
                )
            )
        }

        return Result.success(
            ParsedLink(
                normalizedUrl = trimmed,
                kind = LinkSourceKind.DIRECT_VIDEO,
                host = host
            )
        )
    }

    private fun extractYouTubeId(uri: URI, host: String): Pair<String, Boolean>? {
        if (host !in youtubeHosts) return null
        val segments = uri.path.orEmpty().split("/").filter { it.isNotBlank() }

        if (host == "youtu.be" || host == "www.youtu.be") {
            val id = segments.firstOrNull()
            if (id != null && youtubeIdRegex.matches(id)) return id to false
            return null
        }

        val queryPairs = uri.rawQuery.orEmpty().split("&")
        for (pair in queryPairs) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == "v" && youtubeIdRegex.matches(parts[1])) {
                return parts[1] to false
            }
        }

        if (segments.size >= 2) {
            val prefix = segments[0]
            val candidate = segments[1]
            if (prefix in setOf("live", "shorts", "embed", "v") && youtubeIdRegex.matches(candidate)) {
                return candidate to (prefix == "live")
            }
        }
        return null
    }

    fun isPrivateOrBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress) {
            return true
        }
        val bytes = addr.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            if (a == 100 && b in 64..127) return true // CGNAT
            if (a == 169 && b == 254) return true // Cloud metadata
            if (a == 0 || a >= 224) return true
        }
        return false
    }
}

/**
 * On-device URL & Live Stream ingestion engine:
 * - Resolves YouTube watch/shorts/live metadata, timed captions (JSON3/XML), and streams
 * - Downloads direct MP4/WebM/MOV URLs into app-private cache via OkHttp
 * - Captures HLS (.m3u8) VOD/live windows directly using Media3 Transformer
 * - Strictly blocks private/loopback/metadata network hosts
 */
object LinkIngestEngine {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns { hostname ->
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.any { LinkUrlParser.isPrivateOrBlockedAddress(it) }) {
                    throw SecurityException("Blocked connection to private or loopback host.")
                }
                addresses
            }
            .build()
    }

    private const val MAX_DIRECT_BYTES = 250L * 1024L * 1024L

    suspend fun ingest(
        context: Context,
        rawUrl: String,
        captureSeconds: Int,
        onStatus: (String, Int?) -> Unit
    ): IngestedLinkMedia = withContext(Dispatchers.IO) {
        val parsed = LinkUrlParser.parse(rawUrl).getOrElse { throw it }
        assertPublicHost(parsed.normalizedUrl)

        val cacheDir = File(context.cacheDir, "ingested_links").apply { mkdirs() }
        val boundedSec = captureSeconds.coerceIn(20, 240)

        when (parsed.kind) {
            LinkSourceKind.YOUTUBE_VIDEO, LinkSourceKind.YOUTUBE_LIVE -> {
                onStatus("Fetching YouTube video & captions...", null)
                val yt = fetchYouTubeDetails(parsed.youtubeVideoId!!)
                val streamUrl = yt.playableUrl
                    ?: throw IllegalStateException(
                        "YouTube restricted direct stream access for \"${yt.title}\". Try another public link, an HLS (.m3u8) stream, or a local video."
                    )

                assertPublicHost(streamUrl)
                val outFile = File(cacheDir, "yt_${UUID.randomUUID().toString().take(8)}.mp4")
                if (yt.isHls || yt.isLive) {
                    onStatus("Capturing live/HLS window (${boundedSec}s)...", null)
                    captureViaMedia3(context, streamUrl, outFile, boundedSec)
                } else {
                    downloadDirectVideo(streamUrl, outFile) { pct ->
                        onStatus("Downloading video ($pct%)...", pct)
                    }
                }
                IngestedLinkMedia(
                    localFile = outFile,
                    localUri = Uri.fromFile(outFile),
                    displayTitle = yt.title,
                    sourceKind = if (yt.isLive) LinkSourceKind.YOUTUBE_LIVE else LinkSourceKind.YOUTUBE_VIDEO,
                    extractedWords = yt.words
                )
            }

            LinkSourceKind.HLS_STREAM -> {
                val outFile = File(cacheDir, "hls_${UUID.randomUUID().toString().take(8)}.mp4")
                onStatus("Capturing live stream window (${boundedSec}s)...", null)
                captureViaMedia3(context, parsed.normalizedUrl, outFile, boundedSec)
                val title = URI(parsed.normalizedUrl).path?.substringAfterLast('/')
                    ?.ifBlank { parsed.host } ?: parsed.host
                IngestedLinkMedia(
                    localFile = outFile,
                    localUri = Uri.fromFile(outFile),
                    displayTitle = title,
                    sourceKind = LinkSourceKind.HLS_STREAM,
                    extractedWords = emptyList()
                )
            }

            LinkSourceKind.DIRECT_VIDEO -> {
                val outFile = File(cacheDir, "vid_${UUID.randomUUID().toString().take(8)}.mp4")
                downloadDirectVideo(parsed.normalizedUrl, outFile) { pct ->
                    onStatus("Downloading video ($pct%)...", pct)
                }
                val title = URI(parsed.normalizedUrl).path?.substringAfterLast('/')
                    ?.ifBlank { parsed.host } ?: parsed.host
                IngestedLinkMedia(
                    localFile = outFile,
                    localUri = Uri.fromFile(outFile),
                    displayTitle = title,
                    sourceKind = LinkSourceKind.DIRECT_VIDEO,
                    extractedWords = emptyList()
                )
            }
        }
    }

    private fun assertPublicHost(urlStr: String) {
        val uri = URI(urlStr)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw SecurityException("Only http and https links are allowed.")
        }
        val host = uri.host ?: throw SecurityException("Missing host.")
        val addresses = InetAddress.getAllByName(host)
        if (addresses.isEmpty()) throw SecurityException("Host could not be resolved.")
        for (addr in addresses) {
            if (LinkUrlParser.isPrivateOrBlockedAddress(addr)) {
                throw SecurityException("Target resolves to a blocked private or loopback address.")
            }
        }
    }

    private data class YouTubeResolveResult(
        val title: String,
        val isLive: Boolean,
        val isHls: Boolean,
        val playableUrl: String?,
        val words: List<WordToken>
    )

    private fun fetchYouTubeDetails(videoId: String): YouTubeResolveResult {
        var title = "YouTube Video ($videoId)"
        var isLive = false
        var isHls = false
        var playableUrl: String? = null
        var words: List<WordToken> = emptyList()

        // Try Android Innertube client first for metadata, captions & stream URL
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.44.38")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    }
                )
            )
        }

        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", "com.google.android.youtube/19.44.38 (Linux; U; Android 14)")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val details = json.optJSONObject("videoDetails")
                    if (details != null) {
                        title = details.optString("title", title).ifBlank { title }
                        isLive = details.optBoolean("isLiveContent", false) || details.optBoolean("isLive", false)
                    }

                    val streaming = json.optJSONObject("streamingData")
                    if (streaming != null) {
                        val hls = streaming.optString("hlsManifestUrl", "")
                        if (hls.isNotBlank()) {
                            playableUrl = hls
                            isHls = true
                        } else {
                            val formats = streaming.optJSONArray("formats")
                            if (formats != null) {
                                var bestHeight = 0
                                for (i in 0 until formats.length()) {
                                    val f = formats.optJSONObject(i) ?: continue
                                    val mime = f.optString("mimeType", "")
                                    val url = f.optString("url", "")
                                    val h = f.optInt("height", 0)
                                    if (url.isNotBlank() && mime.contains("video/mp4") && h <= 720 && h >= bestHeight) {
                                        bestHeight = h
                                        playableUrl = url
                                    }
                                }
                            }
                        }
                    }

                    val tracks = json.optJSONObject("captions")
                        ?.optJSONObject("playerCaptionsTracklistRenderer")
                        ?.optJSONArray("captionTracks")
                    if (tracks != null && tracks.length() > 0) {
                        var chosenBaseUrl: String? = null
                        for (i in 0 until tracks.length()) {
                            val t = tracks.optJSONObject(i) ?: continue
                            val lang = t.optString("languageCode", "")
                            val base = t.optString("baseUrl", "")
                            if (base.isBlank()) continue
                            if (chosenBaseUrl == null || lang.startsWith("en") || lang.startsWith("hi")) {
                                chosenBaseUrl = base
                                if (lang.startsWith("en") && t.optString("kind") != "asr") break
                            }
                        }
                        if (chosenBaseUrl != null) {
                            words = fetchYouTubeCaptions(chosenBaseUrl)
                        }
                    }
                }
            }
        }

        // Fallback title via oEmbed if Innertube omitted videoDetails
        if (title.startsWith("YouTube Video (")) {
            runCatching {
                val encoded = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
                val oReq = Request.Builder()
                    .url("https://www.youtube.com/oembed?url=$encoded&format=json")
                    .build()
                httpClient.newCall(oReq).execute().use { r ->
                    if (r.isSuccessful) {
                        val j = JSONObject(r.body?.string().orEmpty())
                        title = j.optString("title", title).ifBlank { title }
                    }
                }
            }
        }

        return YouTubeResolveResult(title, isLive, isHls, playableUrl, words)
    }

    private fun fetchYouTubeCaptions(baseUrl: String): List<WordToken> {
        return runCatching {
            val json3Url = if (baseUrl.contains("?")) "$baseUrl&fmt=json3" else "$baseUrl?fmt=json3"
            assertPublicHost(json3Url)
            val req = Request.Builder().url(json3Url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val text = resp.body?.string().orEmpty()
                if (!text.trim().startsWith("{")) return emptyList()
                val root = JSONObject(text)
                val events = root.optJSONArray("events") ?: return emptyList()
                val words = mutableListOf<WordToken>()
                var idx = 1
                for (i in 0 until events.length()) {
                    val ev = events.optJSONObject(i) ?: continue
                    val segs = ev.optJSONArray("segs") ?: continue
                    val baseMs = ev.optLong("tStartMs", -1L)
                    if (baseMs < 0L) continue
                    val durMs = ev.optLong("dDurationMs", 2200L).coerceAtLeast(300L)

                    val segCount = segs.length()
                    for (s in 0 until segCount) {
                        val seg = segs.optJSONObject(s) ?: continue
                        val utf8 = seg.optString("utf8", "").replace("\n", " ").trim()
                        if (utf8.isBlank()) continue
                        val offsetMs = seg.optLong("tOffsetMs", (s * durMs) / segCount.coerceAtLeast(1))
                        val startSec = (baseMs + offsetMs) / 1000.0
                        val tokens = utf8.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val step = ((durMs / 1000.0) / segCount.coerceAtLeast(1) / tokens.size.coerceAtLeast(1))
                            .coerceIn(0.18, 0.75)
                        tokens.forEachIndexed { tIdx, tok ->
                            val st = startSec + tIdx * step
                            words.add(
                                WordToken(
                                    id = "yt_${idx++}",
                                    text = tok,
                                    startSec = st,
                                    endSec = st + step * 0.92,
                                    confidence = 0.94f,
                                    locked = false
                                )
                            )
                        }
                    }
                }
                words
            }
        }.getOrDefault(emptyList())
    }

    private fun downloadDirectVideo(
        url: String,
        targetFile: File,
        onProgress: (Int) -> Unit
    ) {
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Video server returned HTTP ${resp.code}.")
            }
            val body = resp.body ?: throw IllegalStateException("Empty video response.")
            val total = body.contentLength()
            if (total > MAX_DIRECT_BYTES) {
                throw IllegalStateException("Remote video exceeds 250 MB link download limit. Pick it locally instead.")
            }

            var copied = 0L
            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        copied += read
                        if (copied > MAX_DIRECT_BYTES) {
                            throw IllegalStateException("Download stopped: exceeds 250 MB limit.")
                        }
                        output.write(buf, 0, read)
                        if (total > 0L) {
                            onProgress(((copied * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun captureViaMedia3(
        context: Context,
        streamUrl: String,
        outFile: File,
        captureSeconds: Int
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val clippedItem = MediaItem.Builder()
                .setUri(Uri.parse(streamUrl))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs(captureSeconds * 1000L)
                        .build()
                )
                .build()

            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()

            cont.invokeOnCancellation {
                transformer.cancel()
                outFile.delete()
            }
            val editedItem = EditedMediaItem.Builder(clippedItem).build()
            transformer.start(editedItem, outFile.absolutePath)
        }
    }
}

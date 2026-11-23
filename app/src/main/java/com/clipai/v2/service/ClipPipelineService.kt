package com.clipai.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.clipai.v2.R
import com.clipai.v2.data.local.ClipAiDatabase
import com.clipai.v2.data.local.ClipEntity
import com.clipai.v2.data.local.PipelineJobEntity
import com.clipai.v2.data.local.ProjectEntity
import com.clipai.v2.data.local.WordEntity
import com.clipai.v2.data.models.DeviceInspector
import com.clipai.v2.data.models.ModelCatalog
import com.clipai.v2.data.prefs.PreferencesRepository
import com.clipai.v2.domain.CaptionEngine
import com.clipai.v2.domain.ClipCandidate
import com.clipai.v2.domain.CropPlanner
import com.clipai.v2.domain.DownloadStatus
import com.clipai.v2.domain.FallbackLadder
import com.clipai.v2.domain.PipelineProgress
import com.clipai.v2.domain.PipelineStage
import com.clipai.v2.domain.PipelineStageState
import com.clipai.v2.domain.PipelineStageStatus
import com.clipai.v2.domain.ScoringEngine
import com.clipai.v2.domain.WindowSelector
import com.clipai.v2.domain.WordToken
import com.clipai.v2.media.AudioAnalyzer
import com.clipai.v2.media.VideoProber
import com.clipai.v2.media.VisualAnalyzer
import com.clipai.v2.ml.LiteRtLmReranker
import com.clipai.v2.ml.OnDeviceAsrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Foreground `mediaProcessing` service running ONE Auto Clip job at a time:
 * - Enforces 3.0 GB free storage guardrail before starting
 * - Checkpoints every stage into Room (`pipeline_jobs`)
 * - Strictly releases each native ML model before loading the next (RAM mutex)
 * - Publishes honest per-stage progress (indeterminate during model warm-up)
 * - Supports clean cancellation preserving any already-completed data
 */
class ClipPipelineService : Service() {

    companion object {
        const val CHANNEL_ID = "clipai_v2_pipeline"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_PIPELINE = "com.clipai.v2.action.START_PIPELINE"
        const val ACTION_START_LINK_PIPELINE = "com.clipai.v2.action.START_LINK_PIPELINE"
        const val ACTION_CANCEL_PIPELINE = "com.clipai.v2.action.CANCEL_PIPELINE"
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_SOURCE_URL = "extra_source_url"
        const val EXTRA_CAPTURE_SECONDS = "extra_capture_seconds"

        private val _progress = MutableStateFlow(PipelineProgress())
        val progress: StateFlow<PipelineProgress> = _progress.asStateFlow()

        fun start(context: Context, videoUri: Uri) {
            val intent = Intent(context, ClipPipelineService::class.java).apply {
                action = ACTION_START_PIPELINE
                putExtra(EXTRA_VIDEO_URI, videoUri.toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startForLink(context: Context, url: String, captureSeconds: Int = 120) {
            val intent = Intent(context, ClipPipelineService::class.java).apply {
                action = ACTION_START_LINK_PIPELINE
                putExtra(EXTRA_SOURCE_URL, url)
                putExtra(EXTRA_CAPTURE_SECONDS, captureSeconds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ClipPipelineService::class.java).apply {
                action = ACTION_CANCEL_PIPELINE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_PIPELINE -> {
                activeJob?.cancel()
                _progress.update { state ->
                    state.copy(
                        isRunning = false,
                        activeModelInRam = null,
                        stages = state.stages.map {
                            if (it.status == PipelineStageStatus.RUNNING) {
                                it.copy(status = PipelineStageStatus.CANCELLED, detail = "Cancelled by user")
                            } else {
                                it
                            }
                        }
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_START_PIPELINE -> {
                val uriStr = intent.getStringExtra(EXTRA_VIDEO_URI) ?: return START_NOT_STICKY
                val uri = Uri.parse(uriStr)

                startForegroundSafe("Starting Auto Clip...")
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    runPipeline(uri = uri, overrideTitle = null, preExtractedWords = emptyList())
                }
            }

            ACTION_START_LINK_PIPELINE -> {
                val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: return START_NOT_STICKY
                val captureSeconds = intent.getIntExtra(EXTRA_CAPTURE_SECONDS, 120)

                startForegroundSafe("Loading link...")
                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    runLinkPipeline(sourceUrl, captureSeconds)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafe(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun runLinkPipeline(sourceUrl: String, captureSeconds: Int) {
        _progress.value = PipelineProgress(
            isRunning = true,
            projectId = null,
            activeModelInRam = null,
            stages = PipelineStage.entries.map { PipelineStageState(it) },
            errorMessage = null
        )
        try {
            updateStage(PipelineStage.PROBE, PipelineStageStatus.RUNNING, "Resolving video / live stream link...", null)
            val ingested = com.clipai.v2.media.LinkIngestEngine.ingest(
                context = applicationContext,
                rawUrl = sourceUrl,
                captureSeconds = captureSeconds
            ) { msg, pct ->
                updateStage(PipelineStage.PROBE, PipelineStageStatus.RUNNING, msg, pct)
                updateNotification(msg)
            }
            runPipeline(
                uri = ingested.localUri,
                overrideTitle = ingested.displayTitle,
                preExtractedWords = ingested.extractedWords
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _progress.update { state ->
                state.copy(
                    isRunning = false,
                    activeModelInRam = null,
                    errorMessage = t.message ?: "Unable to clip video from that link."
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun runPipeline(
        uri: Uri,
        overrideTitle: String? = null,
        preExtractedWords: List<WordToken> = emptyList()
    ) {
        val db = ClipAiDatabase.getInstance(applicationContext)
        val prefsRepo = PreferencesRepository(applicationContext)
        val settings = prefsRepo.settingsFlow.first()
        val device = DeviceInspector.inspect(applicationContext)
        val installedModels = db.modelStateDao().getAllModelStates()
            .filter { it.status == DownloadStatus.INSTALLED.name && !it.localFilePath.isNullOrBlank() }
            .associateBy { it.modelId }

        val plan = FallbackLadder.resolve(
            device = device,
            installedModelIds = installedModels.keys,
            preferredAsrId = settings.selectedAsrModelId,
            preferredLlmId = settings.selectedLlmModelId,
            llmEnabledByUser = settings.llmEnabled,
            requireChargingForHeavy = settings.requireChargingForHeavy
        )

        if (plan.storageBlockedReason != null) {
            _progress.value = PipelineProgress(
                isRunning = false,
                errorMessage = plan.storageBlockedReason
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val jobId = "job-${UUID.randomUUID().toString().take(8)}"
        val projectId = "proj-${UUID.randomUUID().toString().take(8)}"

        _progress.value = PipelineProgress(
            isRunning = true,
            projectId = projectId,
            activeModelInRam = null,
            stages = PipelineStage.entries.map { PipelineStageState(it) },
            errorMessage = null
        )

        try {
            // 1. Probe
            updateStage(PipelineStage.PROBE, PipelineStageStatus.RUNNING, "Probing metadata & SHA-256...", null)
            updateNotification("Probing video metadata...")
            val meta = VideoProber.probe(applicationContext, uri)
            val displayTitle = overrideTitle?.takeIf { it.isNotBlank() } ?: meta.fileName
            val nowMs = System.currentTimeMillis()
            db.projectDao().upsert(
                ProjectEntity(
                    id = projectId,
                    uriString = meta.uriString,
                    fileName = displayTitle,
                    fileSizeBytes = meta.fileSizeBytes,
                    fileHashSha256 = meta.fileHashSha256,
                    durationSec = meta.durationSec,
                    width = meta.width,
                    height = meta.height,
                    fps = meta.fps,
                    rotationDegrees = meta.rotationDegrees,
                    hasAudio = meta.hasAudio,
                    transcriptCached = false,
                    llmReranked = false,
                    createdAtMs = nowMs,
                    updatedAtMs = nowMs
                )
            )
            checkpoint(db, jobId, projectId, PipelineStage.PROBE)
            updateStage(
                PipelineStage.PROBE,
                PipelineStageStatus.DONE,
                String.format(java.util.Locale.ROOT, "%dx%d · %.1fs", meta.width, meta.height, meta.durationSec),
                100
            )

            // 2. Audio extraction & VAD
            updateStage(PipelineStage.AUDIO_VAD, PipelineStageStatus.RUNNING, "Decoding audio PCM & VAD...", 0)
            updateNotification("Analyzing audio waveform & silence...")
            val audio = AudioAnalyzer.analyze(applicationContext, uri, meta.durationSec) { pct ->
                updateStage(PipelineStage.AUDIO_VAD, PipelineStageStatus.RUNNING, "Decoding audio PCM ($pct%)", pct)
            }
            checkpoint(db, jobId, projectId, PipelineStage.AUDIO_VAD)
            updateStage(
                PipelineStage.AUDIO_VAD,
                PipelineStageStatus.DONE,
                "${audio.rmsSamples.size} RMS frames analyzed",
                100
            )

            // 3. ASR (YouTube timed captions -> SHA-256 transcript cache -> ONNX ASR with RAM mutex)
            val preSynced = preExtractedWords.filter { it.startSec <= meta.durationSec + 0.5 }
            val cachedEntities = if (preSynced.isEmpty()) db.wordDao().getCachedWordsByHash(meta.fileHashSha256) else emptyList()
            val words: List<WordToken>
            val usedCache: Boolean

            if (preSynced.isNotEmpty()) {
                usedCache = true
                words = preSynced.mapIndexed { idx, w ->
                    w.copy(
                        id = "${projectId}_yt_$idx",
                        endSec = w.endSec.coerceAtMost(meta.durationSec)
                    )
                }
                db.wordDao().insertAll(
                    words.map { w ->
                        WordEntity(
                            id = w.id,
                            projectId = projectId,
                            fileHashSha256 = meta.fileHashSha256,
                            text = w.text,
                            startSec = w.startSec,
                            endSec = w.endSec,
                            confidence = w.confidence,
                            locked = w.locked
                        )
                    }
                )
                updateStage(
                    PipelineStage.ASR,
                    PipelineStageStatus.DONE,
                    "${words.size} synced caption words loaded",
                    100
                )
            } else if (cachedEntities.isNotEmpty()) {
                usedCache = true
                words = cachedEntities.map {
                    WordToken(it.id, it.text, it.startSec, it.endSec, it.confidence, it.locked)
                }
                db.wordDao().insertAll(
                    words.map { w ->
                        WordEntity(
                            id = "${projectId}_${w.id}",
                            projectId = projectId,
                            fileHashSha256 = meta.fileHashSha256,
                            text = w.text,
                            startSec = w.startSec,
                            endSec = w.endSec,
                            confidence = w.confidence,
                            locked = w.locked
                        )
                    }
                )
                updateStage(
                    PipelineStage.ASR,
                    PipelineStageStatus.DONE,
                    "Transcript cache hit (${words.size} words) — skipped ASR",
                    100
                )
            } else {
                usedCache = false
                val asrModelState = installedModels[plan.activeAsrModelId]
                val asrFile = asrModelState?.localFilePath?.let { File(it) }
                val asrName = ModelCatalog.findById(plan.activeAsrModelId)?.name ?: "On-Device ASR"
                setActiveModel(asrName)
                updateNotification("Running speech recognition ($asrName)...")
                updateStage(PipelineStage.ASR, PipelineStageStatus.RUNNING, "Loading $asrName...", null)

                val outcome = OnDeviceAsrEngine.transcribeOrAlign(
                    modelFile = asrFile,
                    rmsSamples = audio.rmsSamples,
                    durationSec = meta.durationSec
                ) { msg ->
                    updateStage(PipelineStage.ASR, PipelineStageStatus.RUNNING, msg, null)
                }
                setActiveModel(null) // Release ASR before next stage!
                words = outcome.words

                db.wordDao().insertAll(
                    words.map { w ->
                        WordEntity(
                            id = w.id,
                            projectId = projectId,
                            fileHashSha256 = meta.fileHashSha256,
                            text = w.text,
                            startSec = w.startSec,
                            endSec = w.endSec,
                            confidence = w.confidence,
                            locked = w.locked
                        )
                    }
                )
                updateStage(PipelineStage.ASR, PipelineStageStatus.DONE, outcome.note, 100)
            }
            checkpoint(db, jobId, projectId, PipelineStage.ASR)

            // 4. Sentence snapping
            updateStage(PipelineStage.SENTENCES, PipelineStageStatus.RUNNING, "Snapping pause boundaries...", null)
            val sentences = WindowSelector.buildSentences(words)
            updateStage(PipelineStage.SENTENCES, PipelineStageStatus.DONE, "${sentences.size} sentence segments", 100)
            checkpoint(db, jobId, projectId, PipelineStage.SENTENCES)

            // 5. Scene-cut detection + MediaPipe BlazeFace proxy
            val blazeFile = installedModels[FallbackLadder.MODEL_BLAZEFACE]?.localFilePath?.let { File(it) }
            setActiveModel(if (blazeFile != null) "BlazeFace Short-Range" else null)
            updateNotification("Tracking faces & detecting scene cuts...")
            updateStage(PipelineStage.SCENE_AND_FACE, PipelineStageStatus.RUNNING, "Sampling 480p proxy frames...", 0)
            val visual = VisualAnalyzer.analyze(
                context = applicationContext,
                uri = uri,
                durationSec = meta.durationSec,
                blazeFaceModelFile = blazeFile
            ) { pct ->
                updateStage(PipelineStage.SCENE_AND_FACE, PipelineStageStatus.RUNNING, "Proxy scan ($pct%)", pct)
            }
            setActiveModel(null)
            updateStage(
                PipelineStage.SCENE_AND_FACE,
                PipelineStageStatus.DONE,
                "${visual.sceneCuts.size} cuts · ${if (visual.usedBlazeFaceModel) "BlazeFace active" else "Center fallback"}",
                100
            )
            checkpoint(db, jobId, projectId, PipelineStage.SCENE_AND_FACE)

            // 6. Window generation + deterministic scoring + 30s greedy dedup
            updateStage(PipelineStage.WINDOW_SCORING, PipelineStageStatus.RUNNING, "Scoring candidate windows...", null)
            val rawWindows = WindowSelector.generateCandidateWindows(
                sentences = sentences,
                sceneCuts = visual.sceneCuts,
                sourceDurationSec = meta.durationSec,
                minDurSec = settings.minClipSec,
                maxDurSec = settings.maxClipSec
            )

            val scoredCandidates = rawWindows.mapIndexed { idx, win ->
                val score = ScoringEngine.scoreWindow(win, words, audio.rmsSamples, visual.faceSamples)
                val verbatim = CaptionEngine.extractVerbatimHook(words, win.startSec)
                val title = CaptionEngine.buildOfflineTemplateTitle(words, win.startSec, win.endSec, score.hookSignals)
                val cropPlan = CropPlanner.planSmoothedCrop(
                    faceSamples = visual.faceSamples,
                    sceneCuts = visual.sceneCuts,
                    clipStartSec = win.startSec,
                    clipEndSec = win.endSec
                )
                ClipCandidate(
                    id = "${projectId}_clip_${idx + 1}",
                    rank = 0,
                    startSec = win.startSec,
                    endSec = win.endSec,
                    scores = score,
                    verbatimHook = verbatim.ifBlank { "Clip ${idx + 1}" },
                    titleHook = title,
                    cropKeyframes = cropPlan
                )
            }

            // 7. Optional LiteRT-LM Top-10 Re-rank (strictly isolated in RAM)
            val llmId = plan.activeLlmModelId
            val llmFile = llmId?.let { installedModels[it]?.localFilePath }?.let { File(it) }
            val rerankedCandidates: List<ClipCandidate>
            val llmDidRun: Boolean

            if (llmFile != null && llmFile.exists()) {
                val llmName = ModelCatalog.findById(llmId)?.name ?: "Gemma LiteRT-LM"
                setActiveModel(llmName)
                updateNotification("Loading $llmName for top-10 re-rank...")
                updateStage(PipelineStage.LLM_RERANK, PipelineStageStatus.RUNNING, "Loading $llmName...", null)
                rerankedCandidates = LiteRtLmReranker.rerankTopCandidates(llmFile, scoredCandidates) { msg ->
                    updateStage(PipelineStage.LLM_RERANK, PipelineStageStatus.RUNNING, msg, null)
                }
                setActiveModel(null)
                llmDidRun = true
                updateStage(PipelineStage.LLM_RERANK, PipelineStageStatus.DONE, "Top-10 re-ranked · RAM released", 100)
            } else {
                rerankedCandidates = scoredCandidates
                llmDidRun = false
                updateStage(
                    PipelineStage.LLM_RERANK,
                    PipelineStageStatus.SKIPPED,
                    plan.llmDisabledReason ?: "Rule-based scoring active",
                    null
                )
            }

            val selection = WindowSelector.selectGreedyDedup(
                candidates = rerankedCandidates,
                maxClips = settings.maxClips,
                minGapSec = settings.dedupGapSec.toDouble()
            )
            updateStage(
                PipelineStage.WINDOW_SCORING,
                PipelineStageStatus.DONE,
                "${selection.selected.size} clips kept from ${selection.allAnnotated.size} candidates",
                100
            )
            checkpoint(db, jobId, projectId, PipelineStage.WINDOW_SCORING)

            // 8. Persist crop plan & clips to Room
            updateStage(PipelineStage.CROP_PLAN, PipelineStageStatus.RUNNING, "Saving clips & crop keyframes...", null)
            db.clipDao().deleteForProject(projectId)
            db.clipDao().insertAll(selection.allAnnotated.map { it.toEntity(projectId) })
            db.projectDao().upsert(
                ProjectEntity(
                    id = projectId,
                    uriString = meta.uriString,
                    fileName = displayTitle,
                    fileSizeBytes = meta.fileSizeBytes,
                    fileHashSha256 = meta.fileHashSha256,
                    durationSec = meta.durationSec,
                    width = meta.width,
                    height = meta.height,
                    fps = meta.fps,
                    rotationDegrees = meta.rotationDegrees,
                    hasAudio = meta.hasAudio,
                    transcriptCached = usedCache || words.isNotEmpty(),
                    llmReranked = llmDidRun,
                    createdAtMs = nowMs,
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            checkpoint(db, jobId, projectId, PipelineStage.CROP_PLAN)
            updateStage(
                PipelineStage.CROP_PLAN,
                PipelineStageStatus.DONE,
                "Saved ${selection.selected.size} ready-to-edit clips",
                100
            )

            _progress.update { it.copy(isRunning = false, activeModelInRam = null) }
        } catch (ce: CancellationException) {
            setActiveModel(null)
            throw ce
        } catch (t: Throwable) {
            setActiveModel(null)
            _progress.update { state ->
                state.copy(
                    isRunning = false,
                    activeModelInRam = null,
                    errorMessage = t.message ?: "Processing failed unexpectedly.",
                    stages = state.stages.map {
                        if (it.status == PipelineStageStatus.RUNNING) {
                            it.copy(status = PipelineStageStatus.ERROR, detail = t.message ?: "Error")
                        } else {
                            it
                        }
                    }
                )
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun checkpoint(
        db: ClipAiDatabase,
        jobId: String,
        projectId: String,
        stage: PipelineStage
    ) {
        db.pipelineJobDao().upsert(
            PipelineJobEntity(
                jobId = jobId,
                projectId = projectId,
                lastCompletedStage = stage.name,
                status = "CHECKPOINTED",
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun setActiveModel(name: String?) {
        _progress.update { it.copy(activeModelInRam = name) }
    }

    private fun updateStage(
        stage: PipelineStage,
        status: PipelineStageStatus,
        detail: String,
        progressPct: Int?
    ) {
        _progress.update { state ->
            state.copy(
                stages = state.stages.map {
                    if (it.stage == stage) {
                        it.copy(status = status, detail = detail, progressPercent = progressPct)
                    } else {
                        it
                    }
                }
            )
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_pipeline_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_pipeline_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

fun ClipCandidate.toEntity(projectId: String): ClipEntity {
    val kfCsv = cropKeyframes.joinToString(";") { kf ->
        "${kf.timeSec},${kf.centerX},${kf.faceConfidence},${if (kf.isCutBoundary) 1 else 0}"
    }
    return ClipEntity(
        id = id,
        projectId = projectId,
        rank = rank,
        startSec = startSec,
        endSec = endSec,
        hookScore = scores.hook,
        flowScore = scores.flow,
        valueScore = scores.value,
        audioVisualScore = scores.audioVisual,
        totalScore = scores.total,
        hookSignalsJoined = scores.hookSignals.joinToString("|"),
        silenceFraction = scores.silenceFraction,
        faceVisibleFraction = scores.faceVisibleFraction,
        infoDensityWps = scores.infoDensityWps,
        energyRatio = scores.energyRatio,
        llmDelta = scores.llmDelta,
        llmNote = scores.llmNote,
        verbatimHook = verbatimHook,
        titleHook = titleHook,
        hookMode = hookMode.name,
        cropMode = cropMode.name,
        manualCropOffsetX = manualCropOffsetX,
        cropKeyframesCsv = kfCsv,
        selected = selected,
        dedupedByClipId = dedupedByClipId,
        dedupGapSec = dedupGapSec,
        exportedFilePath = exportedFilePath,
        qaPassed = qaPassed
    )
}

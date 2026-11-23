package com.clipai.v2.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.clipai.v2.data.local.ClipAiDatabase
import com.clipai.v2.data.local.ClipEntity
import com.clipai.v2.data.local.ModelStateEntity
import com.clipai.v2.data.local.ProjectEntity
import com.clipai.v2.data.local.WordEntity
import com.clipai.v2.data.models.DeviceInspector
import com.clipai.v2.data.models.ModelCatalog
import com.clipai.v2.data.models.ModelDownloadWorker
import com.clipai.v2.data.prefs.PreferencesRepository
import com.clipai.v2.data.prefs.UserSettings
import com.clipai.v2.domain.CaptionEngine
import com.clipai.v2.domain.ClipCandidate
import com.clipai.v2.domain.CropKeyframe
import com.clipai.v2.domain.CropMode
import com.clipai.v2.domain.CropPlanner
import com.clipai.v2.domain.DeviceCapabilities
import com.clipai.v2.domain.DownloadStatus
import com.clipai.v2.domain.FallbackLadder
import com.clipai.v2.domain.FallbackPlan
import com.clipai.v2.domain.HookMode
import com.clipai.v2.domain.PipelineProgress
import com.clipai.v2.domain.QaCheckResult
import com.clipai.v2.domain.ScoreBreakdown
import com.clipai.v2.domain.ScoringEngine
import com.clipai.v2.domain.WindowSelector
import com.clipai.v2.domain.WordToken
import com.clipai.v2.media.ClipExporter
import com.clipai.v2.ml.OnDeviceAsrEngine
import com.clipai.v2.service.ClipPipelineService
import com.clipai.v2.service.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ExportUiState(
    val isExporting: Boolean = false,
    val progressPercent: Int = 0,
    val statusText: String = "",
    val lastQaResult: QaCheckResult? = null,
    val lastOutputPath: String? = null,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ClipAiDatabase.getInstance(application)
    private val prefsRepo = PreferencesRepository(application)
    private val workManager = WorkManager.getInstance(application)

    private val _selectedProjectId = MutableStateFlow<String?>(null)
    val selectedProjectId: StateFlow<String?> = _selectedProjectId.asStateFlow()

    private val _selectedClipId = MutableStateFlow<String?>(null)
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()

    private val _deviceCapabilities = MutableStateFlow(DeviceInspector.inspect(application))
    val deviceCapabilities: StateFlow<DeviceCapabilities> = _deviceCapabilities.asStateFlow()

    private val _hasHfToken = MutableStateFlow(prefsRepo.hasUserHfToken())
    val hasHfToken: StateFlow<Boolean> = _hasHfToken.asStateFlow()

    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

    private var exportJob: Job? = null

    val pipelineProgress: StateFlow<PipelineProgress> = ClipPipelineService.progress

    val settings: StateFlow<UserSettings> = prefsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val projects: StateFlow<List<ProjectEntity>> = db.projectDao().observeAllProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelStates: StateFlow<Map<String, ModelStateEntity>> = db.modelStateDao()
        .observeAllModelStates()
        .map { list -> list.associateBy { it.modelId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val fallbackPlan: StateFlow<FallbackPlan> = combine(
        deviceCapabilities,
        modelStates,
        settings
    ) { device, states, cfg ->
        val installedIds = states.values
            .filter { it.status == DownloadStatus.INSTALLED.name && !it.localFilePath.isNullOrBlank() }
            .map { it.modelId }
            .toSet()

        FallbackLadder.resolve(
            device = device,
            installedModelIds = installedIds,
            preferredAsrId = cfg.selectedAsrModelId,
            preferredLlmId = cfg.selectedLlmModelId,
            llmEnabledByUser = cfg.llmEnabled,
            requireChargingForHeavy = cfg.requireChargingForHeavy
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FallbackLadder.resolve(
            device = _deviceCapabilities.value,
            installedModelIds = emptySet(),
            preferredAsrId = FallbackLadder.MODEL_WHISPER_TINY,
            preferredLlmId = FallbackLadder.MODEL_GEMMA_4_E2B,
            llmEnabledByUser = false,
            requireChargingForHeavy = true
        )
    )

    val activeProject: StateFlow<ProjectEntity?> = combine(projects, _selectedProjectId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeClips: StateFlow<List<ClipCandidate>> = activeProject
        .flatMapLatest { proj ->
            if (proj == null) flowOf(emptyList())
            else db.clipDao().observeClipsForProject(proj.id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeWords: StateFlow<List<WordToken>> = activeProject
        .flatMapLatest { proj ->
            if (proj == null) flowOf(emptyList())
            else db.wordDao().observeWordsForProject(proj.id).map { list ->
                list.map { WordToken(it.id, it.text, it.startSec, it.endSec, it.confidence, it.locked) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedClip: StateFlow<ClipCandidate?> = combine(activeClips, _selectedClipId) { clips, clipId ->
        clips.firstOrNull { it.id == clipId }
            ?: clips.firstOrNull { it.selected }
            ?: clips.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        syncModelStatesWithDisk()
        viewModelScope.launch {
            pipelineProgress.collect { progress ->
                if (!progress.isRunning && progress.projectId != null && progress.errorMessage == null) {
                    _selectedProjectId.value = progress.projectId
                }
            }
        }
    }

    fun refreshDeviceCapabilities() {
        _deviceCapabilities.value = DeviceInspector.inspect(getApplication())
    }

    private fun syncModelStatesWithDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val existing = db.modelStateDao().getAllModelStates().associateBy { it.modelId }
            for (descriptor in ModelCatalog.models) {
                val finalFile = ModelCatalog.targetFile(ctx, descriptor)
                val partFile = ModelCatalog.partFile(ctx, descriptor)
                val current = existing[descriptor.id]
                when {
                    finalFile.exists() && finalFile.length() > 0L -> {
                        if (current?.status != DownloadStatus.INSTALLED.name) {
                            db.modelStateDao().upsert(
                                ModelStateEntity(
                                    modelId = descriptor.id,
                                    status = DownloadStatus.INSTALLED.name,
                                    downloadedBytes = finalFile.length(),
                                    totalBytes = finalFile.length(),
                                    localFilePath = finalFile.absolutePath,
                                    verifiedSha256 = true,
                                    lastError = null,
                                    updatedAtMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }

                    partFile.exists() && partFile.length() > 0L &&
                        current?.status != DownloadStatus.DOWNLOADING.name -> {
                        db.modelStateDao().upsert(
                            ModelStateEntity(
                                modelId = descriptor.id,
                                status = DownloadStatus.PAUSED.name,
                                downloadedBytes = partFile.length(),
                                totalBytes = descriptor.sizeBytes,
                                localFilePath = null,
                                verifiedSha256 = false,
                                lastError = null,
                                updatedAtMs = System.currentTimeMillis()
                            )
                        )
                    }

                    !finalFile.exists() && current?.status == DownloadStatus.INSTALLED.name -> {
                        db.modelStateDao().deleteById(descriptor.id)
                    }
                }
            }
        }
    }

    fun startAutoClipForUri(uri: Uri) {
        val ctx = getApplication<Application>()
        refreshDeviceCapabilities()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        ClipPipelineService.start(ctx, uri)
    }

    fun startAutoClipForLink(url: String, captureSeconds: Int = 120) {
        val ctx = getApplication<Application>()
        refreshDeviceCapabilities()
        ClipPipelineService.startForLink(ctx, url.trim(), captureSeconds)
    }

    fun cancelActivePipeline() {
        ClipPipelineService.cancel(getApplication())
    }

    fun selectProject(projectId: String) {
        _selectedProjectId.value = projectId
        _selectedClipId.value = null
        _exportState.value = ExportUiState()
    }

    fun selectClip(clipId: String) {
        _selectedClipId.value = clipId
        _exportState.value = ExportUiState()
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.wordDao().deleteForProject(projectId)
            db.clipDao().deleteForProject(projectId)
            db.projectDao().deleteById(projectId)
            if (_selectedProjectId.value == projectId) {
                _selectedProjectId.value = null
                _selectedClipId.value = null
            }
        }
    }

    fun updateCropSettings(clipId: String, cropMode: CropMode, manualOffsetX: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            db.clipDao().updateCropSettings(
                clipId = clipId,
                cropMode = cropMode.name,
                offsetX = manualOffsetX.coerceIn(-0.35f, 0.35f)
            )
        }
    }

    fun updateHookSettings(clipId: String, hookMode: HookMode, verbatimHook: String, titleHook: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.clipDao().updateHookSettings(
                clipId = clipId,
                hookMode = hookMode.name,
                verbatimHook = verbatimHook.trim(),
                titleHook = titleHook.trim()
            )
        }
    }

    fun lockWordEdit(wordId: String, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isBlank()) return
        val proj = activeProject.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            db.wordDao().lockWordEdit(wordId, trimmed)
            val refreshedWords = db.wordDao().getWordsForProject(proj.id).map {
                WordToken(it.id, it.text, it.startSec, it.endSec, it.confidence, it.locked)
            }
            val clips = db.clipDao().getClipsForProject(proj.id)
            clips.forEach { clip ->
                val refreshedHook = CaptionEngine.extractVerbatimHook(refreshedWords, clip.startSec)
                if (refreshedHook.isNotBlank() && refreshedHook != clip.verbatimHook) {
                    db.clipDao().updateHookSettings(
                        clipId = clip.id,
                        hookMode = clip.hookMode,
                        verbatimHook = refreshedHook,
                        titleHook = clip.titleHook
                    )
                }
            }
        }
    }

    /**
     * Imports & aligns a pasted transcript (English, Hindi Devanagari, or Hinglish) across the
     * active project's timeline and immediately re-scores candidate clips deterministically.
     */
    fun importTranscriptAndRescore(rawTranscript: String) {
        val proj = activeProject.value ?: return
        val cfg = settings.value
        viewModelScope.launch(Dispatchers.Default) {
            val aligned = OnDeviceAsrEngine.alignTranscriptToSpeech(
                rawTranscript = rawTranscript,
                rmsSamples = emptyList(),
                durationSec = proj.durationSec
            )
            if (aligned.isEmpty()) return@launch

            db.wordDao().deleteForProject(proj.id)
            db.wordDao().insertAll(
                aligned.map { w ->
                    WordEntity(
                        id = w.id,
                        projectId = proj.id,
                        fileHashSha256 = proj.fileHashSha256,
                        text = w.text,
                        startSec = w.startSec,
                        endSec = w.endSec,
                        confidence = w.confidence,
                        locked = true
                    )
                }
            )

            val sentences = WindowSelector.buildSentences(aligned)
            val windows = WindowSelector.generateCandidateWindows(
                sentences = sentences,
                sceneCuts = emptyList(),
                sourceDurationSec = proj.durationSec,
                minDurSec = cfg.minClipSec,
                maxDurSec = cfg.maxClipSec
            )
            val scored = windows.mapIndexed { idx, win ->
                val score = ScoringEngine.scoreWindow(win, aligned, emptyList(), emptyList())
                val verbatim = CaptionEngine.extractVerbatimHook(aligned, win.startSec)
                val title = CaptionEngine.buildOfflineTemplateTitle(aligned, win.startSec, win.endSec, score.hookSignals)
                ClipCandidate(
                    id = "${proj.id}_clip_${idx + 1}",
                    rank = 0,
                    startSec = win.startSec,
                    endSec = win.endSec,
                    scores = score,
                    verbatimHook = verbatim.ifBlank { "Clip ${idx + 1}" },
                    titleHook = title,
                    cropKeyframes = CropPlanner.planSmoothedCrop(emptyList(), emptyList(), win.startSec, win.endSec)
                )
            }
            val deduped = WindowSelector.selectGreedyDedup(
                candidates = scored,
                maxClips = cfg.maxClips,
                minGapSec = cfg.dedupGapSec.toDouble()
            )
            db.clipDao().deleteForProject(proj.id)
            db.clipDao().insertAll(deduped.allAnnotated.map { it.toEntity(proj.id) })
            _selectedClipId.value = deduped.selected.firstOrNull()?.id
        }
    }

    fun exportSelectedClip() {
        val proj = activeProject.value ?: return
        val clip = selectedClip.value ?: return
        val words = activeWords.value
        val preset = settings.value.exportPreset

        refreshDeviceCapabilities()
        val storageBlock = fallbackPlan.value.storageBlockedReason
        if (storageBlock != null) {
            _exportState.value = ExportUiState(errorMessage = storageBlock)
            return
        }

        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _exportState.value = ExportUiState(isExporting = true, progressPercent = 0, statusText = "Preparing export...")
            try {
                val outcome = ClipExporter.exportClip(
                    context = getApplication(),
                    sourceUri = Uri.parse(proj.uriString),
                    sourceWidth = proj.width,
                    sourceHeight = proj.height,
                    clip = clip,
                    words = words,
                    preset = preset
                ) { pct, msg ->
                    _exportState.update { it.copy(progressPercent = pct, statusText = msg) }
                }

                withContext(Dispatchers.IO) {
                    db.clipDao().updateExportResult(
                        clipId = clip.id,
                        path = outcome.outputFile.absolutePath,
                        qaPassed = outcome.qaResult.passed
                    )
                }

                _exportState.value = ExportUiState(
                    isExporting = false,
                    progressPercent = 100,
                    statusText = if (outcome.qaResult.passed) "Export verified" else "QA flagged warnings",
                    lastQaResult = outcome.qaResult,
                    lastOutputPath = outcome.outputFile.absolutePath
                )
            } catch (t: Throwable) {
                _exportState.value = ExportUiState(
                    isExporting = false,
                    errorMessage = t.message ?: "Export failed."
                )
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _exportState.value = ExportUiState(statusText = "Export cancelled")
    }

    fun buildShareIntent(filePath: String): Intent? {
        val file = File(filePath)
        if (!file.exists()) return null
        val ctx = getApplication<Application>()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun enqueueModelDownload(modelId: String) {
        val descriptor = ModelCatalog.findById(modelId) ?: return
        refreshDeviceCapabilities()
        val cfg = settings.value
        val blockReason = FallbackLadder.canDownloadModel(
            model = descriptor,
            device = _deviceCapabilities.value,
            wifiOnlyOver100Mb = cfg.wifiOnlyOver100Mb,
            hasUserHfToken = _hasHfToken.value
        )
        if (blockReason != null) {
            viewModelScope.launch(Dispatchers.IO) {
                db.modelStateDao().upsert(
                    ModelStateEntity(
                        modelId = modelId,
                        status = DownloadStatus.FAILED.name,
                        downloadedBytes = 0L,
                        totalBytes = descriptor.sizeBytes,
                        localFilePath = null,
                        verifiedSha256 = false,
                        lastError = blockReason,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
            return
        }

        val networkType = if (cfg.wifiOnlyOver100Mb && descriptor.sizeBytes > 100L * 1024L * 1024L) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(descriptor.category == com.clipai.v2.domain.ModelCategory.LLM)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to modelId))
            .addTag("model_$modelId")
            .build()

        // One model download at a time
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun pauseModelDownload(modelId: String) {
        workManager.cancelAllWorkByTag("model_$modelId")
    }

    fun deleteModel(modelId: String) {
        pauseModelDownload(modelId)
        val descriptor = ModelCatalog.findById(modelId) ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            ModelCatalog.targetFile(ctx, descriptor).delete()
            ModelCatalog.partFile(ctx, descriptor).delete()
            db.modelStateDao().deleteById(modelId)
        }
    }

    fun saveUserHfToken(token: String) {
        prefsRepo.saveUserHfToken(token)
        _hasHfToken.value = prefsRepo.hasUserHfToken()
    }

    fun clearUserHfToken() {
        prefsRepo.clearUserHfToken()
        _hasHfToken.value = false
    }

    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch {
            prefsRepo.updateSettings(transform)
        }
    }
}

private fun ClipEntity.toDomain(): ClipCandidate {
    val keyframes = cropKeyframesCsv.split(";")
        .mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size < 4) null
            else CropKeyframe(
                timeSec = parts[0].toDoubleOrNull() ?: return@mapNotNull null,
                centerX = parts[1].toFloatOrNull() ?: 0.5f,
                faceConfidence = parts[2].toFloatOrNull() ?: 0.5f,
                isCutBoundary = parts[3] == "1"
            )
        }

    return ClipCandidate(
        id = id,
        rank = rank,
        startSec = startSec,
        endSec = endSec,
        scores = ScoreBreakdown(
            hook = hookScore,
            flow = flowScore,
            value = valueScore,
            audioVisual = audioVisualScore,
            total = totalScore,
            hookSignals = hookSignalsJoined.split("|").filter { it.isNotBlank() },
            silenceFraction = silenceFraction,
            faceVisibleFraction = faceVisibleFraction,
            infoDensityWps = infoDensityWps,
            energyRatio = energyRatio,
            llmDelta = llmDelta,
            llmNote = llmNote
        ),
        verbatimHook = verbatimHook,
        titleHook = titleHook,
        hookMode = HookMode.entries.firstOrNull { it.name == hookMode } ?: HookMode.VERBATIM_QUOTE,
        cropMode = CropMode.entries.firstOrNull { it.name == cropMode } ?: CropMode.SMART_FACE_TRACK,
        manualCropOffsetX = manualCropOffsetX,
        cropKeyframes = keyframes,
        selected = selected,
        dedupedByClipId = dedupedByClipId,
        dedupGapSec = dedupGapSec,
        exportedFilePath = exportedFilePath,
        qaPassed = qaPassed
    )
}

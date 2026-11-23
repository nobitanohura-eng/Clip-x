package com.clipai.v2.data.models

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clipai.v2.data.local.ClipAiDatabase
import com.clipai.v2.data.local.ModelStateEntity
import com.clipai.v2.data.prefs.PreferencesRepository
import com.clipai.v2.domain.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Real WorkManager resumable model downloader:
 * - Downloads into `.part` file using HTTP `Range` resume
 * - Supports optional user-supplied Hugging Face read token from EncryptedSharedPreferences
 * - Computes SHA-256 digest before atomic rename into final model path
 * - One download at a time enforced via unique work name in WorkManager
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val UNIQUE_WORK_NAME = "clipai_model_download_queue"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val descriptor = ModelCatalog.findById(modelId) ?: return@withContext Result.failure()
        val db = ClipAiDatabase.getInstance(applicationContext)
        val modelDao = db.modelStateDao()
        val prefs = PreferencesRepository(applicationContext)

        val finalFile = ModelCatalog.targetFile(applicationContext, descriptor)
        val partFile = ModelCatalog.partFile(applicationContext, descriptor)

        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        modelDao.upsert(
            ModelStateEntity(
                modelId = modelId,
                status = DownloadStatus.DOWNLOADING.name,
                downloadedBytes = existingBytes,
                totalBytes = descriptor.sizeBytes,
                localFilePath = null,
                verifiedSha256 = false,
                lastError = null,
                updatedAtMs = System.currentTimeMillis()
            )
        )

        try {
            val reqBuilder = Request.Builder().url(descriptor.downloadUrl)
            if (existingBytes > 0L) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
            }
            if (descriptor.gatedHuggingFace) {
                val token = prefs.getUserHfToken()
                if (token.isNullOrBlank()) {
                    throw IllegalStateException(
                        "Hugging Face read token missing. Accept model terms and save your personal token in Model Manager."
                    )
                }
                reqBuilder.header("Authorization", "Bearer $token")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    val hint = when (response.code) {
                        401, 403 -> "Access denied (HTTP ${response.code}). Accept Hugging Face terms and verify your read token."
                        404 -> "Model file not found at configured URL (HTTP 404)."
                        416 -> {
                            // Range invalid -> restart clean
                            partFile.delete()
                            existingBytes = 0L
                            "Partial file reset — retry download."
                        }
                        else -> "Download failed with HTTP ${response.code}."
                    }
                    throw IllegalStateException(hint)
                }

                val isResumed = response.code == 206
                val body = response.body ?: throw IllegalStateException("Empty download response body.")
                val contentLength = body.contentLength()
                val totalBytes = when {
                    isResumed && contentLength > 0 -> existingBytes + contentLength
                    contentLength > 0 -> contentLength
                    else -> descriptor.sizeBytes
                }

                if (!isResumed) {
                    existingBytes = 0L
                }

                var downloaded = existingBytes
                var lastPersistMs = 0L

                body.byteStream().use { input ->
                    FileOutputStream(partFile, isResumed).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) {
                                modelDao.upsert(
                                    ModelStateEntity(
                                        modelId = modelId,
                                        status = DownloadStatus.PAUSED.name,
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes,
                                        localFilePath = null,
                                        verifiedSha256 = false,
                                        lastError = null,
                                        updatedAtMs = System.currentTimeMillis()
                                    )
                                )
                                return@withContext Result.success()
                            }

                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastPersistMs >= 450L) {
                                lastPersistMs = now
                                modelDao.upsert(
                                    ModelStateEntity(
                                        modelId = modelId,
                                        status = DownloadStatus.DOWNLOADING.name,
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes,
                                        localFilePath = null,
                                        verifiedSha256 = false,
                                        lastError = null,
                                        updatedAtMs = now
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }

                // SHA-256 verification before promoting `.part` to final file
                modelDao.upsert(
                    ModelStateEntity(
                        modelId = modelId,
                        status = DownloadStatus.VERIFYING_SHA256.name,
                        downloadedBytes = downloaded,
                        totalBytes = totalBytes,
                        localFilePath = null,
                        verifiedSha256 = false,
                        lastError = null,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )

                val actualSha256 = computeSha256(partFile)
                val expected = descriptor.expectedSha256
                if (!expected.isNullOrBlank() && !actualSha256.equals(expected, ignoreCase = true)) {
                    partFile.delete()
                    throw IllegalStateException("SHA-256 integrity mismatch — corrupt file removed.")
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                modelDao.upsert(
                    ModelStateEntity(
                        modelId = modelId,
                        status = DownloadStatus.INSTALLED.name,
                        downloadedBytes = finalFile.length(),
                        totalBytes = finalFile.length(),
                        localFilePath = finalFile.absolutePath,
                        verifiedSha256 = true,
                        lastError = null,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )

                Result.success()
            }
        } catch (t: Throwable) {
            modelDao.upsert(
                ModelStateEntity(
                    modelId = modelId,
                    status = DownloadStatus.FAILED.name,
                    downloadedBytes = if (partFile.exists()) partFile.length() else 0L,
                    totalBytes = descriptor.sizeBytes,
                    localFilePath = null,
                    verifiedSha256 = false,
                    lastError = t.message ?: "Download failed unexpectedly.",
                    updatedAtMs = System.currentTimeMillis()
                )
            )
            Result.failure()
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

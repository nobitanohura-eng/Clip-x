package com.clipai.v2.ml

import com.clipai.v2.domain.ClipCandidate
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real LiteRT-LM 0.18.0 on-device LLM re-ranker for Gemma 4 E2B / Gemma 3 1B `.litertlm`.
 * - Only runs on TOP <= 10 candidates (never every candidate window)
 * - Attempts GPU delegate first with automatic CPU fallback
 * - Always closes Engine & Conversation in finally so Gemma never stays resident with Whisper
 * - Falls back cleanly to deterministic rule-based scores on any error
 */
object LiteRtLmReranker {

    suspend fun rerankTopCandidates(
        modelFile: File,
        candidates: List<ClipCandidate>,
        onStatus: (String) -> Unit
    ): List<ClipCandidate> = withContext(Dispatchers.IO) {
        if (!modelFile.exists() || modelFile.length() <= 0L || candidates.isEmpty()) {
            return@withContext candidates
        }

        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        onStatus("Loading ${modelFile.name} (first run warm-up)...")

        var engine: Engine? = null
        try {
            engine = initializeWithGpuFallback(modelFile)
            val topTen = candidates.sortedByDescending { it.scores.total }.take(10)

            val prompt = buildString {
                appendLine("You are ranking short vertical video clip hooks. Reply with one line per clip in the exact format:")
                appendLine("ID|DELTA(-8..+8)|SHORT_TITLE(max 7 words)")
                appendLine("Never invent speaker quotes. Only write concise editorial titles.")
                topTen.forEach { c ->
                    appendLine("${c.id}|rule=${c.scores.total}|hook=${c.verbatimHook.replace("\n", " ")}")
                }
            }

            onStatus("Re-ranking top ${topTen.size} candidates on-device...")
            val rawResponse = StringBuilder()

            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(
                    "Respond strictly with pipe-separated lines ID|DELTA|TITLE and nothing else."
                )
            )

            engine.createConversation(conversationConfig).use { conversation ->
                conversation.sendMessageAsync(prompt).collect { chunk ->
                    rawResponse.append(chunk)
                }
            }

            val adjustments = parseRerankOutput(rawResponse.toString())
            if (adjustments.isEmpty()) return@withContext candidates

            candidates.map { clip ->
                val adj = adjustments[clip.id] ?: return@map clip
                val nextTotal = (clip.scores.total + adj.delta).coerceIn(20, 99)
                clip.copy(
                    scores = clip.scores.copy(
                        total = nextTotal,
                        llmDelta = adj.delta,
                        llmNote = "LiteRT-LM refined"
                    ),
                    titleHook = adj.title.ifBlank { clip.titleHook }
                )
            }
        } catch (_: Throwable) {
            // Honest fallback: return deterministic rule-based scores untouched
            candidates
        } finally {
            runCatching { engine?.close() }
        }
    }

    private fun initializeWithGpuFallback(modelFile: File): Engine {
        val gpuConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            cacheDir = modelFile.parent
        )
        return runCatching {
            Engine(gpuConfig).also { it.initialize() }
        }.getOrElse {
            val cpuConfig = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = modelFile.parent
            )
            Engine(cpuConfig).also { it.initialize() }
        }
    }

    private data class RerankAdjustment(val delta: Int, val title: String)

    private fun parseRerankOutput(text: String): Map<String, RerankAdjustment> {
        val map = mutableMapOf<String, RerankAdjustment>()
        text.lineSequence().forEach { line ->
            val parts = line.trim().split("|")
            if (parts.size >= 3) {
                val id = parts[0].trim()
                val delta = parts[1].trim().toIntOrNull()?.coerceIn(-8, 8) ?: 0
                val title = parts[2].trim().take(60)
                if (id.isNotBlank()) {
                    map[id] = RerankAdjustment(delta, title)
                }
            }
        }
        return map
    }
}

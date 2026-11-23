package com.clipai.v2.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Typeface
import com.clipai.v2.domain.CaptionLine
import com.clipai.v2.domain.HookMode

/**
 * Renders transparent ARGB_8888 overlay bitmaps for Media3 OverlayEffect:
 * - Top-third Hook Overlay (0.0s..3.0s) with contrast scrim and explicit TITLE header when editorial
 * - Karaoke word-by-word captions (~4.2% video height, 12% safe bottom margin,
 *   active word yellow #FACC15, rest white #FFFFFF, bold black outline, Devanagari-safe via Skia/HarfBuzz)
 */
class CaptionOverlayBitmapRenderer(
    private val width: Int,
    private val height: Int,
    private val clipStartSec: Double,
    private val hookText: String,
    private val hookMode: HookMode,
    private val captionLines: List<CaptionLine>
) {
    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas: Canvas = Canvas(bitmap)

    private val captionFontSize = height * 0.042f
    private val hookFontSize = height * 0.036f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = captionFontSize
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = captionFontSize
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (captionFontSize * 0.18f).coerceAtLeast(4f)
        color = Color.BLACK
    }

    private val hookFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = hookFontSize
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val hookStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = hookFontSize
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (hookFontSize * 0.16f).coerceAtLeast(4f)
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = height * 0.016f
        color = Color.parseColor("#FCD34D")
        textAlign = Paint.Align.CENTER
    }

    private val topScrimPaint = Paint().apply {
        shader = LinearGradient(
            0f,
            0f,
            0f,
            height * 0.30f,
            Color.argb(180, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
    }

    private val bottomScrimPaint = Paint().apply {
        shader = LinearGradient(
            0f,
            height * 0.72f,
            0f,
            height.toFloat(),
            Color.TRANSPARENT,
            Color.argb(195, 0, 0, 0),
            Shader.TileMode.CLAMP
        )
    }

    fun renderAtRelativeTimeSec(relTimeSec: Double): Bitmap {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val absTimeSec = clipStartSec + relTimeSec

        // 1. Top-Third Hook Overlay (strictly first 3.0s)
        if (relTimeSec in 0.0..3.0 && hookText.isNotBlank()) {
            val alpha = if (relTimeSec > 2.65) {
                (((3.0 - relTimeSec) / 0.35).coerceIn(0.0, 1.0) * 255).toInt()
            } else {
                255
            }
            topScrimPaint.alpha = alpha
            canvas.drawRect(0f, 0f, width.toFloat(), height * 0.30f, topScrimPaint)

            val renderedHook = if (hookMode == HookMode.VERBATIM_QUOTE) "“$hookText”" else hookText
            val lines = wrapWords(renderedHook, hookFillPaint, width * 0.84f).take(3)
            val startY = height * 0.14f

            if (hookMode == HookMode.MARKED_TITLE) {
                labelPaint.alpha = alpha
                canvas.drawText("TITLE", width / 2f, startY - hookFontSize * 0.9f, labelPaint)
            }

            hookFillPaint.alpha = alpha
            hookStrokePaint.alpha = alpha
            val lineStep = hookFontSize * 1.24f
            lines.forEachIndexed { idx, line ->
                val y = startY + idx * lineStep
                canvas.drawText(line, width / 2f, y, hookStrokePaint)
                canvas.drawText(line, width / 2f, y, hookFillPaint)
            }
        }

        // 2. Karaoke Captions (12% safe bottom margin)
        val activeLine = captionLines.firstOrNull { absTimeSec in (it.startSec - 0.04)..(it.endSec + 0.12) }
        if (activeLine != null) {
            canvas.drawRect(0f, height * 0.72f, width.toFloat(), height.toFloat(), bottomScrimPaint)

            val spaceW = fillPaint.measureText(" ")
            val tokens = activeLine.words.map { word ->
                val text = word.displayText
                val isActive = absTimeSec in (word.startSec - 0.04)..(word.endSec + 0.08)
                Triple(text, fillPaint.measureText(text), isActive)
            }

            val totalW = tokens.sumOf { it.second.toDouble() }.toFloat() +
                spaceW * (tokens.size - 1).coerceAtLeast(0)
            val maxAllowedW = width * 0.88f
            val scale = if (totalW > maxAllowedW) maxAllowedW / totalW else 1f
            val baselineY = height * 0.88f - captionFontSize * 0.55f

            canvas.save()
            canvas.translate(width / 2f, baselineY)
            canvas.scale(scale, scale)

            var cursorX = -totalW / 2f
            for ((text, w, isActive) in tokens) {
                canvas.drawText(text, cursorX, 0f, strokePaint)
                fillPaint.color = if (isActive) Color.parseColor("#FACC15") else Color.WHITE
                canvas.drawText(text, cursorX, 0f, fillPaint)
                cursorX += w + spaceW
            }
            canvas.restore()
        }

        return bitmap
    }

    private fun wrapWords(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                out.add(current)
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

package com.codersergg.video

import com.codersergg.model.video.BackgroundSpan
import com.codersergg.model.video.CueItem
import com.codersergg.model.video.RenderEffects
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min

object BackgroundSpansFfmpeg {

    data class Prepared(
        val inputArgs: List<String>,
        val filterComplex: String,
        val videoOutLabel: String,
        val inputsCount: Int
    )

    /**
     * Готовит входы и filter_complex для ffmpeg, используя список фоновых спанов.
     *
     * @param spans         список фоновых изображений, привязанных к индексам реплик (anchorIdx)
     * @param cues          список таймингов (старт/конец) для каждой реплики
     * @param width         ширина итогового видео
     * @param height        высота итогового видео
     * @param fps           частота кадров
     * @param workDir       рабочая директория для временных файлов
     * @param effects       эффекты рендера (переходы и т.п.)
     * @param downloader    suspend-функция скачивания файла: (url, dest) -> Unit
     */
    suspend fun prepare(
        spans: List<BackgroundSpan>,
        cues: List<CueItem>,
        width: Int,
        height: Int,
        fps: Int,
        workDir: Path,
        effects: RenderEffects? = null,
        downloader: suspend (url: String, dest: Path) -> Unit
    ): Prepared? {
        if (spans.isEmpty() || cues.isEmpty()) return null

        val sorted = spans.sortedBy { it.anchorIdx }

        val durationsMs = buildList {
            for (i in sorted.indices) {
                val startIdx = sorted[i].anchorIdx.coerceIn(0, cues.lastIndex)
                val tStart = if (i == 0) cues.first().startMs else cues[startIdx].startMs
                val tEnd = if (i < sorted.lastIndex) {
                    val nextIdx = sorted[i + 1].anchorIdx.coerceIn(0, cues.lastIndex)
                    cues[nextIdx].startMs
                } else cues.last().endMs
                add(max(0L, tEnd - tStart))
            }
        }
        if (durationsMs.all { it <= 0 }) return null

        val inputArgs = mutableListOf<String>()
        val filters = StringBuilder()
        val labels = mutableListOf<String>()
        var videoInputs = 0

        for (i in sorted.indices) {
            val span = sorted[i]
            val durMs = durationsMs[i]
            if (durMs <= 0L) continue

            val imagePath = downloadImage(span.imageUrl, workDir, downloader)

            inputArgs += listOf(
                "-loop", "1",
                "-framerate", "$fps",
                "-t", "%.3f".format(durMs / 1000.0),
                "-i", imagePath.toAbsolutePath().toString()
            )

            val inLabel = "[$videoInputs:v]"
            val outLabel = "[v$videoInputs]"

            filters.append(
                inLabel +
                        "scale=w=$width:h=$height:force_original_aspect_ratio=decrease," +
                        "pad=$width:$height:(ow-iw)/2:(oh-ih)/2:color=black," +
                        "fps=$fps,format=yuv420p,setsar=1" +
                        outLabel + ";"
            )

            labels += outLabel
            videoInputs++
        }

        if (videoInputs == 0) return null

        if (videoInputs == 1) {
            filters.append("${labels.first()}copy[bg];")
            return Prepared(inputArgs, filters.toString(), "[bg]", videoInputs)
        }

        val trType = effects?.transition?.type ?: "fade"
        val trDurSec = (effects?.transition?.durationSec ?: 0.40).coerceAtLeast(0.05)
        val trCenter = effects?.transition?.centerOnBoundary ?: true

        var prevOutLabel = labels[0]
        var prevOutDur = durationsMs[0] / 1000.0

        for (i in 1 until videoInputs) {
            val curLabel = labels[i]
            val curDur = durationsMs[i] / 1000.0

            val d = min(trDurSec, min(prevOutDur, curDur).coerceAtLeast(0.05))
            val rawOffset = if (trCenter) prevOutDur - d / 2.0 else prevOutDur - d
            val offset = max(0.0, rawOffset)

            val outLabel = if (i == videoInputs - 1) "[bg]" else "[x$i]"
            filters.append(
                "$prevOutLabel$curLabel" +
                        "xfade=transition=${trType}:duration=${fmt(d)}:offset=${fmt(offset)}" +
                        outLabel + ";"
            )

            prevOutLabel = outLabel
            prevOutDur = prevOutDur + curDur - d
        }

        return Prepared(inputArgs, filters.toString(), "[bg]", videoInputs)
    }

    private suspend fun downloadImage(
        url: String,
        workDir: Path,
        downloader: suspend (url: String, dest: Path) -> Unit
    ): Path {
        val file = Files.createTempFile(workDir, "bg_", ".img")
        downloader(url, file)
        return file
    }

    private fun fmt(v: Double): String = "%.3f".format(v)
}

package com.codersergg.video

import com.codersergg.model.video.BackgroundSpan
import com.codersergg.model.video.CueItem
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max

object BackgroundSpansFfmpeg {

    data class Prepared(
        val inputArgs: List<String>,
        val filterComplex: String,
        val videoOutLabel: String,
        val inputsCount: Int
    )

    fun prepare(
        spans: List<BackgroundSpan>,
        cues: List<CueItem>,
        width: Int,
        height: Int,
        fps: Int,
        workDir: Path,
        http: HttpClient
    ): Prepared? {
        if (spans.isEmpty() || cues.isEmpty()) return null

        val sorted = spans.sortedBy { it.anchorIdx }
        val inputArgs = mutableListOf<String>()
        val filters = StringBuilder()
        val labels = mutableListOf<String>()
        var videoInputs = 0

        for ((i, span) in sorted.withIndex()) {
            val startIdx = span.anchorIdx.coerceIn(0, cues.lastIndex)
            val tStart = cues[startIdx].startMs
            val tEnd = if (i < sorted.lastIndex) {
                val nextIdx = sorted[i + 1].anchorIdx.coerceIn(0, cues.lastIndex)
                cues[nextIdx].startMs
            } else {
                cues.last().endMs
            }
            val durMs = max(0L, tEnd - tStart)
            if (durMs <= 0L) continue

            val imagePath = downloadImage(span.imageUrl, workDir, http)

            inputArgs += listOf(
                "-loop", "1",
                "-t", "%.3f".format(durMs / 1000.0),
                "-i", imagePath.toAbsolutePath().toString()
            )

            val inLabel = "[$videoInputs:v]"
            val outLabel = "[v$videoInputs]"
            filters.append(
                inLabel + "scale=w=$width:h=-1:force_original_aspect_ratio=decrease," +
                        "pad=$width:$height:(ow-iw)/2:(oh-ih)/2:color=black," +
                        "format=yuv420p,fps=$fps,setsar=1" +
                        "$outLabel;"
            )
            labels += outLabel
            videoInputs++
        }

        if (videoInputs == 0) return null

        val concatOut = "[bg]"
        filters.append(labels.joinToString(separator = ""))
            .append("concat=n=$videoInputs:v=1:a=0$concatOut;")

        return Prepared(
            inputArgs = inputArgs,
            filterComplex = filters.toString(),
            videoOutLabel = concatOut,
            inputsCount = videoInputs
        )
    }

    private fun downloadImage(url: String, workDir: Path, http: HttpClient): Path {
        val req = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        require(resp.statusCode() in 200..299) { "Failed to download image: $url, status=${resp.statusCode()}" }
        val file = Files.createTempFile(workDir, "bg_", ".img")
        Files.write(file, resp.body())
        return file
    }
}

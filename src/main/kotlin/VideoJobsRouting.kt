package com.codersergg

import com.codersergg.model.video.*
import com.codersergg.video.AssBuilder
import com.codersergg.video.BackgroundSpansFfmpeg
import com.codersergg.video.VideoJob
import com.codersergg.video.VideoJobManager
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import kotlin.math.roundToInt

private val json = Json { ignoreUnknownKeys = true }

fun Application.configureVideoJobsRouting() {
    val log = LoggerFactory.getLogger("VideoJobs")
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    routing {
        post("/video/jobs") {
            val req = call.receive<CreateVideoJobRequest>()
            val jobId = UUID.randomUUID().toString()
            val tmpDir = File("/downloads/${System.currentTimeMillis()}-vr-$jobId").apply { mkdirs() }
            val job = VideoJob(id = jobId, workDir = tmpDir)
            VideoJobManager.create(job)

            appScope.launch {
                job.status = JobStatus.RUNNING
                val client = HttpClient(CIO)

                try {
                    val audioFile = File(tmpDir, "audio.mp3")
                    client.get(req.render.audioUrl).apply {
                        if (!status.isSuccess()) error("Audio download failed: $status")
                        bodyAsChannel().copyTo(audioFile.outputStream())
                    }

                    val cues: EpisodeCuesPayload = req.render.cues ?: run {
                        val cuesUrl = requireNotNull(req.render.cuesUrl) { "cuesUrl is required when cues is null" }
                        val resp = client.get(cuesUrl) { accept(ContentType.Application.Json) }
                        if (!resp.status.isSuccess()) {
                            val head = resp.bodyAsText().take(200).replace("\n", " ")
                            error("cuesUrl fetch failed: ${resp.status}; head='$head'")
                        }
                        val ct = resp.headers[HttpHeaders.ContentType].orEmpty()
                        val txt = resp.bodyAsText()
                        val looksLikeJson = txt.firstOrNull() == '{'
                        if (!ct.contains("application/json") || !looksLikeJson) {
                            log.error(
                                "cuesUrl returned non-JSON. contentType='{}', head='{}'",
                                ct, txt.take(200).replace("\n", " ")
                            )
                            error("cuesUrl returned non-JSON (expected application/json)")
                        }
                        json.decodeFromString(EpisodeCuesPayload.serializer(), txt)
                    }

                    val lines = requireNotNull(req.render.lines) { "render.lines is required" }
                    require(cues.items.size == lines.size) {
                        "lines count (${lines.size}) must match cues (${cues.items.size})"
                    }

                    // уважаем присланное разрешение
                    val w = req.render.resolution.width
                    val h = req.render.resolution.height
                    val isVertical = h > w
                    val fps = req.render.fps

                    val resolvedLayout: TextLayout =
                        req.render.layout ?: if (isVertical) TextLayout.VERTICAL_ONE else TextLayout.BLUR_UNDERLAY

                    val ass = File(tmpDir, "overlay.ass")

                    val panelSpec = req.render.panel ?: PanelSpec()
                    val panelWidthPx = ((w * panelSpec.widthPct).roundToInt()).coerceAtLeast(200)
                    val visibleLinesPanel = req.render.visibleLines ?: 9

                    when (resolvedLayout) {
                        TextLayout.VERTICAL_ONE -> {
                            AssBuilder.buildAssFileVerticalInstant(
                                target = ass,
                                cues = cues,
                                lines = lines,
                                style = req.render.overlayStyle,
                                width = w,
                                height = h,
                                metaHeader = req.render.metaHeader
                            )
                        }

                        TextLayout.PANEL_LEFT -> {
                            AssBuilder.buildAssFilePanelLeftReplace(
                                target = ass,
                                cues = cues,
                                lines = lines,
                                style = req.render.overlayStyle,
                                width = w,
                                height = h,
                                panelWidthPx = panelWidthPx,
                                panelInnerPaddingPx = panelSpec.innerPaddingPx,
                                visibleLines = visibleLinesPanel,
                                metaHeader = req.render.metaHeader
                            )
                        }

                        else -> {
                            if (isVertical) {
                                AssBuilder.buildAssFileVerticalInstant(
                                    target = ass,
                                    cues = cues,
                                    lines = lines,
                                    style = req.render.overlayStyle,
                                    width = w,
                                    height = h,
                                    metaHeader = req.render.metaHeader
                                )
                            } else {
                                AssBuilder.buildAssFile(
                                    target = ass,
                                    cues = cues,
                                    lines = lines,
                                    style = req.render.overlayStyle,
                                    width = w,
                                    height = h,
                                    metaHeader = req.render.metaHeader
                                )
                            }
                        }
                    }

                    val requestedHex = req.render.background.colorHex
                    fun isVeryDark(hex: String?): Boolean {
                        if (hex == null) return true
                        val hsh = hex.removePrefix("#")
                        if (hsh.length < 6) return true
                        val r = hsh.substring(0, 2).toInt(16)
                        val g = hsh.substring(2, 4).toInt(16)
                        val b = hsh.substring(4, 6).toInt(16)
                        return (r + g + b) <= 0x60
                    }

                    val defaultBg = "#6A6A6A"
                    val finalBgHex =
                        if (req.render.background.imageUrl == null && isVeryDark(requestedHex)) defaultBg else (requestedHex
                            ?: defaultBg)
                    val bgPadFF = "0x${finalBgHex.removePrefix("#")}"
                    log.info("VideoRender: output={}x{}, fps={}, bgColor=#{}", w, h, fps, finalBgHex.removePrefix("#"))

                    fun escForFilterPath(p: String): String =
                        p.replace("\\", "\\\\").replace(":", "\\:").replace(",", "\\,")

                    val assPathEsc = escForFilterPath(ass.absolutePath)

                    val outFile = File(tmpDir, "out.mp4")
                    val cmd = mutableListOf<String>().apply {
                        add("ffmpeg")
                        addAll(listOf("-hide_banner", "-loglevel", "info", "-stats"))
                        add("-y")
                    }

                    val needPanel = (resolvedLayout == TextLayout.PANEL_LEFT)

                    val spans = req.render.backgroundSpans
                        .distinctBy { it.anchorIdx }
                        .sortedBy { it.anchorIdx }
                        .filter { it.anchorIdx in 0..cues.items.lastIndex }

                    val panelColorHex = (req.render.panel?.background?.colorHex ?: "#0E0F13").removePrefix("#")
                    val panelOpacity = (req.render.panel?.background?.opacity ?: 1.0).coerceIn(0.0, 1.0)
                    fun colorForDrawbox(hexNoHash: String, alpha: Double): String =
                        "0x$hexNoHash@${alpha.coerceIn(0.0, 1.0)}"

                    if (spans.isNotEmpty()) {
                        val prep = BackgroundSpansFfmpeg.prepare(
                            spans = spans,
                            cues = cues.items,
                            width = w,
                            height = h,
                            fps = fps,
                            workDir = tmpDir.toPath(),
                            effects = req.render.effects
                        ) { url, dest ->
                            client.get(url).apply {
                                if (!status.isSuccess()) error("download failed: $status")
                                bodyAsChannel().copyTo(dest.toFile().outputStream())
                            }
                        }

                        if (prep != null) {
                            cmd.addAll(prep.inputArgs)
                            cmd.addAll(listOf("-i", audioFile.absolutePath))

                            val filter = buildString {
                                append(prep.filterComplex)
                                if (needPanel) {
                                    val color = colorForDrawbox(panelColorHex, panelOpacity)
                                    append("${prep.videoOutLabel}drawbox=x=0:y=0:w=$panelWidthPx:h=$h:color=$color:t=fill[pnl];")
                                    append("[pnl]subtitles='${assPathEsc}':[vout];")
                                } else {
                                    append("${prep.videoOutLabel}subtitles='${assPathEsc}':[vout];")
                                }
                            }

                            val audioInputIndex = prep.inputsCount
                            cmd.addAll(
                                listOf(
                                    "-filter_complex", filter,
                                    "-map", "[vout]", "-map", "$audioInputIndex:a",
                                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                                    "-c:a", "aac", "-b:a", "${req.render.audioBitrateKbps}k",
                                    "-movflags", "+faststart", "-shortest",
                                    outFile.absolutePath
                                )
                            )
                        } else {
                            val totalSec = (cues.totalMs.coerceAtLeast(1)).toDouble() / 1000.0
                            val inputs = mutableListOf<String>()
                            if (req.render.background.imageUrl != null) {
                                val bg = File(tmpDir, "bg.jpg")
                                client.get(requireNotNull(req.render.background.imageUrl)).apply {
                                    if (!status.isSuccess()) error("Background download failed: $status")
                                    bodyAsChannel().copyTo(bg.outputStream())
                                }
                                inputs += listOf(
                                    "-loop",
                                    "1",
                                    "-t",
                                    "%.3f".format(totalSec),
                                    "-i",
                                    bg.absolutePath
                                ) // #0
                            } else {
                                inputs += listOf(
                                    "-f", "lavfi", "-t", "%.3f".format(totalSec),
                                    "-i", "color=c=$bgPadFF:s=${w}x$h:r=$fps"
                                )
                            }
                            inputs += listOf("-i", audioFile.absolutePath) // #1
                            cmd.addAll(inputs)

                            val vf = buildString {
                                append("scale=w=$w:h=$h:force_original_aspect_ratio=decrease,")
                                append("pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=$bgPadFF,")
                                if (needPanel) {
                                    val color = colorForDrawbox(panelColorHex, panelOpacity)
                                    append("drawbox=x=0:y=0:w=$panelWidthPx:h=$h:color=$color:t=fill,")
                                }
                                append("format=yuv420p,fps=$fps,subtitles='${assPathEsc}'")
                            }

                            cmd.addAll(
                                listOf(
                                    "-vf", vf,
                                    "-map", "0:v", "-map", "1:a",
                                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                                    "-c:a", "aac", "-b:a", "${req.render.audioBitrateKbps}k",
                                    "-movflags", "+faststart", "-shortest",
                                    outFile.absolutePath
                                )
                            )
                        }
                    } else {
                        val totalSec = (cues.totalMs.coerceAtLeast(1)).toDouble() / 1000.0
                        val inputs = mutableListOf<String>()
                        if (req.render.background.imageUrl != null) {
                            val bg = File(tmpDir, "bg.jpg")
                            client.get(requireNotNull(req.render.background.imageUrl)).apply {
                                if (!status.isSuccess()) error("Background download failed: $status")
                                bodyAsChannel().copyTo(bg.outputStream())
                            }
                            inputs += listOf("-loop", "1", "-t", "%.3f".format(totalSec), "-i", bg.absolutePath) // #0
                        } else {
                            inputs += listOf(
                                "-f", "lavfi", "-t", "%.3f".format(totalSec),
                                "-i", "color=c=$bgPadFF:s=${w}x$h:r=$fps"
                            )
                        }
                        inputs += listOf("-i", audioFile.absolutePath) // #1
                        cmd.addAll(inputs)

                        val vf = buildString {
                            append("scale=w=$w:h=$h:force_original_aspect_ratio=decrease,")
                            append("pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=$bgPadFF,")
                            if (needPanel) {
                                val color = colorForDrawbox(panelColorHex, panelOpacity)
                                append("drawbox=x=0:y=0:w=$panelWidthPx:h=$h:color=$color:t=fill,")
                            }
                            append("format=yuv420p,fps=$fps,subtitles='${assPathEsc}'")
                        }

                        cmd.addAll(
                            listOf(
                                "-vf", vf,
                                "-map", "0:v", "-map", "1:a",
                                "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                                "-c:a", "aac", "-b:a", "${req.render.audioBitrateKbps}k",
                                "-movflags", "+faststart", "-shortest",
                                outFile.absolutePath
                            )
                        )
                    }

                    log.info("VideoRender: ffmpeg command: {}", cmd.joinToString(" "))

                    val start = System.currentTimeMillis()
                    val p = ProcessBuilder(cmd).directory(tmpDir).redirectErrorStream(true).start()
                    val ffout = p.inputStream.bufferedReader().readText()
                    val code = p.waitFor()
                    val took = System.currentTimeMillis() - start

                    runCatching { File(tmpDir, "ffmpeg.log").writeText(ffout) }
                    runCatching {
                        ProcessBuilder(
                            "ffmpeg", "-y", "-ss", "2", "-i", outFile.absolutePath, "-frames:v", "1",
                            File(tmpDir, "debug_frame_2s.png").absolutePath
                        ).directory(tmpDir).redirectErrorStream(true).start().waitFor()
                    }

                    if (code != 0) {
                        log.error("ffmpeg failed for job={} code={} out=\n{}", jobId, code, ffout)
                        VideoJobManager.fail(jobId, "ffmpeg exit=$code")
                    } else {
                        VideoJobManager.complete(jobId, outFile, took)
                    }
                } catch (e: Exception) {
                    log.error("job {} failed", jobId, e)
                    VideoJobManager.fail(jobId, e.message ?: "unknown error")
                } finally {
                    runCatching { client.close() }
                }
            }

            call.respond(VideoJobResponse(jobId, JobStatus.QUEUED))
        }

        get("/video/jobs/{id}") {
            val id = call.parameters["id"]!!
            val job = VideoJobManager.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(VideoJobResponse(job.id, job.status, job.message, job.durationMs))
        }

        get("/video/jobs/{id}/file") {
            val id = call.parameters["id"]!!
            val job = VideoJobManager.get(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (job.status != JobStatus.SUCCEEDED || job.outputFile?.exists() != true) {
                return@get call.respond(HttpStatusCode.BadRequest, "job not finished")
            }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"episode-$id.mp4\"")
            call.respondFile(job.outputFile!!)
        }
    }
}

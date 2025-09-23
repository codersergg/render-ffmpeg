package com.codersergg

import com.codersergg.model.CreateVideoJobRequest
import com.codersergg.model.EpisodeCuesPayload
import com.codersergg.model.JobStatus
import com.codersergg.model.VideoJobResponse
import com.codersergg.video.AssBuilder
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
import java.util.UUID

fun Application.configureVideoJobsRouting() {
    val log = LoggerFactory.getLogger("VideoJobs")
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

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
                    // 1) audio
                    val audioFile = File(tmpDir, "audio.mp3")
                    client.get(req.render.audioUrl).apply {
                        if (!status.isSuccess()) error("Audio download failed: $status")
                        bodyAsChannel().copyTo(audioFile.outputStream())
                    }

                    // 2) cues
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

                    require(cues.items.size == req.render.lines.size) {
                        "lines count (${req.render.lines.size}) must match cues (${cues.items.size})"
                    }

                    // 3) Размер кадра: по умолчанию горизонталь; вертикаль только по флагу
                    val isVertical = req.render.vertical
                    val w = if (isVertical) 1080 else 1920
                    val h = if (isVertical) 1920 else 1080
                    val fps = req.render.fps
                    val totalSec = (cues.totalMs.coerceAtLeast(1)).toDouble() / 1000.0

                    // 4) .ass под финальный размер + разная логика горизонт/вертикаль
                    val ass = File(tmpDir, "overlay.ass")
                    if (isVertical) {
                        AssBuilder.buildAssFileVerticalInstant(
                            target = ass,
                            cues = cues,
                            lines = req.render.lines,
                            style = req.render.overlayStyle,
                            width = w,
                            height = h
                        )
                    } else {
                        AssBuilder.buildAssFile(
                            target = ass,
                            cues = cues,
                            lines = req.render.lines,
                            style = req.render.overlayStyle,
                            width = w,
                            height = h
                        )
                    }

                    // 5) Фон (безопасный дефолт, если слишком тёмный и нет картинки)
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
                    val finalBgHex = if (req.render.background.imageUrl == null && isVeryDark(requestedHex)) {
                        defaultBg
                    } else {
                        requestedHex ?: defaultBg
                    }
                    val bgPadHex = finalBgHex.removePrefix("#")
                    val bgPadFF = "0x$bgPadHex"
                    log.info("VideoRender: output={}x{}, fps={}, bgColor=#{}", w, h, fps, bgPadHex)

                    // 6) inputs
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
                            "-i", "color=c=$bgPadFF:s=${w}x$h:r=$fps" // #0
                        )
                    }
                    inputs += listOf("-i", audioFile.absolutePath) // #1

                    fun escForFilterPath(p: String): String =
                        p.replace("\\", "\\\\").replace(":", "\\:").replace(",", "\\,")

                    val assPath = escForFilterPath(ass.absolutePath)

                    // 7) фильтр: субтитры последними
                    val vf = listOf(
                        "scale=w=$w:h=-1:force_original_aspect_ratio=decrease",
                        "pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=$bgPadFF",
                        "format=yuv420p",
                        "fps=$fps",
                        "subtitles='${assPath}'"
                    ).joinToString(",")

                    // 8) рендер
                    val outFile = File(tmpDir, "out.mp4")
                    val cmd = mutableListOf<String>().apply {
                        add("ffmpeg")
                        addAll(listOf("-hide_banner", "-loglevel", "info", "-stats"))
                        add("-y")
                        addAll(inputs)
                        addAll(listOf("-vf", vf))
                        addAll(listOf("-map", "0:v", "-map", "1:a"))
                        addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p"))
                        addAll(listOf("-c:a", "aac", "-b:a", "${req.render.audioBitrateKbps}k"))
                        addAll(listOf("-movflags", "+faststart", "-shortest"))
                        add(outFile.absolutePath)
                    }

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
            call.respond(
                VideoJobResponse(
                    jobId = job.id,
                    status = job.status,
                    message = job.message,
                    durationMs = job.durationMs
                )
            )
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

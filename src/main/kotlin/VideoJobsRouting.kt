package com.codersergg

import com.codersergg.model.*
import com.codersergg.video.AssBuilder
import com.codersergg.video.VideoJob
import com.codersergg.video.VideoJobManager
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

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
                    val cues = req.render.cues ?: run {
                        val txt =
                            client.get(requireNotNull(req.render.cuesUrl) { "cuesUrl is required when cues is null" })
                                .bodyAsText()
                        Json.decodeFromString(EpisodeCuesPayload.serializer(), txt)
                    }
                    require(cues.items.size == req.render.lines.size) {
                        "lines count (${req.render.lines.size}) must match cues (${cues.items.size})"
                    }
                    val ass = File(tmpDir, "overlay.ass")
                    AssBuilder.buildAssFile(ass, cues, req.render.lines, req.render.overlayStyle)

                    val totalSec = (cues.totalMs.coerceAtLeast(1)).toDouble() / 1000.0
                    val w = req.render.resolution.width
                    val h = req.render.resolution.height
                    val fps = req.render.fps

                    val cmd = mutableListOf<String>().apply {
                        add("ffmpeg"); add("-y")
                        if (req.render.background.imageUrl != null) {
                            val bg = File(tmpDir, "bg.jpg")
                            client.get(req.render.background.imageUrl!!).apply {
                                if (!status.isSuccess()) error("Background download failed: $status")
                                bodyAsChannel().copyTo(bg.outputStream())
                            }
                            addAll(listOf("-loop", "1", "-t", "%.3f".format(totalSec), "-i", bg.absolutePath))
                            addAll(listOf("-i", audioFile.absolutePath))
                            addAll(
                                listOf(
                                    "-filter_complex",
                                    "[0:v]scale=w=$w:h=-1:force_original_aspect_ratio=decrease," +
                                            "pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=black,fps=$fps,format=yuv420p,subtitles=${ass.absolutePath}[v]"
                                )
                            )
                            addAll(listOf("-map", "[v]", "-map", "1:a"))
                        } else {
                            addAll(
                                listOf(
                                    "-f", "lavfi", "-t", "%.3f".format(totalSec), "-i",
                                    "color=c=${req.render.background.colorHex ?: "#000000"}:s=${w}x$h:r=$fps"
                                )
                            )
                            addAll(listOf("-i", audioFile.absolutePath))
                            addAll(listOf("-vf", "subtitles=${ass.absolutePath}"))
                        }
                        addAll(listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p"))
                        addAll(listOf("-c:a", "aac", "-b:a", "${req.render.audioBitrateKbps}k"))
                        addAll(listOf("-movflags", "+faststart"))
                        add(File(tmpDir, "out.mp4").absolutePath)
                    }

                    val start = System.currentTimeMillis()
                    val p = ProcessBuilder(cmd).directory(tmpDir).redirectErrorStream(true).start()
                    val ffout = p.inputStream.bufferedReader().readText()
                    val code = p.waitFor()
                    val took = System.currentTimeMillis() - start

                    if (code != 0) {
                        log.error("ffmpeg failed for job={} code={} out=\n{}", jobId, code, ffout)
                        VideoJobManager.fail(jobId, "ffmpeg exit=$code")
                    } else {
                        VideoJobManager.complete(jobId, File(tmpDir, "out.mp4"), took)
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

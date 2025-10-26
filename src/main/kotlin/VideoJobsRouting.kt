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
import io.ktor.server.plugins.*
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

                    fun probeDurationMs(file: File): Long {
                        val p = ProcessBuilder(
                            "ffprobe", "-v", "error",
                            "-select_streams", "a:0",
                            "-show_entries", "stream=nb_samples,sample_rate",
                            "-of", "default=noprint_wrappers=1:nokey=1",
                            file.absolutePath
                        ).redirectErrorStream(true).start()
                        val out = p.inputStream.bufferedReader().readLines()
                        if (p.waitFor() == 0) {
                            val nb = out.getOrNull(0)?.trim()?.toLongOrNull()
                            val sr = out.getOrNull(1)?.trim()?.toIntOrNull()
                            if (nb != null && sr != null && sr > 0) {
                                val num = nb * 1000L
                                return (num + sr / 2) / sr
                            }
                        }
                        val p2 = ProcessBuilder(
                            "ffprobe", "-v", "error",
                            "-show_entries", "format=duration",
                            "-of", "default=noprint_wrappers=1:nokey=1",
                            file.absolutePath
                        ).redirectErrorStream(true).start()
                        val out2 = p2.inputStream.bufferedReader().readText().trim()
                        p2.waitFor()
                        val sec = out2.toDoubleOrNull() ?: 0.0
                        return (sec * 1000.0).toLong()
                    }
                    val audioMs = probeDurationMs(audioFile)

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

                    val targetTotalMs = maxOf(cues.totalMs, audioMs)
                    val normalizedCues: EpisodeCuesPayload = if (cues.items.isNotEmpty()) {
                        val last = cues.items.last()
                        if (last.endMs < targetTotalMs) {
                            cues.copy(
                                items = cues.items.dropLast(1) + last.copy(endMs = targetTotalMs),
                                totalMs = targetTotalMs
                            )
                        } else {
                            if (cues.totalMs != targetTotalMs) cues.copy(totalMs = targetTotalMs) else cues
                        }
                    } else cues.copy(totalMs = targetTotalMs)

                    log.info(
                        "VideoRender: durations ms — audio={}, cues={}, target={}",
                        audioMs, cues.totalMs, targetTotalMs
                    )

                    val w = req.render.resolution.width
                    val h = req.render.resolution.height
                    val isVertical = h > w
                    val fps = req.render.fps

                    val resolvedLayout: TextLayout =
                        req.render.layout ?: if (isVertical) TextLayout.VERTICAL_ONE else TextLayout.BLUR_UNDERLAY

                    val ass = File(tmpDir, "overlay.ass")

                    val panelSpec = req.render.panel ?: PanelSpec()
                    val panelWidthPx = ((w * panelSpec.widthPct).roundToInt()).coerceIn(160, w - 80)
                    val visibleLinesPanel: Int? =
                        if (resolvedLayout == TextLayout.PANEL_LEFT) req.render.visibleLines else null

                    when (resolvedLayout) {
                        TextLayout.VERTICAL_ONE -> {
                            AssBuilder.buildAssFileVerticalInstant(
                                target = ass,
                                cues = normalizedCues,
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
                                cues = normalizedCues,
                                lines = lines,
                                style = req.render.overlayStyle,
                                width = w,
                                height = h,
                                panelWidthPx = panelWidthPx,
                                panelInnerPaddingPx = panelSpec.innerPaddingPx,
                                visibleLines = visibleLinesPanel
                                    ?: error("visibleLines is required when layout=PANEL_LEFT"),
                                metaHeader = req.render.metaHeader
                            )
                        }

                        else -> {
                            if (isVertical) {
                                AssBuilder.buildAssFileVerticalInstant(
                                    target = ass,
                                    cues = normalizedCues,
                                    lines = lines,
                                    style = req.render.overlayStyle,
                                    width = w,
                                    height = h,
                                    metaHeader = req.render.metaHeader
                                )
                            } else {
                                AssBuilder.buildAssFile(
                                    target = ass,
                                    cues = normalizedCues,
                                    lines = lines,
                                    style = req.render.overlayStyle,
                                    width = w,
                                    height = h,
                                    metaHeader = req.render.metaHeader
                                )
                            }
                        }
                    }

                    fun isVeryDark(hex: String?): Boolean {
                        if (hex == null) return true
                        val hsh = hex.removePrefix("#")
                        if (hsh.length < 6) return true
                        val r = hsh.substring(0, 2).toInt(16)
                        val g = hsh.substring(2, 4).toInt(16)
                        val b = hsh.substring(4, 6).toInt(16)
                        return (r + g + b) <= 0x60
                    }

                    val requestedHex = req.render.background.colorHex
                    val defaultBg = "#6A6A6A"
                    val finalBgHex =
                        if (req.render.background.imageUrl == null && isVeryDark(requestedHex)) defaultBg else (requestedHex
                            ?: defaultBg)
                    val bgPadFF = "0x${finalBgHex.removePrefix("#")}"
                    log.info("VideoRender: output={}x{}, fps={}, bgColor=#{}", w, h, fps, finalBgHex.removePrefix("#"))

                    fun escForFilterPath(p: String): String =
                        p.replace("\\", "\\\\")
                            .replace(":", "\\:")
                            .replace(",", "\\,")
                            .replace("'", "\\'")

                    val assPathEsc = escForFilterPath(ass.absolutePath)

                    val outFile = File(tmpDir, "out.mp4")
                    val cmd = mutableListOf<String>().apply {
                        add("ffmpeg")
                        addAll(listOf("-hide_banner", "-loglevel", "info", "-stats"))
                        add("-y")
                    }

                    val needPanel = (resolvedLayout == TextLayout.PANEL_LEFT)

                    val spansMutable = req.render.backgroundSpans
                        .asSequence()
                        .map { it.copy(anchorIdx = it.anchorIdx.coerceIn(0, normalizedCues.items.lastIndex)) }
                        .distinctBy { it.anchorIdx }
                        .sortedBy { it.anchorIdx }
                        .toMutableList()
                    if (spansMutable.isNotEmpty() && spansMutable.first().anchorIdx > 0) {
                        spansMutable.add(0, spansMutable.first().copy(anchorIdx = 0))
                    }
                    val spans = spansMutable.toList()

                    val panelColorHex = (req.render.panel?.background?.colorHex ?: "#0E0F13").removePrefix("#")
                    val panelOpacity = (req.render.panel?.background?.opacity ?: 1.0).coerceIn(0.0, 1.0)

                    val branding = req.render.branding
                    val wantBugLogo = branding.show &&
                            (branding.logoUrl?.isNotBlank() == true) &&
                            (branding.placement.uppercase().endsWith("_BUG"))

                    var logoPathEsc: String? = null
                    if (wantBugLogo) {
                        val logoFile = File(tmpDir, "branding-logo.png")
                        client.get(branding.logoUrl).apply {
                            if (!status.isSuccess()) error("logo download failed: $status")
                            bodyAsChannel().copyTo(logoFile.outputStream())
                        }
                        logoPathEsc = escForFilterPath(logoFile.absolutePath)
                    }

                    fun colorForDrawbox(hexNoHash: String, alpha: Double): String =
                        "0x$hexNoHash@${alpha.coerceIn(0.0, 1.0)}"

                    fun overlayXY(
                        b: BrandingSpec,
                        needPanel: Boolean,
                        panelWidthPx: Int
                    ): String {
                        val m = b.marginPx
                        val leftX = if (needPanel) panelWidthPx + m else m
                        val rightX = "main_w-overlay_w-$m"
                        val topY = m
                        val botY = "main_h-overlay_h-$m"

                        return when (b.placement.uppercase()) {
                            "TOP_LEFT_BUG" -> "x=$leftX:y=$topY"
                            "TOP_RIGHT_BUG" -> "x=$rightX:y=$topY"
                            "BOTTOM_LEFT_BUG" -> "x=$leftX:y=$botY"
                            "BOTTOM_RIGHT_BUG" -> "x=$rightX:y=$botY"
                            else -> "x=$rightX:y=$topY"
                        }
                    }

                    if (spans.isNotEmpty()) {
                        val prep = BackgroundSpansFfmpeg.prepare(
                            spans = spans,
                            cues = normalizedCues.items,
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

                            val sceneW = (w - panelWidthPx).coerceAtLeast(1)
                            val colorPanel = colorForDrawbox(panelColorHex, panelOpacity)

                            val filter = buildString {
                                append(prep.filterComplex)

                                if (needPanel) {
                                    append("${prep.videoOutLabel}crop=w=$sceneW:h=$h:x=(iw-$sceneW)/2:y=(ih-$h)/2,")
                                    append("pad=$w:$h:$panelWidthPx:0:color=$bgPadFF,")
                                    append("drawbox=x=0:y=0:w=$panelWidthPx:h=$h:color=$colorPanel:t=fill,")
                                    append("format=yuv420p[fbase];")
                                    append("[fbase]subtitles='${assPathEsc}'[withsubs];")
                                } else {
                                    append("${prep.videoOutLabel}subtitles='${assPathEsc}'[withsubs];")
                                }

                                if (wantBugLogo && logoPathEsc != null) {
                                    val base = minOf(w, h)
                                    val sizePx = if (branding.sizeMode.equals("FIXED", true))
                                        branding.sizeValue.toInt().coerceAtLeast(16)
                                    else
                                        (base * branding.sizeValue).toInt().coerceAtLeast(24)

                                    if (branding.plate.enabled) {
                                        val plateColor = colorForDrawbox(
                                            branding.plate.colorHex.removePrefix("#"),
                                            branding.plate.opacity
                                        )
                                        val padP = branding.plate.paddingPx.coerceAtLeast(0)
                                        append("movie='${logoPathEsc}',scale=-1:$sizePx,")
                                        append("pad=iw+${padP * 2}:ih+${padP * 2}:$padP:$padP:color=$plateColor[logo];")
                                    } else {
                                        append("movie='${logoPathEsc}',scale=-1:$sizePx[logo];")
                                    }
                                    append(
                                        "[withsubs][logo]overlay=${
                                            overlayXY(
                                                branding,
                                                needPanel,
                                                panelWidthPx
                                            )
                                        }[vout];"
                                    )
                                } else {
                                    append("[withsubs]format=yuv420p[vout];")
                                }
                            }

                            val audioInputIndex = prep.inputsCount
                            cmd.addAll(
                                listOf(
                                    "-filter_complex", filter,
                                    "-map", "[vout]", "-map", "$audioInputIndex:a",
                                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                                    "-c:a", "aac", "-ar", "48000", "-b:a", "${req.render.audioBitrateKbps}k",
                                    "-movflags", "+faststart", "-shortest",
                                    outFile.absolutePath
                                )
                            )
                        } else {
                            val totalSec = (targetTotalMs.coerceAtLeast(1)).toDouble() / 1000.0
                            val inputs = mutableListOf<String>()
                            val preferredBgUrl = spans.firstOrNull()?.imageUrl ?: req.render.background.imageUrl

                            if (preferredBgUrl != null) {
                                val bg = File(tmpDir, "bg.jpg")
                                client.get(preferredBgUrl).apply {
                                    if (!status.isSuccess()) error("Background download failed: $status")
                                    bodyAsChannel().copyTo(bg.outputStream())
                                }
                                inputs += listOf("-loop", "1", "-t", "%.3f".format(totalSec), "-i", bg.absolutePath)
                            } else {
                                inputs += listOf("-f", "lavfi", "-t", "%.3f".format(totalSec),
                                    "-i", "color=c=$bgPadFF:s=${w}x$h:r=$fps")
                            }

                            inputs += listOf("-i", audioFile.absolutePath)
                            cmd.addAll(inputs)

                            val sceneW = (w - panelWidthPx).coerceAtLeast(1)
                            val colorPanel = colorForDrawbox(panelColorHex, panelOpacity)

                            if (wantBugLogo && logoPathEsc != null && needPanel) {
                                val filter = buildString {
                                    append("[0:v]scale=w=$w:h=$h:force_original_aspect_ratio=decrease,")
                                    append("crop=w=$sceneW:h=$h:x=(iw-$sceneW)/2:y=(ih-$h)/2,")
                                    append("pad=$w:$h:$panelWidthPx:0:color=$bgPadFF,")
                                    append("drawbox=x=0:y=0:w=$panelWidthPx:h=$h:color=$colorPanel:t=fill,")

                                    append("format=yuv420p,fps=$fps,subtitles='${assPathEsc}'[withsubs];")

                                    val base = minOf(w, h)
                                    val sizePx = if (branding.sizeMode.equals("FIXED", true))
                                        branding.sizeValue.toInt().coerceAtLeast(16)
                                    else
                                        (base * branding.sizeValue).toInt().coerceAtLeast(24)

                                    if (branding.plate.enabled) {
                                        val plateColor = colorForDrawbox(
                                            branding.plate.colorHex.removePrefix("#"),
                                            branding.plate.opacity
                                        )
                                        val padP = branding.plate.paddingPx.coerceAtLeast(0)
                                        append("movie='${logoPathEsc}',scale=-1:$sizePx,")
                                        append("pad=iw+${padP * 2}:ih+${padP * 2}:$padP:$padP:color=$plateColor[logo];")
                                    } else {
                                        append("movie='${logoPathEsc}',scale=-1:$sizePx[logo];")
                                    }
                                    append(
                                        "[withsubs][logo]overlay=${
                                            overlayXY(
                                                branding,
                                                needPanel,
                                                panelWidthPx
                                            )
                                        }[vout]"
                                    )
                                }
                                cmd.addAll(
                                    listOf(
                                        "-filter_complex", filter,
                                        "-map", "[vout]", "-map", "1:a",
                                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                                        "-c:a", "aac", "-b:a", "${req.render.audioBitrateKbps}k",
                                        "-movflags", "+faststart", "-shortest",
                                        outFile.absolutePath
                                    )
                                )
                            } else {
                                val vf = buildString {
                                    append("scale=w=$w:h=$h:force_original_aspect_ratio=decrease,")
                                    if (needPanel) {
                                        append("crop=w=$sceneW:h=$h:x=(iw-$sceneW)/2:y=(ih-$h)/2,")
                                        append("pad=$w:$h:$panelWidthPx:0:color=$bgPadFF,")
                                        append("drawbox=x=0:y=0:w=$panelWidthPx:h=$h:color=$colorPanel:t=fill,")
                                    } else {
                                        append("pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=$bgPadFF,")
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
                        }
                    } else {
                        val totalSec = (targetTotalMs.coerceAtLeast(1)).toDouble() / 1000.0
                        val inputs = mutableListOf<String>()
                        val preferredBgUrl = req.render.background.imageUrl

                        if (preferredBgUrl != null) {
                            val bg = File(tmpDir, "bg.jpg")
                            client.get(preferredBgUrl).apply {
                                if (!status.isSuccess()) error("Background download failed: $status")
                                bodyAsChannel().copyTo(bg.outputStream())
                            }
                            inputs += listOf("-loop", "1", "-t", "%.3f".format(totalSec), "-i", bg.absolutePath)
                        } else {
                            inputs += listOf("-f", "lavfi", "-t", "%.3f".format(totalSec),
                                "-i", "color=c=$bgPadFF:s=${w}x$h:r=$fps")
                        }
                        inputs += listOf("-i", audioFile.absolutePath)
                        cmd.addAll(inputs)

                        val sceneW = (w - panelWidthPx).coerceAtLeast(1)
                        val colorPanel = colorForDrawbox(panelColorHex, panelOpacity)

                        if (wantBugLogo && logoPathEsc != null && needPanel) {
                            val filter = buildString {
                                append("[0:v]scale=w=$w:h=$h:force_original_aspect_ratio=decrease,")
                                append("crop=w=$sceneW:h=$h:x=(iw-$sceneW)/2:y=(ih-$h)/2,")
                                append("pad=$w:$h:$panelWidthPx:0:color=$bgPadFF,")
                                append("drawbox=x=0:y=0:w=$panelWidthPx:h=$h:color=$colorPanel:t=fill,")

                                append("format=yuv420p,fps=$fps,subtitles='${assPathEsc}'[withsubs];")

                                val base = minOf(w, h)
                                val sizePx = if (branding.sizeMode.equals("FIXED", true))
                                    branding.sizeValue.toInt().coerceAtLeast(16)
                                else
                                    (base * branding.sizeValue).toInt().coerceAtLeast(24)

                                if (branding.plate.enabled) {
                                    val plateColor = colorForDrawbox(
                                        branding.plate.colorHex.removePrefix("#"),
                                        branding.plate.opacity
                                    )
                                    val padP = branding.plate.paddingPx.coerceAtLeast(0)
                                    append("movie='${logoPathEsc}',scale=-1:$sizePx,")
                                    append("pad=iw+${padP * 2}:ih+${padP * 2}:$padP:$padP:color=$plateColor[logo];")
                                } else {
                                    append("movie='${logoPathEsc}',scale=-1:$sizePx[logo];")
                                }
                                append("[withsubs][logo]overlay=${overlayXY(branding, needPanel, panelWidthPx)}[vout]")
                            }

                            cmd.addAll(
                                listOf(
                                    "-filter_complex", filter,
                                    "-map", "[vout]", "-map", "1:a",
                                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                                    "-c:a", "aac", "-b:a", "${req.render.audioBitrateKbps}k",
                                    "-movflags", "+faststart", "-shortest",
                                    outFile.absolutePath
                                )
                            )
                        } else {
                            val vf = buildString {
                                append("scale=w=$w:h=$h:force_original_aspect_ratio=decrease,")
                                if (needPanel) {
                                    append("crop=w=$sceneW:h=$h:x=(iw-$sceneW)/2:y=(ih-$h)/2,")
                                    append("pad=$w:$h:$panelWidthPx:0:color=$bgPadFF,")
                                    append("drawbox=x=0:y=0:w=$panelWidthPx:h=$h:color=$colorPanel:t=fill,")
                                } else {
                                    append("pad=$w:$h:(ow-iw)/2:(oh-ih)/2:color=$bgPadFF,")
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

            val f = job.outputFile
                ?: return@get call.respond(HttpStatusCode.NotFound, "no output for job $id")

            val exists = f.exists()
            val len = runCatching { f.length() }.getOrElse { -1L }

            val log = LoggerFactory.getLogger("VideoJobs")
            log.info(
                "[/video/jobs/{}/file] method={} remote={} ua='{}' status={} file='{}' exists={} size={}",
                id,
                call.request.httpMethod.value,
                call.request.origin.remoteHost,
                call.request.headers["User-Agent"] ?: "",
                job.status,
                f.absolutePath,
                exists,
                len
            )

            if (job.status != JobStatus.SUCCEEDED || !exists) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    "job not finished or file missing (status=${job.status}, exists=$exists)"
                )
            }
            if (len <= 0L) {
                log.warn("[/video/jobs/{}/file] zero-length file detected", id)
                return@get call.respond(HttpStatusCode.InternalServerError, "rendered file has zero length")
            }

            call.response.headers.append(HttpHeaders.ContentType, ContentType.Video.MP4.toString(), false)
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"episode-$id.mp4\"",
                false
            )
            call.response.headers.append(HttpHeaders.ContentLength, len.toString(), false)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store", false)
            call.response.headers.append(HttpHeaders.AcceptRanges, "bytes", false)

            val startedAt = System.currentTimeMillis()
            var sent = 0L

            try {
                call.respondOutputStream(status = HttpStatusCode.OK) {
                    f.inputStream().use { input ->
                        val buf = ByteArray(128 * 1024)
                        var lastLog = startedAt
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            this.write(buf, 0, n)
                            sent += n

                            val now = System.currentTimeMillis()
                            if (now - lastLog >= 1500) {
                                log.info(
                                    "[/video/jobs/{}/file] progress: sent={} of {} ({}%)",
                                    id, sent, len, (sent * 100 / len)
                                )
                                lastLog = now
                            }
                        }
                        this.flush()
                    }
                }
                val took = System.currentTimeMillis() - startedAt
                log.info(
                    "[/video/jobs/{}/file] completed: sent={}B of {}B in {}ms",
                    id, sent, len, took
                )
            } catch (t: Throwable) {
                log.error(
                    "[/video/jobs/{}/file] streaming failed after {}B (len={}): {}",
                    id, sent, len, t.toString(), t
                )
                throw t
            }
        }
    }
}

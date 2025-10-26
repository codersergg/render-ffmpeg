package com.codersergg

import com.codersergg.model.MergeRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

fun Application.configureRouting() {
    val client = HttpClient(CIO) {
        engine { requestTimeout = 180_000 }
    }
    val log = LoggerFactory.getLogger(this::class.java)

    runCatching { File("/downloads").mkdirs() }

    launch(Dispatchers.IO) {
        while (true) {
            delay(10 * 60 * 1000L)
            runCatching {
                val root = File("/downloads")
                val now = System.currentTimeMillis()
                root.listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory }
                    ?.forEach { dir ->
                        val age = now - dir.lastModified()
                        if (age > 60 * 60 * 1000L) { // старше 1 часа
                            dir.deleteRecursively()
                        }
                    }
            }.onFailure { e -> log.warn("cleanup failed: ${e.message}") }
        }
    }

    routing {
        get("/") { call.respondText("Hello ffmpeg!") }

        post("/merge-urls") {
            try {
                val request = call.receive<MergeRequest>()
                log.info("Получен запрос на слияние: ${request.urls}")
                if (request.urls.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, "No URLs provided"); return@post
                }

                val tempDir = File("/downloads/${UUID.randomUUID()}").apply { mkdirs() }

                val downloadedFiles = coroutineScope {
                    request.urls.mapIndexed { index, url ->
                        async(Dispatchers.IO) {
                            log.info("Скачивание файла №$index: $url")
                            val file = File(tempDir, "part$index.mp3")
                            try {
                                val response = client.get(url)
                                if (!response.status.isSuccess()) {
                                    error("download failed: index=$index http=${response.status}")
                                }
                                file.outputStream().use { out ->
                                    response.body<ByteReadChannel>().copyTo(out)
                                }
                                val size = file.length()
                                if (size <= 0L) {
                                    error("download empty: index=$index size=0")
                                }
                                file
                            } catch (e: Exception) {
                                throw IllegalStateException("part download error at index=$index : ${e.message}", e)
                            }
                        }
                    }.awaitAll()
                }

                if (downloadedFiles.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, "No parts downloaded"); return@post
                }

                val listFile = File(tempDir, "list.txt").apply {
                    printWriter().use { out ->
                        downloadedFiles.forEach { f ->
                            val safe = f.absolutePath.replace("'", "'\\''")
                            out.println("file '$safe'")
                        }
                    }
                }

                val outputFile = File(tempDir, "merged.mp3")
                val ffmpegCommand = listOf(
                    "ffmpeg", "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", listFile.absolutePath,
                    "-vn",
                    "-c:a", "libmp3lame",
                    "-ar", "48000",
                    "-b:a", "192k",
                    outputFile.absolutePath
                )

                log.info("Запуск ffmpeg: ${ffmpegCommand.joinToString(" ")}")

                val process = ProcessBuilder(ffmpegCommand)
                    .redirectErrorStream(true)
                    .start()

                val ffmpegOutput = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode != 0 || !outputFile.exists() || outputFile.length() <= 0L) {
                    val snippet = ffmpegOutput.take(2000)
                    log.error("FFmpeg failed, exit=$exitCode, bytes=${outputFile.length()}.\n$snippet")
                    call.response.header("X-Merger-Error", "ffmpeg_failed")
                    call.respond(
                        HttpStatusCode.BadGateway,
                        "ffmpeg failed: exit=$exitCode, size=${outputFile.length()}. log:\n$snippet"
                    )
                    return@post
                }

                log.info("Файл успешно объединён: ${outputFile.absolutePath} (${outputFile.length()} bytes)")

                val mergedMs = probeDurationMs(outputFile, log)
                if (mergedMs != null) {
                    call.response.header("X-Audio-Duration-Ms", mergedMs.toString())
                    log.info("X-Audio-Duration-Ms=$mergedMs")
                } else {
                    log.warn("X-Audio-Duration-Ms not set (probe failed)")
                }

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, "merged.mp3"
                    ).toString()
                )
                call.respondFile(outputFile)
            } catch (e: IllegalStateException) {
                log.error("Ошибка сборки мерджа (download/validate): ${e.message}")
                call.response.header("X-Merger-Error", "download_failed")
                call.respond(HttpStatusCode.BadRequest, "merge error: ${e.message}")
            } catch (e: Exception) {
                log.error("Ошибка при обработке merge-запроса", e)
                call.response.header("X-Merger-Error", "internal_error")
                call.respond(HttpStatusCode.InternalServerError, "internal error: ${e.message}")
            }
        }
    }
}

private fun probeDurationMs(file: File, log: org.slf4j.Logger): Long? {
    fun run(vararg args: String): String {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        if (code != 0) {
            log.warn("ffprobe exit=$code output=$out")
            throw IllegalStateException("ffprobe failed: exit=$code")
        }
        return out
    }

    return runCatching {
        val sec = run(
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath
        ).toDouble()
        kotlin.math.round(sec * 1000.0).toLong()
    }.getOrElse {
        log.warn("Cannot probe duration via ffprobe: ${it.message}")
        null
    }
}

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
        engine {
            requestTimeout = 180_000
        }
    }
    val log = LoggerFactory.getLogger(this::class.java)

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
                            val response = client.get(url)
                            file.outputStream().use { out ->
                                response.body<ByteReadChannel>().copyTo(out)
                            }
                            file
                        }
                    }.awaitAll()
                }

                val listFile = File(tempDir, "list.txt").apply {
                    printWriter().use { out ->
                        downloadedFiles.forEach { f ->
                            out.println("file '${f.absolutePath}'")
                        }
                    }
                }

                val outputFile = File(tempDir, "merged.mp3")
                val ffmpegCommand = listOf(
                    "ffmpeg", "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", listFile.absolutePath,
                    "-vn",
                    "-af", "aresample=async=1:first_pts=0",
                    "-c:a", "libmp3lame", "-ar", "44100", "-b:a", "192k",
                    outputFile.absolutePath
                )
                log.info("Запуск ffmpeg: ${ffmpegCommand.joinToString(" ")}")

                val process = ProcessBuilder(ffmpegCommand)
                    .redirectErrorStream(true)
                    .start()

                val ffmpegOutput = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode != 0 || !outputFile.exists()) {
                    log.error("FFmpeg error output:\n$ffmpegOutput")
                    call.respond(HttpStatusCode.InternalServerError, "Failed to merge audio")
                    return@post
                }

                log.info("Файл успешно объединён: ${outputFile.absolutePath}")

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, "merged.mp3"
                    ).toString()
                )
                call.respondFile(outputFile)
            } catch (e: Exception) {
                log.error("Ошибка при обработке merge-запроса", e)
                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
            }
        }
    }
}

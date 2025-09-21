package com.codersergg

import com.codersergg.model.ProbeDurationsRequest
import com.codersergg.model.ProbeDurationsResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import org.slf4j.LoggerFactory
import java.io.File

fun Application.configureProbeRouting() {
    val log = LoggerFactory.getLogger("ProbeRouting")

    routing {
        post("/probe-durations") {
            val req = call.receive<ProbeDurationsRequest>()
            require(req.urls.isNotEmpty()) { "urls must not be empty" }

            val dir = File("/downloads/${System.currentTimeMillis()}-probe").apply { mkdirs() }
            val client = HttpClient(CIO)
            try {
                val files = req.urls.mapIndexed { idx, url ->
                    val target = File(dir, "%03d.mp3".format(idx))
                    val ch: ByteReadChannel = client.get(url).body()
                    target.outputStream().use { out -> ch.copyTo(out) }
                    target
                }

                val durations = files.map { f -> probeDurationMs(f) }
                val total = durations.fold(0L, Long::plus)

                call.respond(ProbeDurationsResponse(durationsMs = durations, totalMs = total))
            } catch (e: Exception) {
                log.error("probe-durations failed", e)
                throw e
            } finally {
                runCatching { client.close() }
                runCatching { dir.deleteRecursively() }
            }
        }
    }
}

/** Возвращает длительность файла в миллисекундах через ffprobe */
private fun probeDurationMs(file: File): Long {
    val pb = ProcessBuilder(
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        file.absolutePath
    ).redirectErrorStream(true)
    val p = pb.start()
    val out = p.inputStream.bufferedReader().readText().trim()
    val code = p.waitFor()
    require(code == 0) { "ffprobe exit=$code output=$out" }
    val seconds = out.toDoubleOrNull() ?: error("Cannot parse ffprobe output: '$out'")
    return (seconds * 1000.0).toLong()
}

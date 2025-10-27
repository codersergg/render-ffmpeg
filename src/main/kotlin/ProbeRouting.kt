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
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

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

                val durationsMs = files.mapIndexed { i, file ->
                    val ms = probeDurationMs(file)
                    log.info("probe[{}]: {} ms", i, ms)
                    ms
                }

                val totalMs = durationsMs.fold(0L) { acc, v -> acc + v }
                log.info("probe totalMs={}", totalMs)

                call.respond(
                    ProbeDurationsResponse(
                        durationsMs = durationsMs,
                        totalMs = totalMs
                    )
                )
            } finally {
                runCatching { client.close() }
                runCatching { dir.deleteRecursively() }
            }
        }
    }
}

private fun probeDurationMs(file: File): Long {
    fun runAndLines(vararg args: String): List<String> {
        val p = ProcessBuilder(*args)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readLines()
        val code = p.waitFor()
        require(code == 0) {
            "ffprobe exit=$code output=${out.joinToString("\\n")}"
        }
        return out
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    val lines = runAndLines(
        "ffprobe",
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        file.absolutePath
    )

    val secStr = lines.firstOrNull()
        ?: throw IllegalStateException("ffprobe returned no duration for ${file.name}")

    val sec = secStr.toBigDecimalOrNull()
        ?: throw IllegalStateException("duration is not a number: '$secStr' for ${file.name}")

    val ms = sec.multiply(BigDecimal("1000"))
        .setScale(0, RoundingMode.FLOOR)
        .longValueExact()

    return max(ms, 0L)
}

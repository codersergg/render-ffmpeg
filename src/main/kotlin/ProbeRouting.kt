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

                val dstRate = 44100
                val pieceSamples = mutableListOf<Long>()
                var totalSamples = 0L

                for (file in files) {
                    val (srcSamples, srcRate) = probeSamplesAndRate(file)
                    val dstSamples = resampleSamplesExact(srcSamples, srcRate, dstRate)
                    pieceSamples += dstSamples
                    totalSamples += dstSamples
                }

                val cumulative = LongArray(pieceSamples.size)
                run {
                    var acc = 0L
                    for (i in pieceSamples.indices) {
                        acc += pieceSamples[i]
                        cumulative[i] = acc
                    }
                }
                val cumulativeMs = cumulative.map { msFromSamples(it, dstRate) }

                val durationsMs = buildList {
                    var prev = 0L
                    for (cur in cumulativeMs) {
                        add(cur - prev)
                        prev = cur
                    }
                }
                val totalMs = msFromSamples(totalSamples, dstRate)

                call.respond(ProbeDurationsResponse(durationsMs = durationsMs, totalMs = totalMs))
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

private fun probeSamplesAndRate(file: File): Pair<Long, Int> {
    val p = ProcessBuilder(
        "ffprobe", "-v", "error",
        "-select_streams", "a:0",
        "-show_entries", "stream=nb_samples,sample_rate",
        "-of", "default=noprint_wrappers=1:nokey=1",
        file.absolutePath
    ).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readLines()
    val code = p.waitFor()
    require(code == 0) { "ffprobe failed: $out" }
    val nb = out.getOrNull(0)?.trim()?.toLongOrNull()
    val sr = out.getOrNull(1)?.trim()?.toIntOrNull()
    require(nb != null && sr != null && sr > 0) { "bad ffprobe: $out" }
    return nb to sr
}

private fun resampleSamplesExact(nbSamples: Long, srcRate: Int, dstRate: Int = 44100): Long {
    val num = nbSamples * dstRate.toLong()
    return (num + srcRate/2) / srcRate
}

private fun msFromSamples(samples: Long, rate: Int = 44100): Long {
    val num = samples * 1000L
    return (num + rate/2) / rate
}


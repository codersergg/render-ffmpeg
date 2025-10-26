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

                val dstRate = 48000
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
    fun runAndLines(vararg args: String): List<String> {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val code = p.waitFor()
        require(code == 0) { "ffprobe exit=$code output=$out" }
        return out
    }

    runCatching {
        val out = runAndLines(
            "ffprobe", "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=nb_samples,sample_rate",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath
        )
        if (out.size >= 2) {
            val nb = out[0].trim().toLongOrNull()
            val sr = out[1].trim().toIntOrNull()
            if (nb != null && sr != null && sr > 0) return nb to sr
        }
        if (out.size == 1) {
            val sr = out[0].trim().toIntOrNull()
            if (sr != null && sr > 0) {
                val alt = runAndLines(
                    "ffprobe", "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=duration_ts,time_base,sample_rate",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.absolutePath
                )
                if (alt.size >= 3) {
                    val durTs = alt[0].trim().toLongOrNull()
                    val timeBase = alt[1].trim()
                    val sr2 = alt[2].trim().toIntOrNull() ?: sr
                    val parts = timeBase.split("/")
                    if (durTs != null && parts.size == 2) {
                        val num = parts[0].toLongOrNull()
                        val den = parts[1].toLongOrNull()
                        if (num != null && den != null && den > 0L) {
                            val nume = durTs * num * sr2
                            val samples = (nume + den / 2) / den
                            return samples to sr2
                        }
                    }
                }
                val fmt = runAndLines(
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.absolutePath
                )
                val seconds = fmt.firstOrNull()?.trim()?.toDoubleOrNull()
                if (seconds != null && seconds > 0.0) {
                    val samples = kotlin.math.round(seconds * sr)
                    return samples.toLong() to sr
                }
            }
        }
        throw IllegalArgumentException("unresolved audio metrics (nb_samples/sample_rate)")
    }.onFailure {  }

    runCatching {
        val alt = runAndLines(
            "ffprobe", "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=duration_ts,time_base,sample_rate",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath
        )
        if (alt.size >= 3) {
            val durTs = alt[0].trim().toLongOrNull()
            val timeBase = alt[1].trim()
            val sr = alt[2].trim().toIntOrNull()
            if (durTs != null && sr != null && sr > 0) {
                val parts = timeBase.split("/")
                if (parts.size == 2) {
                    val num = parts[0].toLongOrNull()
                    val den = parts[1].toLongOrNull()
                    if (num != null && den != null && den > 0L) {
                        val samples = (durTs * num * sr + den / 2) / den
                        return samples to sr
                    }
                }
            }
        }
        throw IllegalArgumentException("unresolved audio metrics (duration_ts/time_base)")
    }.onFailure {  }

    val srOnly = runAndLines(
        "ffprobe", "-v", "error",
        "-select_streams", "a:0",
        "-show_entries", "stream=sample_rate",
        "-of", "default=noprint_wrappers=1:nokey=1",
        file.absolutePath
    ).firstOrNull()?.trim()?.toIntOrNull() ?: throw IllegalArgumentException("no sample_rate")
    val dur = runAndLines(
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        file.absolutePath
    ).firstOrNull()?.trim()?.toDoubleOrNull() ?: 0.0
    val samples = kotlin.math.round(dur * srOnly)
    return samples.toLong() to srOnly
}

private fun resampleSamplesExact(nbSamples: Long, srcRate: Int, dstRate: Int = 44100): Long {
    val num = nbSamples * dstRate.toLong()
    return (num + srcRate/2) / srcRate
}

private fun msFromSamples(samples: Long, rate: Int = 44100): Long {
    val num = samples * 1000L
    return (num + rate/2) / rate
}


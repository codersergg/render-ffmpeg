package com.codersergg.video

import com.codersergg.model.video.JobStatus
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class VideoJob(
    val id: String,
    @Volatile var status: JobStatus = JobStatus.QUEUED,
    @Volatile var message: String? = null,
    @Volatile var durationMs: Long? = null,
    @Volatile var outputFile: File? = null,
    val workDir: File
)

object VideoJobManager {
    private val jobs = ConcurrentHashMap<String, VideoJob>()

    fun create(job: VideoJob) { jobs[job.id] = job }
    fun get(id: String): VideoJob? = jobs[id]
    fun complete(id: String, file: File, durationMs: Long) {
        jobs[id]?.apply {
            status = JobStatus.SUCCEEDED
            outputFile = file
            this.durationMs = durationMs
        }
    }
    fun fail(id: String, msg: String) {
        jobs[id]?.apply {
            status = JobStatus.FAILED
            message = msg
        }
    }
}

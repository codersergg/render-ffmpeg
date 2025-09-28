package com.codersergg.model.video

import kotlinx.serialization.Serializable

enum class JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }

@Serializable
data class CreateVideoJobRequest(
    val render: VideoRenderRequest
)

@Serializable
data class VideoJobResponse(
    val jobId: String,
    val status: JobStatus,
    val message: String? = null,
    val durationMs: Long? = null
)

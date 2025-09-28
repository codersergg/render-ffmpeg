package com.codersergg.model.video

import kotlinx.serialization.Serializable

@Serializable
data class VideoRenderResolution(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0 && height > 0) { "Resolution must be positive: ${width}x${height}" }
    }
}

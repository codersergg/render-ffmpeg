package com.codersergg.model

import kotlinx.serialization.Serializable

@Serializable
data class ProbeDurationsRequest(val urls: List<String>)

@Serializable
data class ProbeDurationsResponse(val durationsMs: List<Long>, val totalMs: Long)

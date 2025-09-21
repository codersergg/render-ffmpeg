package com.codersergg.model

import kotlinx.serialization.Serializable

@Serializable
data class MergeRequest(val urls: List<String>)

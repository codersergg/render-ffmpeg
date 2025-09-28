package com.codersergg.model.video

import kotlinx.serialization.Serializable

@Serializable
data class CueItem(
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val sentenceId: Long? = null
)

@Serializable
data class EpisodeCuesPayload(
    val episodeId: Long,
    val lang: String,
    val items: List<CueItem>,
    val totalMs: Long
)

@Serializable
data class Resolution(val width: Int, val height: Int)

@Serializable
data class OverlayLineStyle(
    val colorHex: String = "#FFFFFF",
    val opacity: Double = 1.0,
    val bold: Boolean = false
)

@Serializable
data class OverlayStyle(
    val fontFamily: String = "Inter",
    val fontSizePx: Int = 54,
    val lineSpacingPx: Int = 10,
    val paddingTop: Int = 64,
    val paddingRight: Int = 64,
    val paddingBottom: Int = 220,
    val paddingLeft: Int = 64,
    val boxRadiusPx: Int = 20,
    val shadow: Boolean = true,
    val boxColor: String = "#000000",
    val boxOpacity: Double = 0.35,
    val align: String = "CENTER", // LEFT | CENTER
    val previous: OverlayLineStyle = OverlayLineStyle("#FFFFFF", 0.55, false),
    val current: OverlayLineStyle = OverlayLineStyle("#FFFFFF", 1.0, true),
    val next: OverlayLineStyle = OverlayLineStyle("#FFFFFF", 0.7, false)
)

@Serializable
data class BackgroundSpec(
    val colorHex: String? = "#000000",
    val imageUrl: String? = null
)

@Serializable
data class BackgroundSpan(
    val anchorIdx: Int,
    val imageUrl: String
)

@Serializable
data class VideoRenderRequest(
    val audioUrl: String,
    val cuesUrl: String? = null,
    val cues: EpisodeCuesPayload? = null,
    val lines: List<String>,
    val resolution: Resolution = Resolution(1080, 1920),
    val fps: Int = 30,
    val videoBitrateKbps: Int = 6000,
    val audioBitrateKbps: Int = 192,
    val overlayStyle: OverlayStyle = OverlayStyle(),
    val background: BackgroundSpec = BackgroundSpec(),
    val returnAsFile: Boolean = true,
    val vertical: Boolean = false,
    val backgroundSpans: List<BackgroundSpan> = emptyList()
)

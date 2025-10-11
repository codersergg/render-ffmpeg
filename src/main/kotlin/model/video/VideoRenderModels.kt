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

// ---------- ЭФФЕКТЫ ----------

@Serializable
data class TransitionSpec(
    val type: String = "fade",
    val durationSec: Double = 0.40,
    val centerOnBoundary: Boolean = true
)

@Serializable
data class MotionSpec(
    val enabled: Boolean = false,
    val maxZoom: Double = 1.10,
    val panFraction: Double = 0.0,
    val minSpanSec: Double = 3.5,
    val alternateAxis: Boolean = false,
    val easing: String = "cosine"
)

@Serializable
data class RenderEffects(
    val transition: TransitionSpec = TransitionSpec(),
    val motion: MotionSpec = MotionSpec()
)

// ---------- ЛЕЙАУТЫ И РЕЖИМЫ СМЕНЫ ТЕКСТА ----------

@Serializable
enum class TextLayout {
    BLUR_UNDERLAY,   // горизонталь: текст на размытой подложке снизу
    PANEL_LEFT,      // горизонталь: левая панель, картинка справа
    VERTICAL_ONE     // вертикаль: одна строка
}

@Serializable
enum class ChangeMode {
    SHIFT,           // previous/current/next — текущая хореография, только BLUR
    REPLACE          // страничная замена без сдвига — BLUR и PANEL
}

// ---------- ПАНЕЛЬ (для PANEL_LEFT) ----------

@Serializable
enum class PanelBackgroundMode { SOLID, TEXTURE, GRADIENT }

@Serializable
enum class GradientType { LINEAR, RADIAL }

@Serializable
enum class GradientSource { AUTO_FROM_IMAGE, PRESET, MANUAL }

@Serializable
enum class PaletteStrategy { ANALOGOUS, SPLIT_COMPLEMENTARY, MONOCHROME }

@Serializable
enum class TextureScaleMode { TILE, COVER }

@Serializable
enum class BlendMode { MULTIPLY, OVERLAY, NORMAL }

@Serializable
data class GradientAutoSpec(
    val k: Int = 5,
    val sampleRegion: String = "SAFE_RIGHT",
    val lock: String = "FIRST_SPAN",         // FIRST_SPAN | AVERAGE_OF_SPANS | PER_SPAN
    val minContrastForText: Double = 4.5
)

@Serializable
data class GradientStop(
    val colorHex: String,
    val pos: Double
)

@Serializable
data class GradientSpec(
    val source: GradientSource = GradientSource.AUTO_FROM_IMAGE,
    val type: GradientType = GradientType.LINEAR,
    val directionDeg: Double = 0.0,
    val paletteStrategy: PaletteStrategy = PaletteStrategy.ANALOGOUS,
    val stops: List<GradientStop> = emptyList(), // для PRESET/MANUAL
    val auto: GradientAutoSpec = GradientAutoSpec(),
    val smoothness: Double = 0.6,
    val inkOverlayEnabled: Boolean = true,
    val inkOverlayOpacity: Double = 0.10
)

@Serializable
data class PanelTextureSpec(
    val textureUrl: String,
    val textureOpacity: Double = 0.22, // 0.15..0.35
    val textureScaleMode: TextureScaleMode = TextureScaleMode.TILE,
    val blendMode: BlendMode = BlendMode.MULTIPLY
)

@Serializable
data class PanelBackground(
    val mode: PanelBackgroundMode = PanelBackgroundMode.SOLID,
    val colorHex: String = "#141416",
    val opacity: Double = 0.96,
    val gradient: GradientSpec? = null,
    val texture: PanelTextureSpec? = null,
    val dividerRight: Boolean = true,
    val dividerColorHex: String? = null,
    val dividerOpacity: Double? = null
)

@Serializable
data class PanelSpec(
    val widthPct: Double = 0.36,      // 0.34..0.40
    val innerPaddingPx: Int = 48,
    val background: PanelBackground = PanelBackground()
)

// ---------- МЕТА-ШАПКА И БРЕНДИНГ (лого) ----------

@Serializable
data class MetaHeaderSpec(
    val visible: Boolean = true,
    val storyTitle: String? = null,
    val level: String? = null,
    val languageName: String? = null,
    val paddingTopExtraPx: Int? = null,
    val separatorEnabled: Boolean? = null,
    val separatorHeightPx: Int? = null,
    val separatorOpacity: Double? = null,
    val separatorColorHex: String? = null,
    val headerTitleColorHex: String? = null,
    val headerMetaColorHex: String? = null,
    val allowTwoLineTitleInPanel: Boolean? = null
)

@Serializable
data class BrandingPlateSpec(
    val enabled: Boolean = true,
    val colorHex: String = "#000000",
    val opacity: Double = 0.26,
    val radiusPx: Int = 14,
    val paddingPx: Int = 10
)

@Serializable
data class BrandingSpec(
    val show: Boolean = true,
    val placement: String = "IN_HEADER",   // IN_HEADER | TOP_RIGHT_BUG
    val logoUrl: String? = null,
    val sizeMode: String = "RELATIVE",     // RELATIVE | FIXED
    val sizeValue: Double = 0.04,          // 4% от min(width,height) или px при FIXED
    val marginPx: Int = 48,
    val plate: BrandingPlateSpec = BrandingPlateSpec(),
    val fadeMs: Int = 250,
    val pageTurnPulse: Boolean = false
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
    val backgroundSpans: List<BackgroundSpan> = emptyList(),
    val effects: RenderEffects = RenderEffects(),
    val layout: TextLayout? = null,
    val changeMode: ChangeMode? = null,
    val visibleLines: Int? = null,
    val panel: PanelSpec? = null,
    val metaHeader: MetaHeaderSpec = MetaHeaderSpec(),
    val branding: BrandingSpec = BrandingSpec()
)

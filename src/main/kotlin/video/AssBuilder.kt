package com.codersergg.video

import com.codersergg.model.EpisodeCuesPayload
import com.codersergg.model.OverlayLineStyle
import com.codersergg.model.OverlayStyle
import java.io.File
import kotlin.math.max
import kotlin.math.min

object AssBuilder {

    fun buildAssFile(
        target: File,
        cues: EpisodeCuesPayload,
        lines: List<String>,
        style: OverlayStyle
    ) {
        require(cues.items.size == lines.size) {
            "lines count (${lines.size}) must match cues (${cues.items.size})"
        }
        target.writeText(buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: ${1920}")
            appendLine("PlayResY: ${1080}")
            appendLine("ScaledBorderAndShadow: yes")
            appendLine()

            appendLine("[V4+ Styles]")
            appendLine(
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, " +
                        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
                        "Alignment, MarginL, MarginR, MarginV, Encoding"
            )

            fun color(a: Double, hex: String): String {
                val c = hex.removePrefix("#")
                val r = c.substring(0, 2).toInt(16)
                val g = c.substring(2, 4).toInt(16)
                val b = c.substring(4, 6).toInt(16)
                val alpha = (255 - (a * 255)).coerceIn(0.0, 255.0).toInt()
                return "&H%02X%02X%02X%02X".format(alpha, b, g, r)
            }

            val alignCode = if (style.align.equals("LEFT", true)) 7 else 8
            val outline = 2
            val shadow = if (style.shadow) 2 else 0

            fun styleLine(name: String, ls: OverlayLineStyle) = buildString {
                append("Style: $name,${style.fontFamily},${style.fontSizePx},")
                append("${color(ls.opacity, ls.colorHex)},&H000000FF,&H00000000,")
                append("${if (ls.bold) -1 else 0},0,0,0,100,100,0,0,1,$outline,$shadow,")
                append("$alignCode,${style.paddingLeft},${style.paddingRight},${style.paddingBottom},0")
            }

            appendLine(styleLine("prev", style.previous))
            appendLine(styleLine("cur", style.current))
            appendLine(styleLine("next", style.next))
            appendLine()

            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

            fun msToAss(t: Long): String {
                val h = t / 3_600_000
                val m = (t % 3_600_000) / 60_000
                val s = (t % 60_000) / 1000
                val ms = t % 1000
                return "%01d:%02d:%02d.%02d".format(h, m, s, ms / 10)
            }

            val padL = style.paddingLeft
            val padR = style.paddingRight
            val padB = style.paddingBottom

            fun event(styleName: String, start: Long, end: Long, text: String) =
                "Dialogue: 0,${msToAss(start)},${msToAss(end)},$styleName,,${padL},${padR},${padB},,${escapeAss(text)}"

            for (i in cues.items.indices) {
                val cur = cues.items[i]
                val curText = lines[i]

                val prevText = if (i - 1 >= 0) lines[i - 1] else ""
                val nextText = if (i + 1 < lines.size) lines[i + 1] else ""

                val prevEnd = cur.startMs
                val prevStart = max(0, cur.startMs - 500)
                val nextStart = cur.endMs
                val nextEnd = min(cues.totalMs, cur.endMs + 500)

                if (prevText.isNotBlank()) appendLine(event("prev", prevStart, prevEnd, prevText))
                appendLine(event("cur", cur.startMs, cur.endMs, curText))
                if (nextText.isNotBlank()) appendLine(event("next", nextStart, nextEnd, nextText))
            }
        })
    }

    private fun escapeAss(text: String): String =
        text.replace("\n", "\\N")
            .replace("{", "\\{")
            .replace("}", "\\}")
}

package com.codersergg.video

import com.codersergg.model.video.EpisodeCuesPayload
import com.codersergg.model.video.MetaHeaderSpec
import com.codersergg.model.video.OverlayLineStyle
import com.codersergg.model.video.OverlayStyle
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AssBuilder {
    fun buildAssFile(
        target: File,
        cues: EpisodeCuesPayload,
        lines: List<String>,
        style: OverlayStyle,
        width: Int,
        height: Int,
        metaHeader: MetaHeaderSpec? = null
    ) {
        require(cues.items.size == lines.size) {
            "lines count (${lines.size}) must match cues (${cues.items.size})"
        }
        if (cues.items.isEmpty()) {
            target.writeText("")
            return
        }

        val shiftMs = 240
        val outline = 2
        val shadow = if (style.shadow) 2 else 0

        val isLeft = style.align.equals("LEFT", true)
        val alignCode = if (isLeft) 1 else 2

        val marginL = max(32, (width * 0.04).roundToInt())
        val marginR = max(32, (width * 0.04).roundToInt())
        val marginV = max((height * 0.09).roundToInt(), 52)
        val lineStep = (style.fontSizePx * 1.52).roundToInt()

        val safeAvailPx = (width - marginL - marginR).coerceAtLeast(160)
        val avgCharPxNormal = max(7.5, style.fontSizePx * 0.46)
        val avgCharPxBold = max(7.5, style.fontSizePx * 0.52)
        val outlineOverhead = outline * 0.6

        fun calcLimit(isBold: Boolean): Int {
            val glyph = if (isBold) avgCharPxBold else avgCharPxNormal
            return ((safeAvailPx - outlineOverhead) / glyph).toInt().coerceAtLeast(14)
        }

        fun hardWrap(text: String, isBold: Boolean): String {
            val maxChars = calcLimit(isBold)
            val words = text.trim().split(Regex("\\s+"))
            if (words.isEmpty()) return ""
            val sb = StringBuilder()
            var curLen = 0
            fun nl() {
                sb.append("\\N"); curLen = 0
            }
            for (w in words) {
                if (w.length > maxChars) {
                    var i = 0
                    while (i < w.length) {
                        val take = min(maxChars, w.length - i)
                        if (curLen != 0) nl()
                        sb.append(w.substring(i, i + take))
                        curLen = take
                        i += take
                    }
                    continue
                }
                val add = if (curLen == 0) w.length else w.length + 1
                if (curLen > 0 && curLen + add > maxChars) {
                    nl(); sb.append(w); curLen = w.length
                } else {
                    if (curLen > 0) sb.append(' ')
                    sb.append(w); curLen += add
                }
            }
            return sb.toString()
        }

        fun styleLine(name: String, ls: OverlayLineStyle, forceBold: Boolean, minOpacity: Double) = buildString {
            val primary = rgbaToAss(max(ls.opacity, minOpacity), ls.colorHex)
            append("Style: $name,${style.fontFamily},${style.fontSizePx},")
            append("$primary,&H000000FF,&H00000000,&H00000000,")
            append("${if (forceBold) -1 else 0},0,0,0,100,100,0,0,1,$outline,$shadow,")
            append("$alignCode,$marginL,$marginR,$marginV,0")
        }

        // ── Заголовок/чипы в шапке ──────────────────────────────────────────
        val headerAlignCode = 7
        val headerMarginL = max(24, style.paddingLeft)
        val headerMarginR = style.paddingRight

        val padExtra = (metaHeader?.paddingTopExtraPx ?: 0).coerceAtLeast(0)
        val headerMarginV = max(24, style.paddingTop) + padExtra

        val headerFontTitle = (style.fontSizePx * 0.8).roundToInt().coerceAtLeast(24)
        val headerFontMeta = (style.fontSizePx * 0.55).roundToInt().coerceAtLeast(18)

        val titleColor = (metaHeader?.headerTitleColorHex ?: "#FFFFFF")
        val metaColor = (metaHeader?.headerMetaColorHex ?: "#FFFFFF")

        fun styleHeaderTitle() = buildString {
            val primary = rgbaToAss(1.0, titleColor)
            append("Style: hdr,${style.fontFamily},$headerFontTitle,")
            append("$primary,&H000000FF,&H00000000,&H00000000,")
            append("-1,0,0,0,100,100,0,0,1,2,${if (style.shadow) 2 else 0},")
            append("$headerAlignCode,$headerMarginL,$headerMarginR,$headerMarginV,0")
        }

        fun styleHeaderMeta() = buildString {
            val primary = rgbaToAss(0.95, metaColor)
            append("Style: hdrMeta,${style.fontFamily},$headerFontMeta,")
            append("$primary,&H000000FF,&H00000000,&H00000000,")
            append("0,0,0,0,100,100,0,0,1,2,${if (style.shadow) 2 else 0},")
            append("$headerAlignCode,$headerMarginL,$headerMarginR,${headerMarginV + headerFontTitle + 10},0")
        }

        // ── Разделитель ШАПКИ (headerSeparator*) ─────────────────────
        val hdrSepColor   = (metaHeader?.headerSeparatorColorHex ?: "#FFFFFF")
        val hdrSepOpacity = (metaHeader?.headerSeparatorOpacity ?: 0.50).coerceIn(0.0, 1.0)
        val hdrSepHeight  = (metaHeader?.headerSeparatorHeightPx ?: 0).coerceAtLeast(0)
        val hdrSepEnabled = (metaHeader?.headerSeparatorEnabled == true) && hdrSepHeight > 0

        fun styleHeaderSeparator() = buildString {
            val sep = rgbaToAss(hdrSepOpacity, hdrSepColor)
            append("Style: hdrSep,${style.fontFamily},100,")
            append("$sep,&H000000FF,&H00000000,&H00000000,")
            append("0,0,0,0,100,100,0,0,1,0,0,")
            append("$headerAlignCode,$headerMarginL,$headerMarginR,${headerMarginV + headerFontTitle + headerFontMeta + 14},0")
        }

        val totalMs = max(1L, cues.totalMs)

        data class Two(val a: String, val b: String?)

        fun splitTwo(text: String): Two {
            val wrapped = hardWrap(text, isBold = true)
            val parts = wrapped.split("\\N")
            if (parts.size <= 1) return Two(parts.firstOrNull().orEmpty(), null)

            val aWords = parts[0].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
            val bWords =
                parts.drop(1).joinToString(" ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()

            val minTailWords = 2
            fun chars(lst: List<String>) = lst.sumOf { it.length } + (lst.size - 1).coerceAtLeast(0)
            fun ratioB(a: List<String>, b: List<String>): Double {
                val ca = chars(a).toDouble()
                val cb = chars(b).toDouble()
                val sum = (ca + cb).coerceAtLeast(1.0)
                return cb / sum
            }

            while ((bWords.size < minTailWords || ratioB(aWords, bWords) < 0.35) && aWords.size > minTailWords) {
                val moved = aWords.removeAt(aWords.size - 1)
                bWords.add(0, moved)
            }

            val a = aWords.joinToString(" ")
            val b = bWords.joinToString(" ")
            return Two(a, if (b.isBlank()) null else b)
        }

        val startsOrig: List<Long> = cues.items.map { it.startMs }
        val linesTwo: List<Two> = lines.map { splitTwo(it) }

        val expandedStarts = ArrayList<Long>(lines.size * 2)
        val expandedLines = ArrayList<String>(lines.size * 2)

        for (j in lines.indices) {
            val t0 = startsOrig[j]
            val t1 = if (j + 1 < startsOrig.size) startsOrig[j + 1] else totalMs
            val cur = linesTwo[j]

            if (cur.b == null) {
                expandedStarts += t0
                expandedLines += cur.a
            } else {
                val lenA = cur.a.length.toDouble().coerceAtLeast(1.0)
                val lenB = cur.b.length.toDouble().coerceAtLeast(1.0)
                val ratio = (lenA / (lenA + lenB)).coerceIn(0.35, 0.65)
                val dur = (t1 - t0).coerceAtLeast(2L * shiftMs)
                val tMid = (t0 + (dur * ratio)).toLong().coerceIn(t0 + shiftMs, t1 - shiftMs)

                expandedStarts += t0
                expandedLines += cur.a
                expandedStarts += tMid
                expandedLines += cur.b
            }
        }

        target.writeText(buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: $width")
            appendLine("PlayResY: $height")
            appendLine("ScaledBorderAndShadow: yes")
            appendLine("WrapStyle: 2")
            appendLine("Collisions: Normal")
            appendLine()

            appendLine("[V4+ Styles]")
            appendLine(
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, " +
                        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
                        "Alignment, MarginL, MarginR, MarginV, Encoding"
            )
            appendLine(styleLine("prev", style.previous, forceBold = false, minOpacity = 0.70))
            appendLine(styleLine("cur", style.current, forceBold = true, minOpacity = 1.00))
            appendLine(styleLine("next", style.next, forceBold = false, minOpacity = 0.70))
            if (shouldRenderHeader(metaHeader)) {
                appendLine(styleHeaderTitle())
                appendLine(styleHeaderMeta())
                if (hdrSepEnabled) appendLine(styleHeaderSeparator())
            }

            appendLine()

            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

            fun msToAss(t: Long): String {
                val cs = t.coerceIn(0L, totalMs) / 10
                val h = cs / 360000
                val m = (cs / 6000) % 60
                val s = (cs / 100) % 60
                val c = cs % 100
                return "%01d:%02d:%02d.%02d".format(h, m, s, c)
            }

            fun add(layer: Int, t0: Long, t1: Long, styleName: String, tag: String, text: String) {
                if (t1 <= t0) return
                if (text.isEmpty()) return
                appendLine("Dialogue: $layer,${msToAss(t0)},${msToAss(t1)},$styleName,,0,0,0,,$tag${esc(text)}")
            }

            if (shouldRenderHeader(metaHeader)) {
                val title = splitTwo(metaHeader!!.storyTitle ?: "").a
                val chips = buildChips(metaHeader)
                add(3, 0, totalMs, "hdr", "{\\q2\\an7}", title)
                if (chips.isNotEmpty()) add(3, 0, totalMs, "hdrMeta", "{\\q2\\an7}", chips)

                if (hdrSepEnabled) {
                    val h = hdrSepHeight.coerceAtLeast(1)
                    val path = "m 0 0 l $safeAvailPx 0 l $safeAvailPx $h l 0 $h"
                    add(3, 0, totalMs, "hdrSep", "{\\q2\\an7\\p1\\fs100\\bord0\\shad0}", path)
                }
            }

            val x = if (isLeft) marginL else width / 2
            fun yFor(idxFromBottom: Int) = (height - marginV - idxFromBottom * lineStep)

            fun posTag(y: Int) = "{\\q2\\an$alignCode\\pos($x,$y)}"
            fun moveTag(y0: Int, y1: Int, durationMs: Long) =
                "{\\q2\\an$alignCode\\move($x,$y0,$x,$y1,0,$durationMs)}"

            val N = expandedLines.size
            fun t(i: Int): Long = when {
                i < 0 -> 0L
                i < N -> expandedStarts[i]
                else -> totalMs
            }

            for (j in 0 until N) {
                val curText = expandedLines[j]

                val toMidStart = t(j)
                val toMidEnd = min(totalMs, t(j) + shiftMs)
                val holdMidEnd = t(j + 1)
                val toTopEnd = min(totalMs, t(j + 1) + shiftMs)
                val holdTopEnd = t(j + 2)
                val exitEnd = min(totalMs, t(j + 2) + shiftMs)

                val yBottom = yFor(0)
                val yMid = yFor(1)
                val yTop = yFor(2)
                val yBelow = yFor(-1)
                val yAbove = yFor(3)

                add(2, toMidStart, toMidEnd, "cur", moveTag(yBottom, yMid, (toMidEnd - toMidStart)), curText)
                add(2, toMidEnd, holdMidEnd, "cur", posTag(yMid), curText)

                add(0, holdMidEnd, toTopEnd, "prev", moveTag(yMid, yTop, (toTopEnd - holdMidEnd)), curText)
                add(0, toTopEnd, holdTopEnd, "prev", posTag(yTop), curText)
                add(0, holdTopEnd, exitEnd, "prev", moveTag(yTop, yAbove, (exitEnd - holdTopEnd)), curText)

                if (j + 1 < N) {
                    val nextText = expandedLines[j + 1]
                    val nextInStart = t(j)
                    val nextInEnd = min(totalMs, t(j) + shiftMs)
                    add(
                        1,
                        nextInStart,
                        nextInEnd,
                        "next",
                        moveTag(yBelow, yBottom, (nextInEnd - nextInStart)),
                        nextText
                    )
                    add(1, nextInEnd, t(j + 1), "next", posTag(yBottom), nextText)
                }
            }
        })
    }

    fun buildAssFileVerticalInstant(
        target: File,
        cues: EpisodeCuesPayload,
        lines: List<String>,
        style: OverlayStyle,
        width: Int,
        height: Int,
        metaHeader: MetaHeaderSpec? = null
    ) {
        require(cues.items.size == lines.size) {
            "lines count (${lines.size}) must match cues (${cues.items.size})"
        }
        if (cues.items.isEmpty()) {
            target.writeText("")
            return
        }

        val shadow = if (style.shadow) 2 else 0

        fun styleLine(name: String, ls: OverlayLineStyle, forceBold: Boolean) = buildString {
            val primary = rgbaToAss(ls.opacity, ls.colorHex)
            append("Style: $name,${style.fontFamily},${style.fontSizePx},")
            append("$primary,&H000000FF,&H00000000,&H00000000,")
            append("${if (forceBold) -1 else 0},0,0,0,100,100,0,0,1,2,$shadow,")
            append(
                "2,${max(24, (width * 0.05).roundToInt())},${max(24, (width * 0.05).roundToInt())},${
                    max(
                        64,
                        (height * 0.12).roundToInt()
                    )
                },0"
            )
        }

        val totalMs = max(1L, cues.totalMs)

        val headerAlignCode = 8
        val headerMarginL = max(24, style.paddingLeft)
        val headerMarginR = max(24, style.paddingRight)

        val padExtra = (metaHeader?.paddingTopExtraPx ?: 0).coerceAtLeast(0)
        val headerMarginV = max(40, style.paddingTop) + padExtra

        val headerFontTitle = (style.fontSizePx * 0.8).roundToInt().coerceAtLeast(24)
        val headerFontMeta = (style.fontSizePx * 0.55).roundToInt().coerceAtLeast(18)

        val titleColor = (metaHeader?.headerTitleColorHex ?: "#FFFFFF")
        val metaColor = (metaHeader?.headerMetaColorHex ?: "#FFFFFF")

        fun styleHeaderTitle() = buildString {
            val primary = rgbaToAss(1.0, titleColor)
            append("Style: hdr,${style.fontFamily},$headerFontTitle,")
            append("$primary,&H000000FF,&H00000000,&H00000000,")
            append("-1,0,0,0,100,100,0,0,1,2,${if (style.shadow) 2 else 0},")
            append("$headerAlignCode,$headerMarginL,$headerMarginR,$headerMarginV,0")
        }

        fun styleHeaderMeta() = buildString {
            val primary = rgbaToAss(0.95, metaColor)
            append("Style: hdrMeta,${style.fontFamily},$headerFontMeta,")
            append("$primary,&H000000FF,&H00000000,&H00000000,")
            append("0,0,0,0,100,100,0,0,1,2,${if (style.shadow) 2 else 0},")
            append("$headerAlignCode,$headerMarginL,$headerMarginR,${headerMarginV + headerFontTitle + 10},0")
        }

        // ── Разделитель ШАПКИ (headerSeparator*) ─────────────────────
        val hdrSepColor   = (metaHeader?.headerSeparatorColorHex ?: "#FFFFFF")
        val hdrSepOpacity = (metaHeader?.headerSeparatorOpacity ?: 0.50).coerceIn(0.0, 1.0)
        val hdrSepHeight  = (metaHeader?.headerSeparatorHeightPx ?: 0).coerceAtLeast(0)
        val hdrSepEnabled = (metaHeader?.headerSeparatorEnabled == true) && hdrSepHeight > 0

        fun styleHeaderSeparator() = buildString {
            val sep = rgbaToAss(hdrSepOpacity, hdrSepColor)
            append("Style: hdrSep,${style.fontFamily},100,")
            append("$sep,&H000000FF,&H00000000,&H00000000,")
            append("0,0,0,0,100,100,0,0,1,0,0,")
            append("$headerAlignCode,$headerMarginL,$headerMarginR,${headerMarginV + headerFontTitle + headerFontMeta + 14},0")
        }

        fun hardWrap(text: String): String {
            val marginL = max(24, (width * 0.05).roundToInt())
            val marginR = max(24, (width * 0.05).roundToInt())
            val safeAvailPx = (width - marginL - marginR).coerceAtLeast(160)
            val avgCharPxBold = max(7.5, style.fontSizePx * 0.52)
            val outlineOverhead = 2 * 0.6
            val maxChars = ((safeAvailPx - outlineOverhead) / avgCharPxBold).toInt().coerceAtLeast(12)
            val words = text.trim().split(Regex("\\s+"))
            if (words.isEmpty()) return ""
            val sb = StringBuilder()
            var curLen = 0
            fun nl() {
                sb.append("\\N"); curLen = 0
            }
            for (w in words) {
                if (w.length > maxChars) {
                    var i = 0
                    while (i < w.length) {
                        val take = min(maxChars, w.length - i)
                        if (curLen != 0) nl()
                        sb.append(w.substring(i, i + take))
                        curLen = take
                        i += take
                    }
                    continue
                }
                val add = if (curLen == 0) w.length else w.length + 1
                if (curLen > 0 && curLen + add > maxChars) {
                    nl(); sb.append(w); curLen = w.length
                } else {
                    if (curLen > 0) sb.append(' ')
                    sb.append(w); curLen += add
                }
            }
            return sb.toString()
        }

        target.writeText(buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: $width")
            appendLine("PlayResY: $height")
            appendLine("ScaledBorderAndShadow: yes")
            appendLine("WrapStyle: 2")
            appendLine("Collisions: Normal")
            appendLine()

            appendLine("[V4+ Styles]")
            appendLine(
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, " +
                        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
                        "Alignment, MarginL, MarginR, MarginV, Encoding"
            )
            appendLine(styleLine("cur", style.current, forceBold = true))
            if (shouldRenderHeader(metaHeader)) {
                appendLine(styleHeaderTitle())
                appendLine(styleHeaderMeta())
                if (hdrSepEnabled) appendLine(styleHeaderSeparator())
            }
            appendLine()

            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

            fun msToAss(t: Long): String {
                val cs = t.coerceIn(0L, totalMs) / 10
                val h = cs / 360000
                val m = (cs / 6000) % 60
                val s = (cs / 100) % 60
                val c = cs % 100
                return "%01d:%02d:%02d.%02d".format(h, m, s, c)
            }

            if (shouldRenderHeader(metaHeader)) {
                val title = metaHeader?.storyTitle?.trim().orEmpty()
                val chips = buildChips(metaHeader)
                appendLine("Dialogue: 3,${msToAss(0)},${msToAss(totalMs)},hdr,,0,0,0,,{\\q2\\an8}${esc(title)}")
                if (chips.isNotEmpty()) {
                    appendLine("Dialogue: 3,${msToAss(0)},${msToAss(totalMs)},hdrMeta,,0,0,0,,{\\q2\\an8}${esc(chips)}")
                }
                if (hdrSepEnabled) {
                    val safeAvailPx = (width - headerMarginL - headerMarginR).coerceAtLeast(160)
                    val path = "m 0 0 l $safeAvailPx 0 l $safeAvailPx $hdrSepHeight l 0 $hdrSepHeight"
                    appendLine("Dialogue: 3,${msToAss(0)},${msToAss(totalMs)},hdrSep,,0,0,0,,{\\q2\\an8\\p1\\fs100\\bord0\\shad0}$path")
                }
            }

            val x = width / 2
            val lineStep = (style.fontSizePx * 1.6).roundToInt()
            val marginV = max(64, (height * 0.12).roundToInt())
            val y = (height - marginV - lineStep)
            val posTag = "{\\q2\\an2\\pos($x,$y)}"

            for (j in cues.items.indices) {
                val t0 = cues.items[j].startMs
                val t1 = if (j + 1 < cues.items.size) cues.items[j + 1].startMs else totalMs
                if (t1 <= t0) continue
                val txt = hardWrap(lines[j])
                appendLine("Dialogue: 0,${msToAss(t0)},${msToAss(t1)},cur,,0,0,0,,$posTag${esc(txt)}")
            }
        })
    }

    fun buildAssFilePanelLeftReplace(
        target: File,
        cues: EpisodeCuesPayload,
        lines: List<String>,
        style: OverlayStyle,
        width: Int,
        height: Int,
        panelWidthPx: Int,
        panelInnerPaddingPx: Int,
        visibleLines: Int = 9,
        metaHeader: MetaHeaderSpec? = null
    ) {
        require(cues.items.size == lines.size) {
            "lines count (${lines.size}) must match cues (${cues.items.size})"
        }
        val totalMs = max(1L, cues.totalMs)
        if (cues.items.isEmpty() || visibleLines <= 0) {
            target.writeText("")
            return
        }

        val padL = panelInnerPaddingPx.coerceAtLeast(24)
        val padTBase = panelInnerPaddingPx.coerceAtLeast(24)
        val padR = panelInnerPaddingPx.coerceAtLeast(24)

        val padExtra = (metaHeader?.paddingTopExtraPx ?: 0).coerceAtLeast(0)
        val padT = padTBase + padExtra

        val availTextW = (panelWidthPx - padL - padR).coerceAtLeast(120)
        val lineStep = max(20, (style.fontSizePx * 1.35).roundToInt())

        val hdrTitleSize = (style.fontSizePx * 0.78).roundToInt().coerceAtLeast(22)
        val hdrMetaSize = (style.fontSizePx * 0.56).roundToInt().coerceAtLeast(16)
        val allowTwoLine = metaHeader?.allowTwoLineTitleInPanel == true
        val titleLineH = hdrTitleSize + 6
        val titleLines = if (shouldRenderHeader(metaHeader) && allowTwoLine) 2 else 1
        val headerReservedH = if (shouldRenderHeader(metaHeader))
            (titleLines * titleLineH) + 10 + hdrMetaSize + 20
        else 0
        val baseY = padT + headerReservedH

        // ── ПАНЕЛЬНЫЙ разделитель
        val sepColor = (metaHeader?.separatorColorHex ?: "#FFFFFF")
        val sepOpacity = (metaHeader?.separatorOpacity ?: 0.50).coerceIn(0.0, 1.0)
        val sepHeight = (metaHeader?.separatorHeightPx ?: 2).coerceAtLeast(0)
        val sepEnabled = (metaHeader?.separatorEnabled ?: true) && sepHeight > 0

        val avgCharPxBold = max(7.0, style.fontSizePx * 0.50)
        val outlineOverhead = 2 * 0.6
        val maxChars = ((availTextW - outlineOverhead) / avgCharPxBold).toInt().coerceAtLeast(10)

        fun wrapHard(s: String): List<String> {
            val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            var cur = StringBuilder()
            var curLen = 0
            fun flush() {
                if (curLen > 0) {
                    out += cur.toString(); cur = StringBuilder(); curLen = 0
                }
            }
            for (w in words) {
                if (w.length > maxChars) {
                    var i = 0
                    while (i < w.length) {
                        val take = min(maxChars, w.length - i)
                        if (curLen != 0) flush()
                        out += w.substring(i, i + take)
                        i += take
                    }
                    continue
                }
                val add = if (curLen == 0) w.length else w.length + 1
                if (curLen > 0 && curLen + add > maxChars) {
                    flush()
                    cur.append(w); curLen = w.length
                } else {
                    if (curLen > 0) cur.append(' ')
                    cur.append(w); curLen += add
                }
            }
            flush()
            return out
        }

        fun wrapTitleForPanel(title: String, maxLines: Int): String {
            if (title.isBlank()) return ""
            val words = title.trim().split(Regex("\\s+"))
            val out = mutableListOf(StringBuilder())
            for (w in words) {
                var placed = false
                while (!placed) {
                    val cur = out.last()
                    val cand = if (cur.isEmpty()) w else "$cur $w"
                    if (wrapHard(cand).size <= 1) {
                        cur.clear(); cur.append(cand); placed = true
                    } else {
                        if (out.size >= maxLines) {
                            cur.append(" ").append(w); placed = true
                        } else {
                            out += StringBuilder()
                        }
                    }
                }
            }
            return out.take(maxLines).joinToString("\\N") { it.toString().trim() }
        }

        data class Seg(val text: String, val start: Long, val end: Long)

        val segs = ArrayList<Seg>(lines.size * 2)
        val partsPerSentence = IntArray(lines.size) { 0 }
        val sentenceStartSegIdx = IntArray(lines.size) { 0 }

        var segCursor = 0
        for (i in lines.indices) {
            val t0 = cues.items[i].startMs
            val t1 = cues.items[i].endMs
            if (t1 <= t0) continue

            val parts = wrapHard(lines[i])
            if (parts.isEmpty()) continue

            sentenceStartSegIdx[i] = segCursor
            partsPerSentence[i] = parts.size

            val totalChars = parts.sumOf { it.length }.coerceAtLeast(1)
            var acc = 0
            for (p in parts) {
                val prev = acc
                acc += p.length
                val st = t0 + ((t1 - t0) * prev.toDouble() / totalChars).toLong()
                val en = t0 + ((t1 - t0) * acc.toDouble() / totalChars).toLong()
                segs += Seg(p, st, max(st + 1, en))
                segCursor++
            }
        }

        if (segs.isEmpty()) {
            target.writeText("")
            return
        }

        data class Page(val fromIdx: Int, val toIdx: Int, val tStart: Long, val tEnd: Long)

        val pages = mutableListOf<Page>()

        var sent = 0
        while (sent < lines.size) {
            val partsThis = partsPerSentence[sent].coerceAtLeast(1)
            val fromSeg = sentenceStartSegIdx[sent]
            var toSeg = fromSeg + partsThis - 1

            if (partsThis > visibleLines) {
                var cur = 0
                while (cur < partsThis) {
                    val chunk = min(visibleLines, partsThis - cur)
                    val chFrom = fromSeg + cur
                    val chTo   = chFrom + chunk - 1
                    val tStart = segs[chFrom].start
                    val tEnd   = (chFrom..chTo).maxOf { segs[it].end }
                    pages += Page(chFrom, chTo, tStart, tEnd)
                    cur += chunk
                }
                sent++
                continue
            }

            var used = partsThis
            var s2 = sent + 1
            while (s2 < lines.size) {
                val need = partsPerSentence[s2].coerceAtLeast(1)
                if (used + need > visibleLines) break
                val addFrom = sentenceStartSegIdx[s2]
                val addTo = addFrom + need - 1
                toSeg = addTo
                used += need
                s2++
            }

            val tStart = segs[fromSeg].start
            val tEnd = (fromSeg..toSeg).maxOf { segs[it].end }
            pages += Page(fromSeg, toSeg, tStart, tEnd)
            sent = s2
        }

        fun stylePanelLine(name: String, ls: OverlayLineStyle, forceBold: Boolean, alignCode: Int, marginV: Int) =
            buildString {
                val primary = rgbaToAss(ls.opacity, ls.colorHex)
                val shadow = if (style.shadow) 2 else 0
                append("Style: $name,${style.fontFamily},${style.fontSizePx},")
                append("$primary,&H000000FF,&H00000000,&H00000000,")
                append("${if (forceBold) -1 else 0},0,0,0,100,100,0,0,1,2,$shadow,")
                append("$alignCode,$padL,0,$marginV,0")
            }

        val titleColor = (metaHeader?.headerTitleColorHex ?: "#FFFFFF")
        val metaColor = (metaHeader?.headerMetaColorHex ?: "#FFFFFF")

        fun styleHeaderTitlePanel() = buildString {
            val primary = rgbaToAss(1.0, titleColor)
            append("Style: hdrP,${style.fontFamily},$hdrTitleSize,")
            append("$primary,&H000000FF,&H00000000,&H00000000,")
            append("-1,0,0,0,100,100,0,0,1,2,${if (style.shadow) 2 else 0},")
            append("7,$padL,0,$padT,0")
        }

        fun styleHeaderMetaPanel() = buildString {
            val primary = rgbaToAss(0.95, metaColor)
            append("Style: hdrPMeta,${style.fontFamily},$hdrMetaSize,")
            append("$primary,&H000000FF,&H00000000,&H00000000,")
            append("0,0,0,0,100,100,0,0,1,2,${if (style.shadow) 2 else 0},")
            append("7,$padL,0,${padT + hdrTitleSize + 10},0")
        }

        fun styleHeaderSeparatorPanel() = buildString {
            val sep = rgbaToAss(sepOpacity, sepColor)
            append("Style: hdrPSep,${style.fontFamily},100,")
            append("$sep,&H000000FF,&H00000000,&H00000000,")
            append("0,0,0,0,0,0,0,0,1,2,0,7,$padL,0,${padT + hdrTitleSize + hdrMetaSize + 14},0")
        }

        target.writeText(buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: $width")
            appendLine("PlayResY: $height")
            appendLine("ScaledBorderAndShadow: yes")
            appendLine("WrapStyle: 2")
            appendLine("Collisions: Normal")
            appendLine()

            appendLine("[V4+ Styles]")
            appendLine(
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, " +
                        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
                        "Alignment, MarginL, MarginR, MarginV, Encoding"
            )
            appendLine(stylePanelLine("p_cur", style.current.copy(opacity = 1.0), true, 7, padT))
            appendLine(stylePanelLine("p_dim", style.previous.copy(opacity = 0.72), false, 7, padT))
            if (shouldRenderHeader(metaHeader)) {
                appendLine(styleHeaderTitlePanel())
                appendLine(styleHeaderMetaPanel())
                if (sepEnabled) appendLine(styleHeaderSeparatorPanel())
            }
            appendLine()

            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

            fun msToAss(t: Long): String {
                val cs = t.coerceIn(0L, totalMs) / 10
                val h = cs / 360000
                val m = (cs / 6000) % 60
                val s = (cs / 100) % 60
                val c = cs % 100
                return "%01d:%02d:%02d.%02d".format(h, m, s, c)
            }

            fun add(styleName: String, t0: Long, t1: Long, x: Int, y: Int, text: String, layer: Int) {
                if (t1 <= t0 || text.isBlank()) return
                appendLine(
                    "Dialogue: $layer,${msToAss(t0)},${msToAss(t1)},$styleName,,0,0,0,,{\\q2\\an7\\pos($x,$y)}${esc(text)}"
                )
            }

            if (shouldRenderHeader(metaHeader)) {
                val rawTitle = metaHeader?.storyTitle?.trim().orEmpty()
                val title = if (allowTwoLine) wrapTitleForPanel(rawTitle, 2) else rawTitle
                val chips = buildChips(metaHeader)
                add("hdrP", 0, totalMs, padL, padT, title, 3)
                if (chips.isNotEmpty()) add("hdrPMeta", 0, totalMs, padL, padT + hdrTitleSize + 10, chips, 3)

                if (sepEnabled) {
                    val sepY = padT + titleLines * titleLineH + 10 + hdrMetaSize + 14
                    val path = "m 0 0 l $availTextW 0 l $availTextW $sepHeight l 0 $sepHeight"
                    appendLine(
                        "Dialogue: 3,${msToAss(0)},${msToAss(totalMs)},hdrPSep,,0,0,0,," +
                                "{\\q2\\an7\\p1\\fs100\\bord0\\shad0\\pos($padL,$sepY)}$path"
                    )
                }
            }

            for (pg in pages) {
                var lineIndex = 0
                for (k in pg.fromIdx..pg.toIdx) {
                    val seg = segs[k]
                    val y = baseY + lineIndex * lineStep

                    add("p_dim", pg.tStart, seg.start, padL, y, seg.text, 1)
                    add("p_cur", seg.start, seg.end, padL, y, seg.text, 2)
                    add("p_dim", seg.end, pg.tEnd, padL, y, seg.text, 1)

                    lineIndex++
                }
            }
        })
    }

    private fun rgbaToAss(opacity: Double, hex: String): String {
        val c = hex.removePrefix("#")
        require(c.length >= 6) { "Bad color hex: $hex" }
        val a = (255.0 - (opacity.coerceIn(0.0, 1.0) * 255.0)).toInt().coerceIn(0, 255)
        val r = c.take(2).toInt(16)
        val g = c.substring(2, 4).toInt(16)
        val b = c.substring(4, 6).toInt(16)
        return "&H%02X%02X%02X%02X".format(a, b, g, r)
    }

    private fun esc(s: String) = s.replace("\n", "\\N").replace("{", "\\{").replace("}", "\\}")

    private fun shouldRenderHeader(meta: MetaHeaderSpec?): Boolean {
        if (meta == null) return false
        if (!meta.visible) return false
        val hasTitle = !meta.storyTitle.isNullOrBlank()
        val hasAnyChip = !meta.level.isNullOrBlank() || !meta.languageName.isNullOrBlank()
        return hasTitle || hasAnyChip
    }

    private fun buildChips(meta: MetaHeaderSpec?): String {
        if (meta == null) return ""
        val parts = mutableListOf<String>()
        if (!meta.level.isNullOrBlank()) parts += meta.level.trim()
        if (!meta.languageName.isNullOrBlank()) parts += meta.languageName.trim()
        return parts.joinToString("  •  ")
    }
}

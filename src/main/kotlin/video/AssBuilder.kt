package com.codersergg.video

import com.codersergg.model.video.EpisodeCuesPayload
import com.codersergg.model.video.OverlayLineStyle
import com.codersergg.model.video.OverlayStyle
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object AssBuilder {

    /**
     * ГОРИЗОНТАЛЬ + ХОРЕОГРАФИЯ на 3 строки.
     * ДОРАБОТКА: длинные реплики расщепляем на две части (A+B) и вставляем
     * дополнительный старт "mid" внутри исходного интервала [t(j), t(j+1)].
     * Дальше применяем прежнюю логику к уже расширенному ряду (A, B, ...).
     */
    fun buildAssFile(
        target: File,
        cues: EpisodeCuesPayload,
        lines: List<String>,
        style: OverlayStyle,
        width: Int,
        height: Int
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

        fun rgbaToAss(opacity: Double, hex: String): String {
            val a = (255.0 - (opacity.coerceIn(0.0, 1.0) * 255.0)).toInt().coerceIn(0, 255)
            val c = hex.removePrefix("#")
            val r = c.substring(0, 2).toInt(16)
            val g = c.substring(2, 4).toInt(16)
            val b = c.substring(4, 6).toInt(16)
            return "&H%02X%02X%02X%02X".format(a, b, g, r)
        }

        val isLeft = style.align.equals("LEFT", true)
        val alignCode = if (isLeft) 1 else 2 // 1=bottom-left, 2=bottom-center

        // Поля — узкие, чтобы реально занять ширину кадра
        val marginL = max(32, (width * 0.04).roundToInt())
        val marginR = max(32, (width * 0.04).roundToInt())
        val marginV = max((height * 0.09).roundToInt(), 52)
        val lineStep = (style.fontSizePx * 1.52).roundToInt()

        // --- расчёт ширины и «жёсткий» перенос ---
        val safeAvailPx = (width - marginL - marginR).coerceAtLeast(160)
        val avgCharPxNormal = max(7.5, style.fontSizePx * 0.46)
        val avgCharPxBold   = max(7.5, style.fontSizePx * 0.52)
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
            fun nl() { sb.append("\\N"); curLen = 0 }
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

        val totalMs = max(1L, cues.totalMs)

        // ===================== ПРЕПРОЦЕСС: расширяем ряд =====================
        // Каждый исходный элемент j (t_j .. t_{j+1}) может превратиться в:
        //   — A (первая строка) в t_j
        //   — B (вторая строка) в t_mid, где t_mid внутри интервала, пропорционально длинам A/B
        // вместо прежнего splitTwo(text)
        data class Two(val a: String, val b: String?)
        fun splitTwo(text: String): Two {
            // первичная разбивка по нашему жёсткому переносу
            val wrapped = hardWrap(text, isBold = true) // центр — жирный
            val parts = wrapped.split("\\N")

            // короткая реплика — не делим
            if (parts.isEmpty() || parts.size == 1) return Two(parts.firstOrNull().orEmpty(), null)

            // склеиваем всё, что после первого переноса, во вторую часть
            var aWords = parts[0].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
            var bWords = parts.drop(1).joinToString(" ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()

            // минимальные требования к «хвосту»
            val minTailWords = 2
            fun chars(lst: List<String>) = lst.sumOf { it.length } + (lst.size - 1).coerceAtLeast(0)
            fun ratioB(a: List<String>, b: List<String>): Double {
                val ca = chars(a).toDouble()
                val cb = chars(b).toDouble()
                val sum = (ca + cb).coerceAtLeast(1.0)
                return cb / sum
            }

            // если во второй части 0–1 слово, или она слишком короткая (<35%), перебалансируем
            while (
                (bWords.size < minTailWords || ratioB(aWords, bWords) < 0.35) &&
                aWords.size > minTailWords
            ) {
                // переносим последнее слово из A в начало B
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
        val expandedLines  = ArrayList<String>(lines.size * 2)

        for (j in lines.indices) {
            val t0 = startsOrig[j]
            val t1 = if (j + 1 < startsOrig.size) startsOrig[j + 1] else totalMs
            val cur = linesTwo[j]

            if (cur.b == null) {
                // короткое — одна часть
                expandedStarts += t0
                expandedLines  += cur.a
            } else {
                // длинное — две части A+B
                val lenA = cur.a.length.toDouble().coerceAtLeast(1.0)
                val lenB = cur.b.length.toDouble().coerceAtLeast(1.0)
                // середина по доле A, но в безопасных пределах
                val ratio = (lenA / (lenA + lenB)).coerceIn(0.35, 0.65)
                val dur = (t1 - t0).coerceAtLeast(2L * shiftMs) // чтобы была анимация обеих частей
                val tMid = (t0 + (dur * ratio)).toLong().coerceIn(t0 + shiftMs, t1 - shiftMs)

                expandedStarts += t0
                expandedLines  += cur.a
                expandedStarts += tMid
                expandedLines  += cur.b
            }
        }
        // ====================================================================

        target.writeText(buildString {
            // Header
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("PlayResX: $width")
            appendLine("PlayResY: $height")
            appendLine("ScaledBorderAndShadow: yes")
            appendLine("WrapStyle: 2")
            appendLine("Collisions: Normal")
            appendLine()

            // Styles
            appendLine("[V4+ Styles]")
            appendLine(
                "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, " +
                        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, " +
                        "Alignment, MarginL, MarginR, MarginV, Encoding"
            )
            appendLine(styleLine("prev",   style.previous, forceBold = false, minOpacity = 0.70))
            appendLine(styleLine("cur",    style.current,  forceBold = true,  minOpacity = 1.00))
            appendLine(styleLine("next",   style.next,     forceBold = false, minOpacity = 0.70))
            appendLine()

            // Events
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
            fun esc(s: String) = s.replace("\n", "\\N").replace("{", "\\{").replace("}", "\\}")

            val x = if (isLeft) marginL else width / 2
            fun yFor(idxFromBottom: Int) = (height - marginV - idxFromBottom * lineStep)

            fun posTag(y: Int) = "{\\q2\\an$alignCode\\pos($x,$y)}"
            fun moveTag(y0: Int, y1: Int, durationMs: Long) =
                "{\\q2\\an$alignCode\\move($x,$y0,$x,$y1,0,$durationMs)}"

            fun add(layer: Int, t0: Long, t1: Long, styleName: String, tag: String, text: String) {
                if (t1 <= t0) return
                if (text.isEmpty()) return
                appendLine("Dialogue: $layer,${msToAss(t0)},${msToAss(t1)},$styleName,,0,0,0,,$tag${esc(text)}")
            }

            // Работаем уже с "расширенными" рядами
            val N = expandedLines.size
            fun t(i: Int): Long = when {
                i < 0 -> 0L
                i < N -> expandedStarts[i]
                else -> totalMs
            }

            for (j in 0 until N) {
                val curText  = expandedLines[j]

                val toMidStart = t(j)
                val toMidEnd   = min(totalMs, t(j) + shiftMs)
                val holdMidEnd = t(j + 1)
                val toTopEnd   = min(totalMs, t(j + 1) + shiftMs)
                val holdTopEnd = t(j + 2)
                val exitEnd    = min(totalMs, t(j + 2) + shiftMs)

                val yBottom = yFor(0)
                val yMid    = yFor(1)
                val yTop    = yFor(2)
                val yBelow  = yFor(-1)
                val yAbove  = yFor(3)

                // current: низ -> середина и удержание
                add(2, toMidStart, toMidEnd, "cur",  moveTag(yBottom, yMid, (toMidEnd - toMidStart)), curText)
                add(2, toMidEnd,   holdMidEnd,"cur", posTag(yMid),                                    curText)

                // prev: середина -> верх, удержание, уход
                add(0, holdMidEnd, toTopEnd,  "prev", moveTag(yMid, yTop, (toTopEnd - holdMidEnd)),   curText)
                add(0, toTopEnd,   holdTopEnd,"prev", posTag(yTop),                                   curText)
                add(0, holdTopEnd, exitEnd,   "prev", moveTag(yTop, yAbove, (exitEnd - holdTopEnd)),  curText)

                // next внизу — всегда берем след. элемент расширенного ряда
                if (j + 1 < N) {
                    val nextText = expandedLines[j + 1]
                    val nextInStart = t(j)
                    val nextInEnd   = min(totalMs, t(j) + shiftMs)
                    add(1, nextInStart, nextInEnd, "next", moveTag(yBelow, yBottom, (nextInEnd - nextInStart)), nextText)
                    add(1, nextInEnd,   t(j + 1), "next", posTag(yBottom), nextText)
                }
            }
        })
    }

    // ----------------- ВЕРТИКАЛЬ: одно предложение, моментальная замена -----------------
    fun buildAssFileVerticalInstant(
        target: File,
        cues: EpisodeCuesPayload,
        lines: List<String>,
        style: OverlayStyle,
        width: Int,
        height: Int
    ) {
        require(cues.items.size == lines.size) {
            "lines count (${lines.size}) must match cues (${cues.items.size})"
        }
        if (cues.items.isEmpty()) {
            target.writeText("")
            return
        }

        val outline = 2
        val shadow = if (style.shadow) 2 else 0

        fun rgbaToAss(opacity: Double, hex: String): String {
            val a = (255.0 - (opacity.coerceIn(0.0, 1.0) * 255.0)).toInt().coerceIn(0, 255)
            val c = hex.removePrefix("#")
            val r = c.substring(0, 2).toInt(16)
            val g = c.substring(2, 4).toInt(16)
            val b = c.substring(4, 6).toInt(16)
            return "&H%02X%02X%02X%02X".format(a, b, g, r)
        }

        val alignCode = 2 // bottom-center
        val marginL = max(24, (width * 0.05).roundToInt())
        val marginR = max(24, (width * 0.05).roundToInt())
        val marginV = max(64, (height * 0.12).roundToInt())
        val lineStep = (style.fontSizePx * 1.6).roundToInt()

        val safeAvailPx = (width - marginL - marginR).coerceAtLeast(160)
        val avgCharPxBold = max(7.5, style.fontSizePx * 0.52)
        val outlineOverhead = 2 * 0.6

        fun calcLimit(): Int =
            ((safeAvailPx - outlineOverhead) / avgCharPxBold).toInt().coerceAtLeast(12)

        fun hardWrap(text: String): String {
            val maxChars = calcLimit()
            val words = text.trim().split(Regex("\\s+"))
            if (words.isEmpty()) return ""
            val sb = StringBuilder()
            var curLen = 0
            fun nl() { sb.append("\\N"); curLen = 0 }
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

        fun styleLine(name: String, ls: OverlayLineStyle, forceBold: Boolean) = buildString {
            val primary = rgbaToAss(ls.opacity, ls.colorHex)
            append("Style: $name,${style.fontFamily},${style.fontSizePx},")
            append("$primary,&H000000FF,&H00000000,&H00000000,")
            append("${if (forceBold) -1 else 0},0,0,0,100,100,0,0,1,2,$shadow,")
            append("$alignCode,$marginL,$marginR,$marginV,0")
        }

        val totalMs = max(1L, cues.totalMs)

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

            fun esc(s: String) = s.replace("\n", "\\N").replace("{", "\\{").replace("}", "\\}")

            val x = width / 2
            val y = (height - marginV - lineStep)
            val posTag = "{\\q2\\an$alignCode\\pos($x,$y)}"

            for (j in cues.items.indices) {
                val t0 = cues.items[j].startMs
                val t1 = if (j + 1 < cues.items.size) cues.items[j + 1].startMs else totalMs
                if (t1 <= t0) continue
                val txt = hardWrap(lines[j])
                appendLine("Dialogue: 0,${msToAss(t0)},${msToAss(t1)},cur,,0,0,0,,$posTag${esc(txt)}")
            }
        })
    }
}

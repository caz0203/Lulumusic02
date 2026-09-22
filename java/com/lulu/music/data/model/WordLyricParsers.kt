package com.lulu.music.data.model

/**
 * 逐字歌词解析器：网易云 YRC、QQ 音乐 QRC、酷狗 KRC。
 *
 * 三者的**明文**结构都是「一行一个时间头 + 行内若干带时间的词片段」，但**词的标记位置有两种约定**：
 *
 * - 标记在词**前面**（YRC / KRC）：`[行起点ms,行时长ms](词起点ms,词时长ms,0)词…`
 *   KRC 是 `<词相对偏移ms,词时长ms,0>词…`
 * - 标记在词**后面**（QRC）：`[行起点ms,行时长ms]词(词起点ms,词时长ms)…`
 *
 * 时间单位都是毫秒：YRC / QRC 的**词时间是绝对毫秒**，KRC 的**词时间是相对行起点的偏移**。
 *
 * 这里只负责把明文变成 [LyricLine] / [LyricWord]。解密与解压分别在
 * `com.lulu.music.data.net.QrcCipher`（QRC）与 `com.lulu.music.data.net.KrcCodec`（KRC）。
 *
 * 所有解析器都是**纯函数且不抛异常**：畸形输入一律返回「能解析出来的部分」或空列表，
 * 让调用方安全地退回整行歌词。
 */
object YrcParser {

    /** YRC 的词标记是 `(词起点ms,词时长ms,0)`；第三个字段是保留位，恒为 0。标记在词前面。 */
    private val WORD_MARKER = Regex("""\((\d+),(\d+),\d+\)""")

    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrEmpty()) return emptyList()
        return parseWordTimedLines(raw, WORD_MARKER, relativeWordTime = false, markerBeforeText = true)
    }
}

object QrcParser {

    /** QRC 的词标记只有两个数字：`(词起点ms,词时长ms)`；标记在词**后面**。 */
    private val WORD_MARKER = Regex("""\((\d+),(\d+)\)""")

    private const val LYRIC_CONTENT_KEY = "LyricContent=\""

    /**
     * 解析解密后的 QRC 明文。[raw] 通常是外层 XML（`<QrcInfos>…<Lyric_1 LyricContent="…"/>`），
     * 这里会先取出 `LyricContent` 属性并还原 XML 实体；若传入的已经是纯 QRC 正文则直接解析。
     */
    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrEmpty()) return emptyList()
        return parseWordTimedLines(unwrapXml(raw), WORD_MARKER, relativeWordTime = false, markerBeforeText = false)
    }

    /**
     * 取出 `LyricContent` 属性值。
     *
     * 不用正则：属性值的结束位置是「引号 + 若干空白 + 斜杠大于号」，这样属性值内部即使出现
     * 裸引号（QQ 偶尔不转义）也不会被第一个引号截断。手写扫描同时避开了「正则里出现
     * 星号紧跟斜杠」这种会干扰本仓库注释扫描的写法。
     */
    internal fun unwrapXml(raw: String): String {
        val start = raw.indexOf(LYRIC_CONTENT_KEY)
        if (start < 0) return raw
        val from = start + LYRIC_CONTENT_KEY.length
        val end = attributeEnd(raw, from)
        if (end < 0) return raw
        return unescapeXmlEntities(raw.substring(from, end))
    }

    /** 从 [from] 起找「引号 + 空白 + 斜杠大于号」里的那个引号下标；找不到返回 -1。 */
    private fun attributeEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '"') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j + 1 < text.length && text[j] == '/' && text[j + 1] == '>') return i
            }
            i++
        }
        return -1
    }

    internal fun unescapeXmlEntities(text: String): String {
        if (!text.contains('&')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '&') {
                sb.append(ch)
                i++
                continue
            }
            val end = text.indexOf(';', i + 1)
            if (end < 0 || end - i > 12) {
                sb.append(ch)
                i++
                continue
            }
            val body = text.substring(i + 1, end)
            val decoded = when {
                body == "amp" -> "&"
                body == "lt" -> "<"
                body == "gt" -> ">"
                body == "quot" -> "\""
                body == "apos" -> "'"
                body == "nbsp" -> " "
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { codePointToString(it) }
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { codePointToString(it) }
                else -> null
            }
            if (decoded == null) {
                sb.append(ch)
                i++
            } else {
                sb.append(decoded)
                i = end + 1
            }
        }
        return sb.toString()
    }

    private fun codePointToString(code: Int): String? {
        if (code <= 0 || code > 0x10FFFF) return null
        return try {
            String(Character.toChars(code))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

object KrcParser {

    /** KRC 的词标记是 `<词相对偏移ms,词时长ms,0>`；标记在词前面、时间是相对行起点的偏移。 */
    private val WORD_MARKER = Regex("""<(\d+),(\d+),\d+>""")

    /**
     * 解析解密解压后的 KRC 明文。
     *
     * 会跳过 `[ti:…]` / `[offset:…]` / `[language:…]` 这类元信息行（它们不带 `[起点,时长]` 头），
     * 也会跳过 `[bg:…]` 背景和声行 —— 本模型是「一行一条」的扁平结构，没有背景声通道，
     * 把背景声混进来会和主歌词重复显示。
     */
    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrEmpty()) return emptyList()
        return parseWordTimedLines(raw, WORD_MARKER, relativeWordTime = true, markerBeforeText = true)
    }
}

// ---------------------------------------------------------------------------
// 三种格式共用的行内解析
// ---------------------------------------------------------------------------

/** `[行起点ms,行时长ms]` 头；YRC / QRC / KRC 都是这个形态。 */
private val TIMED_LINE_HEADER = Regex("""^\[(\d+),(\d+)\](.*)$""")

private fun parseWordTimedLines(
    raw: String,
    wordMarker: Regex,
    relativeWordTime: Boolean,
    markerBeforeText: Boolean,
): List<LyricLine> {
    val out = ArrayList<LyricLine>()
    for (rawLine in raw.split('\n')) {
        val line = rawLine.trim().removePrefix("\uFEFF").trim()
        if (line.isEmpty()) continue
        val header = TIMED_LINE_HEADER.find(line) ?: continue
        val lineStartMs = header.groupValues[1].toDoubleOrNull() ?: continue
        val body = header.groupValues[3]
        if (body.isEmpty()) continue

        val markers = wordMarker.findAll(body).toList()
        val words = ArrayList<LyricWord>(markers.size)
        for (i in markers.indices) {
            val marker = markers[i]
            // 标记在词前面（YRC/KRC）：词从标记之后开始，到下一个标记之前结束。
            // 标记在词后面（QRC）：词从上一个标记之后开始，到本标记之前结束。
            val previousEnd = if (i > 0) markers[i - 1].range.last + 1 else 0
            val nextStart = if (i + 1 < markers.size) markers[i + 1].range.first else body.length
            val textStart = if (markerBeforeText) marker.range.last + 1 else previousEnd
            val textEnd = if (markerBeforeText) nextStart else marker.range.first
            if (textEnd < textStart) continue
            val startMs = marker.groupValues[1].toDoubleOrNull() ?: 0.0
            val durationMs = marker.groupValues[2].toDoubleOrNull() ?: 0.0
            words.add(
                LyricWord(
                    time = wordTimeSeconds(startMs, lineStartMs, relativeWordTime),
                    duration = durationMs / 1000.0,
                    text = body.substring(textStart, textEnd),
                )
            )
        }

        // 行文本 = 去掉全部时间标记后的正文，与整行 LRC 看到的字面一致（翻译按时间合并时要用）。
        val text = wordMarker.replace(body, "").trim()
        out.add(
            LyricLine(
                time = lineStartMs / 1000.0,
                text = text,
                words = words.filter { it.text.isNotEmpty() || it.duration > 0.0 },
            )
        )
    }
    // 稳定性排序：时间相同的行保持文件内顺序（Kotlin 的 sortedBy 是稳定排序）。
    return out.sortedBy { it.time }
}

private fun wordTimeSeconds(startMs: Double, lineStartMs: Double, relative: Boolean): Double =
    (if (relative) lineStartMs + startMs else startMs) / 1000.0

// ---------------------------------------------------------------------------
// 兜底契约
// ---------------------------------------------------------------------------

/**
 * 「有真数据就用真的，没有就退回整行高亮」这条规则的**唯一实现**，三个平台共用。
 *
 * 输入永远是两份解析结果：整行歌词（一定尽量拿到）与逐字歌词（可能拿不到/解析不出词）。
 * 输出保证：
 *  1. [wordLevel] 里存在真逐字行（[LyricHighlight.hasWordTiming]）时，返回逐字歌词，
 *     并把 [lineLevel] 里的翻译按时间贴上去；
 *  2. 否则原样返回 [lineLevel] —— **即使 [wordLevel] 空、畸形、或整首歌没有 yrc/QRC/KRC**，
 *     只要整行歌词拿到了就不会变成空列表（[lineLevel] 本身为空时才会是空列表）。
 */
object LyricFallback {

    fun hasWordTiming(lines: List<LyricLine>): Boolean = LyricHighlight.hasWordTiming(lines)

    /** 逐字可用就用逐字（带翻译），否则退回整行。 */
    fun best(lineLevel: List<LyricLine>, wordLevel: List<LyricLine>): List<LyricLine> {
        if (!hasWordTiming(wordLevel)) return lineLevel
        return attachTranslations(wordLevel, lineLevel)
    }
}

// ---------------------------------------------------------------------------
// 翻译合并
// ---------------------------------------------------------------------------

/**
 * 把整行歌词里的**翻译**按时间贴到逐字歌词上。
 *
 * [translations] 传的是「已经合并过翻译的整行歌词」（即 `LyricParser.parse(lrc, tlyric)` 的结果），
 * 只有 `translation` 非空的行会被用来打补丁 —— 否则会把原文当成翻译贴上去。
 *
 * 逐字歌词（YRC/QRC/KRC）与整行歌词（LRC）来自同一首歌的同一份时间轴，起始时间通常逐毫秒相同；
 * 但两边的取整/来源偶尔会差几十毫秒，所以先精确匹配，再在 [toleranceSeconds] 内找最近的一条。
 * 找不到就保持 `translation = null`（宁可少一行翻译，也不贴错行）。
 */
internal fun attachTranslations(
    lines: List<LyricLine>,
    translations: List<LyricLine>,
    toleranceSeconds: Double = 0.2,
): List<LyricLine> {
    if (lines.isEmpty()) return lines
    val byTime = HashMap<Double, String>()
    for (t in translations) {
        val value = t.translation
        if (!value.isNullOrEmpty()) byTime[t.time] = value
    }
    if (byTime.isEmpty()) return lines
    val sortedTimes = byTime.keys.sorted()
    return lines.map { line ->
        val exact = byTime[line.time]
        if (exact != null) {
            line.copy(translation = exact)
        } else {
            val nearest = nearestTime(sortedTimes, line.time, toleranceSeconds)
            if (nearest == null) line else line.copy(translation = byTime[nearest])
        }
    }
}

private fun nearestTime(sorted: List<Double>, target: Double, tolerance: Double): Double? {
    if (sorted.isEmpty()) return null
    var low = 0
    var high = sorted.size - 1
    var index = 0
    while (low <= high) {
        val mid = (low + high) / 2
        if (sorted[mid] <= target) {
            index = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    val best = listOfNotNull(
        sorted.getOrNull(index),
        sorted.getOrNull(index + 1),
    ).minByOrNull { kotlin.math.abs(it - target) } ?: return null
    return if (kotlin.math.abs(best - target) <= tolerance) best else null
}

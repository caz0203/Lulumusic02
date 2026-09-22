package com.lulu.music.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** 音质等级 (port of `BeansAudioQuality`). */
enum class BeansAudioQuality(val level: String, val zh: String, val en: String) {
    STANDARD("standard", "标准", "Standard"),
    HIGHER("higher", "较高", "Higher"),
    EXHIGH("exhigh", "极高", "Very High"),
    LOSSLESS("lossless", "无损", "Lossless"),
    HIRES("hires", "Hi-Res", "Hi-Res");

    val displayName: String get() = beansLocalized(zh, en)

    companion object {
        fun fromRaw(raw: String?): BeansAudioQuality =
            entries.firstOrNull { it.level == raw } ?: HIRES
    }
}

/** 第三方音源音质 (port of `ThirdPartyAudioQuality`). */
enum class ThirdPartyAudioQuality(val raw: String) {
    KB128("128k"),
    KB320("320k"),
    FLAC("flac"),
    FLAC24BIT("flac24bit"),
    HIRES("hires"),
    ATMOS("atmos"),
    ATMOS_PLUS("atmos_plus"),
    MASTER("master");

    val displayName: String
        get() = when (this) {
            KB128 -> "128k"
            KB320 -> "320k"
            FLAC -> beansLocalized("无损 FLAC", "FLAC")
            FLAC24BIT -> beansLocalized("FLAC 24 位", "FLAC 24-bit")
            HIRES -> "Hi-Res"
            ATMOS -> "Atmos"
            ATMOS_PLUS -> "Atmos+"
            MASTER -> "Master"
        }

    /** 从高到低的降级链 (port of `fallbackChain`). */
    val fallbackChain: List<ThirdPartyAudioQuality>
        get() = when (this) {
            KB128 -> listOf(KB128)
            KB320 -> listOf(KB320, KB128)
            FLAC -> listOf(FLAC, KB320, KB128)
            FLAC24BIT -> listOf(FLAC24BIT, FLAC, KB320, KB128)
            HIRES -> listOf(HIRES, FLAC24BIT, FLAC, KB320, KB128)
            ATMOS -> listOf(ATMOS, HIRES, FLAC24BIT, FLAC, KB320, KB128)
            ATMOS_PLUS -> listOf(ATMOS_PLUS, ATMOS, HIRES, FLAC24BIT, FLAC, KB320, KB128)
            MASTER -> listOf(MASTER, ATMOS_PLUS, ATMOS, HIRES, FLAC24BIT, FLAC, KB320, KB128)
        }

    companion object {
        /** 兼容第三方脚本常见的音质别名 (port of `init?(sourceValue:)`). */
        fun fromSourceValue(value: String?): ThirdPartyAudioQuality? {
            val n = value?.trim()?.lowercase() ?: return null
            return when (n) {
                "128", "128k", "low", "standard" -> KB128
                "320", "320k", "high", "exhigh" -> KB320
                "flac", "lossless" -> FLAC
                "24bit", "flac24", "flac24bit", "hires24" -> FLAC24BIT
                "hires", "highres", "high-resolution" -> HIRES
                "atmos", "dolby" -> ATMOS
                "atmosplus", "atmos_plus", "dolbyplus" -> ATMOS_PLUS
                "master", "masterquality" -> MASTER
                else -> null
            }
        }

        fun supported(providerCode: String): List<ThirdPartyAudioQuality> =
            when (providerCode.trim().lowercase()) {
                "kw", "mg" -> listOf(KB128, KB320, FLAC, FLAC24BIT, HIRES)
                "kg" -> listOf(KB128, KB320, FLAC, FLAC24BIT, HIRES, ATMOS, MASTER)
                "tx" -> entries.toList()
                "wy" -> listOf(KB128, KB320, FLAC, FLAC24BIT, HIRES, ATMOS, MASTER)
                "git" -> listOf(KB128, KB320, FLAC)
                else -> entries.toList()
            }

        fun supported(source: SongSource): List<ThirdPartyAudioQuality> = when (source) {
            SongSource.NET_EASE -> supported("wy")
            SongSource.QQ -> supported("tx")
            SongSource.KUGOU -> supported("kg")
        }
    }
}

/** 歌曲来源. */
@Serializable
enum class SongSource(val raw: String) {
    NET_EASE("netease"),
    QQ("qq"),
    KUGOU("kugou");

    companion object {
        /** 兼容旧版本地收藏：未知或已下线来源统一回退为网易云. */
        fun fromRaw(raw: String?): SongSource =
            entries.firstOrNull { it.raw == raw } ?: NET_EASE
    }
}

@Serializable
data class Song(
    // NetEase ids exceed Int range for some tracks; keep 64-bit to avoid wrapping negative.
    val id: Long,
    val name: String,
    val artists: String = "",
    val album: String = "",
    val coverURL: String? = null,
    /** 时长（秒） */
    val duration: Double = 0.0,
    val source: SongSource = SongSource.NET_EASE,
    /** QQ 音乐 songmid */
    val qqMid: String? = null,
    /** QQ 音乐 media_mid；取 vkey 时优先使用 */
    val qqMediaMid: String? = null,
    val kugouHash: String? = null,
    val kugouAlbumAudioId: String? = null,
    val kugouAlbumId: String? = null,
    val kugouQualityHashes: Map<String, String>? = null,
    /** 付费/VIP 标记（网易云 0 免费、1 VIP、4 付费单曲；QQ 非 0 付费） */
    val fee: Int = 0,
    /**
     * Device-local audio URI (`content://` or `file://`) for tracks imported from this device.
     * When set, the player uses this directly and never contacts a platform API.
     */
    val localUri: String? = null,
) {
    val formattedDuration: String
        get() {
            val total = duration.toInt().coerceAtLeast(0)
            return "%d:%02d".format(total / 60, total % 60)
        }

    /** 跨平台唯一标识 */
    val identityKey: String
        get() = when (source) {
            SongSource.QQ -> "qq-$id"
            SongSource.KUGOU -> "kugou-$id"
            SongSource.NET_EASE -> "netease-$id"
        }

    /** 是否为 VIP / 付费歌曲 */
    val isVIP: Boolean
        get() = when (source) {
            // 8 为翻唱/免费资源，不视为 VIP
            SongSource.NET_EASE -> fee == 1 || fee == 4
            SongSource.QQ, SongSource.KUGOU -> fee != 0
        }
}

/** 歌手搜索结果 */
data class Artist(
    val id: String,
    val name: String,
    val coverURL: String?,
    val source: SongSource,
)

/** 专辑搜索结果 */
data class Album(
    val id: String,
    val name: String,
    val artistName: String,
    val coverURL: String?,
    val source: SongSource,
    val trackCount: Int? = null,
)

@Serializable
data class Playlist(
    val id: Long,
    val name: String,
    val coverURL: String? = null,
    val trackCount: Int = 0,
    val creatorName: String = "",
    val specialType: Int = 0,
    val source: SongSource = SongSource.NET_EASE,
) {
    /** 网易云「我喜欢的音乐」特殊歌单 */
    val isNetEaseLikedPlaylist: Boolean
        get() = source == SongSource.NET_EASE &&
            (specialType == 5 || name == "我喜欢的音乐" || name.contains("liked songs", ignoreCase = true))
}

data class TopList(
    val id: Long,
    val name: String,
    val coverURL: String?,
    val updateFrequency: String,
)

/** QQ 峰尖榜总览项 */
data class QQTopInfo(
    val id: Int,
    val name: String,
    val subTitle: String,
    val topSongNames: List<String>,
    val coverURL: String?,
)

/** 酷狗官方排行榜总览项 */
data class KugouTopInfo(
    val id: Int,
    val name: String,
    val updateFrequency: String,
    val coverURL: String?,
)

/**
 * 逐字（卡拉 OK）歌词里的一个词。
 *
 * 时间单位统一为**秒**（与 [LyricLine.time] 一致），[duration] 为这个词自身持续的时间。
 * 三个平台返回的都是毫秒，由各自的解析器除以 1000 后落到这里。
 */
data class LyricWord(
    val time: Double,
    val duration: Double,
    val text: String,
)

data class LyricLine(
    val id: String = UUID.randomUUID().toString(),
    val time: Double,
    val text: String,
    /** 歌词翻译（网易云 tlyric，可空） */
    val translation: String? = null,
    /**
     * 逐字时间轴；**默认空列表**表示这一行只有整行时间，渲染端应退回整行高亮。
     *
     * 新增字段带默认值，因此既有的 `LyricLine(time = …, text = …)` 位置/具名构造、
     * `copy()` 调用与相等比较都不受影响（两侧都为 `emptyList()` 时相等语义与改动前一致）。
     */
    val words: List<LyricWord> = emptyList(),
)

/** LRC 歌词解析 (port of `LyricParser`). */
object LyricParser {

    private val TIME_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val OFFSET_REGEX = Regex("""\[offset:([+-]?\d+)\]""")
    private val STRIP_TIME_REGEX = Regex("""\[\d{2}:\d{2}(\.\d{1,3})?\]""")

    /** 解析歌词；可选传入翻译歌词，按时间戳合并到对应行. */
    fun parse(raw: String, translationRaw: String? = null): List<LyricLine> {
        val lines = parseCore(raw, declaredOffsetSeconds(raw)).toMutableList()
        if (!translationRaw.isNullOrEmpty()) {
            val trans = parseCore(translationRaw, declaredOffsetSeconds(translationRaw))
            val byTime = HashMap<Double, String>()
            for (t in trans) {
                if (t.text.isNotEmpty()) byTime[t.time] = t.text
            }
            for (i in lines.indices) {
                val tr = byTime[lines[i].time]
                if (!tr.isNullOrEmpty()) lines[i] = lines[i].copy(translation = tr)
            }
        }
        return lines
    }

    private fun parseCore(raw: String, offset: Double): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (line in raw.split('\n')) {
            for (time in parseTimes(line)) {
                val text = line.replace(STRIP_TIME_REGEX, "").trim()
                out.add(LyricLine(time = (time + offset).coerceAtLeast(0.0), text = text))
            }
        }
        return out.sortedBy { it.time }
    }

    private fun declaredOffsetSeconds(raw: String): Double {
        val m = OFFSET_REGEX.find(raw) ?: return 0.0
        return (m.groupValues[1].toDoubleOrNull() ?: 0.0) / 1000.0
    }

    private fun parseTimes(line: String): List<Double> {
        val times = ArrayList<Double>()
        for (m in TIME_REGEX.findAll(line)) {
            val minutes = m.groupValues[1].toDoubleOrNull() ?: 0.0
            val seconds = m.groupValues[2].toDoubleOrNull() ?: 0.0
            var fraction = 0.0
            val fracRaw = m.groupValues.getOrNull(3).orEmpty()
            if (fracRaw.isNotEmpty()) {
                fraction = (fracRaw.toDoubleOrNull() ?: 0.0) / Math.pow(10.0, fracRaw.length.coerceAtLeast(1).toDouble())
            }
            times.add(minutes * 60 + seconds + fraction)
        }
        return times
    }
}

/** 歌词时间偏移 (port of `LyricTiming`). */
object LyricTiming {
    fun effectiveProgress(progress: Double, userOffset: Double): Double =
        (progress + userOffset).coerceAtLeast(0.0)

    fun seekTime(line: LyricLine, userOffset: Double): Double =
        (line.time - userOffset).coerceAtLeast(0.0)
}

/**
 * 逐字高亮所需的两个纯函数：**当前在唱哪个词** 与 **整行扫过了多少**。
 *
 * 渲染端（以后的任务）只消费这里的结果，不自己写二分或插值 —— 这样「卡拉 OK 进度」
 * 是一条可测的纯逻辑，而不是散落在 Compose 里的算术。
 *
 * 约定：`words` 为空即「这一行没有真逐字数据」，一律退回整行高亮（[LyricLine.words] 的注释）。
 */
object LyricHighlight {

    /**
     * 返回 [progress]（秒）时刻正在演唱的词下标（二分取最后一个 `time <= progress` 的词）。
     * 没有逐字数据、或还没唱到第一个词时返回 `-1`。
     */
    fun activeWordIndex(line: LyricLine, progress: Double): Int {
        val words = line.words
        if (words.isEmpty()) return -1
        var low = 0
        var high = words.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (words[mid].time <= progress) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /** 当前活动的词；没有则返回 null。 */
    fun activeWord(line: LyricLine, progress: Double): LyricWord? {
        val index = activeWordIndex(line, progress)
        return if (index < 0) null else line.words[index]
    }

    /** 当前这个词内部已经唱过的比例 0..1；没有逐字数据或还没到第一个词时返回 0。 */
    fun wordProgress(line: LyricLine, progress: Double): Float {
        val index = activeWordIndex(line, progress)
        if (index < 0) return 0f
        val word = line.words[index]
        // 词自身 duration 为 0 时（三种格式里都存在）用下一个词的起点兜底，避免除零。
        val next = line.words.getOrNull(index + 1)?.time
        val end = when {
            word.duration > 0.0 -> word.time + word.duration
            next != null -> next
            else -> word.time
        }
        if (end <= word.time) return 1f
        return ((progress - word.time) / (end - word.time)).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * 整行已经唱过的比例 0..1，可直接当作「已高亮部分的百分比」。
     *
     * - 有逐字数据：按**字符覆盖率**加权（一个长词的进度不会让短词瞬间跳完），这是卡拉 OK
     *   渐变遮罩需要的量；纯按时间会与肉眼看到的字形铺开速度对不上。
     * - 没有逐字数据：按 `line.time` → [nextLineTime] 线性插值，即整行均匀铺开，
     *   与「退回整行高亮」的行为一致。
     *
     * [nextLineTime] 是下一行的起始时间（秒），可为 null（最后一行 / 数据缺失）。
     */
    fun sweepProgress(line: LyricLine, nextLineTime: Double?, progress: Double): Float {
        val words = line.words
        if (words.isEmpty()) {
            val end = nextLineTime
            if (end == null || end <= line.time) return if (progress >= line.time) 1f else 0f
            return ((progress - line.time) / (end - line.time)).coerceIn(0.0, 1.0).toFloat()
        }
        val index = activeWordIndex(line, progress)
        if (index < 0) return 0f
        val total = words.sumOf { it.text.length }
        if (total <= 0) {
            // 词文本全为空（异常/占位数据）：退化成按整段时间插值，保证返回值仍单调且落在 0..1。
            val first = words.first().time
            val last = words.last()
            val end = if (last.duration > 0.0) last.time + last.duration else (nextLineTime ?: last.time)
            if (end <= first) return 1f
            return ((progress - first) / (end - first)).coerceIn(0.0, 1.0).toFloat()
        }
        var covered = 0.0
        for (i in 0 until index) covered += words[i].text.length
        covered += words[index].text.length * wordProgress(line, progress).toDouble()
        return (covered / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /** 这一行是否**真的**带逐字数据（至少两个词、且至少一个词的时长大于 0）。 */
    fun hasWordTiming(line: LyricLine): Boolean =
        line.words.size >= 2 && line.words.any { it.duration > 0.0 }

    /** 整篇歌词里是否至少有一行带真逐字数据 —— 平台是否提供了逐字歌词的判据。 */
    fun hasWordTiming(lines: List<LyricLine>): Boolean = lines.any { hasWordTiming(it) }

    /**
     * 逐字歌词里「当前行」的整体扫过比例，自动把下一行的时间算进去。
     * [index] 为 -1（还没到第一行）时返回 0。
     */
    fun sweepProgress(lines: List<LyricLine>, index: Int, progress: Double): Float {
        if (index < 0 || index >= lines.size) return 0f
        return sweepProgress(lines[index], lines.getOrNull(index + 1)?.time, progress)
    }
}

@Serializable
data class NetEaseUser(
    val uid: Long,
    val nickname: String,
    val avatarURL: String? = null,
    /** 0 无会员；非 0 有 VIP；>= 11 为黑胶 SVIP */
    val vipType: Int = 0,
) {
    /** VIP 标识：null 表示无会员 */
    val vipBadge: String?
        get() = when {
            vipType >= 11 -> "SVIP"
            vipType > 0 -> "VIP"
            else -> null
        }
}

/** 听歌排行条目 */
data class PlayRecordItem(val song: Song, val playCount: Int)

/** 听歌排行结果（列表 + 真实总数） */
data class PlayRecordResult(val items: List<PlayRecordItem>, val totalCount: Int)

/** 歌曲评论 */
data class SongComment(
    val id: Long,
    val content: String,
    val nickname: String,
    val avatarURL: String?,
    /** epoch millis */
    val time: Long,
    val likedCount: Int,
    val isHot: Boolean = false,
)

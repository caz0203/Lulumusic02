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

data class LyricLine(
    val id: String = UUID.randomUUID().toString(),
    val time: Double,
    val text: String,
    /** 歌词翻译（网易云 tlyric，可空） */
    val translation: String? = null,
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

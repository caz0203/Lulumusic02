package com.lulu.music.playback

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import com.lulu.music.data.api.NetEaseApi
import com.lulu.music.data.api.QQMusicApi
import com.lulu.music.data.auth.KugouMusicAuth
import com.lulu.music.data.auth.QQMusicAuth
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.source.PlaybackSource
import com.lulu.music.data.source.UnblockService
import com.lulu.music.data.store.CrashLog
import com.lulu.music.data.store.Prefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

/**
 * Songs are queued as `beans://song?...` placeholder URIs carrying everything needed to obtain a
 * real playback URL **and** everything needed to rebuild the [Song] itself. The real URL is resolved
 * lazily by [BeansDataSourceFactory] at the moment ExoPlayer is about to load the item.
 *
 * This keeps the whole queue visible to the media session (lock-screen next/previous work across the
 * entire queue) without pre-resolving every URL up front, which would be slow and would expire.
 *
 * ## 为什么 URI 必须自带整首歌（历史 bug）
 *
 * 占位 URI 曾经只带 `src`/`id`/`k`：懒解析时关键词类音源与 LX 脚本音源还需要歌名 / 歌手，
 * 于是解析器去查一张**纯内存**的登记表（[QueuedSongs]）。进程重启、系统恢复队列、
 * 或任何没经过那张表的播放路径都会查不到，解析器随即退化成「仅官方」——第三方音源等于死掉。
 *
 * 现在 URI 里带一整个 `Song`（`j` 参数），解析不再依赖任何内存状态：
 * [MediaResolver] 拿到的 URI 本身就是完整信息源，[QueuedSongs] 只剩兜底作用。
 *
 * 这个 URI 只是交给 [ResolvingDataSource] 改写的本地占位符，**永远不会发到网络上**，
 * 所以可以放心地长（`Uri.parse` / `getQueryParameter` 处理几 KB 毫无压力）。
 */
object SongUri {

    private const val SCHEME = "beans"
    private const val HOST = "song"

    /** 整首歌 JSON 的查询参数名。 */
    private const val PARAM_JSON = "j"

    /**
     * 整首歌（[Song] 的 kotlinx.serialization JSON，URL 编码）写进 `j` 参数的主键——
     * 以后给 [Song] 加字段不需要再改这里，也不会漏字段。
     * 逐字段的短名参数作为兜底 + 方便直接读日志。
     *
     * 序列化本身包在 `runCatching` 里：万一某个字段序列化失败，仍会写出短名参数，
     * [decodeSong] 照样能重建出一首可播放的歌。
     */
    fun encode(song: Song): Uri {
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
        val full = runCatching { Prefs.json.encodeToString(Song.serializer(), song) }.getOrNull()
        if (!full.isNullOrEmpty()) builder.appendQueryParameter(PARAM_JSON, full)
        builder.appendQueryParameter("src", song.source.raw)
            .appendQueryParameter("id", song.id.toString())
            .appendQueryParameter("name", song.name)
            .appendQueryParameter("artists", song.artists)
        song.album.takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("album", it) }
        song.coverURL?.takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("cover", it) }
        song.duration.takeIf { it > 0.0 }?.let { builder.appendQueryParameter("dur", it.toString()) }
        song.fee.takeIf { it != 0 }?.let { builder.appendQueryParameter("fee", it.toString()) }
        song.qqMid?.let { builder.appendQueryParameter("mid", it) }
        song.qqMediaMid?.let { builder.appendQueryParameter("mmedia", it) }
        song.kugouHash?.let { builder.appendQueryParameter("hash", it) }
        song.kugouAlbumAudioId?.let { builder.appendQueryParameter("aaid", it) }
        song.kugouAlbumId?.let { builder.appendQueryParameter("aid", it) }
        // Cross-platform identity, so offline playback can find a downloaded file without
        // re-deriving it from the per-platform fields.
        builder.appendQueryParameter("k", song.identityKey)
        return builder.build()
    }

    fun isSongUri(uri: Uri): Boolean = uri.scheme == SCHEME && uri.authority == HOST

    /** 单次解码的短名参数（`src` / `id` / `mid` / …）。保留给 [decode] 的既有调用方。 */
    private class Fields(
        val source: SongSource,
        val id: Long,
        val name: String,
        val artists: String,
        val album: String,
        val cover: String?,
        val duration: Double,
        val fee: Int,
        val qqMid: String?,
        val qqMediaMid: String?,
        val kugouHash: String?,
        val kugouAlbumAudioId: String?,
        val kugouAlbumId: String?,
    )

    /**
     * 从占位 URI 重建完整 [Song]：优先解析 `j` 参数里的整首 JSON，
     * 失败（旧版本写出的 URI / JSON 截断 / 字段不认）再退回逐字段短名参数。
     * 两者都拿不到 `id` 才返回 null——调用方据此放弃这一项，绝不抛异常。
     */
    fun decodeSong(uri: Uri): Song? {
        if (!isSongUri(uri)) return null
        uri.getQueryParameter(PARAM_JSON)?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Prefs.json.decodeFromString(Song.serializer(), raw) }
                .getOrNull()
                ?.let { return it }
        }
        val f = decodeFields(uri) ?: return null
        return Song(
            id = f.id,
            name = f.name,
            artists = f.artists,
            album = f.album,
            coverURL = f.cover,
            duration = f.duration,
            source = f.source,
            qqMid = f.qqMid,
            qqMediaMid = f.qqMediaMid,
            kugouHash = f.kugouHash,
            kugouAlbumAudioId = f.kugouAlbumAudioId,
            kugouAlbumId = f.kugouAlbumId,
            fee = f.fee,
        )
    }

    /** Returns the source + identifiers encoded in a placeholder URI. */
    fun decode(uri: Uri): Decoded? {
        if (!isSongUri(uri)) return null
        // 完整 JSON 优先：旧调用方只关心身份字段，直接从重建出来的 Song 上取。
        decodeSong(uri)?.let { return it.toDecoded() }
        val f = decodeFields(uri) ?: return null
        return Decoded(
            source = f.source,
            id = f.id,
            qqMid = f.qqMid,
            qqMediaMid = f.qqMediaMid,
            kugouHash = f.kugouHash,
            kugouAlbumAudioId = f.kugouAlbumAudioId,
            kugouAlbumId = f.kugouAlbumId,
        )
    }

    private fun decodeFields(uri: Uri): Fields? {
        if (!isSongUri(uri)) return null
        val source = SongSource.fromRaw(uri.getQueryParameter("src"))
        val id = uri.getQueryParameter("id")?.toLongOrNull() ?: return null
        return Fields(
            source = source,
            id = id,
            name = uri.getQueryParameter("name").orEmpty(),
            artists = uri.getQueryParameter("artists").orEmpty(),
            album = uri.getQueryParameter("album").orEmpty(),
            cover = uri.getQueryParameter("cover"),
            duration = uri.getQueryParameter("dur")?.toDoubleOrNull() ?: 0.0,
            fee = uri.getQueryParameter("fee")?.toIntOrNull() ?: 0,
            qqMid = uri.getQueryParameter("mid"),
            qqMediaMid = uri.getQueryParameter("mmedia"),
            kugouHash = uri.getQueryParameter("hash"),
            kugouAlbumAudioId = uri.getQueryParameter("aaid"),
            kugouAlbumId = uri.getQueryParameter("aid"),
        )
    }

    private fun Song.toDecoded(): Decoded = Decoded(
        source = source,
        id = id,
        qqMid = qqMid,
        qqMediaMid = qqMediaMid,
        kugouHash = kugouHash,
        kugouAlbumAudioId = kugouAlbumAudioId,
        kugouAlbumId = kugouAlbumId,
    )

    data class Decoded(
        val source: SongSource,
        val id: Long,
        val qqMid: String?,
        val qqMediaMid: String?,
        val kugouHash: String?,
        val kugouAlbumAudioId: String?,
        val kugouAlbumId: String?,
    )
}

/** Turns a placeholder song URI into a real, playable CDN URL. */
object MediaResolver {

    private const val TAG = "LuluResolve"

    /** 试听片段的时长上限：网易云只给片段时通常 30~60 秒。 */
    private const val TRIAL_FRAGMENT_MAX_MS = 61_000

    // ---- 决策日志里的「歌曲信息从哪来」--------------------------------------
    // 就是这几行让下一次设备日志立刻能看出修复有没有生效：正常必须是 URI。

    /** 占位 URI 自带整首歌（正常路径）。 */
    private const val SONG_SOURCE_URI = "URI"

    /** 只有旧版 URI 才会命中：退回到入队时登记的内存表。 */
    private const val SONG_SOURCE_REGISTRY = "内存登记表"

    /** 只有 [resolveUrl] 这种直接传入歌曲模型的调用方。 */
    private const val SONG_SOURCE_MODEL = "歌曲模型"

    /** 连歌曲信息都拿不到（占位 URI 缺 `id`）。 */
    private const val SONG_SOURCE_NONE = "无"

    /**
     * Blocking on purpose: this runs on ExoPlayer's loading thread, never on the main thread.
     * Returns null when no URL could be obtained (the item then fails cleanly instead of hanging).
     */
    fun resolve(uri: Uri): Uri? {
        if (!SongUri.isSongUri(uri)) return null
        val quality = BeansAudioQuality.fromRaw(SettingsStore.audioQuality.value)
        val url = runCatching { runBlocking { resolveStreaming(uri, quality) } }.getOrNull()
        return url?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    /**
     * 懒解析：按「播放来源」设置在官方接口与已启用的第三方音源之间选择。
     *
     * 改动前这里是 `official() ?: thirdParty()`，于是 VIP 歌曲永远用不上第三方音源：
     * 官方接口对 VIP 歌曲会返回一段**能正常播放的试听片段**（不是 null），`official()` 于是
     * 「成功」了，第三方音源根本不会被问；而试听片段也不会触发播放错误，所以
     * `PlaybackController` 的失败兜底同样救不回来。
     *
     * 现在把「官方只给了试听片段」当作官方失败处理（见 [preferThirdParty]）。
     *
     * **信息源**：一切都从 [uri] 来（[SongUri.decodeSong]），因为 URI 里带着整首歌的 JSON。
     * 只有历史版本写出的、缺数据的 URI 才会去查内存登记表 [QueuedSongs] 兜底——
     * **登记表查不到不再意味着「仅官方」**：URI 里有歌名 / 歌手，关键词类与脚本类音源照样可用。
     * 这条「查不到就退化成仅官方」的老路正是第三方音源失效的根因。
     *
     * 这里阻塞的是 ExoPlayer 的加载线程（不是主线程），语义与 iOS
     * 「先把音源解析出来再交给播放器」一致。
     */
    private suspend fun resolveStreaming(uri: Uri, quality: BeansAudioQuality): String? {
        val source = PlaybackSource.fromKey(SettingsStore.playbackSource.value)
        val fromUri = runCatching { SongUri.decodeSong(uri) }.getOrNull()
        // 兜底：只有极旧的占位 URI 才会走这里（进程重启后的系统恢复队列曾依赖它）。
        val song = fromUri ?: QueuedSongs.find(uri.getQueryParameter("k"))
        val songSource = if (fromUri != null) SONG_SOURCE_URI else SONG_SOURCE_REGISTRY
        if (song == null) {
            // 连 id 都没解出来：这一项没法解析，交给上层当作失败处理。
            val official = officialForUri(uri, quality, expectedDurationSeconds = 0.0)
            val decision = Decision(
                url = official.url,
                headers = null,
                officialNote = official.note,
                officialTrial = official.trial,
                usedThirdParty = false,
                branch = "占位 URI 无歌曲信息→仅官方",
            )
            return applyDecision(null, source, decision, songSource = SONG_SOURCE_NONE)
        }
        val official = officialForSong(song, quality)
        val decision = resolveBest(source, song, official) { thirdPartyStream(song, quality) }
        return applyDecision(song, source, decision, songSource)
    }

    /**
     * Resolve the real stream URL for a song. Shared by offline playback (the data source) and by
     * the download manager, so both use exactly the same quality and fallback behaviour.
     *
     * 顺序与流式播放完全一致（下载同样不该存下试听片段）：
     *  - `official`    只走官方接口（试听片段也照播，用户明确要求）；
     *  - `third_party` 先试已启用的第三方音源，全失败后回落官方；
     *  - `auto`        官方整曲优先；[preferThirdParty] 成立时（官方只给试听片段 / 没给地址）
     *                  改为第三方优先，官方留作最后兜底。
     */
    suspend fun resolveUrl(song: Song, quality: BeansAudioQuality): String? = runCatching {
        val source = PlaybackSource.fromKey(SettingsStore.playbackSource.value)
        val official = officialForSong(song, quality)
        val decision = resolveBest(source, song, official) { thirdPartyStream(song, quality) }
        applyDecision(song, source, decision, songSource = SONG_SOURCE_MODEL)
    }.getOrNull()

    // ---- 「播放来源」决策 -----------------------------------------------------

    /**
     * AUTO 模式下是否该把第三方音源摆在官方前面。
     *
     * 只有官方**确实没能给出整曲**时才改走第三方（用户明确要求）：
     *  - [OfficialResolution.trial]：这一次只返回了试听片段（VIP 歌曲，`freeTrialInfo`
     *    或时长兜底判定）；
     *  - `official.url == null`：官方根本没给地址。
     *
     * 这里刻意**不再**无条件看 `Song.isVIP`：`fee=1` 只说明歌曲需要会员，
     * 有会员的用户官方照样能返回整曲——那时再走第三方只会更慢、音质可能更差。
     * 官方地址仍然保留为最后兜底，所以第三方全部失败时照样能出声。
     */
    private fun preferThirdParty(official: OfficialResolution): Boolean =
        official.trial || official.url == null

    /** 决策原因，写进日志说明「为什么用了 / 没用第三方」。 */
    private fun preferenceReason(official: OfficialResolution): String = when {
        official.trial -> "官方仅试听片段"
        official.url == null -> "官方无地址"
        else -> "播放来源设置"
    }

    /**
     * 决策核心：流式播放与下载共用同一套顺序。
     *
     * 之所以是 suspend：第三方解析本身是 suspend 的；[resolve] 在自己的线程上用 `runBlocking` 包住。
     */
    private suspend fun resolveBest(
        source: PlaybackSource,
        song: Song?,
        official: OfficialResolution,
        thirdParty: suspend () -> ThirdPartyResolution?,
    ): Decision {
        // 仅官方：用户明确要求，试听片段也照播（这段语义与改动前一致）。
        if (source == PlaybackSource.OFFICIAL) {
            return Decision(
                url = official.url,
                headers = null,
                officialNote = official.note,
                officialTrial = official.trial,
                usedThirdParty = false,
                branch = "仅官方",
            )
        }
        val thirdPartyFirst = source == PlaybackSource.THIRD_PARTY || preferThirdParty(official)
        if (thirdPartyFirst) {
            val reason =
                if (source == PlaybackSource.THIRD_PARTY) "播放来源=第三方" else preferenceReason(official)
            thirdParty()?.let { third ->
                return Decision(
                    url = third.url,
                    headers = third.headers,
                    officialNote = official.note,
                    officialTrial = official.trial,
                    usedThirdParty = true,
                    branch = "第三方优先（$reason）",
                )
            }
            return Decision(
                url = official.url,
                headers = null,
                officialNote = official.note,
                officialTrial = official.trial,
                usedThirdParty = false,
                branch = "第三方未命中→官方（$reason）",
            )
        }
        // AUTO 且官方这次给的就是整曲。
        return Decision(
            url = official.url,
            headers = null,
            officialNote = official.note,
            officialTrial = false,
            usedThirdParty = false,
            branch = "官方整曲",
        )
    }

    /** 第三方音源：直链 + 该音源要求的拉流请求头；任何失败都退化成 null，绝不外抛。 */
    private suspend fun thirdPartyStream(song: Song, quality: BeansAudioQuality): ThirdPartyResolution? =
        runCatching { UnblockService.resolveStream(song, quality) }.getOrNull()
            ?.takeIf { it.url.isNotBlank() }
            ?.let { ThirdPartyResolution(url = it.url, headers = it.headers) }

    // ---- 官方平台解析 -------------------------------------------------------

    /**
     * 官方解析结果。
     *
     * `trial = true` 表示官方只给了一段试听片段（VIP 歌曲）：它虽然能正常出声，
     * 但对「播放整首歌」而言等同于失败，必须改走第三方音源。
     */
    private data class OfficialResolution(
        val url: String?,
        val trial: Boolean = false,
        /** 官方这次返回了什么（音质 / fee / 是否试听），只用于决策日志。 */
        val note: String = "",
    )

    /** 第三方解析结果：直链 + 该音源要求带到拉流请求上的请求头。 */
    private data class ThirdPartyResolution(val url: String, val headers: Map<String, String>?)

    /** 一次解析的最终结果，附带可读的决策描述（只用于日志）。 */
    private data class Decision(
        val url: String?,
        val headers: Map<String, String>?,
        val officialNote: String,
        val officialTrial: Boolean,
        val usedThirdParty: Boolean,
        val branch: String,
    )

    /** 官方平台解析（只走官方接口）。任何异常都退化成空结果，绝不外抛。 */
    private suspend fun officialForDecoded(
        decoded: SongUri.Decoded,
        quality: BeansAudioQuality,
        expectedDurationSeconds: Double,
    ): OfficialResolution = runCatching {
        when (decoded.source) {
            SongSource.NET_EASE -> netEaseOfficial(decoded.id, quality, expectedDurationSeconds)

            SongSource.QQ -> {
                val mid = decoded.qqMid
                if (mid.isNullOrBlank()) OfficialResolution(null, note = "缺少 songmid")
                else qqOfficial(mid, decoded.qqMediaMid, quality)
            }

            SongSource.KUGOU -> OfficialResolution(
                url = KugouPlayback.resolve(
                    hash = decoded.kugouHash,
                    albumAudioId = decoded.kugouAlbumAudioId,
                    albumId = decoded.kugouAlbumId,
                    quality = quality,
                )?.takeIf { it.isNotBlank() },
                note = "酷狗官方接口",
            )
        }
    }.getOrNull() ?: OfficialResolution(null, note = "官方解析异常")

    /** 官方解析（按 [Song] 走）——供下载等只有歌曲模型的调用方使用。 */
    private suspend fun officialForSong(song: Song, quality: BeansAudioQuality): OfficialResolution =
        officialForDecoded(
            decoded = SongUri.Decoded(
                source = song.source,
                id = song.id,
                qqMid = song.qqMid,
                qqMediaMid = song.qqMediaMid,
                kugouHash = song.kugouHash,
                kugouAlbumAudioId = song.kugouAlbumAudioId,
                kugouAlbumId = song.kugouAlbumId,
            ),
            quality = quality,
            expectedDurationSeconds = song.duration,
        )

    /** 官方解析（按占位 URI 走）——只在连 [Song] 都重建不出来时用（例如缺少 `id`）。 */
    private suspend fun officialForUri(
        uri: Uri,
        quality: BeansAudioQuality,
        expectedDurationSeconds: Double,
    ): OfficialResolution {
        val decoded = runCatching { SongUri.decode(uri) }.getOrNull()
        if (decoded == null) return OfficialResolution(null, note = "占位 URI 无法解析")
        return officialForDecoded(decoded, quality, expectedDurationSeconds)
    }

    /**
     * 网易云官方解析（对齐 iOS `neteaseResolve`）：
     *  - 用 [NetEaseApi.songURLInfo] 而不是 `songURLs`，才能区分「整曲」与「试听片段」；
     *  - 目标音质拿不到整曲时再用标准音质试一次；两次都只是试听片段时保留原结果（仅作兜底）。
     */
    private suspend fun netEaseOfficial(
        id: Long,
        quality: BeansAudioQuality,
        expectedDurationSeconds: Double,
    ): OfficialResolution {
        val requested = runCatching { NetEaseApi.songURLInfo(listOf(id), quality.level) }
            .getOrNull()?.get(id)?.toOfficialResolution(expectedDurationSeconds)
            ?: OfficialResolution(null, note = "接口未返回")
        if (requested.url != null && !requested.trial) return requested
        if (quality == BeansAudioQuality.STANDARD) return requested
        val standard = runCatching { NetEaseApi.songURLInfo(listOf(id), BeansAudioQuality.STANDARD.level) }
            .getOrNull()?.get(id)?.toOfficialResolution(expectedDurationSeconds)
        return if (standard != null && standard.url != null && !standard.trial) standard else requested
    }

    /** QQ 官方解析：用 [QQMusicApi.songURLResult] 拿到实际命中的音质（内部已含档位回落）。 */
    private suspend fun qqOfficial(mid: String, mediaMid: String?, quality: BeansAudioQuality): OfficialResolution =
        QQMusicApi.songURLResult(songmid = mid, mediaMid = mediaMid, quality = quality)
            ?.let { OfficialResolution(url = it.url.takeIf { url -> url.isNotBlank() }, note = "br=${it.br}") }
            ?: OfficialResolution(null, note = "vkey 无可用地址")

    /**
     * 把网易云 `songURLInfo` 的结果翻译成官方解析结果。
     *
     * 「试听片段」以接口的 `freeTrialInfo`（[NetEaseApi.SongURLInfo.freeTrial]）为准，
     * 再补一条兜底：接口没标试听、但返回的音频时长明显短于整首歌时，同样按试听片段处理。
     */
    private fun NetEaseApi.SongURLInfo?.toOfficialResolution(expectedDurationSeconds: Double): OfficialResolution {
        val info = this ?: return OfficialResolution(null, note = "接口未返回")
        val url = info.url?.takeIf { it.isNotBlank() } ?: return OfficialResolution(null, note = "接口无地址")
        val trial = info.freeTrial || isShortFragment(info.durationMs, expectedDurationSeconds)
        val fee = info.fee?.toString() ?: "?"
        return OfficialResolution(url = url, trial = trial, note = "fee=$fee 试听=${if (trial) "是" else "否"}")
    }

    /**
     * 没有试听标记时的兜底判断：返回的音频时长明显短于整首歌 → 同样是试听片段。
     * 阈值刻意保守（不超过 61 秒，且不到整首歌的一半），避免把元数据时长不准的正常歌曲误判。
     */
    private fun isShortFragment(returnedMs: Int?, expectedSeconds: Double): Boolean {
        if (returnedMs == null || returnedMs <= 0) return false
        val expectedMs = expectedSeconds * 1000
        if (expectedMs <= 0) return false
        return returnedMs <= TRIAL_FRAGMENT_MAX_MS && returnedMs * 2 < expectedMs
    }

    // ---- 决策日志 -----------------------------------------------------------

    /** 登记拉流请求头 + 写决策日志，返回最终地址。 */
    private fun applyDecision(
        song: Song?,
        source: PlaybackSource,
        decision: Decision,
        songSource: String,
    ): String? {
        // 第三方直链可能还需要音源自己的请求头（UA / Referer / Cookie / X-*）才能拉流。
        decision.headers?.let { rememberStreamHeaders(decision.url, it) }
        logDecision(song, source, decision, songSource)
        return decision.url?.takeIf { it.isNotBlank() }
    }

    /**
     * 每次解析只写一行：歌曲信息从哪来（URI / 内存登记表 / 歌曲模型）、走了哪个分支、
     * 官方这次是不是只给了试听片段、第三方有没有被用上。
     * 目的是让 设置 → 崩溃日志 直接回答「为什么这首 VIP 歌没用上第三方音源」。
     * 普通歌曲（官方整曲、没碰第三方）只进 logcat，不占用 [CrashLog] 仅 8 条的容量。
     */
    private fun logDecision(song: Song?, source: PlaybackSource, decision: Decision, songSource: String) {
        val name = song?.let { "${it.name} - ${it.artists}" } ?: "未登记歌曲"
        val officialNote = if (decision.officialNote.isEmpty()) "" else " (${decision.officialNote})"
        val line = "音源决策[${source.key}] $name｜歌曲来源=$songSource｜" +
            "官方试听=${if (decision.officialTrial) "是" else "否"}$officialNote｜" +
            "第三方=${if (decision.usedThirdParty) "已使用" else "未使用"}｜${decision.branch}"
        runCatching { Log.d(TAG, line) }
        val notable = decision.officialTrial || decision.usedThirdParty || decision.url == null || song?.isVIP == true
        if (notable) runCatching { CrashLog.write(ResolutionNote(line)) }
    }

    /**
     * 单行解析决策记录：借 [CrashLog] 落盘，用户在 设置 → 崩溃日志 里能看到原因。
     * 清空 stackTrace，让它只占一行——这里记录的不是异常，只是「可读现场」。
     */
    private class ResolutionNote(message: String) : RuntimeException(message) {
        init {
            stackTrace = emptyArray<StackTraceElement>()
        }
    }

    // ---- 拉流请求头 --------------------------------------------------------

    /**
     * 第三方音源直链 → 该音源用于拉流的请求头。
     *
     * 直链是直接交给播放器的（不经过 `beans://`），播放器无从知道它出自哪个音源，
     * 所以解析成功时登记一次，[StreamHeaderDataSourceFactory] 拉流时再取出来。
     */
    private val streamHeadersByURL = ConcurrentHashMap<String, Map<String, String>>()

    /** 见 [streamHeadersByURL]；[url] 为空或 [headers] 为空时是空操作。 */
    internal fun rememberStreamHeaders(url: String?, headers: Map<String, String>?) {
        val key = normalizeURLKey(url) ?: return
        if (headers.isNullOrEmpty()) return
        // 只服务「刚解析出来马上要播」的场景，不需要长期保留。
        if (streamHeadersByURL.size >= MAX_REMEMBERED_STREAM_HEADERS) streamHeadersByURL.clear()
        streamHeadersByURL[key] = headers
    }

    /**
     * 与拉流时 `dataSpec.uri.toString()` 对齐：地址会先经过一次 `Uri.parse`，
     * 两边用同样的归一化形式做键，避免个别字符被改写后查不到。
     */
    private fun normalizeURLKey(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { Uri.parse(raw).toString() }.getOrDefault(raw)
    }

    /**
     * 拉流请求头（对齐 iOS `PlayerManager.setupPlayer`）：
     *  - QQ 音乐 CDN（含第三方音源解析出的 `*.ptqqmusic.gitv.tv` 等节点）缺 `Referer` 会 403，
     *    登录后还要带 `Cookie` 才能拿到 VIP 对应的流；
     *  - 酷狗 CDN 同理，且对 `User-Agent` 敏感；
     *  - 其他域名用音源自己声明的请求头（缺什么补什么）。
     *
     * `User-Agent` 也在这里逐请求给出：`DefaultHttpDataSource` 会在写完请求头之后再写一次
     * 工厂里的 UA，所以工厂那边刻意不设置 UA，否则平台专用的 UA 会被覆盖掉。
     *
     * 这个方法在 ExoPlayer 的加载线程上被调用，绝不抛异常。
     */
    fun streamRequestHeaders(uri: Uri): Map<String, String> = runCatching {
        val host = uri.host?.lowercase().orEmpty()
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = BeansPlayerService.USER_AGENT
        when {
            isQQStreamHost(host) -> {
                headers["User-Agent"] = QQ_STREAM_USER_AGENT
                headers["Referer"] = QQ_STREAM_REFERER
                if (QQMusicAuth.isLoggedIn) {
                    QQMusicAuth.cookieHeader.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                }
            }

            isKugouStreamHost(host) -> {
                headers["User-Agent"] = KUGOU_STREAM_USER_AGENT
                headers["Referer"] = KUGOU_STREAM_REFERER
                if (KugouMusicAuth.isLoggedIn) {
                    KugouMusicAuth.cookieHeader.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                }
            }
        }
        // 音源自己声明的头只补空缺，不覆盖平台必需的头。
        val registered = streamHeadersByURL[uri.toString()]
        if (!registered.isNullOrEmpty()) {
            for ((key, value) in registered) {
                if (headers.keys.none { it.equals(key, ignoreCase = true) }) headers[key] = value
            }
        }
        headers
    }.getOrDefault(emptyMap())

    private fun isQQStreamHost(host: String): Boolean =
        host.contains("qq.com") || host.contains("qqmusic") ||
            host.contains("ptqqmusic") || host.contains("gitv.tv")

    private fun isKugouStreamHost(host: String): Boolean =
        host.contains("kugou.com") || host.contains("kgimg.com")

    /** 与 [UnblockService] 里 QQ_CDN_HOSTS 保持一致的 UA（iOS 侧播放固定用桌面 Firefox）。 */
    private const val QQ_STREAM_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:80.0) Gecko/20100101 Firefox/80.0"

    private const val KUGOU_STREAM_USER_AGENT = "Android15-1070-11440-46-0-DiscoveryDRADProtocol-wifi"

    private const val QQ_STREAM_REFERER = "https://y.qq.com/"

    private const val KUGOU_STREAM_REFERER = "https://www.kugou.com/"

    private const val MAX_REMEMBERED_STREAM_HEADERS = 32
}

/**
 * 入队时登记的歌曲表：关键词类音源与 LX 脚本音源需要歌名 / 歌手。
 *
 * **这是兜底，不是必需。** 歌曲信息现在由占位 URI 自己携带（[SongUri.encode] 写入整首歌），
 * 所以进程重启 / 系统恢复队列 / 绕过本表入库的播放路径都不会再退化成「仅官方」。
 * 只有极旧版本写出的 URI 缺少数据时才会查到这里。
 *
 * 纯内存、跨线程访问（入队在主线程，解析在 ExoPlayer 加载线程），因此用 [ConcurrentHashMap]；
 * 超出上限整体清空，避免长期占用内存。
 */
internal object QueuedSongs {

    private const val MAX_ENTRIES = 128

    private val songs = ConcurrentHashMap<String, Song>()

    fun remember(song: Song) {
        if (songs.size >= MAX_ENTRIES) songs.clear()
        songs[song.identityKey] = song
    }

    fun find(identityKey: String?): Song? = identityKey?.let { songs[it] }
}

/**
 * Wraps the HTTP data source so ExoPlayer resolves `beans://song` URIs on demand.
 * A downloaded copy always wins over streaming, so offline playback is automatic.
 * Any other URI (mp3/flac/local file) passes straight through untouched.
 *
 * 这一层只负责「把占位 URI 换成真正的地址」；请求头交给下游的
 * [StreamHeaderDataSourceFactory]（它拿到的是最终地址，因此两条路径都覆盖）。
 */
class BeansDataSourceFactory(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val base = upstream.createDataSource()
        return ResolvingDataSource(base) { dataSpec: DataSpec ->
            if (SongUri.isSongUri(dataSpec.uri)) {
                val key = dataSpec.uri.getQueryParameter("k")
                val local = key?.let { DownloadManager.localFileFor(it) }
                if (local != null) {
                    dataSpec.withUri(Uri.fromFile(local))
                } else {
                    val resolved = MediaResolver.resolve(dataSpec.uri)
                    if (resolved != null) dataSpec.withUri(resolved) else dataSpec
                }
            } else {
                dataSpec
            }
        }
    }
}

/**
 * 给音频拉流请求补上平台 / 音源要求的请求头。
 *
 * QQ 音乐与酷狗的 CDN 在缺少 `Referer`（登录后还要 `Cookie`）时会直接返回 403，
 * 而 Media3 的 `ResolvingDataSource` 只能改写 URI、无法添加请求头，所以这里再包一层
 * [DataSource]：`open()` 时按最终 URL 选好请求头，写进底层 [HttpDataSource] 再打开。
 *
 * 它在 [BeansDataSourceFactory] 的下游，所以「`beans://song` 懒解析」与「第三方直链」
 * 两条路径都会经过它。
 */
class StreamHeaderDataSourceFactory(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        StreamHeaderDataSource(upstream.createDataSource())
}

private class StreamHeaderDataSource(
    private val delegate: DataSource,
) : DataSource {

    override fun open(dataSpec: DataSpec): Long {
        val http = delegate as? HttpDataSource
        if (http != null) {
            // DataSource 实例会被复用，先清掉上一首留下的头，避免请求头串味。
            runCatching {
                http.clearAllRequestProperties()
                for ((key, value) in MediaResolver.streamRequestHeaders(dataSpec.uri)) {
                    http.setRequestProperty(key, value)
                }
            }
        }
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        delegate.close()
    }
}

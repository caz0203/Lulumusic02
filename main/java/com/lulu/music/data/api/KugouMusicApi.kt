package com.lulu.music.data.api

import android.util.Log
import com.lulu.music.data.auth.KugouMusicAuth
import com.lulu.music.data.model.Album
import com.lulu.music.data.model.Artist
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.KugouTopInfo
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongComment
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.net.BeansApiException
import com.lulu.music.data.net.Http
import com.lulu.music.data.net.arr
import com.lulu.music.data.net.int
import com.lulu.music.data.net.objects
import com.lulu.music.data.net.optMap
import com.lulu.music.data.net.str
import com.lulu.music.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 酷狗二维码登录句柄（对应 iOS `KugouMusicAPI.QRLogin`）。 */
data class KugouQRLogin(val key: String, val url: String)

/** 酷狗二维码登录轮询状态（对应 iOS `KugouMusicAPI.QRState`）。 */
sealed class KugouQRState {
    data object Waiting : KugouQRState()
    data object Scanned : KugouQRState()
    data object Expired : KugouQRState()
    data class Success(val nickname: String) : KugouQRState()
    data class Failure(val message: String) : KugouQRState()
}

/** 酷狗评论分页结果。 */
data class KugouCommentPage(
    val comments: List<SongComment>,
    val total: Int,
)

/**
 * 酷狗接口（搜索 / 歌单 / 排行榜 / 私人漫游 / 播放地址 / 歌词 / 评论 / 设备注册）。
 *
 * 端点路径、参数名、签名拼接顺序与盐值、请求头、响应字段路径均与 iOS 实现逐字对应，
 * 任何偏差都会导致签名校验失败或播放地址缺失。
 */
object KugouMusicApi {

    private const val LOG_TAG = "KugouMusicApi"

    private const val gateway = "https://gateway.kugou.com"
    private const val loginBase = "https://login-user.kugou.com"
    private const val userService = "https://userservice.kugou.com"
    // Keep the app's existing login flow, but use the current public KuGouMusicApi
    // request profile for search, recommendations, charts, FM, and playback.
    private const val upstreamAppID = "1005"
    private const val upstreamClientVersion = "20489"
    private const val upstreamSignSalt = "OIlwieks28dk2k092lksi2UIkp"
    private const val upstreamSongClientVersion = "11430"
    private const val appid = "3116"
    private const val clientver = "11440"
    private const val qrAppid = "1001"
    private const val qrSrcAppid = "2919"
    private const val androidSignKey = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    private const val webSignKey = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val playSalt = "kgcloudv2"
    private const val playSignSalt = "57ae12eb6890223e355ccfcb74edf70d"
    private const val playAppid = "1005"
    private const val playClientver = "20489"
    private const val androidUA = "Android15-1070-11440-46-0-DiscoveryDRADProtocol-wifi"
    private const val playbackUA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"

    /** 网页通道 User-Agent（对应 `Self.browserUA`）。 */
    private const val browserUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

    /** 设备注册 RSA 公钥（PKCS#1 DER，1024 位）。 */
    private const val rsaPublicKeyBase64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val OCTET_TYPE = "application/octet-stream".toMediaType()

    /** 对应 iOS `lastMembershipProbeAt`（会员状态探测节流，5 分钟）。 */
    @Volatile
    private var lastMembershipProbeAt: Long? = null

    // MARK: - 二维码登录

    suspend fun qrKey(): KugouQRLogin {
        KugouMusicAuth.prepareDevice()
        val qrcodeText = "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=$appid&"
        val response = gatewayRequest(
            "/v2/qrcode",
            baseURL = loginBase,
            signType = SignType.Web,
            params = mapOf(
                "appid" to qrAppid,
                "type" to "1",
                "plat" to "4",
                "qrcode_txt" to qrcodeText,
                "srcappid" to qrSrcAppid,
            ),
            headers = mapOf(
                "User-Agent" to browserUA,
                "x-router" to "login-user.kugou.com",
            ),
        )
        val json = response.json
        val key = deepString(json, listOf("qrcode", "key"))
        if (key.isEmpty()) throw BeansApiException.Unknown("酷狗二维码生成失败")
        return KugouQRLogin(
            key = key,
            url = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=${urlEncode(key)}",
        )
    }

    suspend fun pollQR(key: String): KugouQRState {
        val response = gatewayRequest(
            "/v2/get_userinfo_qrcode",
            baseURL = loginBase,
            signType = SignType.Web,
            params = mapOf(
                "plat" to "4",
                "appid" to appid,
                "srcappid" to qrSrcAppid,
                "qrcode" to key,
            ),
            headers = mapOf(
                "User-Agent" to browserUA,
                "x-router" to "login-user.kugou.com",
            ),
        )
        val json = response.json
        val status = deepInt(json, listOf("status"))
        val token = deepString(json, listOf("token", "user_token", "access_token", "key"))
        val userId = deepString(json, listOf("userid", "user_id", "uid", "kugooid", "kugouid"))
            .filter { it in '0'..'9' }
        if (token.isEmpty() || userId.isEmpty()) {
            if (status == 2) return KugouQRState.Scanned
            if (status == 3) return KugouQRState.Expired
            return KugouQRState.Waiting
        }
        val nick = deepString(json, listOf("nickname", "nick", "username", "user_name", "uname"))
        val avatar = deepString(json, listOf("avatar", "pic", "img", "headpic", "user_pic", "userpic"))
        val vip = deepInt(json, listOf("vip_type", "vipType", "viptype", "isvip", "is_vip", "vip"))
        KugouMusicAuth.saveLogin(userId = userId, token = token, nickname = nick, avatar = avatar, vipType = vip)
        registerDevice()
        refreshMembershipStatusIfNeeded(force = true)
        return KugouQRState.Success(if (nick.isEmpty()) "酷狗音乐用户 $userId" else nick)
    }

    // MARK: - 用户歌单

    suspend fun userPlaylists(): List<Playlist> {
        val auth = KugouMusicAuth
        if (!auth.isLoggedIn) return emptyList()
        refreshMembershipStatusIfNeeded()
        val dataBody: Map<String, Any> = mapOf(
            "total_ver" to 979,
            "type" to 2,
            "page" to 1,
            "pagesize" to 200,
            "userid" to (auth.userId.toIntOrNull() ?: 0),
            "token" to auth.token,
        )
        val response = gatewayRequest(
            "/v7/get_all_list",
            method = "POST",
            params = mapOf(
                "total_ver" to "979",
                "type" to "2",
                "page" to "1",
                "pagesize" to "200",
                "userid" to auth.userId,
                "token" to auth.token,
            ),
            data = dataBody,
            headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
        )
        val json = response.json
        val code = deepInt(json, listOf("error_code", "errcode", "code"))
        val status = deepInt(json, listOf("status"))
        val raw = deepArrays(
            json,
            listOf("lists", "list", "info", "data", "listinfo", "collection_list", "playlist"),
        )
        Log.d(LOG_TAG, "酷狗歌单同步：status=$status code=$code 返回 ${raw.size} 个")
        val seen = mutableSetOf<Long>()
        return raw.mapNotNull { item ->
            val playlist = mapPlaylist(item) ?: return@mapNotNull null
            if (!seen.add(playlist.id)) return@mapNotNull null
            playlist
        }
    }

    // MARK: - 推荐

    /**
     * 酷狗私人漫游：使用 KuGouMusicApi 的 personal_fm 请求协议，连续取几批推荐，
     * 让首页不会被固定在首批三首歌曲。
     */
    suspend fun personalFM(limit: Int = 12): List<Song> {
        val auth = KugouMusicAuth
        if (!auth.isLoggedIn) return emptyList()
        auth.prepareDevice()

        val target = max(limit, 1)
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        var lastHash: String? = null
        var lastSongID: String? = null
        val batchCount = max(1, min(8, ceil(target / 3.0).toInt() + 1))

        repeat(batchCount) {
            val clientTime = System.currentTimeMillis()
            val data = mutableMapOf<String, Any>(
                "appid" to upstreamAppID,
                "clienttime" to clientTime,
                "mid" to auth.mid,
                "action" to "play",
                "recommend_source_locked" to 0,
                "song_pool_id" to 0,
                "callerid" to 0,
                "m_type" to 1,
                "platform" to "ios",
                "area_code" to 1,
                "remain_songcnt" to 0,
                "clientver" to upstreamClientVersion,
                "is_overplay" to if (lastHash == null) 0 else 1,
                "mode" to "normal",
                "fakem" to "ca981cfc583a4c37f28d2d49000013c16a0a",
                "key" to upstreamParamsKey("$clientTime"),
            )
            if (auth.userId.isNotEmpty()) {
                data["userid"] = auth.userId.toIntOrNull() ?: 0
                data["kguid"] = auth.userId.toIntOrNull() ?: 0
            }
            if (auth.token.isNotEmpty()) data["token"] = auth.token
            if (auth.vipType > 0) data["vip_type"] = auth.vipType
            lastHash?.let { data["hash"] = it }
            lastSongID?.let { data["songid"] = it }
            val hash = lastHash
            val songID = lastSongID
            if (hash != null && songID != null) {
                data["playtime"] = max(0, (songs.lastOrNull()?.duration ?: 0.0).toInt())
                data["hash"] = hash
                data["songid"] = songID
            }

            val response = upstreamRequest(
                "/v2/personal_recommend",
                method = "POST",
                data = data,
                headers = mapOf("x-router" to "persnfm.service.kugou.com"),
            )
            val rows = deepArrays(
                response.json,
                listOf(
                    "songs", "song", "songlist", "list", "data", "recommend", "recommend_list",
                    "recommend_song", "personal_fm", "result",
                ),
            )
            val batch = rows.mapNotNull { mapCompleteTrack(it) }
            if (batch.isEmpty()) {
                val code = deepInt(response.json, listOf("code", "status", "error_code", "errcode"))
                val message = deepString(response.json, listOf("msg", "message", "error_msg", "error_message"))
                Log.d(
                    LOG_TAG,
                    "酷狗私人漫游接口无歌曲：code=$code message=${if (message.isEmpty()) "无" else message}",
                )
                return@repeat
            }
            for (song in batch) {
                if (!seen.add(song.identityKey)) continue
                songs.add(song)
                if (songs.size >= target) return songs.take(target)
            }
            lastHash = batch.last().kugouHash
            lastSongID = batch.last().kugouAlbumAudioId
            if (lastHash == null && lastSongID == null) return@repeat
        }

        // Keep the feature usable when the personal-FM service returns an empty
        // payload for a valid login by falling back to Kugou daily suggestions.
        if (songs.isEmpty()) {
            val fallback = runCatching { everydayRecommend(limit = target) }.getOrNull()
            if (!fallback.isNullOrEmpty()) {
                Log.d(LOG_TAG, "酷狗私人漫游无个性化结果，使用每日推荐兜底：返回 ${fallback.size} 首")
                return fallback.take(target)
            }
        }
        Log.d(LOG_TAG, "酷狗私人漫游：返回 ${songs.size} 首")
        return songs
    }

    /** 酷狗每日推荐，替代首页原先用“热门歌曲”搜索模拟推荐的方式。 */
    suspend fun everydayRecommend(limit: Int = 30): List<Song> {
        KugouMusicAuth.prepareDevice()
        val response = upstreamRequest(
            "/everyday_song_recommend",
            method = "POST",
            params = mapOf("platform" to "ios"),
            headers = mapOf("x-router" to "everydayrec.service.kugou.com"),
        )
        val rows = deepArrays(
            response.json,
            listOf("songs", "songlist", "list", "data", "recommend", "recommend_list"),
        )
        return rows.mapNotNull { mapCompleteTrack(it) }.take(max(limit, 1))
    }

    // MARK: - 搜索

    /**
     * 酷狗自有移动端搜索接口：搜索结果携带 hash、专辑和封面，可直接复用酷狗播放地址解析。
     * 优先使用 KuGouMusicApi 的 v3/search/song，现有综合搜索和网页接口作为兜底。
     */
    suspend fun searchSongs(keyword: String, limit: Int = 30): List<Song> {
        val upstream = runCatching { upstreamSearchSongs(keyword = keyword, limit = limit) }.getOrNull()
        if (!upstream.isNullOrEmpty()) {
            Log.d(LOG_TAG, "酷狗 v3 搜索完成：$keyword 结果=${upstream.size}")
            return upstream
        }
        val complete = runCatching { searchSongsComplete(keyword = keyword, limit = limit) }.getOrNull()
        if (!complete.isNullOrEmpty()) {
            Log.d(LOG_TAG, "酷狗综合搜索完成：$keyword 结果=${complete.size}")
            return complete
        }

        val url = buildUrl(
            "https://songsearch.kugou.com/song_search_v2",
            listOf(
                "keyword" to keyword,
                "page" to "1",
                "pagesize" to "${min(max(limit, 1), 100)}",
            ),
        ) ?: throw BeansApiException.Unknown("酷狗搜索地址无效")
        val json = getJson(url, browserUA)
        val raw = deepArrays(json, listOf("info", "songs", "song", "list", "data"))
        val songs = raw.mapNotNull { mapTrack(it) }
        Log.d(LOG_TAG, "酷狗搜索完成：$keyword 结果=${songs.size}")
        return songs
    }

    private suspend fun upstreamSearchSongs(keyword: String, limit: Int): List<Song> {
        val pageSize = min(max(limit, 1), 30)
        // The upstream endpoint commonly returns 15 rows even when pagesize is 30.
        // Keep paging in that case so artist pages do not stop at the first 15 songs.
        val pageCount = max(1, min(40, ceil(max(limit, 1) / 15.0).toInt() + 2))
        val result = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        for (page in 1..pageCount) {
            val response = upstreamRequest(
                "/v3/search/song",
                params = mapOf(
                    "albumhide" to "0",
                    "iscorrection" to "1",
                    "keyword" to keyword,
                    "nocollect" to "0",
                    "page" to "$page",
                    "pagesize" to "$pageSize",
                    "platform" to "AndroidFilter",
                ),
                headers = mapOf("x-router" to "complexsearch.kugou.com"),
            )
            val rows = deepArrays(response.json, listOf("info", "songs", "song", "list", "data"))
            val batch = rows.mapNotNull { mapCompleteTrack(it) }
            if (batch.isEmpty()) break
            val before = result.size
            for (song in batch) {
                if (!seen.add(song.identityKey)) continue
                result.add(song)
                if (result.size >= limit) return result.take(limit)
            }
            if (result.size == before) break
        }
        return result
    }

    /**
     * 酷狗 iOS 综合搜索接口。该接口返回的结果比旧网页接口完整，
     * 同时携带歌曲 hash、专辑、歌手、封面和权限字段。
     */
    private suspend fun searchSongsComplete(keyword: String, limit: Int): List<Song> {
        val result = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        val pageSize = min(max(limit, 1), 50)
        // 综合搜索接口经常固定只返回 15 条，即使 pagesize 请求更大；
        // 不要用 songs.count < pageSize 判断分页结束，否则歌手页永远只得到首屏。
        val pages = max(1, min(30, ceil(min(max(limit, 1), 300) / 15.0).toInt() + 2))
        for (page in 1..pages) {
            val songs = searchSongsCompletePage(keyword = keyword, page = page, pageSize = pageSize)
            if (songs.isEmpty()) break
            val before = result.size
            for (song in songs) {
                if (!seen.add(song.identityKey)) continue
                result.add(song)
                if (result.size >= limit) return result
            }
            if (result.size == before) break
        }
        return result
    }

    private suspend fun searchSongsCompletePage(keyword: String, page: Int, pageSize: Int): List<Song> {
        val auth = KugouMusicAuth
        auth.prepareDevice()
        val clientTime = "${System.currentTimeMillis() / 1000}"
        val userID = if (auth.isLoggedIn) auth.userId else "0"
        val token = if (auth.isLoggedIn) auth.token else ""
        val mid = auth.mid
        val dfid = auth.dfid
        val uuid = if (auth.guid.isEmpty()) mid else auth.guid
        val params = mutableMapOf(
            "ab_tag" to "1",
            "ability" to "57343",
            "albumhide" to "1",
            "apiver" to "22",
            "appid" to "1000",
            "area_code" to "1",
            "clienttime" to clientTime,
            "clientver" to "20549",
            "com_user_type" to "0",
            "cursor" to "${max(page, 1)}",
            "dfid" to dfid,
            "is_gpay" to "0",
            "iscorrection" to "1",
            "keyword" to keyword,
            "mid" to mid,
            "mode_ability" to "0",
            "nocollect" to "0",
            "osversion" to "16.0",
            "platform" to "IOSFilter",
            "recver" to "2",
            "req_ai" to "1",
            "search_ability" to "31",
            "search_source" to "手动输入",
            "sec_aggre" to "1",
            "sec_aggre_bitmap" to "22",
            "style_type" to "3",
            "tag" to "em",
            "token" to token,
            "userid" to userID,
            "uuid" to uuid,
        )
        params["signature"] = searchSignature(params)

        val url = buildUrl("https://gateway.kugou.com/complexsearch/v3/search/mixed", params.map { it.key to it.value })
            ?: throw BeansApiException.Unknown("酷狗综合搜索地址无效")
        val headers = mapOf(
            "KG-RF" to "D4407D2505656C0FDC1621BA6FA3FEB5",
            "KG-FAKE" to "359933394",
            "KG-FAKE-TYPE" to "29,1",
            "KG-RC" to "1",
            "UNI-UserAgent" to "iOS16.0-Phone-1009-0-WiFi",
            "Accept" to "*/*",
            "Accept-Language" to "zh-Hans-CN;q=1",
        )
        val json = getJson(
            url,
            "IPhone-20549-Search#183534257/723988397/625045823/284854956-SearchGeneralInfoWithKeyWordV8",
            headers,
        )
        val data = json.optMap("data") ?: return emptyList()
        val groups = data.arr("lists")?.objects() ?: return emptyList()
        val songGroup = groups.firstOrNull {
            val type = string(it.opt("type")).lowercase()
            type == "song" || type == "songs"
        } ?: return emptyList()
        val rows = songGroup.arr("lists")?.objects() ?: return emptyList()
        return rows.take(min(max(pageSize, 1), 100)).mapNotNull { mapCompleteTrack(it) }
    }

    /** 酷狗官方歌手搜索，保留 author_id，供歌手主页调用作者歌曲接口。 */
    private suspend fun upstreamSearchArtists(keyword: String, limit: Int): List<Artist> {
        val pageSize = min(max(limit, 1), 30)
        val pageCount = max(1, min(10, ceil(max(limit, 1) / pageSize.toDouble()).toInt()))
        val result = mutableListOf<Artist>()
        val seen = mutableSetOf<String>()

        for (page in 1..pageCount) {
            val response = upstreamRequest(
                "/v1/search/author",
                params = mapOf(
                    "albumhide" to "0",
                    "iscorrection" to "1",
                    "keyword" to keyword,
                    "nocollect" to "0",
                    "page" to "$page",
                    "pagesize" to "$pageSize",
                    "platform" to "AndroidFilter",
                ),
                headers = mapOf("x-router" to "complexsearch.kugou.com"),
            )
            val rows = deepArrays(
                response.json,
                listOf("info", "authors", "artists", "author", "list", "data"),
            )
            if (rows.isEmpty()) break

            for (item in rows) {
                val id = string(
                    item.opt("author_id") ?: item.opt("authorid") ?: item.opt("singerid")
                        ?: item.opt("singer_id") ?: item.opt("id"),
                )
                val name = clean(
                    string(item.opt("author_name") ?: item.opt("authorname") ?: item.opt("singername") ?: item.opt("name")),
                )
                if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue
                val cover = normalizeURL(
                    string(
                        item.opt("avatar") ?: item.opt("pic") ?: item.opt("imgurl")
                            ?: item.opt("img_url") ?: item.opt("author_pic"),
                    ).replace("{size}", "400"),
                )
                result.add(Artist(id = id, name = name, coverURL = cover.ifEmpty { null }, source = SongSource.KUGOU))
                if (result.size >= limit) return result
            }
            if (rows.size < pageSize) break
        }
        return result
    }

    /** 基于酷狗官方歌曲搜索结果聚合歌手，保留官方歌手名与封面。 */
    suspend fun searchArtists(keyword: String, limit: Int = 40): List<Artist> {
        val artists = runCatching { upstreamSearchArtists(keyword = keyword, limit = limit) }.getOrNull()
        if (!artists.isNullOrEmpty()) {
            Log.d(LOG_TAG, "酷狗歌手搜索完成：$keyword 结果=${artists.size}")
            return artists
        }
        val songs = searchSongs(keyword = keyword, limit = limit)
        val result = mutableListOf<Artist>()
        val seen = mutableSetOf<String>()
        for (song in songs) {
            for (name in song.artists.split("/")) {
                val value = name.trim()
                if (value.isEmpty() || !seen.add(value)) continue
                result.add(Artist(id = value, name = value, coverURL = song.coverURL, source = SongSource.KUGOU))
            }
        }
        return result
    }

    // MARK: - 歌手歌曲

    /** 酷狗官方歌手歌曲接口。每页最多 100 首，按热度排序。 */
    suspend fun artistSongs(authorID: String, page: Int = 1, limit: Int = 100): List<Song> {
        val target = min(max(limit, 1), 100)
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()

        val upstream = runCatching { upstreamArtistSongs(authorID = authorID, page = page, limit = target) }
            .getOrNull() ?: emptyList()
        for (song in upstream) {
            if (seen.add(song.identityKey)) songs.add(song)
        }

        if (songs.size < target) {
            val singer = runCatching { officialArtistSongs(authorID = authorID, page = page, limit = target) }
                .getOrNull() ?: emptyList()
            if (singer.isNotEmpty()) {
                for (song in singer) {
                    if (seen.add(song.identityKey)) songs.add(song)
                }
            }
        }

        if (songs.size < target) {
            val legacy = runCatching { legacyArtistSongs(authorID = authorID, page = page, limit = target) }
                .getOrNull() ?: emptyList()
            if (legacy.isNotEmpty()) {
                for (song in legacy) {
                    if (seen.add(song.identityKey)) songs.add(song)
                }
            }
        }

        Log.d(LOG_TAG, "酷狗歌手歌曲：author=$authorID page=$page 返回 ${songs.size} 首")
        return songs.take(target)
    }

    private suspend fun upstreamArtistSongs(authorID: String, page: Int, limit: Int): List<Song> {
        val target = min(max(limit, 1), 100)
        val response = upstreamRequest(
            "/openapi/kmr/v2/audio_group/author",
            params = mapOf(
                "author_id" to authorID,
                "area_code" to "all",
                "sort" to "1",
                "page" to "${max(page, 1)}",
                "pagesize" to "$target",
                "replace_api_version" to "1",
                "mvdata_need" to "1",
                "show_audio_honor" to "1",
                "show_audio_tag" to "1",
                "replace_need" to "1",
            ),
            headers = mapOf("kg-tid" to "36"),
        )
        val rows = deepArrays(response.json, listOf("songs", "songlist", "list", "info", "audio", "data"))
        return rows.mapNotNull { mapCompleteTrack(it) }
    }

    /** 酷狗移动端歌手歌曲列表，通常比作者接口更完整。 */
    private suspend fun officialArtistSongs(authorID: String, page: Int, limit: Int): List<Song> {
        val target = min(max(limit, 1), 100)
        val url = buildUrl(
            "https://mobilecdn.kugou.com/api/v3/singer/song",
            listOf(
                "format" to "json",
                "singerid" to authorID,
                "page" to "${max(page, 1)}",
                "pagesize" to "$target",
                "sorttype" to "2",
                "sort" to "1",
                "with_res_tag" to "1",
                "identity" to "3",
                "plat" to "0",
                "area_code" to "1",
                "version" to "9108",
            ),
        ) ?: return emptyList()
        val json = getJson(url, browserUA)
        val rows = deepArrays(json, listOf("info", "songs", "songlist", "list", "data"))
        val songs = rows.mapNotNull { mapCompleteTrack(it) }
        if (songs.isNotEmpty()) {
            Log.d(LOG_TAG, "酷狗 singer/song：author=$authorID page=$page 返回 ${songs.size} 首")
        }
        return songs
    }

    /** 老版作者歌曲接口作为分页补充，避免新版接口固定只返回 19 首。 */
    private suspend fun legacyArtistSongs(authorID: String, page: Int, limit: Int): List<Song> {
        val auth = KugouMusicAuth
        val clientTime = (System.currentTimeMillis() / 1000).toInt()
        val data = mutableMapOf<String, Any>(
            "appid" to upstreamAppID,
            "clientver" to upstreamClientVersion,
            "mid" to auth.mid,
            "clienttime" to clientTime,
            "key" to upstreamParamsKey("$clientTime"),
            "author_id" to authorID,
            "pagesize" to min(max(limit, 1), 100),
            "page" to max(page, 1),
            "sort" to 1,
            "area_code" to "all",
        )
        if (auth.userId.isNotEmpty()) {
            data["userid"] = auth.userId.toIntOrNull() ?: 0
            data["kguid"] = auth.userId.toIntOrNull() ?: 0
        }
        if (auth.token.isNotEmpty()) data["token"] = auth.token
        // 该接口同时把 JSON 体参与签名，因此这里序列化出的文本必须与实际发送的完全一致。
        val bodyString = jsonText(data)
        val params = upstreamBaseParams().toMutableMap()
        params["clienttime"] = "$clientTime"
        params["signature"] = upstreamAndroidSignature(params = params, data = bodyString)
        val response = fetch(
            baseURL = "https://openapi.kugou.com",
            path = "/kmr/v1/audio_group/author",
            method = "POST",
            params = params,
            body = bodyString,
            bodyType = JSON_TYPE,
            headers = upstreamHeaders(
                extra = mapOf(
                    "x-router" to "openapi.kugou.com",
                    "kg-tid" to "220",
                    "clienttime" to "$clientTime",
                    "Content-Type" to "application/json",
                ),
            ),
        )
        val rows = deepArrays(response.json, listOf("songs", "songlist", "list", "info", "audio", "data"))
        return rows.mapNotNull { mapCompleteTrack(it) }
    }

    /** 基于酷狗官方歌曲搜索结果聚合专辑，保留官方专辑名、歌手与封面。 */
    suspend fun searchAlbums(keyword: String, limit: Int = 40): List<Album> {
        val songs = searchSongs(keyword = keyword, limit = limit)
        val result = mutableListOf<Album>()
        val seen = mutableSetOf<String>()
        for (song in songs) {
            if (song.album.isEmpty()) continue
            val key = "${song.album}|${song.artists}"
            if (!seen.add(key)) continue
            result.add(
                Album(
                    id = key,
                    name = song.album,
                    artistName = song.artists,
                    coverURL = song.coverURL,
                    source = SongSource.KUGOU,
                    trackCount = null,
                ),
            )
        }
        return result
    }

    // MARK: - 排行榜

    /** 酷狗官方排行榜列表（移动站点 JSON）。 */
    suspend fun topLists(limit: Int = 10): List<KugouTopInfo> {
        val upstream = runCatching { upstreamTopLists(limit = limit) }.getOrNull()
        if (!upstream.isNullOrEmpty()) {
            Log.d(LOG_TAG, "酷狗 v6 排行榜列表：返回 ${upstream.size} 个")
            return upstream
        }
        val lists = runCatching { officialWebTopLists(limit = limit) }.getOrNull()
        if (!lists.isNullOrEmpty()) {
            return lists
        }
        val url = "https://m.kugou.com/rank/list?json=true"
        val json = getJson(url, browserUA)
        val rank = json.optMap("rank") ?: throw BeansApiException.Decoding("酷狗排行榜数据格式异常")
        val list = rank.arr("list")?.objects() ?: throw BeansApiException.Decoding("酷狗排行榜数据格式异常")
        return list.take(limit).mapNotNull { item ->
            val id = int(item.opt("rankid") ?: item.opt("id"))
            if (id <= 0) return@mapNotNull null
            val name = string(item.opt("rankname") ?: item.opt("name"))
            if (name.isEmpty()) return@mapNotNull null
            val cover = normalizeURL(
                string(item.opt("album_img_9") ?: item.opt("img_9") ?: item.opt("imgurl"))
                    .replace("{size}", "400"),
            )
            KugouTopInfo(
                id = id,
                name = name,
                updateFrequency = string(item.opt("update_frequency") ?: item.opt("updateFrequency")),
                coverURL = cover.ifEmpty { null },
            )
        }
    }

    private suspend fun upstreamTopLists(limit: Int): List<KugouTopInfo> {
        val response = upstreamRequest(
            "/ocean/v6/rank/list",
            params = mapOf(
                "plat" to "2",
                "withsong" to "1",
                "parentid" to "0",
            ),
        )
        val rows = deepArrays(response.json, listOf("rank", "list", "ranklist", "data", "info"))
        val seen = mutableSetOf<Int>()
        return rows.mapNotNull { item ->
            val id = int(item.opt("rankid") ?: item.opt("rank_id") ?: item.opt("id"))
            val name = clean(string(item.opt("rankname") ?: item.opt("rank_name") ?: item.opt("name") ?: item.opt("title")))
            if (id <= 0 || name.isEmpty() || !seen.add(id)) return@mapNotNull null
            val cover = normalizeURL(
                string(
                    item.opt("imgurl") ?: item.opt("img_url") ?: item.opt("img_9")
                        ?: item.opt("album_img_9") ?: item.opt("cover"),
                ).replace("{size}", "400"),
            )
            KugouTopInfo(
                id = id,
                name = name,
                updateFrequency = clean(string(item.opt("update_frequency") ?: item.opt("updateFrequency"))),
                coverURL = cover.ifEmpty { null },
            )
        }.take(max(limit, 1))
    }

    private suspend fun upstreamRankSongs(rankID: Int, limit: Int): List<Song> {
        val target = max(limit, 1)
        // The KMR endpoint may cap a response at roughly 20 rows regardless of
        // pagesize, so a single request silently truncates chart details.
        val pageSize = min(max(target, 1), 100)
        val pageCount = max(1, min(50, ceil(target / 30.0).toInt() + 2))
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()

        for (page in 1..pageCount) {
            val response = upstreamRequest(
                "/openapi/kmr/v2/rank/audio",
                method = "POST",
                data = mapOf(
                    "show_portrait_mv" to 1,
                    "show_type_total" to 1,
                    "filter_original_remarks" to 1,
                    "area_code" to 1,
                    "pagesize" to pageSize,
                    "rank_cid" to 0,
                    "type" to 1,
                    "page" to page,
                    "rank_id" to rankID,
                ),
                headers = mapOf("kg-tid" to "369"),
            )
            val rows = deepArrays(response.json, listOf("songs", "songlist", "list", "info", "data", "audio"))
            val batch = rows.mapNotNull { mapCompleteTrack(it) }
            if (batch.isEmpty()) break

            val before = songs.size
            for (song in batch) {
                if (!seen.add(song.identityKey)) continue
                songs.add(song)
                if (songs.size >= target) return songs.take(target)
            }
            // Some server-side chart variants repeat page one when they do not
            // support pagination. Stop instead of issuing identical requests.
            if (songs.size == before) break
        }

        return songs.take(target)
    }

    /** 酷狗官方排行榜歌曲。 */
    suspend fun rankSongs(rankID: Int, limit: Int = 100): List<Song> {
        val target = max(limit, 1)
        val upstream = runCatching { upstreamRankSongs(rankID = rankID, limit = target) }.getOrNull() ?: emptyList()
        if (upstream.isNotEmpty()) {
            Log.d(LOG_TAG, "酷狗 KMR 排行榜歌曲：rankid=$rankID 返回 ${upstream.size} 首")
        }

        // Keep the KMR order, but use the other official endpoints to fill a
        // short page. This handles chart variants that expose only 20-22 rows
        // through one of the APIs.
        var merged = upstream
        if (merged.size < target) {
            val official = runCatching { officialWebRankSongs(rankID = rankID, limit = target) }.getOrNull()
            if (!official.isNullOrEmpty()) {
                merged = mergeRankSongs(primary = merged, fallback = official, limit = target)
                Log.d(
                    LOG_TAG,
                    "酷狗排行榜歌曲补齐：rankid=$rankID KMR=${upstream.size} 官网=${official.size} 当前=${merged.size}",
                )
            }
        }

        // 移动端分页结果同时用于「补足曲目」和「补齐封面」；这里只向
        // /rank/info 请求一次，避免对同一页发出完全相同的重复请求。
        var mobile: List<Song>? = null
        if (merged.size < target) {
            mobile = runCatching { mobileRankSongs(rankID = rankID, limit = target) }.getOrNull()
            if (!mobile.isNullOrEmpty()) {
                merged = mergeRankSongs(primary = merged, fallback = mobile, limit = target)
                Log.d(
                    LOG_TAG,
                    "酷狗排行榜歌曲补齐：rankid=$rankID 当前=${merged.size} 移动端=${mobile.size}",
                )
            }
        }

        if (merged.isEmpty()) {
            throw BeansApiException.Decoding("酷狗排行榜歌曲为空")
        }

        // If the main source has rows but missing covers, enrich them without
        // changing the order or the song metadata selected above.
        if (merged.any { it.coverURL == null }) {
            val coverSource = mobile ?: runCatching { mobileRankSongs(rankID = rankID, limit = target) }.getOrNull()
            if (!coverSource.isNullOrEmpty()) {
                merged = mergeRankCovers(primary = merged, fallback = coverSource)
            }
        }
        return merged.take(target)
    }

    private suspend fun mobileRankSongs(rankID: Int, limit: Int): List<Song> {
        val target = max(limit, 1)
        val pageSize = min(max(target, 1), 100)
        val pageCount = max(1, min(50, ceil(target / 20.0).toInt() + 2))
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()

        for (page in 1..pageCount) {
            val url = buildUrl(
                "https://m.kugou.com/rank/info",
                listOf(
                    "rankid" to "$rankID",
                    "page" to "$page",
                    "pagesize" to "$pageSize",
                    "json" to "true",
                ),
            ) ?: throw BeansApiException.Unknown("酷狗排行榜地址无效")
            val json = getJson(url, browserUA)
            val rows = json.optMap("songs")?.arr("list")?.objects() ?: emptyList()
            val batch = rows.mapNotNull { mapCompleteTrack(it) }
            if (batch.isEmpty()) break

            val before = songs.size
            for (song in batch) {
                if (!seen.add(song.identityKey)) continue
                songs.add(song)
                if (songs.size >= target) return songs.take(target)
            }
            if (songs.size == before) break
        }

        return songs.take(target)
    }

    // MARK: - 歌单

    /** 酷狗官方歌单广场（移动站点 JSON）。 */
    suspend fun recommendPlaylists(limit: Int = 12): List<Playlist> {
        val upstream = runCatching { upstreamRecommendPlaylists(limit = limit) }.getOrNull()
        if (!upstream.isNullOrEmpty()) {
            Log.d(LOG_TAG, "酷狗 special_recommend：返回 ${upstream.size} 个歌单")
            return upstream
        }
        val playlists = runCatching { officialWebPlaylists(limit = limit) }.getOrNull()
        if (!playlists.isNullOrEmpty()) {
            return playlists
        }
        val url = "https://m.kugou.com/plist/index?json=true&page=1"
        val json = getJson(url, browserUA)
        val rows = json.optMap("plist")
            ?.optMap("list")
            ?.arr("info")
            ?.objects()
            ?: emptyList()
        return rows.take(limit).mapNotNull { item ->
            val id = int(item.opt("specialid") ?: item.opt("id"))
            if (id <= 0) return@mapNotNull null
            val name = string(item.opt("specialname") ?: item.opt("name") ?: item.opt("title"))
            if (name.isEmpty()) return@mapNotNull null
            val cover = normalizeURL(
                string(item.opt("imgurl") ?: item.opt("pic") ?: item.opt("cover")).replace("{size}", "400"),
            )
            Playlist(
                id = id.toLong(),
                name = name,
                coverURL = cover.ifEmpty { null },
                trackCount = int(item.opt("songcount") ?: item.opt("song_count")),
                source = SongSource.KUGOU,
            )
        }
    }

    private suspend fun upstreamRecommendPlaylists(limit: Int): List<Playlist> {
        val auth = KugouMusicAuth
        val clientTime = (System.currentTimeMillis() / 1000).toInt()
        val specialRecommend: Map<String, Any> = mapOf(
            "withtag" to 1,
            "withsong" to 1,
            "sort" to 1,
            "ugc" to 1,
            "is_selected" to 0,
            "withrecommend" to 1,
            "area_code" to 1,
            "categoryid" to 0,
        )
        val response = upstreamRequest(
            "/v2/special_recommend",
            method = "POST",
            data = mapOf(
                "appid" to upstreamAppID,
                "mid" to auth.mid,
                "clientver" to upstreamClientVersion,
                "platform" to "android",
                "clienttime" to clientTime,
                "userid" to (auth.userId.toIntOrNull() ?: 0),
                "module_id" to 1,
                "page" to 1,
                "pagesize" to min(max(limit, 1), 30),
                "key" to upstreamParamsKey("$clientTime"),
                "special_recommend" to specialRecommend,
                "req_multi" to 1,
                "retrun_min" to 5,
                "return_special_falg" to 1,
            ),
            headers = mapOf("x-router" to "specialrec.service.kugou.com"),
        )
        val rows = deepArrays(
            response.json,
            listOf("special_recommend", "playlists", "playlist", "list", "info", "data"),
        )
        val seen = mutableSetOf<Long>()
        return rows.mapNotNull { item ->
            val playlist = mapPlaylist(item) ?: return@mapNotNull null
            if (!seen.add(playlist.id)) return@mapNotNull null
            playlist
        }.take(max(limit, 1))
    }

    private suspend fun officialWebTopLists(limit: Int): List<KugouTopInfo> {
        val url = "https://www.kugou.com/yy/html/rank.html"
        val html = getString(url, browserUA)
        val pattern =
            """<a title="([^"]+)"[^>]*href="https://www\.kugou\.com/yy/rank/home/1-(\d+)\.html\?from=rank"[\s\S]*?background-image:url\(([^\)]+)\)"""
        val seen = mutableSetOf<Int>()
        val rows = regexMatches(pattern, html).mapNotNull { groups ->
            if (groups.size < 4) return@mapNotNull null
            val id = groups[2].toIntOrNull() ?: 0
            if (id <= 0 || !seen.add(id)) return@mapNotNull null
            val cover = normalizeURL(groups[3].trim())
            KugouTopInfo(
                id = id,
                name = clean(htmlDecode(groups[1])),
                updateFrequency = "酷狗官网热门榜单",
                coverURL = cover.ifEmpty { null },
            )
        }
        Log.d(LOG_TAG, "酷狗官网热门榜单：返回 ${rows.size} 个")
        return rows.take(limit)
    }

    private suspend fun officialWebRankSongs(rankID: Int, limit: Int): List<Song> {
        val url = "https://www.kugou.com/yy/rank/home/1-$rankID.html?from=rank"
        val html = getString(url, browserUA)
        val rows = javascriptArray(named = "global.features", html = html)
            ?: throw BeansApiException.Decoding("酷狗官网排行榜歌曲格式异常")
        val songs = rows.take(limit).mapNotNull { mapCompleteTrack(it) }
        Log.d(LOG_TAG, "酷狗官网排行榜歌曲：rankid=$rankID 返回 ${songs.size} 首")
        return songs
    }

    private suspend fun officialWebPlaylists(limit: Int): List<Playlist> {
        val url = "https://www.kugou.com/yy/html/special.html"
        val html = getString(url, browserUA)
        val pattern =
            """<li class="s_(\d+)"[\s\S]*?<a[^>]+title="([^"]+)" href="https://www\.kugou\.com/songlist/(gcid_[^/]+)/"[\s\S]*?_src="([^"]+)""""
        val seen = mutableSetOf<Int>()
        val rows = regexMatches(pattern, html).mapNotNull { groups ->
            if (groups.size < 5) return@mapNotNull null
            val id = groups[1].toIntOrNull() ?: 0
            if (id <= 0 || !seen.add(id)) return@mapNotNull null
            val cover = normalizeURL(groups[4].replace("{size}", "400"))
            Playlist(
                id = id.toLong(),
                name = clean(htmlDecode(groups[2])),
                coverURL = cover.ifEmpty { null },
                trackCount = 0,
                source = SongSource.KUGOU,
            )
        }
        Log.d(LOG_TAG, "酷狗官网歌单广场：返回 ${rows.size} 个")
        return rows.take(limit)
    }

    private suspend fun officialWebPlaylistSongs(listID: Int): List<Song> {
        val url = "https://www.kugou.com/yy/special/single/$listID.html"
        val html = getString(url, browserUA)
        val rows = javascriptArray(named = "data", html = html)
            ?: throw BeansApiException.Decoding("酷狗官网歌单歌曲格式异常")
        val songs = rows.mapNotNull { mapCompleteTrack(it) }
        Log.d(LOG_TAG, "酷狗官网歌单歌曲：specialid=$listID 返回 ${songs.size} 首")
        return songs
    }

    /**
     * 酷狗搜索页专用热词。酷狗没有稳定公开的热搜 JSON 合约时使用独立词表，
     * 确保切换到酷狗后不会继续显示网易云热搜。
     */
    suspend fun hotWords(): List<String> = listOf(
        "周杰伦", "林俊杰", "陈奕迅", "薛之谦", "邓紫棋",
        "凤凰传奇", "五月天", "毛不易", "告五人", "热门歌曲",
    )

    suspend fun playlistSongs(listID: Int): List<Song> {
        if (listID >= 1000) {
            val songs = runCatching { officialWebPlaylistSongs(listID = listID) }.getOrNull()
            if (!songs.isNullOrEmpty()) {
                return songs
            }
        }
        val auth = KugouMusicAuth
        if (!auth.isLoggedIn) return emptyList()
        val pid = "$listID"
        val all = mutableListOf<JSONObject>()
        var page = 1
        val pageSize = 200
        val maxSongs = 10_000
        do {
            val body: Map<String, Any> = mapOf(
                "listid" to pid,
                "page" to page,
                "pagesize" to pageSize,
                "area_code" to 1,
                "show_relate_goods" to 0,
                "allplatform" to 1,
                "show_cover" to 1,
                "type" to 0,
                "userid" to (auth.userId.toIntOrNull() ?: 0),
                "token" to auth.token,
            )
            val json = cloudlistRequest(
                "/v4/get_list_all_file",
                params = mapOf("listid" to pid, "page" to "$page", "pagesize" to "200"),
                data = body,
            )
            val pageTracks = deepArrays(json, listOf("songs", "songlist", "list", "info", "files", "data"))
            Log.d(LOG_TAG, "酷狗歌单歌曲：listid=$pid page=$page 返回 ${pageTracks.size} 首")
            all.addAll(pageTracks)
            if (all.size >= maxSongs) break
            if (pageTracks.size < pageSize) break
            page += 1
        } while (page <= maxSongs / pageSize)
        val limited = if (all.size > maxSongs) all.take(maxSongs) else all
        Log.d(LOG_TAG, "酷狗歌单歌曲：listid=$pid 最终最多加载 ${limited.size} 首")
        return limited
            .sortedBy { int(it.opt("fsort") ?: it.opt("sort") ?: it.opt("position")) }
            .mapNotNull { mapTrack(it) }
    }

    // MARK: - 播放地址

    suspend fun songURL(song: Song, quality: BeansAudioQuality? = null): String? {
        refreshMembershipStatusIfNeeded()
        val requestedQuality = quality ?: currentQuality()
        var primary = song.kugouHash
        var qualityHashes = song.kugouQualityHashes
        var albumAudioId = song.kugouAlbumAudioId
        var albumId = song.kugouAlbumId
        if (qualityHashCandidates(primary = primary, qualityHashes = qualityHashes, quality = requestedQuality).isEmpty()) {
            val completed = runCatching { completePlaybackMetadata(forSong = song) }.getOrNull()
            if (completed != null) {
                primary = completed.kugouHash ?: primary
                qualityHashes = completed.kugouQualityHashes ?: qualityHashes
                albumAudioId = completed.kugouAlbumAudioId ?: albumAudioId
                albumId = completed.kugouAlbumId ?: albumId
                Log.d(
                    LOG_TAG,
                    "酷狗播放元数据补齐：${song.name} hash=${if ((primary ?: "").isEmpty()) "无" else "有"} albumAudioId=${albumAudioId ?: ""}",
                )
            }
        }
        val hashes = qualityHashCandidates(primary = primary, qualityHashes = qualityHashes, quality = requestedQuality)
        if (hashes.isEmpty()) return null
        return songURL(hashes = hashes, albumAudioId = albumAudioId, albumId = albumId, quality = requestedQuality)
    }

    suspend fun songURL(hash: String, albumAudioId: String?, albumId: String?): String? =
        songURL(hashes = listOf(hash), albumAudioId = albumAudioId, albumId = albumId, quality = currentQuality())

    private suspend fun songURL(
        hashes: List<String>,
        albumAudioId: String?,
        albumId: String?,
        quality: BeansAudioQuality,
    ): String? {
        val auth = KugouMusicAuth
        val vipTypes = vipTypeCandidates(auth.vipType, auth.isLoggedIn)
        var lastCode = 0
        var lastStatus = 0
        for (hash in hashes) {
            val latest = runCatching {
                upstreamSongURLOnce(hash = hash, albumAudioId = albumAudioId, albumId = albumId, requestedQuality = quality)
            }.getOrNull()
            val latestURL = latest?.url
            if (!latestURL.isNullOrEmpty()) {
                Log.d(LOG_TAG, "酷狗 KuGouMusicApi 播放地址命中：hash=${hash.take(8)}")
                return latestURL
            }
            for (vipType in vipTypes) {
                val result = runCatching {
                    songURLOnce(hash = hash, albumAudioId = albumAudioId, albumId = albumId, vipType = vipType)
                }.getOrNull() ?: continue
                lastCode = result.code
                lastStatus = result.status
                val url = result.url
                if (!url.isNullOrEmpty()) {
                    if (vipType != auth.vipType) {
                        Log.d(LOG_TAG, "酷狗播放地址命中：hash=${hash.take(8)} vipType=$vipType")
                    }
                    return url
                }
            }
            // 酷狗新版客户端使用 v5/url。旧版 i/v2 在部分新曲和会员曲目上
            // 只返回 status，不返回播放地址，因此再尝试一次官方新版通道。
            for (vipType in vipTypes) {
                val v5 = runCatching {
                    songURLV5Once(
                        hash = hash,
                        albumAudioId = albumAudioId,
                        albumId = albumId,
                        vipType = vipType,
                        requestedQuality = quality,
                    )
                }.getOrNull() ?: continue
                lastCode = v5.code
                lastStatus = v5.status
                val url = v5.url
                if (!url.isNullOrEmpty()) {
                    if (vipType != auth.vipType) {
                        Log.d(LOG_TAG, "酷狗 v5 播放地址命中：hash=${hash.take(8)} vipType=$vipType")
                    }
                    return url
                }
            }
            val web = songURLWebOnce(hash = hash, albumAudioId = albumAudioId, albumId = albumId)
            lastCode = web.code
            lastStatus = web.status
            val webURL = web.url
            if (!webURL.isNullOrEmpty()) return webURL
        }
        val vipTypeText = vipTypes.joinToString("/")
        Log.d(
            LOG_TAG,
            "酷狗播放地址为空：hash候选=${hashes.size} vipType=$vipTypeText 已登录=${if (auth.isLoggedIn) "是" else "否"} " +
                "token=${if (auth.token.isEmpty()) "无" else "有"} dfid=${if (auth.dfid.isEmpty()) "无" else "有"} " +
                "status=$lastStatus code=$lastCode",
        )
        return null
    }

    /** KuGouMusicApi 使用的 song_url 请求：v5/url + signKey，不依赖网页播放地址。 */
    private suspend fun upstreamSongURLOnce(
        hash: String,
        albumAudioId: String?,
        albumId: String?,
        requestedQuality: BeansAudioQuality,
    ): PlayURLResult {
        val auth = KugouMusicAuth
        auth.prepareDevice()
        val fileHash = hash.lowercase()
        val quality = when (requestedQuality) {
            BeansAudioQuality.STANDARD -> "128"
            BeansAudioQuality.HIGHER, BeansAudioQuality.EXHIGH -> "320"
            BeansAudioQuality.LOSSLESS -> "flac"
            BeansAudioQuality.HIRES -> "high"
        }

        val params = upstreamBaseParams().toMutableMap()
        params["album_id"] = albumId ?: "0"
        params["area_code"] = "1"
        params["hash"] = fileHash
        params["ssa_flag"] = "is_fromtrack"
        params["version"] = upstreamSongClientVersion
        params["quality"] = quality
        params["behavior"] = "play"
        params["pid"] = "2"
        params["pidversion"] = "3001"
        params["cmd"] = "26"
        params["page_id"] = "151369488"
        params["ppage_id"] = "463467626,350369493,788954147"
        params["cdnBackup"] = "1"
        params["module"] = ""
        params["clientver"] = upstreamSongClientVersion
        params["key"] =
            "$fileHash$playSignSalt$upstreamAppID${auth.mid}${if (auth.userId.isEmpty()) "0" else auth.userId}".md5Hex()
        if (!albumAudioId.isNullOrEmpty()) {
            params["album_audio_id"] = albumAudioId
        }

        val response = fetch(
            baseURL = gateway,
            path = "/v5/url",
            method = "GET",
            params = params,
            headers = upstreamHeaders(
                extra = mapOf(
                    "x-router" to "trackercdn.kugou.com",
                    "dfid" to auth.dfid,
                    "mid" to auth.mid,
                    "Cookie" to auth.cookieHeader,
                ),
            ),
        )
        val status = deepInt(response.json, listOf("status", "result"))
        val code = deepInt(response.json, listOf("error_code", "errcode", "code"))
        val raw = deepString(response.json, listOf("play_url", "play_backup_url", "url", "src", "backup_url"))
        return PlayURLResult(url = raw.ifEmpty { null }, status = status, code = code)
    }

    /** 酷狗官方新版播放地址接口，参数结构与酷狗客户端的 v5/url 通道一致。 */
    private suspend fun songURLV5Once(
        hash: String,
        albumAudioId: String?,
        albumId: String?,
        vipType: Int,
        requestedQuality: BeansAudioQuality,
    ): PlayURLResult {
        val auth = KugouMusicAuth
        val quality = when (requestedQuality) {
            BeansAudioQuality.STANDARD -> "128"
            BeansAudioQuality.HIGHER, BeansAudioQuality.EXHIGH -> "320"
            BeansAudioQuality.LOSSLESS -> "flac"
            BeansAudioQuality.HIRES -> "hires"
        }
        val userId = if (auth.userId.isEmpty()) "0" else auth.userId
        val fileHash = hash.lowercase()
        val params = mutableMapOf(
            "album_id" to (albumId ?: "0"),
            "area_code" to "1",
            "hash" to fileHash,
            "ssa_flag" to "is_fromtrack",
            "version" to "11430",
            "quality" to quality,
            "behavior" to "play",
            "pid" to "2",
            "pidversion" to "3001",
            "cmd" to "26",
            "appid" to playAppid,
            "page_id" to "151369488",
            "ppage_id" to "463467626,350369493,788954147",
            "cdnBackup" to "1",
            "module" to "",
            "clientver" to playClientver,
            "dfid" to auth.dfid,
            "mid" to auth.mid,
            "userid" to userId,
            "token" to auth.token,
            "vipType" to "$vipType",
            "IsFreePart" to if (vipType > 0) "0" else "1",
            "key" to "$fileHash$playSignSalt$playAppid${auth.mid}$userId".md5Hex(),
        )
        if (!albumAudioId.isNullOrEmpty()) {
            params["album_audio_id"] = albumAudioId
        }
        val url = buildUrl("$gateway/v5/url", params.map { it.key to it.value })
            ?: throw BeansApiException.Unknown("酷狗播放地址无效")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", playbackUA)
            .header("x-router", "trackercdn.kugou.com")
            .header("dfid", auth.dfid)
            .header("mid", auth.mid)
            .header("Cookie", auth.cookieHeader)
            .get()
            .build()
        val text = executeText(request) ?: return PlayURLResult(url = null, status = 0, code = -1)
        val json = parseJson(text) ?: return PlayURLResult(url = null, status = 0, code = -2)
        val status = deepInt(json, listOf("status", "result"))
        val code = deepInt(json, listOf("error_code", "errcode", "code"))
        val raw = deepString(json, listOf("play_url", "play_backup_url", "url", "src", "backup_url"))
        return PlayURLResult(url = raw.ifEmpty { null }, status = status, code = code)
    }

    /** 网页播放通道。部分帐号登录态在移动端 tracker 返回 20006 时，网页接口仍会返回授权后的播放地址。 */
    private suspend fun songURLWebOnce(hash: String, albumAudioId: String?, albumId: String?): PlayURLResult {
        val auth = KugouMusicAuth
        val params = mutableMapOf(
            "r" to "play/getdata",
            "hash" to hash.uppercase(),
            "appid" to "1014",
            "platid" to "4",
            "mid" to auth.mid,
            "dfid" to auth.dfid,
            "userid" to (if (auth.userId.isEmpty()) "0" else auth.userId),
            "token" to auth.token,
        )
        if (!albumId.isNullOrEmpty()) params["album_id"] = albumId
        if (!albumAudioId.isNullOrEmpty()) params["album_audio_id"] = albumAudioId
        val url = buildUrl("https://wwwapi.kugou.com/yy/index.php", params.map { it.key to it.value })
            ?: throw BeansApiException.Unknown("酷狗播放地址无效")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", browserUA)
            .header("Referer", "https://www.kugou.com/")
            .header("Cookie", auth.cookieHeader)
            .get()
            .build()
        val text = executeText(request) ?: return PlayURLResult(url = null, status = 0, code = -1)
        val json = parseJson(text) ?: return PlayURLResult(url = null, status = 0, code = -2)
        val status = deepInt(json, listOf("status", "result"))
        val code = deepInt(json, listOf("error_code", "errcode", "code"))
        val raw = deepString(json, listOf("play_url", "play_backup_url", "url", "src", "backup_url"))
        return PlayURLResult(url = raw.ifEmpty { null }, status = status, code = code)
    }

    private suspend fun completePlaybackMetadata(forSong: Song): Song? {
        val keyword = listOf(forSong.name, forSong.artists).filter { it.isNotEmpty() }.joinToString(" ")
        if (keyword.isEmpty()) return null
        val candidates = searchSongs(keyword = keyword, limit = 8)
        val targetDuration = forSong.duration
        return candidates.firstOrNull { candidate ->
            val expected = forSong.kugouAlbumAudioId
            val actual = candidate.kugouAlbumAudioId
            if (expected != null && actual != null && expected.isNotEmpty() && expected == actual) {
                return@firstOrNull true
            }
            val nameOK = candidate.name == forSong.name
            val artistOK = forSong.artists.isEmpty() || candidate.artists.contains(forSong.artists) ||
                forSong.artists.contains(candidate.artists)
            val durationOK = targetDuration <= 0 || abs(candidate.duration - targetDuration) < 12
            nameOK && artistOK && durationOK
        } ?: candidates.firstOrNull()
    }

    private suspend fun songURLOnce(
        hash: String,
        albumAudioId: String?,
        albumId: String?,
        vipType: Int,
    ): PlayURLResult {
        val auth = KugouMusicAuth
        val h = hash.uppercase()
        val params = mutableMapOf(
            "cmd" to "26",
            "hash" to h,
            "behavior" to "play",
            "appid" to appid,
            "pid" to "2",
            "mid" to auth.mid,
            "userid" to (if (auth.userId.isEmpty()) "0" else auth.userId),
            "version" to clientver,
            "vipType" to "$vipType",
            "token" to (if (auth.token.isEmpty()) "0" else auth.token),
            "key" to "$h$playSalt$appid${auth.mid}${if (auth.userId.isEmpty()) "0" else auth.userId}".md5Hex(),
        )
        if (!albumAudioId.isNullOrEmpty()) params["album_audio_id"] = albumAudioId
        if (!albumId.isNullOrEmpty()) params["album_id"] = albumId
        val url = buildUrl("https://trackercdn.kugou.com/i/v2/", params.map { it.key to it.value })
            ?: throw BeansApiException.Unknown("酷狗播放地址无效")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", androidUA)
            .header("Cookie", auth.cookieHeader)
            .get()
            .build()
        val raw = executeText(request) ?: return PlayURLResult(url = null, status = 0, code = -1)
        val text = raw
            .replace("<!--KG_TAG_RES_START-->", "")
            .replace("<!--KG_TAG_RES_END-->", "")
            .trim()
        val json = parseJson(text) ?: return PlayURLResult(url = null, status = 0, code = -2)
        val status = deepInt(json, listOf("status"))
        val code = deepInt(json, listOf("error_code", "errcode", "code"))
        val play = deepString(json, listOf("play_url", "play_backup_url", "url", "src", "backup_url"))
        return PlayURLResult(url = play.ifEmpty { null }, status = status, code = code)
    }

    private suspend fun refreshMembershipStatusIfNeeded(force: Boolean = false) {
        val auth = KugouMusicAuth
        if (!auth.isLoggedIn || auth.hasMembership) return
        val last = lastMembershipProbeAt
        if (!force && last != null && (System.currentTimeMillis() - last) < 300_000) {
            return
        }
        lastMembershipProbeAt = System.currentTimeMillis()
        for (listID in listOf("3", "2")) {
            try {
                val body: Map<String, Any> = mapOf(
                    "listid" to listID,
                    "page" to 1,
                    "pagesize" to 1,
                    "area_code" to 1,
                    "show_relate_goods" to 0,
                    "allplatform" to 1,
                    "show_cover" to 1,
                    "type" to 0,
                    "userid" to (auth.userId.toIntOrNull() ?: 0),
                    "token" to auth.token,
                )
                val json = cloudlistRequest(
                    "/v4/get_list_all_file",
                    params = mapOf("listid" to listID, "page" to "1", "pagesize" to "1"),
                    data = body,
                )
                val first = deepArrays(json, listOf("songs", "songlist", "list", "info", "files", "data"))
                    .firstOrNull() ?: continue
                val song = mapTrack(first) ?: continue
                val hash = song.kugouHash
                if (hash.isNullOrEmpty()) continue
                val probe = songURLOnce(
                    hash = hash,
                    albumAudioId = song.kugouAlbumAudioId,
                    albumId = song.kugouAlbumId,
                    vipType = 1,
                )
                val url = probe.url
                if (!url.isNullOrEmpty()) {
                    KugouMusicAuth.updateVIPType(1)
                    Log.d(LOG_TAG, "酷狗会员状态补齐：播放探测成功 listid=$listID vipType=1")
                    return
                }
                Log.d(LOG_TAG, "酷狗会员状态探测未命中：listid=$listID status=${probe.status} code=${probe.code}")
            } catch (e: Exception) {
                Log.d(LOG_TAG, "酷狗会员状态探测失败：listid=$listID ${e.message ?: ""}")
            }
        }
    }

    // MARK: - 歌词

    suspend fun lyric(hash: String, duration: Double): String {
        if (hash.isEmpty()) return ""
        val searchURL = buildUrl(
            "http://lyrics.kugou.com/search",
            listOf(
                "ver" to "1",
                "man" to "yes",
                "client" to "pc",
                "hash" to hash.uppercase(),
                "duration" to "${(duration * 1000).toInt()}",
            ),
        ) ?: return ""
        val first = runCatching {
            getJson(searchURL, browserUA).arr("candidates")?.objects()?.firstOrNull()
        }.getOrNull() ?: return ""
        val id = first.opt("id") ?: return ""
        val accessKey = first.opt("accesskey") ?: return ""
        val downloadURL = buildUrl(
            "http://lyrics.kugou.com/download",
            listOf(
                "ver" to "1",
                "client" to "pc",
                "id" to string(id),
                "accesskey" to string(accessKey),
                "fmt" to "lrc",
                "charset" to "utf8",
            ),
        ) ?: return ""
        val djson = runCatching { getJson(downloadURL, browserUA) }.getOrNull() ?: return ""
        val content = djson.opt("content") as? String ?: return ""
        val data = runCatching { Base64.getDecoder().decode(content.replace("\n", "")) }.getOrNull() ?: return ""
        return String(data, Charsets.UTF_8)
    }

    // MARK: - 酷狗评论

    /** 酷狗官方移动评论接口。评论读取不依赖会员权限。 */
    suspend fun comments(
        mixSongID: String,
        hash: String? = null,
        page: Int = 1,
        limit: Int = 30,
    ): KugouCommentPage {
        var commentID = mixSongID.trim()
        if (commentID.isEmpty() || commentID.toIntOrNull() == null) {
            val resolved = runCatching { commentAudioID(hash = hash) }.getOrNull()
            if (resolved != null) commentID = resolved
        }
        if (commentID.isEmpty()) {
            throw BeansApiException.Unknown("酷狗评论缺少歌曲 ID")
        }
        val params = mapOf(
            "mixsongid" to commentID,
            "need_show_image" to "1",
            "p" to "${max(page, 1)}",
            "pagesize" to "${min(max(limit, 1), 30)}",
            "show_classify" to "1",
            "show_hotword_list" to "1",
            "extdata" to "0",
            "code" to "fc4be23b4e972707f36b8a828a93ba8a",
        )
        val json: JSONObject = try {
            gatewayRequest(
                "/mcomment/v1/cmtlist",
                baseURL = gateway,
                method = "POST",
                params = params,
                headers = mapOf("x-router" to "mcomment.service.kugou.com"),
            ).json
        } catch (e: Exception) {
            Log.d(LOG_TAG, "酷狗评论 POST 失败：${e.message ?: ""}，尝试 GET 兜底")
            try {
                gatewayCommentJSON(params = params)
            } catch (e2: Exception) {
                Log.d(LOG_TAG, "酷狗评论 gateway 全部失败：${e2.message ?: ""}，尝试旧版评论接口")
                legacyCommentJSON(childrenID = commentID, page = page, limit = limit)
            }
        }
        val parsed = parseComments(json = json, page = page, songName = commentID)
        if (parsed.comments.isEmpty()) {
            val legacy = runCatching {
                legacyCommentJSON(childrenID = commentID, page = page, limit = limit)
            }.getOrNull()
            if (legacy != null) {
                return parseComments(json = legacy, page = page, songName = commentID)
            }
        }
        return parsed
    }

    private suspend fun gatewayCommentJSON(params: Map<String, String>): JSONObject {
        return try {
            gatewayRequest(
                "/mcomment/v1/cmtlist",
                baseURL = gateway,
                method = "GET",
                params = params,
                headers = mapOf("x-router" to "mcomment.service.kugou.com"),
            ).json
        } catch (e: Exception) {
            Log.d(LOG_TAG, "酷狗评论 GET 失败：${e.message ?: ""}，尝试备用路由")
            gatewayRequest(
                "/m.comment.service/v1/cmtlist",
                baseURL = gateway,
                method = "GET",
                params = params,
                headers = mapOf("x-router" to "m.comment.service.kugou.com"),
            ).json
        }
    }

    private suspend fun legacyCommentJSON(childrenID: String, page: Int, limit: Int): JSONObject {
        val url = buildUrl(
            "http://m.comment.service.kugou.com/index.php",
            listOf(
                "r" to "commentsv2/getCommentWithLike",
                "childrenid" to childrenID,
                "code" to "fc4be23b4e972707f36b8a828a93ba8a",
                "extdata" to "0",
                "p" to "${max(page, 1)}",
                "pagesize" to "${min(max(limit, 1), 30)}",
            ),
        ) ?: throw BeansApiException.Network("network error")
        return getJson(url, browserUA)
    }

    private suspend fun commentAudioID(hash: String?): String? {
        if (hash.isNullOrEmpty()) return null
        val url = buildUrl(
            "https://wwwapi.kugou.com/yy/index.php",
            listOf(
                "r" to "play/getdata",
                "hash" to hash.uppercase(),
                "appid" to "1014",
                "platid" to "4",
            ),
        ) ?: return null
        val json = getJson(url, browserUA)
        val value = deepString(json, listOf("album_audio_id", "audio_id", "mixsongid", "songid", "id"))
        return value.ifEmpty { null }
    }

    // MARK: - 设备注册

    private suspend fun registerDevice() {
        val auth = KugouMusicAuth
        val guid = if (auth.guid.isEmpty()) UUID.randomUUID().toString() else auth.guid
        val dataMap: Map<String, Any> = mapOf(
            "availableRamSize" to 4983533568L,
            "availableRomSize" to 48114719,
            "availableSDSize" to 48114717,
            "basebandVer" to "",
            "batteryLevel" to 100,
            "batteryStatus" to 3,
            "brand" to "Redmi",
            "buildSerial" to "unknown",
            "device" to "marble",
            "imei" to guid,
            "imsi" to "",
            "manufacturer" to "Xiaomi",
            "uuid" to guid,
            "accelerometer" to false,
            "gyroscope" to false,
        )
        val aes = randomLower(6) ?: return
        val encrypted = aesCBCEncrypt(jsonText(dataMap).toByteArray(Charsets.UTF_8), password = aes) ?: return
        val pData = jsonText(
            mapOf(
                "aes" to aes,
                "uid" to (auth.userId.toIntOrNull() ?: 0),
                "token" to auth.token,
            ),
        ).toByteArray(Charsets.UTF_8)
        val rsa = rsaEncryptPKCS1(pData, publicKeyBase64 = rsaPublicKeyBase64)?.toHexString() ?: return
        try {
            val response = gatewayRequest(
                "/risk/v2/r_register_dev",
                baseURL = userService,
                method = "POST",
                params = mapOf("part" to "1", "platid" to "1", "p" to rsa),
                dataRaw = encrypted,
                headers = mapOf(
                    "x-router" to "userservice.kugou.com",
                    "Content-Type" to "application/octet-stream",
                ),
                responseAsData = true,
            )
            var json: JSONObject? = parseJson(String(response.rawData, Charsets.UTF_8))
            if (json == null) {
                val decrypted = aesCBCDecrypt(response.rawData, password = aes)
                if (decrypted != null) json = parseJson(String(decrypted, Charsets.UTF_8))
            }
            val dfid = deepString(json ?: JSONObject(), listOf("dfid"))
            if (dfid.isNotEmpty()) KugouMusicAuth.saveDeviceDFID(dfid)
            Log.d(LOG_TAG, "酷狗设备注册：dfid=${if (dfid.isEmpty()) "未返回" else "已获取"}")
        } catch (e: Exception) {
            Log.d(LOG_TAG, "酷狗设备注册失败：${e.message ?: ""}")
        }
    }

    // MARK: - 请求封装

    private enum class SignType { Android, Web }

    /** 响应体 + 原始字节（原始字节用于设备注册的 AES 解密）。 */
    private class RawResponse(val json: JSONObject, val rawData: ByteArray)

    /** 单次播放地址解析的结果：url + status + code。 */
    private class PlayURLResult(val url: String?, val status: Int, val code: Int)

    private suspend fun cloudlistRequest(path: String, params: Map<String, String>, data: Map<String, Any>): JSONObject {
        val auth = KugouMusicAuth
        val final = baseParams()
        final["userid"] = auth.userId
        final["token"] = auth.token
        for ((key, value) in params) final[key] = value
        val body = jsonText(data)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        final["signature"] = androidSignature(params = final, data = body)
        val response = fetch(
            baseURL = gateway,
            path = path,
            method = if (bodyBytes.isEmpty()) "GET" else "POST",
            params = final,
            body = body,
            bodyType = JSON_TYPE,
            headers = mapOf(
                "User-Agent" to androidUA,
                "x-router" to "cloudlist.service.kugou.com",
                "kg-rc" to "1",
                "kg-thash" to "5d816a0",
                "kg-rec" to "1",
                "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
                "dfid" to auth.dfid,
                "mid" to auth.mid,
                "Content-Type" to "application/json",
                "Cookie" to auth.cookieHeader,
            ),
        )
        return response.json
    }

    private suspend fun gatewayRequest(
        path: String,
        baseURL: String? = null,
        method: String = "GET",
        signType: SignType = SignType.Android,
        params: Map<String, String> = emptyMap(),
        data: Map<String, Any>? = null,
        dataRaw: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        responseAsData: Boolean = false,
    ): RawResponse {
        val final = baseParams()
        for ((key, value) in params) final[key] = value
        val body: String? = if (data != null) jsonText(data) else dataRaw?.let { String(it, Charsets.UTF_8) }
        val bodyString = body ?: ""
        final["signature"] = if (signType == SignType.Web) {
            webSignature(params = final)
        } else {
            androidSignature(params = final, data = bodyString)
        }
        val requestHeaders = mutableMapOf(
            "User-Agent" to androidUA,
            "kg-rc" to "1",
            "kg-thash" to "5d816a0",
            "kg-rec" to "1",
            "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
            "dfid" to KugouMusicAuth.dfid,
            "mid" to KugouMusicAuth.mid,
            "clienttime" to (final["clienttime"] ?: ""),
        )
        if (KugouMusicAuth.cookieHeader.isNotEmpty()) requestHeaders["Cookie"] = KugouMusicAuth.cookieHeader
        for ((key, value) in headers) requestHeaders[key] = value
        val bodyType = if (data != null) JSON_TYPE else OCTET_TYPE
        val response = fetch(
            baseURL = baseURL ?: gateway,
            path = path,
            method = method,
            params = final,
            body = body,
            bodyType = bodyType,
            rawBody = dataRaw,
            headers = requestHeaders,
        )
        // Swift 保留了 `responseAsData` 开关但两条分支返回同一个值：调用方自己决定
        // 解析 json 还是使用 rawData。
        if (responseAsData) return response
        return response
    }

    /**
     * KuGouMusicApi 的标准版请求封装。它与应用原先的 3116/11440
     * 请求保持分开，避免不同客户端配置互相覆盖。
     */
    private suspend fun upstreamRequest(
        path: String,
        method: String = "GET",
        params: Map<String, String> = emptyMap(),
        data: Map<String, Any>? = null,
        headers: Map<String, String> = emptyMap(),
    ): RawResponse {
        val body = data?.let { jsonText(it) }
        val final = upstreamBaseParams()
        for ((key, value) in params) final[key] = value
        final["signature"] = upstreamAndroidSignature(params = final, data = body ?: "")
        val requestHeaders = upstreamHeaders(extra = headers)
        if (body != null && !requestHeaders.containsKey("Content-Type")) {
            requestHeaders["Content-Type"] = "application/json"
        }
        return fetch(
            baseURL = gateway,
            path = path,
            method = method,
            params = final,
            body = body,
            bodyType = JSON_TYPE,
            headers = requestHeaders,
        )
    }

    private fun upstreamBaseParams(): MutableMap<String, String> {
        val auth = KugouMusicAuth
        val params = mutableMapOf(
            "dfid" to auth.dfid,
            "mid" to auth.mid,
            "uuid" to "-",
            "appid" to upstreamAppID,
            "clientver" to upstreamClientVersion,
            "clienttime" to "${System.currentTimeMillis() / 1000}",
        )
        if (auth.isLoggedIn) {
            params["token"] = auth.token
            params["userid"] = auth.userId
        }
        return params
    }

    private fun upstreamParamsKey(value: String): String =
        "$upstreamAppID$upstreamSignSalt$upstreamClientVersion$value".md5Hex()

    private fun upstreamHeaders(extra: Map<String, String> = emptyMap()): MutableMap<String, String> {
        val auth = KugouMusicAuth
        val headers = mutableMapOf(
            "User-Agent" to androidUA,
            "kg-rc" to "1",
            "kg-thash" to "5d816a0",
            "kg-rec" to "1",
            "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
            "dfid" to auth.dfid,
            "mid" to auth.mid,
        )
        if (auth.cookieHeader.isNotEmpty()) {
            headers["Cookie"] = auth.cookieHeader
        }
        for ((key, value) in extra) headers[key] = value
        return headers
    }

    /**
     * 单次底层请求：URL 查询串由 params 按插入顺序拼装，
     * 与 Swift `URLComponents.queryItems` 的编码保持等价。
     */
    private suspend fun fetch(
        baseURL: String,
        path: String,
        method: String,
        params: Map<String, String>,
        body: String? = null,
        bodyType: okhttp3.MediaType = JSON_TYPE,
        rawBody: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): RawResponse {
        // 测试专用的离线闸门（默认关闭，见 Http.offlineMode）：绝不发出真实请求。
        Http.guardNetwork()
        val url = buildUrl(baseURL + path, params.map { it.key to it.value })
            ?: throw BeansApiException.Network("network error")
        val builder = Request.Builder().url(url)
        for ((key, value) in headers) {
            if (value.isNotEmpty()) builder.header(key, value)
        }
        val payload = rawBody ?: body?.toByteArray(Charsets.UTF_8)
        when {
            payload != null -> builder.method(method, payload.toRequestBody(bodyType))
            method.equals("GET", ignoreCase = true) -> builder.get()
            else -> builder.method(method, ByteArray(0).toRequestBody(bodyType))
        }
        val text = executeText(builder.build()) ?: throw BeansApiException.Network("network error")
        val json = parseJson(text) ?: JSONObject()
        return RawResponse(json = json, rawData = text.toByteArray(Charsets.UTF_8))
    }

    /** 执行请求并返回文本体；非 2xx 或 IO 失败时返回 null（对应 Swift 的 `throw NetEaseError.network`）。 */
    private suspend fun executeText(request: Request): String? = withContext(Dispatchers.IO) {
        try {
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) null else text
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun baseParams(): MutableMap<String, String> {
        val auth = KugouMusicAuth
        val params = mutableMapOf(
            "dfid" to auth.dfid,
            "mid" to auth.mid,
            "uuid" to "-",
            "appid" to appid,
            "clientver" to clientver,
            "clienttime" to "${System.currentTimeMillis() / 1000}",
        )
        if (auth.isLoggedIn) {
            params["token"] = auth.token
            params["userid"] = auth.userId
        }
        return params
    }

    private fun androidSignature(params: Map<String, String>, data: String): String {
        val body = params.keys.sorted().joinToString("") { "$it=${params[it] ?: ""}" }
        return "$androidSignKey$body$data$androidSignKey".md5Hex()
    }

    private fun upstreamAndroidSignature(params: Map<String, String>, data: String): String {
        val body = params.keys.sorted().joinToString("") { "$it=${params[it] ?: ""}" }
        return "$upstreamSignSalt$body$data$upstreamSignSalt".md5Hex()
    }

    private fun webSignature(params: Map<String, String>): String {
        val body = params.keys.sorted().joinToString("") { "$it=${params[it] ?: ""}" }
        return "$webSignKey$body$webSignKey".md5Hex()
    }

    private suspend fun getJson(url: String, ua: String): JSONObject =
        getJson(url, ua, emptyMap())

    private suspend fun getJson(url: String, ua: String, headers: Map<String, String>): JSONObject {
        val builder = Request.Builder().url(url).header("User-Agent", ua).get()
        for ((key, value) in headers) builder.header(key, value)
        val text = executeText(builder.build()) ?: throw BeansApiException.Network("network error")
        return parseJson(text) ?: throw BeansApiException.Network("network error")
    }

    private suspend fun getString(url: String, ua: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Referer", "https://www.kugou.com/")
            .get()
            .build()
        return executeText(request) ?: throw BeansApiException.Network("network error")
    }

    private fun searchSignature(params: Map<String, String>): String {
        val body = params
            .filterKeys { it != "signature" }
            .keys
            .sorted()
            .joinToString("") { "$it=${params[it] ?: ""}" }
        return "y9tjae~n)k)vn[8${body}y9tjae~n)k)vn[8".md5Hex()
    }

    // MARK: - 解析工具

    private fun javascriptArray(named: String, html: String): List<JSONObject>? {
        val startRange = html.indexOf("$named = [").takeIf { it >= 0 }
            ?: html.indexOf("$named=[").takeIf { it >= 0 }
            ?: return null
        val openIndex = html.indexOf('[', startRange).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        var endIndex = -1
        var index = openIndex
        while (index < html.length) {
            val char = html[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
            } else if (char == '"') {
                inString = true
            } else if (char == '[') {
                depth += 1
            } else if (char == ']') {
                depth -= 1
                if (depth == 0) {
                    endIndex = index + 1
                    break
                }
            }
            index += 1
        }
        if (endIndex < 0) return null
        val jsonText = html.substring(openIndex, endIndex)
        return try {
            JSONArray(jsonText).objects()
        } catch (e: Exception) {
            null
        }
    }

    private fun regexMatches(pattern: String, text: String): List<List<String>> {
        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            return emptyList()
        }
        return regex.findAll(text).map { match -> match.groupValues.toList() }.toList()
    }

    private fun normalizeURL(value: String): String {
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://")) return "https://" + value.removePrefix("http://")
        return value
    }

    private fun htmlDecode(value: String): String = value
        .replace("&#34;", "\"")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")

    private fun mapCompleteTrack(raw: JSONObject): Song? {
        val normalized = JSONObject()
        for (key in raw.keys()) normalized.put(key, raw.opt(key))
        if (!normalized.has("singername")) {
            val authors = raw.arr("authors")
            if (authors != null) {
                val names = authors.objects().mapNotNull { author ->
                    val name = string(author.opt("author_name") ?: author.opt("name"))
                    name.ifEmpty { null }
                }
                if (names.isNotEmpty()) normalized.put("singername", names.joinToString(" / "))
            }
        }
        normalized.put("songname", raw.opt("SongName") ?: raw.opt("FileName") ?: raw.opt("songname"))
        normalized.put("filename", raw.opt("FileName") ?: raw.opt("SongName") ?: raw.opt("filename"))
        normalized.put("singername", raw.opt("SingerName") ?: raw.opt("singername") ?: normalized.opt("singername"))
        normalized.put("album_name", raw.opt("AlbumName") ?: raw.opt("album_name"))
        normalized.put("hash", raw.opt("FileHash") ?: raw.opt("Hash") ?: raw.opt("hash"))
        normalized.put(
            "mixsongid",
            raw.opt("MixSongID") ?: raw.opt("mixsongid") ?: raw.opt("audio_id")
                ?: raw.opt("audioid") ?: raw.opt("encrypt_id"),
        )
        normalized.put("audio_id", raw.opt("audio_id") ?: raw.opt("audioid") ?: raw.opt("encrypt_id"))
        normalized.put("album_id", raw.opt("AlbumID") ?: raw.opt("album_id"))
        normalized.put(
            "duration",
            raw.opt("Duration") ?: raw.opt("duration") ?: raw.opt("timeLen") ?: raw.opt("timelength"),
        )
        normalized.put(
            "album_sizable_cover",
            raw.opt("Image") ?: raw.opt("ImageUrl") ?: raw.opt("AlbumImg") ?: raw.opt("album_sizable_cover"),
        )
        normalized.put("pay_type", raw.opt("PayType") ?: raw.opt("Privilege") ?: raw.opt("pay_type"))
        normalized.put("feetype", raw.opt("FeeType") ?: raw.opt("feetype"))
        normalized.put("privilege", raw.opt("Privilege") ?: raw.opt("privilege"))
        normalized.put("pay_type_320", raw.opt("PayType320") ?: raw.opt("pay_type_320"))
        normalized.put("pay_type_sq", raw.opt("PayTypeSQ") ?: raw.opt("pay_type_sq"))
        return mapTrack(normalized)
    }

    private fun mapPlaylist(raw: JSONObject): Playlist? {
        val id = int(raw.opt("listid") ?: raw.opt("id") ?: raw.opt("global_collection_id") ?: raw.opt("specialid"))
        if (id <= 0) return null
        val name = string(raw.opt("name") ?: raw.opt("listname") ?: raw.opt("list_name") ?: raw.opt("specialname") ?: raw.opt("title"))
        val cover = string(
            raw.opt("pic") ?: raw.opt("img") ?: raw.opt("cover") ?: raw.opt("sizable_cover") ?: raw.opt("list_pic"),
        ).replace("{size}", "240")
        val count = int(raw.opt("count") ?: raw.opt("song_count") ?: raw.opt("total") ?: raw.opt("file_count") ?: raw.opt("songcount"))
        return Playlist(
            id = id.toLong(),
            name = if (name.isEmpty()) "酷狗歌单" else name,
            coverURL = cover.ifEmpty { null },
            trackCount = count,
            source = SongSource.KUGOU,
        )
    }

    private fun mapTrack(raw: JSONObject): Song? {
        val trans = raw.optMap("trans_param") ?: raw.optMap("transParam") ?: JSONObject()
        val qualityHashes = qualityHashes(raw = raw, trans = trans)
        val hash = string(
            raw.opt("hash") ?: raw.opt("Hash") ?: raw.opt("file_hash") ?: raw.opt("FileHash")
                ?: raw.opt("audio_hash") ?: qualityHashes["exhigh"] ?: qualityHashes["standard"]
                ?: qualityHashes["lossless"],
        )
        val albumAudioId = string(
            raw.opt("album_audio_id") ?: raw.opt("albumAudioId") ?: raw.opt("audio_id")
                ?: raw.opt("audioid") ?: raw.opt("mixsongid") ?: raw.opt("songid") ?: raw.opt("id"),
        )
        val stable = abs((if (hash.isEmpty()) albumAudioId else hash).hashCode()).toLong()
        var title = clean(string(raw.opt("songname") ?: raw.opt("song_name") ?: raw.opt("name") ?: raw.opt("title")))
        var artist = clean(
            string(raw.opt("singername") ?: raw.opt("singer_name") ?: raw.opt("author_name") ?: raw.opt("singer") ?: raw.opt("artist")),
        )
        val filename = clean(string(raw.opt("filename") ?: raw.opt("FileName")))
        if (filename.isNotEmpty()) {
            val parts = filename.split(" - ")
            if (parts.size >= 2) {
                if (artist.isEmpty()) artist = clean(parts[0])
                if (title.isEmpty() || title == filename) {
                    title = clean(parts.drop(1).joinToString(" - "))
                }
            } else if (title.isEmpty()) {
                title = filename
            }
        }
        if (title.isEmpty() || (hash.isEmpty() && albumAudioId.isEmpty())) return null
        val albumName = string(
            raw.opt("album_name") ?: raw.opt("albumname") ?: raw.opt("album")
                ?: (raw.opt("albuminfo") as? JSONObject)?.opt("name"),
        )
        val cover = normalizeURL(
            string(
                raw.opt("pic") ?: raw.opt("img") ?: raw.opt("image") ?: raw.opt("cover")
                    ?: raw.opt("album_sizable_cover") ?: raw.opt("sizable_cover") ?: trans.opt("union_cover"),
            ).replace("{size}", "300"),
        )
        val durRaw = double(
            raw.opt("timelength") ?: raw.opt("time_length") ?: raw.opt("timelen")
                ?: raw.opt("duration") ?: raw.opt("interval"),
        )
        val seconds = if (durRaw > 1000) durRaw / 1000.0 else durRaw
        var artists = artist
        if (artists.isEmpty()) {
            artists = clean(string(raw.opt("h5_author_name") ?: raw.opt("authors")))
        }
        return Song(
            id = stable,
            name = title,
            artists = artists,
            album = albumName,
            coverURL = cover.ifEmpty { null },
            duration = seconds,
            source = SongSource.KUGOU,
            kugouHash = hash,
            kugouAlbumAudioId = albumAudioId,
            kugouAlbumId = string(raw.opt("album_id") ?: raw.opt("albumid") ?: raw.opt("AlbumID") ?: raw.opt("albumId")),
            kugouQualityHashes = qualityHashes.ifEmpty { null },
            fee = kugouVIPFee(raw),
        )
    }

    private fun mergeRankCovers(primary: List<Song>, fallback: List<Song>): List<Song> {
        val byHash = mutableMapOf<String, Song>()
        val byAudioId = mutableMapOf<String, Song>()
        val byTitle = mutableMapOf<String, Song>()
        for (song in fallback) {
            if (song.coverURL == null) continue
            val hash = song.kugouHash?.lowercase()
            if (!hash.isNullOrEmpty()) byHash[hash] = song
            val audioId = song.kugouAlbumAudioId
            if (!audioId.isNullOrEmpty()) byAudioId[audioId] = song
            byTitle[rankCoverMatchKey(song)] = song
        }
        return primary.map { song ->
            if (song.coverURL != null) return@map song
            val fallbackSong: Song?
            val hash = song.kugouHash?.lowercase()
            val audioId = song.kugouAlbumAudioId
            fallbackSong = when {
                !hash.isNullOrEmpty() && byHash.containsKey(hash) -> byHash[hash]
                !audioId.isNullOrEmpty() && byAudioId.containsKey(audioId) -> byAudioId[audioId]
                else -> byTitle[rankCoverMatchKey(song)]
            }
            val coverURL = fallbackSong?.coverURL ?: return@map song
            song.copy(coverURL = coverURL)
        }
    }

    private fun mergeRankSongs(primary: List<Song>, fallback: List<Song>, limit: Int): List<Song> {
        val target = max(limit, 1)
        val result = mutableListOf<Song>()
        val seenIdentity = mutableSetOf<String>()
        val seenContent = mutableSetOf<String>()

        for (song in primary + fallback) {
            val contentKey = rankSongContentKey(song)
            if (!seenIdentity.add(song.identityKey)) continue
            if (contentKey.isNotEmpty() && !seenContent.add(contentKey)) continue
            result.add(song)
            if (result.size >= target) break
        }
        return result
    }

    private fun rankSongContentKey(song: Song): String {
        val hash = song.kugouHash?.trim()?.lowercase()
        if (!hash.isNullOrEmpty()) {
            return "hash:$hash"
        }
        val audioID = song.kugouAlbumAudioId?.trim()
        if (!audioID.isNullOrEmpty()) {
            return "audio:$audioID"
        }
        return "meta:${song.name.lowercase()}|${song.artists.lowercase()}|${song.album.lowercase()}|${song.duration.toInt()}"
    }

    private fun rankCoverMatchKey(song: Song): String = "${song.name}|${song.artists}".lowercase()

    private fun qualityHashes(raw: JSONObject, trans: JSONObject): Map<String, String> {
        val values = listOf(
            "standard" to string(
                raw.opt("128hash") ?: raw.opt("hash") ?: raw.opt("Hash") ?: raw.opt("file_hash")
                    ?: raw.opt("FileHash") ?: trans.opt("ogg_128_hash"),
            ),
            "exhigh" to string(
                raw.opt("320hash") ?: raw.opt("HQFileHash") ?: trans.opt("ogg_320_hash") ?: raw.opt("hash")
                    ?: raw.opt("Hash") ?: raw.opt("file_hash") ?: raw.opt("FileHash"),
            ),
            "lossless" to string(
                raw.opt("sqhash") ?: raw.opt("SQFileHash") ?: raw.opt("flac_hash") ?: raw.opt("hash")
                    ?: raw.opt("Hash") ?: raw.opt("file_hash") ?: raw.opt("FileHash"),
            ),
            "hires" to string(
                raw.opt("hrhash") ?: raw.opt("high_hash") ?: raw.opt("sqhash") ?: raw.opt("SQFileHash")
                    ?: raw.opt("hash") ?: raw.opt("Hash") ?: raw.opt("file_hash") ?: raw.opt("FileHash"),
            ),
        )
        val result = LinkedHashMap<String, String>()
        for ((key, value) in values) {
            if (value.isNotEmpty()) result[key] = value
        }
        return result
    }

    private fun qualityHashCandidates(
        primary: String?,
        qualityHashes: Map<String, String>?,
        quality: BeansAudioQuality? = null,
    ): List<String> {
        val requested = quality ?: currentQuality()
        val order = when (requested) {
            BeansAudioQuality.HIRES -> listOf("hires", "lossless", "exhigh", "standard")
            BeansAudioQuality.LOSSLESS -> listOf("lossless", "exhigh", "standard")
            BeansAudioQuality.EXHIGH, BeansAudioQuality.HIGHER -> listOf("exhigh", "standard")
            BeansAudioQuality.STANDARD -> listOf("standard")
        }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        fun append(value: String?) {
            val hash = (value ?: "").trim().uppercase()
            if (hash.isEmpty() || !seen.add(hash)) return
            result.add(hash)
        }
        for (key in order) append(qualityHashes?.get(key))
        append(primary)
        for (key in listOf("hires", "lossless", "exhigh", "standard")) append(qualityHashes?.get(key))
        return result
    }

    private fun vipTypeCandidates(vipType: Int, loggedIn: Boolean): List<Int> {
        val values = if (vipType > 0) listOf(vipType, 6, 1, 0) else if (loggedIn) listOf(1, 6, 0) else listOf(0)
        val seen = mutableSetOf<Int>()
        return values.filter { seen.add(it) }
    }

    private fun clean(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("\\.(mp3|flac|m4a|aac|ogg|wav)$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * 酷狗各接口的付费标记散落在多种字段（部分还包在 authors/privilege 等嵌套结构里），
     * 这里递归收集所有可能的标记（对应 iOS `kugouVIPFee`）。
     */
    private fun kugouVIPFee(raw: JSONObject): Int {
        val explicitKeys = setOf(
            "fee", "feetype", "fee_type", "pay_type", "paytype", "paytype320",
            "pay_type_320", "pay_type_sq", "media_pay_type", "needpay", "need_pay",
        )
        val boolKeys = setOf(
            "vip", "isvip", "is_vip", "onlyvipplayable", "only_vip_playable",
            "viprequired", "vip_required", "needvip", "need_vip",
        )
        var explicit = 0
        var privilege = 0
        var flag = false
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    for (key in value.keys()) {
                        val child = value.opt(key)
                        val normalized = key.replace("_", "").replace("-", "").lowercase()
                        val lower = key.lowercase()
                        if (explicitKeys.contains(lower) || explicitKeys.contains(normalized)) {
                            explicit = max(explicit, int(child))
                        }
                        if (lower == "privilege" || lower == "media_privilege" ||
                            lower == "320privilege" || lower == "sqprivilege"
                        ) {
                            privilege = max(privilege, int(child))
                        }
                        if (boolKeys.contains(lower) || boolKeys.contains(normalized)) {
                            if (child is Boolean && child) flag = true
                            if (int(child) > 0) flag = true
                            val text = string(child).lowercase()
                            if (text == "true" || text.contains("vip") || text.contains("会员")) flag = true
                        }
                        walk(child)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i))
                }
            }
        }
        walk(raw)
        if (explicit > 0) return explicit
        if (privilege >= 9) return 1
        if (flag) return 1
        return 0
    }

    private fun parseComments(json: JSONObject, page: Int, songName: String): KugouCommentPage {
        val rows = deepArrays(
            json,
            listOf("commentlist", "comments", "list", "comment", "hot_comment", "hot_comments"),
        )
        val seen = mutableSetOf<Long>()
        val comments = rows.mapNotNull { raw ->
            val rawID = string(raw.opt("commentid") ?: raw.opt("comment_id") ?: raw.opt("id") ?: raw.opt("cid"))
            val content = clean(
                string(raw.opt("content") ?: raw.opt("comment_content") ?: raw.opt("commentContent") ?: raw.opt("text")),
            )
            if (content.isEmpty()) return@mapNotNull null
            val nickname = clean(
                string(raw.opt("nick") ?: raw.opt("nickname") ?: raw.opt("username") ?: raw.opt("user_name") ?: raw.opt("author")),
            )
            val avatar = string(
                raw.opt("avatarurl") ?: raw.opt("avatar_url") ?: raw.opt("avatar")
                    ?: raw.opt("user_pic") ?: raw.opt("headurl"),
            )
            val timestamp = double(
                raw.opt("addtime") ?: raw.opt("add_time") ?: raw.opt("time")
                    ?: raw.opt("timestamp") ?: raw.opt("created_at"),
            )
            val seconds = if (timestamp > 10_000_000_000) timestamp / 1000 else timestamp
            // iOS 用 `hashValue` 生成本地去重键（跨进程不保证稳定），这里对应 Kotlin 的 hashCode。
            val id = abs(if (rawID.isEmpty()) content.hashCode() else rawID.hashCode()).toLong()
            if (!seen.add(id)) return@mapNotNull null
            SongComment(
                id = id,
                content = content,
                nickname = if (nickname.isEmpty()) "酷狗用户" else nickname,
                avatarURL = avatar.ifEmpty { null },
                time = if (seconds > 0) (seconds * 1000).toLong() else System.currentTimeMillis(),
                likedCount = int(raw.opt("praisenum") ?: raw.opt("like_count") ?: raw.opt("liked_count") ?: raw.opt("likes")),
                isHot = page == 1,
            )
        }
        val total = deepInt(json, listOf("total", "commenttotal", "comment_total", "count"))
        Log.d(LOG_TAG, "酷狗评论：mixsongid=$songName page=$page 返回 ${comments.size} 条")
        return KugouCommentPage(comments = comments, total = total)
    }

    private fun deepString(obj: JSONObject, names: List<String>): String {
        val value = deepValue(obj, names)
        if (value is JSONArray) {
            return if (value.length() > 0) string(value.opt(0)) else ""
        }
        return string(value)
    }

    private fun deepInt(obj: JSONObject, names: List<String>): Int = int(deepValue(obj, names))

    private fun deepArrays(obj: JSONObject, names: List<String>): List<JSONObject> {
        var best: List<JSONObject> = emptyList()
        fun walk(value: Any?) {
            when (value) {
                is JSONArray -> {
                    val array = value.objects()
                    if (array.size > best.size) {
                        best = array
                    } else {
                        for (i in 0 until value.length()) walk(value.opt(i))
                    }
                }
                is JSONObject -> {
                    var matched = false
                    for (key in value.keys()) {
                        if (names.any { it.equals(key, ignoreCase = true) }) {
                            matched = true
                            walk(value.opt(key))
                        }
                    }
                    if (!matched && best.isEmpty()) {
                        for (key in value.keys()) walk(value.opt(key))
                    }
                }
            }
        }
        walk(obj)
        return best
    }

    private fun deepValue(obj: JSONObject, names: List<String>): Any? {
        val wanted = names.map { it.lowercase() }.toSet()
        var found = false
        fun walk(value: Any?): Any? {
            when (value) {
                is JSONObject -> {
                    // 与 Swift 一致：命中键就返回该键的值（即使值是 null），不再继续深入。
                    for (key in value.keys()) {
                        if (wanted.contains(key.lowercase())) {
                            found = true
                            return value.opt(key)
                        }
                    }
                    for (key in value.keys()) {
                        val child = walk(value.opt(key))
                        if (found || child != null) return child
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val child = walk(value.opt(i))
                        if (found || child != null) return child
                    }
                }
            }
            return null
        }
        return walk(obj)
    }

    private fun string(value: Any?): String {
        val text = when (value) {
            is String -> value
            is Number -> value.toString()
            else -> ""
        }
        return text.trim(*swiftWhitespace)
    }

    private fun int(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        else -> 0
    }

    private fun double(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    /** 对应 Swift `addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)`。 */
    private fun urlEncode(text: String): String = Http.formEncode(text).replace("+", "%20")

    /**
     * 拼装查询串（对应 Swift `URLComponents.queryItems`）：键值均按
     * `.urlQueryAllowed` 百分号编码，空值保留 `key=`。
     */
    private fun buildUrl(base: String, queryItems: List<Pair<String, String>>): String? {
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        if (queryItems.isEmpty()) return base
        val query = queryItems.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
        return "$base?$query"
    }

    private fun parseJson(text: String): JSONObject? {
        val trimmed = text.removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return null
        return try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            null
        }
    }

    /** 对应 `JSONSerialization.data(withJSONObject:)` 的紧凑 JSON 文本（签名与请求体共用）。 */
    private fun jsonText(map: Map<String, Any?>): String = jsonObject(map).toString()

    private fun jsonObject(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) obj.put(key.toString(), jsonValue(value))
        return obj
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> jsonObject(value)
        is Iterable<*> -> {
            val array = JSONArray()
            for (item in value) array.put(jsonValue(item))
            array
        }
        is Array<*> -> {
            val array = JSONArray()
            for (item in value) array.put(jsonValue(item))
            array
        }
        else -> value
    }

    /** 公开给本模块其它酷狗逻辑复用的 MD5 十六进制摘要（对应 Swift `String.kgMD5Hex`）。 */
    fun md5Digest(value: String): String = value.md5Hex()

    private fun currentQuality(): BeansAudioQuality = try {
        BeansAudioQuality.fromRaw(SettingsStore.audioQuality.value)
    } catch (e: Exception) {
        BeansAudioQuality.HIRES
    }

    private fun randomLower(length: Int): String? {
        val chars = "1234567890abcdefghijklmnopqrstuvwxyz"
        return buildString(length) {
            repeat(length) { append(chars[Random.nextInt(chars.length)]) }
        }
    }

    // MARK: - 设备注册加解密

    /** AES-128-CBC / PKCS7，密钥与 IV 来自密码的 MD5 十六进制串前后各 16 个字符。 */
    private fun aesCBCEncrypt(data: ByteArray, password: String): ByteArray? {
        val digest = password.toByteArray(Charsets.UTF_8).md5Hex()
        return crypt(data, Cipher.ENCRYPT_MODE, digest.take(16), digest.takeLast(16))
    }

    private fun aesCBCDecrypt(data: ByteArray, password: String): ByteArray? {
        val digest = password.toByteArray(Charsets.UTF_8).md5Hex()
        return crypt(data, Cipher.DECRYPT_MODE, digest.take(16), digest.takeLast(16))
    }

    private fun crypt(data: ByteArray, mode: Int, key: String, iv: String): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            mode,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        cipher.doFinal(data)
    } catch (e: Exception) {
        null
    }

    /** RSC PKCS#1 v1.5 加密（对应 `SecKeyCreateEncryptedData(.rsaEncryptionPKCS1)`）。 */
    private fun rsaEncryptPKCS1(data: ByteArray, publicKeyBase64: String): ByteArray? = try {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.doFinal(data)
    } catch (e: Exception) {
        null
    }
}

private fun ByteArray.toHexString(): String {
    val sb = StringBuilder(size * 2)
    for (byte in this) sb.append("%02x".format(byte.toInt() and 0xFF))
    return sb.toString()
}

private fun ByteArray.md5Hex(): String =
    MessageDigest.getInstance("MD5").digest(this).toHexString()

private fun String.md5Hex(): String = toByteArray(Charsets.UTF_8).md5Hex()

/** 对应 Swift `CharacterSet.whitespacesAndNewlines`。 */
private val swiftWhitespace: CharArray = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u0085', '\u00A0',
    '\u1680', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006',
    '\u2007', '\u2008', '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
)

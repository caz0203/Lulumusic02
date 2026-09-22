package com.lulu.music.data.api

import android.util.Log
import com.lulu.music.BeansApplication
import com.lulu.music.data.auth.QQMusicAuth
import com.lulu.music.data.model.Album
import com.lulu.music.data.model.Artist
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.LyricLine
import com.lulu.music.data.model.LyricParser
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.QQTopInfo
import com.lulu.music.data.model.QrcParser
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongComment
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.net.BeansApiException
import com.lulu.music.data.net.Http
import com.lulu.music.data.net.QrcCipher
import com.lulu.music.data.net.arr
import com.lulu.music.data.net.objects
import com.lulu.music.data.net.objAt
import com.lulu.music.data.net.optMap
import com.lulu.music.data.net.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** QQ 音乐搜索类型（musicu search_type：0 单曲 / 1 歌手 / 2 专辑 / 3 歌单 / 4 MV / 7 歌词 / 8 用户） */
internal enum class QQSearchType(val rawValue: Int) {
    SONG(0),
    ARTIST(1),
    ALBUM(2),
}

/** QQ 音乐评论分页结果（热评只在第一页返回）。 */
data class QQCommentPage(
    val comments: List<SongComment>,
    val total: Int,
)

/** 播放地址解析结果：QQ 实际命中的 BR，供播放器在真正失败时继续向下切换。 */
data class SongURLResult(
    val url: String,
    val br: String,
    val attemptedBRs: List<String>,
)

/// QQ 音乐接口（搜索 / 播放地址 / 歌词 / 热搜）
/// 参考 wp_MusicApi（https://github.com/GitHub-ZC/wp_MusicApi）逆向结论：
/// - 歌曲搜索改用 client_search_cp（t=0），该接口对家庭/移动/数据中心网络均可用；
///   歌手/专辑搜索使用 musicu.fcg POST JSON（search_type 1/2），专辑空结果时自动用 client_search_cp t=8 兜底。
/// - vkey 播放地址经 musicu.fcg 获取，VIP 歌曲返回空；部分数据中心 IP 会被风控返回空，家庭网络正常。
object QQMusicApi {

    /// QQ「我的喜欢」不是“创建歌单”列表中的普通歌单，使用稳定占位 ID 进入专用加载流程。
    const val QQ_LIKED_PLAYLIST_ID = -201L
    private const val QQ_LIKED_COVER_URL = "https://y.gtimg.cn/mediastyle/global/img/cover_like.png"
    private const val GUID_PREFS = "beans_prefs"
    private const val GUID_KEY = "beans.qqmusic.guid.v1"
    private const val LOG_TAG = "QQMusicApi"

    private const val BASE = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val SEARCH_BASE = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp"
    private const val UA_IPHONE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 QQMusic/9.0.5"
    private const val UA_MSIE = "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)"
    private const val UA_FIREFOX = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:80.0) Gecko/20100101 Firefox/80.0"
    private const val DEFAULT_COOKIE = "uin=0; qqmusic_fromtag=66"

    /** 名称带下划线避免与注入参数 cookie 冲突（对应 Swift 的 `_ cookie`）。 */
    private val _longTimeoutClient: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    }
    private val _shortTimeoutClient: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    }

    // MARK: - 基础请求

    /// 与 Swift `URLComponents.query` 一致的表单体编码：保留字母数字与 `-._~`，
    /// 空格输出 `+`（服务端两种写法等价），其余字符百分号编码并使用大写十六进制。
    private fun queryFormBody(fields: Map<String, Any?>): String =
        fields.entries.joinToString("&") { (key, value) ->
            "${queryEncode(key)}=${queryEncode(valueToString(value))}"
        }

    private fun valueToString(value: Any?): String =
        if (value == null) "" else value.toString()

    private fun queryEncode(value: String): String {
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            when {
                c.isLetterOrDigit() && c.code < 128 -> sb.append(c)
                c == '-' || c == '.' || c == '_' || c == '~' -> sb.append(c)
                c == ' ' -> sb.append('+')
                else -> sb.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    /** 对应 `addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)`。 */
    private fun urlQueryAllowed(value: String): String = Http.formEncode(value).replace("+", "%20")

    /** musicu.fcg 统一入口：POST JSON body（与 wp_MusicApi 一致）；登录后附加 QQ Cookie。
     *  musicu 偶发挂起/风控，搜索保持短超时（timeout<=6 使用 6 秒客户端），歌单同步允许更长时间完成回退请求。 */
    private suspend fun musicu(
        payload: JSONObject,
        cookie: String = "",
        timeout: Double = 6.0,
    ): JSONObject {
        val headers = mutableMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to UA_MSIE,
            "Referer" to "https://y.qq.com/",
        )
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        val client = if (timeout <= 6.0) _shortTimeoutClient else _longTimeoutClient
        return postJsonWith(client, BASE, payload.toString(), headers)
    }

    private suspend fun postJsonWith(
        client: OkHttpClient,
        url: String,
        json: String,
        headers: Map<String, String>,
    ): JSONObject {
        val body = json.toRequestBody("application/json".toMediaType())
        val builder = Request.Builder().url(url).post(body)
        for ((k, v) in headers) if (v.isNotEmpty()) builder.header(k, v)
        val text = try {
            httpCall(client, builder)
        } catch (e: BeansApiException) {
            throw e
        } catch (e: IOException) {
            throw BeansApiException.Network(e.message ?: "network error")
        }
        return parseJsonOrThrow(text)
    }

    private suspend fun getJson(
        urlString: String,
        referer: String = "https://y.qq.com/",
        cookie: String = "",
    ): JSONObject {
        val headers = mapOf(
            "User-Agent" to UA_IPHONE,
            "Referer" to referer,
            "Cookie" to if (cookie.isEmpty()) DEFAULT_COOKIE else cookie,
        )
        return getJsonWith(urlString, headers)
    }

    private suspend fun httpCall(client: OkHttpClient, builder: Request.Builder): String =
        withContext(Dispatchers.IO) {
            // 测试专用的离线闸门（默认关闭，见 Http.offlineMode）：绝不发出真实请求。
            Http.guardNetwork()
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw BeansApiException.HttpStatus(response.code, body.take(120))
                }
                body
            }
        }

    private fun parseJsonOrThrow(text: String): JSONObject =
        parseJSON(text) ?: throw BeansApiException.Decoding(text.take(120))

    /// 表单 POST（fcg 老接口统一走这里，如 H5 评论接口）
    private suspend fun postFormBody(
        urlString: String,
        body: Map<String, Any?>,
        referer: String = "https://y.qq.com/",
        cookie: String = "",
    ): JSONObject {
        val headers = mapOf(
            "User-Agent" to UA_IPHONE,
            "Referer" to referer,
            "Cookie" to if (cookie.isEmpty()) DEFAULT_COOKIE else cookie,
        )
        val text = Http.postFormText(urlString, queryFormBody(body), headers)
        return parseJSON(text) ?: throw BeansApiException.Decoding(text.take(120))
    }

    /** 兼容纯 JSON 与 JSONP（`callback({...})`）两种响应；QQ 部分老接口会前置 `while(1);` 防护前缀 */
    private fun parseJSON(text: String): JSONObject? {
        val trimmed = text.removePrefix("\uFEFF").trim()
        try {
            return JSONObject(trimmed)
        } catch (_: Exception) {
            // 继续尝试 JSONP / while(1); 前缀
        }
        var body = trimmed
        if (body.startsWith("while(1);")) {
            body = body.substring("while(1);".length)
        }
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(body.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    private fun photoURL(mid: String?, size: String = "300x300"): String? {
        if (mid.isNullOrEmpty()) return null
        return "https://y.gtimg.cn/music/photo_new/T002R${size}M000$mid.jpg"
    }

    /// 歌手头像（T001 歌手模板；T002 专辑模板对歌手 mid 会 404，搜索歌手必须用 T001）
    private fun singerPhotoURL(mid: String?, size: String = "300x300"): String? {
        if (mid.isNullOrEmpty()) return null
        return "https://y.gtimg.cn/music/photo_new/T001R${size}M000$mid.jpg"
    }

    private fun normalizedQQImageURL(raw: Any?): String? {
        if (raw !is String) return null
        var value = raw.trim()
        if (value.isEmpty()) return null
        // org.json 已经解转义 `\/`，这里保留与 Swift 相同的防御性替换
        value = value.replace("\\/", "/")
        value = decodeJSONStringValue(value)
        if (value.startsWith("data:image")) return null
        value = when {
            value.startsWith("http://") -> "https://" + value.substring(7)
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://y.gtimg.cn$value"
            !value.startsWith("https://") -> "https://y.gtimg.cn/" + value.trim('/')
            else -> value
        }
        return normalizeURLString(value)
    }

    /// 与 Swift `URL(string:)` 对齐：合法 URL 原样返回，否则按 urlQueryAllowed 百分号编码后再尝试。
    private fun normalizeURLString(value: String): String? {
        if (value.toHttpUrlOrNull() != null) return value
        val encoded = urlQueryAllowed(value)
        return if (encoded.toHttpUrlOrNull() != null) encoded else null
    }

    private fun musicuSearchPayload(keyword: String, limit: Int, type: QQSearchType): JSONObject = JSONObject()
        .put(
            "comm",
            JSONObject()
                .put("ct", 19)
                .put("cv", 1859)
                .put("uin", "0")
                .put("format", "json"),
        )
        .put(
            "req_1",
            JSONObject()
                .put("module", "music.search.SearchCgiService")
                .put("method", "DoSearchForQQMusicDesktop")
                .put(
                    "param",
                    JSONObject()
                        .put("query", keyword)
                        .put("num_per_page", limit)
                        .put("page_num", 1)
                        .put("search_type", type.rawValue)
                        .put("grp", 1),
                ),
        )

    /// search_for_qq_cp 搜索 URL（未登录可用；t：0 单曲 / 8 专辑）
    private fun clientSearchURL(keyword: String, limit: Int, type: Int, page: Int = 1): String? =
        SEARCH_BASE.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("w", keyword)
            ?.addQueryParameter("n", "$limit")
            ?.addQueryParameter("p", "${maxOf(page, 1)}")
            ?.addQueryParameter("t", "$type")
            ?.build()
            ?.toString()

    // MARK: - 搜索

    /// 搜索歌曲（client_search_cp，本机与手机网络均可）
    suspend fun searchSongs(keyword: String, limit: Int = 30, offset: Int = 0): List<Song> {
        val pageSize = maxOf(limit, 1)
        val page = maxOf(offset, 0) / pageSize + 1
        val url = clientSearchURL(keyword = keyword, limit = pageSize, type = 0, page = page)
            ?: throw BeansApiException.Unknown("搜索地址无效")
        val json = getJson(url, referer = "https://y.qq.com/portal/player.html")
        val data = json.optMap("data")
        val songNode = data?.optMap("song")
        val list = songNode?.arr("list").orEmptyObjects()
        val songs = mutableListOf<Song>()
        for (item in list) {
            val songid = longOrNull(item.opt("songid")) ?: continue
            val mid = item.opt("songmid") as? String ?: continue
            val singer = item.arr("singer").orEmptyObjects()
            val artists = singer.mapNotNull { it.opt("name") as? String }.joinToString(" / ")
            val albumMid = item.opt("albummid") as? String ?: item.opt("albumMID") as? String
            val interval = intOrNull(item.opt("interval")) ?: 0
            val pay = item.optMap("pay")
            val fee = intOrNull(item.opt("fee"))
                ?: intOrNull(pay?.opt("pay_play"))
                ?: intOrNull(pay?.opt("payplay"))
                ?: 0
            val file = item.optMap("file")
            val mediaMid = file?.opt("media_mid") as? String
                ?: item.opt("strMediaMid") as? String
                ?: item.opt("media_mid") as? String
            songs.add(
                Song(
                    id = songid,
                    name = item.opt("songname") as? String ?: "",
                    artists = artists,
                    album = item.opt("albumname") as? String ?: item.opt("albumName") as? String ?: "",
                    coverURL = photoURL(albumMid),
                    duration = interval.toDouble(),
                    source = SongSource.QQ,
                    qqMid = mid,
                    qqMediaMid = mediaMid,
                    fee = fee,
                ),
            )
        }
        return songs
    }

    /// 搜索歌手（musicu search_type=1 为主，字段 singerName/singerMID；musicu 被风控返回 2001 时用 smartbox_new 兜底）
    suspend fun searchArtists(keyword: String, limit: Int = 30): List<Artist> {
        val json = runCatching { musicu(musicuSearchPayload(keyword, limit, QQSearchType.ARTIST)) }.getOrNull()
        if (json != null) {
            val list = nestedArray(json, listOf("req_1", "data", "body", "singer", "list"))
            val artists = mutableListOf<Artist>()
            for (item in list) {
                val name = item.opt("singerName") as? String
                    ?: (item.opt("name") as? String ?: (item.opt("title") as? String ?: ""))
                if (name.isEmpty()) continue
                val mid = item.opt("singerMID") as? String ?: (item.opt("mid") as? String)
                val numericID = intOrNull(item.opt("singerID")) ?: (intOrNull(item.opt("id")) ?: 0)
                artists.add(
                    Artist(
                        id = mid ?: "qq-$numericID-$name",
                        name = name,
                        coverURL = singerPhotoURL(mid),
                        source = SongSource.QQ,
                    ),
                )
            }
            if (artists.isNotEmpty()) return artists
        }
        // 兜底：smartbox_new.fcg 联想接口（含歌手/专辑，数据中心与移动网络均可用）
        val encoded = urlQueryAllowed(keyword)
        val smartboxURL =
            "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?format=json&s_from=pc_header&type=1&key=$encoded"
        val smartboxJson = runCatching { getJson(smartboxURL) }.getOrNull()
        if (smartboxJson != null) {
            val singer = smartboxJson.optMap("data")?.optMap("singer")
            val list = singer?.arr("itemlist").orEmptyObjects()
            val artists = mutableListOf<Artist>()
            for (item in list.take(limit)) {
                val name = item.opt("name") as? String ?: ""
                if (name.isEmpty()) continue
                val mid = item.opt("mid") as? String
                val numericID = item.opt("id") as? String ?: ""
                artists.add(
                    Artist(
                        id = mid ?: "qq-$numericID-$name",
                        name = name,
                        coverURL = normalizedQQImageURL(item.opt("pic")) ?: singerPhotoURL(mid),
                        source = SongSource.QQ,
                    ),
                )
            }
            if (artists.isNotEmpty()) return artists
        }
        // 兜底 2：歌曲搜索结果里的歌手名去重（保证关键词搜索始终能出歌手）
        val songs = runCatching { searchSongs(keyword = keyword, limit = 40) }.getOrNull()
        if (songs != null) {
            val seen = mutableSetOf<String>()
            val artists = mutableListOf<Artist>()
            for (song in songs) {
                for (part in song.artists.split(" / ")) {
                    val name = part.trim()
                    if (name.isEmpty() || seen.contains(name)) continue
                    seen.add(name)
                    artists.add(Artist(id = "qq-name-$name", name = name, coverURL = null, source = SongSource.QQ))
                    if (artists.size >= limit) break
                }
                if (artists.size >= limit) break
            }
            if (artists.isNotEmpty()) return artists
        }
        return emptyList()
    }

    /// 搜索专辑（client_search_cp t=8 为主，与歌曲搜索同源、数据中心/移动网络均可用；musicu search_type=2 常被风控返回 2001 作为次选）
    suspend fun searchAlbums(keyword: String, limit: Int = 30): List<Album> {
        val url = clientSearchURL(keyword = keyword, limit = limit, type = 8)
        if (url != null) {
            val json = runCatching { getJson(url, referer = "https://y.qq.com/portal/player.html") }.getOrNull()
            if (json != null) {
                val data = json.optMap("data")
                val album = data?.optMap("album")
                val list = album?.arr("list").orEmptyObjects()
                if (list.isNotEmpty()) {
                    return parseAlbumItems(list)
                }
            }
        }
        val json = runCatching { musicu(musicuSearchPayload(keyword, limit, QQSearchType.ALBUM)) }.getOrNull()
        if (json != null) {
            val list = nestedArray(json, listOf("req_1", "data", "body", "album", "list"))
            if (list.isNotEmpty()) {
                return parseAlbumItems(list)
            }
        }
        return emptyList()
    }

    private fun parseAlbumItems(items: List<JSONObject>): List<Album> {
        val albums = mutableListOf<Album>()
        for (item in items) {
            val name = item.opt("name") as? String
                ?: (item.opt("albumname") as? String ?: item.opt("albumName") as? String ?: "")
            if (name.isEmpty()) continue
            val mid = item.opt("mid") as? String
                ?: (item.opt("albummid") as? String ?: item.opt("albumMID") as? String)
            val singer = item.arr("singer").orEmptyObjects()
            var artistName = singer.mapNotNull { it.opt("name") as? String }.joinToString(" / ")
            if (artistName.isEmpty()) artistName = item.opt("singerName") as? String ?: ""
            val numericID = intOrNull(item.opt("id")) ?: 0
            albums.add(
                Album(
                    id = mid ?: "qq-album-$numericID-$name",
                    name = name,
                    artistName = artistName,
                    coverURL = photoURL(mid),
                    source = SongSource.QQ,
                    trackCount = intOrNull(item.opt("total")),
                ),
            )
        }
        return albums
    }

    /// QQ 音乐热搜词
    suspend fun hotKeys(limit: Int = 10): List<String> {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/gethotkey.fcg?format=json&inCharset=utf8&outCharset=utf-8"
        val json = getJson(url)
        val data = json.optMap("data")
        val hots = data?.arr("hotkey").orEmptyObjects()
        return hots.mapNotNull { it.opt("k") as? String }.take(limit)
    }

    // MARK: - 歌单管理（创建 / 删除 / 我喜欢）

    /// 创建歌单（create_playlist.fcg，需登录；code 0 成功 / 21 重名 / 1 未登录）
    /// URL 与表单同时携带 format=json/outCharset，兼容 while(1); 与 JSONP 响应，code 支持 Int/String 两种形态
    suspend fun createPlaylist(name: String): Boolean {
        val qqAuth = QQMusicAuth
        if (!qqAuth.isLoggedIn) return false
        val gtk = qqAuth.gtk
        val json = postFormBody(
            urlString = "https://c.y.qq.com/splcloud/fcgi-bin/create_playlist.fcg?g_tk=$gtk&format=json&inCharset=utf8&outCharset=utf-8",
            body = linkedMapOf(
                "loginUin" to qqAuth.rawUin,
                "hostUin" to 0,
                "format" to "json",
                "inCharset" to "utf8",
                "outCharset" to "utf-8",
                "notice" to 0,
                "platform" to "yqq",
                "needNewCode" to 0,
                "g_tk" to gtk,
                "uin" to qqAuth.rawUin,
                "name" to name,
                "show" to 1,
                "formsender" to 1,
                "utf8" to 1,
                "qzreferrer" to "https://y.qq.com/portal/profile.html#sub=other&tab=create&",
            ),
            cookie = qqAuth.cookieHeader,
        )
        val code = extractCode(json) ?: -1
        return code == 0
    }

    /// 宽松提取接口 code（兼容 Int / String / "code":"0" 等形态）
    private fun extractCode(json: JSONObject): Int? {
        intOrNull(json.opt("code"))?.let { return it }
        intOrNull(json.opt("ret"))?.let { return it }
        return null
    }

    /// 删除歌单（fcg_fav_modsongdir.fcg，需登录；返回 JSONP，parseJSON 自动剥离）
    suspend fun deletePlaylist(dirid: Int): Boolean {
        val qqAuth = QQMusicAuth
        if (!qqAuth.isLoggedIn) return false
        val gtk = qqAuth.gtk
        val json = postFormBody(
            urlString = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_fav_modsongdir.fcg?g_tk=$gtk",
            body = linkedMapOf(
                "loginUin" to qqAuth.rawUin,
                "hostUin" to 0,
                "format" to "fs",
                "inCharset" to "GB2312",
                "outCharset" to "gb2312",
                "notice" to 0,
                "platform" to "yqq",
                "needNewCode" to 0,
                "g_tk" to gtk,
                "uin" to qqAuth.rawUin,
                "delnum" to 1,
                "deldirids" to dirid,
                "forcedel" to 1,
                "formsender" to 1,
                "source" to 103,
            ),
            cookie = qqAuth.cookieHeader,
        )
        val code = intOrNull(json.opt("code")) ?: -1
        return code == 0
    }

    /// 我喜欢（红心）歌单歌曲列表（dirid=201 解析真实歌单 ID，再拉歌单详情）
    /// QQ“我的喜欢”歌曲列表。limit <= 0 表示持续分页直到接口没有更多歌曲。
    suspend fun favoriteSongs(limit: Int = 0): List<Song> {
        val qqAuth = QQMusicAuth
        val pageSize = 300
        val targetLimit = if (limit > 0) limit else Int.MAX_VALUE
        if (!qqAuth.isLoggedIn) {
            logError("QQ 我的喜欢终止：当前未登录")
            return emptyList()
        }
        val cookieCandidates = listOf(qqAuth.playlistCookieHeader, qqAuth.cookieHeader)
            .filter { it.isNotEmpty() }
            .distinct()
        val identityCandidates = qqAuth.playlistIdentityCandidates

        // 保留旧版已验证的主路径：201 接口返回真实 map 后，详情请求必须使用
        // loginUin=0 + 完整 Cookie。部分微信登录态换成 wxuin 或裁剪 Cookie 后会返回空。
        val legacyFavURL =
            "https://c.y.qq.com/splcloud/fcgi-bin/fcg_musiclist_getmyfav.fcg?dirid=201&dirinfo=1&g_tk=${qqAuth.gtk}&format=json&utf8=1"
        val favJson = runCatching {
            getJson(legacyFavURL, referer = "https://y.qq.com/n/yqq/playlist", cookie = qqAuth.cookieHeader)
        }.getOrNull()
        val mapid = favJson?.let { likedMapID(it) }
        if (mapid != null && mapid > 0L) {
            val detailURL =
                "https://c.y.qq.com/qzone/fcgi-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$mapid&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
            val detailJson = runCatching {
                getJson(detailURL, referer = "https://y.qq.com/", cookie = qqAuth.cookieHeader)
            }.getOrNull()
            val cdlist = detailJson?.arr("cdlist")
            val rawSongs = cdlist?.objAt(0)?.arr("songlist")
            if (cdlist != null && rawSongs != null) {
                val songs = rawSongs.objects().take(targetLimit)
                    .mapNotNull { song(unwrapQQSong(it)) }
                if (songs.isNotEmpty() && (limit > 0 || rawSongs.length() < pageSize)) return songs
            }
        }

        // 官方“我的喜欢”详情接口。它不依赖普通歌单的 disstid，
        // 而是用 dirid=201 返回专属收藏夹内容。
        val officialPayload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 24)
                    .put("cv", 0)
                    .put("uin", qqAuth.playlistUin)
                    .put("g_tk", qqAuth.gtk)
                    .put("platform", "yqq"),
            )
            .put(
                "req_1",
                JSONObject()
                    .put("module", "music.srfDissInfo.DissInfo")
                    .put("method", "CgiGetDiss")
                    .put(
                        "param",
                        JSONObject()
                            .put("new_format", 1)
                            .put("disstid", 201)
                            .put("dirid", 201)
                            .put("song_begin", 0)
                            .put("song_num", pageSize)
                            .put("enc_host_uin", qqAuth.playlistUin)
                            .put("onlysonglist", 0)
                            .put("userinfo", 1),
                    ),
            )
        for (cookie in cookieCandidates) {
            val allSongs = mutableListOf<Song>()
            val seen = mutableSetOf<String>()
            var begin = 0
            while (allSongs.size < targetLimit) {
                val pagePayload = JSONObject(officialPayload.toString())
                pagePayload.optMap("req_1")?.let { req ->
                    req.optMap("param")?.let { param ->
                        param.put("song_begin", begin)
                        req.put("param", param)
                    }
                    pagePayload.put("req_1", req)
                }
                val json = runCatching { musicu(pagePayload, cookie = cookie, timeout = 20.0) }.getOrNull() ?: break
                val rawPage = favoriteSongArray(json)
                val pageSongs = rawPage.mapNotNull { song(unwrapQQSong(it)) }
                val newSongs = pageSongs.filter { seen.add(it.identityKey) }
                allSongs.addAll(newSongs)
                if (rawPage.size < pageSize || newSongs.isEmpty()) break
                begin += rawPage.size
            }
            if (allSongs.isNotEmpty()) {
                return if (limit > 0) allSongs.take(limit) else allSongs
            }
        }

        // 201 收藏夹接口在不同登录态下有两种返回形式：
        // 有的直接返回 songlist，有的只返回 map/歌单 ID。必须先消费直接返回的歌曲，
        // 否则微信登录时会因为拿不到普通歌单 ID 而显示空列表。
        for (identity in identityCandidates) {
            val favURL =
                "https://c.y.qq.com/splcloud/fcgi-bin/fcg_musiclist_getmyfav.fcg?dirid=201&dirinfo=1&uin=$identity&loginUin=$identity&hostUin=0&g_tk=${qqAuth.gtk}&format=json&utf8=1"
            for (cookie in cookieCandidates) {
                val itemJson = runCatching {
                    getJson(favURL, referer = "https://y.qq.com/n/ryqq_v2/profile/create", cookie = cookie)
                }.getOrNull() ?: continue
                val songs = favoriteSongArray(itemJson).take(targetLimit)
                    .mapNotNull { song((it.optMap("track_info") ?: it)) }
                if (songs.isNotEmpty()) return songs
            }
        }

        val resolvedMapid = likedPlaylistID(qqAuth, cookieCandidates)
        if (resolvedMapid == null || resolvedMapid <= 0L) {
            logError("QQ 我的喜欢歌单解析失败：未找到真实歌单 ID")
            return emptyList()
        }
        val fallbackLimit = if (limit > 0) limit else pageSize
        for (cookie in cookieCandidates) {
            val songs = playlistSongs(listID = resolvedMapid, preferredCookie = cookie, limit = fallbackLimit)
            if (songs.isNotEmpty()) return songs
        }
        return playlistSongs(listID = resolvedMapid, preferredCookie = null, limit = fallbackLimit)
    }

    /// 解析「我的喜欢」的真实歌单 ID。该歌单通常不会出现在 profile/create 的创建歌单列表中，
    /// 需要通过 dirid=201 专用接口或 GetUserPlaylist order=3 单独获取。
    private suspend fun likedPlaylistID(qqAuth: QQMusicAuth, cookies: List<String>): Long? {
        val favURL =
            "https://c.y.qq.com/splcloud/fcgi-bin/fcg_musiclist_getmyfav.fcg?dirid=201&dirinfo=1&g_tk=${qqAuth.gtk}&format=json&utf8=1"
        for (cookie in cookies) {
            val favJson = runCatching {
                getJson(favURL, referer = "https://y.qq.com/n/yqq/playlist", cookie = cookie)
            }.getOrNull() ?: continue
            likedMapID(favJson)?.let { if (it > 0) return it }

            val data = favJson.optMap("data") ?: favJson
            val first = data.arr("cdlist")?.objAt(0)
            if (first != null) {
                val id = integerValue(first.opt("dissid") ?: first.opt("diss_id"))
                if (id > 0) return id
            }
        }

        val identities = qqAuth.playlistIdentityCandidates
        for (identity in identities) {
            val numericUin = identity.toIntOrNull() ?: 0
            val payload = JSONObject()
                .put(
                    "comm",
                    JSONObject()
                        .put("ct", 24)
                        .put("cv", 0)
                        .put("uin", numericUin)
                        .put("g_tk", qqAuth.gtk)
                        .put("platform", "yqq"),
                )
                .put(
                    "req_1",
                    JSONObject()
                        .put("module", "music.musichallSong.PlayListDataServer")
                        .put("method", "GetUserPlaylist")
                        .put(
                            "param",
                            JSONObject()
                                .put("uin", numericUin)
                                .put("sin", 0)
                                .put("size", 100)
                                .put("order", 3),
                        ),
                )
            for (cookie in cookies) {
                val json = runCatching { musicu(payload, cookie = cookie, timeout = 15.0) }.getOrNull() ?: continue
                for (item in playlistArray(json)) {
                    val id = integerValue(item.opt("dissid") ?: item.opt("diss_id") ?: item.opt("tid"))
                    if (id > 0) return id
                }
            }
        }
        return null
    }

    /// fcg_musiclist_getmyfav 的 map 可能是标量、嵌套在 data 中，或是 {"201": 真实歌单 ID}。
    private fun likedMapID(json: JSONObject): Long? {
        val data = json.optMap("data") ?: JSONObject()
        val values = listOf(
            json.opt("map"), json.opt("mapid"), json.opt("id"),
            data.opt("map"), data.opt("mapid"), data.opt("id"),
        )
        for (value in values) {
            if (value is JSONObject) {
                val preferredID = integerValue(value.opt("201"))
                if (preferredID > 0) return preferredID
                val id = value.keys().asSequence()
                    .map { integerValue(value.opt(it)) }
                    .firstOrNull { it > 0 }
                if (id != null) return id
            } else {
                val id = integerValue(value)
                if (id > 0) return id
            }
        }
        return null
    }

    /// 递归提取 fcg_musiclist_getmyfav 可能返回的 songlist/songList 数组。
    private fun favoriteSongArray(json: JSONObject): List<JSONObject> {
        var result: List<JSONObject> = emptyList()
        fun walk(value: Any?) {
            if (result.isNotEmpty()) return
            when (value) {
                is JSONObject -> {
                    for (key in listOf("songlist", "songList", "song_list", "tracks", "tracklist")) {
                        val list = value.arr(key)?.objects()
                        if (!list.isNullOrEmpty()) {
                            result = list
                            return
                        }
                    }
                    for (key in value.keys()) walk(value.opt(key))
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i))
                }
            }
        }
        walk(json)
        return result
    }

    /// 收藏接口的曲目有时会包在 songInfo/data/track_info 内，统一解包到歌曲字段层。
    private fun unwrapQQSong(item: JSONObject): JSONObject {
        if (item.has("songmid") || item.has("mid") || item.has("songname")) {
            return item
        }
        for (key in listOf("songInfo", "songinfo", "track_info", "trackInfo", "data", "song")) {
            val nested = item.optMap(key) ?: continue
            val unwrapped = unwrapQQSong(nested)
            if (unwrapped.has("songmid") || unwrapped.has("mid") || unwrapped.has("songname")) {
                return unwrapped
            }
        }
        return item
    }

    // MARK: - 红心收藏

    /// 红心 / 取消红心（musicu.fcg music.srfDissong do_dissong_op，借鉴 qqmusicapi 逆向实现）
    suspend fun like(songmid: String, liked: Boolean): Boolean {
        val qqAuth = QQMusicAuth
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 24)
                    .put("cv", 0)
                    .put("uin", if (qqAuth.isLoggedIn) qqAuth.uin else "0")
                    .put("g_tk", 5381)
                    .put("platform", "yqq")
                    .put("format", "json"),
            )
            .put(
                "req_0",
                JSONObject()
                    .put("module", "music.srfDissong")
                    .put("method", "do_dissong_op")
                    .put(
                        "param",
                        JSONObject()
                            .put("songmid", JSONArray().put(songmid))
                            .put("op", if (liked) 1 else 2),
                    ),
            )
        val json = musicu(payload, cookie = if (qqAuth.isLoggedIn) qqAuth.cookieHeader else "")
        val req = json.optMap("req_0")
        val code = intOrNull(req?.opt("code")) ?: -1
        return code == 0
    }

    // MARK: - 播放 / 歌词

    /// 指定音质获取播放地址（br: M800=320kbps 高质量 / M500=128kbps 低质量），下载用
    suspend fun songURL(songmid: String, mediaMid: String? = null, br: String): String? {
        val qqAuth = QQMusicAuth
        val uin = if (qqAuth.isLoggedIn) qqAuth.uin else "0"
        val loginKey = if (qqAuth.isLoggedIn) qqAuth.loginKey else ""
        val guid = deviceGuid
        val resolvedMediaMid = resolveMediaMid(songmid = songmid, provided = mediaMid, qqAuth = qqAuth)
        return vkeyURL(
            songmid = songmid,
            mediaMid = resolvedMediaMid,
            br = br,
            uin = uin,
            loginKey = loginKey,
            guid = guid,
            qqAuth = qqAuth,
        )
    }

    /// 通过 vkey 获取 QQ 音乐播放地址（对齐 wp_MusicApi：GET + data JSON + filename + CDN 分发）。
    /// 优先请求当前官方音质；会员歌曲在目标档位不可用时，继续尝试兼容档位。
    suspend fun songURL(
        songmid: String,
        mediaMid: String? = null,
        quality: BeansAudioQuality = BeansAudioQuality.HIRES,
    ): String? {
        val qqAuth = QQMusicAuth
        val uin = if (qqAuth.isLoggedIn) qqAuth.uin else "0"
        val loginKey = if (qqAuth.isLoggedIn) qqAuth.loginKey else ""
        val guid = deviceGuid
        val resolvedMediaMid = resolveMediaMid(songmid = songmid, provided = mediaMid, qqAuth = qqAuth)
        val preferredBR = qqBR(quality)
        val fallbackBRs = listOf(preferredBR, "F000", "M800", "M500", "C400")
        val seenBRs = mutableSetOf<String>()
        // 独家 VIP 曲库并不保证所有档位都存在；避免同一 BR 因不同显示音质重复请求。
        for (br in fallbackBRs) {
            if (!seenBRs.add(br)) continue
            val url = vkeyURL(
                songmid = songmid,
                mediaMid = resolvedMediaMid,
                br = br,
                uin = uin,
                loginKey = loginKey,
                guid = guid,
                qqAuth = qqAuth,
            )
            if (url != null) return url
        }
        return null
    }

    /// 返回 QQ 实际命中的 BR，供播放器在真正失败时继续向下切换。
    suspend fun songURLResult(
        songmid: String,
        mediaMid: String? = null,
        quality: BeansAudioQuality = BeansAudioQuality.HIRES,
    ): SongURLResult? {
        val qqAuth = QQMusicAuth
        val uin = if (qqAuth.isLoggedIn) qqAuth.uin else "0"
        val loginKey = if (qqAuth.isLoggedIn) qqAuth.loginKey else ""
        val guid = deviceGuid
        val resolvedMediaMid = resolveMediaMid(songmid = songmid, provided = mediaMid, qqAuth = qqAuth)
        val preferredBR = qqBR(quality)
        val fallbackBRs = listOf(preferredBR, "F000", "M800", "M500", "C400")
        val seenBRs = mutableSetOf<String>()
        val attemptedBRs = mutableListOf<String>()
        for (br in fallbackBRs) {
            if (!seenBRs.add(br)) continue
            attemptedBRs.add(br)
            val url = vkeyURL(
                songmid = songmid,
                mediaMid = resolvedMediaMid,
                br = br,
                uin = uin,
                loginKey = loginKey,
                guid = guid,
                qqAuth = qqAuth,
            )
            if (url != null) return SongURLResult(url = url, br = br, attemptedBRs = attemptedBRs)
        }
        return null
    }

    /// 搜索接口经常不返回 file.media_mid；独家 VIP 歌曲的 media_mid 又常与 songmid 不同，必须补拉详情。
    private suspend fun resolveMediaMid(songmid: String, provided: String?, qqAuth: QQMusicAuth): String {
        if (!provided.isNullOrEmpty()) return provided
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 24)
                    .put("cv", 0)
                    .put("uin", if (qqAuth.isLoggedIn) qqAuth.uin else "0"),
            )
            .put(
                "songinfo",
                JSONObject()
                    .put("module", "music.pf_song_detail_svr")
                    .put("method", "get_song_detail_yqq")
                    .put("param", JSONObject().put("song_mid", songmid)),
            )
        val json = runCatching {
            musicu(payload, cookie = if (qqAuth.isLoggedIn) qqAuth.cookieHeader else "")
        }.getOrNull()
        val block = json?.optMap("songinfo")
        val data = block?.optMap("data")
        val track = data?.optMap("track_info")
        val file = track?.optMap("file")
        val mediaMid = file?.opt("media_mid") as? String
        if (mediaMid.isNullOrEmpty()) {
            return songmid
        }
        return mediaMid
    }

    /// 单次 vkey 请求（GET musicu.fcg，data 参数格式与 wp_MusicApi 完全一致）
    private suspend fun vkeyURL(
        songmid: String,
        mediaMid: String?,
        br: String,
        uin: String,
        loginKey: String,
        guid: String,
        qqAuth: QQMusicAuth,
    ): String? {
        // 音质与扩展名：M500/M800 为 mp3，F000（无损）为 flac
        val ext = when {
            br.startsWith("F") -> "flac"
            br.startsWith("C") -> "m4a"
            else -> "mp3"
        }
        val preferredMid = (mediaMid?.takeIf { it.isNotEmpty() }) ?: songmid
        // QQ 官方 Web 格式为 prefix + songmid + media_mid；其余格式用于兼容不同年代接口。
        val filenames = listOf(
            "$br$songmid$preferredMid.$ext",
            "$br$preferredMid.$ext",
            "$br$songmid$songmid.$ext",
            "$br$songmid.$ext",
        ).distinct()
        val songmids = JSONArray().also { arr -> repeat(filenames.size) { arr.put(songmid) } }
        val songtypes = JSONArray().also { arr -> repeat(filenames.size) { arr.put(0) } }
        val param = JSONObject()
            .put("filename", JSONArray(filenames))
            .put("guid", guid)
            .put("songmid", songmids)
            .put("songtype", songtypes)
            .put("uin", uin)
            .put("loginflag", if (qqAuth.isLoggedIn) 1 else 0)
            .put("platform", "20")
        val comm = JSONObject()
            .put("uin", uin.toIntOrNull() ?: 0)
            .put("format", "json")
            .put("ct", if (loginKey.isEmpty()) 24 else 19)
            .put("cv", 0)
            .put("g_tk", qqAuth.gtk)
        if (loginKey.isNotEmpty()) comm.put("authst", loginKey)
        val payload = JSONObject()
            .put("comm", comm)
            .put(
                "req",
                JSONObject()
                    .put("module", "CDN.SrfCdnDispatchServer")
                    .put("method", "GetCdnDispatch")
                    .put("param", JSONObject().put("guid", guid).put("calltype", 0).put("userip", "")),
            )
            .put(
                "req_0",
                JSONObject()
                    .put("module", "vkey.GetVkeyServer")
                    .put("method", "CgiGetVkey")
                    .put("param", param),
            )
        val url = BASE.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("data", payload.toString())
            ?.build()
            ?.toString()
            ?: return null
        val headers = mutableMapOf(
            "User-Agent" to UA_FIREFOX,
            "Referer" to "https://y.qq.com/",
            "Origin" to "https://y.qq.com",
            "Cookie" to if (qqAuth.isLoggedIn) qqAuth.cookieHeader else DEFAULT_COOKIE,
        )
        val json = runCatching { getJsonWith(url, headers) }.getOrNull() ?: return null
        val req = json.optMap("req_0") ?: return null
        val reqData = req.optMap("data") ?: return null
        val infos = reqData.arr("midurlinfo").orEmptyObjects()
        val playableInfos = infos.filter { !(it.opt("purl") as? String).isNullOrEmpty() }
        if (playableInfos.isEmpty()) {
            // 官方没给地址（VIP / 风控）：把响应里的标记字段打出来，便于判断是会员受限还是被风控。
            val marker = infos.firstOrNull()
            Log.d(
                LOG_TAG,
                "QQ vkey 无可播地址：br=$br result=${marker?.opt("result")} errtype=${marker?.opt("errtype")} " +
                    "已登录=${if (qqAuth.isLoggedIn) "是" else "否"}",
            )
            return null
        }
        val sips = reqData.arr("sip")?.strings() ?: emptyList()
        val cdnBases = qqCDNBases(sips)
        var unverifiedCandidate: String? = null
        for (info in playableInfos) {
            val purl = info.opt("purl") as? String ?: continue
            if (purl.isEmpty()) continue
            val candidateURLs = if (purl.startsWith("http")) {
                listOf(purl)
            } else {
                cdnBases.map { base ->
                    val secureBase = if (base.startsWith("http://")) "https://" + base.substring("http://".length) else base
                    val suffix = if (purl.startsWith("/")) purl.substring(1) else purl
                    secureBase + suffix
                }
            }
            for (candidate in candidateURLs) {
                if (candidate.toHttpUrlOrNull() == null) continue
                if (unverifiedCandidate == null) unverifiedCandidate = candidate
                when (probeAudioURL(candidate, cookie = if (qqAuth.isLoggedIn) qqAuth.cookieHeader else "")) {
                    AudioProbeResult.PLAYABLE -> {
                        // 只有 vkey 响应里的 filename 可能带有试听 / 权限标记，留一行现场便于真机核对。
                        Log.d(LOG_TAG, "QQ vkey 命中：br=$br 文件=${info.opt("filename")}")
                        return candidate
                    }
                    AudioProbeResult.FORBIDDEN -> continue
                    AudioProbeResult.INDETERMINATE ->
                        if (unverifiedCandidate == null) unverifiedCandidate = candidate
                }
            }
        }
        // 部分 QQ CDN 不支持 Range 探测，但播放器携带 Referer/Cookie 仍可播放。
        // 不要把“探测被拒绝”误判成会员没有播放权限，交给播放器继续验证。
        unverifiedCandidate?.let { return it }
        return null
    }

    private enum class AudioProbeResult { PLAYABLE, FORBIDDEN, INDETERMINATE }

    /// 在交给播放器前验证 CDN，避免 purl 非空但实际 404 的假成功地址。
    private suspend fun probeAudioURL(url: String, cookie: String): AudioProbeResult = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: return@withContext AudioProbeResult.INDETERMINATE
        val builder = Request.Builder()
            .url(httpUrl)
            .header("Range", "bytes=0-2047")
            .header("User-Agent", UA_FIREFOX)
            .header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com")
        if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
        try {
            _shortTimeoutClient.newCall(builder.build()).execute().use { response ->
                if (response.code in intArrayOf(403, 404, 410, 451)) return@withContext AudioProbeResult.FORBIDDEN
                val body = response.body?.bytes() ?: ByteArray(0)
                if ((response.code != 200 && response.code != 206) || body.isEmpty()) {
                    return@withContext AudioProbeResult.INDETERMINATE
                }
                val contentType = (response.header("Content-Type") ?: "").lowercase()
                if (contentType.contains("text/html") || contentType.contains("application/json")) {
                    return@withContext AudioProbeResult.FORBIDDEN
                }
                AudioProbeResult.PLAYABLE
            }
        } catch (_: IOException) {
            // 超时、TLS/CDN 节点暂时不支持 Range 等情况不能证明会员地址失效，
            // 交给播放器继续尝试，避免把有效地址错误降级到最低音质。
            AudioProbeResult.INDETERMINATE
        }
    }

    /// 固定设备 GUID（持久化）：vkey 与 guid 强相关，随机 guid 会导致播放地址失效
    @Volatile
    private var cachedGuid: String? = null

    private val deviceGuid: String
        get() {
            cachedGuid?.let { return it }
            val prefs = runCatching {
                BeansApplication.instance.getSharedPreferences(GUID_PREFS, android.content.Context.MODE_PRIVATE)
            }.getOrNull()
            val saved = runCatching { prefs?.getString(GUID_KEY, null) }.getOrNull()
            if (!saved.isNullOrEmpty()) {
                cachedGuid = saved
                return saved
            }
            val guid = "%09d".format(Random.nextInt(100000000, 1000000000))
            runCatching { prefs?.edit()?.putString(GUID_KEY, guid)?.apply() }
            cachedGuid = guid
            return guid
        }

    private fun qqBR(quality: BeansAudioQuality): String = when (quality) {
        BeansAudioQuality.STANDARD -> "M500"
        BeansAudioQuality.HIGHER, BeansAudioQuality.EXHIGH -> "M800"
        BeansAudioQuality.LOSSLESS, BeansAudioQuality.HIRES -> "F000"
    }

    /// QQ 返回的 SIP 可能只包含当前分配到的节点。保留官方节点的同时补充
    /// 常见备用 CDN，避免会员地址只落到一个临时失效节点。
    private fun qqCDNBases(sips: List<String>): List<String> {
        val fallbacks = listOf(
            "https://isure.stream.qqmusic.qq.com/",
            "https://dl.stream.qqmusic.qq.com/",
            "https://ws.stream.qqmusic.qq.com/",
            "https://streamoc.music.tc.qq.com/",
        )
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (raw in sips + fallbacks) {
            var base = raw.trim()
            if (base.isEmpty()) continue
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                base = "https://$base"
            }
            if (!base.endsWith("/")) {
                base += "/"
            }
            if (seen.add(base.lowercase())) {
                result.add(base)
            }
        }
        return result
    }

    /// QQ 音乐歌词（LRC 文本）
    suspend fun lyric(songmid: String): String? {
        val mid = urlQueryAllowed(songmid)
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=1&g_tk=5381"
        val json = getJson(url, referer = "https://y.qq.com/portal/player.html")
        val lyric = json.opt("lyric") as? String
        if (lyric.isNullOrEmpty()) return null
        return lyric
    }

    /**
     * 逐字歌词入口：有 QRC 就用 QRC，否则退回 [lyric] 的整行 LRC（`words` 全空）。
     *
     * ## 为什么不用 `fcg_query_lyric_new`
     *
     * 本机实测：老歌词接口加上 `&qrc=1` 之后响应**逐字节不变**，只返回 LRC；QRC 只能从
     * musicu 的 `music.musichallSong.PlayLyricInfo / GetPlayLyricInfo` 拿，且必须同时带
     * `crypt=1`（返回十六进制密文）与 `qrc=1`。
     *
     * ## 关于「必须登录」
     *
     * 实测**未登录**（`uin=0`、默认 Cookie）也能拿到可解密的 QRC，但**不是每首歌都有** ——
     * 没有 `qrc` 的歌会返回空 `lyric`，此时必须干净地退回整行 LRC，绝不能返回空歌词。
     */
    suspend fun lyricWithWords(songmid: String): List<LyricLine> {
        val encrypted = runCatching { qrcPayload(songmid) }.getOrNull()
        if (!encrypted.isNullOrEmpty()) {
            val decoded = QrcCipher.decryptHexToText(encrypted)
            // `crypt` 偶尔被忽略、直接回明文 LRC：那它本身就够当整行歌词用，不必再请求一次。
            // QRC 正文去掉了时间标记后同样是可用的整行歌词，只是在没有逐字数据时退化。
            val fromMusicu = if (decoded != null) QrcParser.parse(decoded) else LyricParser.parse(encrypted)
            if (fromMusicu.isNotEmpty()) return fromMusicu
        }
        return LyricParser.parse(lyric(songmid).orEmpty())
    }

    /** musicu 的 QRC 请求：响应里的 `req_1.data.lyric` 是十六进制密文。 */
    private suspend fun qrcPayload(songmid: String): String? {
        val qqAuth = QQMusicAuth
        val uin = if (qqAuth.isLoggedIn) qqAuth.uin else "0"
        val payload = JSONObject()
            .put(
                "comm",
                JSONObject()
                    .put("ct", 24)
                    .put("cv", 0)
                    .put("uin", uin)
                    .put("format", "json"),
            )
            .put(
                "req_1",
                JSONObject()
                    .put("module", "music.musichallSong.PlayLyricInfo")
                    .put("method", "GetPlayLyricInfo")
                    .put(
                        "param",
                        JSONObject()
                            .put("crypt", 1)
                            .put("lrc_t", 0)
                            .put("qrc", 1)
                            .put("qrc_t", 0)
                            .put("roma", 0)
                            .put("roma_t", 0)
                            .put("trans", 0)
                            .put("trans_t", 0)
                            .put("type", 1)
                            .put("songMID", songmid)
                            .put("songID", 0),
                    ),
            )
        val json = runCatching {
            musicu(payload, cookie = if (qqAuth.isLoggedIn) qqAuth.cookieHeader else "")
        }.getOrNull() ?: return null
        return json.optMap("req_1")?.optMap("data")?.opt("lyric") as? String
    }

    // MARK: - 评论区 / 排行榜 / 推荐 / 歌单

    /// QQ 音乐评论分页（fcg_global_comment_h5；topid 必须用数字 songid 并带 cid/reqtype，用 songmid 会返回空）
    /// pagenum 从 0 开始；热评只在第一页返回，翻页只取普通评论
    suspend fun comments(songID: Int, limit: Int = 25, pagenum: Int = 0): QQCommentPage {
        val json = postFormBody(
            urlString = "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg?format=json&cid=205360772&reqtype=2",
            body = linkedMapOf(
                "biztype" to 1,
                "topid" to songID,
                "LoginUin" to 0,
                "cmd" to 8,
                "pagenum" to maxOf(pagenum, 0),
                "pagesize" to minOf(maxOf(limit, 1), 25),
            ),
        )
        val hot = if (pagenum == 0) {
            json.optMap("hot_comment")?.arr("commentlist").orEmptyObjects()
        } else {
            emptyList()
        }
        val normal = json.optMap("comment")?.arr("commentlist").orEmptyObjects()
        val commentTotal = intOrNull(json.optMap("comment")?.opt("commenttotal")) ?: 0
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SongComment>()
        for (item in hot + normal) {
            val rootID = item.opt("rootcommentid") as? String ?: ""
            val commentID = item.opt("commentid") as? String ?: ""
            val key = rootID + "_" + commentID
            if (key.isEmpty() || seen.contains(key)) continue
            seen.add(key)
            var content = decodeCommentEmoji(item.opt("rootcommentcontent") as? String ?: "")
            content = content.replace("\\n", "\n")
            if (content.isEmpty()) continue
            var nick = item.opt("nick") as? String ?: ""
            if (nick.isEmpty()) nick = item.opt("rootcommentnick") as? String ?: ""
            if (nick.startsWith("@")) nick = nick.substring(1)
            val avatar = item.opt("avatarurl") as? String ?: ""
            val time = dblOrNull(item.opt("time")) ?: 0.0
            result.add(
                SongComment(
                    id = key.hashCode().toLong(),
                    content = content,
                    nickname = nick,
                    avatarURL = if (avatar.isEmpty()) null else avatar,
                    time = if (time > 0) (time * 1000.0).toLong() else System.currentTimeMillis(),
                    likedCount = intOrNull(item.opt("praisenum")) ?: 0,
                    isHot = true,
                ),
            )
        }
        return QQCommentPage(comments = result, total = commentTotal)
    }

    /// QQ 评论表情解码（[em]eXXXXXX[/em] → 对应 Unicode 表情）
    private val commentEmojis: Map<String, String> = mapOf(
        "e400846" to "😘",
        "e400874" to "😴",
        "e400825" to "😃",
        "e400847" to "😙",
        "e400835" to "😍",
        "e400873" to "😳",
        "e400836" to "😎",
        "e400867" to "😭",
        "e400832" to "😊",
        "e400837" to "😏",
        "e400875" to "😫",
        "e400831" to "😉",
        "e400855" to "😡",
        "e400823" to "😄",
        "e400862" to "😨",
        "e400844" to "😖",
        "e400841" to "😓",
        "e400830" to "😈",
        "e400828" to "😆",
        "e400833" to "😋",
        "e400822" to "😀",
        "e400843" to "😕",
        "e400829" to "😇",
        "e400824" to "😂",
        "e400834" to "😌",
        "e400877" to "😷",
        "e400132" to "🍉",
        "e400181" to "🍺",
        "e401067" to "☕️",
        "e400186" to "🥧",
        "e400343" to "🐷",
        "e400116" to "🌹",
        "e400126" to "🍃",
        "e400613" to "💋",
        "e401236" to "❤️",
        "e400622" to "💔",
        "e400637" to "💣",
        "e400643" to "💩",
        "e400773" to "🔪",
        "e400102" to "🌛",
        "e401328" to "🌞",
        "e400420" to "👏",
        "e400914" to "🙌",
        "e400408" to "👍",
        "e400414" to "👎",
        "e401121" to "✋",
        "e400396" to "👋",
        "e400384" to "👉",
        "e401115" to "✊",
        "e400402" to "👌",
        "e400905" to "🙈",
        "e400906" to "🙉",
        "e400907" to "🙊",
        "e400562" to "👻",
        "e400932" to "🙏",
        "e400644" to "💪",
        "e400611" to "💉",
        "e400185" to "🎁",
        "e400655" to "💰",
        "e400325" to "🐥",
        "e400612" to "💊",
        "e400198" to "🎉",
        "e401685" to "⚡️",
        "e400631" to "💝",
        "e400768" to "🔥",
        "e400432" to "👑",
    )

    private val commentEmojiRegex = Regex("""\[em\]e\d+\[/em\]""")

    private fun decodeCommentEmoji(raw: String): String =
        commentEmojiRegex.replace(raw) { match ->
            val code = match.value.replace("[em]", "").replace("[/em]", "")
            commentEmojis[code] ?: ""
        }

    /// QQ 峰尖榜总览（榜单列表在响应的 data.topList，字段为 topTitle / picUrl）
    suspend fun topLists(): List<QQTopInfo> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?format=json"
        val json = getJson(url)
        val data = json.optMap("data") ?: json
        val list = data.arr("topList").orEmptyObjects()
        val result = mutableListOf<QQTopInfo>()
        for (item in list) {
            val id = intOrNull(item.opt("id")) ?: continue
            val name = item.opt("topTitle") as? String ?: (item.opt("title") as? String ?: "")
            // 总览接口同时返回 MV/有声等非歌曲榜单；它们的详情接口没有可解析的歌曲，
            // 继续展示会让用户点进一个空白榜单。只保留歌曲榜单。
            if (isNonSongTopList(id = id, name = name)) continue
            val songs = item.arr("songList").orEmptyObjects()
            val topNames = songs.mapNotNull { it.opt("songname") as? String }.take(3)
            result.add(
                QQTopInfo(
                    id = id,
                    name = name,
                    subTitle = item.opt("subTitle") as? String ?: "",
                    topSongNames = topNames,
                    coverURL = normalizedQQImageURL(item.opt("picUrl")),
                ),
            )
        }
        return result
    }

    private fun isNonSongTopList(id: Int, name: String): Boolean {
        // These IDs are currently exposed by QQ's overview endpoint but return no
        // playable song records from fcg_v8_toplist_cp.fcg.
        if (id == 201 || id == 75) return true
        val normalized = name.lowercase()
        return normalized.contains("mv") || name.contains("有声") || name.contains("电台")
    }

    /// 某个峰尖榜的歌曲列表
    suspend fun topListSongs(topid: Int, limit: Int = 30): List<Song> {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?format=json&page=detail&type=top&topid=$topid&song_begin=0&song_num=$limit"
        val json = getJson(url)
        val list = json.arr("songlist").orEmptyObjects()
        return list.mapNotNull { entry ->
            val trackInfo = entry.optMap("data")
            if (trackInfo != null) song(trackInfo) else song(entry)
        }
    }

    /// QQ 每日推荐：热歌/新歌/飙升 三榜混合，按日期种子确定性打乱，每日轮换且与单个榜单内容区分
    suspend fun recommendSongs(limit: Int = 30): List<Song> {
        val day = java.time.LocalDate.now().dayOfYear
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        val per = maxOf(8, (limit + 2) / 3)
        for (topid in listOf(26, 27, 62)) {
            val list = runCatching { topListSongs(topid = topid, limit = per) }.getOrNull() ?: continue
            for (item in list) {
                if (seen.contains(item.identityKey)) continue
                seen.add(item.identityKey)
                songs.add(item)
            }
        }
        // 与 Swift SeededRNG 同样按日期播种，保证同一天内刷新得到相同顺序。
        val rng = Random(day.toLong() * 2654435761L)
        songs.shuffle(rng)
        return songs.take(limit)
    }

    /// 用户歌单（创建 + 收藏合并）。
    /// 微信网页登录可能没有 QQ uin，优先使用带微信 Cookie 的官方 GetUserPlaylist 接口，
    /// 同时保留旧版 fcg 接口作为 QQ 登录和部分旧账号的快速通道。
    suspend fun userPlaylists(uin: String): List<Playlist> {
        val qqAuth = QQMusicAuth
        if (!qqAuth.isLoggedIn) return emptyList()

        val requestUin = qqAuth.playlistUin
        val cookieCandidates = listOf(qqAuth.playlistCookieHeader, qqAuth.cookieHeader)
            .filter { it.isNotEmpty() }
            .distinct()
        val legacyUins = (qqAuth.playlistIdentityCandidates + listOf(requestUin, uin))
            .filter { it.isNotEmpty() && it != "0" }
            .distinct()

        val playlists = mutableListOf<Playlist>()
        val seen = mutableSetOf<Long>()

        fun append(entry: JSONObject) {
            val parsed = playlist(entry) ?: return
            if (seen.contains(parsed.id)) return
            val text = parsed.name + " " + (entry.opt("hostname") as? String ?: "")
            if (text.lowercase().contains("qzone") || text.contains("空间") || text.contains("背景音乐")) return
            seen.add(parsed.id)
            playlists.add(parsed)
        }

        var createdLoaded = false
        var collectedLoaded = false

        // QQ/微信登录都尝试旧接口；部分微信态账号必须显式带 wxuin 才会返回歌单。
        for (legacyUin in legacyUins) {
            val createdURL =
                "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?hostUin=0&hostuin=$legacyUin&sin=0&size=200&g_tk=${qqAuth.gtk}&loginUin=$legacyUin&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
            for (cookie in cookieCandidates) {
                val created = runCatching {
                    getJson(createdURL, referer = "https://y.qq.com/portal/profile.html", cookie = cookie)
                }.getOrNull() ?: continue
                val data = created.optMap("data") ?: created
                val disslist = data.arr("disslist") ?: created.arr("disslist") ?: JSONArray()
                if (disslist.length() > 0) {
                    createdLoaded = true
                    disslist.objects().forEach { append(it) }
                    break
                }
            }
            if (createdLoaded) break
        }

        for (legacyUin in legacyUins) {
            val collectURL =
                "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg?ct=20&cid=205360956&userid=$legacyUin&reqtype=3&sin=0&ein=80&g_tk=${qqAuth.gtk}"
            for (cookie in cookieCandidates) {
                val collected = runCatching {
                    getJson(collectURL, referer = "https://y.qq.com/portal/profile.html", cookie = cookie)
                }.getOrNull() ?: continue
                val data = collected.optMap("data") ?: collected
                val cdlist = data.arr("cdlist") ?: collected.arr("cdlist") ?: JSONArray()
                if (cdlist.length() > 0) {
                    collectedLoaded = true
                    cdlist.objects().forEach { append(it) }
                    break
                }
            }
            if (collectedLoaded) break
        }

        // 官方 App 接口按多个身份候选依次尝试，兼容 QQ 登录和微信网页登录。
        // order：1=创建，2=收藏，3=我喜欢。空响应仍继续尝试下一个身份参数。
        val officialUins = (qqAuth.playlistIdentityCandidates + listOf(requestUin, "0")).distinct()
        for ((order, loaded) in listOf(1 to createdLoaded, 2 to collectedLoaded, 3 to false)) {
            if (loaded) continue
            for (officialUin in officialUins) {
                val numericUin = officialUin.toIntOrNull() ?: 0
                val payload = JSONObject()
                    .put(
                        "comm",
                        JSONObject()
                            .put("ct", 24)
                            .put("cv", 0)
                            .put("uin", numericUin)
                            .put("g_tk", qqAuth.gtk)
                            .put("platform", "yqq"),
                    )
                    .put(
                        "req_1",
                        JSONObject()
                            .put("module", "music.musichallSong.PlayListDataServer")
                            .put("method", "GetUserPlaylist")
                            .put(
                                "param",
                                JSONObject()
                                    .put("uin", numericUin)
                                    .put("sin", 0)
                                    .put("size", 200)
                                    .put("order", order),
                            ),
                    )
                for (cookie in cookieCandidates) {
                    val json = runCatching { musicu(payload, cookie = cookie, timeout = 15.0) }.getOrNull() ?: continue
                    val list = playlistArray(json)
                    if (list.isNotEmpty()) {
                        list.forEach { append(it) }
                        break
                    }
                }
                if (playlists.isNotEmpty() && order != 3) break
            }
        }

        // QQ 网页的 profile/create 只展示创建歌单，“我的喜欢”由 dirid=201 单独维护。
        // 即使各歌单列表接口没有把它返回，也要显式放回音乐库列表。
        if (!seen.contains(QQ_LIKED_PLAYLIST_ID) &&
            playlists.none { it.name == "我的喜欢" }
        ) {
            playlists.add(
                0,
                Playlist(
                    id = QQ_LIKED_PLAYLIST_ID,
                    name = "我的喜欢",
                    coverURL = QQ_LIKED_COVER_URL,
                    source = SongSource.QQ,
                ),
            )
        }

        val sorted = playlists.sortedWith { lhs, rhs ->
            val a = lhs.name.contains("我喜欢") || lhs.name.contains("我的喜欢") || lhs.name.contains("喜欢的音乐")
            val b = rhs.name.contains("我喜欢") || rhs.name.contains("我的喜欢") || rhs.name.contains("喜欢的音乐")
            if (a != b) {
                if (a) -1 else 1
            } else {
                lhs.name.compareTo(rhs.name)
            }
        }
        return sorted
    }

    /// QQ 歌单项解析，兼容旧 fcg 和 musicu GetUserPlaylist 的字段命名。
    private fun playlist(item: JSONObject): Playlist? {
        val rawName = item.opt("diss_name") as? String
            ?: (item.opt("dissname") as? String
                ?: (item.opt("name") as? String ?: (item.opt("title") as? String ?: "")))
        val dirid = integerValue(item.opt("dirid"))
        val dissid = integerValue(item.opt("dissid") ?: item.opt("diss_id"))
        val tid = integerValue(item.opt("tid"))

        val trimmedName = rawName.trim()
        if (dirid == 201L || trimmedName == "我喜欢" || trimmedName == "我的喜欢" || trimmedName == "喜欢的音乐") {
            return Playlist(
                id = QQ_LIKED_PLAYLIST_ID,
                name = "我的喜欢",
                coverURL = "https://y.gtimg.cn/mediastyle/global/img/cover_like.png",
                trackCount = integerValue(item.opt("song_cnt") ?: item.opt("songnum") ?: item.opt("total_song_num")).toInt(),
                source = SongSource.QQ,
            )
        }

        // 目录项没有真实歌单 ID，不能展示成可打开但永远为空的歌单。
        if (dissid == 0L && tid == 0L && dirid > 0L) return null
        val id = if (dissid > 0L) dissid else if (tid > 0L) tid else if (dirid > 0L) dirid else integerValue(item.opt("id"))
        if (id <= 0L || trimmedName.isEmpty()) return null

        val coverURL = listOf(
            "diss_cover", "dir_pic_url", "logo", "picurl", "pic_url",
            "cover", "cover_url", "headurl", "imgurl",
        ).firstNotNullOfOrNull { normalizedQQImageURL(item.opt(it)) }
        val count = integerValue(
            item.opt("song_cnt") ?: item.opt("songnum") ?: item.opt("total_song_num") ?: item.opt("song_count"),
        ).toInt()
        return Playlist(id = id, name = trimmedName, coverURL = coverURL, trackCount = count, source = SongSource.QQ)
    }

    private fun integerValue(value: Any?): Long {
        if (value == null || value == JSONObject.NULL) return 0L
        if (value is Number) return value.toLong()
        if (value is String) return value.toLongOrNull() ?: 0L
        return 0L
    }

    private fun intOrNull(value: Any?): Int? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is Number) return value.toInt()
        if (value is String) return value.toIntOrNull()
        return null
    }

    private fun longOrNull(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is Number) return value.toLong()
        if (value is String) return value.toLongOrNull()
        return null
    }

    private fun dblOrNull(value: Any?): Double? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is Number) return value.toDouble()
        if (value is String) return value.toDoubleOrNull()
        return null
    }

    private fun decodeJSONStringValue(value: String): String = try {
        JSONObject("{\"v\":\"$value\"}").optString("v", value)
    } catch (_: Exception) {
        value
    }

    private fun initialData(html: String): JSONObject? {
        val marker = html.indexOf("window.__INITIAL_DATA__").takeIf { it >= 0 }
            ?: html.indexOf("__INITIAL_DATA__").takeIf { it >= 0 }
            ?: return null
        val start = html.indexOf('{', marker).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < html.length) {
            val ch = html[index]
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = inString
            } else if (ch == '"') {
                inString = !inString
            } else if (!inString) {
                if (ch == '{') {
                    depth += 1
                } else if (ch == '}') {
                    depth -= 1
                    if (depth == 0) {
                        return try {
                            JSONObject(html.substring(start, index + 1))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
            index += 1
        }
        return null
    }

    private fun hotRecommendArray(json: JSONObject): List<JSONObject> {
        val top = json.arr("hotRecommend")?.objects()
        if (!top.isNullOrEmpty()) return top
        var result: List<JSONObject> = emptyList()
        fun walk(value: Any?) {
            if (result.isNotEmpty()) return
            when (value) {
                is JSONObject -> {
                    for (key in value.keys()) {
                        val child = value.opt(key)
                        if (key == "hotRecommend" && child is JSONArray) {
                            val list = child.objects()
                            if (list.isNotEmpty()) {
                                result = list
                                return
                            }
                        }
                        walk(child)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i))
                }
            }
        }
        walk(json)
        return result
    }

    private fun hotRecommendPlaylist(item: JSONObject): Playlist? {
        val id = integerValue(item.opt("dissid") ?: item.opt("diss_id") ?: item.opt("tid") ?: item.opt("id"))
        val name = (item.opt("dissname") as? String) ?: (item.opt("title") as? String) ?: (item.opt("name") as? String) ?: ""
        val trimmedName = name.trim()
        if (id <= 0 || trimmedName.isEmpty()) return null
        val cover = normalizedQQImageURL(item.opt("imgurl") ?: item.opt("picurl") ?: item.opt("pic_url") ?: item.opt("cover"))
        val count = integerValue(item.opt("listennum") ?: item.opt("songnum") ?: item.opt("song_cnt") ?: item.opt("listen_num")).toInt()
        return Playlist(id = id, name = trimmedName, coverURL = cover, trackCount = count, source = SongSource.QQ)
    }

    /// 兼容 GetUserPlaylist 在不同客户端版本中的嵌套位置。
    private fun playlistArray(json: JSONObject): List<JSONObject> {
        val paths = listOf(
            listOf("req_1", "data", "v_playlist"),
            listOf("req_1", "data", "playlist"),
            listOf("req_1", "data", "list"),
            listOf("req_1", "data", "data", "v_playlist"),
            listOf("req_1", "data", "body", "v_playlist"),
        )
        for (path in paths) {
            var current: Any? = json
            for (key in path) {
                val dict = current as? JSONObject
                if (dict == null) {
                    current = null
                    break
                }
                current = dict.opt(key)
            }
            if (current is JSONArray) {
                val list = current.objects()
                if (list.isNotEmpty()) return list
            }
        }

        var result: List<JSONObject> = emptyList()
        fun walk(value: Any?) {
            if (result.isNotEmpty()) return
            when (value) {
                is JSONObject -> {
                    for (key in value.keys()) {
                        val child = value.opt(key)
                        if (key == "v_playlist" && child is JSONArray) {
                            val list = child.objects()
                            if (list.isNotEmpty()) {
                                result = list
                                return
                            }
                        }
                        walk(child)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) walk(value.opt(i))
                }
            }
        }
        walk(json)
        return result
    }

    /// QQ 官网首页热门歌单。
    /// 官网首页会把这组数据放在 SSR 的 __INITIAL_DATA__.hotRecommend 中，
    /// 比旧的 RecommendPlaylist musicu 接口更接近官网实际展示内容。
    suspend fun hotPlaylists(limit: Int = 18): List<Playlist> {
        val headers = mapOf(
            "User-Agent" to UA_IPHONE,
            "Referer" to "https://y.qq.com/",
        )
        val html = try {
            Http.getText("https://y.qq.com/", headers)
        } catch (e: BeansApiException) {
            throw BeansApiException.Network(e.message ?: "network error")
        }

        val initialData = initialData(html)
        if (initialData != null) {
            val seen = mutableSetOf<Long>()
            val playlists = hotRecommendArray(initialData)
                .mapNotNull { hotRecommendPlaylist(it) }
                .filter { playlist ->
                    if (seen.contains(playlist.id)) return@filter false
                    seen.add(playlist.id)
                    true
                }
                .take(maxOf(1, limit))
            if (playlists.isNotEmpty()) {
                return enrichHotPlaylistCovers(playlists)
            }
        }

        val pattern = Regex(
            """"imgurl"\s*:\s*"([^"]+)".*?"dissname"\s*:\s*"([^"]*)".*?"listennum"\s*:\s*([0-9]+).*?"dissid"\s*:\s*([0-9]+)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val playlists = mutableListOf<Playlist>()
        val seen = mutableSetOf<Long>()
        for (match in pattern.findAll(html)) {
            val groups = match.groupValues
            if (groups.size != 5) continue
            val id = groups[4].toLongOrNull() ?: continue
            if (seen.contains(id)) continue
            val image = decodeJSONStringValue(groups[1])
            val name = decodeJSONStringValue(groups[2])
            val count = groups[3].toIntOrNull() ?: 0
            if (name.isEmpty()) continue
            seen.add(id)
            playlists.add(Playlist(id = id, name = name, coverURL = normalizedQQImageURL(image), trackCount = count, source = SongSource.QQ))
            if (playlists.size >= maxOf(1, limit)) break
        }
        if (playlists.isNotEmpty()) return enrichHotPlaylistCovers(playlists)
        return recommendPlaylistsFallback(limit = limit)
    }

    /// 官网 SSR 为了懒加载通常只返回占位 data URI，使用歌单详情接口一次性补齐真实 logo。
    private suspend fun enrichHotPlaylistCovers(playlists: List<Playlist>): List<Playlist> {
        val ids = playlists.map { it.id }.joinToString(",")
        if (ids.isEmpty()) return playlists
        val url =
            "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$ids&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        val json = runCatching { getJson(url, referer = "https://y.qq.com/") }.getOrNull() ?: return playlists
        val cdlist = json.arr("cdlist").orEmptyObjects()
        val covers = mutableMapOf<Long, String>()
        for (item in cdlist) {
            val id = integerValue(item.opt("disstid") ?: item.opt("dissid"))
            val cover = normalizedQQImageURL(item.opt("logo")) ?: normalizedQQImageURL(item.opt("coveradurl"))
            if (cover != null) covers[id] = cover
        }
        return playlists.map { playlist ->
            val cover = covers[playlist.id]
            if (cover != null) playlist.copy(coverURL = cover) else playlist
        }
    }

    /// QQ 推荐歌单 musicu 兜底接口
    private suspend fun recommendPlaylistsFallback(limit: Int = 12): List<Playlist> {
        val payload = JSONObject()
            .put("comm", JSONObject().put("ct", 24).put("cv", 0))
            .put(
                "req_1",
                JSONObject()
                    .put("module", "music.srfDissInfo.RecommendPlaylist")
                    .put("method", "GetRecommendPlaylist")
                    .put("param", JSONObject().put("uin", 0).put("lastDissid", 0).put("songtype", 1).put("scene", 0)),
            )
        val json = musicu(payload)
        val list = nestedArray(json, listOf("req_1", "data", "v_playlist"))
        val playlists = mutableListOf<Playlist>()
        for (item in list) {
            val id = longOrNull(item.opt("tid")) ?: longOrNull(item.opt("id")) ?: continue
            val name = item.opt("title") as? String ?: ""
            val pic = normalizedQQImageURL(item.opt("cover")) ?: normalizedQQImageURL(item.opt("pic_url"))
            val songNum = intOrNull(item.opt("songnum")) ?: 0
            playlists.add(Playlist(id = id, name = name, coverURL = pic, trackCount = songNum, source = SongSource.QQ))
        }
        return playlists
    }

    /// QQ 歌单内歌曲（主通道 fcg_ucc_getcdinfo_byids_cp，Mineradio 逆向；兜底 musicu GetPlaylistDetail）
    suspend fun playlistSongs(listID: Long): List<Song> = playlistSongsUnlimited(listID = listID)

    private suspend fun playlistSongs(listID: Long, preferredCookie: String?, limit: Int): List<Song> {
        val pageSize = 300
        val targetLimit = if (limit > 0) limit else Int.MAX_VALUE
        if (listID == QQ_LIKED_PLAYLIST_ID) {
            return favoriteSongs(limit = limit)
        }
        val qqAuth = QQMusicAuth
        val cookie = preferredCookie ?: (if (qqAuth.isLoggedIn) qqAuth.playlistCookieHeader else "")
        val loginUins = (if (qqAuth.isLoggedIn) qqAuth.playlistIdentityCandidates else listOf("0")).distinct()
        for (loginUin in loginUins) {
            val detailURL =
                "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$listID&loginUin=$loginUin&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
            val detailJson = runCatching {
                getJson(detailURL, referer = "https://y.qq.com/n/yqq/playlist", cookie = cookie)
            }.getOrNull()
            val cdlist = detailJson?.arr("cdlist")
            val songlist = cdlist?.objAt(0)?.arr("songlist")
            if (songlist != null && songlist.length() > 0) {
                val songs = songlist.objects().take(limit).mapNotNull { item ->
                    // 部分接口返回会把歌曲包在 track_info 里，先解包再走统一解析
                    val raw = item.optMap("track_info") ?: item
                    song(raw)
                }
                if (songs.isNotEmpty()) return songs
            }
        }
        // 兜底：musicu GetPlaylistDetail
        for (loginUin in loginUins) {
            val payload = JSONObject()
                .put(
                    "comm",
                    JSONObject()
                        .put("ct", 24)
                        .put("cv", 0)
                        .put("uin", loginUin.toIntOrNull() ?: 0)
                        .put("g_tk", qqAuth.gtk)
                        .put("platform", "yqq"),
                )
                .put(
                    "req_1",
                    JSONObject()
                        .put("module", "music.playlist.PlayListDataServer")
                        .put("method", "GetPlaylistDetail")
                        .put(
                            "param",
                            JSONObject()
                                .put("id", listID)
                                .put("uin", loginUin.toIntOrNull() ?: 0)
                                .put("song_begin", 0)
                                .put("song_num", limit),
                        ),
                )
            val json = runCatching { musicu(payload, cookie = cookie) }.getOrNull() ?: continue
            val list = nestedArray(json, listOf("req_1", "data", "songlist"))
            val songs = list.take(limit).mapNotNull { item ->
                val raw = item.optMap("track_info") ?: item
                song(raw)
            }
            if (songs.isNotEmpty()) return songs
        }
        return emptyList()
    }

    /// 加载 QQ 普通歌单的全部歌曲。QQ 单次接口最多返回约 300 首，
    /// 这里按 song_begin 分页，直到接口返回不足一页或没有新歌曲。
    private suspend fun playlistSongsUnlimited(listID: Long): List<Song> {
        if (listID == QQ_LIKED_PLAYLIST_ID) {
            return favoriteSongs(limit = 0)
        }

        val pageSize = 300
        val firstPage = playlistSongs(listID = listID, preferredCookie = null, limit = pageSize)
        if (firstPage.size < pageSize) return firstPage

        val qqAuth = QQMusicAuth
        val cookie = if (qqAuth.isLoggedIn) qqAuth.playlistCookieHeader else ""
        val loginUin = qqAuth.playlistIdentityCandidates.firstOrNull() ?: "0"
        val songs = firstPage.toMutableList()
        val seen = firstPage.map { it.identityKey }.toMutableSet()
        var begin = firstPage.size

        while (true) {
            val detailURL =
                "https://c.y.qq.com/qzone/fcgi-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=$listID&loginUin=$loginUin&hostUin=0&song_begin=$begin&song_num=$pageSize&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
            val detailJson = runCatching {
                getJson(detailURL, referer = "https://y.qq.com/n/yqq/playlist", cookie = cookie)
            }.getOrNull() ?: break
            val cdlist = detailJson.arr("cdlist") ?: break
            val songlist = cdlist.objAt(0)?.arr("songlist") ?: break
            if (songlist.length() == 0) break

            val pageSongs = songlist.objects().mapNotNull { item ->
                val raw = item.optMap("track_info") ?: item
                song(raw)
            }
            val newSongs = pageSongs.filter { seen.add(it.identityKey) }
            songs.addAll(newSongs)
            if (songlist.length() < pageSize || newSongs.isEmpty()) break
            begin += songlist.length()
        }
        return songs
    }

    /// 歌单第一首歌曲封面（歌单封面缺失时的兜底；失败返回 nil）
    suspend fun firstSongCover(listID: Long): String? {
        val songs = playlistSongs(listID = listID, preferredCookie = null, limit = 1)
        return songs.firstOrNull()?.coverURL
    }

    /// QQ 歌手热门歌曲（分页加载，避免接口单页最多返回 30 首）
    suspend fun artistHotSongs(mid: String?, name: String, limit: Int = 120): List<Song> {
        if (mid.isNullOrEmpty()) return emptyList()
        val targetCount = maxOf(limit, 1)
        val pageSize = minOf(targetCount, 30)
        val songs = mutableListOf<Song>()
        val seen = mutableSetOf<String>()
        var begin = 0

        while (songs.size < targetCount) {
            val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_singer_track_cp.fcg?singer_mid=$mid&order=listen&begin=$begin&num=$pageSize&format=json"
            val json = runCatching { getJson(url) }.getOrNull() ?: break
            val data = json.optMap("data")
            val list = data?.arr("list").orEmptyObjects()
            if (list.isEmpty()) break

            var added = 0
            for (item in list) {
                val raw = item.optMap("musicData") ?: item
                val parsed = song(raw) ?: continue
                if (!seen.add(parsed.identityKey)) continue
                songs.add(parsed)
                added += 1
                if (songs.size >= targetCount) break
            }

            // 某些接口异常时会重复返回同一页，避免陷入无限请求。
            if (added <= 0) break
            begin += list.size
            if (list.size < pageSize) break
        }
        return songs.take(targetCount)
    }

    /// QQ 歌手专辑（fcg_v8_singer_album；接口异常时返回空）
    suspend fun artistAlbums(mid: String?, name: String, limit: Int = 30): List<Album> {
        if (mid.isNullOrEmpty()) return emptyList()
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_singer_album.fcg?singer_mid=$mid&order=time&begin=0&num=$limit&format=json"
        val json = runCatching { getJson(url) }.getOrNull() ?: return emptyList()
        val data = json.optMap("data")
        val list = data?.arr("list").orEmptyObjects()
        val albums = mutableListOf<Album>()
        for (item in list) {
            val albumName = item.opt("albumName") as? String ?: ""
            if (albumName.isEmpty()) continue
            val albumMid = item.opt("albumMID") as? String ?: ""
            albums.add(
                Album(
                    id = "qq-album-$albumMid-$albumName",
                    name = albumName,
                    artistName = name,
                    coverURL = normalizedQQImageURL(item.opt("pic"))
                        ?: photoURL(if (albumMid.isEmpty()) null else albumMid),
                    source = SongSource.QQ,
                    trackCount = intOrNull(item.opt("songnum")),
                ),
            )
        }
        return albums
    }

    /// 通用 QQ 歌曲解析（各接口字段略有差异，此处统一容错）
    private fun song(item: JSONObject): Song? {
        val mid = item.opt("songmid") as? String ?: (item.opt("mid") as? String ?: "")
        val sid = longOrNull(item.opt("songid")) ?: (longOrNull(item.opt("id")) ?: 0L)
        if (mid.isEmpty() && sid <= 0L) return null
        val singers = item.arr("singer")?.objects() ?: (item.arr("songer")?.objects() ?: emptyList())
        val artists = singers.mapNotNull { it.opt("name") as? String }.joinToString(" / ")
        val albumDict = item.optMap("album") ?: JSONObject()
        val albumName = albumDict.opt("name") as? String ?: (item.opt("albumname") as? String ?: "")
        val albumMid = albumDict.opt("mid") as? String ?: (item.opt("albummid") as? String ?: "")
        val interval = intOrNull(item.opt("interval")) ?: 0
        val pay = item.optMap("pay")
        val fee = intOrNull(item.opt("fee"))
            ?: intOrNull(pay?.opt("pay_play"))
            ?: intOrNull(pay?.opt("payplay"))
            ?: 0
        val file = item.optMap("file")
        val mediaMid = file?.opt("media_mid") as? String
            ?: item.opt("strMediaMid") as? String
            ?: item.opt("media_mid") as? String
        return Song(
            id = sid,
            name = item.opt("songname") as? String ?: (item.opt("name") as? String ?: ""),
            artists = artists,
            album = albumName,
            coverURL = photoURL(if (albumMid.isEmpty()) null else albumMid),
            duration = interval.toDouble(),
            source = SongSource.QQ,
            qqMid = if (mid.isEmpty()) null else mid,
            qqMediaMid = mediaMid,
            fee = fee,
        )
    }

    // MARK: - 工具

    private fun nestedArray(json: JSONObject, path: List<String>): List<JSONObject> {
        var current: Any? = json
        for (key in path) {
            val dict = current as? JSONObject ?: return emptyList()
            current = dict.opt(key)
        }
        return (current as? JSONArray)?.objects() ?: emptyList()
    }

    private fun JSONArray?.orEmptyObjects(): List<JSONObject> = this?.objects() ?: emptyList()

    private suspend fun getJsonWith(url: String, headers: Map<String, String>): JSONObject {
        // Swift 对非 200 抛网络错误、对解析失败抛解码错误，这里保持同一异常分类。
        val text = try {
            Http.getText(url, headers)
        } catch (e: BeansApiException.HttpStatus) {
            throw BeansApiException.Network(e.message ?: "network error")
        }
        return parseJsonOrThrow(text)
    }

    private fun logError(message: String) {
        Log.e(LOG_TAG, message)
    }
}

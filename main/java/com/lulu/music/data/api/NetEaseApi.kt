package com.lulu.music.data.api

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lulu.music.BeansApplication
import com.lulu.music.data.model.Album
import com.lulu.music.data.model.Artist
import com.lulu.music.data.model.NetEaseUser
import com.lulu.music.data.model.PlayRecordItem
import com.lulu.music.data.model.PlayRecordResult
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongComment
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.TopList
import com.lulu.music.data.net.BeansApiException
import com.lulu.music.data.net.Http
import com.lulu.music.data.net.NetEaseCrypto
import com.lulu.music.data.net.arr
import com.lulu.music.data.net.arrOrEmpty
import com.lulu.music.data.net.int
import com.lulu.music.data.net.long
import com.lulu.music.data.net.objects
import com.lulu.music.data.net.optMap
import com.lulu.music.data.net.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.math.ceil

object NetEaseApi {

    private const val TAG = "NetEaseApi"

    private const val domain = "https://music.163.com"
    private const val apiDomain = "https://interface.music.163.com"

    // 模拟 PC 客户端环境（与 NeteaseCloudMusicApi 一致）
    private const val os = "pc"
    private const val appver = "3.1.17.204416"
    private const val osver = "Microsoft-Windows-10-Professional-build-19045-64bit"
    private const val channel = "netease"

    private const val WEAPI_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0"
    private const val EAPI_USER_AGENT = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"

    private const val prefsName = "beans_netease"
    private const val cookiesKey = "beans.netease.cookies"

    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    private val nuid: String
    private val deviceId: String
    private val wnMcid: String
    private val storedCookies: MutableMap<String, String> = LinkedHashMap()

    private val prefs: SharedPreferences
        get() = BeansApplication.instance.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    init {
        nuid = randomHex(32)      // 64 位 hex
        deviceId = randomHex(26)  // 52 位 hex
        wnMcid = "${randomLowercase(6)}.${System.currentTimeMillis()}.01.0"
        loadStoredCookies()
    }

    // MARK: - 请求

    /**
     * 所有接口的统一入口。[uri] 以 "/api..." 开头，按 [crypto] 分别改写成
     * `music.163.com/weapi/...` 或 `interface.music.163.com/eapi/...`。
     * 失败时抛出 [BeansApiException]。
     */
    private suspend fun request(uri: String, payload: Map<String, Any?>, crypto: String): JSONObject {
        // 测试专用的离线闸门（默认关闭，见 Http.offlineMode）：在加密与建连之前直接抛出。
        Http.guardNetwork()
        val url: String
        val form: String
        val cookieHeader: String
        val userAgent: String
        val referer: String?

        if (crypto == "weapi") {
            url = domain + "/weapi" + uri.drop(4)
            val data = HashMap(payload)
            data["csrf_token"] = csrfToken
            val enc = NetEaseCrypto.weapi(data)
            form = "params=${Http.formEncode(enc["params"] ?: "")}&encSecKey=${Http.formEncode(enc["encSecKey"] ?: "")}"
            cookieHeader = weapiCookieHeader()
            userAgent = WEAPI_USER_AGENT
            referer = domain
        } else {
            url = apiDomain + "/eapi" + uri.drop(4)
            val header = eapiHeader()
            val data = HashMap(payload)
            data["e_r"] = false
            data["header"] = header
            val enc = NetEaseCrypto.eapi(data, path = uri)
            form = "params=${Http.formEncode(enc["params"] ?: "")}"
            cookieHeader = eapiCookieHeader(header)
            userAgent = EAPI_USER_AGENT
            referer = null
        }

        val parsedUrl = url.toHttpUrlOrNull() ?: throw BeansApiException.Unknown("请求地址无效")

        val builder = Request.Builder()
            .url(parsedUrl)
            .header("Cookie", cookieHeader)
            .header("User-Agent", userAgent)
            .post(form.toRequestBody(FORM))
        if (referer != null) builder.header("Referer", referer)
        val request = builder.build()

        return try {
            withContext(Dispatchers.IO) {
                Http.client.newCall(request).execute().use { response ->
                    storeCookies(response)
                    val text = response.body?.string().orEmpty()
                    if (response.code != 200) {
                        throw BeansApiException.HttpStatus(response.code, text.take(120))
                    }
                    try {
                        JSONObject(text.trim().removePrefix("\uFEFF"))
                    } catch (e: Exception) {
                        throw BeansApiException.Decoding(text.take(120))
                    }
                }
            }
        } catch (e: BeansApiException) {
            throw e
        } catch (e: IOException) {
            throw BeansApiException.Network(e.message ?: "网络连接失败，请检查网络")
        }
    }

    /**
     * 对应 Swift `addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)`：
     * 保留 ALPHA / DIGIT / `-._~` / sub-delims(`!$&'()*+,;=`) / `:@/?`，
     * 其余（含 `%`、`#`、空格、非 ASCII 的每个 UTF-8 字节）转义成大写十六进制。
     */
    private fun urlQueryEncode(value: String): String {
        val allowed = "-._~!\$&'()*+,;=:@/?"
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val c = code.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || allowed.indexOf(c) >= 0) {
                sb.append(c)
            } else {
                sb.append('%').append(String.format(Locale.US, "%02X", code))
            }
        }
        return sb.toString()
    }

    // MARK: - Cookie 构造

    private val csrfToken: String
        get() = cookieValue("__csrf")

    private val musicU: String
        get() = cookieValue("MUSIC_U")

    private fun cookieValue(name: String): String = storedCookies[name] ?: ""

    fun clearCookies() {
        storedCookies.clear()
        prefs.edit().remove(cookiesKey).apply()
    }

    /// 应用内网页登录：将 WebView 中 music.163.com 的 Cookie 合并进登录态并持久化
    fun importWebCookies(cookies: Map<String, String>) {
        var changed = false
        for ((key, value) in cookies) {
            if (value.isEmpty()) continue
            if (storedCookies[key] != value) {
                storedCookies[key] = value
                changed = true
            }
        }
        if (changed) persistCookies()
    }

    private fun loadStoredCookies() {
        val raw = prefs.getString(cookiesKey, null) ?: return
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return
        }
        for (key in json.keys()) {
            if (json.isNull(key)) continue
            storedCookies[key] = json.optString(key, "")
        }
    }

    private fun persistCookies() {
        prefs.edit().putString(cookiesKey, JSONObject(storedCookies).toString()).apply()
    }

    private fun storeCookies(response: Response) {
        var changed = false
        for (header in response.headers.values("Set-Cookie")) {
            if (header.isEmpty()) continue
            for ((name, value) in cookiePairs(header)) {
                if (value.isEmpty()) continue
                if (storedCookies[name] != value) {
                    storedCookies[name] = value
                    changed = true
                }
            }
        }
        if (changed) persistCookies()
    }

    /**
     * 从单行 Set-Cookie 中取出 `name=value` 对。多个 cookie 被合并成一行时
     * （逗号 + 下一个 `name=`）也能拆开；`Expires=Wed, 21 Oct ...` 里的逗号
     * 后面跟的是日期而不是 `name=`，不会误拆。
     */
    private fun cookiePairs(setCookie: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (part in COOKIE_SPLIT_REGEX.split(setCookie)) {
            val pair = part.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            out.add(name to value)
        }
        return out
    }

    private fun weapiCookieHeader(): String {
        val ts = System.currentTimeMillis().toString()
        val parts = ArrayList<String>()
        parts.add("__remember_me=true")
        parts.add("ntes_kaola_ad=1")
        parts.add("_ntes_nuid=$nuid")
        parts.add("_ntes_nnid=$nuid,$ts")
        parts.add("WNMCID=$wnMcid")
        parts.add("WEVNSM=1.0.0")
        parts.add("osver=$osver")
        parts.add("deviceId=$deviceId")
        parts.add("os=$os")
        parts.add("channel=$channel")
        parts.add("appver=$appver")
        parts.add("NMTID=${randomHex(16)}")
        if (musicU.isNotEmpty()) parts.add("MUSIC_U=$musicU")
        if (csrfToken.isNotEmpty()) parts.add("__csrf=$csrfToken")
        return parts.joinToString("; ")
    }

    private fun eapiHeader(): Map<String, String> {
        val ts = System.currentTimeMillis().toString()
        val buildver = ts.take(10)
        val header = LinkedHashMap<String, String>()
        header["osver"] = osver
        header["deviceId"] = deviceId
        header["os"] = os
        header["appver"] = appver
        header["versioncode"] = "140"
        header["mobilename"] = ""
        header["buildver"] = buildver
        header["resolution"] = "1920x1080"
        header["__csrf"] = csrfToken
        header["channel"] = channel
        header["requestId"] = "${ts}_${String.format(Locale.US, "%04d", (0..999).random())}"
        if (musicU.isNotEmpty()) header["MUSIC_U"] = musicU
        return header
    }

    private fun eapiCookieHeader(header: Map<String, String>): String =
        header.entries.joinToString("; ") { "${it.key}=${urlQueryEncode(it.value)}" }

    private fun randomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (0 until length).map { chars.random() }.joinToString("")
    }

    private fun randomLowercase(count: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz"
        return (0 until count).map { chars.random() }.joinToString("")
    }

    // MARK: - 登录（二维码，eapi 优先、weapi 降级）

    suspend fun qrKey(): String {
        return try {
            qrKeyEAPI()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            qrKeyWEAPI()
        }
    }

    private suspend fun qrKeyEAPI(): String {
        val json = request("/api/login/qrcode/unikey", mapOf("type" to 3), "eapi")
        val direct = json.stringOrNull("unikey")
        if (!direct.isNullOrEmpty()) return direct
        val nested = json.optMap("data")?.stringOrNull("unikey")
        if (!nested.isNullOrEmpty()) return nested
        throw BeansApiException.Unknown("获取二维码密钥失败")
    }

    private suspend fun qrKeyWEAPI(): String {
        val json = request("/api/login/qrcode/unikey", mapOf("type" to 3), "weapi")
        val direct = json.stringOrNull("unikey")
        if (!direct.isNullOrEmpty()) return direct
        val nested = json.optMap("data")?.stringOrNull("unikey")
        if (!nested.isNullOrEmpty()) return nested
        throw BeansApiException.Unknown("获取二维码密钥失败")
    }

    fun qrLoginURL(key: String): String = "https://music.163.com/login?codekey=$key"

    suspend fun qrCheck(key: String): Int {
        try {
            val json = request("/api/login/qrcode/client/login", mapOf("key" to key, "type" to 3), "eapi")
            val code = json.intOrNull("code") ?: -1
            if (code != -1) return code
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // eapi 不可用时降级 weapi
        }
        val json = request("/api/login/qrcode/client/login", mapOf("key" to key, "type" to 3), "weapi")
        return json.intOrNull("code") ?: -1
    }

    suspend fun account(): NetEaseUser {
        val json = request("/api/w/nuser/account/get", emptyMap(), "weapi")
        val profile = json.optMap("profile") ?: throw BeansApiException.Unknown("获取账号信息失败")
        return netEaseUserFromJson(profile) ?: throw BeansApiException.Unknown("获取账号信息失败")
    }

    // MARK: - 音乐库

    suspend fun userPlaylists(uid: Long): List<Playlist> {
        val json = request(
            "/api/user/playlist",
            mapOf("uid" to uid, "limit" to 1000, "offset" to 0, "includeVideo" to true),
            "weapi",
        )
        return json.arrOrEmpty("playlist").objects().mapNotNull { playlistFromJson(it) }
    }

    suspend fun playlistTracks(id: Long): List<Song> {
        val json = request("/api/v6/playlist/detail", mapOf("id" to id, "n" to 100000, "s" to 8), "eapi")
        val tracks = json.optMap("playlist")?.arrOrEmpty("tracks") ?: JSONArray()
        return tracks.objects().mapNotNull { songFromJson(it) }
    }

    suspend fun songURLs(ids: List<Long>, level: String = "standard"): Map<Long, String> {
        val idsString = "[" + ids.joinToString(",") + "]"
        val json = request(
            "/api/song/enhance/player/url/v1",
            mapOf("ids" to idsString, "level" to level, "encodeType" to "flac"),
            "eapi",
        )
        val data = json.arrOrEmpty("data")
        val result = HashMap<Long, String>()
        for (item in data.objects()) {
            val id = item.longOrNull("id") ?: continue
            val url = item.stringOrNull("url") ?: continue
            if (url.isEmpty()) continue
            result[id] = url
        }
        return result
    }

    /// 歌曲播放地址 + 试听标记（用于灰色/VIP 歌曲解锁判断）
    data class SongURLInfo(
        val url: String?,
        /// 存在 freeTrialInfo 表示仅返回试听片段（VIP 歌曲）
        val freeTrial: Boolean,
        /// 接口返回的付费标记（0 免费 / 1 VIP / 4 付费单曲 / 8 低音质免费）；null 表示接口未返回
        val fee: Int? = null,
        /// 接口返回的这段音频的实际时长（毫秒）：试听片段通常只有 30~60 秒
        val durationMs: Int? = null,
    )

    suspend fun songURLInfo(ids: List<Long>, level: String = "standard"): Map<Long, SongURLInfo> {
        val idsString = "[" + ids.joinToString(",") + "]"
        val json = request(
            "/api/song/enhance/player/url/v1",
            mapOf("ids" to idsString, "level" to level, "encodeType" to "flac"),
            "eapi",
        )
        val data = json.arrOrEmpty("data")
        val result = HashMap<Long, SongURLInfo>()
        for (item in data.objects()) {
            val id = item.longOrNull("id") ?: continue
            val rawURL = item.stringOrNull("url")
            val url = if (rawURL.isNullOrEmpty()) null else rawURL
            val freeTrial = item.optMap("freeTrialInfo") != null
            result[id] = SongURLInfo(
                url = url,
                freeTrial = freeTrial,
                fee = item.intOrNull("fee"),
                // `time` 是这段音频自身的时长（毫秒），试听片段会明显短于整首歌。
                durationMs = item.intOrNull("time"),
            )
        }
        return result
    }

    // MARK: - 歌词

    suspend fun lyric(id: Long): String? {
        val json = request("/api/song/lyric", mapOf("id" to id, "lv" to -1, "kv" to -1, "tv" to -1), "weapi")
        val text = json.optMap("lrc")?.stringOrNull("lyric")
        if (text.isNullOrEmpty()) return null
        return text
    }

    /// 歌词 + 翻译（tlyric），用于歌词翻译显示
    data class LyricResult(val lrc: String?, val tlyric: String?)

    suspend fun lyricWithTranslation(id: Long): LyricResult {
        val json = request("/api/song/lyric", mapOf("id" to id, "lv" to -1, "kv" to -1, "tv" to -1), "weapi")
        val lrc = json.optMap("lrc")?.stringOrNull("lyric")
        val tlyric = json.optMap("tlyric")?.stringOrNull("lyric")
        return LyricResult(lrc = lrc, tlyric = tlyric)
    }

    // MARK: - 评论

    data class SongCommentPage(
        val total: Int,
        val hot: List<SongComment>,
        var comments: List<SongComment>,
    )

    /// 歌曲评论（含热门评论）
    suspend fun songComments(id: Long, limit: Int = 30, offset: Int = 0): SongCommentPage {
        val json = request(
            "/api/v1/resource/comments/R_SO_4_$id",
            mapOf("rid" to id, "limit" to limit, "offset" to offset, "beforeTime" to 0),
            "weapi",
        )
        val total = json.int("total", 0)
        val hot = json.arrOrEmpty("hotComments").objects().mapNotNull { songCommentFromJson(it, true) }
        val comments = json.arrOrEmpty("comments").objects().mapNotNull { songCommentFromJson(it, false) }
        return SongCommentPage(total = total, hot = hot, comments = comments)
    }

    // MARK: - 搜索

    suspend fun search(keyword: String, limit: Int = 30, offset: Int = 0): List<Song> {
        val json = request(
            "/api/cloudsearch/pc",
            mapOf("s" to keyword, "type" to 1, "limit" to limit, "offset" to offset, "total" to true),
            "weapi",
        )
        val songs = json.optMap("result")?.arrOrEmpty("songs") ?: JSONArray()
        return songs.objects().mapNotNull { songFromJson(it) }
    }

    /// 搜索歌手（type=100）
    suspend fun searchArtists(keyword: String, limit: Int = 30): List<Artist> {
        val json = request(
            "/api/cloudsearch/pc",
            mapOf("s" to keyword, "type" to 100, "limit" to limit, "offset" to 0, "total" to true),
            "weapi",
        )
        val list = json.optMap("result")?.arrOrEmpty("artists") ?: JSONArray()
        val artists = ArrayList<Artist>()
        for (item in list.objects()) {
            val id = item.longOrNull("id") ?: continue
            val pic = item.stringOrNull("picUrl") ?: item.stringOrNull("img1v1Url") ?: ""
            artists.add(
                Artist(
                    id = "netease-$id",
                    name = item.str("name"),
                    coverURL = pic.ifEmpty { null },
                    source = SongSource.NET_EASE,
                )
            )
        }
        return artists
    }

    /// 搜索专辑（type=10）
    suspend fun searchAlbums(keyword: String, limit: Int = 30): List<Album> {
        val json = request(
            "/api/cloudsearch/pc",
            mapOf("s" to keyword, "type" to 10, "limit" to limit, "offset" to 0, "total" to true),
            "weapi",
        )
        val list = json.optMap("result")?.arrOrEmpty("albums") ?: JSONArray()
        val albums = ArrayList<Album>()
        for (item in list.objects()) {
            val id = item.longOrNull("id") ?: continue
            val artist = item.optMap("artist")?.stringOrNull("name") ?: ""
            val pic = item.str("picUrl")
            albums.add(
                Album(
                    id = "netease-$id",
                    name = item.str("name"),
                    artistName = artist,
                    coverURL = pic.ifEmpty { null },
                    source = SongSource.NET_EASE,
                    trackCount = item.intOrNull("size"),
                )
            )
        }
        return albums
    }

    // MARK: - 歌手主页

    /// 歌手热门歌曲（分页加载，避免接口单页最多返回 30 首）
    suspend fun artistHotSongs(artistID: Long, limit: Int = 120): List<Song> {
        val targetCount = maxOf(limit, 1)
        val pageSize = minOf(targetCount, 30)
        val songs = ArrayList<Song>()
        val seen = HashSet<String>()
        var offset = 0

        while (songs.size < targetCount) {
            val json = request(
                "/api/artist/songs",
                mapOf(
                    "id" to artistID,
                    "private_cloud" to true,
                    "work_type" to 1,
                    "order" to "hot",
                    "offset" to offset,
                    "limit" to pageSize,
                ),
                "weapi",
            )
            val list = json.arrOrEmpty("songs")
            if (list.length() == 0) break

            var added = 0
            for (item in list.objects()) {
                val song = songFromJson(item) ?: continue
                if (!seen.add(song.identityKey)) continue
                songs.add(song)
                added += 1
                if (songs.size >= targetCount) break
            }

            // 某些接口异常时会重复返回同一页，避免陷入无限请求。
            if (added <= 0) break
            offset += list.length()
            if (list.length() < pageSize) break
        }
        return songs.take(targetCount)
    }

    /// 歌手专辑
    suspend fun artistAlbums(artistID: Long, limit: Int = 50): List<Album> {
        val json = request("/api/artist/albums", mapOf("id" to artistID, "limit" to limit, "offset" to 0), "weapi")
        val list = json.arrOrEmpty("hotAlbums")
        val albums = ArrayList<Album>()
        for (item in list.objects()) {
            val id = item.longOrNull("id") ?: continue
            val artistName = item.optMap("artist")?.stringOrNull("name") ?: ""
            val pic = item.str("picUrl")
            albums.add(
                Album(
                    id = "netease-$id",
                    name = item.str("name"),
                    artistName = artistName,
                    coverURL = pic.ifEmpty { null },
                    source = SongSource.NET_EASE,
                    trackCount = item.intOrNull("size"),
                )
            )
        }
        return albums
    }

    /// 专辑内歌曲
    suspend fun albumSongs(albumID: Long): List<Song> {
        val json = request("/api/album", mapOf("id" to albumID), "weapi")
        return json.arrOrEmpty("songs").objects().mapNotNull { songFromJson(it) }
    }

    // MARK: - 收藏

    /// 听歌排行（type=1 最近一周 / type=0 所有时间）
    /// 本周取 100 顶；累计上限 1000，并优先从 size 字段读取真实总数（避免总是显示 100）
    suspend fun playRecord(uid: Long, type: Int): PlayRecordResult {
        val limit = if (type == 1) 100 else 1000
        val json = request("/api/v1/play/record", mapOf("uid" to uid, "type" to type, "limit" to limit), "weapi")
        val key = if (type == 1) "weekData" else "allData"
        val sizeKey = if (type == 1) "weekDataSize" else "allDataSize"
        val list = json.arrOrEmpty(key)
        val total = json.intOrNull(sizeKey) ?: list.length()
        val items = list.objects().mapNotNull { item ->
            val songJSON = item.optMap("song") ?: return@mapNotNull null
            val song = songFromJson(songJSON) ?: return@mapNotNull null
            PlayRecordItem(song = song, playCount = item.int("playCount", 0))
        }
        return PlayRecordResult(items = items, totalCount = maxOf(total, items.size))
    }

    suspend fun like(id: Long, liked: Boolean): Boolean {
        val time = System.currentTimeMillis().toString()
        val payloads: List<Map<String, Any?>> = listOf(
            mapOf("alg" to "itembased", "trackId" to id, "like" to liked, "time" to time),
            mapOf("trackId" to id, "like" to liked),
            mapOf("songId" to id, "like" to liked),
        )
        var lastCode = -1
        var lastError = ""
        for (payload in payloads) {
            try {
                val json = request("/api/song/like?t=$liked", payload, "weapi")
                val code = json.intOrNull("code") ?: -1
                lastCode = code
                if (code == 200) {
                    return true
                }
                lastError = json.stringOrNull("message") ?: json.stringOrNull("msg") ?: ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: ""
            }
        }
        Log.e(TAG, "网易云红心同步失败：id=$id liked=$liked code=$lastCode $lastError")
        return false
    }

    // MARK: - 发现

    suspend fun topLists(): List<TopList> {
        val json = request("/api/toplist/detail", emptyMap(), "weapi")
        return json.arrOrEmpty("list").objects().take(12).mapNotNull { topListFromJson(it) }
    }

    suspend fun dailyRecommend(): List<Song> {
        val json = request("/api/v3/discovery/recommend/songs", emptyMap(), "weapi")
        val songs = json.optMap("data")?.arrOrEmpty("dailySongs") ?: JSONArray()
        return songs.objects().mapNotNull { songFromJson(it) }
    }

    /// 歌单广场（对应网易云「发现音乐-歌单广场」，默认热门排序）
    suspend fun playlistSquare(cat: String = "全部", order: String = "hot", limit: Int = 12): List<Playlist> {
        val json = request(
            "/api/playlist/list",
            mapOf("cat" to cat, "order" to order, "limit" to limit, "offset" to 0, "total" to true),
            "weapi",
        )
        return json.arrOrEmpty("playlists").objects().mapNotNull { playlistFromJson(it) }
    }

    /// 精品歌单（官方歌单广场默认内容，网易云编辑精选；对应 music.163.com/discover/playlist 的「精品歌单」）
    suspend fun highQualityPlaylists(cat: String = "全部", limit: Int = 18): List<Playlist> {
        val json = request(
            "/api/playlist/highquality/list",
            mapOf("cat" to cat, "limit" to limit, "offset" to 0, "total" to true),
            "weapi",
        )
        return json.arrOrEmpty("playlists").objects().mapNotNull { playlistFromJson(it) }
    }

    /// 官方歌单分类（官网 discover/playlist 的分类标签；失败返回空数组，调用方回落内置分类）
    suspend fun playlistCatlist(): List<String> {
        val json = tryOrNull { request("/api/playlist/catlist", emptyMap(), "weapi") } ?: return emptyList()
        return json.arrOrEmpty("sub").objects().mapNotNull { it.stringOrNull("name") }
    }

    suspend fun personalizedPlaylists(limit: Int = 20): List<Playlist> {
        val json = request("/api/personalized/playlist", mapOf("limit" to limit, "n" to limit), "weapi")
        return json.arrOrEmpty("result").objects().mapNotNull { playlistFromPersonalizedJson(it) }
    }

    suspend fun recommendResource(): List<Playlist> {
        val json = request("/api/v1/discovery/recommend/resource", emptyMap(), "weapi")
        return json.arrOrEmpty("recommend").objects().mapNotNull { playlistFromJson(it) }
    }

    suspend fun recommendedHomePlaylists(loggedIn: Boolean, limit: Int = 18): List<Playlist> {
        if (loggedIn) {
            return coroutineScope {
                val recommend = async { tryOrNull { recommendResource() } }
                val personalized = async { tryOrNull { personalizedPlaylists(limit) } }
                val head = recommend.await() ?: emptyList()
                val tail = personalized.await() ?: emptyList()
                val seen = HashSet<Long>()
                (head + tail).filter { seen.add(it.id) }.take(limit)
            }
        }
        return tryOrNull { personalizedPlaylists(limit) } ?: emptyList()
    }

    // MARK: - 更多发现

    suspend fun hotSearch(): List<String> {
        val json = request("/api/search/hot", mapOf("type" to 1111), "weapi")
        val list = json.optMap("result")?.arrOrEmpty("hots") ?: JSONArray()
        return list.objects().mapNotNull { it.stringOrNull("first") }.take(10)
    }

    suspend fun newSongs(limit: Int = 10): List<Song> {
        val json = request("/api/personalized/newsong", mapOf("type" to 0, "limit" to limit), "weapi")
        val list = json.arrOrEmpty("result")
        val songs = ArrayList<Song>()
        for (item in list.objects()) {
            val song = (item.optMap("song")?.let { songFromJson(it) }) ?: songFromJson(item)
            if (song != null) songs.add(song)
        }
        return songs
    }

    suspend fun topPlaylists(limit: Int = 10): List<Playlist> {
        val json = request(
            "/api/top/playlist",
            mapOf("limit" to limit, "order" to "hot", "cat" to "全部", "total" to true),
            "weapi",
        )
        return json.arrOrEmpty("playlists").objects().mapNotNull { playlistFromJson(it) }
    }

    suspend fun simiSongs(id: Long): List<Song> {
        val json = request("/api/simi/song", mapOf("songid" to id), "weapi")
        return json.arrOrEmpty("songs").objects().mapNotNull { songFromJson(it) }
    }

    suspend fun intelligenceList(songID: Long, playlistID: Long, count: Int = 30): List<Song> {
        val json = request(
            "/api/playmode/intelligence/list",
            mapOf(
                "songId" to songID,
                "type" to "fromPlayOne",
                "playlistId" to playlistID,
                "startMusicId" to songID,
                "count" to count,
            ),
            "weapi",
        )
        val data = json.arrOrEmpty("data")
        return data.objects().mapNotNull { item ->
            val songInfo = item.optMap("songInfo")
            if (songInfo != null) songFromJson(songInfo) else songFromJson(item)
        }
    }

    /// 网易云私人漫游：一次预取 30 首，避免只有 12 首时很快播放完。
    suspend fun personalFM(limit: Int = 30): List<Song> {
        val songs = ArrayList<Song>()
        val seen = HashSet<String>()
        val batchCount = maxOf(1, ceil(limit / 3.0).toInt())
        for (i in 0 until batchCount) {
            val json = request("/api/v1/radio/get", emptyMap(), "weapi")
            val list = json.arrOrEmpty("data")
            val batch = list.objects().mapNotNull { songFromJson(it) }
            for (song in batch) {
                if (seen.add(song.identityKey)) {
                    songs.add(song)
                    if (songs.size >= limit) return songs
                }
            }
            if (batch.isEmpty()) break
        }
        return songs
    }

    // MARK: - 歌单编辑

    suspend fun createPlaylist(name: String): Long {
        val json = request("/api/playlist/create", mapOf("name" to name, "privacy" to 0), "weapi")
        return json.longOrNull("id") ?: throw BeansApiException.Unknown("创建歌单失败")
    }

    suspend fun addToPlaylist(playlistID: Long, songIDs: List<Long>): Boolean {
        val tracks = "[" + songIDs.joinToString(",") + "]"
        val json = request(
            "/api/playlist/manipulate/tracks",
            mapOf("op" to "add", "pid" to playlistID, "tracks" to tracks),
            "weapi",
        )
        return json.intOrNull("code") == 200
    }

    suspend fun removeFromPlaylist(playlistID: Long, songIDs: List<Long>): Boolean {
        val tracks = "[" + songIDs.joinToString(",") + "]"
        val json = request(
            "/api/playlist/manipulate/tracks",
            mapOf("op" to "del", "pid" to playlistID, "tracks" to tracks),
            "weapi",
        )
        return json.intOrNull("code") == 200
    }

    suspend fun deletePlaylist(id: Long): Boolean {
        val json = request("/api/playlist/remove", mapOf("ids" to "[" + id + "]"), "weapi")
        return json.intOrNull("code") == 200
    }

    // MARK: - 内部工具

    /** `try?` 语义：捕获接口错误并返回 null，但保留协程取消。 */
    private suspend fun <T> tryOrNull(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private val COOKIE_SPLIT_REGEX = Regex(",\\s*(?=[^=;,\\s]+=)")
}

// ---------------------------------------------------------------------------
// JSON → model 映射（对应 Swift 各个 `init?(json:)`）
// ---------------------------------------------------------------------------

/** 对应 Swift `json["key"] as? String`：缺失或类型不符返回 null（空串会被保留）。 */
private fun JSONObject.stringOrNull(key: String): String? {
    if (isNull(key)) return null
    return opt(key) as? String
}

/** 读取 32 位整数（非 id 字段：code / size / specialType / vipType / 毫秒时长等）。 */
private fun JSONObject.intOrNull(key: String): Int? {
    if (isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toInt()
        else -> null
    }
}

/**
 * 读取 64 位整数（Swift 的 Int 是 64 位）。网易云的 id 有时是 JSON 数字、有时是字符串，
 * 两种都要能解析；解析不出来返回 null（对应 Swift `as? Int` 失败）。
 */
private fun JSONObject.longOrNull(key: String): Long? {
    if (isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

private fun songFromJson(json: JSONObject): Song? {
    val id = json.longOrNull("id") ?: return null
    val artistsArray = json.arr("artists") ?: json.arr("ar") ?: JSONArray()
    val artists = artistsArray.objects().mapNotNull { it.stringOrNull("name") }.joinToString(" / ")

    val albumDict = json.optMap("album")
    val al = json.optMap("al")
    val album: String
    val coverURL: String?
    if (albumDict != null) {
        album = albumDict.str("name")
        val pic = albumDict.stringOrNull("picUrl") ?: albumDict.stringOrNull("blurPicUrl") ?: ""
        coverURL = pic.ifEmpty { null }
    } else if (al != null) {
        album = al.str("name")
        val pic = al.str("picUrl")
        coverURL = pic.ifEmpty { null }
    } else {
        album = ""
        coverURL = null
    }

    val ms = json.intOrNull("duration") ?: json.intOrNull("dt") ?: 0
    return Song(
        id = id,
        name = json.str("name"),
        artists = artists,
        album = album,
        coverURL = coverURL,
        duration = ms / 1000.0,
        source = SongSource.NET_EASE,
        fee = json.int("fee", 0),
    )
}

private fun playlistFromJson(json: JSONObject): Playlist? {
    val id = json.longOrNull("id") ?: return null
    val pic = json.stringOrNull("coverImgUrl") ?: json.stringOrNull("picUrl") ?: ""
    return Playlist(
        id = id,
        name = json.str("name"),
        coverURL = pic.ifEmpty { null },
        trackCount = json.intOrNull("trackCount") ?: 0,
        creatorName = json.optMap("creator")?.stringOrNull("nickname") ?: "",
        specialType = json.intOrNull("specialType") ?: 0,
        source = SongSource.NET_EASE,
    )
}

private fun playlistFromPersonalizedJson(json: JSONObject): Playlist? {
    val id = json.longOrNull("id") ?: return null
    val pic = json.str("picUrl")
    return Playlist(
        id = id,
        name = json.str("name"),
        coverURL = pic.ifEmpty { null },
        trackCount = 0,
        creatorName = "",
        specialType = json.intOrNull("specialType") ?: 0,
        source = SongSource.NET_EASE,
    )
}

private fun topListFromJson(json: JSONObject): TopList? {
    val id = json.longOrNull("id") ?: return null
    val pic = json.str("coverImgUrl")
    return TopList(
        id = id,
        name = json.str("name"),
        coverURL = pic.ifEmpty { null },
        updateFrequency = json.str("updateFrequency"),
    )
}

private fun netEaseUserFromJson(json: JSONObject): NetEaseUser? {
    val uid = json.longOrNull("userId") ?: json.longOrNull("id") ?: return null
    val pic = json.str("avatarUrl")
    return NetEaseUser(
        uid = uid,
        nickname = json.str("nickname"),
        avatarURL = pic.ifEmpty { null },
        vipType = json.intOrNull("vipType") ?: 0,
    )
}

private fun songCommentFromJson(json: JSONObject, isHot: Boolean): SongComment? {
    val id = json.longOrNull("commentId") ?: return null
    val user = json.optMap("user")
    val avatar = user?.str("avatarUrl") ?: ""
    return SongComment(
        id = id,
        content = json.str("content"),
        nickname = user?.stringOrNull("nickname") ?: "",
        avatarURL = avatar.ifEmpty { null },
        time = json.long("time", 0L),
        likedCount = json.int("likedCount", 0),
        isHot = isHot,
    )
}

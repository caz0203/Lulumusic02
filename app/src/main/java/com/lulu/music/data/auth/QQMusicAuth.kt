package com.lulu.music.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.lulu.music.BeansApplication
import com.lulu.music.data.net.BeansApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/// QQ 音乐扫码登录（逆向自 wp_MusicApi util/login_qq_scan.js，仅供学习交流）
/// 流程：ptqrshow 生成二维码 -> ptqrlogin 轮询扫码状态 -> check_sig 拿 skey/p_skey
///      -> graph.qq.com oauth2 authorize 换 code -> musicu.fcg QQConnectLogin 换 musickey
/// 登录后播放请求携带 qq.com 域 Cookie（p_skey / qqmusic_key），QQ 歌曲播放成功率显著提升。
object QQMusicAuth {

    // MARK: - 对外状态

    private val _loggedIn = MutableStateFlow(false)
    private val _nickname = MutableStateFlow("")
    private val _vipBadge = MutableStateFlow<String?>(null)

    /** Compose 观察用镜像，与下面的普通属性读的是同一份数据。 */
    val loggedInFlow: StateFlow<Boolean> = _loggedIn.asStateFlow()
    val nicknameFlow: StateFlow<String> = _nickname.asStateFlow()
    val vipBadgeFlow: StateFlow<String?> = _vipBadge.asStateFlow()

    val isLoggedIn: Boolean get() = _loggedIn.value
    val nickname: String get() = _nickname.value

    /** QQ 音乐会员标识：nil 无 / "VIP" / "SVIP"（登录后尽力拉取，失败不阻塞） */
    val vipBadge: String? get() = _vipBadge.value

    // MARK: - 持久化

    private const val PREFS_NAME = "beans_auth"
    private const val COOKIE_KEY = "beans.qqmusic.cookie.v1"
    private const val NICK_KEY = "beans.qqmusic.nickname.v1"
    private const val VIP_KEY = "beans.qqmusic.vip.v1"

    /** 状态与 cookies 的写入都在此锁内完成，保证两者始终是同一份快照。 */
    private val lock = Any()

    /** 内存中的 Cookie 快照；每次变更整体替换，读方法无需加锁。 */
    @Volatile
    private var cookies: Map<String, String> = emptyMap()

    private var qrsig = ""

    private val prefs: SharedPreferences
        get() = BeansApplication.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 表单 / JSON 请求体类型（`toRequestBody` 需要显式 MediaType）。 */
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()
    private val jsonMediaType = "application/json".toMediaType()

    init {
        val saved = loadCookies()
        if (saved.isNotEmpty()) {
            synchronized(lock) {
                cookies = saved
                _loggedIn.value = true
                _nickname.value = readString(NICK_KEY) ?: ""
                _vipBadge.value = readString(VIP_KEY)?.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** 与 iOS `defaults.dictionary(forKey:)` 对应：字典以 JSON 字符串保存，损坏时按未登录处理。 */
    private fun loadCookies(): Map<String, String> {
        val raw = readString(COOKIE_KEY) ?: return emptyMap()
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        for (key in json.keys()) {
            if (json.isNull(key)) continue
            out[key] = json.optString(key, "")
        }
        return out
    }

    private fun persistCookies() {
        val json = JSONObject()
        for ((key, value) in cookies) json.put(key, value)
        prefs.edit().putString(COOKIE_KEY, json.toString()).apply()
    }

    private fun readString(key: String): String? = try {
        prefs.getString(key, null)
    } catch (e: Exception) {
        null
    }

    // MARK: - 登录状态

    /// 登录账号 ID。QQ 登录通常使用 uin，微信登录通常使用 wxuin。
    val uin: String
        get() = normalizedUIN(accountID(cookies))

    /// 原始账号 ID（保留 o 前缀，歌单增删等写操作接口需要）。
    /// 微信网页登录没有 uin 时回退到 wxuin。
    val rawUin: String
        get() = accountID(cookies)

    val isWeChatLogin: Boolean
        get() = hasUsableAccountID(cookies["wxuin"]) || !(cookies["wxopenid"] ?: "").isEmpty()

    /// QQ 歌单接口使用的 QQ 账号 ID。微信登录通常没有可用的 QQ uin，
    /// 此时返回 0，让官方接口根据 wxuin / wxopenid Cookie 识别账号。
    val playlistUin: String
        get() {
            if (isWeChatLogin &&
                !hasUsableAccountID(cookies["p_uin"]) &&
                !hasUsableAccountID(cookies["pt2gguin"])
            ) {
                return normalizedUIN(cookies["wxuin"] ?: "0")
            }
            for (key in listOf("uin", "p_uin", "pt2gguin")) {
                val value = cookies[key] ?: continue
                if (!hasUsableAccountID(value)) continue
                return normalizedUIN(value)
            }
            return "0"
        }

    /// QQ/微信登录态可用于歌单接口的身份候选。微信登录时 wxuin 也必须尝试；
    /// 部分账号的歌单/喜欢接口不会仅凭 uin=0 + Cookie 返回数据。
    val playlistIdentityCandidates: List<String>
        get() {
            val rawValues = listOf(
                cookies["p_uin"],
                cookies["pt2gguin"],
                cookies["uin"],
                cookies["wxuin"],
                playlistUin,
                "0",
            )
            val result = ArrayList<String>()
            for (value in rawValues) {
                if (value == null) continue
                if (!hasUsableAccountID(value) && value != "0") continue
                val normalized = normalizedUIN(value)
                if (!result.contains(normalized)) result.add(normalized)
            }
            return result
        }

    /// g_tk（写操作接口签名；QQ/微信登录均优先使用音乐域凭证）。
    val gtk: Int
        get() {
            val key = cookies["qqmusic_key"]
                ?: cookies["qm_keyst"]
                ?: cookies["wxskey"]
                ?: cookies["p_skey"]
                ?: cookies["skey"]
                ?: ""
            return if (key.isEmpty()) 5381 else hash5381(key)
        }

    /// 播放接口 authst。QQ vkey 优先识别音乐域凭证，p_skey 仅作旧登录态兜底。
    val loginKey: String
        get() = cookies["qm_keyst"] ?: cookies["qqmusic_key"] ?: cookies["music_key"]
            ?: cookies["wxskey"] ?: cookies["musickey"] ?: cookies["p_skey"] ?: ""

    /// 发给 u.y.qq.com 的 Cookie 串（含 qqmusic_key 时 VIP 歌曲播放成功率最高）
    val cookieHeader: String
        get() = makeCookieHeader(includeCompatibilityUIN = true)

    /// 不注入兼容用的 uin=wxuin，给微信登录的歌单接口使用。
    /// 部分 QQ 接口会优先读取 uin，误把 wxuin 当成 QQ uin 后会返回空歌单。
    val playlistCookieHeader: String
        get() = makeCookieHeader(includeCompatibilityUIN = false)

    private fun makeCookieHeader(includeCompatibilityUIN: Boolean): String {
        val current = cookies
        val order = listOf(
            "uin", "wxuin", "p_uin", "wxopenid",
            "qm_keyst", "qqmusic_key", "music_key", "wxskey", "wx_skey",
            "musickey", "p_skey", "skey", "pt4_token",
        )
        val pairs = ArrayList<String>()
        for (key in order) {
            val value = current[key] ?: continue
            if (value.isEmpty()) continue
            pairs.add("$key=$value")
        }
        // 旧版保存的微信登录态可能只有 wxuin；部分 QQ 接口仍只读取 uin。
        if (includeCompatibilityUIN && !hasUsableAccountID(current["uin"])) {
            val wxuin = current["wxuin"]
            if (wxuin != null && wxuin.isNotEmpty()) {
                pairs.add(0, "uin=$wxuin")
            }
        }
        return pairs.joinToString("; ")
    }

    fun logout() {
        synchronized(lock) {
            cookies = emptyMap()
            qrsig = ""
            _loggedIn.value = false
            _nickname.value = ""
            _vipBadge.value = null
        }
        prefs.edit()
            .remove(COOKIE_KEY)
            .remove(NICK_KEY)
            .remove(VIP_KEY)
            .apply()
    }

    // MARK: - 网页登录 / Cookie 导入

    /// 网页登录（WebView 读取）或手动粘贴 Cookie 导入登录态
    fun importCookies(dict: Map<String, String>, nickname: String?) {
        if (dict.isEmpty()) return
        val resolvedNickname = nickname ?: fallbackNickname(dict)
        synchronized(lock) {
            cookies = LinkedHashMap(dict)
            _loggedIn.value = true
            _nickname.value = resolvedNickname
        }
        persistCookies()
        prefs.edit().putString(NICK_KEY, resolvedNickname).apply()
        // 登录成功后异步刷新会员标识与真实昵称（失败静默降级）
        appScope().launchBestEffort { fetchVIPStatus() }
        appScope().launchBestEffort { fetchProfile() }
    }

    /// 返回网页登录 Cookie 缺少哪一部分，便于区分 QQ 登录和微信登录失败原因。
    fun loginValidationMessage(dict: Map<String, String>): String? {
        if (!hasUsableAccountID(accountID(dict))) {
            return "未读取到 QQ/微信账号标识，请确认网页登录已经完成"
        }
        val credentialKeys = listOf(
            "p_skey", "skey", "qqmusic_key", "qm_keyst",
            "music_key", "wxskey", "wx_skey", "musickey",
        )
        val hasCredential = credentialKeys.any { !(dict[it] ?: "").isEmpty() }
        if (!hasCredential) {
            return "已读取到账号，但缺少 QQ 音乐登录凭证，请在网页中重新登录后再同步"
        }
        return null
    }

    /// Cookie 是否包含有效登录态，兼容 QQ 登录的 uin 和微信登录的 wxuin。
    fun hasValidLogin(dict: Map<String, String>): Boolean = loginValidationMessage(dict) == null

    /// 解析浏览器复制出来的完整 Cookie 字符串："a=b; c=d"
    fun parseCookieHeader(header: String): Map<String, String> {
        val dict = LinkedHashMap<String, String>()
        for (part in header.split(";")) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val key = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                dict[key] = value
            }
        }
        return dict
    }

    /// QQ/微信账号 ID 转显示昵称；优先 ptlogin 下发的 ptnick_* / nick Cookie。
    fun fallbackNickname(dict: Map<String, String>): String {
        val ptNickKey = dict.keys.firstOrNull { it.startsWith("ptnick") }
        if (ptNickKey != null) {
            val raw = dict[ptNickKey]
            if (!raw.isNullOrEmpty()) {
                return percentDecode(raw) ?: raw
            }
        }
        val nick = dict["nick"]
        if (!nick.isNullOrEmpty()) return nick
        val clean = normalizedUIN(accountID(dict))
        return if (clean.isEmpty()) "QQ音乐用户" else "QQ音乐用户 $clean"
    }

    private fun accountID(from: Map<String, String>): String {
        for (key in listOf("uin", "wxuin", "pt2gguin")) {
            val value = from[key] ?: continue
            if (!hasUsableAccountID(value)) continue
            return value.trim()
        }
        return "0"
    }

    private fun hasUsableAccountID(raw: String?): Boolean {
        val value = raw?.trim() ?: return false
        if (value.isEmpty()) return false
        return value != "0" && value != "o0"
    }

    private fun normalizedUIN(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        return if (value.startsWith("o")) value.substring(1) else value
    }

    // MARK: - 扫码登录

    sealed class ScanState {
        object Waiting : ScanState()
        object Scanned : ScanState()
        data class Success(val nickname: String) : ScanState()
        object Expired : ScanState()
        data class Error(val message: String) : ScanState()
    }

    /// 获取二维码图片（PNG），并保存 qrsig 供轮询使用
    suspend fun fetchQRCode(): ByteArray {
        synchronized(lock) { qrsig = "" }
        val t = String.format(Locale.US, "%.6f", Math.random())
        val url = "https://ssl.ptlogin2.qq.com/ptqrshow" +
            "?appid=716027609&e=2&l=M&s=3&d=72&v=4" +
            "&t=$t&daid=383&pt_3rd_aid=100497308"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://xui.ptlogin2.qq.com/")
            .get()
            .build()
        val exchange = authExchange(request)
        storeCookies(exchange.cookies)
        val qr = cookies["qrsig"] ?: ""
        if (qr.isEmpty()) {
            throw BeansApiException.Unknown("获取 QQ 二维码失败，请检查网络后重试")
        }
        synchronized(lock) { qrsig = qr }
        return exchange.bytes
    }

    /// 单次轮询扫码状态（调用方以 3 秒间隔重复调用）
    suspend fun poll(): ScanState {
        val sig = qrsig
        if (sig.isEmpty()) return ScanState.Expired
        val url = "https://ssl.ptlogin2.qq.com/ptqrlogin" +
            "?u1=https://graph.qq.com/oauth2.0/login_jump" +
            "&ptqrtoken=${hash33(sig)}" +
            "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052" +
            "&action=0-0-${System.currentTimeMillis()}" +
            "&js_ver=22080914&js_type=1&login_sig=&pt_uistyle=40" +
            "&aid=716027609&daid=383&pt_3rd_aid=100497308" +
            "&o1vId=49283d5cbb01a744d46314da4608d929"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://xui.ptlogin2.qq.com/")
            .header("Cookie", "qrsig=$sig")
            .get()
            .build()
        val exchange = authExchange(request)
        storeCookies(exchange.cookies)
        val parsed = parsePTUI(exchange.text) ?: return ScanState.Error("QQ 登录接口异常，请重试")
        return when (parsed.code) {
            "0" -> {
                val redirect = parsed.url ?: return ScanState.Error("登录成功但凭证获取失败")
                try {
                    completeOAuth(redirect)
                } catch (e: BeansApiException) {
                    return ScanState.Error(e.message ?: "QQ 授权失败，请重新扫码")
                }
                val resolvedNickname = parsed.nickname
                synchronized(lock) {
                    _nickname.value = resolvedNickname
                    _loggedIn.value = true
                }
                persistCookies()
                prefs.edit().putString(NICK_KEY, resolvedNickname).apply()
                appScope().launchBestEffort { fetchVIPStatus() }
                appScope().launchBestEffort { fetchProfile() }
                ScanState.Success(resolvedNickname)
            }
            "65", "68" -> ScanState.Expired
            "67" -> ScanState.Scanned
            "66" -> ScanState.Waiting
            else -> ScanState.Waiting
        }
    }

    // MARK: - 授权换 musickey

    private suspend fun completeOAuth(redirectURL: String) {
        // check_sig 跳转链必须携带 qrsig（ptlogin2.qq.com 域），否则校验失败、拿不到 skey/p_skey
        var loginCookie = loginCookieWithQrsig()
        // 1. 依次访问 check_sig 跳转链，收集 skey / p_skey（最多 6 跳）
        var current = redirectURL
        for (i in 0 until 6) {
            val request = try {
                Request.Builder()
                    .url(current)
                    .header("User-Agent", UA)
                    .header("Referer", "https://xui.ptlogin2.qq.com/")
                    .header("Cookie", loginCookie)
                    .get()
                    .build()
            } catch (e: IllegalArgumentException) {
                break
            }
            // 不跟随 302：必须自己从 Location 继续走跳转链（对应 iOS 的 NoRedirectDelegate）
            val exchange = authExchange(request, followRedirects = false)
            storeCookies(exchange.cookies)
            loginCookie = loginCookieWithQrsig()
            val location = exchange.location
            if (location.isEmpty()) break
            current = resolveLocation(location, current) ?: break
        }

        // 2. graph.qq.com oauth2 authorize 换 code
        loginCookie = cookieHeader
        val authorizeToken = cookies["qqmusic_key"] ?: cookies["p_skey"] ?: cookies["skey"] ?: ""
        val gtk = hash5381(authorizeToken)
        val fields = linkedMapOf(
            "response_type" to "code",
            "client_id" to "100497308",
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
            "scope" to "all",
            "state" to "state",
            "switch" to "",
            "from_ptlogin" to "1",
            "src" to "1",
            "update_auth" to "1",
            "openapi" to "80901010_1030",
            "g_tk" to "$gtk",
            "auth_time" to "${System.currentTimeMillis()}",
            "ui" to "DFEC5395-9E69-4D3E-96A6-300BB770874D",
        )
        val authRequest = Request.Builder()
            .url("https://graph.qq.com/oauth2.0/authorize")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", UA)
            .header("Referer", "https://graph.qq.com/")
            .header("Cookie", loginCookie)
            .post(queryFormBody(fields).toRequestBody(formMediaType))
            .build()
        val authResult = authExchange(authRequest, followRedirects = false)
        storeCookies(authResult.cookies)
        val authLocation = authResult.location
        val code = extractCode(authLocation)
        if (authLocation.isEmpty() || code == null) {
            throw BeansApiException.Unknown("QQ 授权失败，请重新扫码")
        }

        // 3. musicu.fcg QQConnectLogin 换 musickey（登录态 Cookie 持久化）
        loginCookie = cookieHeader
        val body = "{\"comm\":{\"g_tk\":5381,\"platform\":\"yqq\",\"ct\":24,\"cv\":0}," +
            "\"req\":{\"module\":\"QQConnectLogin.LoginServer\",\"method\":\"QQLogin\"," +
            "\"param\":{\"code\":\"$code\"}}}"
        val loginRequest = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .header("Content-Type", "application/json")
            .header("User-Agent", UA)
            .header("Referer", "https://y.qq.com/")
            .header("Cookie", loginCookie)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val loginExchange = authExchange(loginRequest)
        storeCookies(loginExchange.cookies)
        // 部分 QQ 登录响应把音乐域凭证放在 JSON 中而不是 Set-Cookie，必须显式持久化。
        val data = loginExchange.asJsonObject()
            ?.optJSONObject("req")
            ?.optJSONObject("data")
        if (data != null) {
            val next = LinkedHashMap(cookies)
            var changed = false
            val musicKey = stringOrNull(data, "musickey")
            if (!musicKey.isNullOrEmpty()) {
                next["musickey"] = musicKey
                next["qm_keyst"] = musicKey
                next["qqmusic_key"] = musicKey
                changed = true
            }
            val numericID = intOrNull(data, "musicid")
            if (numericID != null && numericID > 0) {
                next["uin"] = "$numericID"
                changed = true
            } else {
                val textID = stringOrNull(data, "musicid")
                if (!textID.isNullOrEmpty()) {
                    next["uin"] = textID
                    changed = true
                }
            }
            if (changed) {
                synchronized(lock) { cookies = next }
            }
        }
    }

    private fun loginCookieWithQrsig(): String {
        val header = cookieHeader
        val sig = qrsig
        if (sig.isEmpty()) return header
        return "qrsig=$sig" + if (header.isEmpty()) "" else "; $header"
    }

    // MARK: - 会员状态

    /// 拉取 QQ 音乐会员标识（逆向自 musicu.fcg music.member.getVipInfo，仅供学习交流）。
    /// 携带登录 Cookie 请求，接口字段各家实现略有差异，这里做递归宽松解析：
    /// - 命中 svip 相关字段且数值 > 0 -> SVIP
    /// - 命中 vipType / vip_type 且数值 > 0 -> VIP
    /// - 请求失败或字段缺失 -> nil（不阻塞登录，也不弹错误）
    suspend fun fetchVIPStatus() {
        val account = uin
        if (!isLoggedIn || account.isEmpty() || account == "0") {
            if (vipBadge != null) {
                setVipBadge(null)
                prefs.edit().remove(VIP_KEY).apply()
            }
            return
        }
        try {
            val payload = JSONObject()
                .put("comm", JSONObject().put("ct", 24).put("cv", 0).put("uin", account))
                .put(
                    "req_0",
                    JSONObject()
                        .put("module", "music.member.getVipInfo")
                        .put("method", "get_vip_info")
                        .put("param", JSONObject().put("uin", account)),
                )
            val json = musicu(payload)
            val badge = parseVIPBadge(json)
            if (badge != vipBadge) {
                setVipBadge(badge)
                prefs.edit().putString(VIP_KEY, badge ?: "").apply()
            }
        } catch (e: CancellationException) {
            // 协程取消必须继续向上传播，不能当成接口波动吞掉
            throw e
        } catch (e: BeansApiException) {
            // 尽力而为：接口波动不影响登录与播放
        }
    }

    /// 递归扫描响应 JSON 中的会员字段（兼容不同返回结构）
    private fun parseVIPBadge(json: JSONObject): String? {
        var vipLevel = 0
        var svipFlag = false
        walkJson(json) { key, value ->
            val lower = key.lowercase(Locale.US)
            if (lower.contains("svip")) {
                val n = numericInt(value)
                if (n != null && n > 0) svipFlag = true
                if (value is Boolean && value) svipFlag = true
            } else if (lower == "viptype" || lower == "vip_type") {
                val n = numericInt(value)
                if (n != null && n > 0) vipLevel = maxOf(vipLevel, n)
            }
        }
        if (svipFlag || vipLevel >= 11) return "SVIP"
        if (vipLevel > 0) return "VIP"
        return null
    }

    /// 拉取 QQ 音乐真实昵称（fcg_get_profile_homepage；扫码/网页/Cookie 登录后调用，失败静默保留旧昵称）
    suspend fun fetchProfile() {
        val account = uin
        if (!isLoggedIn || account.isEmpty() || account == "0") return
        try {
            val urlString = "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg" +
                "?cid=205360838&userid=$account&reqfrom=1&g_tk=5381&loginUin=$account" +
                "&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0" +
                "&platform=yqq.json&needNewCode=0"
            val request = Request.Builder()
                .url(urlString)
                .header("User-Agent", UA)
                .header("Referer", "https://y.qq.com/")
                .header("Cookie", cookieHeader)
                .get()
                .build()
            val exchange = authExchange(request)
            val obj = exchange.asJsonObject() ?: return
            val code = intOrNull(obj, "code") ?: -1
            // code 1000 = 资料接口不可用（Mineradio 排障记录），不视为未登录，改用 Cookie 兜底
            if (code != 0 && code != 1000) return
            val nick = extractNickname(obj)
            if (!nick.isNullOrEmpty() && nick != nickname) {
                setNickname(nick)
                prefs.edit().putString(NICK_KEY, nick).apply()
                return
            }
            // 资料接口拿不到昵称时，用 ptlogin 下发的 ptnick_* Cookie 兜底
            val ptNickKey = cookies.keys.firstOrNull { it.startsWith("ptnick") }
            if (ptNickKey != null) {
                val raw = cookies[ptNickKey]
                if (!raw.isNullOrEmpty()) {
                    val fallbackNick = percentDecode(raw) ?: raw
                    if (fallbackNick != nickname) {
                        setNickname(fallbackNick)
                        prefs.edit().putString(NICK_KEY, fallbackNick).apply()
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消必须继续向上传播，不能当成接口波动吞掉
            throw e
        } catch (e: BeansApiException) {
            // 尽力而为：接口波动不影响登录
        }
    }

    /// 从个人主页响应中提取昵称（data.mymusic.info.nick 优先，其次递归找 nick/nickname）
    private fun extractNickname(json: JSONObject): String? {
        val info = json.optJSONObject("data")
            ?.optJSONObject("mymusic")
            ?.optJSONObject("info")
        val direct = info?.let { stringOrNull(it, "nick") }
        if (!direct.isNullOrEmpty()) return direct

        var found: String? = null
        walkJsonValues(json) { value ->
            if (found != null) return@walkJsonValues
            if (value is JSONObject) {
                val nickValue = stringOrNull(value, "nick")
                if (!nickValue.isNullOrEmpty() && !nickValue.contains("QQ音乐用户")) {
                    found = nickValue
                    return@walkJsonValues
                }
                val nicknameValue = stringOrNull(value, "nickname")
                if (!nicknameValue.isNullOrEmpty() && !nicknameValue.contains("QQ音乐用户")) {
                    found = nicknameValue
                }
            }
        }
        return found
    }

    /// musicu.fcg 统一 POST（携带当前登录 Cookie）
    private suspend fun musicu(payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .header("Content-Type", "application/json")
            .header("User-Agent", UA)
            .header("Referer", "https://y.qq.com/")
            .header("Cookie", cookieHeader)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        val exchange = authExchange(request)
        return exchange.asJsonObject() ?: throw BeansApiException.Network("QQ 音乐接口响应异常")
    }

    // MARK: - 工具

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 登录之后拉取会员/昵称的尽力而为任务，等价于 Swift 里的 `Task { await ... }`。 */
    private fun appScope(): CoroutineScope = BeansApplication.instance.appScope

    private fun storeCookies(incoming: Map<String, String>) {
        if (incoming.isEmpty()) return
        synchronized(lock) {
            val next = LinkedHashMap(cookies)
            for ((name, value) in incoming) {
                if (name.isEmpty()) continue
                next[name] = value
            }
            cookies = next
        }
    }

    private fun setNickname(value: String) {
        synchronized(lock) { _nickname.value = value }
    }

    private fun setVipBadge(value: String?) {
        synchronized(lock) { _vipBadge.value = value }
    }

    /// 解析 ptuiCB('66','','0','','') 形式的 JSONP 回调
    /// 兼容两种历史格式：code,url,'0',msg,nick 与 code,'0',url,msg,nick
    private fun parsePTUI(text: String): PTUIResult? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val inner = text.substring(open + 1, close)
        val parts = inner.split(",").map { part ->
            var start = 0
            var end = part.length
            while (start < end && (part[start] == '\'' || part[start] == '"' || part[start] == ' ')) start++
            while (end > start && (part[end - 1] == '\'' || part[end - 1] == '"' || part[end - 1] == ' ')) end--
            part.substring(start, end)
        }
        if (parts.size < 5) return null
        val url = if (parts[1].startsWith("http")) {
            parts[1]
        } else {
            if (parts.size > 2) parts[2] else ""
        }
        return PTUIResult(code = parts[0], url = if (url.isEmpty()) null else url, nickname = parts[4])
    }

    private fun extractCode(urlString: String): String? {
        val query = urlString.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split("&")) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == "code") return pair.substring(eq + 1)
        }
        return null
    }

    /** 对应 iOS `URL(string:relativeTo:)`：绝对地址直接用，相对地址按当前地址解析。 */
    private fun resolveLocation(location: String, base: String): String? {
        if (location.isEmpty()) return null
        return try {
            val absolute = URI(location)
            if (absolute.scheme != null) absolute.toString() else URI(base).resolve(location)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private data class PTUIResult(val code: String, val url: String?, val nickname: String)

    /**
     * 对应 JS: e += (e << 5) + t.charCodeAt(n); return 2147483647 & e（e 初始 0）—— ptqrtoken 用
     * JS 的 e 是 IEEE-754 double（累加不截断 32 位），必须用 Double 精确模拟，
     * 否则长 qrsig 在 Int64 中溢出导致 ptqrtoken 错误、登录接口返回异常。
     */
    fun hash33(t: String): Int {
        var e = 0.0
        for (unit in t.toCharArray()) {
            e = e + toInt32Shift(e).toDouble() + unit.code.toDouble()
        }
        return toInt32(e) and 0x7FFF_FFFF
    }

    /// 对应 wp_MusicApi 的 f()：n 初始 5381，key 取 skey/qqmusic_key —— oauth g_tk 用
    fun hash5381(t: String): Int {
        var e = 5381.0
        for (unit in t.toCharArray()) {
            e = e + toInt32Shift(e).toDouble() + unit.code.toDouble()
        }
        return toInt32(e) and 0x7FFF_FFFF
    }

    /// JS ToInt32(d) << 5（32 位有符号截断）
    private fun toInt32Shift(d: Double): Int = toInt32(d) shl 5

    /// JS ToInt32(d)：对 2^32 取模后转有符号 32 位
    private fun toInt32(d: Double): Int {
        var r = d % 4294967296.0
        if (r < 0) r += 4294967296.0
        return (r.toLong() and 0xFFFF_FFFFL).toInt()
    }

    /** 对应 Swift `URLComponents.query` 的表单编码（空格用 `+`）。 */
    private fun queryFormBody(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (key, value) ->
            "${formComponentEncode(key)}=${formComponentEncode(value)}"
        }

    private fun formComponentEncode(value: String): String {
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            when {
                c.isLetterOrDigit() && c.code < 128 -> sb.append(c)
                c == '-' || c == '.' || c == '_' || c == '~' -> sb.append(c)
                c == ' ' -> sb.append('+')
                else -> sb.append('%').append(String.format(Locale.US, "%02X", byte.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    /** `raw.removingPercentEncoding`：非法转义序列返回 null（保持原串）。 */
    private fun percentDecode(raw: String): String? {
        if (raw.indexOf('%') < 0) return raw
        var i = 0
        val bytes = ArrayList<Byte>()
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                if (i + 2 >= raw.length) return null
                val value = raw.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.add(value.toByte())
                i += 3
            } else {
                bytes.add(c.code.toByte())
                i += 1
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** 对应 Swift `json["k"] as? String`：缺失或类型不符返回 null。 */
    private fun stringOrNull(json: JSONObject, key: String): String? {
        if (json.isNull(key)) return null
        return json.opt(key) as? String
    }

    /** 对应 Swift `json["k"] as? Int`。 */
    private fun intOrNull(json: JSONObject, key: String): Int? {
        if (json.isNull(key)) return null
        return numericInt(json.opt(key))
    }

    private fun numericInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        else -> null
    }

    /** 递归遍历所有对象成员（键 + 值），对应 Swift 里对 `[String: Any]` 的递归扫描。 */
    private fun walkJson(json: JSONObject, visit: (String, Any?) -> Unit) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = if (json.isNull(key)) null else json.opt(key)
            visit(key, value)
            walkJsonValue(value, visit)
        }
    }

    private fun walkJsonValue(value: Any?, visit: (String, Any?) -> Unit) {
        when (value) {
            is JSONObject -> walkJson(value, visit)
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    walkJsonValue(if (value.isNull(i)) null else value.opt(i), visit)
                }
            }
            else -> Unit
        }
    }

    /** 递归遍历 JSON 中的每个值（前序），对应 `extractNickname` 的 walk。 */
    private fun walkJsonValues(value: Any?, visit: (Any) -> Unit) {
        when (value) {
            is JSONObject -> {
                visit(value)
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    walkJsonValues(if (value.isNull(key)) null else value.opt(key), visit)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    walkJsonValues(if (value.isNull(i)) null else value.opt(i), visit)
                }
            }
            else -> Unit
        }
    }
}

/**
 * 对应 Swift 里的 `Task { await ... }`：登录后的会员/昵称刷新是尽力而为，
 * 任何异常都不能冒泡到 UI（存储未初始化时静默跳过）。
 */
private fun CoroutineScope.launchBestEffort(block: suspend () -> Unit) {
    runCatching { launch { runCatching { block() } } }
}

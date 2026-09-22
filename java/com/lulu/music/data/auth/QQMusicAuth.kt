package com.lulu.music.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import com.lulu.music.BeansApplication
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.net.BeansApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

    // MARK: - 登录诊断

    /**
     * 扫码登录全过程的有界诊断日志（≤ [QQLoginDiagnostics.MAX_ENTRIES] 条，最旧的先丢）。
     *
     * 记录的是「每步拿到了什么」：`ptqrshow` 结果、每个不同的轮询状态码、check_sig 每一跳、
     * authorize 响应、musicu.fcg 结果。所有远端原文在录入时就被 [QQLoginRedaction] 脱敏，
     * 任何 cookie / skey / p_skey / qqmusic_key / qrsig / 授权 code 都不会出现在日志里。
     */
    private val loginDiagnostics = QQLoginDiagnostics()

    /** Compose 观察用镜像。 */
    val diagnosticsFlow: StateFlow<List<QQLoginDiag>> get() = loginDiagnostics.entriesFlow

    /** 纯文本导出（已脱敏），供用户复制发给开发者。 */
    fun diagnosticsText(): String = loginDiagnostics.export()

    /** 清空诊断日志。 */
    fun clearDiagnostics() {
        loginDiagnostics.clear()
    }

    private fun recordDiag(step: String, detail: String, snippet: String = "") {
        // 先把「当前内存里的凭证值」登记进去，再做整串兜底脱敏。
        loginDiagnostics.setSensitiveValues(cookies.values + listOf(qrsig))
        loginDiagnostics.record(step, detail, snippet)
    }

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

    /// 发给 u.y.qq.com / c.y.qq.com 等 QQ 音乐接口的 Cookie 串。
    /// 内容 = 整个 Cookie 仓库（偏好顺序见 [QQ_COOKIE_EMISSION_ORDER]），并补一个兼容用的 `uin`。
    val cookieHeader: String
        get() = makeCookieHeader(includeCompatibilityUIN = true)

    /// 不注入兼容用的 uin=wxuin，给微信登录的歌单接口使用。
    /// 部分 QQ 接口会优先读取 uin，误把 wxuin 当成 QQ uin 后会返回空歌单。
    val playlistCookieHeader: String
        get() = makeCookieHeader(includeCompatibilityUIN = false)

    /**
     * 组装出站 `Cookie:` 头。
     *
     * **这不是白名单**（曾经是，那是真机 `100035` 的直接原因）：[QQ_COOKIE_EMISSION_ORDER]
     * 里的名字排在前面，仓库里其余的名字**照发不误**。登录链路上真正下发、而旧白名单没写的
     * `pt_oauth_token` / `pt_login_type` / `qrsig` 都因此不再被丢掉。
     *
     * [includeCompatibilityUIN] 为 true 且仓库里没有可用的 `uin` 时，用
     * [qqDerivedUIN] 从 `pt2gguin` / `p_uin` 派生一个 `uin` 放在最前面：
     * `graph.qq.com/oauth2.0/authorize` 是按 `uin` Cookie 认会话的，只有 `p_uin` / `pt2gguin`
     * 时它会回 `error=100035`（用户未登录）—— 真机诊断就是这个现象。
     */
    private fun makeCookieHeader(includeCompatibilityUIN: Boolean): String =
        qqAuthorizeCookieHeader(cookies, includeUIN = includeCompatibilityUIN)

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
        // 先归一化成 {名字: 值}：WebView 采集与手动粘贴本来就是这个名字，旧备份里可能是
        // 「整段头当键」，归一化后两条路径 + 旧备份都能被 fallbackNickname / query 正确读到。
        val normalized = qqNormalizedCookieDict(dict)
        if (normalized.isEmpty()) return
        val resolvedNickname = nickname ?: fallbackNickname(normalized)
        synchronized(lock) {
            cookies = LinkedHashMap(normalized)
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
        val hasCredential = CREDENTIAL_COOKIE_KEYS.any { !(dict[it] ?: "").isEmpty() }
        if (!hasCredential) {
            return "已读取到账号，但缺少 QQ 音乐登录凭证，请在网页中重新登录后再同步"
        }
        return null
    }

    /// Cookie 是否包含有效登录态，兼容 QQ 登录的 uin 和微信登录的 wxuin。
    fun hasValidLogin(dict: Map<String, String>): Boolean = loginValidationMessage(dict) == null

    /// 解析浏览器复制出来的完整 Cookie 字符串："a=b; c=d"
    fun parseCookieHeader(header: String): Map<String, String> = qqCookiePairsFromHeader(header)

    /**
     * 应用内**网页登录**的 Cookie 采集：从 WebView 的 `CookieManager` 里读 [QQ_WEB_COOKIE_SOURCES]
     * 列出的每个域名，合并成一份登录态。
     *
     * 这里只是一层薄封装（唯一碰 Android 的部分）；合并、去空值、判据全在
     * `QQWebLoginHelpers.kt` 的纯函数里，单测直接测那些。`CookieManager.getCookie` 一次只认一个
     * URL（传空格分隔的多域名不会返回合并结果），所以逐域名读。
     *
     * 必须在主线程调用：`CookieManager` 要求（见 Android 文档 `CookieManager.getCookie`）。
     * 调用方 `ui/screens/LoginScreen.kt` 的 `QQWebPanel` 从 Compose 的协程里调用它。
     *
     * 读的是 WebView 的 Cookie 仓库，因此 **HttpOnly Cookie 也能拿到**
     * （`qqmusic_key` 就是 HttpOnly —— 这是这条路径不需要 client_secret / OAuth 换取的原因）。
     */
    suspend fun harvestWebViewCookies(context: Context): Map<String, String> = withContext(Dispatchers.Main) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return@withContext emptyMap()
        val perDomain = LinkedHashMap<String, Map<String, String>>()
        for (source in QQ_WEB_COOKIE_SOURCES) {
            val raw = runCatching { manager.getCookie(source) }.getOrNull()
            perDomain[source] = qqCookiePairsFromHeader(raw)
        }
        qqSessionCookiesFromDomains(perDomain)
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

    private fun accountID(from: Map<String, String>): String = qqAccountID(from)


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
        // 新一次登录尝试：清掉上一次的诊断，避免两次尝试的记录混在一起
        loginDiagnostics.clear()
        lastPollCode = null
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
        // 二维码本身是 PNG 二进制，没有可展示的文本片段，只记录状态与是否拿到 qrsig。
        recordDiag(
            "ptqrshow",
            "HTTP ${exchange.status} | bytes=${exchange.bytes.size}" +
                " | qrsigPresent=${if (qr.isEmpty()) "no" else "yes"}",
        )
        if (qr.isEmpty()) {
            throw BeansApiException.Unknown(
                beansLocalized(
                    "获取 QQ 二维码失败，请检查网络后重试",
                    "Could not fetch the QQ QR code — check your network and try again",
                ),
            )
        }
        synchronized(lock) { qrsig = qr }
        return exchange.bytes
    }

    /// 单次轮询扫码状态（调用方以 3 秒间隔重复调用）
    suspend fun poll(): ScanState {
        val sig = qrsig
        if (sig.isEmpty()) {
            recordDiag("ptqrlogin", "qrsigPresent=no（登录会话已失效）")
            return ScanState.Expired
        }
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
        val parsed = parsePTUICallback(exchange.text)
        if (parsed == null) {
            recordDiag("ptqrlogin", "HTTP ${exchange.status} | ptuiCB 解析失败", exchange.text)
            return ScanState.Error(qqLoginFailureMessage(QQLoginFailure.INTERFACE_ABNORMAL))
        }
        recordPollDiag(exchange.status, parsed)
        return when (parsed.code) {
            "0" -> {
                val redirect = parsed.url
                    ?: return ScanState.Error(qqLoginFailureMessage(QQLoginFailure.NO_REDIRECT))
                try {
                    completeOAuth(redirect)
                } catch (e: CancellationException) {
                    // 协程取消必须继续向上传播，UI 依赖它停止轮询
                    throw e
                } catch (e: BeansApiException) {
                    // completeOAuth 抛出的都是带具体原因的消息（见 QQLoginFailure 映射）
                    return ScanState.Error(e.message ?: qqLoginFailureMessage(QQLoginFailure.EXCHANGE_FAILED))
                } catch (e: Exception) {
                    recordDiag("exchange", "unexpected=${e.javaClass.simpleName}")
                    return ScanState.Error(qqLoginFailureMessage(QQLoginFailure.EXCHANGE_FAILED))
                }
                val resolvedNickname = parsed.nickname.ifBlank { fallbackNickname(cookies) }
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

    /** 轮询状态码只记录「发生变化」的那一次，避免 24 条日志被同一个 code=66 刷满。 */
    private var lastPollCode: String? = null

    private fun recordPollDiag(status: Int, parsed: PTUIResult) {
        if (parsed.code == lastPollCode) return
        lastPollCode = parsed.code
        recordDiag(
            "ptqrlogin",
            "HTTP $status | ptuiCB=${parsed.code}（${pollCodeLabel(parsed.code)}）" +
                " | redirectPresent=${if (parsed.url == null) "no" else "yes"}" +
                " | nicknamePresent=${if (parsed.nickname.isEmpty()) "no" else "yes"}",
        )
    }

    private fun pollCodeLabel(code: String): String = when (code) {
        "0" -> beansLocalized("登录成功，开始换取凭证", "Signed in — exchanging credentials")
        "65", "68" -> beansLocalized("二维码已过期", "QR code expired")
        "66" -> beansLocalized("等待扫码", "Waiting for scan")
        "67" -> beansLocalized("已扫码，等待手机确认", "Scanned — waiting for confirmation")
        else -> beansLocalized("未知状态码", "Unknown status code")
    }

    // MARK: - 授权换 musickey

    private suspend fun completeOAuth(redirectURL: String) {
        // check_sig 跳转链必须携带 qrsig（ptlogin2.qq.com 域），否则校验失败、拿不到 skey/p_skey
        var loginCookie = loginCookieWithQrsig()
        // 1. 依次访问 check_sig 跳转链，收集 skey / p_skey（最多 6 跳）
        //
        // 注意：跳转链正常走到最后一跳时本来就没有 Location（最后是 200 页面），
        // 所以「没有 Location」本身不是错误 —— 真正的错误是「走完之后一个凭证都没拿到」。
        // 这两种情况分别对应 NO_REDIRECT / NO_CREDENTIALS 两条诊断消息。
        var current = redirectURL
        var hops = 0
        var sawRedirect = false
        var badURL = false
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
                badURL = true
                recordDiag("check_sig#$i", "locationResolved=no（跳转地址不是合法 URL，跳转链中断）")
                break
            }
            // 不跟随 302：必须自己从 Location 继续走跳转链（对应 iOS 的 NoRedirectDelegate）
            val exchange = authExchange(request, followRedirects = false)
            storeCookies(exchange.cookies)
            loginCookie = loginCookieWithQrsig()
            hops += 1
            val location = exchange.location
            val hasLocation = location.isNotEmpty()
            if (hasLocation) sawRedirect = true
            val locationDetail =
                if (hasLocation) " | locationKeys=[${queryKeys(location).joinToString(",")}]" else ""
            recordDiag(
                "check_sig#$i",
                "HTTP ${exchange.status} | locationPresent=${if (hasLocation) "yes" else "no"}" +
                    " | newCookies=[${exchange.cookies.keys.joinToString(",")}]" + locationDetail,
                // Location 里带 ptsigx 之类的凭据，不能直接记；这里只在没有跳转地址时记响应体
                if (hasLocation) "" else exchange.text,
            )
            if (!hasLocation) break
            val next = resolveLocation(location, current)
            if (next == null) {
                recordDiag("check_sig#$i", "locationResolved=no（无法解析成绝对地址，跳转链中断）")
                break
            }
            current = next
        }
        val credentials = credentialCookieNames()
        recordDiag(
            "check_sig.summary",
            "hops=$hops | locationPresent=${if (sawRedirect) "yes" else "no"}" +
                " | credentials=[${credentials.joinToString(",")}]",
        )
        if (credentials.isEmpty()) {
            // 区分两种「没凭证」：跳转链根本没走起来（第一步就没有 Location / URL 非法），
            // 与「走完了但一个凭证也没有」。
            val kind = if (!sawRedirect || badURL) {
                QQLoginFailure.NO_REDIRECT
            } else {
                QQLoginFailure.NO_CREDENTIALS
            }
            throw BeansApiException.Unknown(qqLoginFailureMessage(kind))
        }

        // 2. graph.qq.com oauth2 authorize 换 code
        //
        // 出站 Cookie = **整个仓库**（不再挑名字），并且补上 `uin`：
        // 真机诊断里 authorize 回的是 `error=100035`（用户未登录），原因是旧白名单既丢掉了
        // 链路刚下发的 pt_oauth_token / pt_login_type，又完全没有 uin（只有 p_uin / pt2gguin）。
        loginCookie = cookieHeader
        recordAuthorizeCookies(loginCookie)
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
        // 字段名与取值与 iOS 版逐字一致，提交方式是 POST 表单：QQ 对这个端点用 GET 会返回
        // 公共返回码 100012（HTTP请求非post方式），见 QQ_AUTHORIZE_USE_POST_FORM 的说明。
        val spec = qqAuthorizeRequest(fields)
        val authRequestBuilder = Request.Builder()
            .url(spec.url)
            .header("User-Agent", UA)
            .header("Referer", "https://graph.qq.com/")
            .header("Cookie", loginCookie)
        if (spec.formBody != null) {
            authRequestBuilder.header("Content-Type", "application/x-www-form-urlencoded")
            authRequestBuilder.post(spec.formBody.toRequestBody(formMediaType))
        } else {
            authRequestBuilder.get()
        }
        val authResult = authExchange(authRequestBuilder.build(), followRedirects = false)
        storeCookies(authResult.cookies)
        val authLocation = authResult.location
        val code = extractCode(authLocation)
        // QQ 失败时不会返回业务 JSON，而是跳到 `oauth2.0/show?which=error&error=<code>`：
        // 这里先把错误码挖出来，既能写进诊断，也能直接变成用户看得懂的一句话。
        val authorizeError = if (code == null) parseAuthorizeError(authLocation, authResult.text) else null
        val authorizeErrorDetail = if (authorizeError == null) {
            ""
        } else {
            " | qqError=${authorizeError.code.ifEmpty { "unknown" }}" +
                " | whichError=${if (authorizeError.isErrorForm) "yes" else "no"}"
        }
        recordDiag(
            "authorize",
            "HTTP ${authResult.status} | locationPresent=${if (authLocation.isEmpty()) "no" else "yes"}" +
                " | codePresent=${if (code == null) "no" else "yes"}" +
                " | newCookies=[${authResult.cookies.keys.joinToString(",")}]" +
                authorizeErrorDetail,
            // 响应体无论成功失败都记（以前只在失败时记），并在命中错误时把 Location 排在最前 ——
            // 诊断片段会被截断，`which=error&error=100012` 是最值钱的那几个字节。
            // code / skey / p_skey 等字段值在入库时统一脱敏，不会进日志。
            authorizeEvidenceSnippet(
                body = authResult.text,
                location = authLocation,
                error = authorizeError,
                codePresent = code != null,
            ),
        )
        if (code == null) {
            // 有错误码就把它翻成人话，直接作为 ScanState.Error 的文案显示在登录页红色状态行上
            // （不必让用户去翻「登录诊断」）。没有错误码才退回泛化的 NO_AUTHORIZE_CODE 文案。
            val message = if (authorizeError != null) {
                qqAuthorizeErrorSentence(authorizeError)
            } else {
                qqLoginFailureMessage(QQLoginFailure.NO_AUTHORIZE_CODE)
            }
            throw BeansApiException.Unknown(message)
        }

        // 3. musicu.fcg QQConnectLogin 换 musickey（登录态 Cookie 持久化）
        //    与 authorize 同步：整个 Cookie 仓库都带上（musickey 之外还需要 uin / p_skey）。
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
        val loginJson = loginExchange.asJsonObject()
        val data = loginJson
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
        val musicCredentials = musicCredentialCookieNames()
        val apiCode = loginJson?.optInt("code", 0) ?: -1
        recordDiag(
            "musicu.fcg",
            "HTTP ${loginExchange.status} | jsonPresent=${if (loginJson == null) "no" else "yes"}" +
                " | dataPresent=${if (data == null) "no" else "yes"}" +
                " | apiCode=$apiCode" +
                " | musicCredPresent=${if (musicCredentials.isEmpty()) "no" else "yes"}",
            // 整个响应体前 160 字符往往还没走到关键字段，优先记 req.data（musickey/musicid 就在里面）
            data?.toString() ?: loginExchange.text,
        )
        // code 被拒 / 响应里既没有 musickey 也没有音乐域 Cookie：以前会「静默成功」，
        // 结果是显示已登录但播放/歌单接口全部拿不到数据。这里必须报出来。
        if (musicCredentials.isEmpty()) {
            throw BeansApiException.Unknown(qqLoginFailureMessage(QQLoginFailure.CODE_REJECTED))
        }
    }

    /**
     * 记下 authorize 请求**实际发出去**的 Cookie 名（只有名字，永远没有值）。
     *
     * 这条诊断是为 `error=100035`（用户未登录）准备的：以前只能看到 authorize 回了什么错误，
     * 看不到「我们到底带着哪些 Cookie 去换 code」。有了它，下一次失败时能不能一眼判断
     * `uin` / `pt_oauth_token` / `qqmusic_key` 有没有出现在请求里。
     *
     * 脱敏不变：这里只拼字段名，值一个都不进日志（[QQLoginDiagnostics.record] 仍会再过一遍
     * [QQLoginRedaction]，`Cookie:` 整行抹除与 `name=value` 值抹除都照旧生效）。
     */
    private fun recordAuthorizeCookies(cookieHeader: String) {
        val names = sentCookieNames(cookieHeader)
        // 注意：单条 detail 会被 QQLoginRedaction 截断到 160 字符（MAX_SNIPPET），
        // 所以名字顺序很关键 —— QQ_COOKIE_EMISSION_ORDER 里最要紧的那几个排在最前，
        // 仓库里不认识的名字排在最后，被截也是截它们。
        recordDiag(
            "authorize.cookies",
            "count=${names.size} | sendCookieNames=[${names.joinToString(",")}]",
        )
    }

    /** 跳转链里已经拿到的凭证 Cookie 名（只记名字，不记值）。 */
    private fun credentialCookieNames(): List<String> =
        CREDENTIAL_COOKIE_KEYS.filter { !(cookies[it] ?: "").isEmpty() }

    /** 音乐域凭证（真正能播放/读写歌单的那一个），用于判断 musicu.fcg 这一步是否成功。 */
    private fun musicCredentialCookieNames(): List<String> =
        MUSIC_CREDENTIAL_COOKIE_KEYS.filter { !(cookies[it] ?: "").isEmpty() }

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

// ---------------------------------------------------------------------------------------------
// MARK: - 账号 ID / Cookie 头（纯逻辑，可单测；之前是 object 里的私有方法）
// ---------------------------------------------------------------------------------------------

/**
 * 登录账号 ID，按 `uin -> wxuin -> pt2gguin` 顺序取第一个可用的，保留原始形态（可能带 `o` 前缀）。
 *
 * `p_uin` 刻意**不参与**这里的优先级（历史行为如此：微信登录优先看 wxuin，
 * 否则 p_uin 会把微信会话误判成 QQ 会话）。`uin` 的派生见 [qqDerivedUIN]。
 */
internal fun qqAccountID(from: Map<String, String>): String {
    for (key in listOf("uin", "wxuin", "pt2gguin")) {
        val value = from[key] ?: continue
        if (!hasUsableAccountID(value)) continue
        return value.trim()
    }
    return "0"
}

/** 去掉 `o` 前缀的纯数字 QQ 号（个人资料 / 会员接口用）；没有前缀时原样返回。 */
internal fun normalizedUIN(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) return ""
    return if (value.startsWith("o")) value.substring(1) else value
}

/**
 * `0` / `o0` / 空串都算「没有账号」，其余非空值算有。
 *
 * 网页登录的判据（`QQWebLoginHelpers.kt` 的 `qqWebSessionStrength`）用的是同一个函数：
 * 「什么算一个真账号」只能有一处定义。
 */
internal fun hasUsableAccountID(raw: String?): Boolean {
    val value = raw?.trim() ?: return false
    if (value.isEmpty()) return false
    return value != "0" && value != "o0"
}

/**
 * 从 Cookie 仓库里派生一个 `uin`（仅在仓库本身没有可用的 `uin` 时用）。
 *
 * 来源见 [QQ_UIN_DERIVATION_SOURCES]：`pt2gguin` 优先，其次 `p_uin`。
 * **形态沿用来源本身**（`o` + 数字），因为这两个 Cookie 的值就是这个形态，
 * 下游 `accountID` / `normalizedUIN` 也一直按这个约定处理；纯数字版本由
 * [QQMusicAuth.uin]（`normalizedUIN`）提供。取不到返回 null。
 */
internal fun qqDerivedUIN(cookies: Map<String, String>): String? {
    for (key in QQ_UIN_DERIVATION_SOURCES) {
        val value = cookies[key]?.trim()
        if (!hasUsableAccountID(value)) continue
        return value
    }
    return null
}

/**
 * 把「一个域名一份的 Cookie 字典」归一化成 `{名字: 值}`。
 *
 * 存在的理由：[QQWebLoginHelpers] 与 [QQMusicAuth.harvestWebViewCookies] 给出的本来就是这个名字，
 * 而归一化让**旧版本备份**也继续可用 —— 老版本把整段头当键存进字典（`{"a=1; b=2": "a=1; b=2"}`），
 * 直接 `LinkedHashMap(dict)` 会让所有查询都查不到东西（`uin` / `qqmusic_key` 全部取空）。
 * 带 `;` / `=` 的键按 Cookie 头重新切开，其余键原样保留。
 */
internal fun qqNormalizedCookieDict(dict: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>(dict.size)
    for ((key, value) in dict) {
        if (key.indexOf(';') >= 0 || key.indexOf('=') >= 0) {
            out.putAll(qqCookiePairsFromHeader(value))
            out.putAll(qqCookiePairsFromHeader(key))
        } else if (value.isNotEmpty()) {
            out[key] = value
        }
    }
    return out
}

/**
 * 从已经拼好的 `Cookie:` 头里取出**字段名**（不含值），按 [QQ_COOKIE_EMISSION_ORDER] 排序。
 *
 * 只给诊断用：`authorize.cookies` 那一条就是它生成的。值永远不会被取出来 ——
 * 函数签名只返回名字列表，调用方即使想记值也拿不到。
 */
internal fun sentCookieNames(cookieHeader: String): List<String> {
    val names = qqCookiePairsFromHeader(cookieHeader).keys
    val ordered = ArrayList<String>(names.size)
    for (name in QQ_COOKIE_EMISSION_ORDER) if (names.contains(name)) ordered.add(name)
    for (name in names) if (!ordered.contains(name)) ordered.add(name)
    return ordered
}

// ---------------------------------------------------------------------------------------------
// MARK: - 扫码登录失败原因（纯逻辑，可单测；文案双语）
// ---------------------------------------------------------------------------------------------

/**
 * 扫码登录可能失败的**真实原因**。以前所有失败都显示同一句「QQ 授权失败，请重新扫码」，
 * 把「二维码过期」「扫了没确认」「确认了但换凭证失败」混成一句，用户只能反复重扫。
 */
enum class QQLoginFailure {
    /** 二维码过期 / 轮询会话丢失：让用户刷新二维码。 */
    QR_EXPIRED,

    /** 已扫码但没在手机上确认：继续等待，别提示失败。 */
    SCANNED_NOT_CONFIRMED,

    /** 已确认登录，但换凭证过程本身出错（网络、解析等未分类异常）。 */
    EXCHANGE_FAILED,

    /** 跳转链没有返回重定向地址（第一步 check_sig 就没生效）。 */
    NO_REDIRECT,

    /** 跳转链走完了，但一个凭证（skey / p_skey / qqmusic_key）都没拿到。 */
    NO_CREDENTIALS,

    /**
     * authorize 接口没有返回 code，并且响应里也解析不出 QQ 公共返回码时的**兜底**文案。
     * 只要拿到了 `error=`（例如 100012 / 100010 / 100057），用的就不是这条，而是
     * [qqAuthorizeErrorSentence] 给出的具体原因。
     */
    NO_AUTHORIZE_CODE,

    /** musicu.fcg 拒绝了这次 code（响应里没有 musickey，也没有音乐域 Cookie）。 */
    CODE_REJECTED,

    /** ptqrlogin 返回体不是合法的 ptuiCB（接口变动 / 被劫持 / 网络中间页）。 */
    INTERFACE_ABNORMAL,
}

/** [QQLoginFailure] 的用户可见文案（双语）。文案刻意压到 30 字以内：状态行最多显示两行。 */
internal fun qqLoginFailureMessage(kind: QQLoginFailure): String = when (kind) {
    QQLoginFailure.QR_EXPIRED -> beansLocalized(
        "二维码已过期，请点「刷新」重新获取",
        "The QR code expired — tap Refresh for a new one",
    )

    QQLoginFailure.SCANNED_NOT_CONFIRMED -> beansLocalized(
        "已扫码，请在手机上确认登录，并保持本页打开",
        "Scanned — confirm on your phone and keep this screen open",
    )

    QQLoginFailure.EXCHANGE_FAILED -> beansLocalized(
        "已确认登录，但换取登录凭证时出错，详见「登录诊断」",
        "Sign-in was confirmed, but exchanging credentials failed — see Login diagnostics",
    )

    QQLoginFailure.NO_REDIRECT -> beansLocalized(
        "已确认登录，但跳转链没有返回重定向地址，详见「登录诊断」",
        "Sign-in was confirmed, but the redirect chain returned no URL — see Login diagnostics",
    )

    QQLoginFailure.NO_CREDENTIALS -> beansLocalized(
        "已确认登录，但跳转链没有拿到凭证（skey/p_skey 为空），详见「登录诊断」",
        "Sign-in was confirmed, but the redirect chain returned no credentials (skey/p_skey empty) — see Login diagnostics",
    )

    QQLoginFailure.NO_AUTHORIZE_CODE -> beansLocalized(
        "已确认登录，但授权接口没返回 code（可能被风控），详见「登录诊断」",
        "Sign-in was confirmed, but the authorize endpoint returned no code (possibly blocked) — see Login diagnostics",
    )

    QQLoginFailure.CODE_REJECTED -> beansLocalized(
        "已确认登录，但音乐接口拒绝了这次授权 code，请重新扫码",
        "Sign-in was confirmed, but the music API rejected the authorization code — please scan again",
    )

    QQLoginFailure.INTERFACE_ABNORMAL -> beansLocalized(
        "QQ 登录接口返回异常，请重试",
        "The QQ login endpoint returned an unexpected response, please retry",
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 凭证 Cookie 名（只关心名字；诊断日志永远不记值）
// ---------------------------------------------------------------------------------------------

/**
 * 登录态凭证 Cookie 名（QQ 扫码得到的 skey/p_skey，或音乐域凭证）。
 *
 * `internal`（而不是 private）是因为 `QQWebLoginHelpers.kt` 的网页登录判据要用同一份名单：
 * 「哪些 Cookie 算凭证」只能有一处定义，否则网页登录与扫码登录会对同一份 Cookie 给出不同结论。
 */
internal val CREDENTIAL_COOKIE_KEYS = listOf(
    "p_skey", "skey", "qqmusic_key", "qm_keyst",
    "music_key", "wxskey", "wx_skey", "musickey",
)

/** 音乐域凭证：真正能播放 / 读写歌单的那一个（`musicu.fcg` 这一步的成败判据）。 */
internal val MUSIC_CREDENTIAL_COOKIE_KEYS = listOf(
    "qqmusic_key", "qm_keyst", "music_key", "musickey", "wxskey", "wx_skey",
)

// ---------------------------------------------------------------------------------------------
// MARK: - authorize 请求（纯逻辑，可单测）
// ---------------------------------------------------------------------------------------------

/** QQ Connect 授权端点（换取 code）。 */
internal const val QQ_AUTHORIZE_ENDPOINT = "https://graph.qq.com/oauth2.0/authorize"

/**
 * authorize 是用 **POST 表单**（默认）还是 GET + query 提交。
 *
 * 默认 `true`（POST 表单）：这是真机验证后的结论，不是偏好。
 * QQ Connect 官方公开的「公共返回码说明」里 **100012 = HTTP请求非post方式**，
 * 而真机诊断拿到的正是 `https://graph.qq.com/oauth2.0/show?which=error&...&error=100012`：
 * 说明这个端点（`oauth2.0/authorize`，带 `switch` / `from_ptlogin` / `g_tk` / `openapi` 这些
 * ptlogin 专用字段的那一支）**只接受 POST**，用 GET 会被直接拒掉。
 *
 * 因此上一版把它改成 GET 是错的：那个 KDoc 拿「RFC 6749 授权端点用 GET」当理由，
 * 但 RFC 6749 描述的是标准授权码流程的浏览器跳转端点；这里走的是 QQ 自己的
 * ptlogin -> check_sig -> graph.qq.com 换 code 链路，服务端实现要求 POST，
 * 而且**错误码 100012 已经把这件事写明了**。
 *
 * 保留 GET 分支只是为了让回归测试能断言「两种提交方式编码出的字段逐字一致」，
 * 生产默认值必须是 POST：`false` 是**已知会被 QQ 拒绝**的取值，不要再改回去。
 */
internal const val QQ_AUTHORIZE_USE_POST_FORM = true

/**
 * 一次 authorize 请求的纯描述：GET 时字段全在 [url] 的 query 里、[formBody] 为 null；
 * POST 时 [url] 是裸端点、[formBody] 是表单体。两种方式编码出的字段完全一致。
 */
internal data class QQAuthorizeRequest(val url: String, val formBody: String?)

/** 按 [usePostForm] 组装 authorize 请求；默认走 [QQ_AUTHORIZE_USE_POST_FORM]。 */
internal fun qqAuthorizeRequest(
    fields: Map<String, String>,
    usePostForm: Boolean = QQ_AUTHORIZE_USE_POST_FORM,
): QQAuthorizeRequest {
    val encoded = qqFormEncode(fields)
    return if (usePostForm) {
        QQAuthorizeRequest(url = QQ_AUTHORIZE_ENDPOINT, formBody = encoded)
    } else {
        QQAuthorizeRequest(url = "$QQ_AUTHORIZE_ENDPOINT?$encoded", formBody = null)
    }
}

/** 对应 Swift `URLComponents.query` 的表单编码（空格用 `+`）。 */
internal fun qqFormEncode(fields: Map<String, String>): String =
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

/**
 * `ptqrlogin` 的 JSONP 回调解析结果。
 *
 * @param code ptuiCB 的第一个字段（0 成功 / 65、68 过期 / 66 等待 / 67 已扫码）
 * @param url 跳转地址（`check_sig`），没有时为 null
 * @param nickname 昵称；服务端没下发时为空串
 */
internal data class PTUIResult(val code: String, val url: String?, val nickname: String)

/**
 * 解析 `ptuiCB('0','0','<url>','0','<message>','<nickname>')` 形式的 JSONP 回调。
 *
 * 真实字段顺序（扫码成功的形状）：
 * 0 状态码 / 1 固定 `'0'` / 2 跳转地址 / 3 固定 `'0'` / 4 人类可读消息（如「登录成功！」）/ 5 昵称。
 *
 * 旧实现取 `parts[4]` 当昵称，于是把「登录成功！」写成了用户昵称（`parts[5]` 才是昵称）。
 * 只有五个字段的历史形状（`code,url,'0',msg,nick`）没有独立昵称位，退回 `parts[4]`。
 *
 * 纯字符串处理，不碰网络也不依赖 Android，便于单测。
 */
internal fun parsePTUICallback(text: String): PTUIResult? {
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
    // 跳转地址一定出现在 1（历史形状）或 2（真实形状），且一定是绝对 http(s) 地址；
    // 等待/过期状态下这两格是 '0'，必须当成「没有地址」，否则诊断里的 redirectPresent 会说谎。
    val candidate = if (parts[1].startsWith("http")) parts[1] else parts.getOrElse(2) { "" }
    val url = candidate.takeIf { it.startsWith("http") } ?: ""
    // 6 个字段及以上：昵称在 5；恰好 5 个字段：历史形状，昵称在 4。
    val nickname = if (parts.size >= 6) parts[5] else parts[4]
    return PTUIResult(code = parts[0], url = if (url.isEmpty()) null else url, nickname = nickname)
}

/**
 * 取出 URL query 里的**参数名**（不含值）。
 *
 * 诊断日志需要「这一跳带了哪些参数」来判断流程走到哪儿，但 query 里可能有
 * `ptsigx` 这类凭据，所以只记名字，永远不记值。
 */
internal fun queryKeys(urlString: String): List<String> {
    val query = urlString.substringAfter('?', "")
    if (query.isEmpty()) return emptyList()
    return query.split('&').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        val key = if (eq < 0) pair else pair.substring(0, eq)
        key.trim().takeIf { it.isNotEmpty() }
    }
}

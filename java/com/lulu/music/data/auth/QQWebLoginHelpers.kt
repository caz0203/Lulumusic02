package com.lulu.music.data.auth

/**
 * QQ 音乐**应用内网页登录**（WebView + `CookieManager`）的纯逻辑。
 *
 * 这个文件里没有任何 Android / 网络依赖 —— 只有字符串与字典处理，所以能在 JVM 单测里直接断言。
 * 真正的 `CookieManager` 读取在 `QQMusicAuth` 的 `harvestWebViewCookies`（那里是薄薄一层），
 * UI 在 `ui/screens/LoginScreen.kt` 的 `QQWebPanel`。
 *
 * ## 为什么需要它
 *
 * 扫码链路（[QQMusicAuth] 里的 ptqrshow → ptqrlogin → check_sig → graph.qq.com authorize）
 * 需要 QQ Connect 的 code 换 musickey，真机诊断显示 authorize 返回 `error=100035`（用户未登录）。
 * 换成 WebView 之后，**根本不需要** client_id / client_secret / OAuth 换取：
 * 用户在真正的登录页完成登录，服务端自己把 `qqmusic_key` 等凭证写进 WebView 的 Cookie 仓库，
 * 我们只把这些 Cookie 原样读出来交给既有的 [QQMusicAuth.importCookies]。
 * 也正因为如此，这条路径上没有任何 appid 或密钥被硬编码。
 *
 * ## 单位是「域名」
 *
 * `CookieManager.getCookie(url)` 一次只认**一个** URL（传空格分隔的多域名不会返回合并结果），
 * 所以每个域名单独读一次，再按顺序合并。合并规则是「后读到的覆盖先读到的」，
 * 因此 [QQ_WEB_COOKIE_SOURCES] 把最权威的音乐域放在**最后**（见那里的说明）。
 */

/** WebView 登录加载的页面：QQ 音乐门户。用户在该页点「登录」走 QQ 官方登录流程。 */
internal const val QQ_WEB_LOGIN_URL = "https://y.qq.com/"

/**
 * 采集 Cookie 时逐个读取的域名根地址。**顺序 = 合并优先级，越靠后越优先。**
 *
 * - `https://qq.com`：父域兜底（`uin` / `p_uin` / `pt2gguin` 常挂在这一层）。
 * - `https://ptlogin2.qq.com` / `https://graph.qq.com`：登录跳转链的中间域，
 *   `p_skey` / `pt_oauth_token` / `pt_login_type` 这类 QQ Connect 会话 Cookie 在这里下发，
 *   音乐域不一定复制一份。
 * - `https://y.qq.com`：**判据所在**，放在最后 = 优先级最高。登录成功后
 *   `qqmusic_key` / `qm_keyst` / `uin` 都挂在音乐域；同名 Cookie 以它为准。
 */
internal val QQ_WEB_COOKIE_SOURCES: List<String> = listOf(
    "https://qq.com",
    "https://ptlogin2.qq.com",
    "https://graph.qq.com",
    "https://y.qq.com",
)

/**
 * QQ 登录链路上会用到 Cookie 的域名后缀。
 *
 * 只用于**诊断标注**（这一批 Cookie 是从哪个域读出来的），不参与登录判定 ——
 * 判定只看 Cookie 名，与它挂在哪个域无关。
 */
internal val QQ_LOGIN_DOMAIN_SUFFIXES: List<String> = listOf("qq.com", "y.qq.com")

/**
 * `a=1; b=2` → `{a=1, b=2}`。
 *
 * `CookieManager` / 手动粘贴的 Cookie 头都是这个形状。值为空、缺 `=`、名为空的对一律跳过。
 * 换行、制表符当成普通空白（WebView 有时把整段 Cookie 拼成一行，中间夹着 `\n`）。
 */
internal fun qqCookiePairsFromHeader(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in raw.split(';', '\n', '\r')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val name = part.substring(0, eq).trim()
        val value = part.substring(eq + 1).trim()
        if (name.isEmpty() || value.isEmpty()) continue
        out[name] = value
    }
    return out
}

/**
 * `{a=1, b=2}` → `a=1; b=2`（[QQ_COOKIE_EMISSION_ORDER] 优先，其余按字典顺序）。
 *
 * 顺序只影响可读性与「同名 Cookie 谁先被服务端看到」，不改变集合内容。
 */
internal fun qqCookieHeaderFromPairs(cookies: Map<String, String>): String =
    qqCookiePairsInEmissionOrder(cookies).joinToString("; ") { (name, value) -> "$name=$value" }

/**
 * 从一次 `CookieManager` 读取结果里取出可用的登录态（多域名合并、去空值、按域去重）。
 *
 * 返回内容直接就是 [QQMusicAuth.importCookies] 需要的形状：`{Cookie 名: 值}`。
 * 同名 Cookie 保留**优先级最高**的那个域的值（[QQ_WEB_COOKIE_SOURCES] 里靠后的域优先，
 * 即 `y.qq.com` > `graph.qq.com` > `ptlogin2.qq.com` > `qq.com`），其余域只补它没有的名字。
 * 传入的 [perDomain] 顺序不重要：合并顺序只由常量决定，不受调用方拼装字典的顺序影响。
 * 空值一律跳过（`getCookie` 偶尔会返回 `name=` 这种占位）。
 */
internal fun qqSessionCookiesFromDomains(perDomain: Map<String, Map<String, String>>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (source in QQ_WEB_COOKIE_SOURCES) {
        val cookies = perDomain[source] ?: continue
        for ((name, value) in cookies) {
            if (name.isEmpty() || value.isEmpty()) continue
            out[name] = value
        }
    }
    // 常量表之外的域名（诊断/测试会传）：一并带上，但优先级最低，不覆盖已知域的值。
    for ((source, cookies) in perDomain) {
        if (QQ_WEB_COOKIE_SOURCES.contains(source)) continue
        for ((name, value) in cookies) {
            if (name.isEmpty() || value.isEmpty()) continue
            if (!out.containsKey(name)) out[name] = value
        }
    }
    return out
}

/**
 * 「这个会话现在能不能用」的判据：**有账号 ID 且至少有一个凭证**。
 *
 * 也就是 [QQWebSessionStrength.QQ_SESSION] / [QQWebSessionStrength.MUSIC_SESSION] 两档；
 * 只有账号（[QQWebSessionStrength.ACCOUNT_ONLY]）**不算**能用 —— 那正是「刚点进登录页、
 * 服务端只种了 uin」的状态，把它当成已登录会让播放/歌单接口全线拿不到数据。
 *
 * 纯函数：只读 Cookie 字典，不碰网络也不碰 Android。
 */
internal fun qqWebSessionUsable(cookies: Map<String, String>): Boolean = when (qqWebSessionStrength(cookies)) {
    QQWebSessionStrength.QQ_SESSION, QQWebSessionStrength.MUSIC_SESSION -> true
    QQWebSessionStrength.NONE, QQWebSessionStrength.ACCOUNT_ONLY -> false
}

/** 网页登录会话的强弱（判据见 [qqWebSessionStrength]），用于「能用了没 / 是不是只差一步」的分支。 */
internal enum class QQWebSessionStrength {
    /** 没有账号 ID。 */
    NONE,

    /** 有账号 ID，但没有账号凭证（只有 weblogin 之类的无关 Cookie）。 */
    ACCOUNT_ONLY,

    /** 有账号 ID + `p_skey`（QQ Connect 会话）：能用，但未必能读写全部音乐接口。 */
    QQ_SESSION,

    /** 有账号 ID + 音乐域凭证（`qqmusic_key` / `qm_keyst` / `musickey` / …）：完整可用。 */
    MUSIC_SESSION,
}

/**
 * 会话强弱的分档，判据是「有账号 ID + 有什么级别的凭证」：
 *
 * 1. **账号 ID**：`uin` / `p_uin` / `pt2gguin` / `wxuin` 里至少有一个可用
 *    （`0`、`o0`、空串都算没有）；
 * 2. **凭证**：[MUSIC_CREDENTIAL_COOKIE_KEYS]（`qqmusic_key` / `qm_keyst` / `musickey` …）
 *    → `MUSIC_SESSION`（完整可用）；只有 [CREDENTIAL_COOKIE_KEYS] 里的 `p_skey` / `skey`
 *    → `QQ_SESSION`（能用，但未必能读写全部音乐接口）；什么都没有 → `ACCOUNT_ONLY`。
 *
 * 为什么不用 [QQMusicAuth.hasValidLogin] 当这里的判据：那个判据是给「手动粘贴 Cookie」用的
 * （用户自己负责，粘错了我也不该拦着）。WebView 是**自动**导入，判据必须能区分
 * 「已经登进去了」和「只种了个 uin」，所以这里分四档而不是布尔。
 *
 * 参见 [qqWebSessionUsable]。
 */
internal fun qqWebSessionStrength(cookies: Map<String, String>): QQWebSessionStrength {
    val hasAccount = QQ_ACCOUNT_ID_COOKIE_KEYS.any { hasUsableAccountID(cookies[it]) }
    if (!hasAccount) return QQWebSessionStrength.NONE
    if (MUSIC_CREDENTIAL_COOKIE_KEYS.any { !(cookies[it] ?: "").isEmpty() }) {
        return QQWebSessionStrength.MUSIC_SESSION
    }
    val hasQQCredential = CREDENTIAL_COOKIE_KEYS.any { !(cookies[it] ?: "").isEmpty() }
    return if (hasQQCredential) QQWebSessionStrength.QQ_SESSION else QQWebSessionStrength.ACCOUNT_ONLY
}

/**
 * [url] 是不是 QQ 登录链路上的页面（`qq.com` 及其子域）。
 *
 * 用后缀匹配而不是 `contains("qq.com")`：后者会把 `qq.com.evil.example` 也判成 QQ 域。
 * 大小写不敏感，端口/路径/查询串不影响结果。仅用于诊断标注，不参与登录判定。
 */
internal fun isQQLoginDomain(url: String?): Boolean {
    val host = qqHostOf(url) ?: return false
    return QQ_LOGIN_DOMAIN_SUFFIXES.any { suffix ->
        host == suffix || host.endsWith(".$suffix")
    }
}

/**
 * 取 URL 的 host（小写、去掉用户信息/端口）。
 *
 * 自己解析而不是用 `java.net.URI`：`CookieManager` 回调里的 URL 偶尔是 `about:blank`
 * 这类非法 URI，用异常兜底的解析器更适合这里。解析不出来返回 null。
 */
internal fun qqHostOf(url: String?): String? {
    val raw = url?.trim() ?: return null
    if (raw.isEmpty()) return null
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = raw.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    var rest = raw.substring(schemeEnd + 3)
    val cut = listOf(rest.indexOf('/'), rest.indexOf('?'), rest.indexOf('#'))
        .filter { it >= 0 }
        .minOrNull()
    if (cut != null) rest = rest.substring(0, cut)
    val at = rest.lastIndexOf('@')
    if (at >= 0) rest = rest.substring(at + 1)
    val colon = rest.lastIndexOf(':')
    if (colon >= 0) rest = rest.substring(0, colon)
    val host = rest.trim().lowercase()
    return host.ifEmpty { null }
}

// ---------------------------------------------------------------------------------------------
// MARK: - Cookie 顺序 / 账号 ID 常量（扫码与网页登录共用）
// ---------------------------------------------------------------------------------------------

/**
 * 组装出站 `Cookie:` 头（与 [QQMusicAuth.makeCookieHeader] 同一条规则）。
 *
 * `includeUIN` 为 true 且 [cookies] 里没有可用的 `uin` 时，用 [qqDerivedUIN] 派生一个放在最前面；
 * 派生不出来就只发仓库里已有的东西，不加空值。
 *
 * 纯函数：`QQMusicAuth` 的私有实现之所以还要存在（而不是直接调它），
 * 只是为了把「要不要注入兼容 uin」这个开关留在对象里 —— 两者行为完全一致。
 */
internal fun qqAuthorizeCookieHeader(cookies: Map<String, String>, includeUIN: Boolean = true): String {
    val ordered = ArrayList<Pair<String, String>>(cookies.size + 1)
    if (includeUIN && !hasUsableAccountID(cookies["uin"])) {
        val derived = qqDerivedUIN(cookies)
        if (derived != null) ordered.add("uin" to derived)
    }
    ordered.addAll(qqCookiePairsInEmissionOrder(cookies))
    return ordered.joinToString("; ") { (name, value) -> "$name=$value" }
}

/**
 * 组装出站 `Cookie:` 头时的偏好顺序。**不是白名单**：不在表里的名字照样会发出去（排在后面）。
 *
 * 这个区别很关键：旧实现把这张表当白名单用，于是登录链路真正下发、而表里没写的
 * `pt_oauth_token` / `pt_login_type` 被静默丢掉，`uin` 更是完全没有 —— 真机上
 * `graph.qq.com/oauth2.0/authorize` 因此报 `100035`（用户未登录）。
 */
internal val QQ_COOKIE_EMISSION_ORDER = listOf(
    "uin", "p_uin", "pt2gguin", "wxuin", "wxopenid",
    "qqmusic_key", "qm_keyst", "music_key", "musickey",
    "p_skey", "skey", "wxskey", "wx_skey",
    "pt4_token", "pt_oauth_token", "pt_login_type", "qrsig",
)

/** 账号 ID Cookie：有任意一个可用（非空、非 `0` / `o0`）就认为「有账号」。 */
internal val QQ_ACCOUNT_ID_COOKIE_KEYS = listOf("uin", "p_uin", "pt2gguin", "wxuin")

/**
 * `uin` 的派生来源（顺序即优先级）：`pt2gguin` 优先于 `p_uin`。
 *
 * 两者都是 `o` + 数字的登录账号形态，`uin` 按约定取同一形态（保留 `o` 前缀）；
 * 需要纯数字 QQ 号的接口（个人资料、会员信息）走 [QQMusicAuth.uin]，那里会去掉前缀。
 */
internal val QQ_UIN_DERIVATION_SOURCES = listOf("pt2gguin", "p_uin")

/**
 * 出站 Cookie 的有序 `name=value` 对：先按 [QQ_COOKIE_EMISSION_ORDER] 排，
 * 表里没写的名字**照样跟在后面**（这不是白名单）。
 *
 * `QQMusicAuth.makeCookieHeader`（扫码链路）与 [qqCookieHeaderFromPairs] 共用同一份顺序，
 * 保证「实际发出去的头」与「测试/诊断里推导的头」永远一致。
 */
internal fun qqCookiePairsInEmissionOrder(cookies: Map<String, String>): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>(cookies.size)
    val emitted = HashSet<String>(cookies.size)
    for (name in QQ_COOKIE_EMISSION_ORDER) {
        val value = cookies[name]
        if (value.isNullOrEmpty()) continue
        out.add(name to value)
        emitted.add(name)
    }
    for ((name, value) in cookies) {
        if (name.isEmpty() || value.isEmpty()) continue
        if (emitted.contains(name)) continue
        out.add(name to value)
    }
    return out
}

package com.lulu.music.data.auth

import com.lulu.music.data.model.beansLocalized
import java.net.URLDecoder

// ---------------------------------------------------------------------------------------------
// MARK: - QQ Connect 公共返回码（纯逻辑，可单测；文案双语）
// ---------------------------------------------------------------------------------------------

/**
 * QQ Connect 授权端点返回的一次错误。
 *
 * 关键点：**`oauth2.0/authorize` 失败时不返回业务 JSON，而是 302 跳到
 * `https://graph.qq.com/oauth2.0/show?which=error&display=pc&error=<code>...`**。
 * 也就是说，错误码只存在于 `Location` 里（HTTP 200 的 JSONP 回调
 * `callback( {"error":100019,...} );` 是另一条路径）。两种形状都要认。
 *
 * @param code `error=` 的原始取值（原样保留，可能不是数字，例如 `invalid_request`）；没给出时为空串
 * @param description `error_description=` 的原始取值；没有时为 null
 * @param isErrorForm 是否命中 `which=error` 这种「错误跳转页」形状
 */
internal data class QQAuthorizeError(
    val code: String,
    val description: String?,
    val isErrorForm: Boolean,
) {
    /** 服务端是否给出了错误码。为 false 时只能报「授权接口返回错误」。 */
    val hasCode: Boolean get() = code.isNotEmpty()
}

/** 一条公共返回码的中英文含义。 */
internal data class QQConnectErrorMeaning(val zh: String, val en: String)

/**
 * QQ Connect 官方「公共返回码说明」里与扫码换 code / 换 token 相关的条目。
 *
 * 来源：https://wiki.connect.qq.com/公共返回码说明（官方公开表，2024 版）。
 * 只收录这条链路（ptlogin -> check_sig -> oauth2.0/authorize -> musicu.fcg）上
 * 可能真的撞到的码 —— 表里其余条目（例如移动端 SDK 专用码）不在这里编造。
 *
 * 三个与本次真机排障直接相关的码已加粗标注在下方注释里：
 * `100010` 回调地址不合法 / `100012` HTTP 请求非 POST 方式 / `100057` 无 skey 换 code 权限。
 */
internal val QQ_CONNECT_ERROR_CODES: Map<String, QQConnectErrorMeaning> = linkedMapOf(
    "100000" to QQConnectErrorMeaning("缺少或非法的 response_type", "missing or invalid response_type"),
    "100001" to QQConnectErrorMeaning("缺少 client_id", "missing client_id"),
    "100002" to QQConnectErrorMeaning("缺少 client_secret", "missing client_secret"),
    "100008" to QQConnectErrorMeaning("该 appid 不存在", "that appid does not exist"),
    "100009" to QQConnectErrorMeaning("client_secret 非法", "invalid client_secret"),
    "100010" to QQConnectErrorMeaning("回调地址不合法", "the callback address is not allowed"),
    "100011" to QQConnectErrorMeaning("APP 未上线", "the app is not published"),
    "100012" to QQConnectErrorMeaning("HTTP 请求非 POST 方式", "the request was not sent as an HTTP POST"),
    "100013" to QQConnectErrorMeaning("access token 失效", "the access token is invalid"),
    "100014" to QQConnectErrorMeaning("access token 过期", "the access token has expired"),
    "100018" to QQConnectErrorMeaning("获取 code 失败", "could not obtain a code"),
    "100019" to QQConnectErrorMeaning(
        "用 code 换 access token 失败",
        "exchanging the code for an access token failed",
    ),
    "100020" to QQConnectErrorMeaning("code 被重复使用", "the code was already used"),
    "100030" to QQConnectErrorMeaning("用户未授权该 api", "the user has not authorized that API"),
    "100031" to QQConnectErrorMeaning("应用无该 api 权限", "the app has no permission for that API"),
    "100035" to QQConnectErrorMeaning("用户未登录", "the user is not signed in"),
    "100044" to QQConnectErrorMeaning("sign 校验失败", "signature (sign) verification failed"),
    "100046" to QQConnectErrorMeaning("g_tk 校验失败", "g_tk verification failed"),
    "100057" to QQConnectErrorMeaning(
        "APPID 没有使用 skey 换 code/token 的权限",
        "this appid may not exchange skey for a code or token",
    ),
    "100058" to QQConnectErrorMeaning("openid 非法", "invalid openid"),
    "100060" to QQConnectErrorMeaning("禁止登录", "sign-in is forbidden"),
    "100067" to QQConnectErrorMeaning("code 过期", "the code has expired"),
    "100068" to QQConnectErrorMeaning("非法 code", "invalid code"),
    "110405" to QQConnectErrorMeaning(
        "登录请求被限制，请稍后再试",
        "sign-in requests are rate-limited, please retry later",
    ),
)

/** 未知错误码的兜底文案里，服务端 `error_description` 的最大展示长度。 */
private const val MAX_DESCRIPTION_LENGTH = 60

/**
 * 用户可见的一句话：「QQ 拒绝授权：<含义>（<code>）」。
 *
 * 要求：
 * - 已知码走 [QQ_CONNECT_ERROR_CODES] 的中文/英文含义；
 * - **未知码也必须显示出来**，形状是「QQ 拒绝授权：QQ 错误码 NNNNN」，绝不吞掉原始码；
 * - 只有 `which=error` 而没有 `error=` 时，说明是错误跳转页但没给码，也要给出可读的一句；
 * - 服务端的 `error_description` 只在「码未知」时附上（已知码的含义本身更好懂），
 *   并且先过一遍脱敏，避免把任何凭证或 URL 里的敏感字段带进 UI 文案。
 */
internal fun qqAuthorizeErrorSentence(error: QQAuthorizeError): String {
    val code = error.code.trim()
    if (code.isEmpty()) {
        return beansLocalized(
            "QQ 拒绝授权：授权接口返回错误（未给出错误码），详见「登录诊断」",
            "QQ rejected the authorization: the endpoint returned an error with no code — see Login diagnostics",
        )
    }
    val meaning = QQ_CONNECT_ERROR_CODES[code]
    if (meaning != null) {
        return beansLocalized(
            "QQ 拒绝授权：${meaning.zh}（$code）",
            "QQ rejected the authorization: ${meaning.en} ($code)",
        )
    }
    val description = sanitizedDescription(error.description)
    return if (description == null) {
        beansLocalized(
            "QQ 拒绝授权：QQ 错误码 $code",
            "QQ rejected the authorization: QQ error code $code",
        )
    } else {
        beansLocalized(
            "QQ 拒绝授权：QQ 错误码 $code（$description）",
            "QQ rejected the authorization: QQ error code $code ($description)",
        )
    }
}

/** 已知码的含义（测试与诊断日志用）；未知返回 null。 */
internal fun qqConnectErrorMeaning(code: String): QQConnectErrorMeaning? =
    QQ_CONNECT_ERROR_CODES[code.trim()]

/** `error_description` 是不可信远端文本：先脱敏，再压到一行并限长。 */
private fun sanitizedDescription(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val safe = QQLoginRedaction.redact(value)
    if (safe.isEmpty() || safe == QQLoginRedaction.MASK) return null
    return if (safe.length <= MAX_DESCRIPTION_LENGTH) safe else safe.take(MAX_DESCRIPTION_LENGTH) + "…"
}

// ---------------------------------------------------------------------------------------------
// MARK: - 从响应里把错误码挖出来（Location 优先，其次响应体）
// ---------------------------------------------------------------------------------------------

/** `?a=1&b=2` -> map（同时处理 `%XX` 与 `+`）；没有 query 时返回空 map。 */
private fun authorizeQueryParams(url: String): Map<String, String> {
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (pair in query.split('&')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        val key = decodeQueryComponent(pair.substring(0, eq))
        val value = decodeQueryComponent(pair.substring(eq + 1))
        out[key] = value
    }
    return out
}

/** 非法转义（例如裸 `%`）时按原串处理，绝不因为一个坏字符丢掉整条错误信息。 */
private fun decodeQueryComponent(raw: String): String = try {
    URLDecoder.decode(raw, "UTF-8")
} catch (e: Exception) {
    raw
}

/** 响应体里的 JSONP / JSON 形状：`callback( {"error":100019,"error_description":"..."} );` */
private val BODY_ERROR_JSON = Regex("\"error\"\\s*:\\s*\"?([0-9A-Za-z_.\\-]+)\"?")
private val BODY_ERROR_DESCRIPTION_JSON = Regex("\"error_description\"\\s*:\\s*\"([^\"]*)\"")
/** 响应体里的表单形状（`error=100019&error_description=...`）；`error_description` 不会被误当成 `error`。 */
private val BODY_ERROR_FORM = Regex("(?<![A-Za-z0-9_])error\\s*=\\s*([0-9A-Za-z_.\\-]+)")
private val BODY_ERROR_DESCRIPTION_FORM = Regex(
    "(?<![A-Za-z0-9_])error_description\\s*=\\s*([^&\\s\"']+)",
)

/**
 * 从 authorize 这一跳里解析 QQ 的错误信息。
 *
 * 顺序很重要：[location] 优先 —— 真机实测的失败形状是
 * `HTTP 302 -> https://graph.qq.com/oauth2.0/show?which=error&error=100012&...`，
 * 错误码在 Location 里；响应体常常是空的 HTML 跳转页。
 *
 * 返回 null 表示「这一跳没有携带任何错误信息」（例如正常返回 `?code=...`）。
 */
internal fun parseAuthorizeError(location: String, body: String): QQAuthorizeError? =
    parseAuthorizeErrorFromLocation(location) ?: parseAuthorizeErrorFromBody(body)

private fun parseAuthorizeErrorFromLocation(location: String): QQAuthorizeError? {
    val params = authorizeQueryParams(location)
    if (params.isEmpty()) return null
    val which = params["which"]?.trim()
    val code = params["error"]?.trim().orEmpty()
    val description = params["error_description"]?.trim()
    if (code.isNotEmpty()) {
        return QQAuthorizeError(code = code, description = description, isErrorForm = which == "error")
    }
    if (which == "error") {
        // 错误跳转页但没带 error=：仍然要报出来，不能当成「没有错误」继续往下走。
        return QQAuthorizeError(code = "", description = description, isErrorForm = true)
    }
    return null
}

private fun parseAuthorizeErrorFromBody(body: String): QQAuthorizeError? {
    if (body.isBlank()) return null
    val jsonCode = BODY_ERROR_JSON.find(body)?.groupValues?.get(1)?.trim().orEmpty()
    if (jsonCode.isNotEmpty()) {
        val description = BODY_ERROR_DESCRIPTION_JSON.find(body)?.groupValues?.get(1)
        return QQAuthorizeError(code = jsonCode, description = description, isErrorForm = false)
    }
    val formCode = BODY_ERROR_FORM.find(body)?.groupValues?.get(1)?.trim().orEmpty()
    if (formCode.isNotEmpty()) {
        val description = BODY_ERROR_DESCRIPTION_FORM.find(body)?.groupValues?.get(1)
        return QQAuthorizeError(
            code = formCode,
            description = description?.let { decodeQueryComponent(it) },
            isErrorForm = false,
        )
    }
    return null
}

/**
 * authorize 这一步要写进诊断日志的**证据文本**（入库前还会再整体脱敏一次）。
 *
 * - 响应体无论成功失败都记（以前只在失败时记，成功时干脆不记，等于赌它没有内容）；
 * - 命中错误时把 `Location` 放在**最前面**：诊断片段会被截断到 160 字符，
 *   而 `which=error&error=100012` 恰好是最值钱的那几个字节，不能被响应体挤掉；
 * - 没有 code 又没有错误码时也留下 Location（否则日志只剩一句「没拿到 code」，无从判断）。
 */
internal fun authorizeEvidenceSnippet(
    body: String,
    location: String,
    error: QQAuthorizeError?,
    codePresent: Boolean,
): String {
    val builder = StringBuilder()
    if (error != null && location.isNotEmpty()) {
        builder.append("Location: ").append(location)
    }
    if (body.isNotBlank()) {
        if (builder.isNotEmpty()) builder.append(" | ")
        builder.append(body.trim())
    }
    if (error == null && !codePresent && location.isNotEmpty()) {
        if (builder.isNotEmpty()) builder.append(" | ")
        builder.append("Location: ").append(location)
    }
    return builder.toString()
}

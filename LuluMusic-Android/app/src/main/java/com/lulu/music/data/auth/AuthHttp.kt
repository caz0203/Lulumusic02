package com.lulu.music.data.auth

import com.lulu.music.data.net.BeansApiException
import com.lulu.music.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * 登录流程专用的 HTTP 结果：响应体 + 该次响应的 Set-Cookie + Location。
 *
 * `Http`（对应 iOS 的 URLSession 配置 `httpShouldSetCookies = false`）只返回响应体，
 * 不暴露响应头。QQ 扫码登录必须读取 `Set-Cookie`（qrsig / skey / p_skey / qqmusic_key）
 * 和不跟随 302 时的 `Location`，所以这里在 `Http` 之上补一层薄封装，
 * 不改变任何平台接口的请求行为。
 */
class AuthHttpExchange(
    val bytes: ByteArray,
    val cookies: Map<String, String>,
    /** 响应头 Location（未跟随 302 时用于手动走跳转链，authorize 的 code 就在这里）。 */
    val location: String,
) {
    /** 文本响应体；QQ 的 ptuiCB / JSON 接口都是 UTF-8 文本。 */
    val text: String by lazy { String(bytes, Charsets.UTF_8) }

    /** 解析 JSON 对象；非 JSON（JSONP、ptuiCB('..')）时返回 null。 */
    fun asJsonObject(): JSONObject? = try {
        JSONObject(text.removePrefix("\uFEFF").trim())
    } catch (_: Exception) {
        null
    }
}

/**
 * 执行一次请求并返回响应体、Set-Cookie 与 Location。
 *
 * [followRedirects] 为 false 时不跟随 302：QQ 的 check_sig 跳转链和 oauth2 authorize
 * 都必须手动读取 `Location` 头（authorize 的 code 只出现在 302 响应里），
 * 对应 iOS 里的 `NoRedirectDelegate`（`completionHandler(nil)`）。
 */
suspend fun authExchange(request: Request, followRedirects: Boolean = true): AuthHttpExchange =
    withContext(Dispatchers.IO) {
        try {
            val client = if (followRedirects) Http.client else Http.client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            client.newCall(request).execute().use { response ->
                AuthHttpExchange(
                    bytes = response.body?.bytes() ?: ByteArray(0),
                    cookies = responseCookies(response),
                    location = response.header("Location") ?: "",
                )
            }
        } catch (e: BeansApiException) {
            throw e
        } catch (e: IOException) {
            throw BeansApiException.Network(e.message ?: "network error")
        }
    }

/**
 * 从单次响应中取出全部 Set-Cookie 的 name=value。
 *
 * 部分 CDN 会把多个 cookie 合并成一行（逗号 + 下一个 `name=`），
 * 与 NetEaseApi 的处理保持一致；`Expires=Wed, 21 Oct ...` 里逗号后面跟的是日期
 * 而不是 `name=`，不会被误拆。
 */
fun responseCookies(response: Response): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val headers = response.headers
    for (header in headers.values("Set-Cookie")) {
        if (header.isEmpty()) continue
        for ((name, value) in setCookiePairs(header)) {
            if (name.isEmpty() || value.isEmpty()) continue
            out[name] = value
        }
    }
    return out
}

private fun setCookiePairs(setCookie: String): List<Pair<String, String>> {
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

private val COOKIE_SPLIT_REGEX = Regex(",\\s*(?=[^=;,\\s]+=)")

package com.lulu.music.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Error type mirroring the iOS `NetEaseError` / platform API errors. */
sealed class BeansApiException(message: String) : Exception(message) {
    class Network(message: String) : BeansApiException(message)
    class HttpStatus(val code: Int, val snippet: String) :
        BeansApiException("HTTP $code: $snippet")
    class Decoding(val snippet: String) : BeansApiException("decode failed: $snippet")
    class Unknown(message: String) : BeansApiException(message)
}

/**
 * Shared HTTP layer.
 *
 * Mirrors the iOS design: cookies are NOT handled automatically by the session
 * (`httpShouldSetCookies = false`); each platform API stores and sends the cookie
 * header it needs, because NetEase/QQ/Kugou each require their own cookie shape.
 */
object Http {

    private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 测试专用的「离线闸门」，**默认关闭**。
     *
     * 生产代码从不修改它：全仓库唯一的赋值点是 `app/src/test/` 里的
     * `TestBeansApplication`（Robolectric 的 Compose 渲染测试）。
     * 置为 true 后本对象里任何真实的网络 I/O 都会立刻抛出
     * [BeansApiException.Network]，不会发出请求 —— 单元测试里绝不允许联网。
     * 调用方本来就按「网络失败」处理它（`runCatching` / `catch (e: Exception)`），
     * 所以置位后 UI 只会退化成空态 / 错误态，行为与断网完全一致。
     *
     * 这就是一个「让测试能确定性地不联网」的开关，不改变任何默认行为：
     * 默认 `false` 时分支永远不会走到，与加这个字段之前逐字节等价。
     */
    @Volatile
    private var offlineMode: Boolean = false

    /** 打开 [offlineMode]：只给测试用（见上）。 */
    fun markOffline() {
        offlineMode = true
    }

    /**
     * 测试用的联网守卫：离线时抛出与真实断网同一种异常，且**在网络 I/O 之前**抛出。
     *
     * 放在这里是为了让「离线」只有一处真相（[offlineMode]）；各平台 API 在自己的
     * 底层请求入口调用它。
     */
    internal fun guardNetwork() {
        if (offlineMode) throw BeansApiException.Network("测试环境已禁用网络（Http.offlineMode = true）")
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Long-lived client for audio streaming / large downloads. */
    val streamClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Percent-encode exactly like the iOS `formEncode` (alphanumerics + `-._~`). */
    fun formEncode(value: String): String {
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    fun buildForm(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { "${formEncode(it.key)}=${formEncode(it.value)}" }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        guardNetwork()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw BeansApiException.HttpStatus(response.code, body.take(120))
                }
                body
            }
        } catch (e: BeansApiException) {
            throw e
        } catch (e: IOException) {
            throw BeansApiException.Network(e.message ?: "network error")
        }
    }

    private fun headersOf(headers: Map<String, String>, builder: Request.Builder) {
        for ((k, v) in headers) {
            if (v.isNotEmpty()) builder.header(k, v)
        }
    }

    /** POST an already-encoded form body and return the raw text response. */
    suspend fun postFormText(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val builder = Request.Builder().url(url).post(body.toRequestBody(FORM))
        headersOf(headers, builder)
        return execute(builder.build())
    }

    /** POST a form and parse the response as a JSON object. */
    suspend fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = parseObject(postFormText(url, buildForm(fields), headers))

    /** POST a raw string body (used by eapi, which encodes params itself). */
    suspend fun postRaw(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject = parseObject(postFormText(url, body, headers))

    /** POST a JSON body. */
    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val builder = Request.Builder().url(url).post(json.toRequestBody(JSON))
        headersOf(headers, builder)
        return parseObject(execute(builder.build()))
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).get()
        headersOf(headers, builder)
        return execute(builder.build())
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        parseObject(getText(url, headers))

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            guardNetwork()
            val builder = Request.Builder().url(url).get()
            headersOf(headers, builder)
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw BeansApiException.HttpStatus(response.code, "")
                    }
                    response.body?.bytes() ?: ByteArray(0)
                }
            } catch (e: BeansApiException) {
                throw e
            } catch (e: IOException) {
                throw BeansApiException.Network(e.message ?: "network error")
            }
        }

    private fun parseObject(text: String): JSONObject = try {
        // Some endpoints prepend a JSONP-ish wrapper or BOM; trim defensively.
        JSONObject(text.trim().removePrefix("\uFEFF"))
    } catch (e: Exception) {
        throw BeansApiException.Decoding(text.take(120))
    }

    /** Tolerant JSON array parse for endpoints that may return a bare array. */
    fun parseArray(text: String): JSONArray = try {
        JSONArray(text.trim())
    } catch (e: Exception) {
        throw BeansApiException.Decoding(text.take(120))
    }
}

// ---------------------------------------------------------------------------
// org.json convenience helpers (mirrors Swift's `[String: Any]` navigation)
// ---------------------------------------------------------------------------

fun JSONObject.optMap(key: String): JSONObject? =
    if (isNull(key)) null else optJSONObject(key)

fun JSONObject.str(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

fun JSONObject.int(key: String, fallback: Int = 0): Int =
    if (isNull(key)) fallback else optInt(key, fallback)

fun JSONObject.long(key: String, fallback: Long = 0L): Long =
    if (isNull(key)) fallback else optLong(key, fallback)

fun JSONObject.dbl(key: String, fallback: Double = 0.0): Double =
    if (isNull(key)) fallback else optDouble(key, fallback)

fun JSONObject.bool(key: String, fallback: Boolean = false): Boolean =
    if (isNull(key)) fallback else optBoolean(key, fallback)

fun JSONObject.arr(key: String): JSONArray? =
    if (isNull(key)) null else optJSONArray(key)

fun JSONObject.arrOrEmpty(key: String): JSONArray = arr(key) ?: JSONArray()

fun JSONArray.objAt(index: Int): JSONObject? =
    if (index in 0 until length()) optJSONObject(index) else null

fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { if (isNull(it)) null else optString(it) }

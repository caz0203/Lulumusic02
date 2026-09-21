package com.lulu.music.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lulu.music.BeansApplication
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.ThirdPartyAudioQuality
import com.lulu.music.data.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LX Music JS 音源执行器（port of iOS `LXScriptSourceRunner` / `BeansLXScriptRuntime`）。
 *
 * iOS 用 JavaScriptCore 跑脚本；Android 用**隐藏的 WebView**（V8）跑：
 * `@JavascriptInterface` 方法会被 JS 同步调用，正好满足 `lx.request(url, options, callback)`
 * 这种「同步等结果」的调用约定，不需要额外引入 JS 引擎依赖。
 *
 * 线程模型：
 *  - WebView 的创建、`evaluateJavascript` 必须在主线程；
 *  - WebView 里的 JS 跑在渲染进程，它同步回调进来的 `@JavascriptInterface` 方法执行在
 *    WebView 的 JavaBridge 线程上，所以桥里做阻塞网络请求**不会**卡住主线程（不会 ANR）；
 *  - 所有 WebView 交互用 [gate] 串行化，避免多个音源互相覆盖全局的 `module` / `__beansPlugin`。
 *
 * WebView 是懒加载的：`init` 只记住 Context，第一次真正 [resolve] 时才在主线程创建。
 */
private const val TAG = "LuluLxScript"

/** `addJavascriptInterface` 注册的名字，JS 侧通过 globalThis.<name> 访问。 */
private const val BRIDGE_NAME = "__beansNativeBridge"

/** 桥接 HTTP 默认超时，对应 iOS 里 8s 请求 / 12s 资源。 */
private const val DEFAULT_HTTP_TIMEOUT_MS = 8_000L

object LxScriptRunner {

    private const val BASE_URL = "https://lx-music.local/"
    private const val BLANK_HTML =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>"

    /** 桌面版 UA：部分音源会按 UA 返回不同的接口分支。 */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private const val DEFAULT_VERSION = "1.6.6"

    /** 单次脚本调用超时，对应 iOS `invokeRequest(timeout: 12)`。 */
    private const val SCRIPT_TIMEOUT_MS = 12_000L

    /** 注入引导脚本 / 首次执行音源脚本的超时。 */
    private const val PREPARE_TIMEOUT_MS = 8_000L

    /** 整体兜底超时，保证再离谱的脚本也不会把播放请求挂死。 */
    private const val TOTAL_TIMEOUT_MS = 20_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 所有 WebView 交互串行；同时保证 `pendingCalls` / `preparedKeys` 的可见性。 */
    private val gate = Mutex()

    @Volatile
    private var appContext: Context? = null

    /** 仅主线程访问。 */
    private var webView: WebView? = null

    /** 首个空白页加载完成信号；`evaluateJavascript` 早于页面就绪时可能被丢弃。 */
    private var pageReady: CompletableDeferred<Boolean>? = null

    private var bootstrapDone = false
    private val preparedKeys = HashSet<String>()

    private val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val callSeq = AtomicLong()

    /**
     * 只保存 application context（幂等，可在 `Application.onCreate` 里调用）。
     * 刻意不在这里创建 WebView：构造 WebView 要几十毫秒和可观内存，
     * 没用过脚本音源的用户不该在冷启动时付这笔钱。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 跑一个脚本音源，返回可播放地址或 null；永不抛异常。 */
    suspend fun resolve(
        source: ThirdPartySource,
        song: Song,
        quality: BeansAudioQuality,
    ): String? = resolveForQuality(source, song, thirdPartyQuality(quality).raw, null)

    /**
     * [UnblockService] 内部使用：按第三方音质档位 + 指定歌曲 ID 调用脚本。
     * iOS 的脚本音源会为 QQ 依次尝试 songmid / media_mid / 数字 ID，因此需要显式传入 songID。
     */
    internal suspend fun resolveForQuality(
        source: ThirdPartySource,
        song: Song,
        qualityRaw: String,
        songID: String?,
    ): String? = runCatching {
        val script = source.script?.trim().orEmpty()
        if (script.isEmpty()) return@runCatching null

        val payload = buildPayload(source, song, qualityRaw, songID)
        val raw = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { invoke(source, script, payload) }
            ?: return@runCatching null
        val value = decodeScriptResult(raw, source) ?: return@runCatching null
        extractURLString(value)
    }.getOrNull()

    /** 销毁 WebView；没创建过也安全。 */
    fun release() {
        pendingCalls.values.forEach { it.cancel() }
        pendingCalls.clear()
        mainHandler.post {
            runCatching {
                webView?.let { view ->
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.removeJavascriptInterface(BRIDGE_NAME)
                    view.destroy()
                }
            }
            webView = null
            pageReady = null
            bootstrapDone = false
            preparedKeys.clear()
        }
    }

    // ---------------------------------------------------------------------
    // WebView 生命周期
    // ---------------------------------------------------------------------

    private suspend fun ensureWebView(): WebView? {
        val view = withContext(Dispatchers.Main) { createWebViewOnMain() } ?: return null
        // 空白页没加载完就 evaluateJavascript，注入可能被丢弃。
        withContext(Dispatchers.Main) { pageReady }?.let {
            withTimeoutOrNull(PREPARE_TIMEOUT_MS) { it.await() }
        }
        return view
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewOnMain(): WebView? {
        webView?.let { return it }
        val context = appContext ?: runCatching { BeansApplication.instance }.getOrNull() ?: return null
        return try {
            val view = WebView(context)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = DESKTOP_UA
            }
            view.addJavascriptInterface(BeansLxJsBridge(pendingCalls), BRIDGE_NAME)
            pageReady = CompletableDeferred()
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady?.complete(true)
                }
            }
            view.loadDataWithBaseURL(BASE_URL, BLANK_HTML, "text/html", "utf-8", null)
            // 未 attach 到窗口时个别 ROM 不会回调 onPageFinished，这里加个兜底。
            mainHandler.postDelayed({ pageReady?.complete(true) }, 2_000)
            webView = view
            view
        } catch (t: Throwable) {
            Log.d(TAG, "WebView 创建失败，脚本音源不可用：${t.message ?: t}")
            null
        }
    }

    // ---------------------------------------------------------------------
    // 脚本调用
    // ---------------------------------------------------------------------

    private suspend fun invoke(
        source: ThirdPartySource,
        script: String,
        payload: JSONObject,
    ): String? = gate.withLock {
        val web = ensureWebView() ?: return@withLock null
        val key = cacheKey(source, script)

        if (!bootstrapDone) {
            val error = decodeJsString(evaluate(web, bootstrapJs()))
            if (!error.isNullOrEmpty()) Log.d(TAG, "引导脚本执行异常：$error")
            bootstrapDone = true
        }

        if (key !in preparedKeys) {
            val error = decodeJsString(evaluate(web, buildPrepareJs(key, script, source)))
            if (!error.isNullOrEmpty()) {
                Log.d(TAG, "第三方脚本执行异常：${source.name} $error")
                return@withLock null
            }
            preparedKeys.add(key)
        }

        val callId = "beans_call_" + callSeq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pendingCalls[callId] = deferred
        try {
            withContext(Dispatchers.Main) {
                runCatching { web.evaluateJavascript(buildInvocationJs(key, callId, payload), null) }
                    .onFailure { Log.d(TAG, "第三方脚本调用下发失败：${source.name} ${it.message ?: it}") }
            }
            val raw = withTimeoutOrNull(SCRIPT_TIMEOUT_MS) { deferred.await() }
            if (raw == null) Log.d(TAG, "第三方脚本调用超时：${source.name} ${SCRIPT_TIMEOUT_MS / 1000}s")
            raw
        } finally {
            pendingCalls.remove(callId)
        }
    }

    /** 在主线程执行一段 JS 并等回结果（`evaluateJavascript` 的结果是 JSON 编码的字符串）。 */
    private suspend fun evaluate(web: WebView, js: String, timeoutMs: Long = PREPARE_TIMEOUT_MS): String? {
        val deferred = CompletableDeferred<String?>()
        withContext(Dispatchers.Main) {
            runCatching {
                web.evaluateJavascript(js) { value -> deferred.complete(value) }
            }.onFailure { deferred.complete(null) }
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    private fun decodeScriptResult(raw: String, source: ThirdPartySource): Any? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!obj.optBoolean("ok", false)) {
            Log.d(TAG, "第三方脚本解析失败：${source.name} ${obj.optString("error")}")
            return null
        }
        if (obj.isNull("value")) return null
        return obj.opt("value")
    }

    private fun cacheKey(source: ThirdPartySource, script: String): String =
        "${source.id}|${script.hashCode()}"

    private fun versionName(): String = runCatching {
        val context = appContext ?: return@runCatching DEFAULT_VERSION
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: DEFAULT_VERSION
    }.getOrDefault(DEFAULT_VERSION)

    // ---------------------------------------------------------------------
    // 请求载荷
    // ---------------------------------------------------------------------

    private fun buildPayload(
        source: ThirdPartySource,
        song: Song,
        qualityRaw: String,
        songID: String?,
    ): JSONObject {
        val provider = providerCode(song.source)
        val id = songID?.takeIf { it.isNotBlank() } ?: song.id.toString()
        val qqMid = song.qqMid?.trim()?.takeIf { it.isNotEmpty() }
        val qqMediaMid = song.qqMediaMid?.trim()?.takeIf { it.isNotEmpty() }
        val primaryID = (if (song.source == SongSource.QQ) qqMid else null) ?: id
        val resolvedHash = (if (song.source == SongSource.KUGOU) {
            song.kugouHash?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }) ?: id
        val artistList = song.artists
            .split('/', '&', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val musicInfo = JSONObject().apply {
            put("id", id)
            put("songId", id)
            put("musicId", primaryID)
            put("musicrid", id)
            put("mid", qqMid ?: primaryID)
            put("songmid", qqMid ?: primaryID)
            put("mediaId", qqMediaMid ?: primaryID)
            put("media_mid", qqMediaMid ?: primaryID)
            put("mediaMid", qqMediaMid ?: primaryID)
            put("strMediaMid", qqMediaMid ?: primaryID)
            put("copyrightId", id)
            put("contentId", id)
            put("hash", resolvedHash)
            put("rid", id)
            put("name", song.name)
            put("songName", song.name)
            put("singer", song.artists)
            put("artist", song.artists)
            put("artists", JSONArray(artistList))
            put("source", provider)
            // iOS 侧拿不到专辑字段只能留空；Android 的 Song 有，就顺手喂给脚本。
            put("album", song.album)
            put("albumName", song.album)
            put("albumId", "")
            put("albumAudioId", "")
            put("interval", song.duration.takeIf { it > 0 }?.toInt()?.toString() ?: "")
            put("_types", JSONObject())
            put("meta", JSONObject())
        }

        return JSONObject().apply {
            put("action", "musicUrl")
            put("source", provider)
            put("info", JSONObject().apply {
                put("type", qualityRaw)
                put("musicInfo", musicInfo)
            })
        }
    }

    private fun providerCode(source: SongSource): String = when (source) {
        SongSource.NET_EASE -> "wy"
        SongSource.QQ -> "tx"
        SongSource.KUGOU -> "kg"
    }

    private fun thirdPartyQuality(quality: BeansAudioQuality): ThirdPartyAudioQuality = when (quality) {
        BeansAudioQuality.STANDARD -> ThirdPartyAudioQuality.KB128
        BeansAudioQuality.HIGHER -> ThirdPartyAudioQuality.KB320
        BeansAudioQuality.EXHIGH -> ThirdPartyAudioQuality.KB320
        BeansAudioQuality.LOSSLESS -> ThirdPartyAudioQuality.FLAC
        BeansAudioQuality.HIRES -> ThirdPartyAudioQuality.HIRES
    }

    // ---------------------------------------------------------------------
    // 结果里挑播放地址（对应 iOS `extractURLString`）
    // ---------------------------------------------------------------------

    private fun extractURLString(raw: Any?): String? {
        if (raw is String) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
            if (trimmed.startsWith("//")) return "https:$trimmed"
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                val parsed = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
                if (parsed != null) return extractURLString(parsed)
            }
            return null
        }
        if (raw is JSONArray) {
            for (index in 0 until raw.length()) {
                extractURLString(raw.opt(index))?.let { return it }
            }
            return null
        }
        if (raw is JSONObject) {
            for (path in URL_PATHS) {
                valueAtPath(raw, path)?.let { value ->
                    extractURLString(value)?.let { return it }
                }
            }
            // 有些源会再包一层；只在疑似载荷容器里递归，避免把封面图地址当成播放地址。
            for (key in RECURSION_KEYS) {
                if (!raw.has(key) || raw.isNull(key)) continue
                extractURLString(raw.opt(key))?.let { return it }
            }
        }
        return null
    }

    private fun valueAtPath(obj: Any, path: String): Any? {
        var current: Any? = obj
        for (key in path.split('.')) {
            current = when (val node = current) {
                is JSONObject -> if (node.has(key) && !node.isNull(key)) node.opt(key) else return null
                else -> return null
            }
        }
        return current
    }

    private val URL_PATHS = listOf(
        "musicUrl", "musicurl", "music", "url", "play_url", "playUrl", "src",
        "audioUrl", "audio_url", "link", "purl",
        "data.url", "data.musicUrl", "data.musicurl", "data.music", "data.play_url",
        "data.playUrl", "data.src", "data.audioUrl", "data.audio_url", "data.link",
        "result.url", "result.musicUrl", "result.musicurl", "result.music", "result.play_url",
        "result.playUrl", "result.src", "result.audioUrl", "result.link",
    )

    private val RECURSION_KEYS = listOf("data", "result", "response", "body", "payload", "musicInfo")

    // ---------------------------------------------------------------------
    // 注入的 JS
    // ---------------------------------------------------------------------

    /**
     * 引导脚本：global / self / console / module+exports / lx 垫片，
     * fetch / customFetch / Buffer / Promise.any 补丁，以及 cerumusic 别名。
     *
     * 与 iOS 的差异（都跟 WebView 环境有关）：
     *  - `fetch` / `customFetch` 无条件覆盖：WebView 自带的 fetch 走 CORS，跨域接口必然失败，
     *    必须改成走 `lx.request`（OkHttp 桥）；
     *  - `lx.env` 如实上报 android，而不是假装 iOS。
     */
    private fun bootstrapJs(): String = """
(function () {
    var g = globalThis;
    if (!g.globalThis) { try { g.globalThis = g; } catch (error) {} }
    if (typeof g.global === 'undefined') { g.global = g; }
    if (typeof g.self === 'undefined') { g.self = g; }
    if (typeof g.window === 'undefined') { g.window = g; }
    if (typeof g.console === 'undefined') {
        g.console = { log: function () {}, info: function () {}, debug: function () {}, warn: function () {}, error: function () {} };
    }
    if (!g.__beansHandlersByKey) { g.__beansHandlersByKey = {}; }
    if (!g.__beansPlugins) { g.__beansPlugins = {}; }
    if (!g.__beansMusicPlugins) { g.__beansMusicPlugins = {}; }
    if (typeof g.module === 'undefined') { g.module = { exports: {} }; g.exports = g.module.exports; }

    var nativeBridge = g.$BRIDGE_NAME;

    var utf8Encode = function (text) {
        if (typeof TextEncoder === 'function') { return new TextEncoder().encode(text); }
        var escaped = unescape(encodeURIComponent(text));
        var out = new Uint8Array(escaped.length);
        for (var i = 0; i < escaped.length; i++) { out[i] = escaped.charCodeAt(i) & 0xff; }
        return out;
    };
    var utf8Decode = function (bytes) {
        if (typeof TextDecoder === 'function') {
            try { return new TextDecoder('utf-8', { fatal: false }).decode(bytes); } catch (error) {}
        }
        var binary = '';
        for (var i = 0; i < bytes.length; i++) { binary += String.fromCharCode(bytes[i]); }
        try { return decodeURIComponent(escape(binary)); } catch (error) { return binary; }
    };
    var bytesToBase64 = function (bytes) {
        var binary = '';
        var chunk = 0x8000;
        for (var i = 0; i < bytes.length; i += chunk) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        return btoa(binary);
    };
    var base64ToBytes = function (text) {
        var binary = atob(String(text || '').replace(/[^A-Za-z0-9+/=]/g, ''));
        var out = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) { out[i] = binary.charCodeAt(i) & 0xff; }
        return out;
    };
    var hexToBytes = function (text) {
        var clean = String(text || '').replace(/0x/gi, '').replace(/[^0-9a-fA-F]/g, '');
        if (clean.length % 2 !== 0) { clean = '0' + clean; }
        var out = new Uint8Array(clean.length / 2);
        for (var i = 0; i < out.length; i++) { out[i] = parseInt(clean.substr(i * 2, 2), 16); }
        return out;
    };
    var bytesToHex = function (bytes) {
        var out = '';
        for (var i = 0; i < bytes.length; i++) {
            var part = bytes[i].toString(16);
            out += part.length === 1 ? '0' + part : part;
        }
        return out;
    };
    var bufferToString = function (buffer, encoding) {
        var enc = String(encoding || 'utf8').toLowerCase();
        if (enc === 'base64') { return buffer.base64 || ''; }
        if (enc === 'hex') { return buffer.hex || ''; }
        return buffer.text || '';
    };
    var wrapBuffer = function (buffer) {
        if (!buffer) { return buffer; }
        buffer.toString = function (encoding) { return bufferToString(buffer, encoding); };
        return buffer;
    };
    var bufferFrom = function (value, encoding) {
        if (value && value.__beansBuffer) { return wrapBuffer(value); }
        var enc = String(encoding || 'utf8').toLowerCase();
        var text = (value === null || typeof value === 'undefined') ? '' : String(value);
        var bytes;
        if (enc === 'base64') { bytes = base64ToBytes(text); }
        else if (enc === 'hex') { bytes = hexToBytes(text); }
        else { bytes = utf8Encode(text); }
        return wrapBuffer({
            __beansBuffer: true,
            base64: bytesToBase64(bytes),
            hex: bytesToHex(bytes),
            text: utf8Decode(bytes)
        });
    };

    var beansUtils = {
        buffer: {
            from: function (value, encoding) { return bufferFrom(value, encoding); },
            bufToString: function (buffer, encoding) { return bufferToString(buffer, encoding); }
        },
        crypto: {
            md5: function (value) {
                try { return String(nativeBridge.md5(String(value))); } catch (error) { return ''; }
            },
            aesEncrypt: function (data, mode, key, iv) {
                try {
                    return wrapBuffer(JSON.parse(nativeBridge.aesEncrypt(
                        String(data === undefined || data === null ? '' : data),
                        String(mode || ''),
                        String(key || ''),
                        String(iv || '')
                    )));
                } catch (error) {
                    return bufferFrom('', 'utf8');
                }
            }
        }
    };

    var beansEnv = {
        platform: 'android',
        os: 'Android',
        device: 'Android',
        isMobile: true
    };
    beansEnv.toString = function () { return String(this.platform || 'android'); };

    var lx = {
        EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
        env: beansEnv,
        utils: beansUtils,
        version: '${versionName()}',
        on: function (event, handler) {
            var key = g.__beansCurrentKey;
            if (key) {
                var bucket = g.__beansHandlersByKey[key] || (g.__beansHandlersByKey[key] = {});
                bucket[String(event)] = handler;
            }
            return true;
        },
        send: function (event, payload) {
            var text = 'null';
            try { text = JSON.stringify(typeof payload === 'undefined' ? null : payload); } catch (error) { text = 'null'; }
            try { nativeBridge.onEvent(String(event), text, g.__beansCurrentKey || g.__beansLastKey || ''); } catch (error) {}
            return true;
        },
        request: function (url, options, callback) {
            if (typeof options === 'function') { callback = options; options = {}; }
            options = options || {};
            var envelope;
            try {
                envelope = JSON.parse(nativeBridge.request(JSON.stringify({
                    url: String(url),
                    method: options.method || 'GET',
                    headers: options.headers || null,
                    body: (typeof options.body === 'undefined') ? null : options.body,
                    timeout: options.timeout || 0,
                    responseType: options.responseType || ''
                })));
            } catch (error) {
                envelope = { error: { message: 'bridge error: ' + error }, response: null };
            }
            var requestError = envelope.error || null;
            var response = envelope.response || null;
            if (typeof callback === 'function') { callback(requestError, response); return; }
            return new Promise(function (resolve, reject) {
                if (requestError && requestError.message) { reject(requestError); }
                else { resolve(response); }
            });
        }
    };
    Object.defineProperty(lx, 'currentScriptInfo', {
        enumerable: true,
        get: function () { return g.__beansCurrentScriptInfo || {}; }
    });
    g.lx = lx;

    // WebView 自带 fetch 受 CORS 限制，必须整体替换成走 lx.request 的实现。
    g.fetch = function (url, options) {
        return g.lx.request(url, options || {}).then(function (response) {
            return {
                ok: response.ok,
                status: response.statusCode,
                statusCode: response.statusCode,
                headers: response.headers,
                text: function () {
                    return Promise.resolve(response.bodyText || String(response.body || ''));
                },
                json: function () {
                    return Promise.resolve(
                        typeof response.body === 'string' ? JSON.parse(response.body) : response.body
                    );
                }
            };
        });
    };
    g.customFetch = function (url, options) {
        return g.lx.request(url, options || {}).then(function (response) {
            if (response && typeof response.bodyText === 'string') { return response.bodyText; }
            if (response && typeof response.body === 'string') { return response.body; }
            return JSON.stringify(response && response.body ? response.body : {});
        });
    };

    g.Buffer = {
        __beansBuffer: true,
        from: function (value, encoding) { return bufferFrom(value, encoding); },
        isBuffer: function (value) { return !!(value && value.__beansBuffer); }
    };

    if (typeof Promise.any !== 'function') {
        Promise.any = function (iterable) {
            return new Promise(function (resolve, reject) {
                var values = Array.prototype.slice.call(iterable || []);
                if (!values.length) { reject(new Error('All promises were rejected')); return; }
                var pending = values.length;
                var errors = [];
                values.forEach(function (value, index) {
                    Promise.resolve(value).then(resolve, function (error) {
                        errors[index] = error;
                        pending -= 1;
                        if (pending === 0) { reject(new Error('All promises were rejected')); }
                    });
                });
            });
        };
    }
    if (!JSON.__beansOriginalParse) {
        JSON.__beansOriginalParse = JSON.parse;
        JSON.parse = function (value) {
            return value !== null && typeof value === 'object' ? value : JSON.__beansOriginalParse(value);
        };
    }

    g.cerumusic = {
        request: function (url, options, callback) { return g.lx.request(url, options, callback); },
        utils: g.lx.utils,
        env: g.lx.env,
        version: g.lx.version,
        get currentScriptInfo() { return g.__beansCurrentScriptInfo || {}; },
        NoticeCenter: function () {},
        stopRequests: function () {}
    };
})();
""".trimIndent()

    /**
     * 执行音源脚本。
     *
     * iOS 是在独立的 JSContext 里 `evaluateScript`；这里所有音源共用一个页面，
     * 所以用 IIFE 把脚本包起来（`var` 不外泄、异常可捕获），并把 `module` / `exports` /
     * `MusicPlugin` 作为形参传入，这样即使脚本用 `var MusicPlugin = {...}` 也能取回来。
     * 插件对象和 `lx.on` 注册的 handler 按音源 key 存进注册表，等价于 iOS 每个音源一个 runtime。
     */
    private fun buildPrepareJs(key: String, script: String, source: ThirdPartySource): String {
        val info = JSONObject().apply {
            put("name", source.name)
            put("version", source.headers["version"] ?: "")
            put("rawScript", script)
        }
        return """
(function () {
    var g = globalThis;
    var key = ${JSONObject.quote(key)};
    g.__beansCurrentKey = key;
    g.__beansCurrentScriptInfo = ${info.toString()};
    g.__beansEvalError = null;
    g.MusicPlugin = undefined;
    g.__beansLocalMusicPlugin = null;
    var module = { exports: {} };
    try {
        (function (module, exports, lx, globalThis, global, self, window, console, process, require, Buffer, MusicPlugin) {
$script
            g.__beansLocalMusicPlugin = (typeof MusicPlugin === 'undefined') ? null : MusicPlugin;
        })(module, module.exports, g.lx, g, g, g, g, g.console, undefined, undefined, g.Buffer, undefined);
    } catch (error) {
        g.__beansEvalError = String(error && error.message ? error.message : error);
    }
    g.__beansPlugins[key] = (module.exports && typeof module.exports === 'object') ? module.exports : {};
    g.__beansMusicPlugins[key] = g.MusicPlugin || g.__beansLocalMusicPlugin || {};
    g.__beansPlugin = g.__beansPlugins[key];
    g.__beansMusicPlugin = g.__beansMusicPlugins[key];
    g.__beansLastKey = key;
    g.__beansCurrentKey = null;
    return g.__beansEvalError ? String(g.__beansEvalError) : '';
})();
""".trimIndent()
    }

    /**
     * 调用入口，顺序与 iOS 完全一致：
     * 1. 脚本自己通过 `lx.on(EVENT_NAMES.request, handler)` 注册的 handler；
     * 2. `__beansPlugin.musicUrl(source, musicInfo, type)`；
     * 3. `__beansMusicPlugin.getMusicUrl(source, id, type)`；
     * 4. `__beansPlugin.getMusicUrl(source, id, type)`。
     */
    private fun buildInvocationJs(key: String, callId: String, payload: JSONObject): String = """
(function () {
    var g = globalThis;
    var nativeBridge = g.$BRIDGE_NAME;
    var callId = ${JSONObject.quote(callId)};
    var key = ${JSONObject.quote(key)};
    var payload = ${payload.toString()};
    var plugin = (g.__beansPlugins || {})[key] || {};
    var musicPlugin = (g.__beansMusicPlugins || {})[key] || {};
    var handler = ((g.__beansHandlersByKey || {})[key] || {})['request'];
    var finish = function (value) {
        var text;
        try {
            text = JSON.stringify({ ok: true, value: (typeof value === 'undefined' ? null : value) });
        } catch (error) {
            text = '{"ok":false,"error":"result not serializable"}';
        }
        try { nativeBridge.onScriptResult(callId, text); } catch (error) {}
    };
    var fail = function (error) {
        var message = (error && error.message) ? String(error.message) : String(error);
        try { nativeBridge.onScriptResult(callId, JSON.stringify({ ok: false, error: message })); } catch (inner) {}
    };
    try {
        var musicInfo = payload.info.musicInfo;
        var musicId = musicInfo.musicId || musicInfo.songmid || musicInfo.id;
        var invocation;
        if (typeof handler === 'function') {
            invocation = handler(payload);
        } else if (typeof plugin.musicUrl === 'function') {
            invocation = plugin.musicUrl(payload.source, musicInfo, payload.info.type);
        } else if (typeof musicPlugin.getMusicUrl === 'function') {
            invocation = musicPlugin.getMusicUrl(payload.source, musicId, payload.info.type);
        } else if (typeof plugin.getMusicUrl === 'function') {
            invocation = plugin.getMusicUrl(payload.source, musicId, payload.info.type);
        } else {
            fail('第三方脚本没有可用的音乐地址解析入口');
            return;
        }
        Promise.resolve(invocation).then(finish, fail);
    } catch (error) {
        fail(error);
    }
})();
""".trimIndent()

    /** `evaluateJavascript` 的返回值是 JSON 编码的；这里还原成普通字符串。 */
    private fun decodeJsString(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue() as? String ?: raw }.getOrDefault(raw)
    }

}

// -------------------------------------------------------------------------
// 同步 JS 桥。必须是顶层类：Kotlin 的 object 里不能声明 inner class，
// 而 @JavascriptInterface 的方法宿主需要访问 object 的待完成调用表。
// -------------------------------------------------------------------------

/**
 * 暴露给 JS 的同步桥。方法执行在 WebView 的 JavaBridge 线程（不是主线程），
 * 所以这里允许阻塞式网络请求。
 */
private class BeansLxJsBridge(
    private val pendingCalls: ConcurrentHashMap<String, CompletableDeferred<String>>,
) {

    /**
     * `lx.request` 的后端。入参是 JS 侧的 options JSON：
     * `{ url, method, headers, body, timeout, responseType }`。
     *
     * 返回信封 `{ error: {message} | null, response: {...} | null }`，
     * 其中 response 的字段与 iOS 回调里的字典完全一致：
     * `statusCode` / `headers` / `body`（能解析成 JSON 就是对象，否则是文本）/
     * `bodyText`（永远是文本）/ `ok`。
     */
    @JavascriptInterface
    fun request(optionsJson: String): String {
        val options = runCatching { JSONObject(optionsJson) }.getOrNull() ?: JSONObject()
        val url = options.optString("url", "")
        if (url.isBlank()) return envelope("Invalid URL", null)

        val method = options.optString("method", "GET").trim().uppercase().ifEmpty { "GET" }
        val headerMap = LinkedHashMap<String, String>()
        options.optJSONObject("headers")?.let { node ->
            for (key in node.keys()) {
                val value = node.opt(key) ?: continue
                if (value === JSONObject.NULL) continue
                headerMap[key] = value.toString()
            }
        }
        if (headerMap.keys.none { it.equals("User-Agent", true) }) headerMap["User-Agent"] = "lx-music"
        if (headerMap.keys.none { it.equals("Accept", true) }) headerMap["Accept"] = "*/*"

        var contentType = "application/octet-stream"
        var bodyBytes: ByteArray? = null
        val bodyNode = if (options.isNull("body")) null else options.opt("body")
        when (bodyNode) {
            null -> Unit
            is String -> bodyBytes = bodyNode.toByteArray(Charsets.UTF_8)
            is JSONObject -> {
                if (bodyNode.optBoolean("__beansBuffer", false)) {
                    val base64 = bodyNode.optString("base64", "")
                    bodyBytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: ByteArray(0)
                } else {
                    bodyBytes = bodyNode.toString().toByteArray(Charsets.UTF_8)
                    contentType = "application/json"
                    if (headerMap.keys.none { it.equals("Content-Type", true) }) {
                        headerMap["Content-Type"] = "application/json"
                    }
                }
            }
            is JSONArray -> {
                bodyBytes = bodyNode.toString().toByteArray(Charsets.UTF_8)
                contentType = "application/json"
                if (headerMap.keys.none { it.equals("Content-Type", true) }) {
                    headerMap["Content-Type"] = "application/json"
                }
            }
            else -> bodyBytes = bodyNode.toString().toByteArray(Charsets.UTF_8)
        }

        val timeoutMs = options.optLong("timeout", 0L).takeIf { it > 0 } ?: DEFAULT_HTTP_TIMEOUT_MS
        val timeoutSeconds = (timeoutMs / 1000.0).coerceAtLeast(1.0).toLong()

        val request = try {
            val builder = Request.Builder().url(url)
            for ((key, value) in headerMap) builder.header(key, value)
            if (method == "GET" || method == "HEAD") {
                builder.method(method, null)
            } else {
                builder.method(method, (bodyBytes ?: ByteArray(0)).toRequestBody(contentType.toMediaTypeOrNull()))
            }
            builder.build()
        } catch (t: Throwable) {
            return envelope(t.message ?: "Invalid URL", null)
        }

        return try {
            val client = Http.client.newBuilder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: text
                val responseType = options.optString("responseType", "")
                val bodyValue: Any = if (responseType.equals("text", true)) text else parsed
                val headers = JSONObject()
                for (name in response.headers.names()) {
                    headers.put(name, response.headers.values(name).joinToString(", "))
                }
                val responseObject = JSONObject().apply {
                    put("statusCode", response.code)
                    put("headers", headers)
                    put("body", bodyValue)
                    put("bodyText", text)
                    put("ok", response.code in 200..299)
                }
                // iOS 在 >=400 时同时给出 error 和 response，这里保持同样的形状。
                if (response.code >= 400) {
                    envelope("HTTP ${response.code}", responseObject)
                } else {
                    envelope(null, responseObject)
                }
            }
        } catch (t: Throwable) {
            envelope(t.message ?: "request failed", null)
        }
    }

    /** 便捷入口：脚本可以直接调用 `__beansNativeBridge.httpGet(url, headersJson)`。 */
    @JavascriptInterface
    fun httpGet(url: String, headersJson: String): String {
        val options = JSONObject().apply {
            put("url", url)
            put("method", "GET")
            runCatching { JSONObject(headersJson.ifBlank { "{}" }) }.getOrNull()?.let { put("headers", it) }
        }
        return request(options.toString())
    }

    @JavascriptInterface
    fun md5(value: String): String = runCatching {
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /**
     * 对应 iOS 的 `aesEncrypt(data, mode, key, iv)`：AES + PKCS7，
     * key/iv 统一裁剪或补零到 16 字节。返回 Buffer 字典。
     */
    @JavascriptInterface
    fun aesEncrypt(data: String, mode: String, key: String, iv: String): String {
        val usesECB = mode.lowercase().contains("ecb")
        return try {
            val cipher = if (usesECB) {
                Cipher.getInstance("AES/ECB/PKCS5Padding")
            } else {
                Cipher.getInstance("AES/CBC/PKCS5Padding")
            }
            val spec = SecretKeySpec(normalizeKey(key.toByteArray(Charsets.UTF_8)), "AES")
            if (usesECB) {
                cipher.init(Cipher.ENCRYPT_MODE, spec)
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(normalizeKey(iv.toByteArray(Charsets.UTF_8))))
            }
            bufferDictionary(cipher.doFinal(data.toByteArray(Charsets.UTF_8)))
        } catch (t: Throwable) {
            Log.d(TAG, "AES 加密失败：${t.message ?: t}")
            bufferDictionary(ByteArray(0))
        }
    }

    /** 脚本调用完成（对应 iOS 的 `BeansLXScriptDoneBridge`）。 */
    @JavascriptInterface
    fun onScriptResult(callId: String, payloadJson: String) {
        pendingCalls.remove(callId)?.complete(payloadJson)
    }

    /** `lx.send(...)`：只关心脚本上报的 sources，方便排查脚本为什么没生效。 */
    @JavascriptInterface
    fun onEvent(event: String, payloadJson: String, sourceKey: String) {
        if (event != "inited") return
        runCatching {
            val sources = JSONObject(payloadJson).optJSONObject("sources")
            val names = sources?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""
            Log.d(TAG, "第三方脚本初始化：$sourceKey sources=$names")
        }
    }
}

private fun envelope(error: String?, response: JSONObject?): String = JSONObject().apply {
    put("error", if (error == null) JSONObject.NULL else JSONObject().put("message", error))
    put("response", response ?: JSONObject.NULL)
}.toString()

private fun normalizeKey(data: ByteArray): ByteArray =
    if (data.size == 16) data else data.copyOf(16)

private fun bufferDictionary(data: ByteArray): String = JSONObject().apply {
    put("__beansBuffer", true)
    put("base64", Base64.encodeToString(data, Base64.NO_WRAP))
    put("hex", data.joinToString("") { "%02x".format(it) })
    put("text", String(data, Charsets.UTF_8))
}.toString()

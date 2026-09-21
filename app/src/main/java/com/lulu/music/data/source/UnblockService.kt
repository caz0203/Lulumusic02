package com.lulu.music.data.source

import android.content.SharedPreferences
import android.util.Log
import com.lulu.music.BeansApplication
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.ThirdPartyAudioQuality
import com.lulu.music.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 第三方解锁音源解析（port of iOS `UnblockService`）。
 *
 * 职责：拿到一首歌 + 期望音质后，按用户启用的音源顺序逐个尝试，返回第一个可播放的直链。
 * 两类音源：
 *  - 预设音源（template + urlPath）：拼 URL → 发请求 → 按路径从 JSON 里取直链；
 *  - 脚本音源（script 非空）：交给 [LxScriptRunner] 跑 LX Music JS 脚本。
 *
 * 任何单个音源失败都只记日志、继续下一个；公开函数永远不会抛异常。
 */
object UnblockService {

    private const val TAG = "LuluUnblock"
    private const val PREFS_NAME = "beans_unblock"

    /** iOS 侧 URLSession 的 `timeoutIntervalForRequest = 7`。 */
    private const val REQUEST_TIMEOUT_SECONDS = 7L

    private const val ACCEPT_HEADER = "application/json"
    private const val USER_AGENT = "LuluMusic-UserSource/1.0"

    private val API_KEY_PLACEHOLDERS = listOf("{apiKey}", "{apikey}", "{key}")
    private val QUALITY_PLACEHOLDERS = listOf("{quality}", "{br}", "{level}")

    /** 这些 header 是音源自身的元数据，不能直接当请求头发出去。 */
    private val METADATA_HEADER_KEYS = setOf(
        "source", "quality", "qualities", "qualityOptions", "qualitys",
        "br", "level", "apiKey", "apiKeys", "apiKeyQuery",
    )

    /**
     * QQ 直链经常被固定在某个不稳定的 CDN 节点上。
     * 这里不做 Range 探测：部分 QQ CDN 会拒绝探测请求，但播放器带完整请求头仍能正常播放；
     * 真正失败由播放器反馈，再换下一个节点。
     */
    private val QQ_CDN_HOSTS = listOf(
        "isure6.ptqqmusic.gitv.tv",
        "isure.stream.qqmusic.qq.com",
        "dl.stream.qqmusic.qq.com",
        "ws.stream.qqmusic.qq.com",
        "streamoc.music.tc.qq.com",
    )

    /** 加载用户导入的第三方音源配置；重复调用无副作用。 */
    fun load() {
        runCatching { UnblockSourceStore.load() }
            .onFailure { Log.d(TAG, "音源配置加载失败：${it.message ?: it}") }
    }

    /**
     * 按启用顺序尝试所有可用音源，返回第一个可播放地址；全部失败返回 null。
     *
     * iOS 侧是把所有候选源并发扔进 task group、谁先命中用谁。这里改成按顺序串行：
     * 脚本音源共用同一个 WebView，并发执行会互相覆盖全局的 `module` / `__beansPlugin`，
     * 串行既避免了互相踩踏，也保证「先启用的音源优先」这一直觉。
     */
    suspend fun resolve(song: Song, quality: BeansAudioQuality): String? = runCatching {
        if (song.name.isBlank() && song.artists.isBlank()) return@runCatching null

        val sources = UnblockSourceStore.enabledSources.filter { source ->
            source.isScript || canUse(source, song)
        }
        if (sources.isEmpty()) {
            Log.d(TAG, "没有启用的自定义音源：平台=${song.source.raw}")
            return@runCatching null
        }

        // 相同请求指纹只保留第一个，避免重复打同一个服务。
        val seen = HashSet<String>()
        val uniqueSources = sources.filter { seen.add(requestFingerprint(it)) }
        val preferred = thirdPartyQuality(quality)

        for (source in uniqueSources) {
            val url = if (source.isScript) {
                scriptSourceRequest(source, song, preferred)
            } else {
                presetSourceRequest(source, song, preferred)
            }
            if (!url.isNullOrBlank()) return@runCatching url
        }
        null
    }.getOrNull()

    // ---------------------------------------------------------------------
    // 预设音源
    // ---------------------------------------------------------------------

    private fun canUse(source: ThirdPartySource, song: Song): Boolean {
        val expectedProvider = providerCode(song.source)
        val provider = source.headers["source"]
        if (!provider.isNullOrEmpty() && provider != expectedProvider) return false
        return when (song.source) {
            SongSource.QQ -> !song.qqMid.isNullOrEmpty()
            SongSource.KUGOU -> !song.kugouHash.isNullOrEmpty()
            SongSource.NET_EASE -> song.id > 0
        }
    }

    private suspend fun presetSourceRequest(
        source: ThirdPartySource,
        song: Song,
        preferredQuality: ThirdPartyAudioQuality,
    ): String? {
        if (source.template.isBlank()) return null
        val expectedProvider = providerCode(song.source)
        val provider = source.headers["source"]
        if (!provider.isNullOrEmpty() && provider != expectedProvider) return null

        val songIDs: List<String> = when {
            song.source == SongSource.NET_EASE && song.id > 0 -> listOf(song.id.toString())
            song.source == SongSource.QQ -> {
                val mid = song.qqMid
                if (mid.isNullOrEmpty()) return null
                qqIDCandidates(song.id, mid, song.qqMediaMid)
            }
            song.source == SongSource.KUGOU -> {
                val hash = song.kugouHash
                if (hash.isNullOrEmpty()) return null
                listOf(hash)
            }
            else -> return null
        }

        val apiKeys = orderedAPIKeys(source)
        val requiresAPIKey = API_KEY_PLACEHOLDERS.any { source.template.contains(it) }
        if (requiresAPIKey && apiKeys.isEmpty()) {
            Log.d(TAG, "自定义音源缺少请求密钥：${source.name}，已跳过请求")
            return null
        }

        for (songID in songIDs) {
            var baseURLString = source.template
            val idValues = linkedMapOf(
                "{id}" to songID,
                "{songId}" to songID,
                "{songid}" to songID,
                "{songID}" to songID,
                "{songmid}" to (song.qqMid ?: songID),
                "{mid}" to (song.qqMid ?: songID),
                "{hash}" to (song.kugouHash ?: songID),
            )
            for ((placeholder, value) in idValues) {
                baseURLString = baseURLString.replace(placeholder, value)
            }
            baseURLString = baseURLString.replace("{source}", expectedProvider)
            // iOS 用的是 .urlQueryAllowed（不转义 & = ? /）；这里用严格百分号编码，
            // 名字里带 & 或空格时反而不会把查询串截断。
            baseURLString = baseURLString.replace("{name}", urlEncoded(song.name))
            val keyword = listOf(song.name, song.artists)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .trim()
            baseURLString = baseURLString.replace("{keyword}", urlEncoded(keyword))
            baseURLString = baseURLString.replace("{artist}", urlEncoded(song.artists))

            for (quality in qualityCandidates(source, song.source, preferredQuality)) {
                val urlString = replacingQualityPlaceholders(baseURLString, quality)
                if (apiKeys.isNotEmpty()) {
                    for ((originalIndex, apiKey) in apiKeys) {
                        val keyedURLString = API_KEY_PLACEHOLDERS.fold(urlString) { result, placeholder ->
                            result.replace(placeholder, urlEncoded(apiKey))
                        }
                        val resolved = presetSourceRequestOnce(source, keyedURLString, apiKey, quality)
                        if (resolved != null) {
                            rememberWorkingKey(originalIndex, source)
                            return resolved
                        }
                    }
                } else if (!requiresAPIKey) {
                    val resolved = presetSourceRequestOnce(source, urlString, null, quality)
                    if (resolved != null) return resolved
                }
            }
        }

        if (apiKeys.isNotEmpty()) {
            Log.d(TAG, "第三方音源全部密钥未命中：${source.name} 共 ${apiKeys.size} 个")
        }
        return null
    }

    private suspend fun presetSourceRequestOnce(
        source: ThirdPartySource,
        urlString: String,
        apiKey: String?,
        quality: String,
    ): String? {
        val url = urlString.trim().replace(" ", "%20").toHttpUrlOrNull()
        if (url == null) {
            Log.d(TAG, "第三方音源地址非法：${source.name}")
            return null
        }

        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = ACCEPT_HEADER
        headers["User-Agent"] = USER_AGENT
        if (!apiKey.isNullOrEmpty()) headers["X-API-Key"] = apiKey
        val providerHeader = source.headers["source"] ?: ""
        for ((key, value) in source.headers) {
            if (key in METADATA_HEADER_KEYS) continue
            headers[key] = value
                .replace("{quality}", quality)
                .replace("{source}", providerHeader)
        }
        headers["quality"] = quality

        val response = httpGet(url.toString(), headers)
        if (response == null) {
            Log.d(TAG, "第三方音源请求失败：${source.name} 音质=$quality")
            return null
        }
        if (response.first != 200) {
            Log.d(TAG, "第三方音源 HTTP 失败：${source.name} 状态=${response.first}")
            return null
        }

        val obj = try {
            JSONObject(response.second.trim().removePrefix("\uFEFF"))
        } catch (t: Throwable) {
            Log.d(TAG, "第三方音源响应格式错误：${source.name} 音质=$quality")
            return null
        }

        val code = responseCode(obj)
        if (code != null && code != 0 && code != 200) {
            val message = obj.optString("message").ifEmpty { obj.optString("msg") }.ifEmpty { "code=$code" }
            Log.d(TAG, "第三方音源返回失败：${source.name} $message")
            return null
        }

        val value = valueAtAnyPath(obj, source.urlPath) as? String
        if (value.isNullOrBlank()) {
            Log.d(TAG, "第三方音源响应中没有播放地址：${source.name} 音质=$quality")
            return null
        }
        val playURL = playablePlaybackURL(value, source) ?: return null
        Log.d(TAG, "第三方音源命中：${source.name} 音质=$quality 节点=${safeURLSummary(playURL)}")
        return playURL
    }

    /** 本地 OkHttp 调用；刻意不走 Http.getText，因为要按 iOS 那样在非 200 时也读取响应体做日志。 */
    private suspend fun httpGet(
        url: String,
        headers: Map<String, String>,
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url)
            for ((key, value) in headers) {
                if (value.isNotEmpty()) builder.header(key, value)
            }
            val client = Http.client.newBuilder()
                .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            client.newCall(builder.build()).execute().use { response ->
                response.code to (response.body?.string() ?: "")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "第三方音源网络异常：${t.message ?: t}")
            null
        }
    }

    // ---------------------------------------------------------------------
    // 脚本音源
    // ---------------------------------------------------------------------

    private suspend fun scriptSourceRequest(
        source: ThirdPartySource,
        song: Song,
        preferredQuality: ThirdPartyAudioQuality,
    ): String? {
        val script = source.script?.trim().orEmpty()
        if (script.isEmpty()) return null

        val songIDs: List<String> = when (song.source) {
            SongSource.NET_EASE -> {
                if (song.id <= 0) return null
                listOf(song.id.toString())
            }
            SongSource.QQ -> {
                val mid = song.qqMid
                if (mid.isNullOrEmpty()) return null
                val candidates = ArrayList<String>()
                candidates += mid
                song.qqMediaMid?.takeIf { it.isNotEmpty() }?.let { candidates += it }
                if (song.id > 0) candidates += song.id.toString()
                candidates.distinct()
            }
            SongSource.KUGOU -> {
                val hash = song.kugouHash
                if (hash.isNullOrEmpty()) return null
                listOf(hash)
            }
        }

        for (songID in songIDs) {
            for (quality in qualityCandidates(source, song.source, preferredQuality)) {
                val resolved = LxScriptRunner.resolveForQuality(source, song, quality, songID)
                if (!resolved.isNullOrBlank()) {
                    Log.d(TAG, "脚本音源命中：${source.name} 音质=$quality")
                    return resolved
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------------
    // 音质 / 密钥 / 路径工具
    // ---------------------------------------------------------------------

    /**
     * 音质候选：期望音质 → 音源默认音质 → 平台默认(320k)，再按平台能力表过滤。
     * 若音源只声明了非标准档位，至少尝试它声明过的档位，不能越过能力表强行请求未知音质。
     */
    private fun qualityCandidates(
        source: ThirdPartySource,
        songSource: SongSource,
        preferredQuality: ThirdPartyAudioQuality,
    ): List<String> {
        val provider = providerCode(songSource)
        val supported = SourceQualities.supported(source, provider).toSet()
        val platformSupported = ThirdPartyAudioQuality.supported(provider).toSet()
        val sourceDefault = ThirdPartyAudioQuality.fromSourceValue(source.quality)
        val platformDefault = ThirdPartyAudioQuality.KB320

        val ordered = ArrayList<ThirdPartyAudioQuality>()
        ordered += preferredQuality.fallbackChain
        if (sourceDefault != null && sourceDefault != preferredQuality) {
            ordered += sourceDefault.fallbackChain
        }
        if (platformDefault != preferredQuality && platformDefault != sourceDefault) {
            ordered += platformDefault.fallbackChain
        }

        val result = ordered
            .filter { it in platformSupported && (supported.isEmpty() || it in supported) }
            .map { it.raw.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (result.isNotEmpty()) return result

        if (supported.isNotEmpty()) {
            return SourceQualities.supported(source, provider).map { it.raw }
        }
        return ordered.map { it.raw.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun replacingQualityPlaceholders(template: String, quality: String): String =
        QUALITY_PLACEHOLDERS.fold(template) { result, placeholder ->
            result.replace(placeholder, quality)
        }

    private fun qqIDCandidates(songID: Long, songMid: String, mediaMid: String?): List<String> {
        val numericID = if (songID > 0) songID.toString() else null
        return listOfNotNull(numericID, mediaMid, songMid)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun playablePlaybackURL(rawURL: String, source: ThirdPartySource): String? {
        for (candidate in qqPlaybackURLCandidates(rawURL)) {
            val host = candidate.toHttpUrlOrNull()?.host?.lowercase()
            if (host.isNullOrEmpty()) continue
            if (host != rawURL.toHttpUrlOrNull()?.host?.lowercase()) {
                Log.d(TAG, "第三方音源准备 QQ CDN 备用节点：${source.name} 域名=$host")
            }
            Log.d(TAG, "第三方音源选择播放地址：${source.name} ${safeURLSummary(candidate)}")
            return candidate
        }
        return null
    }

    private fun qqPlaybackURLCandidates(url: String): List<String> {
        val parsed = url.toHttpUrlOrNull() ?: return listOf(url)
        val host = parsed.host.lowercase()
        if (!isQQPlaybackHost(host)) return listOf(url)
        return (listOf(host) + QQ_CDN_HOSTS)
            .distinct()
            .mapNotNull { replacement ->
                runCatching { parsed.newBuilder().host(replacement).build().toString() }.getOrNull()
            }
    }

    private fun isQQPlaybackHost(host: String): Boolean =
        host.contains("qq.com") || host.contains("qqmusic") ||
            host.contains("ptqqmusic") || host.contains("gitv.tv")

    private fun safeURLSummary(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.take(72)
        val host = parsed.host.ifEmpty { "?" }
        val path = parsed.encodedPath.ifEmpty { "/" }
        val shortPath = if (path.length > 72) path.take(72) + "..." else path
        return "$host$shortPath"
    }

    private fun responseCode(obj: JSONObject): Int? {
        if (!obj.has("code") || obj.isNull("code")) return null
        return when (val raw = obj.opt("code")) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    /** 多个点分路径取值：`data.music|data.url|url`。 */
    private fun valueAtAnyPath(obj: Any?, paths: String): Any? {
        for (path in paths.split('|')) {
            if (path.isBlank()) continue
            val value = valueAtPath(obj, path)
            if (value != null) return value
        }
        return null
    }

    /**
     * 点分路径取值：`url` / `data.url` / `data.audioUrl` …
     * iOS 版本只走字典；这里额外支持 `data.list.0.url` 这种数组下标，纯属超集，不影响原有字典取值。
     */
    private fun valueAtPath(obj: Any?, path: String): Any? {
        var current: Any? = obj
        for (key in path.split('.')) {
            if (key.isEmpty()) continue
            current = when (val node = current) {
                is JSONObject -> {
                    if (!node.has(key) || node.isNull(key)) return null
                    node.opt(key)
                }
                is JSONArray -> {
                    val index = key.toIntOrNull() ?: return null
                    if (index < 0 || index >= node.length()) return null
                    node.opt(index)
                }
                else -> return null
            }
        }
        return current
    }

    private fun providerCode(source: SongSource): String = when (source) {
        SongSource.NET_EASE -> "wy"
        SongSource.QQ -> "tx"
        SongSource.KUGOU -> "kg"
    }

    private fun urlEncoded(value: String): String = Http.formEncode(value)

    /** 平台音质 → 第三方音质档位。 */
    private fun thirdPartyQuality(quality: BeansAudioQuality): ThirdPartyAudioQuality = when (quality) {
        BeansAudioQuality.STANDARD -> ThirdPartyAudioQuality.KB128
        BeansAudioQuality.HIGHER -> ThirdPartyAudioQuality.KB320
        BeansAudioQuality.EXHIGH -> ThirdPartyAudioQuality.KB320
        BeansAudioQuality.LOSSLESS -> ThirdPartyAudioQuality.FLAC
        BeansAudioQuality.HIRES -> ThirdPartyAudioQuality.HIRES
    }

    // ---------------------------------------------------------------------
    // 请求指纹与多密钥
    // ---------------------------------------------------------------------

    private fun requestFingerprint(source: ThirdPartySource): String {
        val headers = source.headers
            .filterKeys { it != "source" }
            .toSortedMap()
            .entries
            .joinToString("&") { "${it.key}=${it.value}" }
        val scriptFingerprint = source.script?.hashCode()?.toString() ?: ""
        return "${source.template}|${source.urlPath}|$headers|${source.quality}|$scriptFingerprint"
    }

    private fun sourceAPIKeys(source: ThirdPartySource): List<String> {
        val keys = ArrayList<String>()
        val raw = source.headers["apiKeys"]
        if (!raw.isNullOrEmpty()) {
            keys += raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val single = source.headers["apiKey"]?.trim()
        if (!single.isNullOrEmpty()) keys += single
        return keys.distinct()
    }

    /** 返回 (原始下标, 密钥)；上次命中的密钥会被提前到第一位。 */
    private fun orderedAPIKeys(source: ThirdPartySource): List<Pair<Int, String>> {
        val unique = sourceAPIKeys(source).mapIndexed { index, key -> index to key }
        val preferred = prefs()?.getInt(preferredKeyIndexKey(source), -1) ?: -1
        val hit = unique.indexOfFirst { it.first == preferred }
        if (hit <= 0) return unique
        val reordered = ArrayList(unique)
        val item = reordered.removeAt(hit)
        reordered.add(0, item)
        return reordered
    }

    private fun rememberWorkingKey(index: Int, source: ThirdPartySource) {
        runCatching { prefs()?.edit()?.putInt(preferredKeyIndexKey(source), index)?.apply() }
    }

    private fun preferredKeyIndexKey(source: ThirdPartySource): String =
        "beans.unblock.preferredKeyIndex.${source.id}"

    private fun prefs(): SharedPreferences? = runCatching {
        BeansApplication.instance.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }.getOrNull()
}

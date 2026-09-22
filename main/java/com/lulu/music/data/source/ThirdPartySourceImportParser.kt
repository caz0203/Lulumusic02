@file:OptIn(ExperimentalSerializationApi::class)

package com.lulu.music.data.source

import com.lulu.music.data.model.beansLocalized
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 音源导入解析器。
 *
 * 支持从粘贴文本 / 文件内容里识别出 [ThirdPartySource]：
 * 1. JSON 数组、单个 JSON 对象、LX User API 导出的 `{"sources": [...]}` / `{"items": [...]}`；
 * 2. JS 脚本里的 `SERVER_SCRIPT_CONFIG = {...}`、`globalThis['SERVER_SCRIPT_CONFIG'] = {...}`、
 *    `module.exports = {...}`、`export default {...}` 片段；
 * 3. `@name` / `@template` 风格的注释头（C 风格块注释，带 `!` 的元信息块优先；开头的 `//` 行注释头也认）；
 * 4. 纯 JS 脚本文本（LX / Baka 风格），整段文本作为 script 保存。
 *
 * 所有失败路径都返回 [Result] 而不是抛异常。
 *
 * JSON 解析用 kotlinx.serialization（App 已有的依赖）而不是平台 `org.json`：
 * `org.json` 在 Android 上由系统提供，任何非 Android 运行环境（JVM 测试、桌面端复用同一份
 * 解析器）里它只是 android.jar 的桩实现，所有调用都会抛 `RuntimeException: Stub!`。
 * 之前这些异常被 `runCatching` 静默吞掉，于是 **所有 JSON 分支都返回 null**，
 * 单个对象 / 数组 / `{"sources": [...]}` 全都被判成「无法识别该音源格式」。
 */
object ThirdPartySourceImportParser {

    /** 解析结果。[message] 是给用户看的中文摘要（成功或失败原因）。 */
    data class Result(val sources: List<ThirdPartySource>, val message: String)

    /** JSON 里可映射到音源请求头 / 元数据的字段。 */
    private val HEADER_FIELDS = listOf(
        "source", "quality", "br", "apiKey", "apiKeys", "signSalt",
        "fingerprint", "cookie", "tx_cookie", "wy_cookie",
    )

    /** 音质列表字段（LX 生态里几种写法都要认）。 */
    private val QUALITY_FIELDS = listOf("qualities", "qualityOptions", "qualitys")

    /**
     * 从 JS 文本中抠出内嵌配置对象的赋值模式。
     *
     * 只匹配到 `{` 为止，真正的对象边界交给 [balancedObjectAt] 做花括号配对，
     * 这样 `SERVER_SCRIPT_CONFIG = { a: { b: 1 } }` 这种嵌套配置不会被第一个 `}` 截断。
     */
    private val SCRIPT_CONFIG_PATTERNS = listOf(
        Regex("""SERVER_SCRIPT_CONFIG\s*=\s*(\{)"""),
        Regex("""globalThis\[['"]SERVER_SCRIPT_CONFIG['"]\]\s*=\s*(\{)"""),
        Regex("""module\.exports\s*=\s*(\{)"""),
        Regex("""export\s+default\s*(\{)"""),
    )

    /** 判断「这看起来像可执行脚本」的标记。 */
    private val SCRIPT_MARKERS = listOf(
        "globalThis.lx",
        "EVENT_NAMES",
        "request(",
        "on(EVENT_NAMES.request",
        "module.exports",
        "export default",
        "MusicPlugin",
        "customFetch",
        "function",
        "const ",
        "let ",
        "var ",
        "=>",
    )

    /** 判断「这是完整的 LX 脚本」的标记。 */
    private val LX_MARKERS = listOf(
        "globalThis.lx",
        "globalThis['lx']",
        "globalThis[\"lx\"]",
        "EVENT_NAMES.request",
        "EVENT_NAMES['request']",
        "EVENT_NAMES[\"request\"]",
        "on(EVENT_NAMES",
        "lx.request",
    )

    /**
     * 宽松 JSON：允许未加引号的键 / 值、结尾多余逗号，尽量贴近 `JSONSerialization` 的容错度。
     * `parseToJsonElement` 只解析完整的单个 JSON 文档，尾部还有内容会直接报错，
     * 因此 `globalThis.lx = ...` 这类脚本文本不会被误判成 JSON。
     */
    @OptIn(ExperimentalSerializationApi::class)
    private val JSON = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    /**
     * 解析粘贴文本。
     *
     * @param filename 仅用于在文本里没有名字时兜底命名（取去掉扩展名的文件名），
     *   格式判定完全靠内容嗅探：JSON 优先，其次是 LX / JS 脚本。
     */
    fun parse(text: String, filename: String? = null): Result =
        runCatching { parseInternal(text, fallbackNameFrom(filename)) }
            .getOrElse { Result(emptyList(), unsupportedMessage()) }

    private fun parseInternal(text: String, fallbackName: String?): Result {
        // 文件导入时常见的 UTF-8 BOM 会让 JSON / 注释头都识别不了，先去掉。
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return Result(emptyList(), beansLocalized("内容为空", "Content is empty"))

        success(parseJSON(trimmed, fallbackName))?.let { return it }

        // 完整的 LX 脚本里同时含 SERVER_SCRIPT_CONFIG 或 module.exports，
        // 这时要保留可执行脚本本身，而不是只导入它内嵌的 API 配置。
        if (isLXScript(trimmed)) {
            success(parseLooseScript(trimmed, fallbackName))?.let { return it }
        }
        success(parseScript(trimmed, fallbackName))?.let { return it }
        success(parseHeaderStyle(trimmed, fallbackName))?.let { return it }
        success(parseLooseScript(trimmed, fallbackName))?.let { return it }

        return Result(emptyList(), unsupportedMessage())
    }

    private fun success(candidates: List<ThirdPartySource>?): Result? {
        if (candidates.isNullOrEmpty()) return null
        val unique = unique(candidates)
        if (unique.isEmpty()) return null
        return Result(
            unique,
            beansLocalized("已导入 ${unique.size} 个音源", "Imported ${unique.size} sources"),
        )
    }

    private fun unsupportedMessage(): String =
        beansLocalized("无法识别该音源格式", "Unsupported source format")

    // -----------------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------------

    private fun parseJSON(text: String, fallbackName: String?): List<ThirdPartySource>? {
        val root = parseJsonElement(text) ?: return null
        if (root is JsonArray) {
            val sources = root.mapNotNull { parseDictionary(it as? JsonObject, fallbackName) }
            return sources.ifEmpty { null }
        }
        val dict = root as? JsonObject ?: return null
        // LX User API 的导出格式：{"sources": [...]} 或 {"items": [...]}。
        for (key in listOf("sources", "items")) {
            val nested = dict[key] as? JsonArray ?: continue
            val sources = nested.mapNotNull { parseDictionary(it as? JsonObject, fallbackName) }
            if (sources.isNotEmpty()) return sources
        }
        // 既不是数组也不是包装对象时，对象本身就是一条音源配置。
        return parseDictionary(dict, fallbackName)?.let { listOf(it) }
    }

    private fun parseDictionary(dict: JsonObject?, fallbackName: String?): ThirdPartySource? {
        if (dict == null) return null
        val name = stringIn(dict, listOf("name", "title", "sourceName"))
            ?: fallbackName
            ?: beansLocalized("未命名音源", "Untitled source")
        val script = stringIn(dict, listOf("script", "scriptText", "code"))
        val kind = stringIn(dict, listOf("kind", "type"))
            ?: if (script == null) ThirdPartySource.KIND_KEYWORD else ThirdPartySource.KIND_SCRIPT
        val urlPath = stringIn(dict, listOf("urlPath", "path")) ?: "url"
        val template = templateString(dict)
        // 既没有请求模板也没有脚本内容的条目没有意义，直接丢掉。
        if (template.isEmpty() && script.isNullOrEmpty()) return null
        val quality = stringIn(dict, listOf("quality", "br")) ?: "320k"

        val headers = LinkedHashMap<String, String>()
        headers.putAll(dictionaryIn(dict, "headers"))
        val qualities = qualityStrings(dict)
        if (!qualities.isNullOrEmpty()) headers["qualities"] = qualities.joinToString(",")
        for (field in HEADER_FIELDS) {
            val value = stringIn(dict, listOf(field))
            if (!value.isNullOrEmpty()) headers[field] = value
        }
        if (headers["source"] == null) {
            platformCode(dict)?.let { headers["source"] = it }
        }

        return ThirdPartySource(
            name = name,
            kind = kind,
            template = template,
            urlPath = urlPath,
            headers = headers,
            quality = quality,
            script = script,
            enabled = boolIn(dict, listOf("enabled"), defaultValue = true),
        )
    }

    /**
     * 取请求模板：优先显式 `template`；否则用 `apiUrl` 等基地址，
     * 遇到带鉴权字段（apiKey / signSalt / fingerprint）的私有 API 时补上标准直链接口路径。
     */
    private fun templateString(dict: JsonObject): String {
        val explicit = stringIn(dict, listOf("template"))
        if (!explicit.isNullOrEmpty()) return explicit

        val url = stringIn(dict, listOf("apiUrl", "baseURL", "baseUrl", "url", "endpoint"))
        if (url.isNullOrEmpty()) return ""
        if (dict.containsKey("apiKey") || dict.containsKey("signSalt") || dict.containsKey("fingerprint")) {
            val suffix = "/url?source={source}&songId={id}&quality={quality}"
            return if (url.endsWith(suffix)) url else url.trim('/') + suffix
        }
        return url
    }

    // -----------------------------------------------------------------------
    // 脚本 / 注释头
    // -----------------------------------------------------------------------

    private fun parseScript(text: String, fallbackName: String?): List<ThirdPartySource>? {
        for (pattern in SCRIPT_CONFIG_PATTERNS) {
            val json = extractFirstJSONObject(text, pattern) ?: continue
            val dict = parseJsonElement(json) as? JsonObject ?: continue
            val source = parseDictionary(dict, fallbackName) ?: continue
            return listOf(source)
        }
        return null
    }

    /** `@xxx value` 形式的注释头会转成一个音源配置。 */
    private fun parseHeaderStyle(text: String, fallbackName: String?): List<ThirdPartySource>? {
        val block = extractCommentBlock(text) ?: return null
        val values = LinkedHashMap<String, String>()
        for (line in block.lines()) {
            val cleaned = line.trim().trim('*', ' ')
            if (!cleaned.startsWith("@")) continue
            val (key, value) = splitHeaderLine(cleaned.drop(1))
            values[key.lowercase()] = value
        }
        if (values.isEmpty()) return null

        val nameValue = values["name"]
        val name = if (!nameValue.isNullOrEmpty()) {
            nameValue
        } else {
            fallbackName ?: beansLocalized("未命名音源", "Untitled source")
        }
        val kind = values["kind"]?.takeIf { it.isNotEmpty() } ?: ThirdPartySource.KIND_KEYWORD
        val quality = values["quality"]?.takeIf { it.isNotEmpty() }
            ?: values["br"]?.takeIf { it.isNotEmpty() }
            ?: "320k"
        val template = values["template"]?.takeIf { it.isNotEmpty() }
            ?: values["apiurl"]?.takeIf { it.isNotEmpty() }
            ?: values["url"]?.takeIf { it.isNotEmpty() }
            ?: ""
        if (template.isEmpty()) return null
        val urlPath = values["urlpath"]?.takeIf { it.isNotEmpty() } ?: "url"

        val headers = LinkedHashMap<String, String>()
        val headersValue = values["headers"]
        if (!headersValue.isNullOrEmpty()) {
            val headersObject = parseJsonElement(headersValue) as? JsonObject
            if (headersObject != null) {
                for (key in headersObject.keys) {
                    val value = headersObject[key]
                    if (value is JsonPrimitive && value !is JsonNull && value.isString) {
                        headers[key] = value.content
                    }
                }
            } else {
                // 退化成 `k=v;k=v` 或 `k=v,k=v` 的写法。
                for (pair in headersValue.split(',', ';', '\n')) {
                    val parts = pair.split('=', limit = 2)
                    if (parts.size == 2) headers[parts[0].trim()] = parts[1].trim()
                }
            }
        }
        val reserved = setOf("name", "kind", "template", "urlpath", "headers")
        for ((key, value) in values) {
            if (key in reserved) continue
            if (value.isNotEmpty()) headers[key] = value
        }
        if (headers["source"] == null) {
            val platform = values["source"]
            if (!platform.isNullOrEmpty()) headers["source"] = platform
        }

        return listOf(
            ThirdPartySource(
                name = name,
                kind = kind,
                template = template,
                urlPath = urlPath,
                headers = headers,
                quality = quality,
                enabled = boolString(values["enabled"], defaultValue = true),
            )
        )
    }

    /** 兜底：整段文本当成一个可执行脚本音源。 */
    private fun parseLooseScript(text: String, fallbackName: String?): List<ThirdPartySource>? {
        if (!looksLikeScript(text)) return null
        val name = extractHeaderName(text)
            ?: fallbackName
            ?: beansLocalized("未命名脚本音源", "Untitled script source")
        val headers = extractLooseHeaders(text)
        val quality = headers["quality"] ?: headers["br"] ?: "320k"
        return listOf(
            ThirdPartySource(
                name = name,
                kind = ThirdPartySource.KIND_SCRIPT,
                template = "",
                urlPath = "url",
                headers = headers,
                quality = quality,
                script = text,
                enabled = true,
            )
        )
    }

    private fun looksLikeScript(text: String): Boolean = SCRIPT_MARKERS.any { text.contains(it) }

    private fun isLXScript(text: String): Boolean = LX_MARKERS.any { text.contains(it) }

    private fun extractHeaderName(text: String): String? {
        val block = extractCommentBlock(text) ?: return null
        for (line in block.lines()) {
            val cleaned = line.trim().trim('*', ' ')
            if (!cleaned.startsWith("@name")) continue
            val value = cleaned.drop("@name".length).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun extractLooseHeaders(text: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val block = extractCommentBlock(text) ?: return headers
        for (line in block.lines()) {
            val cleaned = line.trim().trim('*', ' ')
            if (!cleaned.startsWith("@")) continue
            val (key, value) = splitHeaderLine(cleaned.drop(1))
            if (value.isNotEmpty()) headers[key.lowercase()] = value
        }
        return headers
    }

    /**
     * 取第一段块注释：带 `!` 的元信息块（LX 脚本常用）优先，否则退回普通块注释；
     * 都没有时再看文本开头连续的 `//` 行注释（`// @name` / `// @template` 这种手写头）。
     */
    private fun extractCommentBlock(text: String): String? {
        val bangStart = text.indexOf("/*!")
        if (bangStart >= 0) {
            val end = text.indexOf("*/", bangStart + 3)
            if (end >= 0) return text.substring(bangStart + 3, end).trim()
        }
        val start = text.indexOf("/*")
        if (start >= 0) {
            val end = text.indexOf("*/", start + 2)
            if (end >= 0) return text.substring(start + 2, end).trim()
        }
        return extractLineCommentBlock(text)
    }

    /**
     * 兜底：文本**开头**连续的 `//` 注释行拼成一段伪注释块。
     *
     * 只取文件开头的连续行注释，遇到第一行非注释内容就停，这样脚本正文里的随机注释
     * 不会被当成元信息头。
     */
    private fun extractLineCommentBlock(text: String): String? {
        val collected = StringBuilder()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() && collected.isEmpty()) continue
            if (!trimmed.startsWith("//")) break
            collected.append(trimmed.removePrefix("//")).append('\n')
        }
        val block = collected.toString().trim()
        return block.ifEmpty { null }
    }

    private fun extractFirstJSONObject(text: String, pattern: Regex): String? {
        val group = pattern.find(text)?.groups?.get(1) ?: return null
        return balancedObjectAt(text, group.range.first)
    }

    /**
     * 从 [start]（必须指向 `{`）开始做花括号配对，返回完整的对象字面量文本。
     * 跳过字符串里的 `{` / `}` 和转义字符，因此嵌套对象 / 数组都能正确切出来。
     */
    private fun balancedObjectAt(text: String, start: Int): String? {
        if (start !in text.indices || text[start] != '{') return null
        var depth = 0
        var quote = ' '
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> inString = false
                }
                continue
            }
            when (char) {
                '"', '\'' -> {
                    inString = true
                    quote = char
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /** 按第一个空格 / 制表符 / 等号切开 `key value` 或 `key=value`。 */
    private fun splitHeaderLine(content: String): Pair<String, String> {
        val index = content.indexOfFirst { it == ' ' || it == '\t' || it == '=' }
        if (index < 0) return content to ""
        return content.substring(0, index) to content.substring(index + 1).trim()
    }

    // -----------------------------------------------------------------------
    // 通用取值
    // -----------------------------------------------------------------------

    /** 解析一段 JSON 文本；任何格式问题都返回 null（绝不抛出）。 */
    private fun parseJsonElement(text: String): JsonElement? =
        runCatching { JSON.parseToJsonElement(text.trim()) }.getOrNull()

    /**
     * 顺序取第一个非空字符串字段，兼容数字 / 布尔写法。
     * 与 JSON 里的 `null` 一样被跳过。
     */
    private fun stringIn(dict: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            val value = dict[key] ?: continue
            if (value is JsonNull) continue
            val primitive = value as? JsonPrimitive ?: continue
            if (primitive.isString) {
                val trimmed = primitive.content.trim()
                if (trimmed.isNotEmpty()) return trimmed
            } else {
                return primitive.content
            }
        }
        return null
    }

    /** 读取一个内嵌对象，只保留字符串值。 */
    private fun dictionaryIn(dict: JsonObject, key: String): Map<String, String> {
        val raw = dict[key] as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (nestedKey in raw.keys) {
            val value = raw[nestedKey]
            if (value is JsonPrimitive && value !is JsonNull && value.isString) {
                result[nestedKey] = value.content
            }
        }
        return result
    }

    /** 布尔字段：支持真正的布尔、0/1 数字以及 `"true"/"yes"/"on"/"enabled"` 之类的字符串。 */
    private fun boolIn(dict: JsonObject, keys: List<String>, defaultValue: Boolean): Boolean {
        for (key in keys) {
            val value = dict[key] ?: continue
            if (value is JsonNull) continue
            val primitive = value as? JsonPrimitive ?: continue
            if (primitive.isString) return boolString(primitive.content, defaultValue)
            when (primitive.content.lowercase()) {
                "true" -> return true
                "false" -> return false
            }
            primitive.content.toDoubleOrNull()?.let { return it != 0.0 }
        }
        return defaultValue
    }

    private fun boolString(value: String?, defaultValue: Boolean): Boolean {
        val normalized = value?.trim()?.lowercase()
        if (normalized.isNullOrEmpty()) return defaultValue
        return when (normalized) {
            "1", "true", "yes", "on", "enabled" -> true
            "0", "false", "no", "off", "disabled" -> false
            else -> defaultValue
        }
    }

    /** 把平台别名归一成脚本里使用的 source 代码。 */
    private fun platformCode(dict: JsonObject): String? {
        val platform = stringIn(dict, listOf("source", "platform", "provider", "musicSource"))?.lowercase()
        return when (platform) {
            "netease", "wy", "neteasecloudmusic", "cloud" -> "wy"
            "qq", "tx", "qqmusic" -> "tx"
            "kugou", "kg" -> "kg"
            else -> null
        }
    }

    /**
     * 取音质列表：接受数组（字符串或数字混排）和单个字符串。
     * 返回的是原始文本，交给 [SourceQualities.explicitQualities] 做归一化。
     */
    private fun qualityStrings(dict: JsonObject): List<String>? {
        for (key in QUALITY_FIELDS) {
            val value = dict[key] ?: continue
            if (value is JsonNull) continue
            when (value) {
                is JsonArray -> {
                    val normalized = value.mapNotNull { element ->
                        val primitive = element as? JsonPrimitive ?: return@mapNotNull null
                        if (primitive is JsonNull) null else primitive.content.trim().ifEmpty { null }
                    }
                    if (normalized.isNotEmpty()) return normalized
                }
                is JsonPrimitive -> {
                    val text = value.content.trim()
                    if (text.isNotEmpty()) return listOf(text)
                }
                else -> Unit
            }
        }
        return null
    }

    /**
     * 按「名字|类型|模板|urlPath|音质|脚本|头」指纹去重，保留首次出现的那条。
     *
     * iOS 这里用 `String.hashValue` 当脚本指纹，而 Swift 的 hashValue 每个进程都会变；
     * 这里直接用脚本文本本身，去重语义相同而且稳定。
     */
    private fun unique(sources: List<ThirdPartySource>): List<ThirdPartySource> {
        val seen = HashSet<String>()
        return sources.filter { source ->
            val headersFingerprint = source.headers.entries
                .sortedBy { it.key }
                .joinToString("&") { "${it.key}=${it.value}" }
            val fingerprint = listOf(
                source.name,
                source.kind,
                source.template,
                source.urlPath,
                source.quality,
                source.script.orEmpty(),
                headersFingerprint,
            ).joinToString("|")
            seen.add(fingerprint)
        }
    }

    /** 文件名只用于兜底命名：去掉目录和扩展名。 */
    private fun fallbackNameFrom(filename: String?): String? {
        val trimmed = filename?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val base = trimmed.substringAfterLast('/').substringAfterLast('\\')
        val stem = if (base.contains('.')) base.substringBeforeLast('.') else base
        return stem.ifBlank { null }
    }
}

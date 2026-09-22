package com.lulu.music.data.source

import android.util.Log
import com.lulu.music.data.model.ThirdPartyAudioQuality
import com.lulu.music.data.net.userFacingReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "LuluSourceHealth"

// ---------------------------------------------------------------------------------------------
// MARK: - 状态模型
// ---------------------------------------------------------------------------------------------

/**
 * 第三方音源的健康状态（对应 iOS「音源列表」里名字下方那行结论文字）。
 *
 *  - [Unknown]：**还没做过任何检查**，UI 只显示一行中性的灰字提示；
 *  - [Ok]：[detail] 是给用户看的一句结论（`配置音源已识别` / `LX 脚本已加载`）；
 *  - [Failed]：[reason] 是失败原因（`缺少 URL 模板或脚本` / `URL 模板不是 http 地址` …）。
 *
 * 刻意没有「在线可用」状态：不联网就不可能知道某个第三方接口今天是否还活着，
 * 与其编一个假的绿灯，不如把检查范围诚实地限定在「结构 + 脚本能否解析」上。
 */
sealed interface SourceHealth {
    data object Unknown : SourceHealth
    data class Ok(val detail: String) : SourceHealth
    data class Failed(val reason: String) : SourceHealth
}

/** [SourceHealth] 的展示文案（[SourceHealth.Unknown] 为空串，由 UI 换成「尚未检查」提示）。 */
val SourceHealth.displayText: String
    get() = when (this) {
        SourceHealth.Unknown -> ""
        is SourceHealth.Ok -> detail
        is SourceHealth.Failed -> reason
    }

// ---------------------------------------------------------------------------------------------
// MARK: - 能力推导（「支持：…」那一行）
// ---------------------------------------------------------------------------------------------

/** 一个平台的能力：平台代码 + 展示名 + 该平台下音源声明的音质档位。 */
data class SourcePlatformCapability(
    val code: String,
    val title: String,
    val qualities: List<ThirdPartyAudioQuality>,
)

/**
 * 音源声明的「平台 + 音质」能力。
 *
 * [capabilities] 为空表示**一个平台都没声明**（没有 `source` / `platform` 头，或取值无法识别）；
 * 按 iOS 的语义这等价于「适用于所有平台」，UI 用 [SourceHealthChecker.capabilitySummary] 展开。
 */
data class SourceCapabilities(
    val capabilities: List<SourcePlatformCapability>,
    val declaredQualities: List<ThirdPartyAudioQuality>,
    val usesDeclaredQualities: Boolean,
)

/**
 * 第三方音源的**健康检查 + 能力推导**。
 *
 * 单独成文件、不依赖 [UnblockService] 的私有实现：那边只负责解析播放地址，
 * 而平台别名归一化在其内部是 `private`，本文件自带一份等价的小映射表以保持自洽。
 *
 * 所有公开函数都是**纯本地计算**（唯一的例外是 [checkHealth] 的 `deep = true`，
 * 它会用 [LxScriptRunner] 的 WebView 求值一次脚本），**不会发起任何网络请求**。
 */
object SourceHealthChecker {

    /** 模板里必须出现的协议提示；只做「像不像一个可用 URL」的廉价判断。 */
    private const val TEMPLATE_SCHEME_HINT = "http"

    /** 无平台声明（或声明无法识别）时默认覆盖的平台，与 iOS 的「全部平台」一致。 */
    val allPlatformCodes: List<String> = listOf("wy", "tx", "kg")

    /** 导入器可能把音质声明塞进的 headers 键，优先级按顺序。 */
    private val QUALITY_DECLARATION_KEYS = listOf("qualities", "qualityOptions", "qualitys")

    /** 平台别名 → 规范代码；无法识别返回 null。与 `UnblockService` 的映射保持一致。 */
    private fun normalizePlatformCode(raw: String?): String? =
        when (raw?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "wy", "netease", "neteasecloudmusic", "netease_cloud_music", "cloud", "wangyi", "163" -> "wy"
            "tx", "qq", "qqmusic", "qq_music", "tencent" -> "tx"
            "kg", "kugou", "kugoumusic", "kugou_music" -> "kg"
            "kw", "kuwo" -> "kw"
            "mg", "migu" -> "mg"
            "git", "gitee" -> "git"
            else -> null
        }

    private fun splitCodes(raw: String?): List<String> =
        raw?.split(',', ';', '|', '/', ' ', '、')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** 声明里出现的平台原始取值（去空白后非空）。 */
    private fun declaredPlatformRaw(source: ThirdPartySource): List<String> =
        listOf(source.headers["source"], source.headers["platform"])
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }

    /**
     * 音源声明的平台规范代码（去重、保持顺序）。
     * 空列表 = 没声明，或者声明了但一个都认不出来——两种情况在解析侧都按「全部平台」处理。
     */
    fun platformCodes(source: ThirdPartySource): List<String> =
        declaredPlatformRaw(source)
            .flatMap { splitCodes(it) }
            .mapNotNull { normalizePlatformCode(it) }
            .distinct()

    /** 平台代码 → 展示名；未收录的代码原样大写返回。 */
    fun platformTitle(code: String): String = when (code.trim().lowercase(Locale.ROOT)) {
        "wy" -> "网易云音乐"
        "tx" -> "QQ 音乐"
        "kg" -> "酷狗音乐"
        "kw" -> "酷我音乐"
        "mg" -> "咪咕音乐"
        "git" -> "Gitee"
        else -> code.trim().uppercase(Locale.ROOT)
    }

    /**
     * 音源自己写明的音质声明，按优先级取第一个命中的：
     * `headers["qualities"]` → `headers["qualityOptions"]` → `headers["qualitys"]` → 脚本里的 `qualitys: [...]`。
     *
     * 返回 null 表示**没有声明**（此时用平台能力表兜底）；
     * 返回空列表表示**声明了但解析不出有效档位**（此时也不假装它支持全平台音质）。
     */
    fun declaredQualities(source: ThirdPartySource): List<ThirdPartyAudioQuality>? {
        for (key in QUALITY_DECLARATION_KEYS) {
            val raw = source.headers[key]?.takeIf { it.isNotBlank() } ?: continue
            return SourceQualities.explicitQualities(raw)
        }
        val script = source.script?.takeIf { it.isNotBlank() } ?: return null
        return SourceQualities.qualitiesDeclaredInScript(script)
    }

    /** 推导音源的平台 + 音质能力；纯字符串处理。 */
    fun capabilities(source: ThirdPartySource): SourceCapabilities {
        val declared = declaredQualities(source)
        val usesDeclared = declared != null && declared.isNotEmpty()
        val codes = platformCodes(source).ifEmpty { allPlatformCodes }
        val list = codes.map { code ->
            SourcePlatformCapability(
                code = code,
                title = platformTitle(code),
                qualities = qualitiesFor(source, declared, code),
            )
        }
        return SourceCapabilities(
            capabilities = list,
            declaredQualities = declared.orEmpty(),
            usesDeclaredQualities = usesDeclared,
        )
    }

    /**
     * 单个平台的音质集合。
     *
     * 优先用音源自己声明的档位（再按平台能力表过滤一遍，避免给出平台根本不存在的档位）；
     * 没有声明时直接落到 [ThirdPartyAudioQuality.supported] 的平台能力表——
     * 这与解析侧的 `SourceQualities.supported(source, provider)` 是同一套语义。
     */
    private fun qualitiesFor(
        source: ThirdPartySource,
        declared: List<ThirdPartyAudioQuality>?,
        code: String,
    ): List<ThirdPartyAudioQuality> {
        val platform = ThirdPartyAudioQuality.supported(code)
        if (declared.isNullOrEmpty()) return platform
        val filtered = declared.filter { it in platform }
        return filtered.ifEmpty { platform }
    }

    /**
     * 单行摘要：`kg (128k, 320k, flac, hires, atmos, master); tx (128k, 320k, flac)`。
     * 平台多于 [maxPlatforms] 个时省略后半段并注明总数。
     */
    fun capabilitySummary(source: ThirdPartySource, maxPlatforms: Int = 4): String {
        val parts = capabilities(source).capabilities.mapNotNull { cap ->
            cap.qualities.takeIf { it.isNotEmpty() }?.let { qualities ->
                "${cap.code} (${qualities.joinToString(", ") { it.raw }})"
            }
        }
        if (parts.isEmpty()) return ""
        val shown = if (parts.size <= maxPlatforms) parts else parts.take(maxPlatforms)
        val text = shown.joinToString("; ")
        return if (shown.size == parts.size) text else "$text …（共 ${parts.size} 个平台）"
    }

    /**
     * 完整能力清单，供「点一下看全部」的详情弹窗使用；一个平台一行。
     *
     * 平台名与音质档位用代码（`kg` / `flac`）而不是本地化展示名：
     * 这些是音源脚本里的真实取值，用户对照着改脚本时最有用。
     * 头部文案由调用方传入（本文件属于 data 层，不依赖 UI 层的本地化工具）。
     */
    fun capabilityDetailText(
        source: ThirdPartySource,
        header: String,
        basisText: String,
    ): String {
        val caps = capabilities(source)
        val platforms = caps.capabilities.joinToString(" / ") { it.code }.ifEmpty { "-" }
        val lines = caps.capabilities.map { cap ->
            val qualities = cap.qualities.joinToString(", ") { it.raw }.ifEmpty { "-" }
            "• ${cap.code} · ${cap.title}：$qualities"
        }
        return buildString {
            if (header.isNotBlank()) append(header).append('\n')
            append(platforms).append('\n')
            append(basisText).append('\n')
            lines.forEach { append(it).append('\n') }
        }.trim()
    }

    // -----------------------------------------------------------------------------------------
    // MARK: - 健康检查
    // -----------------------------------------------------------------------------------------

    /**
     * 检查一个音源是否「可用」。默认只做**本地结构检查**（不联网、毫秒级）：
     *
     *  1. `urlPath` 空白 → `Failed("缺少字段路径 urlPath")`；
     *  2. 脚本音源（`script` 非空或 `kind == script`）：
     *     - 脚本非空白 → `Ok("LX 脚本已加载")`；
     *     - 脚本空白但模板可用 → 按配置音源算；两者都没有 → `Failed("缺少 URL 模板或脚本")`；
     *  3. 模板音源：模板空白 → `Failed("缺少 URL 模板或脚本")`；
     *     模板里没有 `http` → `Failed("URL 模板不是 http 地址")`；否则 `Ok("配置音源已识别")`。
     *
     * [deep] 为 true 且是脚本音源时，额外把脚本交给 [LxScriptRunner] 的隐藏 WebView **求值一次**，
     * 用来抓语法错误 / 加载期异常（`Ok("LX 脚本已加载")` ↔ `Failed("脚本解析失败：…")`）。
     * 这一步首次会创建 WebView（几百毫秒量级），因此**只由用户点击盾牌图标时触发**，
     * 绝不随列表渲染自动跑；「全部检查」也只跑结构检查。
     *
     * **不做的事**：不发起任何真实的拉流 / 接口请求——所以点「全部检查」永远不会打到第三方接口。
     */
    suspend fun checkHealth(source: ThirdPartySource, deep: Boolean = false): SourceHealth =
        withContext(Dispatchers.Default) {
            try {
                structuralCheck(source)
            } catch (t: Throwable) {
                Log.d(TAG, "音源结构检查异常：${source.name} ${t.message ?: t}")
                SourceHealth.Failed("检查失败：${userFacingReason(t)}")
            }
        }.let { structural ->
            if (!deep || structural !is SourceHealth.Ok || !source.isScript) return@let structural
            withContext(Dispatchers.Default) { deepScriptCheck(source) }
        }

    private fun structuralCheck(source: ThirdPartySource): SourceHealth {
        if (source.urlPath.isBlank()) return SourceHealth.Failed("缺少字段路径 urlPath")
        val template = source.template.trim()
        val templateUsable = template.contains(TEMPLATE_SCHEME_HINT, ignoreCase = true)
        if (source.isScript) {
            // `script` 非空白 → 脚本音源；`kind = script` 但没内容时退回模板判断。
            if (!source.script.isNullOrBlank()) return SourceHealth.Ok("LX 脚本已加载")
            if (template.isBlank()) return SourceHealth.Failed("缺少 URL 模板或脚本")
            if (!templateUsable) return SourceHealth.Failed("URL 模板不是 http 地址")
            return SourceHealth.Ok("配置音源已识别")
        }
        if (template.isBlank()) return SourceHealth.Failed("缺少 URL 模板或脚本")
        if (!templateUsable) return SourceHealth.Failed("URL 模板不是 http 地址")
        return SourceHealth.Ok("配置音源已识别")
    }

    /** 把脚本丢进 WebView 求值一次；失败时把 JS 侧的错误原文带回来。 */
    private suspend fun deepScriptCheck(source: ThirdPartySource): SourceHealth {
        val script = source.script?.trim().orEmpty()
        if (script.isEmpty()) return SourceHealth.Failed("脚本音源没有脚本内容")
        val error = LxScriptRunner.prepareScript(source)
        return if (error.isNullOrBlank()) {
            SourceHealth.Ok("LX 脚本已加载")
        } else {
            SourceHealth.Failed("脚本解析失败：${trimReason(error)}")
        }
    }

    private fun trimReason(raw: String): String =
        raw.replace(Regex("""[\s\u0000-\u001f]+"""), " ").trim().let {
            if (it.length <= 120) it else it.take(119) + "…"
        }

    // -----------------------------------------------------------------------------------------
    // MARK: - 结果缓存（供非 Compose 调用方复用；UI 侧另有自己的 state map）
    // -----------------------------------------------------------------------------------------

    private val cache = ConcurrentHashMap<String, SourceHealth>()

    private fun cacheKey(source: ThirdPartySource): String =
        "${source.id}|${source.kind}|${source.urlPath}|${source.template.hashCode()}|" +
            "${source.script?.hashCode() ?: 0}|${source.headers.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }}"

    /** 读缓存；音源内容变化（模板 / 脚本 / 头）后 key 会变，等价于自动失效。 */
    fun cachedHealth(source: ThirdPartySource): SourceHealth? = cache[cacheKey(source)]

    /** 带缓存的结构检查；纯计算，可在任意线程调用。 */
    fun checkHealthCached(source: ThirdPartySource): SourceHealth =
        cachedHealth(source) ?: runCatching { structuralCheck(source) }
            .getOrElse { SourceHealth.Failed("检查失败：${userFacingReason(it)}") }
            .also { cache[cacheKey(source)] = it }

    fun clearCache() = cache.clear()
}

/**
 * 需求里约定的入口：`checkHealth(source)`。
 * 顶层函数形式，便于 UI 层以 `checkHealth(source, deep = true)` 直接调用。
 */
suspend fun checkHealth(source: ThirdPartySource, deep: Boolean = false): SourceHealth =
    SourceHealthChecker.checkHealth(source, deep)

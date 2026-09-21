package com.lulu.music.data.source

import com.lulu.music.data.model.ThirdPartyAudioQuality
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 第三方解锁音源配置（port of iOS `ThirdPartySource`）。
 *
 * @param kind 音源类型标识，用于兼容不同导入格式（`keyword` / `script` / `url` …）。
 * @param template 请求 URL 模板，支持 `{id}`、`{source}`、`{quality}` 占位符。
 * @param urlPath 响应 JSON 中音频直链的字段路径，默认 `url`。
 * @param headers 可选的请求头与内置元数据（`source` / `platform` / `qualities` 等）。
 * @param quality 默认音质。
 * @param script LX / Baka 风格 JS 脚本内容。
 */
@Serializable
data class ThirdPartySource(
    val id: String = newSourceId(),
    val name: String = "未命名音源",
    val kind: String = KIND_KEYWORD,
    val template: String = "",
    @SerialName("urlPath") val urlPath: String = "url",
    val headers: Map<String, String> = emptyMap(),
    val quality: String = "320k",
    val script: String? = null,
    val enabled: Boolean = true,
) {
    val isScript: Boolean get() = !script.isNullOrBlank() || kind.equals(KIND_SCRIPT, ignoreCase = true)

    /** 展示用的类型标签，对应 iOS 列表里的「脚本 / KEYWORD / url」。 */
    val kindLabel: String
        get() = when {
            isScript -> "脚本"
            kind.isBlank() -> "keyword"
            else -> kind
        }

    companion object {
        const val KIND_KEYWORD = "keyword"
        const val KIND_SCRIPT = "script"
        const val KIND_URL = "url"

        fun newSourceId(): String = "lulu.src." + java.util.UUID.randomUUID().toString()
    }
}

/** 播放来源偏好（对应 iOS 设置里的「播放来源」分段控件）。 */
enum class PlaybackSource(val key: String, val zh: String, val en: String) {
    /** 优先官方，失败后尝试已启用音源。 */
    AUTO("auto", "自动", "Auto"),

    /** 只使用官方接口。 */
    OFFICIAL("official", "官方", "Official"),

    /** 优先使用已启用的第三方音源。 */
    THIRD_PARTY("third_party", "第三方", "Third-party");

    companion object {
        fun fromKey(key: String?): PlaybackSource =
            entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** 音源支持的音质推导（port of `UnblockSourceStore.supportedQualities`）。 */
object SourceQualities {

    private val SCRIPT_QUALITY_REGEX =
        Regex("""(?i)(?:qualitys?|qualityOptions)\s*[:=]\s*\[([^\]]*)]""")

    fun supported(source: ThirdPartySource, providerCode: String? = null): List<ThirdPartyAudioQuality> {
        val explicit = explicitQualities(
            source.headers["qualities"]
                ?: source.headers["qualityOptions"]
                ?: source.headers["qualitys"],
        )
        if (explicit.isNotEmpty()) {
            return if (providerCode != null) {
                val platform = ThirdPartyAudioQuality.supported(providerCode).toSet()
                explicit.filter { it in platform }
            } else {
                explicit
            }
        }

        val fromScript = source.script?.let { scriptQualities(it) }
        if (!fromScript.isNullOrEmpty()) {
            return if (providerCode != null) {
                val platform = ThirdPartyAudioQuality.supported(providerCode).toSet()
                fromScript.filter { it in platform }
            } else {
                fromScript
            }
        }

        if (providerCode != null) return ThirdPartyAudioQuality.supported(providerCode)

        val sourceProvider = source.headers["source"] ?: source.headers["platform"]
        if (sourceProvider != null) return ThirdPartyAudioQuality.supported(sourceProvider)

        return ThirdPartyAudioQuality.entries.toList()
    }

    fun explicitQualities(raw: String?): List<ThirdPartyAudioQuality> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return text.split(Regex("""[\s,;|/\[\]"']+"""))
            .mapNotNull { ThirdPartyAudioQuality.fromSourceValue(it) }
            .distinct()
    }

    private fun scriptQualities(script: String): List<ThirdPartyAudioQuality>? {
        val match = SCRIPT_QUALITY_REGEX.find(script) ?: return null
        return explicitQualities(match.groupValues[1])
    }

    /**
     * 脚本里是否**声明**了音质档位：`null` = 完全没有 `qualitys` / `qualityOptions` 片段，
     * 空列表 = 有片段但一个有效档位都解析不出来。
     *
     * 供 [SourceHealthChecker] 推导「支持：…」时区分「没声明」与「声明了但无效」。
     */
    fun qualitiesDeclaredInScript(script: String): List<ThirdPartyAudioQuality>? {
        if (script.isBlank()) return null
        val match = SCRIPT_QUALITY_REGEX.find(script) ?: return null
        return explicitQualities(match.groupValues[1])
    }
}

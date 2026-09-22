package com.lulu.music.data.feedback

import com.lulu.music.BuildConfig
import com.lulu.music.data.net.Http
import com.lulu.music.data.stats.GiteeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject

/**
 * 问题反馈 → Gitee 仓库新 issue。
 *
 * 调用 Gitee 开放 API v5：
 * ```
 * POST https://gitee.com/api/v5/repos/{owner}/{repo}/issues
 * ```
 * 表单字段 `access_token` / `title` / `body`（`access_token` 放在表单体里，
 * 而不是 URL query 上，避免 token 出现在日志 / 代理的请求行里）。
 *
 * 仓库信息来自冻结的 [GiteeConfig]（由另一个 agent 提供）；[GiteeConfig.isConfigured] 为 false 时
 * **不会发出任何请求**，直接返回 [Result.NotConfigured]，由 UI 引导用户复制反馈内容。
 *
 * 本对象**绝不抛异常**：网络错误、HTTP 非 2xx、响应不是 JSON、字段缺失，全部收敛成 [Result.Failed]。
 */
object GiteeFeedback {

    /** 提交结果。 */
    sealed interface Result {
        /** 建单成功，[url] 是 issue 的网页地址（可能为空字符串）。 */
        data class Submitted(val url: String) : Result

        /** 未配置 token / 仓库，没有发请求。 */
        data class NotConfigured(val message: String) : Result

        /** 已经发出请求但失败了。 */
        data class Failed(val message: String) : Result
    }

    /** Gitee issue 标题有长度上限，这里留足余量。 */
    private const val MAX_TITLE = 100
    private const val MAX_BODY = 60_000

    /**
     * 提交一条反馈。
     *
     * @param description 用户填写的正文（自动附带的环境信息由 [buildBody] 追加）
     * @param environment 自动采集的环境信息，形如 `设备：Xiaomi 14\n系统：Android 14 (API 34)`
     */
    suspend fun submit(description: String, environment: String): Result = withContext(Dispatchers.IO) {
        val configured = runCatching { GiteeConfig.isConfigured }.getOrDefault(false)
        if (!configured) {
            return@withContext Result.NotConfigured(
                "尚未配置 Gitee 反馈仓库（缺少 owner / repo / access_token），无法直接提交",
            )
        }

        val trimmed = description.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.Failed("请先填写问题描述")
        }

        runCatching {
            val form = FormBody.Builder()
                .add("access_token", GiteeConfig.TOKEN)
                .add("title", buildTitle(trimmed))
                .add("body", buildBody(trimmed, environment))
                .build()
            val request = Request.Builder()
                .url("https://gitee.com/api/v5/repos/${GiteeConfig.OWNER}/${GiteeConfig.REPO}/issues")
                .header("Accept", "application/json")
                .post(form)
                .build()

            Http.client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@runCatching Result.Failed(httpMessage(response.code, payload))
                }
                val json = runCatching { JSONObject(payload.trim().removePrefix("\uFEFF")) }
                    .getOrElse { return@runCatching Result.Failed("反馈接口返回了无法解析的内容") }
                // 返回体里 issue 的网页地址在 html_url；缺失也不算失败，建单本身已经成功。
                @Suppress("DEPRECATION")
                val url = json.optString("html_url", json.optString("url", ""))
                Result.Submitted(url)
            }
        }.getOrElse { failure ->
            Result.Failed("提交失败：${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    /** 标题取正文第一行的前 [MAX_TITLE] 个字符，并加上版本号前缀。 */
    private fun buildTitle(description: String): String {
        val firstLine = description.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "用户反馈"
        val head = firstLine.take(MAX_TITLE)
        return "【v${BuildConfig.VERSION_NAME}】$head"
    }

    /**
     * 正文 = 用户描述 + 分隔线 + 自动附带的环境信息。
     *
     * 环境信息由调用方（设置页的「运行环境」区块读取的是同一份数据）采集后传进来，
     * 这样本文件保持在 `data` 层、不依赖任何 UI / Compose API。
     */
    fun buildBody(description: String, environment: String): String {
        val builder = StringBuilder()
        builder.append(description.trim())
        builder.append("\n\n---\n\n### 运行环境\n\n")
        if (environment.isBlank()) {
            builder.append("- （未采集到环境信息）\n")
        } else {
            environment.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line -> builder.append("- ").append(line.trim()).append('\n') }
        }
        builder.append("\n> 由 LuluMusic Android 客户端自动提交\n")
        return builder.toString().take(MAX_BODY)
    }

    /** HTTP 错误 → 人话；Gitee 的错误信息一般在 `message` / `error` 字段里。 */
    private fun httpMessage(code: Int, payload: String): String {
        val detail = runCatching { JSONObject(payload).optString("message", "") }
            .getOrDefault("")
            .ifBlank {
                runCatching { JSONObject(payload).optString("error", "") }.getOrDefault("")
            }
        val base = when (code) {
            401 -> "访问令牌无效或已过期（401）"
            403 -> "访问令牌权限不足（403）"
            404 -> "仓库不存在（404）"
            422 -> "提交内容不被接受（422）"
            else -> "反馈接口返回 HTTP $code"
        }
        return if (detail.isBlank()) base else "$base：${detail.take(120)}"
    }

    /** 供 UI 复制的纯文本反馈（未配置时用）。 */
    fun clipboardText(description: String, environment: String): String =
        buildBody(description, environment)
}

package com.lulu.music.data.feedback

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lulu.music.BuildConfig
import com.lulu.music.data.model.beansLocalized

/**
 * 问题反馈的收件地址。
 *
 * 这是**唯一**的反馈通道：App 自己不再联网提交，只把内容交给系统邮件 App，由用户点「发送」。
 */
const val FEEDBACK_EMAIL = "57844458@qq.com"

/**
 * 问题反馈 → 系统邮件 App（`mailto:`）。
 *
 * 主题固定为 `[LuluMusic 反馈] <正文第一行>`，正文 = 用户描述 + 分隔线 + 自动采集的运行环境。
 * 本对象**不发任何网络请求、不读任何令牌**，也不收集除调用方传入内容以外的任何信息。
 *
 * 「没有邮件 App」绝不等于崩溃：先 `resolveActivity` 探一次，`startActivity` 外面再兜一层
 * [ActivityNotFoundException]，两条路都退化成「把同样的主题 + 正文复制到剪贴板」并返回
 * [Result.Copied]，用户仍能把内容粘贴到任何地方。
 */
object EmailFeedback {

    /** 提交结果。 */
    sealed interface Result {
        /** 已经拉起邮件 App（用户还需要在里面点「发送」，所以不能说「已发送」）。 */
        data object Sent : Result

        /** 没找到邮件 App：同样的主题 + 正文已经复制到剪贴板。 */
        data object Copied : Result

        /** 本地就失败了（内容为空 / 拉不起邮件 App / 剪贴板不可用）。[reason] 可直接展示。 */
        data class Failed(val reason: String) : Result
    }

    /** 主题里正文首行的截断长度：邮件 App 的主题栏放不下更长的内容。 */
    private const val MAX_SUBJECT_HEAD = 60

    /** 主题 + 正文的总长度上限，避免超长文本把邮件 App 卡死。 */
    private const val MAX_BODY = 60_000

    /**
     * 把一条反馈交给邮件 App。
     *
     * @param context 建议传 Activity 的 Context（剪贴板与 `startActivity` 都能直接用）
     * @param description 用户填写的正文
     * @param environment 自动采集的运行环境信息，形如 `设备：Xiaomi 14\n系统：Android 14 (API 34)`
     */
    fun submit(context: Context, description: String, environment: String): Result {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return Result.Failed(beansLocalized("请先填写问题描述", "Please describe the issue first"))

        val subject = buildSubject(trimmed)
        val body = buildBody(trimmed, environment)
        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_EMAIL")).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        // Android 11+（targetSdk 30+）有包可见性过滤：清单里没有 mailto 的 queries 时，
        // 这里的 resolveActivity 会误判成「没有邮件 App」，所以 AndroidManifest.xml 里
        // 声明了对应的 queries —— 两处必须一起改。
        if (mail.resolveActivity(context.packageManager) == null) {
            return copyToClipboard(context, subject, body)
        }

        return try {
            context.startActivity(
                Intent.createChooser(mail, beansLocalized("发送反馈", "Send feedback"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Result.Sent
        } catch (_: ActivityNotFoundException) {
            // resolveActivity 说有、真正拉起时却没有（竞态 / 被禁用）：同样退化成复制。
            copyToClipboard(context, subject, body)
        } catch (t: Throwable) {
            // 其它异常（例如宿主 Activity 已经销毁）不算「没找到邮件 App」，如实报告原因。
            Result.Failed(
                beansLocalized(
                    "无法打开邮件 App：${t.message ?: t.javaClass.simpleName}",
                    "Could not open a mail app: ${t.message ?: t.javaClass.simpleName}",
                ),
            )
        }
    }

    /** 主题：`[LuluMusic 反馈] <正文第一行，截断>`。 */
    internal fun buildSubject(description: String): String {
        val firstLine = description.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: beansLocalized("用户反馈", "User feedback")
        return "[LuluMusic 反馈] ${firstLine.take(MAX_SUBJECT_HEAD)}"
    }

    /**
     * 正文 = 用户描述 + 分隔线 + 运行环境。
     *
     * 环境信息由调用方采集（设置页「运行环境」区块读的是同一份数据），这样本文件保持在
     * `data` 层、不依赖任何 UI / Compose API。
     */
    internal fun buildBody(description: String, environment: String): String {
        val builder = StringBuilder()
        builder.append(description.trim())
        builder.append("\n\n----------\n\n")
        builder.append(beansLocalized("运行环境", "Runtime environment")).append("\n\n")
        if (environment.isBlank()) {
            builder.append(beansLocalized("- （未采集到环境信息）", "- (no environment info collected)")).append('\n')
        } else {
            environment.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line -> builder.append("- ").append(line.trim()).append('\n') }
        }
        builder.append('\n').append("LuluMusic Android v${BuildConfig.VERSION_NAME}")
        return builder.toString().take(MAX_BODY)
    }

    /** 供 UI 复制的纯文本反馈（与 [submit] 失败时落进剪贴板的内容完全一致）。 */
    fun clipboardText(description: String, environment: String): String =
        buildBody(description.trim(), environment)

    /** 主题 + 正文一起进剪贴板；剪贴板不可用时返回 [Result.Failed]。 */
    private fun copyToClipboard(context: Context, subject: String, body: String): Result {
        val clipboard = runCatching {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        }.getOrNull()
        if (clipboard == null) {
            return Result.Failed(beansLocalized("剪贴板不可用", "The clipboard is unavailable"))
        }
        val copied = runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(subject, "$subject\n\n$body"))
        }.isSuccess
        return if (copied) {
            Result.Copied
        } else {
            Result.Failed(
                beansLocalized(
                    "没找到邮件 App，复制到剪贴板也失败了",
                    "No mail app was found and copying to the clipboard failed",
                ),
            )
        }
    }
}

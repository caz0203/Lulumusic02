package com.lulu.music.data.auth

import com.lulu.music.data.model.beansLocalized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条 QQ 扫码登录诊断记录。
 *
 * 只记录「这一步做了什么、结果是什么」，**绝不记录任何凭证**：
 * - [step] 是代码里的固定常量（本文件之外没有第二个赋值点）；
 * - [detail] 与 [snippet] 在录入时都过一遍 [QQLoginRedaction]：Cookie 头整行抹掉、
 *   敏感字段值抹掉、已知凭证值整串抹掉；
 * - [snippet] 另外被截断到 [QQLoginRedaction.MAX_SNIPPET] 个字符，保证日志不超过一行。
 */
data class QQLoginDiag(
    /** 步骤名，例如 `ptqrshow` / `ptqrlogin` / `check_sig#0` / `authorize` / `musicu.fcg`。 */
    val step: String,
    /** 该步的结果摘要：HTTP 状态、是否拿到 Location / code、新增了哪些 Cookie 名（已脱敏）。 */
    val detail: String,
    /** 脱敏后的响应片段；为空表示这一步没有可展示的文本（例如二维码是 PNG 图片）。 */
    val snippet: String,
)

/**
 * QQ 登录诊断的脱敏工具。
 *
 * 这是整个诊断功能里**唯一**必须做对的事：任何 cookie、`skey` / `p_skey` / `qqmusic_key` /
 * `qrsig`、授权 `code`、二维码 token 的明文都不允许进入日志、StateFlow 或导出文本。
 *
 * 三条防线，按顺序作用：
 * 1. 整段 `Cookie:` / `Set-Cookie:` 响应头（无论字段名认不认识）直接替换成占位符；
 * 2. `name=value` / `"name":"value"` / `name: value` 形式的敏感字段名，值替换成 [MASK]；
 * 3. 调用方显式传入的「当前已知敏感值」（内存里的 Cookie 值 + qrsig）做整串替换，
 *    覆盖敏感值出现在任何上下文（例如纯文本、URL 路径）的情况。
 */
object QQLoginRedaction {

    const val MASK = "<redacted>"

    /** 单条响应片段的最大长度，超出后截断。 */
    const val MAX_SNIPPET = 160

    /** 整行 Cookie / Set-Cookie 头：字段名不可枚举（pgv_pvid 之类），一律整行抹掉。 */
    private val COOKIE_HEADER = Regex("(?i)\\b(set-cookie|cookie)\\s*:\\s*[^\\r\\n]+")

    /**
     * 敏感字段名（长的写在前面，配合 `(?<![A-Za-z0-9_])` 前缀断言，
     * 避免 `qqmusic_key` 里的 `music_key`、`p_skey` 里的 `skey` 被当成另一个字段）。
     */
    private val SECRET_PAIR = Regex(
        "(?i)(?<![A-Za-z0-9_])" +
            "(qqmusic_key|qm_keyst|musickey|music_key|p_skey|wxskey|wx_skey|skey|qrsig|" +
            "pt4_token|ptsigx|pskey|superkey|access_token|refresh_token|openid|" +
            "uin|p_uin|pt2gguin|code)" +
            "\\s*[\"']?\\s*[:=]\\s*[\"']?([^\"',;\\s&}]+)",
    )

    private val WHITESPACE = Regex("\\s+")

    /**
     * 脱敏 [raw]：抹掉 Cookie 头、敏感字段值，以及 [secrets] 里出现的任何已知凭证值。
     * 结果会把连续空白压成一个空格并截断到 [MAX_SNIPPET]，保证日志不超过一行。
     *
     * **不要对同一段文本反复调用**：`Cookie:` / `Set-Cookie:` 是「整行抹掉」，
     * 而本函数会把换行压成空格 —— 第二次调用时整段内容就变成「一行」，会被一起吞掉
     * （只会脱敏得更多，不会泄露，但会丢诊断信息）。所以入库时脱敏一次就够了。
     */
    fun redact(raw: String, secrets: Collection<String> = emptyList()): String {
        if (raw.isEmpty()) return ""
        var out = COOKIE_HEADER.replace(raw) { "${it.groupValues[1]}: $MASK" }
        out = SECRET_PAIR.replace(out) { "${it.groupValues[1]}=$MASK" }
        for (secret in secrets) {
            // 太短的值（例如 uin=0）整串替换会误伤正常文本，跳过。
            if (secret.length < MIN_SECRET_LENGTH) continue
            out = out.replace(secret, MASK)
        }
        out = WHITESPACE.replace(out, " ").trim()
        return if (out.length <= MAX_SNIPPET) out else out.take(MAX_SNIPPET) + "…"
    }

    /** 小于这个长度的「敏感值」不做整串替换（避免把 `0`、`1` 这种当成凭证误伤日志）。 */
    const val MIN_SECRET_LENGTH = 6
}

/**
 * QQ 登录诊断日志：有界（最多 [MAX_ENTRIES] 条，最旧的先丢）的顺序记录，供 UI 展示与复制导出。
 *
 * 线程安全：QQ 登录流程在 IO 协程里跑，UI 在主线程读，所有读写都在同一把锁里完成。
 */
class QQLoginDiagnostics(private val limit: Int = MAX_ENTRIES) {

    private val lock = Any()
    private val buffer = ArrayList<QQLoginDiag>()
    private var sensitiveValues: List<String> = emptyList()

    private val _entries = MutableStateFlow<List<QQLoginDiag>>(emptyList())

    /** Compose 观察用；每次变更整体替换列表，观察者只读。 */
    val entriesFlow: StateFlow<List<QQLoginDiag>> = _entries.asStateFlow()

    val entries: List<QQLoginDiag> get() = _entries.value

    /**
     * 登记「当前内存里的凭证值」，之后所有 [record] 都会额外抹掉这些整串，
     * 覆盖敏感值脱离 `name=value` 上下文的情况。
     */
    fun setSensitiveValues(values: Collection<String>) {
        synchronized(lock) {
            sensitiveValues = values.filter { it.isNotEmpty() }.distinct()
        }
    }

    /**
     * 记录一步。[detail] 与 [snippet] 都是「可能含敏感内容的文本」，在这里（且只在这里）
     * 统一脱敏后才进入内存列表 —— 就算将来有人在 detail 里拼了 `skey=...` 也不会有明文落进来。
     *
     * 注意：脱敏会把 `某个敏感字段名=值` 整体替换成占位符，所以 detail 里写「轮询状态码」时
     * 用的是 `ptuiCB=66` 这种不会撞上敏感字段名的写法（撞上的话状态码会被抹掉，见
     * `QQLoginAuthTest` 里对 `ptuiCB=66` 的断言）。
     */
    fun record(step: String, detail: String, snippet: String = "") {
        val secrets = synchronized(lock) { sensitiveValues }
        val entry = QQLoginDiag(
            step = step,
            detail = QQLoginRedaction.redact(detail, secrets),
            snippet = if (snippet.isEmpty()) "" else QQLoginRedaction.redact(snippet, secrets),
        )
        synchronized(lock) {
            buffer.add(entry)
            while (buffer.size > limit) buffer.removeAt(0)
            _entries.value = buffer.toList()
        }
    }

    /** 清空（新一次登录尝试开始时调用，避免把上一次的日志混进导出）。 */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            sensitiveValues = emptyList()
            _entries.value = emptyList()
        }
    }

    /**
     * 纯文本导出（可直接复制发给我）。
     *
     * 导出**只做拼接、不做二次脱敏**：所有内容在 [record] 入库时就过了
     * [QQLoginRedaction]（那是唯一的写入口），再脱敏一次反而有害 —— Cookie 头是整行抹掉的，
     * 而入库时换行已经被压掉，二次调用会把同一行后面的响应内容一起吞掉，
     * 恰好丢掉诊断里最值钱的部分（接口到底报了什么错）。
     */
    fun export(nowMillis: Long = System.currentTimeMillis()): String {
        val snapshot = entries
        val builder = StringBuilder()
        builder.append(EXPORT_HEADER).append('\n')
        builder.append(beansLocalized("生成时间", "Generated at")).append(": ")
        builder.append(formatTime(nowMillis)).append('\n')
        if (snapshot.isEmpty()) {
            builder.append(beansLocalized("（暂无记录）", "(no entries)")).append('\n')
            return builder.toString()
        }
        for ((index, entry) in snapshot.withIndex()) {
            builder.append(index + 1).append(". ").append(entry.step)
            if (entry.detail.isNotEmpty()) builder.append(" | ").append(entry.detail)
            builder.append('\n')
            if (entry.snippet.isNotEmpty()) {
                builder.append("   ").append(entry.snippet).append('\n')
            }
        }
        return builder.toString()
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    companion object {
        /** 有界日志的容量：超过就丢最旧的一条。 */
        const val MAX_ENTRIES = 24

        const val EXPORT_HEADER = "=== LuluMusic QQ 登录诊断 / QQ login diagnostics ==="
    }
}

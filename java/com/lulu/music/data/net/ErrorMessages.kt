package com.lulu.music.data.net

import com.lulu.music.data.model.beansLocalized
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把底层异常翻译成可以直接显示给用户的文案。
 *
 * 背景：有几处界面以前直接把 `Throwable` 的 message 渲染出来，于是用户在「歌单详情」
 * 或「搜索」失败时会看到 OkHttp 的英文原文，例如
 * `Failed to connect to music.163.com:443`，甚至框架内部的异常描述。
 *
 * 这里统一收敛一条规则：**技术细节只进日志**（`Log` / `CrashLog`），界面只显示这句话。
 * 所有平台 API（网易云 / QQ / 酷狗）抛出的都是 [BeansApiException]，因此一处覆盖全部。
 */
fun userFacingMessage(error: Throwable): String = when (error) {
    is BeansApiException.HttpStatus -> when (error.code) {
        401, 403 -> beansLocalized(
            "没有访问权限，请稍后重试",
            "Access denied, please try again later",
        )
        404 -> beansLocalized("内容不存在或已下架", "Content not found")
        in 500..599 -> beansLocalized(
            "服务器暂时不可用，请稍后重试",
            "Server unavailable, please try again later",
        )
        else -> beansLocalized("请求失败，请稍后重试", "Request failed, please try again later")
    }

    is BeansApiException.Decoding -> beansLocalized(
        "数据解析失败，请稍后重试",
        "Could not read the response",
    )

    is BeansApiException.Network -> networkMessage()

    is BeansApiException.Unknown -> genericMessage()

    // 下层没被包装成 BeansApiException 的情况（少数直接使用 OkHttp 的调用点）。
    is SocketTimeoutException -> beansLocalized("连接超时，请重试", "Connection timed out, please retry")
    is UnknownHostException -> networkMessage()
    is IOException -> networkMessage()

    else -> genericMessage()
}

private fun networkMessage(): String =
    beansLocalized("网络连接失败，请检查网络", "Network unavailable, please check your connection")

private fun genericMessage(): String =
    beansLocalized("出错了，请稍后重试", "Something went wrong, please try again")

/**
 * 用于「某某失败：<原因>」这种拼接的**原因片段**。
 *
 * 规则：已经是我们自己写的用户文案（含汉字）就原样保留——那通常比通用文案更有用，
 * 例如 [com.lulu.music.data.backup.BackupManager] 抛出的「备份里的 xxx 不是有效数据」；
 * 底层框架抛出的英文原文一律换成用户能看懂的说法，技术细节只进日志。
 */
fun userFacingReason(error: Throwable?): String {
    val raw = error?.message?.trim().orEmpty()
    if (raw.any { it.isHan() }) return raw
    return if (error == null) genericMessage() else userFacingMessage(error)
}

private fun Char.isHan(): Boolean = this in '\u4e00'..'\u9fff' || this in '\u3400'..'\u4dbf'

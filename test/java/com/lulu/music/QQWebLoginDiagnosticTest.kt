package com.lulu.music

import com.lulu.music.data.auth.QQLoginDiagnostics
import com.lulu.music.data.auth.QQLoginRedaction
import com.lulu.music.data.auth.sentCookieNames
import com.lulu.music.data.auth.qqAuthorizeCookieHeader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新增的 `authorize.cookies` 那条诊断必须**同时**满足两件事：开发者能看清发了哪些 Cookie 名，
 * 且任何一个 Cookie 值都不会被写进日志。
 *
 * 这是 Task 1 第 2 条（记录实际发出去的名字）与「脱敏规则保持不变」之间的交叉验证：
 * 单独测 [sentCookieNames]（名字对不对）或单独测脱敏（值会不会漏）都不够 ——
 * 这里把真实拼出来的头喂进诊断，再断言导出文本。
 */
class QQWebLoginDiagnosticTest {

    private val jar = linkedMapOf(
        "uin" to "o1234567890",
        "pt2gguin" to "o1234567890",
        "p_uin" to "o1234567890",
        "qqmusic_key" to "SUPER-SECRET-MUSIC-KEY",
        "p_skey" to "SUPER-SECRET-PSKEY",
        "pt_oauth_token" to "SUPER-SECRET-OAUTH",
        "qrsig" to "SUPER-SECRET-QRSIG",
    )

    @Test
    fun authorizeCookieDiagnosticNamesEveryCookieSentAndRedactsEveryValue() {
        val header = qqAuthorizeCookieHeader(jar, includeUIN = true)
        val names = sentCookieNames(header)

        val diagnostics = QQLoginDiagnostics()
        diagnostics.setSensitiveValues(header.split("; ").mapNotNull { it.substringAfter('=', "") })
        // 与 QQMusicAuth.recordAuthorizeCookies 中拼出的 detail 逐字一致。
        diagnostics.record("authorize.cookies", "count=${names.size} | sendCookieNames=[${names.joinToString(",")}]")

        val exported = diagnostics.export(nowMillis = 0L)

        // 1. 关键名字必须在（诊断的意义就在这里：证明它们真的发出去了）
        for (name in listOf("uin", "pt_oauth_token", "qqmusic_key", "p_skey", "qrsig")) {
            assertTrue("诊断里应当能看到发出的 Cookie 名 $name；实际：$exported", exported.contains(name))
        }
        assertTrue("条数也要在，便于一眼确认是不是空手去的", exported.contains("count=${names.size}"))

        // 2. 任何一个值都不能泄漏
        for (secret in listOf(
            "SUPER-SECRET-MUSIC-KEY",
            "SUPER-SECRET-PSKEY",
            "SUPER-SECRET-OAUTH",
            "SUPER-SECRET-QRSIG",
            "o1234567890",
        )) {
            assertFalse("凭证值 $secret 绝不能进诊断：$exported", exported.contains(secret))
        }

        // 3. 敏感字段名的值仍被替换成占位符（脱敏规则没被放宽）
        assertTrue(
            "拼成 name=value 的形状时仍应打码；实际：$exported",
            QQLoginRedaction.redact("p_skey=SUPER-SECRET-PSKEY").contains(QQLoginRedaction.MASK),
        )
    }
}

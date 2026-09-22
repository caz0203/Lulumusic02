package com.lulu.music

import com.lulu.music.data.auth.QQLoginDiagnostics
import com.lulu.music.data.auth.QQLoginFailure
import com.lulu.music.data.auth.QQLoginRedaction
import com.lulu.music.data.auth.QQAuthorizeError
import com.lulu.music.data.auth.QQ_AUTHORIZE_ENDPOINT
import com.lulu.music.data.auth.QQ_AUTHORIZE_USE_POST_FORM
import com.lulu.music.data.auth.QQ_CONNECT_ERROR_CODES
import com.lulu.music.data.auth.authorizeEvidenceSnippet
import com.lulu.music.data.auth.parseAuthorizeError
import com.lulu.music.data.auth.parsePTUICallback
import com.lulu.music.data.auth.qqAuthorizeErrorSentence
import com.lulu.music.data.auth.qqAuthorizeRequest
import com.lulu.music.data.auth.qqFormEncode
import com.lulu.music.data.auth.qqLoginFailureMessage
import com.lulu.music.data.auth.queryKeys
import com.lulu.music.data.model.Lang
import com.lulu.music.data.prefs.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/**
 * QQ 扫码登录的**纯逻辑**测试：ptuiCB 解析、失败原因文案、诊断脱敏与导出、authorize 请求组装。
 *
 * 这里刻意不碰 `QQMusicAuth` 这个 object（它的 init 需要真实的 Application / SharedPreferences），
 * 也不发任何网络请求 —— 被测的都是文件里的顶层纯函数。
 *
 * 真机上的完整扫码流程（人扫二维码 -> 手机确认 -> 换 musickey）在单测里无法复现。
 */
class QQLoginAuthTest {

    @Before
    fun forceChinese() {
        Lang.current.value = AppLanguage.CHINESE
    }

    @After
    fun restoreChinese() {
        Lang.current.value = AppLanguage.CHINESE
    }

    // -----------------------------------------------------------------------------------------
    // ptuiCB 解析：昵称必须取第 6 个字段
    // -----------------------------------------------------------------------------------------

    /** 真实的扫码成功回调：`ptuiCB(code,'0',url,'0',message,nickname)`。 */
    private val successPayload = "ptuiCB('0','0','https://ptlogin2.qq.com/check_sig?pttype=1&uin=MTIz" +
        "&service=ptqrlogin&nodirect=0&ptsigx=8f3a1c0d9e&s_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0" +
        "%2Flogin_jump&f_url=&ptlang=2052&aid=716027609&daid=383&pt_3rd_aid=100497308','0','登录成功！','小明');"

    @Test
    fun parsePTUICallbackTakesNicknameFromFieldSixNotFromTheMessage() {
        val parsed = parsePTUICallback(successPayload)
        assertNotNull("真实形状必须能解析出来", parsed)
        parsed!!
        assertEquals("0", parsed.code)
        assertEquals("昵称必须取第 6 个字段，第 5 个字段是给人看的消息", "小明", parsed.nickname)
        assertFalse(
            "「登录成功！」是消息而不是昵称 —— 旧实现就是错在这里",
            parsed.nickname.contains("登录成功"),
        )
        assertTrue("跳转地址应取自第 3 个字段", parsed.url!!.startsWith("https://ptlogin2.qq.com/check_sig"))
        assertFalse("引号必须被剥掉", parsed.url!!.endsWith("'"))
    }

    @Test
    fun parsePTUICallbackHandlesWaitingAndExpiredPayloads() {
        val waiting = parsePTUICallback("ptuiCB('66','0','0','0','','')")
        assertEquals("66", waiting?.code)
        assertEquals("等待状态没有昵称", "", waiting?.nickname)
        assertNull("等待状态的第 3 个字段是 '0'，不是跳转地址", waiting?.url)

        val scanned = parsePTUICallback("ptuiCB('67','0','0','0','','')")
        assertEquals("67", scanned?.code)
        assertNull(scanned?.url)

        val expired = parsePTUICallback("ptuiCB('65','0','0','0','二维码已失效。','')")
        assertEquals("65", expired?.code)
        assertNull(expired?.url)
    }

    @Test
    fun parsePTUICallbackFallsBackToFieldFourOnTheLegacyFiveFieldShape() {
        // 历史形状：code,url,'0',msg,nick —— 没有独立昵称位
        val legacy = parsePTUICallback(
            "ptuiCB('0','https://ptlogin2.qq.com/check_sig?uin=123456','0','登录成功！','小明')",
        )
        legacy!!
        assertEquals("https://ptlogin2.qq.com/check_sig?uin=123456", legacy.url)
        assertEquals("五字段形状的昵称落在第 5 个字段", "小明", legacy.nickname)
        assertFalse("不能把消息当昵称", legacy.nickname.contains("登录成功"))
    }

    @Test
    fun parsePTUICallbackReturnsNullWhenTheBodyIsNotAJSONPCallback() {
        assertNull(parsePTUICallback(""))
        assertNull(parsePTUICallback("<html><body>login required</body></html>"))
        assertNull(parsePTUICallback("ptuiCB('0'"))
        assertNull(parsePTUICallback("ptuiCB('0','0')"))
    }

    // -----------------------------------------------------------------------------------------
    // 失败原因 -> 用户可见文案
    // -----------------------------------------------------------------------------------------

    @Test
    fun everyFailureKindHasItsOwnNonEmptyMessage() {
        val messages = QQLoginFailure.values().map { qqLoginFailureMessage(it) }
        assertTrue("每条文案都不能为空", messages.all { it.isNotBlank() })
        assertEquals("8 种失败原因必须给出 8 条不同的文案", messages.size, messages.toSet().size)
    }

    @Test
    fun expiredAndNotConfirmedTellTheUserWhatToActuallyDo() {
        val expired = qqLoginFailureMessage(QQLoginFailure.QR_EXPIRED)
        assertTrue("过期必须让用户点刷新，实际：$expired", expired.contains("刷新"))
        assertFalse("过期不该说「重新扫码」", expired.contains("重新扫码"))

        val scanned = qqLoginFailureMessage(QQLoginFailure.SCANNED_NOT_CONFIRMED)
        assertTrue("扫了没确认要说继续在手机上确认，实际：$scanned", scanned.contains("确认"))
        assertFalse("扫了没确认不能报成失败/重扫", scanned.contains("失败"))
    }

    @Test
    fun credentialExchangeFailuresDoNotTellTheUserToRescan() {
        val exchange = qqLoginFailureMessage(QQLoginFailure.EXCHANGE_FAILED)
        assertTrue("换凭证失败要引导看诊断，实际：$exchange", exchange.contains("登录诊断"))
        assertFalse("换凭证失败不等于要重扫", exchange.contains("重新扫码"))
        assertTrue("要说清楚登录本身已经确认过了", exchange.contains("已确认登录"))

        val noCredentials = qqLoginFailureMessage(QQLoginFailure.NO_CREDENTIALS)
        assertTrue("要指出缺的是 skey/p_skey，实际：$noCredentials", noCredentials.contains("skey"))
        assertFalse(noCredentials.contains("重新扫码"))

        val noCode = qqLoginFailureMessage(QQLoginFailure.NO_AUTHORIZE_CODE)
        assertTrue("要指出授权接口没给 code，实际：$noCode", noCode.contains("code"))
        assertFalse(noCode.contains("重新扫码"))

        val noRedirect = qqLoginFailureMessage(QQLoginFailure.NO_REDIRECT)
        assertTrue("要指出没有拿到跳转地址，实际：$noRedirect", noRedirect.contains("重定向"))
        assertFalse(noRedirect.contains("重新扫码"))
    }

    @Test
    fun rejectedCodeIsTheOnlyKindThatAsksForANewScan() {
        val rejected = qqLoginFailureMessage(QQLoginFailure.CODE_REJECTED)
        assertTrue("code 被拒才需要重扫，实际：$rejected", rejected.contains("重新扫码"))
    }

    @Test
    fun failureMessagesAreBilingual() {
        Lang.current.value = AppLanguage.ENGLISH
        val english = QQLoginFailure.values().map { qqLoginFailureMessage(it) }
        assertTrue(
            "英文状态下不能出现中文文案",
            english.none { message -> message.any { it.code in 0x4E00..0x9FFF } },
        )
        assertEquals(8, english.toSet().size)
    }

    // -----------------------------------------------------------------------------------------
    // 诊断脱敏：任何凭证明文都不允许存活
    // -----------------------------------------------------------------------------------------

    /** 真实响应里会出现的各类凭证明文（用于断言「一个都不能活下来」）。 */
    private val qrsigValue = "AAbbCCdd1234EEFFgghh"
    private val skeyValue = "@AbCdEf1234567890"
    private val pSkeyValue = "9zYxWvUtSrQpOnMl"
    private val musicKeyValue = "QMK_99887766554433"
    private val musickeyValue = "MUSKEY_ABC123456789"
    private val oauthCodeValue = "OAUTHCODE_XYZ789"
    private val locationCodeValue = "CODE_FROM_LOCATION"
    private val uinValue = "o123456789"

    private val allSecretValues = listOf(
        qrsigValue, skeyValue, pSkeyValue, musicKeyValue,
        musickeyValue, oauthCodeValue, locationCodeValue, uinValue,
    )

    /** 一段「最坏情况」的响应转储：两个 Set-Cookie 头 + JSON 体 + 带 code 的 Location。 */
    private val rawResponseDump = buildString {
        append("Set-Cookie: qrsig=$qrsigValue; path=/; domain=.qq.com\r\n")
        append("Set-Cookie: skey=$skeyValue; p_skey=$pSkeyValue; uin=$uinValue; path=/\r\n")
        append("Set-Cookie: qqmusic_key=$musicKeyValue; path=/; domain=.y.qq.com\r\n")
        append("Cookie: qrsig=$qrsigValue; skey=$skeyValue; p_skey=$pSkeyValue\r\n")
        append("Location: https://y.qq.com/portal/wx_redirect.html?code=$locationCodeValue&state=state\r\n")
        append(
            "callback( {\"error\":100019,\"error_description\":\"code has been used\"," +
                "\"musickey\":\"$musickeyValue\",\"musicid\":123456789,\"code\":\"$oauthCodeValue\"} );",
        )
    }

    @Test
    fun redactionLeavesNoCredentialValueInTheText() {
        val redacted = QQLoginRedaction.redact(rawResponseDump, listOf(qrsigValue))
        for (secret in allSecretValues) {
            assertFalse(
                "脱敏后仍然出现了凭证明文：$secret -> $redacted",
                redacted.contains(secret),
            )
        }
        assertTrue("Cookie 头必须整行抹掉", redacted.contains("Set-Cookie: ${QQLoginRedaction.MASK}"))
        assertFalse("整段 Cookie 头不能残留", redacted.contains("Cookie: qrsig="))
        assertFalse("Location 里的 code 值不能残留", redacted.contains(locationCodeValue))
    }

    @Test
    fun redactionRemovesCookieHeadersWholesaleEvenForUnknownCookieNames() {
        // 字段名不可枚举（pgv_pvid / RK 之类不是凭证名，但也是 Cookie），整行必须抹掉
        val setCookie = QQLoginRedaction.redact("Set-Cookie: pgv_pvid=1234567890; path=/; domain=.qq.com")
        assertEquals("Set-Cookie: ${QQLoginRedaction.MASK}", setCookie)
        val cookie = QQLoginRedaction.redact("Cookie: pgv_pvid=1234567890; RK=abcdefghij")
        assertEquals("Cookie: ${QQLoginRedaction.MASK}", cookie)
    }

    @Test
    fun redactionKeepsNonSecretDiagnosticFacts() {
        // 脱敏不能把「接口到底报了什么」也一起抹掉，否则诊断就没用了
        val body = "callback( {\"error\":100019,\"error_description\":\"code has been used\"} );"
        val redacted = QQLoginRedaction.redact(body)
        assertTrue("错误码必须保留", redacted.contains("100019"))
        assertTrue("错误描述必须保留", redacted.contains("code has been used"))
    }

    @Test
    fun redactionScrubsKnownSecretValuesEvenOutsideAKeyValuePair() {
        // 兜底防线：凭证值脱离了 name=value 上下文（例如拼在 URL 路径里）也必须被抹掉
        val text = "https://example.com/portal/$skeyValue/$oauthCodeValue/detail"
        val redacted = QQLoginRedaction.redact(text, listOf(skeyValue, oauthCodeValue))
        assertFalse(redacted.contains(skeyValue))
        assertFalse(redacted.contains(oauthCodeValue))
        assertTrue(redacted.contains(QQLoginRedaction.MASK))
    }

    @Test
    fun redactionStaysSecretFreeAndBoundedWhenAppliedRepeatedly() {
        val once = QQLoginRedaction.redact(rawResponseDump, listOf(qrsigValue))
        assertTrue(
            "片段必须被截断到 ${QQLoginRedaction.MAX_SNIPPET} 字符内",
            once.length <= QQLoginRedaction.MAX_SNIPPET + 1,
        )
        assertFalse("片段不能含换行（否则导出会串行）", once.contains("\n"))
        assertFalse(once.contains("\r"))

        // 脱敏只保证「更粗」，不保证逐字节幂等：Cookie 头是整行抹掉的，而第一次调用已经把换行
        // 压成了空格，所以第二次会把同一行后面的内容一起吞掉。这里断言的是安全性（不会把凭证
        // 「还原」回来），而不是文本相等 —— 也正因为如此，导出不会做第二次脱敏。
        val twice = QQLoginRedaction.redact(once, listOf(qrsigValue))
        for (secret in allSecretValues) {
            assertFalse("二次脱敏后泄露了凭证：$secret -> $twice", twice.contains(secret))
        }
        assertTrue("二次脱敏只会脱得更多，不会更少", twice.length <= once.length)
    }

    // -----------------------------------------------------------------------------------------
    // 诊断日志：有界、脱敏、导出形状
    // -----------------------------------------------------------------------------------------

    /** 真机诊断里 authorize 失败时的 Location：`which=error` + `error=100012` + 一串凭证。 */
    private val accessTokenValue = "ATOK123456"
    private val ptsigxValue = "SIGX123456"
    private val authorizeErrorLocation =
        "https://graph.qq.com/oauth2.0/show?which=error&error=100012" +
            "&access_token=$accessTokenValue&p_skey=$pSkeyValue&code=$oauthCodeValue&ptsigx=$ptsigxValue"

    @Test
    fun diagnosticWithAnAuthorizeErrorUrlKeepsTheErrorCodeAndLeaksNoCredentials() {
        // 整条 URL 必须落在 MAX_SNIPPET 之内，否则下面的「没有泄露」断言会因为截断而白给。
        assertTrue(
            "测试用的错误 URL 必须短于 ${QQLoginRedaction.MAX_SNIPPET} 字符，实际 ${authorizeErrorLocation.length}",
            authorizeErrorLocation.length <= QQLoginRedaction.MAX_SNIPPET,
        )

        val diagnostics = QQLoginDiagnostics()
        diagnostics.setSensitiveValues(allSecretValues + listOf(accessTokenValue, ptsigxValue))
        diagnostics.record(
            step = "authorize",
            detail = "HTTP 302 | locationPresent=yes | codePresent=no" +
                " | newCookies=[] | qqError=100012 | whichError=yes",
            snippet = authorizeErrorLocation,
        )

        val stored = diagnostics.entries.single()
        assertTrue("错误码是最值钱的证据，必须保留：${stored.snippet}", stored.snippet.contains("error=100012"))
        assertTrue("which=error 是「授权被拒」的直接证据", stored.snippet.contains("which=error"))
        for (secret in allSecretValues + listOf(accessTokenValue, ptsigxValue)) {
            assertFalse("诊断里泄露了凭证：$secret -> ${stored.snippet}", stored.snippet.contains(secret))
        }
        assertFalse("access_token 的值不能残留", stored.snippet.contains("access_token=$accessTokenValue"))
        assertFalse("p_skey 的值不能残留", stored.snippet.contains("p_skey=$pSkeyValue"))
        assertFalse("code 的值不能残留", stored.snippet.contains("code=$oauthCodeValue"))
        assertFalse("ptsigx 的值不能残留", stored.snippet.contains("ptsigx=$ptsigxValue"))

        val exported = diagnostics.export(0L)
        assertTrue("导出里错误码必须可见：$exported", exported.contains("error=100012"))
        assertTrue("导出里 detail 行的错误码必须可见", exported.contains("qqError=100012"))
        for (secret in allSecretValues + listOf(accessTokenValue, ptsigxValue)) {
            assertFalse("导出里泄露了凭证：$secret", exported.contains(secret))
        }
    }

    @Test
    fun diagnosticWithALongAuthorizeErrorUrlStillKeepsTheErrorCodeInFront() {
        // 真实 URL 比 MAX_SNIPPET 长得多（auth_time / redirect_uri 等），片段会被截断 ——
        // 所以证据文本必须把 Location 放在最前面，错误码不能被后面的字段挤掉。
        val longLocation = authorizeErrorLocation +
            "&display=pc&auth_time=1700000000000&response_type=code&client_id=100497308" +
            "&redirect_uri=https%3A%2F%2Fy.qq.com%2Fportal%2Fwx_redirect.html%3Flogin_type%3D1"
        assertTrue("前提：这条 URL 必须超出片段上限", longLocation.length > QQLoginRedaction.MAX_SNIPPET)

        val diagnostics = QQLoginDiagnostics()
        diagnostics.setSensitiveValues(allSecretValues + listOf(accessTokenValue, ptsigxValue))
        diagnostics.record(
            step = "authorize",
            detail = "HTTP 302 | locationPresent=yes | codePresent=no | qqError=100012 | whichError=yes",
            snippet = authorizeEvidenceSnippet(
                body = "",
                location = longLocation,
                error = QQAuthorizeError(code = "100012", description = null, isErrorForm = true),
                codePresent = false,
            ),
        )
        val stored = diagnostics.entries.single().snippet
        assertTrue("截断后错误码仍然必须保留：$stored", stored.contains("error=100012"))
        for (secret in allSecretValues + listOf(accessTokenValue, ptsigxValue)) {
            assertFalse("截断后的片段泄露了凭证：$secret", stored.contains(secret))
        }
    }

    @Test
    fun diagnosticsRedactRawSnippetsBeforeStoringThem() {
        val diagnostics = QQLoginDiagnostics()
        diagnostics.setSensitiveValues(allSecretValues)
        diagnostics.record(
            step = "authorize",
            detail = "HTTP 200 | locationPresent=no | codePresent=no | newCookies=[]",
            snippet = rawResponseDump,
        )

        val stored = diagnostics.entries.single()
        for (secret in allSecretValues) {
            assertFalse("StateFlow 里的片段泄露了凭证：$secret", stored.snippet.contains(secret))
            assertFalse("导出文本泄露了凭证：$secret", diagnostics.export(0L).contains(secret))
        }
        assertTrue("步骤名要保留", diagnostics.export(0L).contains("authorize"))
    }

    @Test
    fun diagnosticsRedactSecretsEvenWhenTheyAreSmuggledIntoTheDetailLine() {
        // 将来有人在 detail 里拼了凭证：同样不允许有明文落进 StateFlow / 导出。
        // setSensitiveValues 与生产一致 —— QQMusicAuth.recordDiag 会把「当前内存里的
        // 全部 Cookie 值 + qrsig」登记进来，所以裸值（bare=...）也能被整串抹掉。
        val diagnostics = QQLoginDiagnostics()
        diagnostics.setSensitiveValues(listOf(skeyValue, pSkeyValue))
        diagnostics.record(
            step = "authorize",
            detail = "HTTP 200 | cookie=skey=$skeyValue | bare=$pSkeyValue",
            snippet = "Location: https://graph.qq.com/?code=$oauthCodeValue",
        )
        val stored = diagnostics.entries.single()
        assertFalse("detail 不能残留 skey 明文", stored.detail.contains(skeyValue))
        assertFalse("detail 不能残留 p_skey 明文（裸值也要抹）", stored.detail.contains(pSkeyValue))
        assertFalse("snippet 不能残留 code 明文", stored.snippet.contains(oauthCodeValue))
        val exported = diagnostics.export(0L)
        assertFalse(exported.contains(skeyValue))
        assertFalse(exported.contains(pSkeyValue))
        assertFalse(exported.contains(oauthCodeValue))
    }

    @Test
    fun exportKeepsTheResponseBodyThatFollowsACookieHeader() {
        // 入库时脱敏一次，导出只做拼接：如果导出再脱一次，Cookie 头整行规则会把同一行后面的
        // 报错信息一起吞掉 —— 那恰好是诊断里最值钱的部分。
        val diagnostics = QQLoginDiagnostics()
        diagnostics.setSensitiveValues(listOf(pSkeyValue))
        diagnostics.record(
            step = "check_sig#0",
            detail = "HTTP 200 | locationPresent=no",
            snippet = "Set-Cookie: p_skey=$pSkeyValue\r\n" +
                "callback( {\"error\":100019,\"error_description\":\"code has been used\"} );",
        )
        val stored = diagnostics.entries.single().snippet
        assertFalse("入库时就必须脱敏", stored.contains(pSkeyValue))
        assertTrue("Cookie 头之后的响应体必须保留：$stored", stored.contains("100019"))

        val exported = diagnostics.export(0L)
        assertTrue("导出不得把响应体吞掉：$exported", exported.contains("100019"))
        assertTrue(exported.contains("code has been used"))
        assertFalse(exported.contains(pSkeyValue))
    }

    @Test
    fun diagnosticsKeepThePollStatusCodeReadable() {
        // 脱敏规则会把「敏感字段名=值」抹掉，所以状态码必须写成 ptuiCB=66 而不是 code=66，
        // 否则 24 条日志里最重要的一条（走到哪一步了）会变成 <redacted>。
        val diagnostics = QQLoginDiagnostics()
        diagnostics.record(
            step = "ptqrlogin",
            detail = "HTTP 200 | ptuiCB=67（已扫码，等待手机确认） | redirectPresent=no",
        )
        val exported = diagnostics.export(0L)
        assertTrue("轮询状态码必须可读，实际导出：$exported", exported.contains("ptuiCB=67"))
        assertTrue(exported.contains("（已扫码，等待手机确认）"))
        assertTrue(exported.contains("redirectPresent=no"))
    }

    @Test
    fun diagnosticsAreBoundedAndDropTheOldestEntries() {
        val diagnostics = QQLoginDiagnostics(limit = 3)
        for (index in 1..5) {
            diagnostics.record(step = "step$index", detail = "HTTP 200")
        }
        assertEquals("超过上限后必须丢最旧的", 3, diagnostics.entries.size)
        assertEquals(listOf("step3", "step4", "step5"), diagnostics.entries.map { it.step })
        assertEquals(
            "StateFlow 镜像必须与内存一致",
            diagnostics.entries.map { it.step },
            diagnostics.entriesFlow.value.map { it.step },
        )
    }

    @Test
    fun diagnosticsExportIsNumberedStepsWithDetailsAndNoSecrets() {
        val diagnostics = QQLoginDiagnostics()
        diagnostics.setSensitiveValues(listOf(pSkeyValue))
        diagnostics.record("ptqrshow", "HTTP 200 | bytes=2048 | qrsigPresent=yes")
        diagnostics.record("check_sig#0", "HTTP 302 | locationPresent=yes | newCookies=[skey]")
        diagnostics.record(
            "authorize",
            "HTTP 200 | locationPresent=no | codePresent=no | newCookies=[]",
            "Set-Cookie: p_skey=$pSkeyValue; path=/",
        )

        val text = diagnostics.export(nowMillis = 0L)
        assertTrue("导出必须以固定标题开头", text.startsWith(QQLoginDiagnostics.EXPORT_HEADER))
        assertTrue("导出要带生成时间", text.contains("生成时间"))
        assertTrue(text.contains("1. ptqrshow | HTTP 200 | bytes=2048 | qrsigPresent=yes"))
        assertTrue(text.contains("2. check_sig#0 | HTTP 302 | locationPresent=yes | newCookies=[skey]"))
        assertTrue(text.contains("3. authorize | HTTP 200 | locationPresent=no | codePresent=no | newCookies=[]"))
        assertTrue("片段要缩进挂在步骤下面", text.contains("   Set-Cookie: ${QQLoginRedaction.MASK}"))
        assertFalse("导出的时段信息不能是凭证", text.contains(pSkeyValue))
        assertFalse("导出里不能出现 p_skey 的值", text.contains("p_skey=$pSkeyValue"))
    }

    @Test
    fun diagnosticsExportSaysSoWhenThereIsNothingToExport() {
        val diagnostics = QQLoginDiagnostics()
        val text = diagnostics.export(nowMillis = 0L)
        assertTrue(text.contains("暂无记录"))
        assertTrue(text.startsWith(QQLoginDiagnostics.EXPORT_HEADER))
    }

    @Test
    fun diagnosticsClearRemovesEntriesAndSecrets() {
        val diagnostics = QQLoginDiagnostics()
        diagnostics.setSensitiveValues(listOf(skeyValue))
        diagnostics.record("ptqrlogin", "HTTP 200 | ptuiCB=66", "skey=$skeyValue")
        assertTrue(diagnostics.entries.isNotEmpty())

        diagnostics.clear()
        assertTrue(diagnostics.entries.isEmpty())
        assertTrue(diagnostics.entriesFlow.value.isEmpty())

        // 清空后再记录：旧凭证值也不能被当成敏感值遗留（新值走新的 setSensitiveValues）
        diagnostics.record("ptqrlogin", "HTTP 200 | ptuiCB=67", "skey=$skeyValue")
        assertEquals("清空后的记录仍按字段名脱敏", "skey=${QQLoginRedaction.MASK}", diagnostics.entries.single().snippet)
    }

    // -----------------------------------------------------------------------------------------
    // authorize 请求组装：默认 POST 表单，GET 只作为「已知会被 QQ 拒绝」的备选保留
    // -----------------------------------------------------------------------------------------

    private val authorizeFields = linkedMapOf(
        "response_type" to "code",
        "client_id" to "100497308",
        "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
        "scope" to "all",
        "state" to "state",
        "switch" to "",
        "from_ptlogin" to "1",
        "src" to "1",
        "update_auth" to "1",
        "openapi" to "80901010_1030",
        "g_tk" to "1234567",
        "auth_time" to "1700000000000",
        "ui" to "DFEC5395-9E69-4D3E-96A6-300BB770874D",
    )

    @Test
    fun authorizeDefaultsToPostFormWithEveryFieldInTheBody() {
        assertTrue(
            "默认必须是 POST 表单：QQ 对 GET 请求这个端点返回公共返回码 100012（HTTP请求非post方式）",
            QQ_AUTHORIZE_USE_POST_FORM,
        )
        val spec = qqAuthorizeRequest(authorizeFields)
        assertNotNull("POST 请求必须带表单体", spec.formBody)
        assertEquals("POST 必须打在裸端点上（字段全在 body 里）", QQ_AUTHORIZE_ENDPOINT, spec.url)
        assertFalse("POST 时字段不能重复出现在 query 里", spec.url.contains('?'))
        assertEquals("字段名与取值必须逐个保持一致", authorizeFields, decodeQuery(spec.formBody!!))
    }

    /** GET 分支必须显式传入 —— 它不再是默认值，只是保留下来证明两种编码一致。 */
    private fun authorizeGetSpec() = qqAuthorizeRequest(authorizeFields, usePostForm = false)

    @Test
    fun authorizeGetRequestPutsEveryFieldInTheQueryString() {
        val spec = authorizeGetSpec()
        assertNull("GET 请求不能带表单体", spec.formBody)
        assertEquals(QQ_AUTHORIZE_ENDPOINT, spec.url.substringBefore('?'))

        val decoded = decodeQuery(spec.url.substringAfter('?'))
        assertEquals("字段名与取值必须逐个保持一致", authorizeFields, decoded)
    }

    @Test
    fun authorizeQueryEncodesTheRedirectUriSoItCannotBreakTheQuery() {
        val query = authorizeGetSpec().url.substringAfter('?')
        assertEquals(
            "只有一个字段分隔符会被当成 & —— redirect_uri 里的 & 必须被编码",
            authorizeFields.size - 1,
            query.count { it == '&' },
        )
        assertTrue(query.contains("redirect_uri=https%3A%2F%2Fy.qq.com%2Fportal%2Fwx_redirect.html"))
        assertFalse("redirect_uri 的查询串不能原样出现在外面", query.contains("&surl=https://y.qq.com/"))
    }

    @Test
    fun authorizeGetAndPostEncodeTheExactSameFields() {
        val get = authorizeGetSpec()
        val post = qqAuthorizeRequest(authorizeFields, usePostForm = true)
        assertEquals("POST 必须打在同一个端点上", QQ_AUTHORIZE_ENDPOINT, post.url)
        assertEquals("POST 体与 GET query 必须是同一套编码", get.url.substringAfter('?'), post.formBody)
        assertEquals(authorizeFields, decodeQuery(post.formBody!!))
        assertEquals(qqFormEncode(authorizeFields), post.formBody)
    }

    @Test
    fun queryKeysExposeNamesOnlyAndNeverValues() {
        val location = "https://ptlogin2.qq.com/check_sig?uin=MTIzNDU2&service=ptqrlogin" +
            "&ptsigx=SECRET_SIG_VALUE&f_url="
        val keys = queryKeys(location)
        assertEquals(listOf("uin", "service", "ptsigx", "f_url"), keys)
        assertTrue("只记参数名，不记值", keys.none { it.contains("SECRET") })
        assertTrue(queryKeys(QQ_AUTHORIZE_ENDPOINT).isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // QQ 公共返回码：从 Location / 响应体解析 -> 用户可见的一句话
    // -----------------------------------------------------------------------------------------

    /** 真机诊断第 6 步原文（auth_time 的具体值略）。 */
    private val realAuthorizeErrorLocation =
        "https://graph.qq.com/oauth2.0/show?which=error&display=pc&error=100012" +
            "&auth_time=1700000000000&response_type=code&client_id=100497308" +
            "&redirect_uri=https%3A%2F%2Fy.qq.com%2Fportal%2Fwx_redirect.html%3Flogin_type%3D1"

    /** 官方公共返回码表里与「扫码换 code」这条路相关的全部码。 */
    private val requiredReturnCodes = listOf(
        "100000", "100001", "100002", "100008", "100009", "100010", "100011", "100012",
        "100013", "100014", "100018", "100019", "100020", "100030", "100031", "100035",
        "100044", "100046", "100057", "100058", "100060", "100067", "100068", "110405",
    )

    @Test
    fun theReturnCodeTableCoversEveryPlausibleCodeForThisFlow() {
        val missing = requiredReturnCodes.filterNot { QQ_CONNECT_ERROR_CODES.containsKey(it) }
        assertTrue("官方公共返回码表里的这些码必须都有含义，缺：$missing", missing.isEmpty())
    }

    @Test
    fun authorizeErrorIsParsedFromTheWhichErrorLocation() {
        val error = parseAuthorizeError(realAuthorizeErrorLocation, "")
        assertNotNull("真机拿到的那条 Location 必须能解析出错误码", error)
        error!!
        assertEquals("100012", error.code)
        assertTrue("必须识别 which=error 形状", error.isErrorForm)
        assertTrue(error.hasCode)
    }

    @Test
    fun authorizeErrorIsParsedFromTheJsonpBodyToo() {
        val error = parseAuthorizeError(
            "",
            "callback( {\"error\":100019,\"error_description\":\"code has been used\"} );",
        )
        error!!
        assertEquals("100019", error.code)
        assertEquals("code has been used", error.description)
        assertFalse("JSONP 回调不是 which=error 跳转页形状", error.isErrorForm)
    }

    @Test
    fun authorizeErrorIsParsedFromAFormEncodedBodyToo() {
        val error = parseAuthorizeError("", "error=100020&error_description=code+has+been+used")
        error!!
        assertEquals("100020", error.code)
        assertEquals("表单里的 error_description 要被解码", "code has been used", error.description)
    }

    @Test
    fun authorizeErrorIsNullWhenTheRedirectCarriesACode() {
        assertNull(
            "正常返回 code 时不能报成错误",
            parseAuthorizeError("https://y.qq.com/portal/wx_redirect.html?code=ABC123&state=state", ""),
        )
        assertNull("空响应里没有错误信息", parseAuthorizeError("", ""))
        assertNull("普通 HTML 里没有 error= 就不是错误码", parseAuthorizeError("", "<html>ok</html>"))
    }

    @Test
    fun whichErrorWithoutACodeIsStillReported() {
        val error = parseAuthorizeError("https://graph.qq.com/oauth2.0/show?which=error&display=pc", "")
        error!!
        assertEquals("服务端没给码时 code 为空串", "", error.code)
        assertFalse(error.hasCode)
        assertTrue(error.isErrorForm)

        val sentence = qqAuthorizeErrorSentence(error)
        assertTrue("必须给出可读文案，实际：$sentence", sentence.isNotBlank())
        assertTrue("要说明没有错误码，实际：$sentence", sentence.contains("错误码"))
        assertTrue("要引导看诊断，实际：$sentence", sentence.contains("诊断"))
    }

    @Test
    fun theRealDiagnosticLocationBecomesAReadableSentence() {
        val error = parseAuthorizeError(realAuthorizeErrorLocation, "")
        val sentence = qqAuthorizeErrorSentence(error!!)
        assertTrue("必须带上原始错误码，实际：$sentence", sentence.contains("100012"))
        assertTrue("100012 的含义是「HTTP 请求非 POST 方式」，实际：$sentence", sentence.contains("POST"))
        assertTrue("要明确是 QQ 拒绝授权，实际：$sentence", sentence.contains("拒绝授权"))
        assertFalse("不能把 URL 原样丢给用户，实际：$sentence", sentence.contains("http"))
        assertFalse("不能把 URL 原样丢给用户，实际：$sentence", sentence.contains("graph.qq.com"))
    }

    @Test
    fun callbackAddressAndSkeyPermissionCodesAreNamedConcretely() {
        val callback = qqAuthorizeErrorSentence(QQAuthorizeError("100010", null, true))
        assertTrue(callback.contains("100010"))
        assertTrue("100010 的含义是「回调地址不合法」，实际：$callback", callback.contains("回调地址"))

        val noSkeyPermission = qqAuthorizeErrorSentence(QQAuthorizeError("100057", null, true))
        assertTrue(noSkeyPermission.contains("100057"))
        assertTrue("100057 与 skey 换 code 的权限有关，实际：$noSkeyPermission", noSkeyPermission.contains("skey"))
    }

    @Test
    fun unknownAuthorizeErrorIsNeverSwallowed() {
        val sentence = qqAuthorizeErrorSentence(QQAuthorizeError("103999", "weird server text", false))
        assertTrue("未知码必须原样显示，实际：$sentence", sentence.contains("QQ 错误码 103999"))
        assertTrue("码未知时服务端描述是唯一线索，要带上，实际：$sentence", sentence.contains("weird server text"))

        val bare = qqAuthorizeErrorSentence(QQAuthorizeError("not_a_number", null, true))
        assertTrue("非数字码也要原样保留，实际：$bare", bare.contains("not_a_number"))

        val sanitized = qqAuthorizeErrorSentence(QQAuthorizeError("103999", "p_skey=$pSkeyValue", false))
        assertFalse("服务端描述同样要脱敏，实际：$sanitized", sanitized.contains(pSkeyValue))
    }

    @Test
    fun everyDocumentedReturnCodeHasItsOwnBilingualSentence() {
        val chinese = QQ_CONNECT_ERROR_CODES.keys.map {
            qqAuthorizeErrorSentence(QQAuthorizeError(it, null, true))
        }
        assertTrue("每个码都要有非空文案", chinese.all { it.isNotBlank() })
        assertEquals(
            "重复含义会让用户看不出区别",
            QQ_CONNECT_ERROR_CODES.size,
            chinese.toSet().size,
        )
        for ((code, sentence) in QQ_CONNECT_ERROR_CODES.keys.zip(chinese)) {
            assertTrue("每条文案都要带上原始码 $code，实际：$sentence", sentence.contains(code))
        }

        Lang.current.value = AppLanguage.ENGLISH
        val english = QQ_CONNECT_ERROR_CODES.keys.map {
            qqAuthorizeErrorSentence(QQAuthorizeError(it, null, true))
        }
        assertTrue(
            "英文状态下不能出现中文文案",
            english.none { message -> message.any { it.code in 0x4E00..0x9FFF } },
        )
        assertEquals(QQ_CONNECT_ERROR_CODES.size, english.toSet().size)
    }

    @Test
    fun authorizeEvidenceAlwaysKeepsTheWhichErrorEvidenceFirst() {
        val snippet = authorizeEvidenceSnippet(
            body = "callback( {\"error\":100019,\"error_description\":\"code has been used\"} );",
            location = "https://graph.qq.com/oauth2.0/show?which=error&error=100019",
            error = QQAuthorizeError("100019", "code has been used", true),
            codePresent = false,
        )
        assertTrue("which=error 证据必须排在最前（片段会被截断）：$snippet", snippet.startsWith("Location: "))
        assertTrue(snippet.contains("which=error"))
        assertTrue(snippet.contains("error=100019"))
        assertTrue("响应体也要保留：$snippet", snippet.contains("code has been used"))
    }

    @Test
    fun authorizeEvidenceRecordsTheBodyEvenWhenThereIsNoError() {
        val emptyBody = authorizeEvidenceSnippet(
            body = "   ",
            location = "https://y.qq.com/portal/wx_redirect.html?code=ABC&state=state",
            error = null,
            codePresent = true,
        )
        assertEquals("成功且没有响应体时不再记 Location（里面就是 code 本身）", "", emptyBody)

        val withBody = authorizeEvidenceSnippet(
            body = "callback({\"ret\":0});",
            location = "https://y.qq.com/portal/wx_redirect.html?code=ABC&state=state",
            error = null,
            codePresent = true,
        )
        assertTrue("成功但有响应体时也要留证据：$withBody", withBody.contains("ret"))
        assertFalse("成功时的 Location 就是 code 本身，不记：$withBody", withBody.contains("y.qq.com"))
    }

    /** `a=1&b=2` -> map；用 URLDecoder 处理 `%XX` 与 `+`。 */
    private fun decodeQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val key = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
            val value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            out[key] = value
        }
        return out
    }
}

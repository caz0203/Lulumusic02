package com.lulu.music

import com.lulu.music.data.auth.QQWebSessionStrength
import com.lulu.music.data.auth.QQ_ACCOUNT_ID_COOKIE_KEYS
import com.lulu.music.data.auth.QQ_COOKIE_EMISSION_ORDER
import com.lulu.music.data.auth.QQ_UIN_DERIVATION_SOURCES
import com.lulu.music.data.auth.QQ_WEB_COOKIE_SOURCES
import com.lulu.music.data.auth.QQ_WEB_LOGIN_URL
import com.lulu.music.data.auth.hasUsableAccountID
import com.lulu.music.data.auth.isQQLoginDomain
import com.lulu.music.data.auth.normalizedUIN
import com.lulu.music.data.auth.qqAccountID
import com.lulu.music.data.auth.qqAuthorizeCookieHeader
import com.lulu.music.data.auth.qqCookieHeaderFromPairs
import com.lulu.music.data.auth.qqCookiePairsFromHeader
import com.lulu.music.data.auth.qqDerivedUIN
import com.lulu.music.data.auth.qqHostOf
import com.lulu.music.data.auth.qqNormalizedCookieDict
import com.lulu.music.data.auth.qqSessionCookiesFromDomains
import com.lulu.music.data.auth.qqWebSessionStrength
import com.lulu.music.data.auth.qqWebSessionUsable
import com.lulu.music.data.auth.sentCookieNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ **网页登录**与 authorize Cookie 头的纯逻辑测试 —— 全部是 JVM 上的顶层函数，
 * 不碰 `QQMusicAuth` 这个 object（它的 init 需要真实的 Application / SharedPreferences），
 * 不碰 `CookieManager`，不发任何网络请求。
 *
 * 覆盖三块：
 *  1. [qqAuthorizeCookieHeader] / [sentCookieNames]：真机 `error=100035`（用户未登录）的直接原因
 *     —— 旧实现用**白名单**拼头，链路刚下发的 `pt_oauth_token` / `pt_login_type` 被丢掉，
 *     `uin` 更是完全没有。
 *  2. [qqSessionCookiesFromDomains] / [qqWebSessionStrength]：WebView 采集后的合并规则与
 *     「这个会话能用了没」的判据（UI 自动导入就靠它，因此必须严格）。
 *  3. [isQQLoginDomain] / [qqHostOf]：域名匹配。
 *
 * **不能**覆盖的（诚实说明）：真正的 WebView、`CookieManager.getCookie` 返回什么、
 * 用户能不能登进去、`qqmusic_key` 到底会不会被服务端下发 —— 这些要真机。
 */
class QQWebLoginTest {

    // -----------------------------------------------------------------------------------------
    // Cookie 头解析 / 拼装
    // -----------------------------------------------------------------------------------------

    @Test
    fun cookieHeaderParsingSkipsMalformedPairsAndKeepsEveryRealOne() {
        val parsed = qqCookiePairsFromHeader(
            "uin=o0123456789; pt_oauth_token=abc; broken; =nope; empty=; qqmusic_key=KEY123",
        )
        assertEquals("o0123456789", parsed["uin"])
        assertEquals("abc", parsed["pt_oauth_token"])
        assertEquals("KEY123", parsed["qqmusic_key"])
        assertFalse("缺 = 的片段不能变成 Cookie", parsed.containsKey("broken"))
        assertFalse("空名不能变成 Cookie", parsed.containsKey(""))
        assertFalse("空值不算登录态", parsed.containsKey("empty"))
        assertEquals("只应有 3 个有效 Cookie", 3, parsed.size)
    }

    @Test
    fun cookieHeaderParsingHandlesNullBlankAndMultiLineHeaders() {
        assertTrue(qqCookiePairsFromHeader(null).isEmpty())
        assertTrue(qqCookiePairsFromHeader("   ").isEmpty())
        val multiline = qqCookiePairsFromHeader("uin=o111\np_skey=PS\r\nqqmusic_key=MK")
        assertEquals("o111", multiline["uin"])
        assertEquals("PS", multiline["p_skey"])
        assertEquals("MK", multiline["qqmusic_key"])
    }

    @Test
    fun cookieHeaderRenderingKeepsKnownNamesAheadAndUnknownNamesInTheHeader() {
        val cookies = mapOf(
            "weblogin_sig" to "WEIRD",
            "qqmusic_key" to "MK",
            "pt_oauth_token" to "OAUTH",
            "uin" to "o123456789",
        )
        val header = qqCookieHeaderFromPairs(cookies)
        assertEquals("uin=o123456789; qqmusic_key=MK; pt_oauth_token=OAUTH; weblogin_sig=WEIRD", header)
    }

    // -----------------------------------------------------------------------------------------
    // authorize Cookie 头：整仓 + 派生 uin（Task 1 的核心）
    // -----------------------------------------------------------------------------------------

    /**
     * 真机失败现场的最小复现：链路只给了 `p_uin` / `pt2gguin`，没有 `uin`。
     * 旧白名单会发 `qqmusic_key` + `p_skey` 而**丢掉** `uin` 和 `pt_oauth_token` —— 这正是
     * `graph.qq.com/oauth2.0/authorize` 回 `error=100035` 的形状。
     */
    @Test
    fun authorizeCookieHeaderSynthesisesUinFromPt2gguinAndDropsNothing() {
        val jar = linkedMapOf(
            "p_uin" to "o2223334444",
            "pt2gguin" to "o1112223333",
            "pt_oauth_token" to "OAUTH",
            "pt_login_type" to "1",
            "qqmusic_key" to "MK",
            "p_skey" to "PS",
            "weblogin_sig" to "WEIRD",
        )
        val header = qqAuthorizeCookieHeader(jar, includeUIN = true)

        assertTrue(
            "uin 是 authorize 认会话的关键，必须在最前面；实际：$header",
            header.startsWith("uin=o1112223333; "),
        )
        assertTrue("pt2gguin 优先于 p_uin", header.contains("uin=o1112223333"))
        for (name in listOf("pt_oauth_token", "pt_login_type", "qqmusic_key", "p_skey", "p_uin")) {
            assertTrue("$name 不能被白名单丢掉；实际：$header", header.contains("$name="))
        }
        assertTrue("白名单里从来没有的名字也要原样发出；实际：$header", header.contains("weblogin_sig=WEIRD"))
    }

    @Test
    fun authorizeCookieHeaderFallsBackToPUinWhenPt2gguinIsUnusable() {
        val header = qqAuthorizeCookieHeader(
            mapOf("p_uin" to "o555", "pt2gguin" to "0", "p_skey" to "PS"),
            includeUIN = true,
        )
        assertTrue("pt2gguin=0 不能用，必须退回 p_uin；实际：$header", header.contains("uin=o555"))
    }

    @Test
    fun authorizeCookieHeaderLeavesARealUinAlone() {
        val header = qqAuthorizeCookieHeader(
            mapOf("uin" to "o999", "pt2gguin" to "o111", "p_skey" to "PS"),
            includeUIN = true,
        )
        assertTrue("已有 uin 时不得被派生值覆盖；实际：$header", header.contains("uin=o999"))
        val sent = qqCookiePairsFromHeader(header)
        assertEquals("只能有一个 uin，且必须是仓库里那个", "o999", sent["uin"])
        assertFalse("不得再从 pt2gguin 派生一个 uin", sent["uin"] == "o111")
        assertTrue("pt2gguin 本身不能被删掉", header.contains("pt2gguin="))
    }

    @Test
    fun authorizeCookieHeaderDoesNotInventAUinWhenThereIsNothingToDeriveItFrom() {
        val header = qqAuthorizeCookieHeader(mapOf("qqmusic_key" to "MK"), includeUIN = true)
        assertEquals("qqmusic_key=MK", header)
        assertFalse("没有账号 ID 时不许凭空造 uin", header.contains("uin"))
    }

    /** 微信登录的歌单接口专用头：**故意**不注入兼容 uin（否则 QQ 接口会返回空歌单）。 */
    @Test
    fun playlistCookieHeaderOmitsTheCompatibilityUin() {
        val jar = mapOf("p_uin" to "o222", "pt2gguin" to "o111", "qqmusic_key" to "MK", "u" to "1")
        val withUin = qqAuthorizeCookieHeader(jar, includeUIN = true)
        val withoutUin = qqAuthorizeCookieHeader(jar, includeUIN = false)
        assertTrue(withUin.contains("uin=o111"))
        assertFalse(
            "includeUIN=false 时不得注入兼容 uin（`pt2gguin=` / `p_uin=` 里也有 `uin=`，" +
                "所以必须按「Cookie 名」匹配而不是子串）；实际：$withoutUin",
            qqCookiePairsFromHeader(withoutUin).containsKey("uin"),
        )
        assertTrue("但其余 Cookie 一个都不能少", withoutUin.contains("qqmusic_key=MK"))
    }

    @Test
    fun authorizeCookieHeaderIsStableAcrossRepeatedCalls() {
        val jar = linkedMapOf("pt2gguin" to "o1", "qqmusic_key" to "MK", "weblogin_sig" to "W")
        assertEquals(
            "同一次会话重复拼头必须逐字一致（否则「诊断里看到的」和「实际发的」会不一致）",
            qqAuthorizeCookieHeader(jar, includeUIN = true),
            qqAuthorizeCookieHeader(jar, includeUIN = true),
        )
    }

    // -----------------------------------------------------------------------------------------
    // 诊断：只记名字，不记值
    // -----------------------------------------------------------------------------------------

    @Test
    fun sentCookieNamesReturnNamesInEmissionOrderAndNoValues() {
        val header = qqAuthorizeCookieHeader(
            mapOf(
                "weblogin_sig" to "SECRET-VALUE-A",
                "qqmusic_key" to "SECRET-VALUE-B",
                "uin" to "o123",
                "pt_oauth_token" to "SECRET-VALUE-C",
            ),
            includeUIN = true,
        )
        val names = sentCookieNames(header)
        assertEquals(listOf("uin", "qqmusic_key", "pt_oauth_token", "weblogin_sig"), names)
        for (name in names) {
            assertFalse("诊断只能有名字；$name 带上了值", name.contains("SECRET-VALUE"))
        }
        assertEquals("名字不能被值污染", 4, names.size)
    }

    @Test
    fun sentCookieNamesIncludesADerivedUinAndIgnoresBlankHeaders() {
        val derived = qqAuthorizeCookieHeader(mapOf("pt2gguin" to "o777", "p_skey" to "PS"), includeUIN = true)
        assertEquals(listOf("uin", "pt2gguin", "p_skey"), sentCookieNames(derived))
        assertTrue(sentCookieNames("").isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // 账号 ID / uin 派生
    // -----------------------------------------------------------------------------------------

    @Test
    fun accountIdPreferenceIsUinThenWxuinThenPt2gguin() {
        assertEquals(
            "uin 优先",
            "o111",
            qqAccountID(mapOf("uin" to "o111", "wxuin" to "o222", "pt2gguin" to "o333")),
        )
        assertEquals(
            "没有 uin 时先看 wxuin（微信登录）",
            "o222",
            qqAccountID(mapOf("wxuin" to "o222", "pt2gguin" to "o333")),
        )
        assertEquals("再看 pt2gguin", "o333", qqAccountID(mapOf("pt2gguin" to "o333")))
        assertEquals("都没有就是 0", "0", qqAccountID(mapOf("p_skey" to "PS")))
        assertEquals("0 / o0 都不算账号", "0", qqAccountID(mapOf("uin" to "o0", "wxuin" to "0")))
    }

    @Test
    fun uinDerivationPrefersPt2gguinAndKeepsTheOPrefix() {
        assertEquals(
            "派生优先级 = pt2gguin > p_uin",
            listOf("pt2gguin", "p_uin"),
            QQ_UIN_DERIVATION_SOURCES,
        )
        assertEquals("o123", qqDerivedUIN(mapOf("pt2gguin" to "o123", "p_uin" to "o456")))
        assertEquals("o456", qqDerivedUIN(mapOf("p_uin" to "o456")))
        assertNull("没有可派生来源就是 null", qqDerivedUIN(mapOf("pt2gguin" to "o0", "p_uin" to "")))
        assertNull(qqDerivedUIN(emptyMap()))
    }

    /**
     * `uin` 与其余代码的约定：**保留来源的 `o…` 形态**（`pt2gguin` / `p_uin` 就是这个形态），
     * 需要纯数字 QQ 号的接口走 `normalizedUIN`（= `QQMusicAuth.uin`）。
     */
    @Test
    fun derivedUinKeepsTheOFormAndNormalizedUinStripsIt() {
        val derived = qqDerivedUIN(mapOf("pt2gguin" to "o987654321"))!!
        assertTrue("派生值保留 o 前缀", derived.startsWith("o"))
        assertEquals("987654321", normalizedUIN(derived))
        assertEquals("没有前缀时原样返回", "987654321", normalizedUIN("987654321"))
        assertEquals("空串保持空串", "", normalizedUIN("  "))
    }

    @Test
    fun usableAccountIdRejectsThePlaceholderForms() {
        assertFalse(hasUsableAccountID(null))
        assertFalse(hasUsableAccountID(""))
        assertFalse(hasUsableAccountID("   "))
        assertFalse(hasUsableAccountID("0"))
        assertFalse(hasUsableAccountID("o0"))
        assertTrue(hasUsableAccountID("o123"))
        assertTrue(hasUsableAccountID(" 123 "))
    }

    // -----------------------------------------------------------------------------------------
    // WebView 采集后的合并规则
    // -----------------------------------------------------------------------------------------

    @Test
    fun domainMergePutsTheMusicDomainLastSoItsValueWins() {
        val merged = qqSessionCookiesFromDomains(
            mapOf(
                "https://y.qq.com" to mapOf("qqmusic_key" to "MUSIC-DOMAIN", "uin" to "o111"),
                "https://qq.com" to mapOf("qqmusic_key" to "PARENT-DOMAIN", "p_skey" to "PS"),
                "https://ptlogin2.qq.com" to mapOf("pt_oauth_token" to "OAUTH", "uin" to "o999"),
            ),
        )
        assertEquals(
            "y.qq.com 是判据所在，同名 Cookie 以它为准",
            "MUSIC-DOMAIN",
            merged["qqmusic_key"],
        )
        assertEquals("y.qq.com 的 uin 压过 ptlogin 域的旧值", "o111", merged["uin"])
        assertEquals("父域独有的 Cookie 必须保留", "PS", merged["p_skey"])
        assertEquals("中间域独有的 Cookie 必须保留", "OAUTH", merged["pt_oauth_token"])
    }

    @Test
    fun domainMergeIsIndependentOfTheCallersMapOrder() {
        val asWritten = mapOf(
            "https://y.qq.com" to mapOf("uin" to "o1"),
            "https://qq.com" to mapOf("uin" to "o2"),
        )
        val reversed = mapOf(
            "https://qq.com" to mapOf("uin" to "o2"),
            "https://y.qq.com" to mapOf("uin" to "o1"),
        )
        assertEquals(qqSessionCookiesFromDomains(asWritten), qqSessionCookiesFromDomains(reversed))
    }

    @Test
    fun domainMergeSkipsEmptyValuesAndCarriesUnknownDomains() {
        val merged = qqSessionCookiesFromDomains(
            mapOf(
                "https://y.qq.com" to mapOf("empty_cookie" to "", "qqmusic_key" to "MK"),
                "https://ptlogin2.qq.com" to emptyMap(),
                "https://weird.example" to mapOf("extra" to "E", "qqmusic_key" to "SHOULD-NOT-WIN"),
            ),
        )
        assertFalse("空值不能进登录态", merged.containsKey("empty_cookie"))
        assertEquals("常量表之外的域优先级最低", "MK", merged["qqmusic_key"])
        assertEquals("但它的独有 Cookie 也要带上", "E", merged["extra"])
    }

    @Test
    fun theHarvestedDomainsCoverEveryPlaceTheChainSetsCredentials() {
        assertEquals(
            "顺序 = 优先级（越靠后越优先），音乐域必须最后",
            listOf("https://qq.com", "https://ptlogin2.qq.com", "https://graph.qq.com", "https://y.qq.com"),
            QQ_WEB_COOKIE_SOURCES,
        )
        assertTrue("判据所在的域必须在采集列表里", QQ_WEB_COOKIE_SOURCES.contains("https://y.qq.com"))
        assertEquals("登录页就是音乐门户，不做脆弱深链", "https://y.qq.com/", QQ_WEB_LOGIN_URL)
    }

    // -----------------------------------------------------------------------------------------
    // 「这个会话能用了没」的判据（UI 的自动导入就靠它）
    // -----------------------------------------------------------------------------------------

    @Test
    fun aMusicDomainSessionIsUsable() {
        val session = mapOf("uin" to "o123", "qqmusic_key" to "MK")
        assertTrue(qqWebSessionUsable(session))
        assertEquals(QQWebSessionStrength.MUSIC_SESSION, qqWebSessionStrength(session))
    }

    @Test
    fun everyAccountIdCookieCanCarryASessionOnItsOwn() {
        for (name in QQ_ACCOUNT_ID_COOKIE_KEYS) {
            val session = mapOf(name to "o123", "qm_keyst" to "MK")
            assertEquals("$name 也必须算账号 ID", QQWebSessionStrength.MUSIC_SESSION, qqWebSessionStrength(session))
        }
    }

    @Test
    fun anAccountWithoutAnyCredentialIsNotUsableYet() {
        // 「只有账号」= 有账号 ID，且**任何**凭证名（音乐域 + p_skey/skey）都没有。
        // `weblogin_sig` 是 QQ 登录链路会下发、但登录态/播放接口都不读的无关 Cookie：
        // 它的存在绝不能被当成「已登录」。
        val half = mapOf("uin" to "o123", "weblogin_sig" to "WEIRD")
        assertFalse("有账号无凭证时不能报成功", qqWebSessionUsable(half))
        assertEquals(QQWebSessionStrength.ACCOUNT_ONLY, qqWebSessionStrength(half))
        val accountOnly = mapOf("uin" to "o123", "p_uin" to "o123")
        assertFalse("换一种账号 Cookie 也一样", qqWebSessionUsable(accountOnly))
    }

    @Test
    fun aCredentialWithoutAnAccountIsNotUsableEither() {
        val orphan = mapOf("qqmusic_key" to "MK", "p_skey" to "PS")
        assertFalse("没有账号 ID 时不算可用", qqWebSessionUsable(orphan))
        assertEquals(QQWebSessionStrength.NONE, qqWebSessionStrength(orphan))
    }

    @Test
    fun placeholderAccountIdsNeverCountAsASession() {
        assertFalse(qqWebSessionUsable(mapOf("uin" to "0", "qqmusic_key" to "MK")))
        assertFalse(qqWebSessionUsable(mapOf("uin" to "o0", "qqmusic_key" to "MK")))
        assertFalse(qqWebSessionUsable(emptyMap()))
    }

    @Test
    fun aPskeyOnlySessionIsWeakerThanAMusicCredentialOne() {
        val pskeyOnly = mapOf("uin" to "o123", "p_skey" to "PS")
        assertTrue("p_skey 够用（旧登录态兜底）", qqWebSessionUsable(pskeyOnly))
        assertEquals(QQWebSessionStrength.QQ_SESSION, qqWebSessionStrength(pskeyOnly))
    }

    @Test
    fun everyMusicCredentialNameIsRecognisedAsACompleteSession() {
        for (name in listOf("qqmusic_key", "qm_keyst", "music_key", "musickey", "wxskey", "wx_skey")) {
            assertEquals(
                "$name 是音乐域凭证，必须算完整会话",
                QQWebSessionStrength.MUSIC_SESSION,
                qqWebSessionStrength(mapOf("uin" to "o123", name to "V")),
            )
        }
    }

    @Test
    fun theEmissionOrderDoesNotDecideUsabilityButCoversTheKnownCredentials() {
        for (name in listOf("uin", "p_uin", "pt2gguin", "qqmusic_key", "qm_keyst", "p_skey", "pt_oauth_token")) {
            assertTrue("$name 应在偏好顺序表里（只为可读性，不影响可用性）", QQ_COOKIE_EMISSION_ORDER.contains(name))
        }
    }

    // -----------------------------------------------------------------------------------------
    // 旧备份里的「整段头当键」也要能读
    // -----------------------------------------------------------------------------------------

    @Test
    fun legacyDictionaryWithAWholeHeaderAsItsKeyIsStillReadable() {
        val legacy = mapOf("uin=o12345; qqmusic_key=MK; p_skey=PS" to "uin=o12345; qqmusic_key=MK; p_skey=PS")
        val normalized = qqNormalizedCookieDict(legacy)
        assertEquals("o12345", normalized["uin"])
        assertEquals("MK", normalized["qqmusic_key"])
        assertEquals("PS", normalized["p_skey"])
        assertEquals("整段头那个键不能留下来", 3, normalized.size)
    }

    @Test
    fun alreadyNormalisedDictionariesSurviveNormalisationUnchanged() {
        val dict = linkedMapOf("uin" to "o123", "qqmusic_key" to "MK", "empty" to "")
        assertEquals(mapOf("uin" to "o123", "qqmusic_key" to "MK"), qqNormalizedCookieDict(dict))
    }

    // -----------------------------------------------------------------------------------------
    // 域名匹配（诊断标注用）
    // -----------------------------------------------------------------------------------------

    @Test
    fun qqLoginDomainsAreMatchedBySuffixNotBySubstring() {
        assertTrue(isQQLoginDomain("https://y.qq.com/portal/profile.html"))
        assertTrue(isQQLoginDomain("https://y.qq.com"))
        assertTrue(isQQLoginDomain("https://qq.com/"))
        assertTrue(isQQLoginDomain("https://graph.qq.com/oauth2.0/authorize"))
        assertTrue(isQQLoginDomain("https://PTLOGIN2.QQ.COM/x"))
        assertTrue(isQQLoginDomain("https://ssl.ptlogin2.qq.com/ptqrshow?a=1"))
        assertFalse("后缀之外不能匹配", isQQLoginDomain("https://qq.com.evil.example/login"))
        assertFalse(isQQLoginDomain("https://notqq.com/"))
        assertFalse(isQQLoginDomain("https://music.163.com/"))
        assertFalse(isQQLoginDomain(null))
        assertFalse(isQQLoginDomain(""))
        assertFalse("不是 http(s) 就不认", isQQLoginDomain("about:blank"))
    }

    @Test
    fun hostParsingStripsSchemePortUserInfoAndCase() {
        assertEquals("y.qq.com", qqHostOf("https://y.qq.com/a/b?c=d#e"))
        assertEquals("y.qq.com", qqHostOf("HTTP://Y.QQ.COM:443/"))
        assertEquals("y.qq.com", qqHostOf("https://user:pw@y.qq.com/x"))
        assertEquals("qq.com", qqHostOf("https://qq.com"))
        assertNull("没有 scheme 就不认", qqHostOf("y.qq.com/x"))
        assertNull(qqHostOf(""))
        assertNull(qqHostOf(null))
    }
}

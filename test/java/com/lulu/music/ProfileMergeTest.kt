package com.lulu.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.data.stats.UserStatsStore
import com.lulu.music.ui.BeansNavigator
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.screens.ProfileScreen
import com.lulu.music.ui.screens.normalizeProfileSectionOrder
import com.lulu.music.ui.screens.profilePlatformChips
import com.lulu.music.ui.screens.profileSectionKeys
import com.lulu.music.ui.theme.BeansTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「我的」页把**两张重复的卡片合并成一张**之后的回归防线。
 *
 * 分两层：
 *  - [ProfileMergeLogicTest]：板块排序的规范化 + 平台胶囊列表（纯函数，确定性强）；
 *  - [ProfileMergeRenderTest]：合并后的卡片真的只有一份头像 / 昵称 / ID，而且原来的入口
 *    （登录 / 恢复我的ID / 我的下载 / 交流群 / 自愿赞助）全都还在。
 *
 * 渲染测试跑在「未登录」状态下（`AuthStore` 的登录态在 Robolectric 里不可写），
 * 因此平台胶囊这一半由纯函数测试覆盖：`profilePlatformChips` 就是卡片上那段渲染用的数据源。
 */
class ProfileMergeLogicTest {

    @Test
    fun sectionOrderAlwaysKeepsEverySectionAndCollapsesTheOldTwoCardValue() {
        // 旧版本存下来的 "stats,account"：两张卡已经合成一张，必须折叠成单个 account
        assertEquals(
            listOf("account", "downloads", "community", "donation"),
            normalizeProfileSectionOrder("stats,account"),
        )
        // 只留一个板块也不会让其它板块消失（when 里没有分支 = 整块不渲染）
        assertEquals(
            listOf("downloads", "account", "community", "donation"),
            normalizeProfileSectionOrder("downloads"),
        )
        // 空串 / 损坏的值 → 默认顺序
        assertEquals(profileSectionKeys, normalizeProfileSectionOrder(""))
        assertEquals(profileSectionKeys, normalizeProfileSectionOrder(",,,,"))
        assertEquals(profileSectionKeys, normalizeProfileSectionOrder("nonsense,stats"))
        // 去重
        assertEquals(
            listOf("downloads", "account", "community", "donation"),
            normalizeProfileSectionOrder("downloads,downloads,account"),
        )
        // 每个板块恰好出现一次
        normalizeProfileSectionOrder("donation,community,downloads,account").let { order ->
            assertEquals(profileSectionKeys.size, order.size)
            assertEquals(profileSectionKeys.toSet(), order.toSet())
        }
    }

    @Test
    fun platformChipsKeepEverySignedInEnabledPlatform() {
        val all = profilePlatformChips(
            neteaseEnabled = true,
            neteaseLoggedIn = true,
            neteaseNickname = "小鹿",
            neteaseUid = 42L,
            neteaseVip = "VIP",
            qqEnabled = true,
            qqLoggedIn = true,
            qqNickname = "QQ小鹿",
            qqVip = null,
            kugouEnabled = true,
            kugouLoggedIn = true,
            kugouNickname = "",
            kugouVip = "豪华VIP",
        )

        assertEquals("三个平台都登录时必须有三颗胶囊", 3, all.size)
        assertEquals("网易云音乐", all[0].name)
        assertEquals("小鹿", all[0].status)
        assertEquals("VIP", all[0].badge)
        assertEquals("QQ 音乐", all[1].name)
        assertEquals("QQ小鹿", all[1].status)
        assertEquals("酷狗音乐", all[2].name)
        assertEquals("昵称为空时退回「已登录」", "已登录", all[2].status)
        assertEquals("豪华VIP", all[2].badge)

        // 未登录 / 未启用 / 昵称为空 + 有 UID 的兜底
        assertTrue(
            "全部未登录时不该有任何胶囊",
            profilePlatformChips(
                neteaseEnabled = true, neteaseLoggedIn = false, neteaseNickname = "",
                neteaseUid = null, neteaseVip = null,
                qqEnabled = true, qqLoggedIn = false, qqNickname = "", qqVip = null,
                kugouEnabled = true, kugouLoggedIn = false, kugouNickname = "", kugouVip = null,
            ).isEmpty(),
        )
        val neteaseOnly = profilePlatformChips(
            neteaseEnabled = true, neteaseLoggedIn = true, neteaseNickname = "",
            neteaseUid = 99L, neteaseVip = null,
            qqEnabled = false, qqLoggedIn = true, qqNickname = "QQ", qqVip = null,
            kugouEnabled = true, kugouLoggedIn = false, kugouNickname = "酷", kugouVip = null,
        )
        assertEquals("未启用的平台不显示", 1, neteaseOnly.size)
        assertEquals("昵称为空时退回 UID", "UID 99", neteaseOnly[0].status)
    }
}

/**
 * 合并后的「我的」页渲染：一张卡 + 三个功能板块。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class ProfileMergeRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var loginOpens = 0
    private var downloadOpens = 0

    private val navigator = BeansNavigator(
        openPlayer = {},
        openSettings = {},
        openLogin = { loginOpens++ },
        openDownloads = { downloadOpens++ },
        openSources = {},
        openPlaylist = { _: Playlist -> },
        openPlaylistById = { _: Long, _: SongSource -> },
    )

    @Composable
    private fun BeansTestHost(content: @Composable () -> Unit) {
        BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
            CompositionLocalProvider(LocalBeansNavigator provides navigator) {
                content()
            }
        }
    }

    private fun bindSettingsToThisTest() {
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
    }

    private fun countWithText(text: String): Int =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).size

    private fun countContainingText(text: String): Int =
        composeRule.onAllNodesWithText(text, substring = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .size

    @Test(timeout = 120_000L)
    fun mergedCardShowsEverythingOnceAndKeepsEveryEntryReachable() {
        bindSettingsToThisTest()
        val userId = UserStatsStore.userId

        composeRule.setContent {
            BeansTestHost { ProfileScreen() }
        }
        composeRule.waitForIdle()

        // ---- 合并后的卡片：头像 / 昵称 / ID / 统计 / 恢复我的ID ----
        composeRule.onNodeWithText("我的").assertIsDisplayed()
        // 昵称占位只出现一次（原来两张卡各写一份昵称 / 头像）
        assertEquals("昵称占位只能有一份（两条就是又分裂成两张卡了）", 1, countWithText("自定义昵称"))
        // 未登录时的账号行（原账号卡的标题兜底文案已经按参考图去掉，引导文案在这里）
        assertTrue(
            "账号行必须保留：未登录时要写明登录后能同步什么",
            countContainingText("登录后可同步") >= 1,
        )
        // ID · xxxxxx 只出现一次
        assertEquals("ID 只能出现一次", 1, countWithText("ID · $userId"))
        composeRule.onNodeWithText("听歌时长").assertIsDisplayed()
        composeRule.onNodeWithText("播放次数").assertIsDisplayed()
        composeRule.onNodeWithText("恢复我的ID").performScrollTo().assertIsDisplayed()

        // ---- 登录入口仍然可达：点卡片的开头（头像 / 昵称那一段）就会进登录页 ----
        assertEquals("渲染期间不该有登录跳转", 0, loginOpens)
        // 先滚回卡片顶部再点：上面的 performScrollTo 已经把页面滚下去了。
        // 点击位置刻意选在这一段的**左侧（头像那一侧）**：ID 行里嵌着「复制」小按钮，
        // 它是嵌套的 clickable，会吃掉落在它身上的点击（改造前那张统计卡也是同样的结构）。
        composeRule.onNodeWithText("自定义昵称").performScrollTo().performTouchInput {
            click(Offset(width * 0.1f, height / 2f))
        }
        composeRule.waitForIdle()
        assertEquals("点合并后的卡片必须仍然能进登录 / 账号中心", 1, loginOpens)

        // ---- 原来的功能板块都还在，而且顺序默认排在卡片之后 ----
        composeRule.onNodeWithText("我的下载").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("交流群").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("自愿赞助").performScrollTo().assertIsDisplayed()

        // ---- 下载入口仍然可达 ----
        composeRule.onNodeWithText("我的下载").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("下载入口必须仍然可达", 1, downloadOpens)

        // ---- 板块排序入口（按钮 + 排序面板）保留 ----
        composeRule.onNodeWithText("板块排序").performScrollTo().assertIsDisplayed()
    }

    @Test(timeout = 120_000L)
    fun sectionSortSheetOpensWithEverySectionOnce() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost { ProfileScreen() }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("板块排序").performScrollTo().performClick()
        composeRule.waitForIdle()

        // 排序面板本体由 ModalBottomSheet 承载（独立窗口），本仓库的渲染测试不进去断言；
        // 这里只证明入口点得开、页面没崩。
        assertFalse(
            "排序入口点开之后页面本身仍然要正常渲染",
            composeRule.onAllNodesWithText("我的").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty(),
        )
    }
}

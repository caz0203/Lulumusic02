package com.lulu.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.BeansFloatingEffect
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.ui.BeansNavigator
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.components.BeansFloatingEffectOverlay
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.GlassBackdrop
import com.lulu.music.ui.screens.BeansSettingsScreen
import com.lulu.music.ui.screens.DiscoverHomeFlags
import com.lulu.music.ui.screens.DiscoverScreen
import com.lulu.music.ui.screens.DiscoverSection
import com.lulu.music.ui.screens.ProfileScreen
import com.lulu.music.ui.screens.beansSearchFieldTag
import com.lulu.music.ui.theme.BeansTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 新增设置项的**消费方**渲染测试：每一条都证明「这个开关真的被某处 UI 读到了」。
 *
 * ## 为什么消费方取值走**显式参数**而不是「写偏好 + 等 StateFlow」
 *
 * `SettingsStore` 的写是 `scope.launch { store.edit { … } }`，在 Robolectric 下这次写的完成时刻
 * 不可观测；更关键的是 `SettingsStore` 的 `by lazy` StateFlow 在本仓库的测试装置里**重置不可靠**
 * （详见 `RobolectricSingletons` 的「已知限制」）。所以这里沿用仓库既有约定 —— 与本工程到处传
 * `uiStyle` 一样 —— 把开关值作为参数传进屏幕：
 *  - `ui/BeansApp.kt` 负责从 `SettingsStore` 读偏好（那一行就是「设置 → 界面」的接线），
 *  - 屏幕只负责「值 → 渲染」，因此可以被确定性驱动。
 *
 * 也就是说本文件覆盖的是**渲染层**：给了 `false` 就一定不画、给了 `true` 就一定画。
 * 偏好本身能不能写进去由 `BackupManagerTest` / `LaunchSmokeTest` 覆盖（DataStore 往返）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class AppearanceConsumersRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val noopNavigator = BeansNavigator(
        openPlayer = {},
        openSettings = {},
        openLogin = {},
        openDownloads = {},
        openSources = {},
        openPlaylist = { _: Playlist -> },
        openPlaylistById = { _: Long, _: SongSource -> },
    )

    @Composable
    private fun BeansTestHost(content: @Composable () -> Unit) {
        BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
            CompositionLocalProvider(LocalBeansNavigator provides noopNavigator) {
                content()
            }
        }
    }

    private fun assertNoText(text: String) {
        assertFalse("「$text」不该出现在当前语义树里", composeRule.hasTextAnywhere(text))
    }

    private fun assertHasText(text: String) {
        assertTrue("「$text」必须出现在当前语义树里", composeRule.hasTextAnywhere(text))
    }

    // ------------------------------------------------------------------
    // 测试设施：每个 @Test 先重绑 DataStore（与本仓库其它渲染测试一致）
    // ------------------------------------------------------------------

    private fun bindSettingsToThisTest() {
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
    }

    // ------------------------------------------------------------------
    // A. 设置页骨架：搜索框 + 三张卡片的行
    // ------------------------------------------------------------------

    @Test(timeout = 120_000L)
    fun settingsScreenShowsTheNewGroupedSkeleton() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost { BeansSettingsScreen(onDismiss = {}) }
        }
        composeRule.waitForIdle()

        // 顶部搜索框
        composeRule.onNodeWithContentDescription(beansSearchFieldTag).assertIsDisplayed()

        // 卡片 1 / 2 / 3 的行
        listOf("账号登录", "主题模式", "平台显示").forEach { title ->
            composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        listOf("音源与音质", "播放设置", "均衡器", "备份与恢复").forEach { title ->
            composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        listOf("更新日志", "检查更新", "问题反馈", "免责声明", "崩溃日志", "运行环境").forEach { title ->
            composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        // 运行环境是键值区块（不是行）
        composeRule.onNodeWithText("设备").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("设备标识").performScrollTo().assertIsDisplayed()
    }

    /** 主题模式展开区里必须能看到这一批**新增**的项（它们全都有真实消费方）。 */
    @Test(timeout = 120_000L)
    fun appearanceSectionShowsEveryNewSetting() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost { BeansSettingsScreen(onDismiss = {}) }
        }
        composeRule.waitForIdle()

        listOf(
            "全局漂浮特效",
            "底栏显示文字",
            "底部栏显示",
            "底栏图标样式",
            "显示歌曲 VIP 图标",
            "低系统悬浮底栏",
            "全局 UI 样式",
            "液态容器颜色",
            "主页背景色",
            "上传壁纸（可多张）",
            "隐藏主页用户名",
            "隐藏所有界面排序按钮",
            "隐藏顶部平台列表",
            "隐藏主页刷新按钮",
            "每日推荐使用旧版样式",
            "隐藏自愿赞助",
        ).forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }

        // 四种 UI 样式必须都在分段控件里（参考图是四个，不是两个）。
        listOf("默认液态", "磨砂玻璃", "紧凑淡雅", "Apple 简洁").forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        // 底栏图标样式三种 / 漂浮特效三种
        listOf("Apple Music", "SF Symbols", "圆润样式").forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText("雪花").performScrollTo().assertIsDisplayed()
        // 悬浮底栏的 4 个滑块
        listOf("圆润度", "长度", "X 位置", "Y 位置").forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * 参考图（Image A）那一段的行序与「紧凑、没有裸 hex 输入框」的约定。
     *
     * 这一条是**反向**断言：Image B 里那个宽 `#RRGGBB` 输入框、以及 Image A 没有的
     * 「通用背景色」旧行，都必须真的不存在（而不是只是被挪到下面去了）。
     */
    @Test(timeout = 120_000L)
    fun appearanceSectionHasNoHexFieldAndNoLegacyBackgroundRow() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost { BeansSettingsScreen(onDismiss = {}) }
        }
        composeRule.waitForIdle()

        // 通用背景色：行删掉了，但数据没被晾在外面（恢复默认背景会清这个 key，见下一条测试）。
        assertNoText("通用背景色")
        assertNoText("旧版单一背景色")

        // 裸的 hex 输入框必须一个都不剩：占位符与它渲染出来的值都不能出现。
        assertNoText("#RRGGBB")
        assertTrue(
            "外观区不该再有可编辑的 hex 文本框",
            composeRule.onAllNodesWithText("#RRGGBB", substring = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )

        // 参考图里那一行紧凑文字动作必须在（不是被删了）。
        composeRule.onNodeWithText("恢复默认背景").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("当前：默认背景").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("上传壁纸（可多张）").performScrollTo().assertIsDisplayed()
        // ＋ 入口的语义标签（上传壁纸的 `＋`）。
        composeRule.onNodeWithContentDescription("上传壁纸").performScrollTo().assertIsDisplayed()
    }

    /** 搜索：中文命中 → 只留命中的行，其它卡片整行消失。 */
    @Test(timeout = 120_000L)
    fun searchFiltersRowsAndAutoExpandsTheHit() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost { BeansSettingsScreen(onDismiss = {}) }
        }
        composeRule.waitForIdle()

        // 命中项埋在主题模式展开区的很下面；用前缀搜索，避免和搜索框自身的文字撞上。
        composeRule.onNodeWithContentDescription(beansSearchFieldTag).performTextInput("隐藏主页")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("隐藏主页用户名").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("隐藏主页刷新按钮").performScrollTo().assertIsDisplayed()
        // 同一张卡片里没命中的子项被过滤掉
        assertNoText("隐藏自愿赞助")
        // 其它卡片整行消失
        assertNoText("播放设置")
        assertNoText("备份与恢复")
        // 同一张卡片里没命中的行也应被过滤掉
        assertNoText("账号登录")
    }

    /** 搜索：英文命中中文界面（索引里中英都登记）。 */
    @Test(timeout = 120_000L)
    fun searchMatchesEnglishTitlesToo() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost { BeansSettingsScreen(onDismiss = {}) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(beansSearchFieldTag).performTextInput("Hide home")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("隐藏主页用户名").performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------------
    // B. 开关类设置的消费方（显式参数驱动）
    // ------------------------------------------------------------------

    /** 「显示歌曲 VIP 图标」→ `BeansVIPBadge`（全 App 唯一的 VIP 小胶囊实现）。 */
    @Test(timeout = 120_000L)
    fun vipBadgeDisappearsWhenTheSwitchIsOff() {
        bindSettingsToThisTest()

        var visible by mutableStateOf(true)
        composeRule.setContent {
            BeansTestHost { BeansVIPBadge("VIP", visibleOverride = visible) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("VIP").assertIsDisplayed()

        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        assertNoText("VIP")
    }

    /** 「隐藏自愿赞助」→ 「我的」页的赞助卡片不再组合。 */
    @Test(timeout = 120_000L)
    fun hideDonationRemovesTheSponsorshipCard() {
        bindSettingsToThisTest()

        var hide by mutableStateOf(false)
        composeRule.setContent {
            BeansTestHost { ProfileScreen(hideDonation = hide) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("我的").assertIsDisplayed()
        composeRule.onNodeWithText("自愿赞助").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { hide = true }
        composeRule.waitForIdle()
        assertNoText("自愿赞助")
        // 交流群卡片不受这个开关影响
        assertHasText("交流群")
    }

    /** 「隐藏所有界面排序按钮」→ 「我的」页的板块排序入口不再组合。 */
    @Test(timeout = 120_000L)
    fun hideSortButtonsRemovesTheProfileSortEntry() {
        bindSettingsToThisTest()

        var hide by mutableStateOf(false)
        composeRule.setContent {
            BeansTestHost { ProfileScreen(hideSortButtons = hide) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("板块排序").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { hide = true }
        composeRule.waitForIdle()
        assertNoText("板块排序")
    }

    /** 「隐藏主页用户名」→ 只去掉昵称行，问候语保留。 */
    @Test(timeout = 120_000L)
    fun hideHomeNicknameRemovesOnlyTheGreetingSubtitle() {
        bindSettingsToThisTest()

        var flags by mutableStateOf(DiscoverHomeFlags())
        composeRule.setContent {
            BeansTestHost { DiscoverScreen(DiscoverSection.HOME, homeFlags = flags) }
        }
        composeRule.waitForIdle()
        // 测试环境下 AuthStore 没有昵称，昵称行的兜底文案就是「发现好音乐」。
        assertHasText("发现好音乐")

        composeRule.runOnIdle { flags = flags.copy(nickname = false) }
        composeRule.waitForIdle()
        assertNoText("发现好音乐")
        assertTrue(
            "问候语本身必须保留（只隐藏昵称）",
            composeRule.hasTextAnywhere("早上好") ||
                composeRule.hasTextAnywhere("下午好") ||
                composeRule.hasTextAnywhere("晚上好"),
        )
    }

    /** 「隐藏主页刷新按钮」→ 问候区右侧那个圆形刷新按钮不再组合。 */
    @Test(timeout = 120_000L)
    fun hideHomeRefreshRemovesTheRefreshButton() {
        bindSettingsToThisTest()

        var flags by mutableStateOf(DiscoverHomeFlags())
        composeRule.setContent {
            BeansTestHost { DiscoverScreen(DiscoverSection.HOME, homeFlags = flags) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("刷新").assertIsDisplayed()

        composeRule.runOnIdle { flags = flags.copy(refreshButton = false) }
        composeRule.waitForIdle()
        assertTrue(
            "刷新按钮必须消失",
            composeRule.onAllNodesWithContentDescription("刷新")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
    }

    /** 「隐藏顶部平台列表」→ 平台切换条整段不再组合。 */
    @Test(timeout = 120_000L)
    fun hideProviderStripRemovesThePlatformPicker() {
        bindSettingsToThisTest()

        var flags by mutableStateOf(DiscoverHomeFlags())
        composeRule.setContent {
            BeansTestHost { DiscoverScreen(DiscoverSection.FEATURED, homeFlags = flags) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("网易云音乐").assertIsDisplayed()
        composeRule.onNodeWithText("QQ音乐").assertIsDisplayed()
        composeRule.onNodeWithText("酷狗音乐").assertIsDisplayed()

        composeRule.runOnIdle { flags = flags.copy(providerStrip = false) }
        composeRule.waitForIdle()
        assertNoText("网易云音乐")
        assertNoText("QQ音乐")
        assertNoText("酷狗音乐")
    }

    /** 「全局漂浮特效」→ 全局覆盖层：关闭时不画，打开时铺满并带语义标签。 */
    @Test(timeout = 120_000L)
    fun floatingEffectOverlayRendersOnlyWhenEnabled() {
        var effect by mutableStateOf(BeansFloatingEffect.OFF)

        composeRule.setContent {
            BeansTestHost {
                Box(modifier = Modifier.fillMaxSize()) {
                    BeansFloatingEffectOverlay(effect = effect)
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue(
            "关闭时不该有任何漂浮层",
            composeRule.onAllNodesWithContentDescription("漂浮特效")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )

        composeRule.runOnIdle { effect = BeansFloatingEffect.SNOW }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("漂浮特效").assertIsDisplayed()
    }

    // ------------------------------------------------------------------
    // C. 四种 UI 样式都能真的画出来（不是只多两个 chip）
    // ------------------------------------------------------------------

    @Test(timeout = 120_000L)
    fun everyUiStyleRendersItsBackdropAndGlassWithoutCrashing() {
        var style by mutableStateOf(BeansUIStyle.LIQUID)

        composeRule.setContent {
            BeansTestHost {
                GlassBackdrop(
                    modifier = Modifier.fillMaxSize(),
                    customColor = null,
                    style = style,
                    ambientGlow = true,
                ) {
                    BeansGlass(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(18),
                        style = style,
                    ) {
                        // 内容留空：这一条只证明四种样式都能组合出自己的背景 / 玻璃分支
                    }
                }
            }
        }
        composeRule.waitForIdle()

        BeansUIStyle.entries.forEach { candidate ->
            composeRule.runOnIdle { style = candidate }
            composeRule.waitForIdle()
        }
    }

    /** 「液态容器颜色」在偏好非空时仍然能画出整个设置页（Plumbing 生效、没有崩）。 */
    @Test(timeout = 120_000L)
    fun liquidTintPreferenceDoesNotBreakTheSettingsScreen() {
        bindSettingsToThisTest()
        // 只验证「读这个偏好不会让页面崩」——颜色差异本身由 AppearanceSettingsTest
        // （beansGlassFill 的纯函数断言）覆盖，渲染测试读不到像素，不假装能读。
        com.lulu.music.data.prefs.SettingsStore.setLiquidTintHex("#33FFFFFF")

        composeRule.setContent {
            BeansTestHost { BeansSettingsScreen(onDismiss = {}) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("液态容器颜色").performScrollTo().assertIsDisplayed()
    }
}

/**
 * 某段文案在当前语义树里出现了吗 —— **永不抛异常**的版本（与 `ComposeScreensRenderTest` 一致）。
 */
private fun ComposeContentTestRule.hasTextAnywhere(text: String): Boolean =
    onAllNodes(hasText(text, substring = true))
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .isNotEmpty()

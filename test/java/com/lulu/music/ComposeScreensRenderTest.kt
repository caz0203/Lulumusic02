package com.lulu.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.net.BeansApiException
import com.lulu.music.data.net.Http
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.BeansNavigator
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.screens.BeansPlayerScreen
import com.lulu.music.ui.screens.BeansSettingsScreen
import com.lulu.music.ui.screens.DiscoverScreen
import com.lulu.music.ui.screens.DiscoverSection
import com.lulu.music.ui.screens.PlaylistDetailScreen
import com.lulu.music.ui.screens.ProfileScreen
import com.lulu.music.ui.screens.discoverLayoutFor
import com.lulu.music.ui.theme.BeansTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 主界面的 **JVM 渲染测试**：把「进页面就崩」这类缺陷从「用户装包才发现」变成
 * 「`testDebugUnitTest` 里直接红」。
 *
 * 覆盖的是五个标签页背后的屏幕 / 分区：主页（[DiscoverSection.HOME]）、精选
 * （[DiscoverSection.FEATURED]）、设置页、播放页、「我的」、歌单详情页。
 * 歌单标签只是复用 `LibraryScreen`，不在这里重复覆盖。
 *
 * ## 这个文件能证明什么
 *
 * 每个用例真的在 Robolectric 里跑一次 `setContent` + 布局/组合，因此能抓住：
 *  - 组合期异常（`requireNotNull` / 越界 / 空集合取下标 / 非法状态转换）；
 *  - 缺少 `CompositionLocal`（例如忘了提供 `LocalBeansNavigator`，`current` 会直接抛错）；
 *  - `LaunchedEffect` 里同步抛出的异常（Compose 测试框架会抽干 Robolectric 的主 Looper）。
 *
 * ## 它**不能**证明什么（诚实说明）
 *
 * 不测点击后的业务流程、不测真实数据渲染、不测播放。断言只到「页面组合出来了、首屏关键
 * 文案在」这一层；[BeansPlayerScreen] 的两种状态都是**没有 `MediaController`** 时渲染的
 * （Robolectric 里没有 `MediaSessionService`，真正的播放态无法构造），所以它证明的是
 * 「空状态 / pending-play 状态画得出来」，不是「歌真的能播」。
 * [PlaylistDetailScreen] 同理：只覆盖「离线失败 → 错误态」，不覆盖有数据的列表渲染。
 *
 * ## 三个必须同时成立的测试设施
 *
 * 1. **不连播放服务**：[TestBeansApplication] 把 `PlaybackController.connectEnabled` 置 false。
 *    否则创建 Compose 规则的 idling 策略会抽干主 Looper，跑起 `MediaController.Builder(...)
 *    .buildAsync()`；Robolectric 的假 `bindService` 让 media3 在 Espresso 投递的 runnable 里抛
 *    NPE（`SessionServiceConnection.onServiceConnected` 拿到的 `ComponentName` 是 null）——
 *    那个异常测试代码拦不住，整个用例直接死。
 * 2. **不联网**：同一个 Application 把 `Http.offlineMode` 置 true。[PlaylistDetailScreen] 的首个
 *    `LaunchedEffect` 会调 `NetEaseApi.playlistTracks`（以及 QQ / 酷狗的同名入口），
 *    `ProfileScreen` 会调 `AuthStore.refreshAccount` → `NetEaseApi.account()`（**没有超时包装**，
 *    真联网就会把 `waitForIdle` 挂死）。闸门在底层请求入口抛 `BeansApiException.Network`，
 *    各调用方本来就把它当「断网」处理，于是页面退化成空态而不是崩掉。
 * 3. **不依赖偏好**：主题固定 `BeansThemeMode.DARK` + 固定强调色，不读 `SettingsStore.themeMode`；
 *    每个用例开头把 `SettingsStore` 重绑到当前测试方法的 filesDir（原因见 [RobolectricSingletons]）。
 *
 * ## 有界超时
 *
 * 每个用例都有 `timeout`，`waitUntil` 还有自己的 `timeoutMillis`。即使将来某个屏幕真的挂住
 * （正在等的网络、永远不会发生的状态迁移），失败的是**这一个用例**，不会挂死构建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class ComposeScreensRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 测试用导航器：全部 no-op。真实实现是 `navController.navigate(...)`，
     * 渲染测试里没有 `NavHost`，所以这里只验证「屏幕拿得到导航器」。
     */
    private val noopNavigator = BeansNavigator(
        openPlayer = {},
        openSettings = {},
        openLogin = {},
        openDownloads = {},
        openSources = {},
        openPlaylist = { _: Playlist -> },
        openPlaylistById = { _: Long, _: SongSource -> },
    )

    // ------------------------------------------------------------------

    /**
     * 两道闸门的「金丝雀」：如果 [TestBeansApplication] 没生效（`@Config` 写错、谁改了
     * seam 的默认值、Robolectric 换了个 Application），后面所有渲染用例都会以一条
     * **难以诊断**的 media3 NPE 或挂死告终。这条用例把那种情况变成一眼能看懂的失败。
     */
    @Test(timeout = 60_000L)
    fun testSeamsAreActiveBeforeAnyScreenComposes() {
        bindSettingsToThisTest()
        assertFalse(
            "PlaybackController.connectEnabled 必须被 TestBeansApplication 置为 false，否则渲染测试会死于 media3 NPE",
            PlaybackController.connectEnabled,
        )
        assertThrows(
            "Http.offlineMode 必须为 true：渲染测试绝不允许联网",
            BeansApiException.Network::class.java,
        ) {
            Http.guardNetwork()
        }
        assertTrue("两道闸门都必须在任何屏幕组合之前生效", true)
    }

    // ------------------------------------------------------------------

    /** 设置页（[BeansSettingsScreen]）：所有屏幕里最大的一个，分组 / 滑块 / 对话框都在这里。 */
    @Test(timeout = 60_000L)
    fun settingsScreenComposes() {
        bindSettingsToThisTest()
        var dismissed = 0

        composeRule.setContent {
            BeansTestHost {
                BeansSettingsScreen(onDismiss = { dismissed++ })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("设置").assertIsDisplayed()
        // 顶栏关闭按钮是文字「完成」：点它必须真的把 onDismiss 回调出去。
        composeRule.onNodeWithText("完成").performClick()
        composeRule.waitForIdle()
        assertTrue("顶栏「完成」必须触发 onDismiss，实际调用 $dismissed 次", dismissed > 0)
    }

    // ------------------------------------------------------------------

    /**
     * 播放页（[BeansPlayerScreen]）在**空状态**下渲染（没有 currentSong）。
     *
     * 这是「冷启动直接进播放页 / 队列被清空后再进播放页」的真实路径，也是空指针最容易出现的
     * 地方；`PlayerTopBar` 对 null 有明确兜底文案，这条断言同时把那条兜底钉住。
     */
    @Test(timeout = 60_000L)
    fun playerScreenComposesInItsEmptyState() {
        bindSettingsToThisTest()
        PlaybackController.clearQueue()

        composeRule.setContent {
            BeansTestHost {
                BeansPlayerScreen(onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("未在播放").assertIsDisplayed()
    }

    /**
     * 播放页在**有歌**的状态下渲染 —— 比空状态更强的覆盖。
     *
     * `PlaybackController` 是进程级单例，测试里没有 `MediaController`，所以这里走的是
     * 「pending play」路径（[PlaybackController.play] 在没有 controller 时会**同步**把
     * `currentSong` / `queue` 填好，好让 UI 立刻有东西可画）。这正是用户点歌后进播放页时
     * 的那一帧：真正的歌名 / 歌手必须出现在顶栏。
     *
     * 顺带覆盖随 `currentSong` 变化的两个 `LaunchedEffect`（歌词、评论数）：它们都走
     * `Dispatchers.IO` + 离线闸门，必须**有界失败**而不是挂住 `waitForIdle`。
     */
    @Test(timeout = 60_000L)
    fun playerScreenComposesWithACurrentSong() {
        bindSettingsToThisTest()
        PlaybackController.clearQueue()
        PlaybackController.play(
            listOf(
                Song(
                    id = 88_001L,
                    name = "渲染测试歌曲",
                    artists = "渲染测试歌手",
                    album = "渲染测试专辑",
                    duration = 215.0,
                    source = SongSource.KUGOU,
                    kugouHash = "RENDER-TEST-HASH",
                ),
            ),
            startIndex = 0,
        )
        composeRule.waitForIdle()

        composeRule.setContent {
            BeansTestHost {
                BeansPlayerScreen(onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        // 顶栏（`PlayerTopBar`）在有歌时直接画歌名 / 歌手；这同时证明「进页面时 currentSong 已就绪」
        // 那一帧不会崩。
        composeRule.onNodeWithText("渲染测试歌曲").assertIsDisplayed()
        composeRule.onNodeWithText("渲染测试歌手").assertIsDisplayed()
        // 真实覆盖度说明：随 currentSong 变的两个 LaunchedEffect（歌词、评论数）也会在这条路径上
        // 跑起来 —— 它们走 Dispatchers.IO + 离线闸门，必须**有界失败**。它们的状态不影响这里的
        // 断言（歌词区默认不展开），所以上面那两次 waitForIdle 已经足够；若它们挂住，
        // waitForIdle 会撞上本用例的 timeout 而不是静默通过。
    }

    // ------------------------------------------------------------------

    /**
     * 参考图把播放页的操作精简到**底排 5 个控件**（播放模式 / 上一首 / 播放暂停 / 下一首 / 播放列表），
     * 其余入口（收藏 / 下载 / 倍速 / 均衡器 / 定时关闭 / 布局 / 歌词 / 识曲 / 百科 /
     * 添加到本地歌单 / 更换自定义封面 / 播放器设置）全部收进右上角 `···`。
     *
     * 最左那颗由「随机」升级成**合并后的播放模式按钮**（顺序 / 列表 / 单曲 / 随机 四档循环，
     * 见 [com.lulu.music.playback.PlayMode]），它的语义标签因此是「播放模式」；
     * 档位放在 `stateDescription` 里，由 `PlayerPlayModeRenderTest` 断言。
     *
     * 这条用例钉住两件事，且都**不依赖网络**：
     *  1. 5 个控件必须真的画在屏上（`assertIsDisplayed`：连 470dp 高的测试屏也放得下）；
     *  2. 改版前常驻在底排的次要图标与循环按钮，在 `···` 面板**未打开**时不能再出现在播放页上
     *     —— 它们只存在于面板里，所以这里必须一个节点都找不到。
     *
     * 面板内部那 12 行不在这里断言：它们被 `ModalBottomSheet` 的独立窗口承载，
     * 要打开面板再断言的行为属于交互测试，超出「进页面就崩」这一层覆盖。
     */
    @Test(timeout = 60_000L)
    fun playerScreenKeepsExactlyTheFiveBottomControls() {
        bindSettingsToThisTest()
        PlaybackController.clearQueue()
        PlaybackController.play(
            listOf(
                Song(
                    id = 88_002L,
                    name = "底排控件测试",
                    artists = "底排控件歌手",
                    album = "底排控件专辑",
                    duration = 180.0,
                    source = SongSource.KUGOU,
                    kugouHash = "BOTTOM-ROW-HASH",
                ),
            ),
            startIndex = 0,
        )
        composeRule.waitForIdle()

        composeRule.setContent {
            BeansTestHost {
                BeansPlayerScreen(onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        listOf("播放模式", "上一首", "播放/暂停", "下一首", "播放列表").forEach { label ->
            composeRule.onNodeWithContentDescription(label).assertIsDisplayed()
        }

        listOf("循环模式", "均衡器", "歌词", "下载", "歌曲百科").forEach { label ->
            assertTrue(
                "「$label」不该再常驻在播放页上（只应存在于未打开的 `···` 面板里）",
                composeRule.onAllNodesWithContentDescription(label)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------

    /**
     * 歌单详情页（[PlaylistDetailScreen]）。
     *
     * 用 `playlistId = 0L` + 网易云来源：离线闸门让 `NetEaseApi.playlistTracks` 抛
     * `BeansApiException.Network`，页面必须**不崩、不永远转圈**，而是落到它自己的错误态
     * （没有曲目可展示时报错 + 重试按钮）。
     *
     * 实测（语义树，见本用例的断言）：页面渲染出的是
     * `BeansErrorState` —— 文案就是离线闸门抛出的那句 `BeansApiException.Network` 的 message，
     * 外加一个「重试」按钮。这证明那条 `LaunchedEffect` **已经跑完**并走了 `onFailure` 分支；
     * 过去最容易挂住的点（没有超时包装的网络调用）现在有界失败。
     *
     * 不断言具体错误文案：那句话来自 `Http.guardNetwork()`，属于测试设施而不是界面契约；
     * 界面的契约是「错误态 + 重试」。
     */
    @Test(timeout = 60_000L)
    fun playlistDetailScreenComposesAndSettlesOffline() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost {
                PlaylistDetailScreen(
                    playlistId = 0L,
                    source = SongSource.NET_EASE,
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 有界等待：加载态 → 错误态。等不到由 waitUntil 自己失败，不会挂死构建。
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.hasTextAnywhere("重试")
        }
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        // 顶栏必须在：说明即使数据层全挂，页面骨架仍然画得出来（实测语义树里它在最前面）。
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    // ------------------------------------------------------------------

    /**
     * 「我的」标签页（[ProfileScreen]）：进页面即刷新账号，是联网面最大的一个主页面。
     *
     * 设置入口只剩标题栏右上角的齿轮，因此这里同时钉住「外观与设置」卡片**不存在** ——
     * 参考设计里没有它，加回来就是回归。
     */
    @Test(timeout = 60_000L)
    fun profileScreenComposes() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost {
                ProfileScreen()
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("我的").assertIsDisplayed()
        // 大统计卡片里的固定标签，证明首屏内容真的画出来了（不是只有一个空壳）。
        composeRule.onNodeWithText("听歌时长").assertIsDisplayed()
        // 已下载入口必须仍然可达（参考设计要求保留）。这一行在首屏下方，外层是
        // `verticalScroll` 的 Column，所以要 `performScrollTo` 才能断言「可见」——
        // 顺带证明它真的在页面上，而不是只存在于语义树里。
        composeRule.onNodeWithText("我的下载").performScrollTo().assertIsDisplayed()
        // 冗余的「外观与设置」卡片已删除：设置只能从齿轮进。
        assertFalse(
            "「外观与设置」卡片必须已被移除（设置入口只保留右上角的齿轮）",
            composeRule.hasTextAnywhere("外观与设置"),
        )
    }

    // ------------------------------------------------------------------

    /**
     * 主页标签页（[DiscoverScreen] 的 [DiscoverSection.HOME] 分区）：问候区 + 排行榜 + 歌单广场入口。
     *
     * 这一条**不依赖网络**：离线闸门让 `load()` 走失败分支（页面退化成错误态 + 重试），
     * 头部与平台切换仍然渲染。同时钉住精选的每日推荐区块（标题「推荐」+ 卡片「每日推荐」）
     * **整段不在主页上** —— 这是这次 4 标签 → 5 标签拆分里最容易回退的地方。那两块内容在
     * 主页上根本不存在，与是否有网无关，所以这条断言是有效的。
     */
    @Test(timeout = 60_000L)
    fun discoverHomeSectionComposesAndKeepsFeaturedCardsOut() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost {
                DiscoverScreen(DiscoverSection.HOME)
            }
        }
        composeRule.waitForIdle()

        assertTrue(
            "主页必须画出问候语（早上好 / 下午好 / 晚上好）",
            composeRule.hasTextAnywhere("早上好") ||
                composeRule.hasTextAnywhere("下午好") ||
                composeRule.hasTextAnywhere("晚上好"),
        )
        assertFalse(
            "心动模式属于精选分区，不应出现在主页",
            composeRule.hasTextAnywhere("心动模式"),
        )
        assertFalse(
            "私人漫游属于精选分区，不应出现在主页",
            composeRule.hasTextAnywhere("私人漫游"),
        )
        assertFalse(
            "每日推荐属于精选分区，不应出现在主页",
            composeRule.hasTextAnywhere("每日推荐"),
        )
    }

    /**
     * 精选标签页（[DiscoverScreen] 的 [DiscoverSection.FEATURED] 分区）：画面骨架能组合出来。
     *
     * 这一条**不能**断言精选卡片（每日推荐 / 私人漫游 / 心动模式）：它们在 `当 load() 成功`
     * 的那个分支里，而测试环境的 `Http.offlineMode = true` 会让网易云快照抛
     * `BeansApiException.Network`（实测语义树里就是「网络连接失败，请检查网络」+「重试」），
     * 页面停在错误态 —— 没有网络就没有那块内容，和分区对不对无关。
     *
     * 所以这里只钉住「无网也有骨架」：问候语 + 三个平台切换 + 错误态重试按钮。这几项在
     * `load()` 的任何结果下都会渲染，是这条用例能诚实覆盖的部分。精选 / 主页的**分区差异**
     * 由上面那条主页用例的「整段缺席」断言证明（那段内容在主页上不存在，与是否有网无关）。
     */
    @Test(timeout = 60_000L)
    fun discoverFeaturedSectionComposesItsSkeletonOffline() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost {
                DiscoverScreen(DiscoverSection.FEATURED)
            }
        }
        composeRule.waitForIdle()

        assertTrue(
            "精选页必须画出问候语（早上好 / 下午好 / 晚上好）",
            composeRule.hasTextAnywhere("早上好") ||
                composeRule.hasTextAnywhere("下午好") ||
                composeRule.hasTextAnywhere("晚上好"),
        )
        // 平台切换是 Discover 的公共前导区：三个平台都在，说明这一屏真的画出来了。
        composeRule.onNodeWithText("网易云音乐").assertIsDisplayed()
        composeRule.onNodeWithText("QQ音乐").assertIsDisplayed()
        composeRule.onNodeWithText("酷狗音乐").assertIsDisplayed()
        // 离线闸门让首个 load() 失败 —— 必须落到有界错误态而不是永远转圈。
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.hasTextAnywhere("重试")
        }
        composeRule.onNodeWithText("重试").assertIsDisplayed()
    }

    // ------------------------------------------------------------------

    /**
     * 分区规则本身（[discoverLayoutFor]）—— **不依赖网络、不依赖渲染**的硬断言。
     *
     * 为什么必须有这一组：渲染测试跑在 `Http.offlineMode = true` 下，`DiscoverScreen` 会停在
     * 「网络连接失败」错误态，内容分支不进组合。也就是说「每日推荐 / 私人漫游 / 心动模式 有没有
     * 跑到主页上」在渲染测试里**永远看不出来**（主页上没网时本来就没有那块内容）。
     * 这一组直接钉住那块逻辑，`section == FEATURED` 一旦被删掉就会红。
     */
    @Test(timeout = 60_000L)
    fun discoverDailyBlockOnlyBelongsToTheFeaturedSection() {
        val featured = discoverLayoutFor(
            section = DiscoverSection.FEATURED,
            hasDailyContent = true,
            hasRankContent = true,
            hasPlaylists = true,
        )
        val home = discoverLayoutFor(
            section = DiscoverSection.HOME,
            hasDailyContent = true,
            hasRankContent = true,
            hasPlaylists = true,
        )

        assertTrue("精选必须渲染每日推荐 / 私人漫游 / 心动模式区块", featured.showsDaily)
        assertFalse("主页绝不能渲染每日推荐 / 私人漫游 / 心动模式区块", home.showsDaily)
        assertEquals("两个分区除了每日推荐区块，其它板块都必须一致", featured.copy(showsDaily = false), home)
    }

    /**
     * 排行榜与歌单广场两个分区都要（主页靠它们撑内容，精选在推荐区块下面接着显示），
     * 但各自的**显示条件**与拆分前的内联条件逐字一致。
     */
    @Test(timeout = 60_000L)
    fun discoverRanksAndPlaylistSquareAreSharedByBothSections() {
        DiscoverSection.entries.forEach { section ->
            val withData = discoverLayoutFor(section, true, true, true)
            assertTrue("$section：有榜单数据时必须渲染排行榜", withData.showsRanks)
            assertTrue("$section：有歌单时必须渲染歌单广场", withData.showsPlaylistSquare)

            val withoutData = discoverLayoutFor(section, false, false, false)
            assertFalse("$section：没数据时不该渲染排行榜", withoutData.showsRanks)
            assertFalse("$section：没数据时不该渲染歌单广场", withoutData.showsPlaylistSquare)
        }
    }

    // ------------------------------------------------------------------
    // 测试设施
    // ------------------------------------------------------------------

    /**
     * 把 `SettingsStore` 重绑到**当前**测试方法的 filesDir，并把常用偏好打回未初始化
     * （原因见 [RobolectricSingletons]）。`ApplicationProvider` 同时确保
     * [TestBeansApplication.onCreate]（以及其中的两道闸门）已经跑过。
     */
    private fun bindSettingsToThisTest() {
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
    }

    /** 统一的宿主：固定深色主题 + 测试导航器，等价于 `BeansApp` 给屏幕准备的那层环境。 */
    @Composable
    private fun BeansTestHost(content: @Composable () -> Unit) {
        BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
            CompositionLocalProvider(LocalBeansNavigator provides noopNavigator) {
                content()
            }
        }
    }
}

/**
 * 某段文案在当前语义树里出现了吗 —— **永不抛异常**的版本，专门给 `waitUntil` 的谓词用。
 *
 * `onNodeWithText(...)` 找不到节点会直接 `AssertionError`；`onAllNodes(hasText(...))` 则是
 * 「数一下匹配数量」，空集合返回 0 条，适合当作轮询谓词。
 */
private fun ComposeContentTestRule.hasTextAnywhere(text: String): Boolean =
    onAllNodes(hasText(text, substring = true))
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .isNotEmpty()

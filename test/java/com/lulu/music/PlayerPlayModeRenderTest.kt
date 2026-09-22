package com.lulu.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.playback.PlayMode
import com.lulu.music.playback.PlaybackController
import com.lulu.music.playback.RepeatMode
import com.lulu.music.ui.BeansNavigator
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.screens.BeansPlayerScreen
import com.lulu.music.ui.screens.playerTransportRowTag
import com.lulu.music.ui.theme.BeansTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放页底排那颗**合并后的播放模式按钮**的渲染 / 交互测试。
 *
 * 它钉住三件只靠纯逻辑测试证明不了的事：
 *  1. 底排**正好** 5 个控件（不是「至少 5 个」—— 只断言那 5 个都在，防不住再加第 6 个）；
 *  2. 按钮的语义标签是稳定的「播放模式」，当前档位落在 `stateDescription` 上
 *     （读屏会读成「播放模式，单曲循环」），并且点一下真的换档、点满四档回到起点；
 *  3. 点击驱动的是 [PlaybackController] 的真实状态（不是只换了个图标）。
 *
 * 不在这里断言的：图标资源与颜色（渲染测试读不到像素）。四种档位的图标由 [PlayMode] 的
 * `when` 分支唯一决定，档位→图标的映射属于编译期穷尽的分支，另有 `PlayModeCycleTest` 钉住档位本身。
 *
 * `···` 面板内部不在覆盖范围内：它由 `ModalBottomSheet` 的独立窗口承载，本仓库的渲染测试
 * 一律不进去断言（见 `SongActionSheetRenderTest` 的说明）。「循环模式」那一行已经从面板源码里删除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class PlayerPlayModeRenderTest {

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

    @Before
    fun bootApplication() {
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
        // 每个用例从「顺序播放」开始：PlaybackController 是进程级单例，上一组用例的值会留下来。
        PlaybackController.setShuffle(false)
        PlaybackController.setRepeatMode(RepeatMode.OFF)
    }

    /** 播一首本地构造的歌并渲染播放页（不碰网络）。 */
    private fun renderPlayer() {
        PlaybackController.clearQueue()
        PlaybackController.play(
            listOf(
                Song(
                    id = 88_101L,
                    name = "播放模式测试",
                    artists = "播放模式歌手",
                    album = "播放模式专辑",
                    duration = 180.0,
                    source = SongSource.KUGOU,
                    kugouHash = "PLAY-MODE-HASH",
                ),
            ),
            startIndex = 0,
        )
        composeRule.waitForIdle()

        composeRule.setContent {
            BeansTestHost { BeansPlayerScreen(onDismiss = {}) }
        }
        composeRule.waitForIdle()
    }

    private fun assertMode(label: String) {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("播放模式")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
    }

    private fun clickPlayMode() {
        composeRule.onNodeWithContentDescription("播放模式").performClick()
        composeRule.waitForIdle()
    }

    @Test(timeout = 60_000L)
    fun bottomRowStillHasExactlyFiveControls() {
        renderPlayer()

        composeRule.onNodeWithTag(playerTransportRowTag)
            .onChildren()
            .assertCountEquals(5)
    }

    @Test(timeout = 60_000L)
    fun playModeButtonCyclesAllFourStatesAndWraps() {
        renderPlayer()

        // 起点：DataStore 里没有随机也没有循环 → 顺序播放。
        assertMode("顺序播放")

        clickPlayMode()
        assertMode("列表循环")
        assertEquals(
            "点击必须驱动真实的 repeatMode（不是只换图标）",
            RepeatMode.ALL,
            PlaybackController.repeatMode.value,
        )

        clickPlayMode()
        assertMode("单曲循环")
        assertEquals(RepeatMode.ONE, PlaybackController.repeatMode.value)

        clickPlayMode()
        assertMode("随机播放")
        assertEquals(RepeatMode.ALL, PlaybackController.repeatMode.value)
        assertEquals("随机播放必须真的打开 shuffle", true, PlaybackController.shuffle.value)

        // 第 4 档之后绕回第 1 档，并且两个状态一起复位。
        clickPlayMode()
        assertMode("顺序播放")
        assertEquals(RepeatMode.OFF, PlaybackController.repeatMode.value)
        assertEquals(false, PlaybackController.shuffle.value)
    }

    @Test(timeout = 60_000L)
    fun buttonShowsTheStateEvenWhenItWasChangedOutsideTheButton() {
        // 模拟「别处改了状态」：直接写 controller，不经过按钮。
        PlaybackController.setShuffle(true)
        PlaybackController.setRepeatMode(RepeatMode.ALL)
        renderPlayer()

        assertMode("随机播放")

        // 反向：偏好被改成单曲循环（例：备份导入）后，按钮必须显示单曲循环。
        PlaybackController.setShuffle(false)
        PlaybackController.setRepeatMode(RepeatMode.ONE)
        composeRule.waitForIdle()
        assertMode("单曲循环")
    }

    @Test(timeout = 60_000L)
    fun everyCycleStepKeepsTheRowAtFiveControls() {
        renderPlayer()

        // 图标是 Crossfade 出来的：换档过程中底排也不能多出节点（否则会挤动布局）。
        PlayMode.entries.forEach { _ ->
            composeRule.onNodeWithTag(playerTransportRowTag)
                .onChildren()
                .assertCountEquals(5)
            clickPlayMode()
        }
    }
}

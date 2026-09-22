package com.lulu.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.LyricLine
import com.lulu.music.data.model.LyricWord
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.ui.screens.LyricsSection
import com.lulu.music.ui.screens.PlayerLyricPreview
import com.lulu.music.ui.screens.lyricSweepTag
import com.lulu.music.ui.theme.BeansTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放页两个歌词显示面的**渲染测试**：三行预览（`PlayerLyricPreview`）与整屏歌词
 * （[LyricsSection]）在「当前行有真逐字数据」时真的多画了一层扫过，在没有逐字数据时
 * 一点都不多画。
 *
 * ## 为什么要直接组合这两块，而不是渲染整个播放页
 *
 * 播放页的歌词来自网络（[com.lulu.music.data.api.LyricService]），Robolectric 里网络被
 * `Http.offlineMode` 挡着，歌词永远是空态 —— 那样写出来的「渲染测试」只能证明空态画得出来，
 * 证明不了扫过。这里直接喂**本地构造**的歌词给这两块 UI：没有伪造网络，也没有伪造歌词
 * 时间轴（词时间就是 [LyricWord] 的真实字段），只是跳过了拉取这一步。
 *
 * `PlayerLyricPreview` 因此从 `private` 放宽到 `internal`（唯一原因就是这个测试）。
 *
 * ## 断言用的是什么
 *
 * 扫过那一层带 [lyricSweepTag]，而整行高亮（今天的样子）不带。所以
 * 「这一行在扫吗」是一个可以数出来的事实，而不是靠读像素猜的 —— 这个仓库的渲染测试读不到像素。
 * 另外两条：当前行的翻译仍然整行渲染；逐字层不会被读屏多读一遍（未合并语义树里同一行文本只有一个节点）。
 *
 * ## 这里**没有**覆盖什么
 *
 * 真实的颜色 / 渐变外观（读不到像素）、真实歌词、以及播放中的平滑时钟（`isPlaying = true`
 * 会启动一个 16ms 的本地时钟循环，本仓库的渲染测试一律用静止状态驱动，不在这里跑动画）。
 * 时钟的算术由 `LyricSweepTest` 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class LyricSweepRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun bindSettingsToThisTest() {
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
    }

    @Composable
    private fun BeansTestHost(content: @Composable () -> Unit) {
        BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
            content()
        }
    }

    /** 带真逐字时间轴的一行：把文本对半切成两个词，词时长都大于 0。 */
    private fun wordedLine(time: Double, text: String, translation: String? = null): LyricLine {
        val half = text.length / 2
        return LyricLine(
            time = time,
            text = text,
            translation = translation,
            words = listOf(
                LyricWord(time = time, duration = 0.6, text = text.substring(0, half)),
                LyricWord(time = time + 0.6, duration = 0.6, text = text.substring(half)),
            ),
        )
    }

    private fun renderLyrics(lyrics: List<LyricLine>, progress: Double) {
        composeRule.setContent {
            BeansTestHost {
                LyricsSection(
                    lyrics = lyrics,
                    errorText = null,
                    progress = progress,
                    userOffset = 0.0,
                    showTranslation = true,
                    fontSize = 18f,
                    align = "center",
                    isPlaying = false,
                    playbackSpeed = 1f,
                    onSeek = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun renderPreview(lyrics: List<LyricLine>, progress: Double) {
        composeRule.setContent {
            BeansTestHost {
                PlayerLyricPreview(
                    lyrics = lyrics,
                    errorText = null,
                    progress = progress,
                    userOffset = 0.0,
                    fontSize = 16f,
                    align = "center",
                    isPlaying = false,
                    playbackSpeed = 1f,
                    onSeek = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun sweepLayerCount(): Int =
        composeRule.onAllNodesWithTag(lyricSweepTag, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .size

    // ------------------------------------------------------------------
    // 整屏歌词
    // ------------------------------------------------------------------

    @Test(timeout = 120_000L)
    fun wholeLineLyricsGetNoSweepLayerAtAll() {
        // 三行都只有整行时间（`words` 为空）—— 就是三个平台在逐字缺席时的返回值。
        renderLyrics(
            lyrics = listOf(
                LyricLine(time = 0.0, text = "Plain alpha", translation = "译文一"),
                LyricLine(time = 4.0, text = "Plain bravo", translation = "译文二"),
                LyricLine(time = 8.0, text = "Plain charlie"),
            ),
            progress = 4.5,
        )

        composeRule.onNodeWithText("Plain bravo").assertIsDisplayed()
        assertEquals(
            "没有真逐字数据就必须一个扫过层都不画（退回整行高亮）",
            0,
            sweepLayerCount(),
        )
        // 当前行的翻译照旧整行渲染，且不参与扫过。
        composeRule.onNodeWithText("译文二").assertIsDisplayed()
    }

    @Test(timeout = 120_000L)
    fun onlyTheCurrentWordTimedLineIsSwept() {
        // 三行都有真逐字数据：只有「当前行」那一行该多出扫过层。
        renderLyrics(
            lyrics = listOf(
                wordedLine(0.0, "Alpha words here"),
                wordedLine(4.0, "Bravo words here", translation = "布拉沃"),
                wordedLine(8.0, "Charlie words now"),
            ),
            progress = 4.5,
        )

        assertEquals("当前行（且只有当前行）必须有扫过层", 1, sweepLayerCount())
        composeRule.onNodeWithText("Bravo words here").assertIsDisplayed()
        composeRule.onNodeWithText("布拉沃").assertIsDisplayed()
    }

    @Test(timeout = 120_000L)
    fun sweptLineIsStillReadByAccessibilityExactlyOnce() {
        renderLyrics(
            lyrics = listOf(
                wordedLine(0.0, "Alpha words here"),
                wordedLine(4.0, "Bravo words here"),
            ),
            progress = 4.5,
        )

        assertEquals("扫过层必须在（它是同一行文本的第二份拷贝）", 1, sweepLayerCount())

        // 读屏看到的是**合并**语义树。逐字层那份拷贝必须被 clearAndSetSemantics 挡在
        // 可访问性之外，否则这一行会被读两遍 —— 这里直接数合并树里这行文本出现了几次。
        // （未合并树里本来就是两个节点：逐字层是真实的第二份文本节点，清语义只影响合并结果。）
        val readTexts = composeRule.onAllNodesWithText("Bravo words here")
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
        assertEquals("读屏必须只读到一遍这行歌词，实际读了 $readTexts", 1, readTexts.size)
    }

    // ------------------------------------------------------------------
    // 三行预览
    // ------------------------------------------------------------------

    @Test(timeout = 120_000L)
    fun previewSweepsOnlyItsCurrentLine() {
        renderPreview(
            lyrics = listOf(
                wordedLine(0.0, "Alpha words here"),
                wordedLine(4.0, "Bravo words here"),
                wordedLine(8.0, "Charlie words now"),
            ),
            progress = 4.5,
        )

        assertEquals("预览的当前行也必须扫", 1, sweepLayerCount())
        assertPreviewRows("Alpha words here", "Bravo words here", "Charlie words now")
        composeRule.onNodeWithText("Bravo words here").assertIsDisplayed()
    }

    @Test(timeout = 120_000L)
    fun previewWithoutWordTimingStaysWholeLineAndKeepsNeighbours() {
        renderPreview(
            lyrics = listOf(
                LyricLine(time = 0.0, text = "Plain alpha"),
                LyricLine(time = 4.0, text = "Plain bravo"),
                LyricLine(time = 8.0, text = "Plain charlie"),
            ),
            progress = 4.5,
        )

        assertEquals("预览在没有逐字数据时不能多画东西", 0, sweepLayerCount())
        assertPreviewRows("Plain alpha", "Plain bravo", "Plain charlie")
        composeRule.onNodeWithText("Plain bravo").assertIsDisplayed()
    }

    /**
     * 三行预览的上一行 / 当前行 / 下一行都必须在。
     *
     * 这里断「都在」而不是「都可见」：预览是固定 68dp 高的盒子，三行在本仓库渲染测试的
     * 字形度量下会溢出盒高，**下一行**可能整个落在盒子外 —— 那是这个盒子既有的设计
     * （盒高与三个字号这次一个都没动），不是逐字渲染引入的。可见性由中间那一行的
     * `assertIsDisplayed` 负责。
     */
    private fun assertPreviewRows(previous: String, current: String, next: String) {
        composeRule.onNodeWithText(previous).assertExists()
        composeRule.onNodeWithText(current).assertExists()
        composeRule.onNodeWithText(next).assertExists()
    }
}

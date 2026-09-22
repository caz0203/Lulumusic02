package com.lulu.music

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.prefs.BeansDesktopLyricsFontSizeDefault
import com.lulu.music.data.prefs.BeansDesktopLyricsFontSizeMax
import com.lulu.music.data.prefs.BeansDesktopLyricsFontSizeMin
import com.lulu.music.data.prefs.BeansDesktopLyricsLineHeightScale
import com.lulu.music.data.prefs.BeansDesktopLyricsNextLineAlpha
import com.lulu.music.data.prefs.BeansDesktopLyricsNextLineScale
import com.lulu.music.data.prefs.BeansDesktopLyricsSungColorDefault
import com.lulu.music.data.prefs.BeansDesktopLyricsUnsungColorDefault
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.data.prefs.beansDesktopLyricsLineHeight
import com.lulu.music.playback.BeansDesktopLyricsControlsTag
import com.lulu.music.playback.BeansDesktopLyricsLyricsTag
import com.lulu.music.playback.BeansDesktopLyricsOutlineTag
import com.lulu.music.playback.BeansDesktopLyricsPanel
import com.lulu.music.playback.BeansDesktopLyricsPanelAutoHideMs
import com.lulu.music.playback.BeansDesktopLyricsPanelController
import com.lulu.music.playback.BeansDesktopLyricsSurface
import com.lulu.music.playback.BeansDesktopLyricsSurfaceSpec
import com.lulu.music.playback.beansDesktopLyricsInkColor
import com.lulu.music.playback.beansDesktopLyricsSettingsIntent
import com.lulu.music.playback.beansDesktopLyricsSurfaceSpec
import com.lulu.music.playback.beansDesktopLyricsPanelTap
import com.lulu.music.ui.screens.LyricInk
import com.lulu.music.ui.screens.LyricPaintMode
import com.lulu.music.ui.screens.LyricPaintPlan
import com.lulu.music.ui.theme.BeansTheme
import com.lulu.music.ui.theme.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 悬浮条的**最终表面**：两行居中的歌词 + （可选）「已锁定」标记 + （可选）展开的控制条。
 *
 * ## 这个文件能证明什么
 *
 * 渲染那一层 [BeansDesktopLyricsSurface] 是**参数驱动**的（文字 / 绘制方案 / 规格 / 面板状态
 * 全部作为入参），所以不需要真的挂一个系统悬浮窗就能把它组合出来，并对「屏幕上到底有什么」
 * 逐条断言：
 *  1. **默认态没有任何可点击节点、没有任何按钮文案** —— 「默认只有两行歌词」是**数出来的**；
 *  2. **不存在描边层** —— 上一轮那层描边（同一段文字的轮廓副本）已经删除，这里直接断言
 *     它的语义标签一个节点都没有（谁把它加回来，这条立刻红）；
 *  3. **两行不重叠、行高一致、各自水平居中** —— 这三条正是用户截图里「两行叠在一起 /
 *     没有居中」那两个缺陷的回归防线；
 *  4. 字号真的生效（12sp 的行比 24sp 的矮 —— 用文本布局输入断言，理由见下）；
 *  5. 已唱 / 未唱两色真的来自那两个键，下一行 = 未唱色压暗一档；
 *  6. 控制条：默认不存在，`panelVisible = true` 时恰好五颗按钮（锁定 / 上一首 / 播放暂停 /
 *     下一首 / 设置），图标随锁定与播放状态变，点每一颗都回调、点歌词回调的是「展开 / 收起」；
 *  7. 锁定只锁拖动：锁定后拖动回调一次都不会发生，点按照常；
 *  8. 齿轮的目标是 App 的设置入口（`MainActivity` + `NEW_TASK`）。
 *
 * ## 关于「重叠」这条为什么能在这里钉住，以及钉不住什么
 *
 * 真机上那个重叠的根因**不是**「两行之间的距离不够」，而是**两层文字各自继承 / 覆盖了不同的
 * `LocalTextStyle`**：歌词层继承 `MaterialTheme` 通过 `ProvideTextStyle(typography.bodyLarge)`
 * 给出的 `lineHeight = 24sp`，而描边层自己传了 `TextStyle(drawStyle = Stroke(...))`、
 * 把那份主题样式**整个替换**掉、行高落回字体自身的 ~1.17em。于是描边层的第二行被排到第一行
 * 上面。Robolectric 的假字体行高恒为 36px（大于 24sp），所以**像素位置上永远看不到这个差异**
 * （实测两层的高度是 35px 与 36px）；能钉住的是**造成差异的那件事本身**：
 * 两行的 `lineHeight` 必须来自同一个来源（[beansDesktopLyricsLineHeight]），且屏幕上只有一层。
 *
 * ## 这个文件**不能**证明什么（诚实说明）
 *
 * 没有模拟器也没有真机：这里不测 `WindowManager.addView`、不测真实触摸 / 拖动在桌面上的手感、
 * 不测真机上的观感（Robolectric 读不到像素，字体度量也是退化的：所有字形宽度约 1px、
 * 行高恒为 36px）。**这里从来没有挂过悬浮窗**，只是把它的内容组合出来。
 * 窗口行为那一半由 `DesktopLyricsTest`（纯逻辑，含面板显隐 / 自动收起 / 落点夹紧）覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class DesktopLyricsSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------------
    // 1. 默认态：只有歌词 —— 没有按钮、没有面板、没有描边
    // ------------------------------------------------------------------

    /**
     * 默认态（控制条收起）下，悬浮条上一个可点击节点都不该有。
     *
     * 这条用例是「默认只有两行歌词」这件事的**直接**守护：控制条是**显式**状态
     * （`panelVisible`），默认必须是收起；谁把默认值改成展开，这里立刻红。
     */
    @Test(timeout = 60_000L)
    fun theDefaultOverlayHasNoClickableNodeAtAll() {
        composeRule.setContent { Host(current = CURRENT, next = NEXT, spec = spec()) }
        composeRule.waitForIdle()

        assertTrue(
            "默认态不允许有任何可点击节点（控制条默认收起）",
            composeRule.onAllNodes(hasClickAction())
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
        assertTrue(
            "默认态不该有控制条",
            composeRule.onAllNodes(hasTestTag(BeansDesktopLyricsControlsTag), useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
    }

    /** 默认态下，控制条与旧面板的文案必须**一个都找不到**。 */
    @Test(timeout = 60_000L)
    fun noneOfTheControlLabelsExistWhileTheControlsAreHidden() {
        composeRule.setContent { Host(current = CURRENT, next = NEXT, spec = spec()) }
        composeRule.waitForIdle()

        listOf(
            "锁定", "解锁", "关闭", "上一首", "下一首", "播放", "暂停", "设置",
            "Lock", "Unlock", "Close", "Previous", "Next", "Play", "Pause", "Settings",
        ).forEach { label ->
            assertTrue(
                "「$label」不该出现在收起状态的悬浮条上",
                composeRule.onAllNodes(hasText(label, substring = true))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isEmpty(),
            )
        }
    }

    /**
     * 屏幕上**不存在**描边层。
     *
     * 上一轮的描边层用 [BeansDesktopLyricsOutlineTag] 出现在语义树上（`clearAndSetSemantics` +
     * testTag），这一版它被删除了 —— 这条用例把「删除」这件事钉死：只要有人把描边加回来
     * （无论用什么颜色），标签就会重新出现，用例立刻红。
     */
    @Test(timeout = 60_000L)
    fun theOutlineLayerIsGoneEntirely() {
        composeRule.setContent { Host(current = CURRENT, next = NEXT, spec = spec()) }
        composeRule.waitForIdle()

        assertTrue(
            "描边层已经被删除，屏幕上不该有任何节点带这个标签",
            composeRule.onAllNodes(hasTestTag(BeansDesktopLyricsOutlineTag), useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
        // 歌词本身照样在（删掉描边不等于删掉歌词）。
        composeRule.onNodeWithText(CURRENT).assertIsDisplayed()
        composeRule.onNodeWithText(NEXT).assertIsDisplayed()
    }

    /** 两行歌词各只出现**一次**：屏幕上是单层文字，不再有轮廓副本。 */
    @Test(timeout = 60_000L)
    fun eachLyricLineIsExposedExactlyOnce() {
        composeRule.setContent { Host(current = CURRENT, next = NEXT, spec = spec()) }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText(CURRENT).assertCountEquals(1)
        composeRule.onAllNodesWithText(NEXT).assertCountEquals(1)
        // 未合并树里也只有一条 —— 这正是「没有第二层副本」的证明。
        composeRule.onAllNodesWithText(CURRENT, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithText(NEXT, useUnmergedTree = true).assertCountEquals(1)
    }

    // ------------------------------------------------------------------
    // 2. 版面：两行不重叠、行高一致、各自居中
    // ------------------------------------------------------------------

    /**
     * 两行**不重叠**，而且用的是同一个行高来源。
     *
     * 重叠的真凶是「两层文字行高不同」（见类注释），所以这里同时断言两件事：
     *  - 几何上 `当前行.bottom <= 下一行.top`（在当前环境里这能真的量出来）；
     *  - 两行的 `lineHeight` **都等于** [beansDesktopLyricsLineHeight] 给出的那个值 ——
     *    这一条不依赖字体度量，是「行高只来自一处」的直接证明（真机上那个 bug 就死在这里）。
     */
    @Test(timeout = 60_000L)
    fun theTwoLinesNeverOverlapAndShareTheSameLineHeightSource() {
        // 固定宽度：让「居中」这件事有一个可量的参照（见下一条用例）。
        composeRule.setContent { Host(current = CURRENT, next = NEXT, spec = spec(), contentWidth = 240.dp) }
        composeRule.waitForIdle()

        val current = composeRule.onNodeWithText(CURRENT).fetchSemanticsNode().boundsInRoot
        val next = composeRule.onNodeWithText(NEXT).fetchSemanticsNode().boundsInRoot

        assertTrue(
            "两行不许重叠：当前行 $current，下一行 $next",
            current.bottom <= next.top,
        )
        assertTrue("两行都必须有高度（否则上面的断言是空的）", current.height > 0f && next.height > 0f)

        val expectedCurrent = beansDesktopLyricsLineHeight(BeansDesktopLyricsFontSizeDefault)
        val expectedNext = beansDesktopLyricsLineHeight(
            BeansDesktopLyricsFontSizeDefault * BeansDesktopLyricsNextLineScale,
        )
        assertEquals(
            "当前行的行高只能来自 beansDesktopLyricsLineHeight（真机重叠的根因就是它被继承成了别的值）",
            expectedCurrent,
            lineHeightOf(CURRENT),
            0.01f,
        )
        assertEquals("下一行同理", expectedNext, lineHeightOf(NEXT), 0.01f)
        assertNotEquals(
            "两行的字号本来就不同，行高当然也不同 —— 但它们必须来自同一个换算",
            expectedCurrent,
            expectedNext,
        )
    }

    /**
     * 两行**各自水平居中**（不是「只是整块靠在左边」）。
     *
     * 参照系是**歌词块自己**的中轴线（悬浮条在真机上是 `WRAP_CONTENT` 窗口，窗口宽度就等于
     * 歌词块宽度，整块再被窗口落点水平居中到屏幕上 —— 那条由 `DesktopLyricsTest` 的
     * `beansDefaultOverlayPosition` 断言）。所以这里要钉的是：长短明显不同的两行**共用同一条中轴线**，
     * 而不是各自贴左。旧实现用 `TextAlign.Start` + `Alignment.TopStart`，短的下一行会明显偏左。
     */
    @Test(timeout = 60_000L)
    fun bothLinesAreCentredOnTheSameAxis() {
        val wide = "这是一行明显长很多的歌词用来验证居中行为是否正确"
        val narrow = "下一行"
        composeRule.setContent {
            Host(current = wide, next = narrow, spec = spec(), contentWidth = 240.dp)
        }
        composeRule.waitForIdle()

        val current = composeRule.onNodeWithText(wide).fetchSemanticsNode().boundsInRoot
        val next = composeRule.onNodeWithText(narrow).fetchSemanticsNode().boundsInRoot
        val block = composeRule.onNodeWithTag(BeansDesktopLyricsLyricsTag).fetchSemanticsNode().boundsInRoot
        val axis = block.left + block.width / 2f

        assertTrue(
            "前提：两行长度必须真的不同（否则「共用中轴线」是空的）：$current vs $next",
            kotlin.math.abs(current.width - next.width) > 4f,
        )
        assertEquals(
            "当前行的中轴线必须落在歌词块中线上（现在：${current.left}..${current.right}）",
            axis,
            current.left + current.width / 2f,
            1f,
        )
        assertEquals(
            "较短的那一行也必须居中，而不是贴左边（现在：${next.left}..${next.right}）",
            axis,
            next.left + next.width / 2f,
            1f,
        )
    }

    // ------------------------------------------------------------------
    // 3. 字号
    // ------------------------------------------------------------------

    /**
     * 字号设置**真的**到了两行文字上。
     *
     * 为什么读文本布局（`GetTextLayoutResult` 里的 `layoutInput.style.fontSize`）而不是量节点高度：
     * Robolectric 的字体度量是退化的 —— 实测 12sp 与 24sp 的同一个节点都是 36px 高，
     * 任何「大字比小字高」的断言在那里都是假的（不是被测代码错，而是测量环境不成立）。
     * 布局输入里的字号是**渲染层真正拿到的那一个值**，所以这条断言既精确又诚实。
     */
    @Test(timeout = 60_000L)
    fun theFontSizeSettingReallyReachesBothLinesOfTheTextLayout() {
        val smallSpec = spec(fontSizeSp = 12f)
        val bigSpec = spec(fontSizeSp = 24f)

        composeRule.setContent {
            BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
                Column {
                    BeansDesktopLyricsSurface(
                        currentText = SMALL,
                        nextText = SMALL_NEXT,
                        plan = plan,
                        spec = smallSpec,
                        colorFor = { ink -> beansDesktopLyricsInkColor(smallSpec, ink) },
                    )
                    BeansDesktopLyricsSurface(
                        currentText = BIG,
                        nextText = BIG_NEXT,
                        plan = plan,
                        spec = bigSpec,
                        colorFor = { ink -> beansDesktopLyricsInkColor(bigSpec, ink) },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals("当前行必须用设置里的字号（sp）", 12f, fontSizeOf(SMALL), 0.01f)
        assertEquals("改成 24sp 之后必须真的变大", 24f, fontSizeOf(BIG), 0.01f)
        assertEquals(
            "下一行 = 当前行 × 0.8（12sp → 9.6sp），而且与当前行分别渲染",
            12f * 0.8f,
            fontSizeOf(SMALL_NEXT),
            0.01f,
        )
        assertEquals(24f * 0.8f, fontSizeOf(BIG_NEXT), 0.01f)
    }

    // ------------------------------------------------------------------
    // 4. 「已锁定」标记（锁定 / 未锁定各一个用例）
    // ------------------------------------------------------------------

    @Test(timeout = 60_000L)
    fun theLockedBadgeIsAbsentWhileUnlocked() {
        composeRule.setContent { Host(current = CURRENT, next = NEXT, spec = spec(locked = false)) }
        composeRule.waitForIdle()

        assertTrue(
            "未锁定时不该有「已锁定」标记",
            composeRule.onAllNodesWithText("已锁定").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty(),
        )
    }

    @Test(timeout = 60_000L)
    fun theLockedBadgeIsShownWhileLocked() {
        composeRule.setContent { Host(current = CURRENT, next = NEXT, spec = spec(locked = true)) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("已锁定").assertIsDisplayed()
    }

    @Test(timeout = 60_000L)
    fun theLockedBadgeStepsAsideWhenTheControlRowExplainsTheState() {
        composeRule.setContent {
            Host(current = CURRENT, next = NEXT, spec = spec(locked = true), panelVisible = true)
        }
        composeRule.waitForIdle()

        // 控制条上那颗按钮已经写着「解锁」，再挂一个「已锁定」标记就是重复。
        composeRule.onNodeWithText("解锁").assertIsDisplayed()
        assertTrue(
            "控制条展开时不再重复显示「已锁定」标记",
            composeRule.onAllNodesWithText("已锁定").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 5. 规格：夹紧 / 默认色 / 行高派生（纯函数，不依赖渲染）
    // ------------------------------------------------------------------

    @Test(timeout = 60_000L)
    fun theSpecClampsEveryValueAndDerivesTheDefaults() {
        val defaults = spec()

        assertEquals(
            "默认字号 = 15sp",
            BeansDesktopLyricsFontSizeDefault,
            defaults.currentFontSizeSp,
            0f,
        )
        assertEquals("默认下一行 = 15 × 0.8 = 12sp", 12f, defaults.nextFontSizeSp, 0.0001f)
        assertEquals(
            "默认行高 = 15 × 1.25 = 18.75sp（显式给出，不再继承主题的 24sp）",
            BeansDesktopLyricsFontSizeDefault * BeansDesktopLyricsLineHeightScale,
            defaults.currentLineHeightSp,
            0.0001f,
        )
        assertEquals(
            "下一行行高同样由字号派生",
            beansDesktopLyricsLineHeight(defaults.nextFontSizeSp),
            defaults.nextLineHeightSp,
            0.0001f,
        )

        // 越界值被夹回区间（备份导入 / 手改偏好都会走到这里）。
        assertEquals(BeansDesktopLyricsFontSizeMin, spec(fontSizeSp = -100f).currentFontSizeSp, 0f)
        assertEquals(BeansDesktopLyricsFontSizeMax, spec(fontSizeSp = 900f).currentFontSizeSp, 0f)
        // NaN 必须被按成默认值（NaN 会让所有比较都为假，任何 coerceIn 都拦不住）。
        assertEquals(BeansDesktopLyricsFontSizeDefault, spec(fontSizeSp = Float.NaN).currentFontSizeSp, 0f)
    }

    /**
     * **已唱色 / 未唱色**：默认就是绿 / 白，设置过就用设置里的值，两把键**互不影响**。
     */
    @Test(timeout = 60_000L)
    fun theSungAndUnsungColoursComeFromTheTwoRepurposedKeys() {
        val defaults = spec()

        assertEquals(
            "没设置过 → 已唱色是默认绿 $BeansDesktopLyricsSungColorDefault",
            parseHexColor(BeansDesktopLyricsSungColorDefault),
            defaults.sungColor,
        )
        assertEquals(
            "没设置过 → 未唱色是默认白 $BeansDesktopLyricsUnsungColorDefault",
            parseHexColor(BeansDesktopLyricsUnsungColorDefault),
            defaults.unsungColor,
        )
        assertEquals(Color.White, defaults.unsungColor)
        assertNotEquals("绿与白必须真的不同（否则逐字扫过看不出来）", defaults.sungColor, defaults.unsungColor)
        assertTrue(
            "默认绿必须够亮才能压在深色壁纸上：${defaults.sungColor}",
            defaults.sungColor.green > 0.5f && defaults.sungColor.red < 0.5f && defaults.sungColor.blue < 0.8f,
        )

        val custom = spec(sungHex = "#FF12AB34", unsungHex = "#FF5678CD")
        assertEquals(Color(0xFF12AB34), custom.sungColor)
        assertEquals(Color(0xFF5678CD), custom.unsungColor)

        // 只改一把键不能影响另一把 —— 两色独立可调。
        assertEquals("改未唱色不影响已唱色", custom.sungColor, spec(sungHex = "#FF12AB34").sungColor)
        assertEquals("改已唱色不影响未唱色", custom.unsungColor, spec(unsungHex = "#FF5678CD").unsungColor)
    }

    /**
     * 逐字扫过里两种墨色**分别**对应已唱 / 未唱色，非当前行对应「未唱色压暗一档」。
     *
     * 这是需求里「已唱 = 绿、未唱 = 白、下一行 = 白压暗」这三句话的逐条落点。
     */
    @Test(timeout = 60_000L)
    fun theSweepInksFollowTheSungAndUnsungColours() {
        val custom = spec(sungHex = "#FF12AB34", unsungHex = "#FF5678CD")

        assertEquals("已唱 = 已唱色", custom.sungColor, beansDesktopLyricsInkColor(custom, LyricInk.CURRENT))
        assertEquals("未唱 = 未唱色", custom.unsungColor, beansDesktopLyricsInkColor(custom, LyricInk.UNSUNG))
        assertNotEquals(
            "已唱与未唱不能是同一个颜色",
            beansDesktopLyricsInkColor(custom, LyricInk.CURRENT),
            beansDesktopLyricsInkColor(custom, LyricInk.UNSUNG),
        )
        assertEquals(
            "非当前行 = 未唱色（不是已唱色）",
            custom.unsungColor.red,
            beansDesktopLyricsInkColor(custom, LyricInk.DIM).red,
            0.0001f,
        )
        val dim = beansDesktopLyricsInkColor(custom, LyricInk.DIM)
        assertTrue(
            "而且必须更暗（下一行要退到背景里）：${dim.alpha}",
            dim.alpha < custom.unsungColor.alpha,
        )
        // 颜色的 alpha 是 8 位量化的（0.45 → 115/255 = 0.45098），所以比的是**比例**。
        assertEquals(
            "压暗的比例就是 BeansDesktopLyricsNextLineAlpha",
            BeansDesktopLyricsNextLineAlpha,
            dim.alpha / custom.unsungColor.alpha,
            0.01f,
        )
    }

    /** 下一行那个颜色也确实进了规格（悬浮条的下一行用的是它，不是未唱色）。 */
    @Test(timeout = 60_000L)
    fun theNextLineColourIsTheUnsungColourDimmed() {
        val custom = spec(unsungHex = "#FF5678CD")
        assertEquals(
            "下一行色 = 未唱色 × 0.45（alpha 是 8 位量化的，比比例）",
            BeansDesktopLyricsNextLineAlpha,
            custom.nextLineColor.alpha / custom.unsungColor.alpha,
            0.01f,
        )
        assertTrue(
            "下一行必须真的比未唱色暗：${custom.nextLineColor.alpha} vs ${custom.unsungColor.alpha}",
            custom.nextLineColor.alpha < custom.unsungColor.alpha,
        )
        assertEquals("色相不变（同一个红绿蓝）", custom.unsungColor.red, custom.nextLineColor.red, 0.0001f)
    }

    // ------------------------------------------------------------------
    // 6. 控制条（图3）
    // ------------------------------------------------------------------

    /** 展开之后恰好五颗按钮，文案与图3 一致；每一颗都带点击动作。 */
    @Test(timeout = 60_000L)
    fun theExpandedControlRowCarriesExactlyTheFiveActions() {
        composeRule.setContent {
            Host(current = CURRENT, next = NEXT, spec = spec(), panelVisible = true, isPlaying = false)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BeansDesktopLyricsControlsTag, useUnmergedTree = true).assertIsDisplayed()
        listOf("锁定", "上一首", "播放", "下一首", "设置").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        assertEquals(
            "控制条上恰好五颗按钮",
            5,
            composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size,
        )
    }

    /** 播放 / 暂停反映**真实**播放状态；锁定时那颗按钮显示「解锁」。 */
    @Test(timeout = 60_000L)
    fun thePlayPauseAndLockButtonsFollowTheRealState() {
        composeRule.setContent {
            Host(current = CURRENT, next = NEXT, spec = spec(locked = true), panelVisible = true, isPlaying = true)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("暂停").assertIsDisplayed()
        assertTrue(
            "正在播放时不该显示「播放」",
            composeRule.onAllNodesWithText("播放").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty(),
        )
        composeRule.onNodeWithText("解锁").assertIsDisplayed()
        assertTrue(
            "已锁定时那颗按钮说的是「解锁」；展开的控制条已经把状态说清楚，" +
                "所以连「已锁定」标记都不该再出现",
            composeRule.onAllNodesWithText("锁定", substring = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
    }

    /** 每一颗按钮都回调它自己的动作，而且**不会**顺手把「点歌词」那条手势也触发。 */
    @Test(timeout = 60_000L)
    fun everyControlRowButtonReportsItsOwnClick() {
        val clicks = mutableListOf<String>()
        composeRule.setContent {
            Host(
                current = CURRENT,
                next = NEXT,
                spec = spec(),
                panelVisible = true,
                onTapLyrics = { clicks += "lyrics" },
                onToggleLock = { clicks += "lock" },
                onPrevious = { clicks += "previous" },
                onTogglePlayPause = { clicks += "playPause" },
                onNext = { clicks += "next" },
                onOpenSettings = { clicks += "settings" },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("锁定").performClick()
        composeRule.onNodeWithText("上一首").performClick()
        composeRule.onNodeWithText("播放").performClick()
        composeRule.onNodeWithText("下一首").performClick()
        composeRule.onNodeWithText("设置").performClick()

        assertEquals(
            "五颗按钮各回调一次，且没有一颗被算成「点歌词」",
            listOf("lock", "previous", "playPause", "next", "settings"),
            clicks,
        )
    }

    /**
     * 点歌词回调的是「展开 / 收起」这条手势，而且它真的会驱动控制条显隐。
     *
     * 面板状态用生产里同一个迁移函数（`beansDesktopLyricsPanelTap`）维护，
     * 于是这里验的是「手势 → 状态 → 界面」这一整条缝：点一下出现控制条，再点一下消失。
     */
    @Test(timeout = 60_000L)
    fun tappingTheLyricsRevealsAndHidesTheControlRow() {
        composeRule.setContent {
            var panel by remember { mutableStateOf(BeansDesktopLyricsPanel.Hidden) }
            Host(
                current = CURRENT,
                next = NEXT,
                spec = spec(),
                panelVisible = panel.visible,
                onTapLyrics = { panel = beansDesktopLyricsPanelTap(panel, nowMs = 1_000L) },
            )
        }
        composeRule.waitForIdle()

        assertTrue(
            "一开始没有控制条",
            composeRule.onAllNodes(hasTestTag(BeansDesktopLyricsControlsTag), useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )

        composeRule.onNodeWithTag(BeansDesktopLyricsLyricsTag).performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("设置").assertIsDisplayed()

        composeRule.onNodeWithTag(BeansDesktopLyricsLyricsTag).performTouchInput { click() }
        composeRule.waitForIdle()
        assertTrue(
            "再点一下收起",
            composeRule.onAllNodes(hasTestTag(BeansDesktopLyricsControlsTag), useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
    }

    /**
     * 拖动：未锁定时逐帧回调位移、结束时回调一次收尾；**锁定后一次都不动**。
     *
     * 「锁定只锁拖动」这条约束的落点就在这里 —— 锁定之后窗口照常接收触摸（点按仍然回调），
     * 只是不再跟着手指走。
     */
    @Test(timeout = 60_000L)
    fun draggingMovesTheWindowUnlessItIsLocked() {
        var drags = 0
        var ends = 0
        var taps = 0
        composeRule.setContent {
            Host(
                current = DRAG_CURRENT,
                next = DRAG_NEXT,
                spec = spec(locked = false),
                onTapLyrics = { taps += 1 },
                onDragBy = { _, _ -> drags += 1 },
                onDragEnd = { ends += 1 },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BeansDesktopLyricsLyricsTag).performTouchInput {
            swipeLeft(startX = right, endX = left, durationMillis = 300)
        }
        composeRule.waitForIdle()

        assertTrue("拖动必须逐帧回调位移（收到 $drags 次）", drags > 0)
        assertEquals("拖动结束必须回调一次（落盘保存位置）", 1, ends)
        assertEquals("拖动不该同时被当成「点一下」（否则一拖就展开控制条）", 0, taps)
    }

    @Test(timeout = 60_000L)
    fun draggingIsDeadWhileLocked() {
        var drags = 0
        var ends = 0
        var taps = 0
        composeRule.setContent {
            Host(
                current = DRAG_CURRENT,
                next = DRAG_NEXT,
                spec = spec(locked = true),
                onTapLyrics = { taps += 1 },
                onDragBy = { _, _ -> drags += 1 },
                onDragEnd = { ends += 1 },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BeansDesktopLyricsLyricsTag).performTouchInput {
            swipeLeft(startX = right, endX = left, durationMillis = 300)
        }
        composeRule.waitForIdle()

        assertEquals("锁定时窗口不该跟着手指走", 0, drags)
        assertEquals("锁定时也不该落盘保存位置", 0, ends)
        assertEquals("但点按照常（锁定只锁拖动，展开控制条这条路必须还在）", 1, taps)
    }

    // ------------------------------------------------------------------
    // 8. 控制条的显隐 + 自动收起（控制器：注入假时钟，不用真机上的秒表）
    // ------------------------------------------------------------------

    /** 默认收起；点一下展开并从这一刻开始计时；再点一下收起。 */
    @Test(timeout = 60_000L)
    fun theControlsStartHiddenAndToggleOnEveryTap() {
        var now = 1_000L
        val controller = BeansDesktopLyricsPanelController(nowMs = { now })

        assertTrue("默认必须是收起的（图2：屏幕上只有两行歌词）", !controller.panel.visible)

        controller.tap()
        assertTrue("点一下展开", controller.panel.visible)
        assertEquals("计时从这一刻开始", 1_000L, controller.panel.lastInteractionMs)

        controller.tap()
        assertTrue("再点一下收起", !controller.panel.visible)
        assertEquals("收起之后不带着上次的计时", 0L, controller.panel.lastInteractionMs)
    }

    /**
     * 自动收起：静默满一个窗口才收；期间任何一次操作都重新计时。
     *
     * 用假时钟逐毫秒钉住，不需要真机上的秒表 —— 这条交互最容易错的地方（计时起点、
     * 交互重置、边界到底是「满 4 秒」还是「超过 4 秒」）都在这里定死。
     */
    @Test(timeout = 60_000L)
    fun theControlsAutoHideExactlyAfterTheSilenceWindowAndAnyInteractionRestartsIt() {
        var now = 0L
        val controller = BeansDesktopLyricsPanelController(nowMs = { now })

        controller.tap()
        assertEquals("刚展开时还剩整整一个静默窗", BeansDesktopLyricsPanelAutoHideMs, controller.remainingMs())

        now = BeansDesktopLyricsPanelAutoHideMs - 1
        controller.tick()
        assertTrue("还差一毫秒：不许收起", controller.panel.visible)

        // 期间按了控制条上的一颗按钮（生产实现里每颗按钮都会 keepAlive）。
        controller.keepAlive()
        assertEquals("交互把计时重置", BeansDesktopLyricsPanelAutoHideMs, controller.remainingMs())

        now += BeansDesktopLyricsPanelAutoHideMs - 1
        controller.tick()
        assertTrue("重置之后又是一整个静默窗", controller.panel.visible)

        now += 1
        controller.tick()
        assertTrue("静默满 4 秒 → 自动收起", !controller.panel.visible)
    }

    /** 时钟回拨（系统对时 / 用户改时间）不能让控制条闪没。 */
    @Test(timeout = 60_000L)
    fun aBackwardsClockNeverHidesTheControls() {
        var now = 10_000L
        val controller = BeansDesktopLyricsPanelController(nowMs = { now })
        controller.tap()

        now = 5_000L
        controller.tick()
        assertTrue("时钟倒退按「还不该收」处理", controller.panel.visible)
    }

    /** 收起状态下的一切操作都是空操作（不能让计时悄悄跑起来）。 */
    @Test(timeout = 60_000L)
    fun keepAliveIsANoOpWhileTheControlsAreHidden() {
        val controller = BeansDesktopLyricsPanelController(nowMs = { 9_999L })
        controller.keepAlive()
        controller.tick()
        assertTrue(!controller.panel.visible)
        assertEquals(0L, controller.panel.lastInteractionMs)
    }

    // ------------------------------------------------------------------
    // 9. 齿轮：进 App 的设置页
    // ------------------------------------------------------------------

    /**
     * 齿轮的目标是 App 的设置入口：一个显式的主界面 Intent + `NEW_TASK`。
     *
     * 没有模拟器 / 真机，所以这里不假装点了齿轮会看到设置页；能钉住的是**这个 Intent 真的
     * 指向本应用的主界面**（设置页就在它的导航树里），而且带上了从悬浮窗 / 服务启动 Activity
     * 必需的 `FLAG_ACTIVITY_NEW_TASK`（漏掉它系统会直接抛异常）。
     */
    @Test(timeout = 60_000L)
    fun theGearIntentOpensTheAppEntryPointThatHostsSettings() {
        val context = ApplicationProvider.getApplicationContext<BeansApplication>()
        val intent = beansDesktopLyricsSettingsIntent(context)

        assertEquals("必须是显式 Intent（不能靠隐式匹配飘到别的 App）", MainActivity::class.java.name, intent.component?.className)
        assertEquals(
            "从服务 / 悬浮窗启动 Activity 必须带 NEW_TASK",
            Intent.FLAG_ACTIVITY_NEW_TASK,
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
        assertTrue(
            "目标组件必须真的是本应用的 Activity",
            intent.component?.packageName == context.packageName,
        )
    }

    // ------------------------------------------------------------------
    // 设施
    // ------------------------------------------------------------------

    /**
     * 某个节点上的文字**真正使用的字号**（sp）。
     *
     * 走 `SemanticsActions.GetTextLayoutResult`：那一个回调给出的 `TextLayoutResult` 就是渲染层
     * 实际用来排版这一行的输入，所以 `layoutInput.style.fontSize` 正是「字号设置有没有生效」的答案。
     */
    private fun fontSizeOf(text: String): Float = layoutOf(text).layoutInput.style.fontSize.value

    /** 同一路径下的行高（sp）—— 「两层行高不一致」那个 bug 就是靠它钉住的。 */
    private fun lineHeightOf(text: String): Float = layoutOf(text).layoutInput.style.lineHeight.value

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(layouts)
        }
        return layouts.first()
    }

    private fun spec(
        fontSizeSp: Float = BeansDesktopLyricsFontSizeDefault,
        sungHex: String = "",
        unsungHex: String = "",
        locked: Boolean = false,
    ): BeansDesktopLyricsSurfaceSpec = beansDesktopLyricsSurfaceSpec(
        fontSizeSp = fontSizeSp,
        sungHex = sungHex,
        unsungHex = unsungHex,
        locked = locked,
    )

    @Composable
    private fun Host(
        current: String,
        next: String?,
        spec: BeansDesktopLyricsSurfaceSpec,
        panelVisible: Boolean = false,
        isPlaying: Boolean = false,
        contentWidth: Dp? = null,
        onTapLyrics: () -> Unit = {},
        onToggleLock: () -> Unit = {},
        onPrevious: () -> Unit = {},
        onTogglePlayPause: () -> Unit = {},
        onNext: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onDragBy: (Float, Float) -> Unit = { _, _ -> },
        onDragEnd: () -> Unit = {},
    ) {
        BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
            val bar: @Composable () -> Unit = {
                BeansDesktopLyricsSurface(
                    currentText = current,
                    nextText = next,
                    plan = plan,
                    spec = spec,
                    colorFor = { ink -> beansDesktopLyricsInkColor(spec, ink) },
                    panelVisible = panelVisible,
                    isPlaying = isPlaying,
                    onTapLyrics = onTapLyrics,
                    onToggleLock = onToggleLock,
                    onPrevious = onPrevious,
                    onTogglePlayPause = onTogglePlayPause,
                    onNext = onNext,
                    onOpenSettings = onOpenSettings,
                    onDragBy = onDragBy,
                    onDragEnd = onDragEnd,
                )
            }
            if (contentWidth == null) {
                bar()
            } else {
                // 固定宽度容器：给「居中」一个可量的参照系。
                Box(modifier = Modifier.width(contentWidth)) { bar() }
            }
        }
    }

    /**
     * 一个「整行高亮」的绘制方案：这些用例测的是版面、颜色与控制条，不是逐字扫过
     * （扫过由 `LyricSweepTest` / `LyricSweepRenderTest` 覆盖）。
     */
    private val plan = LyricPaintPlan(LyricPaintMode.WHOLE_LINE, LyricInk.CURRENT, LyricInk.CURRENT, 0f)

    private companion object {
        const val CURRENT = "现在的这一行歌词"
        const val NEXT = "接下来的那一行"

        /**
         * 拖动用例专用的一行长歌词。
         *
         * 为什么要特意用长的：Robolectric 的假字体每个字形只有约 1px 宽，上面那两行短歌词量出来
         * 只有 9px / 8px 宽，而 `detectDragGestures` 要越过 touch slop（8px）才开始拖动 ——
         * 滑动手势的总位移只有 9px，落在「刚好越不过去」的边界上，测出来的是字体度量的噪声，
         * 不是被测代码的行为。长一点，滑动位移就明确大于 slop。
         */
        const val DRAG_CURRENT = "拖动用例专用的一行很长很长的歌词文本用来让滑动距离明确超过 touch slop 阈值"
        const val DRAG_NEXT = "下一行也同样要足够长才不会把歌词块撑得太窄"
        const val SHORT_NEXT = "下一行"
        const val SMALL = "小号歌词甲"
        const val SMALL_NEXT = "小号下一行甲"
        const val BIG = "大号歌词乙"
        const val BIG_NEXT = "大号下一行乙"
    }
}

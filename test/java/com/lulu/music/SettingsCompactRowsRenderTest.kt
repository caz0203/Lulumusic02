package com.lulu.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.ui.BeansDockRadiusRange
import com.lulu.music.ui.screens.BeansCompactRows
import com.lulu.music.ui.screens.BeansCompactSliderTag
import com.lulu.music.ui.screens.SettingsSegmented
import com.lulu.music.ui.screens.SettingsSliderRow
import com.lulu.music.ui.theme.BeansTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「这一类设置行太大」这件事的**量出来的**结论（全设置页一套数值，不是三处各写一遍）。
 *
 * 用户圈的是「歌词字号 / 歌词对齐 / 歌词时间轴偏移」，要求的是**所有同族行**都收小。这里钉住
 * 三件事：
 *  1. 滑块行的**默认**形态就是紧凑形态（本体 30dp，`BeansCompactSliderTag` 真的出现）——
 *     这一条同时防止「以后新加的滑块默认又是 48dp 的大块」；
 *  2. 分段控件的**装饰**是紧凑的：外壳内边距 2dp + 段内垂直内边距 5dp（上下各一次 = 14dp，
 *     改造前是 3 + 8 各一次 = 22dp），并且比测试里那份「改造前样式」的对照件真的更矮；
 *  3. 同族控件之间的行距是全页统一的 2dp（[BeansCompactRows]）。
 *
 * ## 为什么量「整行 − 文字」而不是整行
 *
 * Robolectric 的文字度量与真机不同（实测 11sp 的行高就有 36px），所以**任何以绝对行高为
 * 断言的写法都是假的**。这里只断言两件与字体度量无关的事：装饰部分（padding）的精确值，
 * 以及「紧凑形态比改造前的松形态更矮」这个相对结论。滑块本体同理，用「整行 − 标签」来量：
 * Material3 的 Slider 语义节点是 `MergeDescendants` 的，`fetchSemanticsNode().size` 只覆盖
 * 可视内容（实测 16px），也不是布局高度。
 *
 * ## 为什么不重绑 DataStore
 *
 * 这三个用例只用纯尺寸原语（`SettingsSliderRow` / `SettingsSegmented` / `BeansCompactRows`）
 * 和显式传参的 `BeansTheme`，**不读任何 `beans.*` 偏好**（读偏好的那个重载是 `BeansAppTheme`）。
 * 所以这里刻意不调用 [RobolectricSingletons.resetSettingsDataStore]：少一次进程级 DataStore
 * 换绑与预热，也就少一次在 Windows 上撞 DataStore 文件锁的机会。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class SettingsCompactRowsRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val density get() = composeRule.density

    private fun dp(value: Int) = with(density) { value.dp.roundToPx() }

    @Test(timeout = 60_000L)
    fun sliderRowIsCompactByDefault() {
        composeRule.setContent {
            BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
                Column {
                    SettingsSliderRow(
                        title = DEFAULT_LABEL,
                        valueText = "32",
                        value = 32f,
                        valueRange = BeansDockRadiusRange,
                        steps = 31,
                        onValueChange = {},
                        modifier = Modifier.testTag(DEFAULT_TAG),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val row = composeRule.onNodeWithTag(DEFAULT_TAG).fetchSemanticsNode().size.height
        val label = composeRule
            .onNodeWithText(DEFAULT_LABEL, useUnmergedTree = true)
            .fetchSemanticsNode().size.height

        // 紧凑滑块本体真的被组合出来了（自定义轨道 / 缩小拇指那一版）。
        composeRule.onNodeWithTag(BeansCompactSliderTag).assertIsDisplayed()
        assertEquals(
            "不写 compact 参数时必须是紧凑形态：标签 + 1dp + 30dp",
            dp(30),
            row - label - dp(1),
        )
    }

    @Test(timeout = 60_000L)
    fun segmentedControlUsesTheCompactChromeAndIsShorterThanTheLooseOne() {
        composeRule.setContent {
            BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
                Column {
                    Box(modifier = Modifier.testTag(SEGMENTED_TAG)) {
                        SettingsSegmented(
                            options = listOf("left", "center"),
                            selected = "left",
                            label = { key -> if (key == "left") SEGMENTED_LABEL else "居中" },
                            onSelect = {},
                        )
                    }
                    // 改造前那一版的**对照**（外壳 3dp / 段内 8dp / 文字 13sp，就是「太大」的样子）。
                    // 它只在测试里存在：这样「更矮」这句话是量出来的，而不是拿文档里的数字当结论。
                    LooseSegmentedReference()
                }
            }
        }
        composeRule.waitForIdle()

        val row = composeRule.onNodeWithTag(SEGMENTED_TAG).fetchSemanticsNode().size.height
        val label = composeRule
            .onNodeWithText(SEGMENTED_LABEL, useUnmergedTree = true)
            .fetchSemanticsNode().size.height
        val loose = composeRule.onNodeWithTag(LOOSE_TAG).fetchSemanticsNode().size.height
        val looseLabel = composeRule
            .onNodeWithText(LOOSE_LABEL, useUnmergedTree = true)
            .fetchSemanticsNode().size.height

        assertEquals(
            "紧凑装饰 = 外壳内边距 2dp + 段内垂直内边距 5dp（上下各一次）= 14dp（改造前是 22dp）",
            dp(14),
            row - label,
        )
        assertTrue(
            "紧凑分段控件必须比改造前的松形态更矮：compact=$row loose=$loose",
            row < loose,
        )
        assertTrue(
            "至少矮掉装饰上省下来的 8dp：${loose - row} px",
            loose - row >= dp(8),
        )
        assertTrue(
            "文字必须从 13sp 收到 11sp（同一个密度下 11sp 的行高不可能高于 13sp）：$label vs $looseLabel",
            label <= looseLabel,
        )
    }

    @Test(timeout = 60_000L)
    fun compactRowsKeepTwoDpBetweenSiblingControls() {
        composeRule.setContent {
            BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
                BeansCompactRows {
                    SettingsSliderRow(
                        title = FIRST_LABEL,
                        valueText = "1",
                        value = 1f,
                        valueRange = BeansDockRadiusRange,
                        steps = 31,
                        onValueChange = {},
                        modifier = Modifier.testTag(FIRST_TAG),
                    )
                    SettingsSliderRow(
                        title = SECOND_LABEL,
                        valueText = "2",
                        value = 2f,
                        valueRange = BeansDockRadiusRange,
                        steps = 31,
                        onValueChange = {},
                        modifier = Modifier.testTag(SECOND_TAG),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val first = composeRule.onNodeWithTag(FIRST_TAG).fetchSemanticsNode().boundsInRoot
        val second = composeRule.onNodeWithTag(SECOND_TAG).fetchSemanticsNode().boundsInRoot

        assertEquals(
            "同一组紧凑控件之间必须正好是 2dp（歌词那一组 / 均衡器那两组 / 悬浮底栏都用这一个值）",
            dp(2),
            (second.top - first.bottom).toInt(),
        )
    }

    /**
     * 改造前那一版分段控件的对照件：外壳圆角 12dp / 内边距 3dp / 段间距 3dp / 段圆角 10dp /
     * 段内垂直内边距 8dp / 文字 13sp。
     *
     * **只存在于测试里**，用途是让「紧凑形态更矮」成为一个量出来的结论；文字用另一句，
     * 免得跟被测控件的文字在语义树里撞车。
     */
    @Composable
    private fun LooseSegmentedReference() {
        Box(modifier = Modifier.testTag(LOOSE_TAG)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(LOOSE_LABEL, LOOSE_OTHER_LABEL).forEach { text ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = text, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_TAG = "beans.compact.default"
        const val SEGMENTED_TAG = "beans.compact.segmented"
        const val LOOSE_TAG = "beans.compact.loose"
        const val FIRST_TAG = "beans.compact.first"
        const val SECOND_TAG = "beans.compact.second"
        const val DEFAULT_LABEL = "默认形态滑块"
        const val SEGMENTED_LABEL = "左对齐"
        const val LOOSE_LABEL = "对照左对齐"
        const val LOOSE_OTHER_LABEL = "对照居中"
        const val FIRST_LABEL = "第一条"
        const val SECOND_LABEL = "第二条"
    }
}

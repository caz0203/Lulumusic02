package com.lulu.music

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.ui.BeansDockRadiusRange
import com.lulu.music.ui.screens.BeansCompactSliderTag
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
 * 「低系统悬浮底栏」滑块块的紧凑化：**量出来的**高度，不是文档里的数字。
 *
 * 这一条同时钉住一个很容易踩回去的坑：Material3 的 `Slider` 有一条 48dp 的
 * `minimumInteractiveComponentSize`，从外面套 `Modifier.height(...)` 是压不下去的
 * （那条修饰符取 `max(内容, 48dp)`）。紧凑形态必须显式提供
 * `LocalMinimumInteractiveComponentSize` 才会真的变矮 —— 下面的等式就是这条结论的实证：
 *
 * ```
 * 默认行 = 标签 + 4dp + 48dp（Material3 的最小交互区）
 * 紧凑行 = 标签 + 1dp + 30dp（LocalMinimumInteractiveComponentSize 收到 30dp）
 * ```
 *
 * 注意**不要**用 `onNodeWithTag(BeansCompactSliderTag).fetchSemanticsNode().size` 去量滑块：
 * Material3 的 Slider 语义节点是 `MergeDescendants = true` 的，它在语义树里的 bounds 只覆盖
 * 可视内容（实测 16px），不是布局高度。所以这里量「行」再减去「标签」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class SettingsDockCompactSliderRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test(timeout = 60_000L)
    fun compactDockSliderIsMeasurablyShorterThanTheDefaultOne() {
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())

        composeRule.setContent {
            BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
                Column {
                    SettingsSliderRow(
                        title = NORMAL_LABEL,
                        valueText = "32",
                        value = 32f,
                        valueRange = BeansDockRadiusRange,
                        steps = 31,
                        onValueChange = {},
                        modifier = Modifier.testTag(NORMAL_TAG),
                    )
                    SettingsSliderRow(
                        title = COMPACT_LABEL,
                        valueText = "32",
                        value = 32f,
                        valueRange = BeansDockRadiusRange,
                        steps = 31,
                        onValueChange = {},
                        modifier = Modifier.testTag(COMPACT_TAG),
                        compact = true,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        fun dp(value: Int) = with(density) { value.dp.roundToPx() }

        val normalRow = composeRule.onNodeWithTag(NORMAL_TAG).fetchSemanticsNode().size.height
        val compactRow = composeRule.onNodeWithTag(COMPACT_TAG).fetchSemanticsNode().size.height
        val normalLabel = composeRule
            .onNodeWithText(NORMAL_LABEL, useUnmergedTree = true)
            .fetchSemanticsNode().size.height
        val compactLabel = composeRule
            .onNodeWithText(COMPACT_LABEL, useUnmergedTree = true)
            .fetchSemanticsNode().size.height

        // 自定义轨道 / 缩小拇指那一版滑块真的被组合出来了。
        composeRule.onNodeWithTag(BeansCompactSliderTag).assertIsDisplayed()

        assertEquals(
            "默认滑块本体仍然是 Material3 的 48dp 最小交互区（这就是「块太大」的根因）",
            dp(48),
            normalRow - normalLabel - dp(4),
        )
        assertEquals(
            "紧凑滑块本体必须是 30dp（LocalMinimumInteractiveComponentSize 收到 30dp）",
            dp(30),
            compactRow - compactLabel - dp(1),
        )
        assertTrue(
            "紧凑行必须真的更矮：compact=$compactRow px，normal=$normalRow px",
            compactRow < normalRow,
        )
        assertTrue(
            "整行至少矮掉滑块本体的 18dp 差值（标签只会更小，不会抵消）：${normalRow - compactRow} px",
            normalRow - compactRow >= dp(18),
        )
    }

    private companion object {
        const val NORMAL_TAG = "beans.slider.normal"
        const val COMPACT_TAG = "beans.slider.compact"
        const val NORMAL_LABEL = "默认形态"
        const val COMPACT_LABEL = "紧凑形态"
    }
}

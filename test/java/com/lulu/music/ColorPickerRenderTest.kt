package com.lulu.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.ui.BeansNavigator
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.components.BeansColorPickerBody
import com.lulu.music.ui.components.beansColorGrid
import com.lulu.music.ui.components.beansColorPickerAddTag
import com.lulu.music.ui.components.beansColorPickerValueTag
import com.lulu.music.ui.components.beansColorSwatchDescription
import com.lulu.music.ui.components.hexString
import com.lulu.music.ui.screens.BeansSettingsScreen
import com.lulu.music.ui.screens.beansSearchFieldTag
import com.lulu.music.ui.screens.settingsColorRingTag
import com.lulu.music.ui.theme.BeansTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 取色器的**渲染 / 交互**测试：三个模式真的画得出来，点色块真的会产出颜色。
 *
 * 与 `ColorPickerTest`（纯数学）分工：这里证明「接线通」—— 网格色块、预设色板、自定义色板、
 * ＋ 按钮都挂上了真实的回调，设置页每一行颜色都挂了色环入口。
 *
 * 面板本体（[BeansColorPickerBody]）直接渲染，不套 `BeansBottomSheet`：`ModalBottomSheet`
 * 的内容由独立窗口承载，本仓库的渲染测试一律不进去断言（见 `SongActionSheetRenderTest` 的说明）。
 * 也就是说「色环点开之后 sheet 里长什么样」由 [BeansColorPickerBody] 这一层覆盖，
 * sheet 外壳本身没有渲染断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class ColorPickerRenderTest {

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

    private fun bindSettingsToThisTest() {
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
    }

    // ------------------------------------------------------------------
    // A. 三个模式都在，切换真的换掉面板
    // ------------------------------------------------------------------

    @Test(timeout = 120_000L)
    fun pickerShowsThreeModesAndTheOpacitySlider() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost {
                BeansColorPickerBody(
                    initialHex = "#123456",
                    customSwatches = listOf("#ABCDEF"),
                    onColorChange = {},
                    onSaveSwatch = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 模式分段控件：网格 / 光谱 / 滑块
        listOf("网格", "光谱", "滑块").forEach { mode ->
            composeRule.onNodeWithText(mode).assertIsDisplayed()
        }
        // 不透明度 + 当前百分比 + 当前 hex
        composeRule.onNodeWithText("不透明度").assertIsDisplayed()
        composeRule.onNodeWithText("100%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(beansColorPickerValueTag)
            .assertTextContains("#123456", substring = true)
        // ＋ 按钮在
        composeRule.onNodeWithContentDescription(beansColorPickerAddTag).assertIsDisplayed()
    }

    @Test(timeout = 120_000L)
    fun switchingToSlidersReplacesTheGridWithRgbSliders() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost {
                BeansColorPickerBody(
                    initialHex = "#0A84FF",
                    customSwatches = emptyList(),
                    onColorChange = {},
                    onSaveSwatch = {},
                )
            }
        }
        composeRule.waitForIdle()
        // 默认是网格：R/G/B 三条滑杆不该存在
        assertTrue(
            "默认网格模式不该出现 RGB 滑杆",
            composeRule.onAllNodesWithText("红").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty(),
        )

        composeRule.onNodeWithText("滑块").performClick()
        composeRule.waitForIdle()

        listOf("红", "绿", "蓝").forEach { channel ->
            composeRule.onNodeWithText(channel).assertIsDisplayed()
        }
        // 三个通道的当前值（#0A84FF = 10 / 132 / 255）
        composeRule.onNodeWithText("10").assertIsDisplayed()
        composeRule.onNodeWithText("132").assertIsDisplayed()
        composeRule.onNodeWithText("255").assertIsDisplayed()
    }

    // ------------------------------------------------------------------
    // B. 点色块 / 色板真的产出颜色
    // ------------------------------------------------------------------

    @Test(timeout = 120_000L)
    fun clickingAGridCellProducesThatColour() {
        bindSettingsToThisTest()
        var picked by mutableStateOf("")

        composeRule.setContent {
            BeansTestHost {
                BeansColorPickerBody(
                    initialHex = "#000000",
                    customSwatches = emptyList(),
                    onColorChange = { picked = it },
                    onSaveSwatch = {},
                )
            }
        }
        composeRule.waitForIdle()

        val expected = beansColorGrid(columns = 12).first().hexString()
        composeRule.onNodeWithContentDescription(beansColorSwatchDescription(expected)).performClick()
        composeRule.waitForIdle()

        assertEquals("点网格里的第一格必须产出那一格的颜色", expected, picked)
        composeRule.onNodeWithContentDescription(beansColorPickerValueTag)
            .assertTextContains(expected, substring = true)
    }

    @Test(timeout = 120_000L)
    fun clickingAPresetOrCustomSwatchProducesThatColour() {
        bindSettingsToThisTest()
        var picked by mutableStateOf("")

        composeRule.setContent {
            BeansTestHost {
                BeansColorPickerBody(
                    initialHex = "#000000",
                    customSwatches = listOf("#ABCDEF"),
                    onColorChange = { picked = it },
                    onSaveSwatch = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(beansColorSwatchDescription("#30D158")).performClick()
        composeRule.waitForIdle()
        assertEquals("点预设色板必须产出它的颜色", "#30D158", picked)

        composeRule.onNodeWithContentDescription(beansColorSwatchDescription("#ABCDEF")).performClick()
        composeRule.waitForIdle()
        assertEquals("自定义色板同样要能点（它只是持久化过的预设）", "#ABCDEF", picked)
    }

    @Test(timeout = 120_000L)
    fun plusButtonSavesTheCurrentColour() {
        bindSettingsToThisTest()
        var saved by mutableStateOf("")

        composeRule.setContent {
            BeansTestHost {
                BeansColorPickerBody(
                    initialHex = "#112233",
                    customSwatches = emptyList(),
                    onColorChange = {},
                    onSaveSwatch = { saved = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(beansColorPickerAddTag).performClick()
        composeRule.waitForIdle()

        assertEquals("＋ 必须把当前颜色交出去（由调用方持久化）", "#112233", saved)
    }

    // ------------------------------------------------------------------
    // C. 设置页：每一行颜色都有色环入口（写死的预设列表已经被取色器取代）
    // ------------------------------------------------------------------

    @Test(timeout = 120_000L)
    fun everyColourRowInSettingsKeepsItsRingEntryAndItsResetActions() {
        bindSettingsToThisTest()

        composeRule.setContent {
            BeansTestHost { BeansSettingsScreen(onDismiss = {}) }
        }
        composeRule.waitForIdle()

        // 展开区默认就在「主题模式」上：色环入口必须每一行都有。
        // 参考图（Image A）的外观区只剩 5 行颜色：「通用背景色」那一行已经按参考图删除，
        // 所以这里是**精确**数目（比 >= 更强）—— 少一行或悄悄多一行都会失败。
        val rings = composeRule.onAllNodesWithContentDescription(settingsColorRingTag)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertEquals(
            "液态容器颜色 / 自定义强调色 / 背景颜色 / 注释文字颜色 / 主文字颜色 五行，各一颗色环",
            5,
            rings.size,
        )

        // 恢复类动作与副标题都还在（参考图：强调色那行是「恢复预设 / 使用预设主题」），
        // 但它们是**行下方的紧凑文字按钮**，不再是占满半行的大胶囊。
        composeRule.onNodeWithText("默认清透").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("使用预设主题").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("恢复预设").performScrollTo().assertIsDisplayed()
        listOf(
            "液态容器颜色",
            "自定义强调色",
            "主页背景色",
            "背景颜色 · 浅色模式",
            "注释文字颜色",
            "主文字颜色",
        ).forEach { title ->
            composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText("全 App 说明文字颜色").performScrollTo().assertIsDisplayed()

        // 搜索框仍然是这一页的入口（没有被颜色行的改动挤掉）
        composeRule.onNodeWithContentDescription(beansSearchFieldTag).performScrollTo().assertIsDisplayed()
    }
}

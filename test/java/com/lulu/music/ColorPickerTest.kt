package com.lulu.music

import androidx.compose.ui.graphics.Color
import com.lulu.music.data.prefs.beansAppendSwatch
import com.lulu.music.data.prefs.beansParseSwatchList
import com.lulu.music.data.prefs.normalizeSwatchHex
import com.lulu.music.ui.components.BeansPickerColor
import com.lulu.music.ui.components.alphaPercent
import com.lulu.music.ui.components.beansColorGrid
import com.lulu.music.ui.components.beansHsvToPickerColor
import com.lulu.music.ui.components.beansPercentLabel
import com.lulu.music.ui.components.beansPickerColorHsv
import com.lulu.music.ui.components.beansPickerColorOf
import com.lulu.music.ui.components.beansPickerColorWithAlphaPercent
import com.lulu.music.ui.components.beansPresetSwatches
import com.lulu.music.ui.components.beansSpectrumColor
import com.lulu.music.ui.components.hexString
import com.lulu.music.ui.theme.parseHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 取色器的**颜色数学**：纯函数测试，不需要渲染、不需要 Robolectric。
 *
 * 这些断言是「用户拖出来的颜色对不对」的唯一确定性证明：渲染测试读不到像素，
 * 只能断言语义树；颜色的正确性必须落在纯函数上。
 *
 * 重点覆盖三件事：
 *  1. 输出格式 —— 不透明时 `#RRGGBB`（6 位，与改造前的取值完全一致），
 *     调过不透明度时 `#AARRGGBB`（8 位）；
 *  2. 与 `ui/theme/BeansPalette.kt` 的 `parseHexColor` **互相往返**
 *     （设置页最终就是把这两个函数接在一起用）；
 *  3. 三种输入模式（网格 / 光谱 / 滑块用的 HSV↔RGB）确实产生不同的颜色，
 *     不是「点了没反应」。
 */
class ColorPickerTest {

    // ------------------------------------------------------------------
    // 1. 十六进制输出格式
    // ------------------------------------------------------------------

    @Test
    fun opaqueColoursSerializeAsSixDigitHex() {
        assertEquals("#000000", BeansPickerColor(0, 0, 0).hexString())
        assertEquals("#FFFFFF", BeansPickerColor(255, 255, 255).hexString())
        assertEquals("#0A84FF", BeansPickerColor(10, 132, 255).hexString())
        assertEquals("#30D158", BeansPickerColor(48, 209, 88).hexString())
        // 小写输入也要输出大写；宽度必须补零（不能出现 #A84FF 这种 5 位）
        assertEquals("#00FF00", BeansPickerColor(0, 255, 0).hexString())
        assertEquals("#0000FF", BeansPickerColor(0, 0, 255).hexString())
    }

    @Test
    fun translucentColoursSerializeAsEightDigitArgBHex() {
        // alpha 255 是「不透明」，必须仍然是 6 位（改造前的默认值都是 6 位）
        assertEquals("#112233", BeansPickerColor(0x11, 0x22, 0x33, 255).hexString())
        // 只要 alpha < 255 就写 8 位 #AARRGGBB
        assertEquals("#80112233", BeansPickerColor(0x11, 0x22, 0x33, 128).hexString())
        assertEquals("#33112233", BeansPickerColor(0x11, 0x22, 0x33, 51).hexString())
        assertEquals("#00FFFFFF", BeansPickerColor(255, 255, 255, 0).hexString())
        // 高位 alpha（>= 0x80）不能让 Int 变负数后输出成带减号的字符串
        assertEquals("#C0112233", BeansPickerColor(0x11, 0x22, 0x33, 0xC0).hexString())
        // alpha 满值时仍然是 6 位（前导零必须补上）
        assertEquals("#001122", BeansPickerColor(0x00, 0x11, 0x22, 255).hexString())
    }

    /**
     * 往返：取色器输出的 hex → `parseHexColor` → 回到同一个 RGBA。
     *
     * 这条是「设置真的会生效」的关键：设置页把 `hexString()` 的结果交给
     * `SettingsStore.setXxxHex(...)`，主题层再用 `parseHexColor` 解析它。
     */
    @Test
    fun hexOutputRoundTripsThroughParseHexColor() {
        val samples = listOf(
            BeansPickerColor(0, 0, 0),
            BeansPickerColor(255, 255, 255),
            BeansPickerColor(10, 132, 255),
            BeansPickerColor(0x33, 0x44, 0x55, 128),
            BeansPickerColor(0xAB, 0xCD, 0xEF, 1),
            BeansPickerColor(0x12, 0x34, 0x56, 0),
        )

        samples.forEach { color ->
            val hex = color.hexString()
            val parsed = parseHexColor(hex)
            assertTrue("$hex 必须能被 parseHexColor 解析", parsed != null)
            requireNotNull(parsed)
            assertEquals("$hex 的 R 通道", color.red, (parsed.red * 255f).roundToInt())
            assertEquals("$hex 的 G 通道", color.green, (parsed.green * 255f).roundToInt())
            assertEquals("$hex 的 B 通道", color.blue, (parsed.blue * 255f).roundToInt())
            assertEquals("$hex 的 alpha", color.alpha, (parsed.alpha * 255f).roundToInt())
            // 反过来：hex → BeansPickerColor → hex 必须回到同一串文本
            assertEquals("$hex 必须原样往返", hex, beansPickerColorOf(hex)?.hexString())
        }
    }

    @Test
    fun parseAcceptsBothSixAndEightDigitHexAndRejectsGarbage() {
        assertEquals(BeansPickerColor(0, 0, 0, 255), beansPickerColorOf("#000000"))
        assertEquals(BeansPickerColor(255, 255, 255, 255), beansPickerColorOf("ffffff"))
        assertEquals(BeansPickerColor(0x11, 0x22, 0x33, 0x80), beansPickerColorOf("#80112233"))

        assertNull("空串不是颜色", beansPickerColorOf(""))
        assertNull("null 不是颜色", beansPickerColorOf(null))
        assertNull("4 位不是合法格式", beansPickerColorOf("#1234"))
        assertNull("非十六进制字符必须被拒绝", beansPickerColorOf("#GGGGGG"))
        assertNull("7 位必须被拒绝", beansPickerColorOf("#1234567"))
    }

    // ------------------------------------------------------------------
    // 2. 不透明度
    // ------------------------------------------------------------------

    @Test
    fun alphaPercentagesAreRoundTrippedWithinOneStep() {
        assertEquals(0, BeansPickerColor(1, 2, 3, 0).alphaPercent())
        assertEquals(100, BeansPickerColor(1, 2, 3, 255).alphaPercent())
        assertEquals(50, BeansPickerColor(1, 2, 3, 128).alphaPercent())
        assertEquals(20, BeansPickerColor(1, 2, 3, 51).alphaPercent())

        listOf(0, 1, 13, 50, 99, 100).forEach { percent ->
            val color = beansPickerColorWithAlphaPercent(BeansPickerColor(1, 2, 3), percent)
            assertEquals("$percent% 必须原样回来", percent, color.alphaPercent())
            assertEquals("改不透明度不能动 R", 1, color.red)
            assertEquals("改不透明度不能动 G", 2, color.green)
            assertEquals("改不透明度不能动 B", 3, color.blue)
        }

        // 越界的百分比被夹紧，而不是产生非法 alpha
        assertEquals(0, beansPickerColorWithAlphaPercent(BeansPickerColor(1, 2, 3), -20).alpha)
        assertEquals(255, beansPickerColorWithAlphaPercent(BeansPickerColor(1, 2, 3), 500).alpha)
    }

    @Test
    fun percentLabelIsBounded() {
        assertEquals("0%", beansPercentLabel(0))
        assertEquals("42%", beansPercentLabel(42))
        assertEquals("100%", beansPercentLabel(100))
        assertEquals("100%", beansPercentLabel(9_999))
        assertEquals("0%", beansPercentLabel(-5))
    }

    // ------------------------------------------------------------------
    // 3. HSV ↔ RGB（光谱 / 滑块两条输入模式的数学）
    // ------------------------------------------------------------------

    @Test
    fun hsvPrimariesAndGreysAreExact() {
        assertEquals(BeansPickerColor(255, 0, 0), beansHsvToPickerColor(0f, 1f, 1f))
        assertEquals(BeansPickerColor(0, 255, 0), beansHsvToPickerColor(1f / 3f, 1f, 1f))
        assertEquals(BeansPickerColor(0, 0, 255), beansHsvToPickerColor(2f / 3f, 1f, 1f))
        assertEquals(BeansPickerColor(255, 255, 255), beansHsvToPickerColor(0.5f, 0f, 1f))
        assertEquals(BeansPickerColor(0, 0, 0), beansHsvToPickerColor(0.5f, 1f, 0f))
        // 色相环绕：1.0 等价于 0.0（红色），负数也要归一化
        assertEquals(BeansPickerColor(255, 0, 0), beansHsvToPickerColor(1f, 1f, 1f))
        assertEquals(BeansPickerColor(255, 0, 0), beansHsvToPickerColor(-1f, 1f, 1f))
        // alpha 原样带过去
        assertEquals(0x80, beansHsvToPickerColor(0f, 1f, 1f, 0x80).alpha)
    }

    @Test
    fun rgbToHsvReturnsTheHueThatProducedIt() {
        val hues = listOf(0f, 0.08f, 0.17f, 0.33f, 0.5f, 0.66f, 0.83f, 0.95f)
        hues.forEach { hue ->
            val color = beansHsvToPickerColor(hue, 1f, 1f)
            val (roundTripped, saturation, value) = beansPickerColorHsv(color)
            assertEquals("色相 $hue 必须能算回来", hue, roundTripped, 0.01f)
            assertEquals(1f, saturation, 0.01f)
            assertEquals(1f, value, 0.01f)
            assertEquals("往返之后 hex 必须一致", color.hexString(), beansHsvToPickerColor(roundTripped, saturation, value).hexString())
        }
        // 灰色没有色相信息，返回 0 而不是 NaN
        assertEquals(0f, beansPickerColorHsv(BeansPickerColor(128, 128, 128)).first, 0f)
    }

    // ------------------------------------------------------------------
    // 4. 网格 / 光谱 / 预设
    // ------------------------------------------------------------------

    @Test
    fun gridHasTheRequestedShapeAndDistinctColours() {
        val grid = beansColorGrid(columns = 12, rows = 5)

        assertEquals("12 列 × 5 行", 60, grid.size)
        assertEquals("网格里不能有重复色块（重复 = 有一块白给）", 60, grid.toSet().size)
        assertTrue("网格默认必须是不透明的", grid.all { it.alpha == 255 })
        assertEquals("每次生成的网格必须一致", grid, beansColorGrid(columns = 12, rows = 5))

        // 传了 alpha 时每一格都带上它（不透明度滑杆改了之后网格要跟着变）
        assertTrue(beansColorGrid(alpha = 64).all { it.alpha == 64 })

        // 第一行第一列是「最淡的红」，最后一行是深色：顺手钉住两端不是同一个颜色
        assertNotEquals(grid.first().hexString(), grid.last().hexString())
        assertEquals("第一行的 12 个色相必须互不相同", 12, grid.take(12).toSet().size)
    }

    @Test
    fun spectrumCoversWhiteToPureHue() {
        assertEquals("左上角必须是白色（饱和度 0）", BeansPickerColor(255, 255, 255), beansSpectrumColor(0.5f, 0f))
        assertEquals("左下角必须是纯红", BeansPickerColor(255, 0, 0), beansSpectrumColor(0f, 1f))
        assertEquals("右下角必须是纯蓝", BeansPickerColor(0, 0, 255), beansSpectrumColor(2f / 3f, 1f))
        assertEquals(0x40, beansSpectrumColor(0.2f, 0.5f, 0x40).alpha)
        // 越界的比例被夹紧（手指拖出边界不能算出非法颜色）
        assertEquals(BeansPickerColor(255, 255, 255), beansSpectrumColor(-1f, -1f))
        assertEquals(BeansPickerColor(255, 0, 0), beansSpectrumColor(1f, 2f))
    }

    @Test
    fun presetSwatchesAreTheFiveReferenceColours() {
        val presets = beansPresetSwatches()

        assertEquals("参考图是 5 个预设", 5, presets.size)
        assertEquals(listOf("#000000", "#0A84FF", "#30D158", "#FFD60A", "#FF3B30"), presets)
        assertTrue("每一个预设都必须能被解析", presets.all { beansPickerColorOf(it) != null })
        assertEquals("预设里必须有黑色", BeansPickerColor(0, 0, 0), beansPickerColorOf(presets[0]))
    }

    // ------------------------------------------------------------------
    // 5. 自定义色板的文本规则
    // ------------------------------------------------------------------

    @Test
    fun swatchHexIsNormalizedOrRejected() {
        assertEquals("#AABBCC", normalizeSwatchHex("#aabbcc"))
        assertEquals("#AABBCC", normalizeSwatchHex("aabbcc"))
        assertEquals("#80AABBCC", normalizeSwatchHex("#80aabbcc"))
        assertEquals("#AABBCC", normalizeSwatchHex("  #AABBCC  "))
        assertNull("长度不对必须拒绝", normalizeSwatchHex("#12345"))
        assertNull("非十六进制必须拒绝", normalizeSwatchHex("#ZZZZZZ"))
        assertNull("空串必须拒绝", normalizeSwatchHex(""))
    }

    @Test
    fun swatchListParsesAndAppendsWithDeduplicationAndCap() {
        assertEquals(emptyList<String>(), beansParseSwatchList(""))
        assertEquals(listOf("#112233", "#445566"), beansParseSwatchList("#112233,#445566"))
        assertEquals("无法解析的项必须丢掉（不能写进一个永远解析不出来的值）", listOf("#112233"), beansParseSwatchList("#112233,junk,"))

        // 追加：最近用的排最后
        assertEquals("#112233,#445566", beansAppendSwatch("#112233", "#445566"))
        // 同色不重复：已存在的先移除再加到末尾
        assertEquals("#445566,#112233", beansAppendSwatch("#112233,#445566", "#112233"))
        assertEquals("#112233", beansAppendSwatch("#112233", "#112233"))
        // 非法值原样返回（不破坏已有色板）
        assertEquals("#112233", beansAppendSwatch("#112233", "junk"))

        // 上限：满了之后丢掉最旧的
        val full = (1..12).joinToString(",") { "#0000" + it.toString().padStart(2, '0') }
        val overflow = beansAppendSwatch(full, "#FFFFFF")
        val parsed = beansParseSwatchList(overflow)
        assertEquals("上限是 12 个", 12, parsed.size)
        assertEquals("最新的必须在最后", "#FFFFFF", parsed.last())
        assertTrue("最旧的必须被丢掉", "#000001" !in parsed)

        // 自定义上限也被尊重
        var small = beansAppendSwatch("", "#000001", limit = 3)
        listOf("#000002", "#000003", "#000004").forEach { small = beansAppendSwatch(small, it, limit = 3) }
        assertEquals(3, beansParseSwatchList(small).size)
        assertTrue("超出上限时最旧的被丢掉", "#000001" !in beansParseSwatchList(small))
    }

    // ------------------------------------------------------------------
    // 6. 预览用的 Compose 颜色
    // ------------------------------------------------------------------

    @Test
    fun composeColorClampsOutOfRangeChannels() {
        val clamped = BeansPickerColor(300, -20, 128, 999).toComposeColor()

        assertEquals(Color(255, 0, 128, 255), clamped)
        assertEquals(Color(0x80112233), BeansPickerColor(0x11, 0x22, 0x33, 0x80).toComposeColor())
    }

    /**
     * 设置行的兜底色 → 取色器起点：色环显示什么颜色，打开取色器就从什么颜色开始
     * （不能因为「这一项还没设过值」而跳到纯黑）。
     */
    @Test
    fun composeColorBecomesThePickerStartingPoint() {
        assertEquals("#0A84FF", beansPickerColorOf(Color(0xFF0A84FF)).hexString())
        assertEquals(BeansPickerColor(0x0A, 0x84, 0xFF), beansPickerColorOf(Color(0xFF0A84FF)))
        assertEquals("#80112233", beansPickerColorOf(Color(0x80112233)).hexString())
        assertEquals(BeansPickerColor(255, 0, 0), beansPickerColorOf(Color(0xFFFF0000)))
    }
}

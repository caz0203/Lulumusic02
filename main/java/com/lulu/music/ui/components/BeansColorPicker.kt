package com.lulu.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.beansParseSwatchList
import com.lulu.music.ui.theme.BeansTheme
import com.lulu.music.ui.theme.parseHexColor
import kotlin.math.roundToInt

/**
 * 颜色选择器（参考图里那张带三个标签页的取色面板）。
 *
 * ## 为什么要自己写
 *
 * iOS 用的是系统 `ColorPicker`；Compose / Material3 没有对应物，改造前这里是
 * 「一排写死的预设色 + 一个 hex 输入框」。现在按参考图做真正的取色器：
 *
 *  - **网格**：12 色相 × 5 明度 / 饱和度，一眼可选；
 *  - **光谱**：横轴色相、纵轴饱和度的连续色域（点 / 拖都能取色）；
 *  - **滑块**：红 / 绿 / 蓝三条 0–255 滑杆；
 *  - **不透明度**：0–100% 滑杆，百分比数字画在带棋盘格的预览上，透明确实看得见；
 *  - **预设色板行**：黑 / 蓝 / 绿 / 黄 / 红 + 用户用 ＋ 存下来的自定义色板（持久化在
 *    `SettingsStore.customColorSwatches`）。
 *
 * ## 值是怎么产生的
 *
 * 内部只有一种颜色表示 [BeansPickerColor]（0–255 的 RGBA），所有输入模式都先换算成它，
 * 再由 [hexString] 输出：alpha 满值时输出 `#RRGGBB`，否则输出 `#AARRGGBB`
 * —— 与 `ui/theme/BeansPalette.kt` 的 `parseHexColor` 支持的两种格式完全对应。
 *
 * 颜色数学（[beansHsvToPickerColor] / [beansPickerColorHsv] / [beansColorGrid] /
 * [beansSpectrumColor] / [hexString] / [beansPickerColorOf]）全部是纯函数，单独有单元测试，
 * 因此「取色器算出来的颜色对不对」不依赖渲染。
 *
 * ## 消费方
 *
 * `ui/screens/SettingsScreen.kt` 的 `SettingsColorRow`：每一行颜色设置都用这个面板，
 * 取到的 hex 直接交给原有的 `setAccentHex` / `setLiquidTintHex` / `setHomeLight|DarkBackgroundHex` /
 * `setCustomBackgroundHex` / `setCommentColorHex` / `setLabelColorHex`。
 */

// ---------------------------------------------------------------------------------------------
// MARK: - 颜色模型与纯数学
// ---------------------------------------------------------------------------------------------

/** 取色器内部唯一的颜色表示：0–255 的 RGBA（等价于 `#AARRGGBB`）。 */
data class BeansPickerColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int = 255,
) {
    /** 0–255 的通道全部夹紧后转成 Compose 颜色（越界输入不能让预览崩）。 */
    fun toComposeColor(): Color = Color(
        red = red.coerceIn(0, 255),
        green = green.coerceIn(0, 255),
        blue = blue.coerceIn(0, 255),
        alpha = alpha.coerceIn(0, 255),
    )
}

/**
 * 传输用的十六进制文本：alpha 满值 → `#RRGGBB`（6 位），否则 → `#AARRGGBB`（8 位）。
 *
 * 6 位是不透明的常规写法（设置里的默认值都是这一种），只有在用户真的调低了不透明度时
 * 才写成 8 位 —— 这样「没动过不透明度」的颜色值和改造前完全一致。
 */
fun BeansPickerColor.hexString(): String {
    val a = alpha.coerceIn(0, 255)
    val r = red.coerceIn(0, 255)
    val g = green.coerceIn(0, 255)
    val b = blue.coerceIn(0, 255)
    val rgb = ((r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
    return if (a == 255) {
        "#" + rgb.toString(16).uppercase().padStart(6, '0')
    } else {
        "#" + ((a.toLong() shl 24) or rgb).toString(16).uppercase().padStart(8, '0')
    }
}

/** 不透明度的百分比（0–100），用于滑杆上的数字。 */
fun BeansPickerColor.alphaPercent(): Int = (alpha.coerceIn(0, 255) * 100f / 255f).roundToInt()

/** 按百分比改不透明度（0–100 → 0–255）。 */
fun beansPickerColorWithAlphaPercent(color: BeansPickerColor, percent: Int): BeansPickerColor =
    color.copy(alpha = (percent.coerceIn(0, 100) * 255f / 100f).roundToInt().coerceIn(0, 255))

/** `#RRGGBB` / `#AARRGGBB` → [BeansPickerColor]；解析不出来返回 null。 */
fun beansPickerColorOf(hex: String?): BeansPickerColor? {
    val color = parseHexColor(hex) ?: return null
    return beansPickerColorOf(color)
}

/**
 * Compose 颜色 → [BeansPickerColor]。
 *
 * 用于「这一项还没设过颜色」时给取色器一个**可见的起点**：设置行里显示的兜底色（主题强调色 /
 * 背景色）就是用户看到的那颗色环，打开取色器时不该跳到纯黑。
 */
fun beansPickerColorOf(color: Color): BeansPickerColor = BeansPickerColor(
    red = (color.red * 255f).roundToInt().coerceIn(0, 255),
    green = (color.green * 255f).roundToInt().coerceIn(0, 255),
    blue = (color.blue * 255f).roundToInt().coerceIn(0, 255),
    alpha = (color.alpha * 255f).roundToInt().coerceIn(0, 255),
)

/** HSV（hue 0–1，saturation / value 0–1）→ RGBA。 */
fun beansHsvToPickerColor(
    hue: Float,
    saturation: Float,
    value: Float,
    alpha: Int = 255,
): BeansPickerColor {
    val h = ((hue % 1f) + 1f) % 1f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val scaled = h * 6f
    val sector = scaled.toInt() % 6
    val f = scaled - scaled.toInt()
    val p = v * (1f - s)
    val q = v * (1f - s * f)
    val t = v * (1f - s * (1f - f))
    val rgb = when (sector) {
        0 -> Triple(v, t, p)
        1 -> Triple(q, v, p)
        2 -> Triple(p, v, t)
        3 -> Triple(p, q, v)
        4 -> Triple(t, p, v)
        else -> Triple(v, p, q)
    }
    fun channel(raw: Float): Int = (raw * 255f).roundToInt().coerceIn(0, 255)
    return BeansPickerColor(
        red = channel(rgb.first),
        green = channel(rgb.second),
        blue = channel(rgb.third),
        alpha = alpha.coerceIn(0, 255),
    )
}

/** RGBA → HSV（hue 0–1，saturation / value 0–1）。灰色时 hue 返回 0。 */
fun beansPickerColorHsv(color: BeansPickerColor): Triple<Float, Float, Float> {
    val r = color.red.coerceIn(0, 255) / 255f
    val g = color.green.coerceIn(0, 255) / 255f
    val b = color.blue.coerceIn(0, 255) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta <= 0.0001f -> 0f
        max == r -> (((g - b) / delta) / 6f + 1f) % 1f
        max == g -> (((b - r) / delta) + 2f) / 6f
        else -> (((r - g) / delta) + 4f) / 6f
    }
    val saturation = if (max <= 0f) 0f else delta / max
    return Triple(hue, saturation, max)
}

/**
 * 网格模式的五行「明度 / 饱和度」组合（第一行是淡彩，最后一行是深色）。
 *
 * 值全部写死而不是现算：网格必须是**稳定**的（用户会记住某个色块的位置），
 * 也便于测试逐项断言。
 */
private val BeansGridRows: List<Pair<Float, Float>> = listOf(
    1.00f to 0.16f,
    0.98f to 0.42f,
    0.92f to 0.72f,
    0.80f to 1.00f,
    0.55f to 1.00f,
)

/** 网格模式的颜色序列（行优先），默认 12 列 × 5 行。 */
fun beansColorGrid(
    columns: Int = 12,
    rows: Int = BeansGridRows.size,
    alpha: Int = 255,
): List<BeansPickerColor> {
    val cols = columns.coerceAtLeast(1)
    val rowCount = rows.coerceAtLeast(1)
    return buildList {
        for (row in 0 until rowCount) {
            val (value, saturation) = BeansGridRows[row % BeansGridRows.size]
            for (column in 0 until cols) {
                add(beansHsvToPickerColor(column.toFloat() / cols, saturation, value, alpha))
            }
        }
    }
}

/** 光谱取色：横轴色相、纵轴饱和度（顶部白 → 底部纯色）。 */
fun beansSpectrumColor(xFraction: Float, yFraction: Float, alpha: Int = 255): BeansPickerColor =
    beansHsvToPickerColor(
        hue = xFraction.coerceIn(0f, 1f),
        saturation = yFraction.coerceIn(0f, 1f),
        value = 1f,
        alpha = alpha,
    )

/** 固定预设色板：黑 / 蓝 / 绿 / 黄 / 红（参考图的顺序）。 */
fun beansPresetSwatches(): List<String> =
    listOf("#000000", "#0A84FF", "#30D158", "#FFD60A", "#FF3B30")

/** 不透明度 / 通道值的显示文本（百分比用整数，通道用 0–255）。 */
fun beansPercentLabel(percent: Int): String = "${percent.coerceIn(0, 100)}%"

/** 棋盘格（透明区域的底纹）：浅灰与深灰相间 [cell] 大小的方块。 */
@Composable
fun Modifier.beansCheckerboard(
    cell: Dp = 6.dp,
    light: Color = Color(0xFFFFFFFF),
    dark: Color = Color(0xFFD8D8DC),
): Modifier {
    val cellPx = with(LocalDensity.current) { cell.toPx() }.coerceAtLeast(1f)
    return drawBehind {
        drawRect(color = light)
        var y = 0f
        var row = 0
        while (y < size.height) {
            val step = if (row % 2 == 0) cellPx * 2f else 0f
            var x = -cellPx + step
            while (x < size.width) {
                drawRect(
                    color = dark,
                    topLeft = Offset(x.coerceAtLeast(0f), y),
                    size = Size(
                        width = cellPx.coerceAtMost(size.width),
                        height = cellPx.coerceAtMost(size.height - y),
                    ),
                )
                x += cellPx * 2f
            }
            y += cellPx
            row++
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 面板（三个模式 + 不透明度 + 预设行 + ＋）
// ---------------------------------------------------------------------------------------------

/** 取色器的三种输入模式（参考图：网格 / 光谱 / 滑块）。 */
enum class BeansColorPickerMode(val titleZh: String, val titleEn: String) {
    GRID("网格", "Grid"),
    SPECTRUM("光谱", "Spectrum"),
    SLIDERS("滑块", "Sliders"),
}

/**
 * 取色面板本体（不含 sheet 外壳，便于直接渲染测试）。
 *
 * @param initialHex 打开时的颜色（`#RRGGBB` 或 `#AARRGGBB`；解析不出来时按不透明黑色处理）
 * @param customSwatches 自定义色板原文（逗号分隔，来自 `SettingsStore.customColorSwatches`）
 * @param onColorChange 颜色确定变化时回调（网格 / 光谱 / 色板 / 滑杆松手 / 完成），参数是 hex
 * @param onSaveSwatch 点 ＋ 时回调，参数是当前颜色的 hex
 * @param onDone 非 null 时在面板底部画一颗「完成」按钮（先回调当前颜色再 [onDone]）
 * @param doneLabel 「完成」按钮的文案（调用方负责本地化）
 */
@Composable
fun BeansColorPickerBody(
    initialHex: String,
    customSwatches: List<String>,
    onColorChange: (String) -> Unit,
    onSaveSwatch: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
    doneLabel: String = "",
    style: BeansUIStyle = BeansUIStyle.LIQUID,
) {
    val colors = BeansTheme.colors
    val initial = remember(initialHex) { beansPickerColorOf(initialHex) ?: BeansPickerColor(0, 0, 0) }
    var color by remember(initial) { mutableStateOf(initial) }
    // 色相单独存一份：饱和度 / 明度变成 0 之后 RGB 里已经没有色相信息，靠它才不会「跳回红色」。
    var hue by remember(initial) { mutableStateOf(beansPickerColorHsv(initial).first) }
    var mode by rememberSaveable { mutableStateOf(BeansColorPickerMode.GRID) }

    /** 直接落地的改动（离散点击）。 */
    fun publish(next: BeansPickerColor) {
        color = next
        onColorChange(next.hexString())
    }

    /** 只更新预览的改动（连续拖动），松手时再由调用方 [onColorChange]。 */
    fun preview(next: BeansPickerColor) {
        color = next
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- 模式分段控件（网格 / 光谱 / 滑块） ----
        BeansColorPickerTabs(
            selected = mode,
            onSelect = {
                mode = it
                BeansHaptics.select()
            },
        )

        // ---- 当前颜色 + 不透明度 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 带棋盘格的预览：半透明时能直接看出透明程度
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .beansCheckerboard(cell = 5.dp)
                    .border(0.8.dp, colors.label.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(1.dp)
                        .clip(CircleShape)
                        .background(color.toComposeColor()),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = beansLocalized("不透明度", "Opacity"),
                    color = colors.label,
                    fontSize = 14.sp,
                )
                Text(
                    text = beansPercentLabel(color.alphaPercent()),
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = color.hexString(),
                color = colors.comment,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.semantics {
                    contentDescription = beansColorPickerValueTag
                },
            )
        }

        Slider(
            value = color.alphaPercent().toFloat(),
            onValueChange = { preview(beansPickerColorWithAlphaPercent(color, it.roundToInt())) },
            onValueChangeFinished = { onColorChange(color.hexString()) },
            valueRange = 0f..100f,
            steps = 19,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.comment.copy(alpha = 0.3f),
            ),
        )

        // ---- 三种输入模式 ----
        when (mode) {
            BeansColorPickerMode.GRID -> BeansColorGrid(
                alpha = color.alpha,
                onPick = { publish(it) },
            )

            BeansColorPickerMode.SPECTRUM -> BeansColorSpectrum(
                hue = hue,
                alpha = color.alpha,
                onPick = { next, nextHue ->
                    hue = nextHue
                    publish(next)
                },
            )

            BeansColorPickerMode.SLIDERS -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BeansChannelSlider(
                    label = beansLocalized("红", "Red"),
                    value = color.red,
                    onValueChange = { preview(color.copy(red = it)) },
                    onValueChangeFinished = { onColorChange(color.hexString()) },
                )
                BeansChannelSlider(
                    label = beansLocalized("绿", "Green"),
                    value = color.green,
                    onValueChange = { preview(color.copy(green = it)) },
                    onValueChangeFinished = { onColorChange(color.hexString()) },
                )
                BeansChannelSlider(
                    label = beansLocalized("蓝", "Blue"),
                    value = color.blue,
                    onValueChange = { preview(color.copy(blue = it)) },
                    onValueChangeFinished = { onColorChange(color.hexString()) },
                )
            }
        }

        // ---- 预设色板行 + ＋（保存当前颜色） ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val swatches = beansPresetSwatches() + customSwatches
            // 色板本身横滑（自定义色板存满 12 个也不会把 ＋ 挤出屏幕），＋ 固定在右侧。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                swatches.forEach { swatch ->
                    val isSelected = swatch.equals(color.hexString(), ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(beansPickerColorOf(swatch)?.toComposeColor() ?: Color.Gray)
                            .border(
                                width = if (isSelected) 2.dp else 0.8.dp,
                                color = if (isSelected) colors.label else colors.label.copy(alpha = 0.2f),
                                shape = CircleShape,
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                BeansHaptics.select()
                                publish(beansPickerColorOf(swatch) ?: color)
                            }
                            .semantics { contentDescription = beansColorSwatchDescription(swatch) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .border(0.8.dp, colors.label.copy(alpha = 0.25f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        BeansHaptics.tap()
                        onSaveSwatch(color.hexString())
                    }
                    .semantics { contentDescription = beansColorPickerAddTag },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = colors.label,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (onDone != null) {
            BeansGlassButton(
                title = doneLabel,
                onClick = {
                    onColorChange(color.hexString())
                    onDone()
                },
                prominent = true,
                style = style,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 取色 sheet：`BeansBottomSheet` 外壳 + 标题 + [BeansColorPickerBody] + 「完成」。
 *
 * 直接把 `onColorChange` 接到设置项上（取值即生效），「完成」只是关掉面板。
 */
@Composable
fun BeansColorPickerSheet(
    title: String,
    initialHex: String,
    customSwatches: String,
    onColorChange: (String) -> Unit,
    onSaveSwatch: (String) -> Unit,
    onDismiss: () -> Unit,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
) {
    val colors = BeansTheme.colors
    val swatches = remember(customSwatches) { beansParseSwatchList(customSwatches) }

    BeansBottomSheet(
        onDismissRequest = onDismiss,
        detents = listOf(BeansDetent.Height(600.dp)),
        style = style,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                color = colors.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            BeansColorPickerBody(
                initialHex = initialHex,
                customSwatches = swatches,
                onColorChange = onColorChange,
                onSaveSwatch = onSaveSwatch,
                onDone = onDismiss,
                doneLabel = beansLocalized("完成", "Done"),
                style = style,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 内部件
// ---------------------------------------------------------------------------------------------

/** 语义标签：当前颜色文本（渲染测试用它读值，不依赖像素）。 */
const val beansColorPickerValueTag: String = "取色器当前颜色"

/** 语义标签：＋ 按钮（保存当前颜色为自定义色板）。 */
const val beansColorPickerAddTag: String = "保存为自定义色板"

/** 单个色块 / 色板的语义标签：`色板 #RRGGBB`（无障碍与渲染测试共用）。 */
fun beansColorSwatchDescription(hex: String): String = "色板 $hex"

/** 模式分段控件（网格 / 光谱 / 滑块）。 */
@Composable
private fun BeansColorPickerTabs(
    selected: BeansColorPickerMode,
    onSelect: (BeansColorPickerMode) -> Unit,
) {
    val colors = BeansTheme.colors
    BeansCapsule(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(4.dp),
    ) {
        BeansColorPickerMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(BeansCapsuleShape)
                    .background(if (isSelected) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { if (!isSelected) onSelect(mode) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = beansLocalized(mode.titleZh, mode.titleEn),
                    color = if (isSelected) colors.accent else colors.comment,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 网格模式：12 列 × 5 行色块。 */
@Composable
private fun BeansColorGrid(
    alpha: Int,
    onPick: (BeansPickerColor) -> Unit,
) {
    val colors = BeansTheme.colors
    val columns = 12
    val cells = remember(alpha) { beansColorGrid(columns = columns, alpha = alpha) }
    val rowCount = (cells.size + columns - 1) / columns

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(rowCount) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(columns) { column ->
                    val index = row * columns + column
                    val cell = cells.getOrNull(index)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(cell?.toComposeColor() ?: Color.Transparent)
                            .border(
                                width = 0.5.dp,
                                color = colors.label.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { cell?.let { onPick(it) } }
                            .semantics {
                                contentDescription = beansColorSwatchDescription(cell?.hexString().orEmpty())
                            },
                    )
                }
            }
        }
    }
}

/**
 * 光谱模式：横轴色相、纵轴饱和度的连续色域，点击或拖动都能取色。
 *
 * 绘制方式：先按列画一条条纯色竖线（等价于色相渐变），再叠一层自上而下的白色渐变
 * （顶部饱和度 0 = 白，底部饱和度 1 = 纯色）。不用 `Brush.sweepGradient`，
 * 因为白饱和度的叠加方向与它不一致，分两步画出来就是所见即所得。
 */
@Composable
private fun BeansColorSpectrum(
    hue: Float,
    alpha: Int,
    onPick: (BeansPickerColor, Float) -> Unit,
) {
    val colors = BeansTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val x = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val y = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                    onPick(beansSpectrumColor(x, y, alpha), x)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val x = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val y = (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                    onPick(beansSpectrumColor(x, y, alpha), x)
                }
            },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val columns = size.width.toInt().coerceAtLeast(1)
            for (column in 0 until columns) {
                val fraction = column.toFloat() / columns.toFloat()
                drawRect(
                    color = beansHsvToPickerColor(fraction, 1f, 1f).toComposeColor(),
                    topLeft = Offset(column.toFloat(), 0f),
                    size = Size(1f, size.height),
                )
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                ),
                size = size,
            )
            // 当前色相的位置指示（一条竖线）
            val x = hue.coerceIn(0f, 1f) * size.width
            drawRect(
                color = colors.label.copy(alpha = 0.55f),
                topLeft = Offset((x - 1f).coerceIn(0f, (size.width - 2f).coerceAtLeast(0f)), 0f),
                size = Size(2f, size.height),
            )
        }
    }
}

/** 滑块模式的单通道滑杆（0–255）。 */
@Composable
private fun BeansChannelSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val colors = BeansTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = colors.label,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${value.coerceIn(0, 255)}",
                color = colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value.coerceIn(0, 255).toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 255)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.comment.copy(alpha = 0.3f),
            ),
        )
    }
}

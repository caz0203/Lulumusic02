package com.lulu.music.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.ui.theme.BeansColors
import com.lulu.music.ui.theme.BeansTheme
import com.lulu.music.ui.theme.parseHexColor
import java.io.File
import kotlinx.coroutines.delay

/** 胶囊形状（对应 SwiftUI `Capsule()`）。 */
val BeansCapsuleShape: Shape = RoundedCornerShape(percent = 50)

/** 与 iOS 默认卡片圆角一致的圆角矩形。 */
val BeansCardShape: Shape = RoundedCornerShape(24.dp)

// ---------------------------------------------------------------------------------------------
// MARK: - 全局 UI 样式的渲染参数（四种样式必须**真的**画出不同结果）
// ---------------------------------------------------------------------------------------------

/**
 * 一种 [BeansUIStyle] 对应的全部几何 / 材质参数。
 *
 * 为什么要把它抽成纯数据：`SettingsScreen` 用 `BeansUIStyle.entries` 渲染分段控件，
 * 所以「加一个枚举值」是零成本的 —— 但如果渲染层没有分支，用户点完发现毫无变化，
 * 就又是一个「主题自定义无效」。把差异集中在这个函数里，既让四种样式真的有区别，
 * 也让测试可以直接断言「四个值两两不同」而不必截图。
 *
 * @param fillAlphaScale 玻璃底色不透明度倍率（磨砂玻璃更低 → 更透）
 * @param sheenAlphaScale 顶部高光倍率
 * @param hairlineAlphaScale 发丝描边倍率
 * @param frostBlurRadius 额外模糊半径；Compose 没有 backdrop-filter，所以它作用在
 *   **玻璃背后的层**（壁纸与环境色斑）上，这正是「磨砂」在 Android 上的可落地形态
 * @param cornerCap 圆角上限；null = 保留调用方传入的圆角
 * @param cardPadding 卡片默认内边距
 * @param rowVerticalPadding 列表行默认上下内边距（紧凑样式收紧行高）
 * @param ambientGlowScale 背景环境色斑的强度倍率（0 = 不画）
 * @param frostVeilAlpha 背景上的「磨砂薄雾」叠加层不透明度
 * @param shadowRadius 卡片阴影半径
 */
data class BeansGlassSpec(
    val fillAlphaScale: Float,
    val sheenAlphaScale: Float,
    val hairlineAlphaScale: Float,
    val frostBlurRadius: Dp,
    val cornerCap: Dp?,
    val cardPadding: Dp,
    val rowVerticalPadding: Dp,
    val ambientGlowScale: Float,
    val frostVeilAlpha: Float,
    val shadowRadius: Dp,
)

/**
 * [BeansUIStyle] → 渲染参数。
 *
 * `LIQUID` / `NATIVE_CLEAN` 的取值刻意等于改造前的行为（原生简洁：圆角封顶 18dp、内边距 13dp、
 * 高光与描边压到 35% / 55%、不画环境色斑），保证老用户升级后观感不变。
 */
fun beansGlassSpec(style: BeansUIStyle): BeansGlassSpec = when (style) {
    BeansUIStyle.LIQUID -> BeansGlassSpec(
        fillAlphaScale = 1f,
        sheenAlphaScale = 1f,
        hairlineAlphaScale = 1f,
        frostBlurRadius = 0.dp,
        cornerCap = null,
        cardPadding = 16.dp,
        rowVerticalPadding = 10.dp,
        ambientGlowScale = 1f,
        frostVeilAlpha = 0f,
        shadowRadius = 9.dp,
    )

    BeansUIStyle.FROSTED -> BeansGlassSpec(
        fillAlphaScale = 0.52f,
        sheenAlphaScale = 1.35f,
        hairlineAlphaScale = 1.15f,
        frostBlurRadius = 18.dp,
        cornerCap = null,
        cardPadding = 16.dp,
        rowVerticalPadding = 10.dp,
        ambientGlowScale = 1.5f,
        frostVeilAlpha = 0.10f,
        shadowRadius = 12.dp,
    )

    BeansUIStyle.COMPACT -> BeansGlassSpec(
        fillAlphaScale = 0.90f,
        sheenAlphaScale = 0.75f,
        hairlineAlphaScale = 1f,
        frostBlurRadius = 0.dp,
        cornerCap = 16.dp,
        cardPadding = 11.dp,
        rowVerticalPadding = 7.dp,
        ambientGlowScale = 0.6f,
        frostVeilAlpha = 0f,
        shadowRadius = 4.dp,
    )

    BeansUIStyle.NATIVE_CLEAN -> BeansGlassSpec(
        fillAlphaScale = 1f,
        sheenAlphaScale = 0.36f,
        hairlineAlphaScale = 0.55f,
        frostBlurRadius = 0.dp,
        cornerCap = 18.dp,
        cardPadding = 13.dp,
        rowVerticalPadding = 10.dp,
        ambientGlowScale = 0f,
        frostVeilAlpha = 0f,
        shadowRadius = 9.dp,
    )
}

/**
 * 玻璃底色的纯函数形态：先按样式压低不透明度，再叠加「液态容器颜色」。
 *
 * 「液态容器颜色」只作用于液态系（`LIQUID` / `FROSTED`）—— 对齐参考图里的说明
 * 「只影响自定义液态容器，系统原生玻璃不变」。
 */
fun beansGlassFill(
    colors: BeansColors,
    style: BeansUIStyle,
    liquidTint: Color? = null,
): Color {
    val spec = beansGlassSpec(style)
    val tintable = style == BeansUIStyle.LIQUID || style == BeansUIStyle.FROSTED
    val base = colors.glassFill
    val tinted = if (liquidTint != null && tintable) {
        liquidTint.copy(alpha = base.alpha)
    } else {
        base
    }
    return tinted.copy(alpha = (tinted.alpha * spec.fillAlphaScale).coerceIn(0f, 1f))
}

// ---------------------------------------------------------------------------------------------
// MARK: - 模糊（API 31+ 才有 RenderEffect，低版本静默降级为不模糊）
// ---------------------------------------------------------------------------------------------

/**
 * 真实模糊只存在于 API 31+（`Modifier.blur` 底层是 `RenderEffect`）。
 * API 24–30 上 `Modifier.blur` 只是空操作并打印警告，这里显式短路，保证低版本既不崩溃也不报错。
 */
fun Modifier.beansBlur(
    radius: Dp,
    edgeTreatment: BlurredEdgeTreatment = BlurredEdgeTreatment.Unbounded,
): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0.dp) {
        this.blur(radius, edgeTreatment)
    } else {
        this
    }

// ---------------------------------------------------------------------------------------------
// MARK: - 背景氛围（液态玻璃需要有可采样的动态内容）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `GlassBackdrop`（+ `LinearGradient.beansBackdrop`）。
 *
 * 全屏背景层：按「壁纸 → 自定义背景色 → 默认氛围渐变」的优先级绘制，
 * 液态样式下再叠加两枚大半径色斑，让上层的玻璃材质始终有内容可采样。
 *
 * 与 iOS 的差异：iOS 从 `ThemeStore` 直接读壁纸 / 背景色 / 同步开关，
 * Android 版本按「数据只走参数」的约定把这些都做成显式参数（保持可预览、可测试）。
 *
 * @param customColor 自定义背景色（null 使用默认氛围渐变）
 * @param wallpaperPath 壁纸文件路径；非空且 [backgroundSyncAll]/[homeMode] 允许时优先于 [customColor]
 * @param homeMode 主页模式：即使「同步到全部页面」关闭，也始终显示壁纸/背景色（仅主页标签传 true）
 * @param ignoreCustomBackground 详情页使用原生氛围背景，不跟随全局壁纸同步
 * @param backgroundSyncAll 「同步到全部页面」开关
 * @param wallpaperBlur 主页壁纸额外模糊半径
 * @param style 全局 UI 样式；`NATIVE_CLEAN` 且无自定义背景时使用纯色底，
 *   `FROSTED` 额外加重壁纸/色斑模糊并叠一层薄雾，`COMPACT` 弱化环境色斑
 * @param ambientGlow 是否绘制环境色斑（对应 iOS `uiStyle != .nativeClean`）
 */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    customColor: Color? = null,
    wallpaperPath: String? = null,
    homeMode: Boolean = false,
    ignoreCustomBackground: Boolean = false,
    backgroundSyncAll: Boolean = true,
    wallpaperBlur: Dp = 0.dp,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    ambientGlow: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = BeansTheme.colors
    val spec = beansGlassSpec(style)

    // 当前页面是否启用自定义背景：同步开启时全部页面生效，关闭时仅主页生效
    val showCustomBackground = !ignoreCustomBackground && (backgroundSyncAll || homeMode)
    val activeWallpaper = wallpaperPath?.takeIf { showCustomBackground && it.isNotBlank() }
    val activeColor = customColor?.takeIf { showCustomBackground }

    // Apple 简洁样式在没有任何自定义背景时使用纯色底（对应 iOS `Color(UIColor.systemBackground)`）
    val nativeFlat = style == BeansUIStyle.NATIVE_CLEAN &&
        (!showCustomBackground || (activeWallpaper == null && activeColor == null))

    Box(modifier = modifier.fillMaxSize()) {
        when {
            nativeFlat -> {
                Box(Modifier.matchParentSize().background(colors.background))
            }

            activeWallpaper != null -> {
                BeansWallpaperImage(
                    path = activeWallpaper,
                    // 磨砂玻璃：壁纸额外吃一层重模糊 —— 这是「磨砂」在 Android 上唯一能真正落地的模糊
                    // （Compose 没有 backdrop-filter，只能模糊自己图层里的内容）。
                    blurRadius = wallpaperBlur + spec.frostBlurRadius,
                    modifier = Modifier.matchParentSize(),
                )
                // 背景图上叠加的可读性遮罩：浅色模式几乎不压暗，深色模式适度压暗
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            if (colors.isDark) {
                                listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.55f))
                            } else {
                                listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.18f))
                            }
                        )
                    )
                )
            }

            activeColor != null -> {
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            listOf(activeColor.copy(alpha = 0.9f), activeColor.copy(alpha = 0.55f))
                        )
                    )
                )
            }

            else -> {
                // LinearGradient.beansBackdrop：背景色 → 背景色 72% 不透明
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            listOf(colors.background, colors.background.copy(alpha = 0.72f))
                        )
                    )
                )
            }
        }

        // 磨砂玻璃的「薄雾」：压平背景对比度，让上层半透明容器真的像毛玻璃。
        if (spec.frostVeilAlpha > 0f) {
            Box(
                Modifier.matchParentSize().background(
                    Color.White.copy(alpha = spec.frostVeilAlpha * if (colors.isDark) 0.45f else 1f),
                )
            )
        }

        if (spec.ambientGlowScale > 0f && ambientGlow) {
            BeansAmbientGlow(colors, spec)
        }

        content()
    }
}

/**
 * 环境色斑（对应 iOS `GlassBackdrop` 里两枚 `Circle().blur(...)`）。
 * iOS 的 ZStack 子视图默认居中、`.offset` 相对中心；这里用 `Alignment.Center` + `offset` 还原同一坐标系。
 *
 * [spec] 决定强度与模糊半径：磨砂玻璃更重（1.5 倍强度 + 1.6 倍模糊半径），紧凑淡雅更轻（0.6 倍）。
 */
@Composable
private fun BoxScope.BeansAmbientGlow(colors: BeansColors, spec: BeansGlassSpec) {
    val glowScale = spec.ambientGlowScale
    val blurScale = if (spec.frostBlurRadius > 0.dp) 1.6f else 1f
    Box(
        Modifier
            .align(Alignment.Center)
            .offset(x = 150.dp, y = (-300).dp)
            .size(340.dp)
            .beansBlur(100.dp * blurScale)
            .background(colors.accent.copy(alpha = (0.14f * glowScale).coerceAtMost(1f)), CircleShape)
    )
    Box(
        Modifier
            .align(Alignment.Center)
            .offset(x = (-160).dp, y = 340.dp)
            .size(300.dp)
            .beansBlur(110.dp * blurScale)
            .background(colors.sage.copy(alpha = (0.12f * glowScale).coerceAtMost(1f)), CircleShape)
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 背景墙纸（上传图片：固定全屏布局，不影响其他 UI 尺寸；小图轻度柔化避免像素感）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `WallpaperImage`。
 *
 * 布局尺寸完全由外层容器决定（`matchParentSize`），图片比例与 UI 布局完全隔离。
 * 小于约 700x700 视为小图：放大时轻度模糊柔化，避免满屏马赛克（与 iOS 的 `isSmall` 判定一致）。
 */
@Composable
fun BeansWallpaperImage(
    path: String?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 0.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (path.isNullOrBlank()) return

    var small by remember(path) { mutableStateOf(false) }
    val resolvedBlur = maxOf(if (small) 5.dp else 0.dp, blurRadius)

    Box(modifier = modifier) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier
                .matchParentSize()
                .beansBlur(resolvedBlur, BlurredEdgeTreatment.Rectangle),
            onSuccess = { state ->
                val drawable = state.result.drawable
                small = drawable.intrinsicWidth * drawable.intrinsicHeight < 480_000
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 卡片阴影（浅色立体阴影 / 卡片分层质感）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `View.beansCardShadow(radius:y:)`。
 *
 * 浅色模式 `black 0.08`，深色模式 `black 0.35`。
 *
 * 注意（Android 平台差异）：
 * 1. Compose 的阴影由 elevation 驱动、由系统绘制，**不支持只偏移阴影**；iOS 的 `y` 在这里折算进
 *    elevation（`elevation = radius + y * 0.4`），视觉上仍是「越大越往下压」。
 * 2. 本 modifier 把阴影画在自身内容之下，因此必须放在 `background` / `beansGlass` **之前** 的链上：
 *    `Modifier.beansCardShadow(...).beansGlass(...)`。
 */
@Composable
fun Modifier.beansCardShadow(
    radius: Dp = 9.dp,
    y: Dp = 3.dp,
    shape: Shape = BeansCardShape,
): Modifier {
    val colors = BeansTheme.colors
    val shadowColor =
        if (colors.isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.08f)
    val elevation = (radius.value + y.value * 0.4f).dp
    return this.shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = shadowColor,
        spotColor = shadowColor,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 全局容器（跟随全局 UI 样式）
// ---------------------------------------------------------------------------------------------

/**
 * iOS `BeansGlass` 的 Compose 形态：既是背景提供者（[Modifier.beansGlass]），
 * 也是带内容的容器（本函数）。
 *
 * iOS 里 `isLiquid` 对 `.liquid` 和 `.nativeClean` 都返回 true，`switch` 中的 `.compact` / `.nativeClean`
 * 分支实际是死代码；Android 侧四种 [BeansUIStyle] 都走同一条「半透明玻璃底色」路径，
 * 差异集中在 [beansGlassSpec]（填充不透明度 / 高光 / 描边 / 模糊 / 几何）。
 *
 * 说明：iOS 的 `.ultraThinMaterial` 是实时背景模糊；Compose 没有 backdrop-filter，
 * API 31+ 也只能模糊自身图层，所以用 `colors.glassFill` 打底 + 顶部高光渐变 + 发丝描边来近似
 * （iOS 之所以新增 `beansGlassFill`，也正是因为纯透明玻璃会渲染成灰糊块）。
 */
@Composable
fun BeansGlass(
    modifier: Modifier = Modifier,
    shape: Shape = BeansCardShape,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    forceLiquid: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.beansGlass(shape = shape, style = style, forceLiquid = forceLiquid),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * 玻璃底色的 `Modifier` 形态：半透明基底填充 + 顶部高光 + 发丝描边。
 *
 * 四种 [BeansUIStyle] 在这里产生**真实差异**（参数见 [beansGlassSpec]）：
 *  - `LIQUID`：原样（`colors.glassFill` + 常规高光 / 描边）；
 *  - `FROSTED`：底色不透明度 ×0.52、高光加强，背景层的重模糊由 [GlassBackdrop] 负责；
 *  - `COMPACT`：底色略降、高光收敛（几何收紧在 [GlassCard] / 列表行里）；
 *  - `NATIVE_CLEAN`：低存在感平面底色（高光 36%、描边 55%）。
 *
 * @param forceLiquid iOS `forceLiquid`：即使全局样式不是液态也强制玻璃质感（prominent 按钮用）
 * @param liquidTint 液态容器颜色覆盖；不传时读取 `SettingsStore.liquidTintHex`
 */
@Composable
fun Modifier.beansGlass(
    shape: Shape = BeansCardShape,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    forceLiquid: Boolean = false,
    sheen: Boolean = true,
    border: Boolean = true,
    liquidTint: Color? = null,
): Modifier {
    val colors = BeansTheme.colors
    val spec = beansGlassSpec(style)
    val subtle = !forceLiquid && style == BeansUIStyle.NATIVE_CLEAN

    // 只在需要时订阅偏好：`@Preview` 里 SettingsStore 没有 init，读它就抛
    // UninitializedPropertyAccessException，整张预览图会红。
    val settingsTint = runCatching { SettingsStore.liquidTintHex }.getOrNull()
    val settingsTintHex = settingsTint?.collectAsState()?.value ?: ""
    val resolvedTint = liquidTint ?: parseHexColor(settingsTintHex)

    val baseSheen = when {
        subtle -> Color.White.copy(alpha = if (colors.isDark) 0.02f else 0.08f)
        colors.isDark -> Color.White.copy(alpha = 0.05f)
        else -> Color.White.copy(alpha = 0.22f)
    }
    val baseHairline = when {
        subtle -> Color.White.copy(alpha = if (colors.isDark) 0.06f else 0.28f)
        colors.isDark -> Color.White.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.55f)
    }
    val sheenColor = baseSheen.copy(alpha = (baseSheen.alpha * spec.sheenAlphaScale).coerceIn(0f, 1f))
    val hairline = baseHairline.copy(alpha = (baseHairline.alpha * spec.hairlineAlphaScale).coerceIn(0f, 1f))

    var result = this.background(color = beansGlassFill(colors, style, resolvedTint), shape = shape)
    if (sheen) {
        result = result.background(
            brush = Brush.verticalGradient(listOf(sheenColor, Color.Transparent)),
            shape = shape,
        )
    }
    if (border) {
        result = result.border(width = 0.8.dp, color = hairline, shape = shape)
    }
    return result
}

/**
 * Port of iOS `BeansSurface`：统一表面容器。
 * iOS 实现就是 `BeansGlass(shape:)`，Android 保持同一语义，避免页面局部出现不同质感。
 */
@Composable
fun BeansSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    BeansGlass(
        modifier = modifier,
        shape = shape,
        style = style,
        contentAlignment = contentAlignment,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 通用卡片（跟随全局 UI 样式）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `GlassCard`。
 *
 * 圆角、内边距与阴影随全局样式收敛（见 [beansGlassSpec]）：
 * `NATIVE_CLEAN` 用 `min(cornerRadius, 18)` + 13dp；`COMPACT` 用 `min(cornerRadius, 16)` + 11dp + 4dp 阴影；
 * `FROSTED` 保持几何不变、只换材质；`LIQUID` 保持 24dp / 16dp。
 *
 * 内容作用域是 [ColumnScope]（对应 iOS 调用方普遍写 `VStack` 的用法）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    contentPadding: Dp? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spec = beansGlassSpec(style)
    val resolvedCornerRadius = spec.cornerCap?.let { minOf(cornerRadius, it) } ?: cornerRadius
    val resolvedPadding = contentPadding ?: spec.cardPadding
    val shape = RoundedCornerShape(resolvedCornerRadius)

    Column(
        modifier = modifier
            .beansCardShadow(radius = spec.shadowRadius, y = 3.dp, shape = shape)
            .beansGlass(shape = shape, style = style)
            .clip(shape)
            .padding(resolvedPadding),
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 按压动效（Port of GlassPressButtonStyle）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `GlassPressButtonStyle`
 * （`scale` / `brightness` / `.spring(response: 0.24, dampingFraction: 0.82)`）。
 *
 * 只负责视觉：按下缩放 + 轻微提亮，不处理点击。
 * 提亮用 `BlendMode.Plus` 叠一层白色，对应 iOS 的 `.brightness(+0.025)`。
 *
 * `response: 0.24` → `stiffness = (2π / 0.24)² ≈ 685`。
 */
@Composable
fun Modifier.beansPressScale(
    interactionSource: MutableInteractionSource,
    scale: Float = 0.94f,
    pressedBrightness: Float = 0.025f,
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed && enabled
    val animatedScale by animateFloatAsState(
        targetValue = if (active) scale else 1f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 685f),
        label = "beansPressScale",
    )
    return this
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
        }
        .drawWithContent {
            drawContent()
            if (active && pressedBrightness > 0f) {
                drawRect(
                    color = Color.White.copy(alpha = pressedBrightness),
                    blendMode = BlendMode.Plus,
                )
            }
        }
}

/**
 * 按压缩放 + 点击的组合：`ButtonStyle` 的完整替代品。
 *
 * 用法：`Modifier.beansGlass(...).beansPressClickable(interaction, onClick = { ... })`。
 * 不使用 ripple（Android 水波纹会破坏 iOS 的清透观感），只保留按压缩放。
 */
@Composable
fun Modifier.beansPressClickable(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    enabled: Boolean = true,
    scale: Float = 0.94f,
    pressedBrightness: Float = 0.025f,
): Modifier = this
    .beansPressScale(
        interactionSource = interactionSource,
        scale = scale,
        pressedBrightness = pressedBrightness,
        enabled = enabled,
    )
    .clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )

/**
 * 通用可按压容器（把 [Modifier.beansPressClickable] 包成 composable，省去手写 interactionSource）。
 */
@Composable
fun BeansPressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scale: Float = 0.94f,
    pressHaptic: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.beansPressClickable(
            interactionSource = interactionSource,
            enabled = enabled,
            scale = scale,
            onClick = {
                if (pressHaptic) BeansHaptics.tap()
                onClick()
            },
        ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 板块进入动画（首页错落渐入，纯视觉不影响布局）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `View.sectionEntrance(delay:)`。
 *
 * 页面内板块错落渐入：opacity 0→1 + 上移 14dp，用 `graphicsLayer` 实现，
 * 因此**不影响布局**（对应 iOS 注释「纯视觉不影响布局」）。
 *
 * @param delayMillis 对应 iOS 的 `delay`
 * @param animationKey 改变它会重播动画（列表刷新后重新入场）
 */
@Composable
fun Modifier.beansSectionEntrance(
    delayMillis: Int = 0,
    animationKey: Any? = Unit,
): Modifier {
    val progress = remember(animationKey) { Animatable(0f) }
    LaunchedEffect(animationKey) {
        progress.snapTo(0f)
        if (delayMillis > 0) delay(delayMillis.toLong())
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing),
        )
    }
    val value = progress.value
    return this.graphicsLayer {
        alpha = value
        translationY = (1f - value) * 14.dp.toPx()
    }
}

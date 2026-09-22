package com.lulu.music.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.BeansFloatingEffect
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.ui.theme.BeansTheme
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// MARK: - 工具
// ---------------------------------------------------------------------------------------------

/** Port of `beansSongCountText(_:)`. */
fun beansSongCountText(count: Int): String = beansLocalized("$count 首", "$count songs")

/** Port of `beansLocalSongCountText(_:)`. */
fun beansLocalSongCountText(count: Int): String =
    beansLocalized("$count 首 · 本机", "$count songs · On device")

/** Port of `beansTimeString(_:)` — `m:ss`，负数按 0 处理。 */
fun beansTimeString(seconds: Double): String {
    val total = max(0, seconds.toInt())
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

// ---------------------------------------------------------------------------------------------
// MARK: - SF Symbol 桥接（iOS 侧组件全部按 systemName 传图标，移植后仍能直接写原来的名字）
// ---------------------------------------------------------------------------------------------

/**
 * SF Symbol 名称 → Material icon 的映射表。
 *
 * Components.swift 里的图标全部以字符串形式传入（`Image(systemName:)`）；
 * 移植后的屏幕代码沿用同样的字符串时，可以通过这里拿到对应的 [ImageVector]。
 * 找不到时返回 null，由调用方决定回退图标。
 */
object BeansIcons {

    private val symbols: Map<String, ImageVector> = mapOf(
        // 标签栏 / 主要入口
        "house.fill" to Icons.Rounded.Home,
        "magnifyingglass" to Icons.Rounded.Search,
        "person.fill" to Icons.Rounded.Person,
        "person.2.fill" to Icons.Rounded.Group,
        "gearshape" to Icons.Rounded.Settings,
        // 音乐
        "music.note" to Icons.Rounded.MusicNote,
        "music.note.list" to Icons.AutoMirrored.Rounded.QueueMusic,
        "music.mic" to Icons.Rounded.Mic,
        "waveform" to Icons.Rounded.GraphicEq,
        "opticaldisc" to Icons.Rounded.Album,
        "radio" to Icons.Rounded.Radio,
        "square.stack" to Icons.Rounded.Layers,
        // 播放控制
        "play.fill" to Icons.Rounded.PlayArrow,
        "pause.fill" to Icons.Rounded.Pause,
        "backward.fill" to Icons.Rounded.SkipPrevious,
        "forward.fill" to Icons.Rounded.SkipNext,
        "shuffle" to Icons.Rounded.Shuffle,
        "repeat" to Icons.Rounded.Repeat,
        "speaker.wave.2.fill" to Icons.AutoMirrored.Rounded.VolumeUp,
        "speaker.slash.fill" to Icons.AutoMirrored.Rounded.VolumeOff,
        // 操作
        "heart" to Icons.Rounded.FavoriteBorder,
        "heart.fill" to Icons.Rounded.Favorite,
        "star.fill" to Icons.Rounded.Star,
        "hand.thumbsup" to Icons.Rounded.ThumbUp,
        "plus" to Icons.Rounded.Add,
        "plus.circle" to Icons.Rounded.AddCircle,
        "minus" to Icons.Rounded.Remove,
        "xmark" to Icons.Rounded.Close,
        "checkmark" to Icons.Rounded.Check,
        "trash" to Icons.Rounded.Delete,
        "square.and.pencil" to Icons.Rounded.Edit,
        "square.and.arrow.up" to Icons.Rounded.Share,
        "arrow.clockwise" to Icons.Rounded.Refresh,
        "arrow.triangle.2.circlepath" to Icons.Rounded.Sync,
        "arrow.down.circle" to Icons.Rounded.Download,
        "arrow.up.arrow.down" to Icons.Rounded.SwapVert,
        "arrow.left" to Icons.AutoMirrored.Rounded.ArrowBack,
        "chevron.left" to Icons.Rounded.ChevronLeft,
        "chevron.right" to Icons.Rounded.ChevronRight,
        "chevron.up" to Icons.Rounded.KeyboardArrowUp,
        "chevron.down" to Icons.Rounded.KeyboardArrowDown,
        "ellipsis" to Icons.Rounded.MoreHoriz,
        "line.3.horizontal" to Icons.Rounded.Menu,
        "list.bullet" to Icons.AutoMirrored.Rounded.List,
        "square.grid.2x2" to Icons.Rounded.GridView,
        // 外观 / 内容
        "paintbrush" to Icons.Rounded.Brush,
        "photo" to Icons.Rounded.Image,
        "sparkles" to Icons.Rounded.AutoAwesome,
        "wand.and.stars" to Icons.Rounded.AutoFixHigh,
        "moon.stars.fill" to Icons.Rounded.DarkMode,
        "sun.max.fill" to Icons.Rounded.LightMode,
        "lock.fill" to Icons.Rounded.Lock,
        "eye" to Icons.Rounded.Visibility,
        "eye.slash" to Icons.Rounded.VisibilityOff,
        "bell" to Icons.Rounded.Notifications,
        "clock" to Icons.Rounded.Schedule,
        "hourglass" to Icons.Rounded.HourglassEmpty,
        "info.circle" to Icons.Rounded.Info,
        "exclamationmark.triangle" to Icons.Rounded.Warning,
        "wifi" to Icons.Rounded.Wifi,
        "wifi.exclamationmark" to Icons.Rounded.WifiOff,
        "doc.text" to Icons.Rounded.Description,
        "folder" to Icons.Rounded.Folder,
        "cloud" to Icons.Rounded.Cloud,
        "bubble.left" to Icons.Rounded.ChatBubbleOutline,
        "mappin" to Icons.Rounded.Place,
        "pin.fill" to Icons.Rounded.PushPin,
        "text.alignleft" to Icons.AutoMirrored.Rounded.FormatAlignLeft,
    )

    /** 查找 SF Symbol 对应的 Material 图标；未收录时返回 null。 */
    fun of(symbol: String): ImageVector? = symbols[symbol]

    /** 查找 SF Symbol 对应的 Material 图标；未收录时返回 [fallback]。 */
    fun of(symbol: String, fallback: ImageVector): ImageVector = symbols[symbol] ?: fallback
}

// ---------------------------------------------------------------------------------------------
// MARK: - 播放 / 暂停图标切换过渡
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `BeansSymbolReplace`（`contentTransition(.symbolEffect(.replace))`）。
 *
 * iOS 17+ 是符号形变，低版本回退透明度过渡；Compose 没有符号形变 API，
 * 这里统一使用 [Crossfade]（= iOS 的 `.opacity` 回退行为）。
 */
@Composable
fun <T> BeansSymbolReplace(
    targetState: T,
    modifier: Modifier = Modifier,
    durationMillis: Int = 180,
    content: @Composable (T) -> Unit,
) {
    Crossfade(
        targetState = targetState,
        modifier = modifier,
        animationSpec = tween(durationMillis = durationMillis),
        label = "BeansSymbolReplace",
    ) { state -> content(state) }
}

/**
 * Port of iOS `PlayPauseMorphIcon`。
 *
 * 播放三角与暂停双竖线共享一个固定画布，避免按钮尺寸跳动；
 * SF Symbol 的 `play.fill` / `pause.fill` 在 Compose 里没有对应的 morph，
 * 因此按 iOS 的几何参数用 [Canvas] 手绘，并保留同样的弹簧参数
 * （`response 0.28` → `stiffness = (2π / 0.28)² ≈ 504`，`dampingFraction 0.78`）。
 *
 * @param size 图标基准尺寸；画布固定为 `size + 6dp`（与 iOS 一致，防止切换时抖动）
 */
@Composable
fun BeansPlayPauseMorphIcon(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tint: Color = Color.Unspecified,
) {
    val colors = BeansTheme.colors
    val color = if (tint == Color.Unspecified) colors.label else tint
    val progress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 504f),
        label = "beansPlayPauseMorph",
    )

    // 以下尺寸沿用 iOS 的绝对点值（并做密度换算），保证与 Swift 版本视觉一致
    val unit = size.value
    val iconSize = size
    val barWidth = max(4f, unit * 0.24f).dp
    val barHeight = (unit * 0.86f).dp
    val barGap = max(3f, unit * 0.18f).dp
    val barCorner = max(1.5f, unit * 0.08f).dp
    val travel = (unit * 5f / 22f).dp

    Canvas(modifier = modifier.size(size + 6.dp)) {
        val centerX = this.size.width / 2f
        val centerY = this.size.height / 2f
        val base = iconSize.toPx()
        val pivot = Offset(centerX, centerY)

        // ---- 播放三角（对应 `play.fill`）----
        val triangleAlpha = 1f - progress
        if (triangleAlpha > 0.001f) {
            val offsetX = progress * travel.toPx()
            val triangleWidth = base * 0.62f
            val triangleHeight = base * 0.80f
            val triangleScale = 1f - progress * 0.18f
            val left = centerX - triangleWidth * 0.35f + offsetX
            val path = Path().apply {
                moveTo(left, centerY - triangleHeight / 2f)
                lineTo(left + triangleWidth, centerY)
                lineTo(left, centerY + triangleHeight / 2f)
                close()
            }
            scale(triangleScale, triangleScale, pivot = pivot) {
                drawPath(path = path, color = color, alpha = triangleAlpha)
                // SF Symbol 的三角是圆角，用一圈描边把尖角柔化
                drawPath(
                    path = path,
                    color = color,
                    alpha = triangleAlpha,
                    style = Stroke(
                        width = base * 0.07f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }

        // ---- 暂停双竖线（对应 `pause.fill`）----
        val barAlpha = progress
        if (barAlpha > 0.001f) {
            val barScale = 0.82f + progress * 0.18f
            val offsetX = (1f - progress) * -travel.toPx()
            val width = barWidth.toPx()
            val height = barHeight.toPx()
            val gap = barGap.toPx()
            val corner = CornerRadius(barCorner.toPx())
            val top = centerY - height / 2f
            scale(barScale, barScale, pivot = pivot) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(centerX - gap / 2f - width + offsetX, top),
                    size = Size(width, height),
                    cornerRadius = corner,
                    alpha = barAlpha,
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(centerX + gap / 2f + offsetX, top),
                    size = Size(width, height),
                    cornerRadius = corner,
                    alpha = barAlpha,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 弹窗尺寸（对应 iOS 的 presentationDetents）
// ---------------------------------------------------------------------------------------------

/** Port of iOS `BeansDetent`。 */
sealed interface BeansDetent {
    /** 对应 `.medium`（约半屏）。 */
    data object Medium : BeansDetent

    /** 对应 `.large`（全屏）。 */
    data object Large : BeansDetent

    /** 对应 `.fraction(_:)`（屏幕高度比例，0…1）。 */
    data class Fraction(val value: Float) : BeansDetent

    /** 对应 `.height(_:)`（固定内容高度）。 */
    data class Height(val value: Dp) : BeansDetent
}

/**
 * Port of iOS `BeansSheetModifier` + `presentationDragIndicator`。
 *
 * iOS 以 `.sheet` + `.presentationDetents` 修饰器形式存在；Compose 的等价物是宿主式的
 * [ModalBottomSheet]，无法做成 `Modifier`，因此这里是一个 composable。
 * iOS 的 `.fraction` / `.height` 通过内容高度近似：`fraction → fillMaxHeight(f)`、`height → heightIn(min)`。
 *
 * @param detents 尺寸集合；含 [BeansDetent.Medium] / [BeansDetent.Fraction] 时允许半展开
 * @param dragIndicator `null` = 使用系统默认（显示），`false` = 隐藏，`true` = 显示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeansBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    detents: List<BeansDetent> = listOf(BeansDetent.Large),
    dragIndicator: Boolean? = null,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BeansTheme.colors
    val partial = detents.any { it is BeansDetent.Medium || it is BeansDetent.Fraction }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !partial)
    val handle: (@Composable () -> Unit)? =
        if (dragIndicator == false) null else { { BeansSheetDragHandle() } }

    // 面板在**自己的窗口**里（Material3 的 `ModalBottomSheet` 是 `ComponentDialog` 子类），
    // 所以全局提示条必须知道「现在有面板」才能把落点从底部挪到顶部，不被面板盖住 / 不压在
    // 面板的行上（见 `BeansToast.kt` 的 `BeansSheetLayer` 与 `BeansToastHost`）。
    // 进出成对记录，嵌套面板（面板里再开面板）也不会漏减。
    DisposableEffect(Unit) {
        BeansSheetLayer.sheetOpened()
        onDispose { BeansSheetLayer.sheetClosed() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = if (containerColor == Color.Unspecified) colors.card else containerColor,
        contentColor = if (contentColor == Color.Unspecified) colors.label else contentColor,
        dragHandle = handle,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().beansDetentSize(detents),
            content = content,
        )
    }
}

/** 拖拽指示条（iOS 的 `presentationDragIndicator`）。 */
@Composable
fun BeansSheetDragHandle(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val colors = BeansTheme.colors
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 5.dp)
                .background(
                    color = if (color == Color.Unspecified) colors.comment.copy(alpha = 0.4f) else color,
                    shape = BeansCapsuleShape,
                )
        )
    }
}

/** 把 [BeansDetent] 折算成内容尺寸约束（取最后一个 detent 作为初始高度）。 */
private fun Modifier.beansDetentSize(detents: List<BeansDetent>): Modifier {
    val target = detents.lastOrNull() ?: return this
    return when (target) {
        BeansDetent.Medium -> this.fillMaxHeight(0.5f)
        BeansDetent.Large -> this
        is BeansDetent.Fraction -> this.fillMaxHeight(target.value.coerceIn(0.05f, 1f))
        is BeansDetent.Height -> this.heightIn(min = target.value)
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 滚动辅助（iOS 15 兼容包装的 Android 对应物）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `View.beansScrollIndicatorsHidden()`。
 *
 * Android 的 Compose 列表本来就不绘制滚动指示条（只有 iOS 默认显示），
 * 因此这里是空实现；保留它只是让移植后的调用点读起来与原代码一一对应。
 */
fun Modifier.beansScrollIndicatorsHidden(): Modifier = this

/**
 * Port of iOS `View.beansScrollContentBackgroundHidden()`。
 *
 * Compose 的 `LazyColumn` / `Column` 没有系统默认内容背景，因此同样是空实现。
 */
fun Modifier.beansScrollContentBackgroundHidden(): Modifier = this

/**
 * Port of iOS `View.beansScrollDismissesKeyboard()`（`.scrollDismissesKeyboard(.interactively)`）。
 *
 * Compose 没有对应 API，这里用 [NestedScrollConnection] 在滚动发生时清除焦点来近似；
 * 效果是「一开始滚动就收起键盘」，比 iOS 的交互式跟随略微激进。
 */
@Composable
fun Modifier.beansScrollDismissesKeyboard(): Modifier {
    val focusManager = LocalFocusManager.current
    val connection = remember(focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }
    return this.nestedScroll(connection)
}

// ---------------------------------------------------------------------------------------------
// MARK: - 封面图
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `CoverImage`。
 *
 * 布局尺寸完全由外层固定容器决定；Coil 的 [AsyncImage] 只放在 overlay 中渲染，
 * 图片加载完成与否都不会改变任何布局尺寸（根治「封面加载后错乱」）。
 *
 * @param url 封面地址；为空时直接显示占位图标，避免一直转圈
 * @param emptyHint 封面未加载时的提示文字（播放器大封面用：等待开始播放）；null 显示中性图标
 * @param fallbackUrl [url] 加载失败时的回退地址（歌曲行用：本机自定义封面坏掉时回到远程封面，
 *   而不是留一块空白）。为 null 时没有回退。
 */
@Composable
fun BeansCoverImage(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    emptyHint: String? = null,
    fallbackUrl: String? = null,
) {
    BeansCoverImageCore(
        model = url?.takeIf { it.isNotBlank() },
        fallbackModel = fallbackUrl?.takeIf { it.isNotBlank() },
        size = size,
        modifier = modifier,
        cornerRadius = cornerRadius,
        emptyHint = emptyHint,
    )
}

/**
 * [BeansCoverImage] 的**本机文件**重载。
 *
 * 本机自定义封面必须走 [File] 而不是路径字符串：Coil 的 `FileKeyer` 会把
 * `lastModified` 算进缓存键，而自定义封面的文件名是由 identityKey 的 sha1 决定的**固定路径**
 * （换封面就是覆盖同一个文件）。用字符串当模型的话，换完封面 Coil 还会命中旧图的缓存。
 */
@Composable
fun BeansCoverImage(
    file: File,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    emptyHint: String? = null,
    fallbackUrl: String? = null,
) {
    BeansCoverImageCore(
        model = file,
        fallbackModel = fallbackUrl?.takeIf { it.isNotBlank() },
        size = size,
        modifier = modifier,
        cornerRadius = cornerRadius,
        emptyHint = emptyHint,
    )
}

/** 两个重载共用的实现：占位 / 加载指示 / 「本机封面坏了就回退到远程」都在这里。 */
@Composable
private fun BeansCoverImageCore(
    model: Any?,
    fallbackModel: Any?,
    size: Dp,
    modifier: Modifier,
    cornerRadius: Dp,
    emptyHint: String?,
) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(cornerRadius)

    // 本机自定义封面损坏 / 被系统清掉时切到远程封面；没有回退时不会来回切。
    var useFallback by remember(model, fallbackModel) { mutableStateOf(false) }
    val resolved: Any? = if (useFallback && fallbackModel != null) fallbackModel else model
    var loaded by remember(resolved) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.glassFill),
        contentAlignment = Alignment.Center,
    ) {
        if (resolved == null) {
            BeansCoverPlaceholder(size = size, emptyHint = emptyHint, cornerRadius = cornerRadius)
        } else {
            if (!loaded) {
                BeansCoverPlaceholder(size = size, emptyHint = emptyHint, cornerRadius = cornerRadius)
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(maxOf(size * 0.22f, 16.dp)),
                )
            }
            AsyncImage(
                model = resolved,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onLoading = { loaded = false },
                onSuccess = { state ->
                    // Coil 2.x 的结果类型是 SuccessResult，取 `drawable`（Coil 1.x 的 `.image` 在 2.x 已移除）。
                    // intrinsic size 为 0 的退化结果（坏图 / 空图）不算加载成功，会继续显示占位图。
                    val drawable = state.result.drawable
                    loaded = drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0
                },
                onError = {
                    loaded = false
                    if (!useFallback && fallbackModel != null) useFallback = true
                },
            )
        }
    }
}

/** 封面占位：提示文字优先，否则使用中性等待图标（不再使用音乐音符）。 */
@Composable
private fun BeansCoverPlaceholder(size: Dp, emptyHint: String?, cornerRadius: Dp) {
    val colors = BeansTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .background(colors.glassFill, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        if (emptyHint != null) {
            Text(
                text = emptyHint,
                color = colors.comment,
                fontSize = max(11f, minOf(size.value * 0.09f, 15f)).sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = colors.comment,
                modifier = Modifier.size(size * 0.28f),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 会员标识小标（SVIP 金色 / VIP 红色）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `VIPBadgeView`。
 *
 * 开关「显示歌曲 VIP 图标」（`beans.showVipBadge`）在**这里**统一消费：全仓库所有 VIP 小胶囊
 * （搜索、音乐库、歌单详情、播放页、主页每日推荐…）都走这个 composable，所以关掉开关之后
 * 没有任何一处会漏掉一个孤零零的 VIP 标签。
 *
 * @param visibleOverride 显式覆盖可见性（预览 / 测试用）。不传时读 `SettingsStore.showVipBadge`：
 *   做成参数是因为 Robolectric 下「写偏好 → StateFlow 回显」不可靠（见 `RobolectricSingletons`），
 *   渲染测试需要一个确定性的入口来驱动**同一个** `if (!visible) return` 分支。
 */
@Composable
fun BeansVIPBadge(
    text: String,
    modifier: Modifier = Modifier,
    visibleOverride: Boolean? = null,
) {
    val settingsFlow = runCatching { SettingsStore.showVipBadge }.getOrNull()
    val visible = visibleOverride ?: settingsFlow?.collectAsState()?.value ?: true
    if (!visible) return

    val background = if (text == "SVIP") {
        Color(red = 0.85f, green = 0.62f, blue = 0.18f)
    } else {
        Color(red = 0.93f, green = 0.25f, blue = 0.22f)
    }
    Text(
        text = text,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .background(background, BeansCapsuleShape)
            .padding(horizontal = 5.dp, vertical = 1.5.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 全局漂浮特效（参考图「全局漂浮特效：关闭 / 雪花 / 文字·Emoji」）
// ---------------------------------------------------------------------------------------------

/** 漂浮特效层的语义标签（无障碍 + 测试都能找到它）。 */
const val BeansFloatingEffectTag: String = "漂浮特效"

/**
 * 一枚漂浮粒子。
 *
 * 位置用 0..1 的**比例**表示，随容器尺寸缩放；生成过程完全确定（没有随机数），
 * 因此同一 [BeansFloatingEffect] 每次渲染的位置一致，也方便单元测试断言。
 */
data class BeansParticle(
    val xFraction: Float,
    val yFraction: Float,
    val scale: Float,
    val glyph: String,
)

private val beansSnowGlyphs = listOf("❄", "❅", "❆")
private val beansTextGlyphs = listOf("♪", "♫", "🎵", "🎶", "✨", "💛")

/**
 * 生成漂浮粒子（纯函数，供渲染层与测试共用）。
 *
 * `OFF` 返回空列表 —— 调用方（[BeansFloatingEffectOverlay]）据此完全不画画。
 */
fun beansFloatingParticles(effect: BeansFloatingEffect): List<BeansParticle> {
    if (effect == BeansFloatingEffect.OFF) return emptyList()
    val glyphs = if (effect == BeansFloatingEffect.SNOW) beansSnowGlyphs else beansTextGlyphs
    val count = if (effect == BeansFloatingEffect.SNOW) 24 else 18
    return List(count) { index ->
        // 确定性的「伪随机」：黄金比取模，避免引入 Random（否则每次重组粒子都会跳）。
        val golden = 0.6180339887f
        val x = ((index + 1) * golden) % 1f
        val y = ((index + 1) * golden * 1.7f) % 1f
        val scale = 0.7f + ((index % 5) * 0.12f)
        BeansParticle(
            xFraction = x,
            yFraction = y,
            scale = scale,
            glyph = glyphs[index % glyphs.size],
        )
    }
}

/**
 * 全局漂浮特效层：铺在所有页面之上、不拦截触摸（没有任何 pointer input modifier），
 * 由 `ui/BeansApp.kt` 渲染一次。
 *
 * 动画刻意做成**一次性有限时长**（12 秒缓缓飘落）而不是无限循环：无限动画会让
 * Compose 的 `waitForIdle` 永远等不到空闲，把 Robolectric 渲染测试挂死。
 *
 * @param effect 显式指定特效（测试 / 预览用）；不传时读 `SettingsStore.floatingEffect`
 */
@Composable
fun BeansFloatingEffectOverlay(
    modifier: Modifier = Modifier,
    effect: BeansFloatingEffect? = null,
) {
    val settingsFlow = runCatching { SettingsStore.floatingEffect }.getOrNull()
    val resolved = effect ?: settingsFlow?.collectAsState()?.value ?: BeansFloatingEffect.OFF
    if (resolved == BeansFloatingEffect.OFF) return

    val particles = remember(resolved) { beansFloatingParticles(resolved) }
    val progress = remember(resolved) { Animatable(0f) }
    LaunchedEffect(resolved) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 12_000, easing = LinearEasing),
        )
    }
    val drift = progress.value
    val colors = BeansTheme.colors

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = BeansFloatingEffectTag },
    ) {
        val width = maxWidth
        val height = maxHeight
        particles.forEach { particle ->
            // 竖向漂移 35% 屏高后循环回顶部；横向轻微摆动。
            val y = height * ((particle.yFraction + drift * 0.35f) % 1f)
            val sway = kotlin.math.sin((drift * 2f * Math.PI).toFloat() + particle.xFraction * 6f) * 0.015f
            val x = width * ((particle.xFraction + sway + 1f) % 1f)
            Text(
                text = particle.glyph,
                color = if (resolved == BeansFloatingEffect.SNOW) {
                    Color.White.copy(alpha = 0.85f)
                } else {
                    colors.accent.copy(alpha = 0.85f)
                },
                fontSize = (16f * particle.scale).sp,
                modifier = Modifier.offset(x = x, y = y),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 玻璃图标按钮（清透 + 按压动效）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `GlassIconButton`。
 *
 * Apple 简洁样式使用平面底色（不描边、不加高光），其余样式使用玻璃圆底；
 * `active` 时着色为主题强调色（对应 iOS `Color.beansAmber`）。
 *
 * @param icon Material 图标；`systemName` 重载可直接传 SF Symbol 名称
 * @param size 圆形点击区直径；图标大小为 `size * 0.42`（简洁样式）/ `size * 0.38`（其余）
 */
@Composable
fun BeansGlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 44.dp,
    active: Boolean = false,
    forceLiquid: Boolean = false,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    haptic: Boolean = false,
) {
    val colors = BeansTheme.colors
    val nativeClean = style == BeansUIStyle.NATIVE_CLEAN
    val interactionSource = remember { MutableInteractionSource() }
    val tint = when {
        active -> colors.accent
        else -> colors.label
    }
    val background = if (nativeClean && !forceLiquid) {
        Modifier.background(
            color = if (active) colors.accent.copy(alpha = 0.12f) else Color.Transparent,
            shape = CircleShape,
        )
    } else {
        Modifier.beansGlass(shape = CircleShape, style = style, forceLiquid = forceLiquid)
    }

    Box(
        modifier = modifier
            .size(size)
            .then(background)
            .clip(CircleShape)
            .beansPressClickable(
                interactionSource = interactionSource,
                onClick = {
                    if (haptic) BeansHaptics.tap()
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * (if (nativeClean) 0.42f else 0.38f)),
        )
    }
}

/** [BeansGlassIconButton] 的 SF Symbol 重载；未收录的符号回退为空心圆。 */
@Composable
fun BeansGlassIconButton(
    systemName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 44.dp,
    active: Boolean = false,
    forceLiquid: Boolean = false,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    haptic: Boolean = false,
) {
    BeansGlassIconButton(
        icon = BeansIcons.of(systemName, Icons.Rounded.Circle),
        onClick = onClick,
        modifier = modifier,
        contentDescription = contentDescription,
        size = size,
        active = active,
        forceLiquid = forceLiquid,
        style = style,
        haptic = haptic,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 玻璃按钮（清透 + 按压动效）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `GlassButton`。
 *
 * `prominent` 时用强调色实底 + 白色文字（iOS 里还会叠一层 `forceLiquid` 玻璃），
 * Apple 简洁样式使用低对比平面底色 + 发丝描边，其余样式使用玻璃胶囊。
 *
 * 与 iOS 的差异：iOS 用 `LocalizedStringKey(title)` 走字符串目录，
 * Android 请直接传入已本地化的文案（用 `Lang.localized(zh, en)` 或 `beansLocalized`）。
 *
 * @param icon 可选的前置图标（对应 `systemName`）
 * @param haptic 点击时是否触发 `BeansHaptics.medium()`（iOS 由调用方自行触发）
 */
@Composable
fun BeansGlassButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    prominent: Boolean = false,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    haptic: Boolean = false,
) {
    val colors = BeansTheme.colors
    val nativeClean = style == BeansUIStyle.NATIVE_CLEAN
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor = if (prominent) Color.White else colors.label

    val background = when {
        prominent -> Modifier
            .beansGlass(shape = BeansCapsuleShape, style = style, forceLiquid = true)
            .background(color = colors.accent.copy(alpha = 0.78f), shape = BeansCapsuleShape)

        nativeClean -> Modifier.background(color = colors.label.copy(alpha = 0.055f), shape = BeansCapsuleShape)

        else -> Modifier.beansGlass(shape = BeansCapsuleShape, style = style)
    }

    Row(
        modifier = modifier
            .then(background)
            .then(
                if (nativeClean && !prominent) {
                    Modifier.border(
                        width = 0.7.dp,
                        color = colors.label.copy(alpha = 0.075f),
                        shape = BeansCapsuleShape,
                    )
                } else {
                    Modifier
                }
            )
            .clip(BeansCapsuleShape)
            .beansPressClickable(
                interactionSource = interactionSource,
                onClick = {
                    if (haptic) BeansHaptics.medium()
                    onClick()
                },
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = title,
            color = contentColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** [BeansGlassButton] 的 SF Symbol 重载。 */
@Composable
fun BeansGlassButton(
    title: String,
    systemName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    haptic: Boolean = false,
) {
    BeansGlassButton(
        title = title,
        onClick = onClick,
        modifier = modifier,
        icon = BeansIcons.of(systemName, Icons.Rounded.Circle),
        prominent = prominent,
        style = style,
        haptic = haptic,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 胶囊容器（chip / 标签 / 排序按钮等通用底座）
// ---------------------------------------------------------------------------------------------

/**
 * 胶囊容器：`BeansSurface` 的胶囊特化版本，用于 Chip / 标签 / 排序按钮这类较不透明的表面。
 *
 * 默认填充：液态样式用 `colors.glassFill`，Apple 简洁样式用 `colors.label @ 5.5%` 平面底色。
 */
@Composable
fun BeansCapsule(
    modifier: Modifier = Modifier,
    fill: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    borderWidth: Dp = 0.8.dp,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = BeansTheme.colors
    val nativeClean = style == BeansUIStyle.NATIVE_CLEAN
    val resolvedFill = when {
        fill != Color.Unspecified -> fill
        nativeClean -> colors.label.copy(alpha = 0.055f)
        else -> colors.glassFill
    }
    val resolvedBorder = when {
        borderColor != Color.Unspecified -> borderColor
        nativeClean -> colors.label.copy(alpha = 0.075f)
        colors.isDark -> Color.White.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.55f)
    }

    Row(
        modifier = modifier
            .background(resolvedFill, BeansCapsuleShape)
            .border(borderWidth, resolvedBorder, BeansCapsuleShape)
            .clip(BeansCapsuleShape)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 区块标题
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `SectionHeader`。
 *
 * iOS 用 `HStack(alignment: .firstTextBaseline)`；Compose 里用 `alignByBaseline()` 还原同一基线对齐。
 *
 * @param titleColor 传 [Color.Unspecified] 时使用 `colors.label`
 * @param trailingColor 传 [Color.Unspecified] 时使用 `colors.comment`
 */
@Composable
fun BeansSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    titleColor: Color = Color.Unspecified,
    trailingColor: Color = Color.Unspecified,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
    onTrailingTap: (() -> Unit)? = null,
) {
    val colors = BeansTheme.colors
    val nativeClean = style == BeansUIStyle.NATIVE_CLEAN
    val resolvedTitle = if (titleColor == Color.Unspecified) colors.label else titleColor
    val resolvedTrailing = if (trailingColor == Color.Unspecified) colors.comment else trailingColor

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = resolvedTitle,
            fontSize = (if (nativeClean) 26 else 21).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        if (trailing != null) {
            val interactionSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .alignByBaseline()
                    .beansPressClickable(
                        interactionSource = interactionSource,
                        scale = 0.9f,
                        onClick = { onTrailingTap?.invoke() },
                    ),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = trailing,
                    color = resolvedTrailing,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = resolvedTrailing,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 空态 / 错误 / 加载
// ---------------------------------------------------------------------------------------------

/** Port of iOS `EmptyStateView`。 */
@Composable
fun BeansEmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.comment,
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = text,
            color = colors.comment,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** [BeansEmptyState] 的 SF Symbol 重载。 */
@Composable
fun BeansEmptyState(
    systemName: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    BeansEmptyState(
        icon = BeansIcons.of(systemName, Icons.Rounded.Circle),
        text = text,
        modifier = modifier,
    )
}

/**
 * Port of iOS `ErrorStateView`：图标固定为 `wifi.exclamationmark`，
 * 重试按钮复用 [BeansGlassButton]（文案走 `beansLocalized`，与 iOS 的 `LocalizedStringKey` 一致）。
 */
@Composable
fun BeansErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    style: BeansUIStyle = BeansUIStyle.LIQUID,
) {
    val colors = BeansTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.WifiOff,
            contentDescription = null,
            tint = colors.comment,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = message,
            color = colors.comment,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        BeansGlassButton(
            title = beansLocalized("重试", "Retry"),
            icon = Icons.Rounded.Refresh,
            onClick = onRetry,
            style = style,
        )
    }
}

/** Port of iOS `LoadingStateView`（大号 `ProgressView` + 强调色）。 */
@Composable
fun BeansLoadingState(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val colors = BeansTheme.colors
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = if (color == Color.Unspecified) colors.accent else color,
            strokeWidth = 3.dp,
            modifier = Modifier.size(40.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 二维码
// ---------------------------------------------------------------------------------------------

/**
 * 二维码展示（Port of iOS `QRCodeView` 的**展示部分**）。
 *
 * iOS 用 CoreImage 的 `CIFilter.qrCodeGenerator()` 生成位图；Android 平台没有内置二维码生成器，
 * 本工程也没有引入 ZXing，因此这里只负责「按固定尺寸、关闭插值地等比展示位图」，
 * 位图由调用方（登录流程）生成后传入。
 *
 * 对应关系：`.interpolation(.none)` → [FilterQuality.None]；`.scaledToFit()` → [ContentScale.Fit]；
 * 生成失败时的 `EmptyView` → [image] 为 null 时什么都不画。
 */
@Composable
fun BeansQRCodeView(
    image: ImageBitmap?,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
) {
    if (image == null) return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
        modifier = modifier.size(size),
    )
}

/** 远程二维码图片（部分登录接口直接返回 PNG 地址）的便捷重载。 */
@Composable
fun BeansQRCodeView(
    url: String,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
            modifier = Modifier.matchParentSize(),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 当前播放指示（均衡器动效）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `NowPlayingIndicator`。
 *
 * 三根胶囊柱以 `easeInOut(0.35s)` 自动往返，逐根延迟 0.12s。
 * iOS 用 `.repeatForever(autoreverses: true).delay(index * 0.12)`；
 * Compose 没有「延迟启动的无限动画」的便捷写法，这里用每根柱子各自的 [Animatable] +
 * `while (true)` 往返来精确复刻同一时序。
 */
@Composable
fun BeansNowPlayingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    barCount: Int = 3,
    height: Dp = 16.dp,
) {
    val colors = BeansTheme.colors
    val barColor = if (color == Color.Unspecified) colors.accent else color

    Row(
        modifier = modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(barCount) { index ->
            val animatable = remember(index) { Animatable(0f) }
            LaunchedEffect(index) {
                delay(index * 120L)
                while (true) {
                    animatable.animateTo(1f, tween(durationMillis = 350, easing = EaseInOut))
                    animatable.animateTo(0f, tween(durationMillis = 350, easing = EaseInOut))
                }
            }
            Box(
                Modifier
                    .width(3.dp)
                    .height((5f + 9f * animatable.value).dp)
                    .background(barColor, BeansCapsuleShape)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 播放进度线（迷你播放器用）
// ---------------------------------------------------------------------------------------------

/** Port of iOS `ProgressLine`；`ratio = progress / duration`（duration <= 0.001 时按 0 处理）。 */
@Composable
fun BeansProgressLine(
    progress: Double,
    duration: Double,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val ratio = if (duration > 0.001) (progress / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    BeansProgressBar(ratio = ratio, modifier = modifier, height = height)
}

/**
 * 直接以 0…1 的比例绘制进度条：轨道 + 强调色渐变填充 + 末端柔圆光点。
 *
 * iOS 用 `GeometryReader` 拿到可用宽度后 `frame(width: max(width * ratio, 6))`，
 * 这里用 [BoxWithConstraints] 还原；光点用 `BlurredEdgeTreatment.Unbounded` 的模糊圆近似
 * iOS 的 `Circle().blur(radius: 4)` 光晕（API < 31 无模糊，退化为半透明圆）。
 */
@Composable
fun BeansProgressBar(
    ratio: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val colors = BeansTheme.colors
    val clamped = ratio.coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(height),
    ) {
        val fillWidth = maxOf(maxWidth * clamped, 6.dp)

        // 轨道
        Box(
            Modifier
                .matchParentSize()
                .background(colors.comment.copy(alpha = 0.25f), BeansCapsuleShape)
        )
        // 进度
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(fillWidth)
                .fillMaxHeight()
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(colors.accent, colors.accent.copy(alpha = 0.5f))
                    ),
                    shape = BeansCapsuleShape,
                )
        ) {
            // 柔圆光晕（替代生硬方边阴影）
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(14.dp)
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .beansBlur(4.dp)
                        .background(colors.accent.copy(alpha = 0.45f), CircleShape)
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(5.dp)
                        .shadow(
                            elevation = 2.dp,
                            shape = CircleShape,
                            ambientColor = colors.accent.copy(alpha = 0.8f),
                            spotColor = colors.accent.copy(alpha = 0.8f),
                        )
                        .background(colors.accent, CircleShape)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 预览（仅用于 Android Studio，不影响运行时）
// ---------------------------------------------------------------------------------------------

@androidx.compose.ui.tooling.preview.Preview(
    name = "Beans components (light)",
    widthDp = 360,
    showBackground = true,
)
@Composable
private fun BeansComponentsLightPreview() {
    com.lulu.music.ui.theme.BeansTheme(
        themeMode = com.lulu.music.data.prefs.BeansThemeMode.LIGHT,
        accentKey = "amber",
    ) {
        val colors = BeansTheme.colors
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BeansSectionHeader(title = "最近播放", trailing = "全部")
            GlassCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BeansCoverImage(url = null, size = 48.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("歌曲名称", color = colors.label, fontSize = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BeansVIPBadge("VIP")
                            BeansVIPBadge("SVIP")
                        }
                    }
                    BeansNowPlayingIndicator()
                }
                Spacer(Modifier.height(8.dp))
                BeansProgressLine(progress = 42.0, duration = 100.0)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BeansGlassButton(
                    title = "播放全部",
                    icon = Icons.Rounded.PlayArrow,
                    prominent = true,
                    onClick = {},
                )
                BeansGlassButton(title = "随机播放", systemName = "shuffle", onClick = {})
                BeansGlassIconButton(icon = Icons.Rounded.Shuffle, onClick = {})
                BeansCapsule { Text("华语", color = colors.label, fontSize = 13.sp) }
            }
            BeansPlayPauseMorphIcon(isPlaying = true)
            BeansEmptyState(icon = Icons.Rounded.MusicNote, text = "还没有歌曲")
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "Beans backdrop (dark)",
    widthDp = 360,
    heightDp = 480,
)
@Composable
private fun BeansGlassBackdropPreview() {
    com.lulu.music.ui.theme.BeansTheme(
        themeMode = com.lulu.music.data.prefs.BeansThemeMode.DARK,
        accentKey = "mint",
    ) {
        GlassBackdrop {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BeansSectionHeader(title = "Song list", trailing = "All")
                BeansLoadingState()
                BeansErrorState(message = "网络连接失败", onRetry = {})
                BeansToastView(message = "已添加到播放队列")
            }
        }
    }
}

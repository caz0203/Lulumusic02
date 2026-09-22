package com.lulu.music.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// MARK: - 全局轻提示（Toast，收藏 / 歌单等操作反馈用）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `ToastCenter`（`ObservableObject` 单例 → Kotlin `object` + [StateFlow]）。
 *
 * 用法：
 * ```
 * BeansToastCenter.show("已收藏")
 * // 在根布局里挂一次（与页面内容同级）：
 * Box { screenContent(); BeansToastHost() }
 * ```
 *
 * 与 iOS 一致：重复调用会取消上一次的自动消失任务并重新计时。
 */
object BeansToastCenter {

    /** iOS `ToastCenter.show(_:duration:)` 的默认时长（2.2 秒）。 */
    const val DEFAULT_DURATION_MILLIS: Long = 2_200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _message = MutableStateFlow<String?>(null)

    /** 当前提示文案；`null` 表示没有提示（对应 iOS `@Published var message: String?`）。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    private var dismissJob: Job? = null

    /** 显示一条提示，[durationMillis] 之后自动消失。 */
    fun show(text: String, durationMillis: Long = DEFAULT_DURATION_MILLIS) {
        dismissJob?.cancel()
        _message.value = text
        dismissJob = scope.launch {
            delay(durationMillis)
            _message.value = null
        }
    }

    /** 立即收起当前提示。 */
    fun dismiss() {
        dismissJob?.cancel()
        dismissJob = null
        _message.value = null
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 浮层高度（谁在自己的窗口里）
// ---------------------------------------------------------------------------------------------

/**
 * 当前打开的 `ModalBottomSheet` 数量。
 *
 * 为什么提示条需要知道这件事：
 *
 * `ModalBottomSheet`（Material3 1.3.1）**不是**主窗口里的一块内容，而是
 * `ModalBottomSheetDialogWrapper extends ComponentDialog` —— 它有自己的窗口
 * （`TYPE_APPLICATION`），盖在主窗口之上。面板打开时：
 *
 * 1. **层级**：主窗口里画的东西永远在面板下面，所以提示条必须画在自己的 `Popup` 窗口里
 *    （见 [BeansToastHost]）；
 * 2. **落点**：面板占据屏幕下半部分，提示条原来贴底 150dp 的位置会正好压在面板的行上 ——
 *    既难读，也挡住用户正在操作的菜单项。所以有面板时把落点换到屏幕上方。
 *
 * 写入方只有面板宿主 `BeansBottomSheet`（进出成对记录），因此这里是一个纯计数：
 * 嵌套面板（面板里再开面板）也只需要「> 0」这个信息。
 */
object BeansSheetLayer {

    private val _openCount = MutableStateFlow(0)

    /** 当前打开的浮层面板数量；`> 0` 表示屏幕下半部分被面板占用。 */
    val openCount: StateFlow<Int> = _openCount.asStateFlow()

    /** 面板开始展示（由 `BeansBottomSheet` 在进入组合时调用）。 */
    fun sheetOpened() {
        _openCount.value += 1
    }

    /** 面板已经消失（由 `BeansBottomSheet` 在离开组合时调用）。 */
    fun sheetClosed() {
        _openCount.value = (_openCount.value - 1).coerceAtLeast(0)
    }

    /** 把计数清零。面板宿主被强制回收（或测试之间互相污染）时的兜底。 */
    fun reset() {
        _openCount.value = 0
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 提示条落点（纯函数：单测直接钉住）
// ---------------------------------------------------------------------------------------------

/** 没有面板时，提示条距屏幕底部的距离（原来是 `BeansApp` 里传的 150dp，这里作为默认值）。 */
val BeansToastDefaultBottomPadding: Dp = 92.dp

/** 有面板时，提示条距屏幕顶部的距离 —— 只要离开面板的势力范围、又不贴到状态栏即可。 */
val BeansToastDefaultTopPadding: Dp = 96.dp

/** 提示条距屏幕左右边缘的距离（胶囊太宽时留白）。 */
val BeansToastDefaultHorizontalPadding: Dp = 30.dp

/** 淡出动画时长：提示文案清空后窗口还要再留这么久，让淡出真的看得见。 */
private const val BeansToastExitMillis: Long = 320L

/** 窗口内留给胶囊阴影的空间（`Popup` 窗口按内容裁剪，不留就会切掉阴影）。 */
private val BeansToastShadowRoom = 8.dp

/**
 * 提示条这一帧的落点。
 *
 * @param alignment 相对屏幕（严格说是相对挂载它的全屏容器）的对齐点
 * @param verticalOffset 从对齐点朝屏幕内侧的位移（正数向下）
 */
data class BeansToastPlacement(
    val alignment: Alignment,
    val verticalOffset: Dp,
    val horizontalPadding: Dp,
)

/**
 * 提示条该画在哪 —— **纯函数**，测试直接断言两种落点。
 *
 * - 没有面板：贴底（沿用改造前的观感，`Alignment.BottomCenter` + 向上 150dp）；
 * - 有面板：改到顶部（面板在屏幕下半部，贴底会压在面板的行上）。
 *
 * 两种情况下提示条都完整落在屏幕内：底部 → `bottomPadding` 必须小于「屏高 − 胶囊高」；
 * 顶部 → `topPadding` 只需避开状态栏。
 */
fun beansToastPlacement(
    sheetOpen: Boolean,
    bottomPadding: Dp = BeansToastDefaultBottomPadding,
    topPadding: Dp = BeansToastDefaultTopPadding,
    horizontalPadding: Dp = BeansToastDefaultHorizontalPadding,
): BeansToastPlacement = if (sheetOpen) {
    BeansToastPlacement(
        alignment = Alignment.TopCenter,
        verticalOffset = topPadding,
        horizontalPadding = horizontalPadding,
    )
} else {
    BeansToastPlacement(
        alignment = Alignment.BottomCenter,
        verticalOffset = -bottomPadding,
        horizontalPadding = horizontalPadding,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 宿主
// ---------------------------------------------------------------------------------------------

/**
 * 把当前提示渲染成一条悬浮胶囊。放在根布局里挂一次即可（对应 iOS 在根视图叠加 `ToastView`）。
 *
 * ## 为什么是 `Popup` 而不是普通的 `Box`
 *
 * 改造前这里是一个 `fillMaxSize()` 的 `Box`：**画在主窗口里**。而 `ModalBottomSheet` 是
 * 自己的窗口（`ComponentDialog`），主窗口的任何内容都在它下面 —— 于是「下一首播放」的
 * 「已加入下一首」提示被面板盖住，用户根本看不到（用户报的就是这个）。
 *
 * 现在整条提示画在 `Popup` 自己的窗口里：Compose 1.7.6 的 `PopupLayout` 用
 * `WindowManager.LayoutParams.type = TYPE_APPLICATION_SUB_PANEL`（1002）、
 * `token = view.applicationWindowToken`、`width/height = WRAP_CONTENT` 添加窗口：
 *
 * - 它是**应用窗口 token 的子窗口**，子窗口的 sublayer 为正 → 排在同一个 token 里的顶层
 *   窗口（也就是面板的对话框窗口）**之上**；
 * - 窗口按内容测量（`WRAP_CONTENT`），所以只会吞掉胶囊自己那一小块区域的手势，
 *   面板的其余部分照常可点；
 * - 窗口是**按需创建**的：有提示才挂上，因此它一定是在面板窗口之后才被添加的。
 *
 * 再叠加一层保险：有面板时落点从底部换到顶部（[beansToastPlacement]），
 * 即使某个 ROM 的窗口排序与预期不同，提示条也不会落在面板覆盖的区域里。
 */
@Composable
fun BeansToastHost(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = BeansToastDefaultBottomPadding,
    horizontalPadding: Dp = BeansToastDefaultHorizontalPadding,
    topPadding: Dp = BeansToastDefaultTopPadding,
) {
    val message by BeansToastCenter.message.collectAsState()
    val openSheets by BeansSheetLayer.openCount.collectAsState()

    // 按需创建窗口：有文案时立刻挂上；文案清空后再留一小会儿让淡出动画播完。
    // 这样提示窗口总是在面板窗口之后创建（层级上稳赢），也不会常驻一个不可见的窗口。
    var windowMounted by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message != null) {
            windowMounted = true
        } else {
            delay(BeansToastExitMillis)
            windowMounted = false
        }
    }
    if (!windowMounted) return

    val placement = beansToastPlacement(
        sheetOpen = openSheets > 0,
        bottomPadding = bottomPadding,
        topPadding = topPadding,
        horizontalPadding = horizontalPadding,
    )
    val density = LocalDensity.current
    val offsetY = with(density) { placement.verticalOffset.roundToPx() }
    // 胶囊最多占满屏宽减去左右留白；再宽就会顶出屏幕（`Popup` 窗口不会自动换行）。
    val maxCapsuleWidth = (LocalConfiguration.current.screenWidthDp.dp - placement.horizontalPadding * 2)

    Popup(
        alignment = placement.alignment,
        offset = IntOffset(x = 0, y = offsetY),
        properties = PopupProperties(
            // 只负责显示：不抢焦点、不吃返回键、点外面也不消失（提示条不该拦任何操作）。
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        BeansToastView(
            message = message,
            modifier = modifier
                .padding(
                    start = placement.horizontalPadding,
                    end = placement.horizontalPadding,
                    top = BeansToastShadowRoom,
                    bottom = BeansToastShadowRoom,
                )
                .widthIn(max = maxCapsuleWidth),
        )
    }
}

/**
 * Port of iOS `ToastView`（胶囊玻璃 + 底部弹入）。
 *
 * [message] 为 `null` 时淡出并下移 16dp；为了在淡出过程中仍能看见文字，
 * 这里用 [lastMessage] 记住最后一次非空文案。
 *
 * 注意：宿主是**按需挂载**的（见 [BeansToastHost]），所以首次组合时 [message] 已经非空。
 * `animate*AsState` 在首次组合时会直接以目标值开始，那样弹入动画就没了 —— 因此这里先用一帧
 * 把状态压在「隐藏」，下一帧再动到「可见」。
 */
@Composable
fun BeansToastView(
    message: String?,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors

    var lastMessage by remember { mutableStateOf(message.orEmpty()) }
    LaunchedEffect(message) {
        if (message != null) lastMessage = message
    }

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val visible = message != null && appeared
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 322f),
        label = "beansToastAlpha",
    )
    val offsetY by animateDpAsState(
        targetValue = if (visible) 0.dp else 16.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 322f),
        label = "beansToastOffset",
    )

    Text(
        text = lastMessage,
        color = colors.label,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                translationY = offsetY.toPx()
            }
            .shadow(
                elevation = 14.dp,
                shape = BeansCapsuleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f),
            )
            .background(color = colors.glassFill, shape = BeansCapsuleShape)
            .border(width = 0.8.dp, color = Color.White.copy(alpha = 0.18f), shape = BeansCapsuleShape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

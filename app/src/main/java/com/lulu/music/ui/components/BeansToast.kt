package com.lulu.music.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * 把当前提示渲染到屏幕底部。放在根 `Box` 里挂一次即可（对应 iOS 在根视图叠加 `ToastView`）。
 *
 * 全屏无点击拦截：内部没有任何 pointerInput，所以不会吞掉页面手势。
 */
@Composable
fun BeansToastHost(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 92.dp,
    horizontalPadding: Dp = 30.dp,
) {
    val message by BeansToastCenter.message.collectAsState()
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BeansToastView(
            message = message,
            modifier = Modifier.padding(
                start = horizontalPadding,
                end = horizontalPadding,
                bottom = bottomPadding,
            ),
        )
    }
}

/**
 * Port of iOS `ToastView`（胶囊玻璃 + 底部弹入）。
 *
 * [message] 为 `null` 时淡出并下移 16dp；为了在淡出过程中仍能看见文字，
 * 这里用 [lastMessage] 记住最后一次非空文案。
 */
@Composable
fun BeansToastView(
    message: String?,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors

    var lastMessage by remember { mutableStateOf("") }
    LaunchedEffect(message) {
        if (message != null) lastMessage = message
    }

    val visible = message != null
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

package com.lulu.music.ui.screens

import androidx.compose.animation.core.RepeatMode as InfiniteRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.beansPressClickable
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.delay

/**
 * 黑胶 / 歌词版式共用的浅色前景。
 *
 * 这两种版式在封面上叠了一层深靛蓝压暗，主题色可能是深色（浅色模式下 `colors.label` 接近黑），
 * 直接沿用会糊在背景里，因此前景固定用近白。
 */
/** 黑胶 / 歌词版式的前景主色（近白），供播放页复用以保持一致。 */
val PlayerOnVinylLabel = Color(0xFFF2F0FB)

private val OnVinylLabel = PlayerOnVinylLabel
private val OnVinylDim = Color(0xB3F2F0FB)

// ---------------------------------------------------------------------------------------------
// MARK: - 黑胶唱盘
// ---------------------------------------------------------------------------------------------

/**
 * 黑胶唱盘：唱片本体缓慢旋转，唱臂停在上方搭在盘边。
 *
 * 与 iOS `VinylTurntableView` 的差异：
 *  - 拖动切歌（左右甩动）没有移植：Android 播放页已有上一首 / 下一首按钮，重复手势容易误触。
 *  - 唱臂用 `Canvas` 画一条贝塞尔 + 配重/支点/唱头三个几何体，而不是逐层叠视图。
 */
@Composable
fun VinylPlayerView(
    coverURL: String?,
    isPlaying: Boolean,
    trackKey: String?,
    discSize: Dp,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    // 唱片直径 = 唱臂遮挡掉的那部分之后的可用宽度。
    val armWidth = discSize * 0.24f
    val discRotation by rememberRecordRotation(isPlaying = isPlaying, trackKey = trackKey)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(discSize + 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(discSize)
                .graphicsLayer { rotationZ = discRotation },
        ) {
            VinylRecordDisc(coverURL = coverURL, discSize = discSize)
        }

        // 唱臂固定在左上角，唱针落在唱片外圈靠左的位置。
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = -(armWidth * 0.55f), y = -(discSize * 0.40f)),
        ) {
            VinylTonearm(isPlaying = isPlaying, height = discSize * 0.62f)
        }

        if (onTap != null) {
            val interaction = remember { MutableInteractionSource() }
            // 透明覆盖层：整块舞台都可点击（切到歌词 / 返回封面）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .beansPressClickable(
                        interactionSource = interaction,
                        scale = 1f,
                        pressedBrightness = 0f,
                        onClick = onTap,
                    ),
            )
        }
    }
}

/**
 * 唱片转速状态：播放时按真实时间累加角度，暂停时停在当前角度。
 *
 * iOS 的 `RecordRotationState` 是「记住基准角度 + 起始时刻，按需计算当前角度」，
 * Android 侧用一个 [mutableFloatStateOf] 持续累加，语义一致（暂停后不回到 0），
 * 额外在暂停时补 5° 缓动，模拟唱盘惯性。
 */
@Composable
private fun rememberRecordRotation(isPlaying: Boolean, trackKey: String?): State<Float> {
    // key = 曲目：换歌时唱片回到 0°，像重新放上一张唱片。
    val angle = remember(trackKey) { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying, trackKey) {
        if (isPlaying) {
            while (true) {
                delay(16L)
                angle.floatValue = wrapAngle(angle.floatValue + 0.38f)
            }
        } else {
            // 惯性：约 5 帧匀速多转 5°，再停住。
            repeat(5) {
                delay(16L)
                angle.floatValue = wrapAngle(angle.floatValue + 1f)
            }
        }
    }

    return angle
}

/** 把角度收进 `[0, 360)`，避免长时间播放后浮点数值无限增大。 */
private fun wrapAngle(value: Float): Float {
    val wrapped = value % 360f
    return if (wrapped < 0f) wrapped + 360f else wrapped
}

/** 唱片：黑胶盘体 + 纹路 + 外圈高光 + 中心圆标（封面）。 */
@Composable
private fun VinylRecordDisc(coverURL: String?, discSize: Dp) {
    val labelSize = discSize * 0.64f
    val holeSize = discSize * 0.045f

    Box(
        modifier = Modifier
            .size(discSize)
            .background(Color(0xFF0A0A0C), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f

            // 盘体：径向渐变（中心略亮，边缘更黑）
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF232327), Color(0xFF121214), Color(0xFF060607)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
            )

            // 细纹：0.70r → 0.97r 共 22 圈，每 4 圈加粗一点
            for (i in 0 until 22) {
                val factor = 0.70f + (i / 21f) * 0.27f
                drawCircle(
                    color = if (i % 4 == 0) Color(0x1FFFFFFF) else Color(0x0FFFFFFF),
                    radius = radius * factor,
                    style = Stroke(width = if (i % 4 == 0) 1.1f else 0.7f),
                )
            }

            // 外圈高光（左上亮、右下暗），模拟灯光打在盘边
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0x40FFFFFF), Color(0x08FFFFFF), Color(0x00000000)),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
                radius = radius * 0.985f,
                style = Stroke(width = 1.4f),
            )

            // 中心圆标外圈阴影
            drawCircle(
                color = Color(0x66000000),
                radius = (labelSize.toPx() / 2f) + 2f,
            )
        }

        // 中心圆标：封面裁成圆形（与 iOS 一致：封面即唱片标签）
        Box(
            modifier = Modifier
                .size(labelSize)
                .background(Color(0xFF1C1C20), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (!coverURL.isNullOrBlank()) {
                AsyncImage(
                    model = coverURL,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        shape = CircleShape
                        clip = true
                    },
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = OnVinylDim,
                    modifier = Modifier.size(labelSize * 0.34f),
                )
            }
        }

        // 主轴孔
        Box(
            modifier = Modifier
                .size(holeSize)
                .background(Color(0xFF0B0B0D), CircleShape),
        )
    }
}

/**
 * 唱臂：支点在左上，臂管向右下弯，唱头压在外圈音轨上。
 *
 * 播放 / 暂停用 [animateFloatAsState] 在 `-30°`（抬起）与 `0°`（落针）之间摆动；
 * 再叠加一个「轻微摆动」的无限动画，让播放时不是完全静止的贴图。
 */
@Composable
private fun VinylTonearm(isPlaying: Boolean, height: Dp) {
    val width = height * 0.58f
    val pivotSize = width * 0.46f
    val tubeWidth = maxOf(3.5f, width.value * 0.058f).dp

    val armRotation by animateFloatAsState(
        targetValue = if (isPlaying) 0f else -30f,
        animationSpec = tween(durationMillis = 480),
        label = "vinylTonearmAngle",
    )

    val wobble = rememberInfiniteTransition(label = "vinylTonearmWobble")
    val wobbleDegrees by wobble.animateFloat(
        initialValue = -0.22f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3100),
            repeatMode = InfiniteRepeatMode.Reverse,
        ),
        label = "vinylTonearmWobbleDegrees",
    )

    Box(modifier = Modifier.size(width, height)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pivotX = size.width * 0.5f
            val pivotY = pivotSize.toPx() * 0.5f
            val tube = tubeWidth.toPx()

            // ---------- 唱臂（随角度摆动） ----------
            withTransform({
                rotate(degrees = armRotation + if (isPlaying) wobbleDegrees else 0f, pivot = Offset(pivotX, pivotY))
            }) {
                // 阴影
                val shadowPath = tonearmPath(size.width, size.height)
                drawPath(
                    path = shadowPath,
                    color = Color(0x59000000),
                    style = Stroke(width = tube * 1.5f, cap = StrokeCap.Round),
                )

                // 臂管
                drawPath(
                    path = shadowPath,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFAFAFA),
                            Color(0xFF8C8C8C),
                            Color(0xFFEBEBEB),
                            Color(0xFF666666),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height),
                    ),
                    style = Stroke(width = tube, cap = StrokeCap.Round),
                )

                // 配重（支点上方的小胶囊）
                val counterWeight = Size(tube * 2.6f, size.height * 0.15f)
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFFBFBFBF), Color(0xFF404040), Color(0xFFA6A6A6)),
                    ),
                    topLeft = Offset(pivotX - counterWeight.width / 2f, -counterWeight.height * 0.55f),
                    size = counterWeight,
                    cornerRadius = CornerRadius(counterWeight.height / 2f),
                )

                drawHeadshell(size.width, size.height)
            }

            // ---------- 支点底座（不摆动） ----------
            drawCircle(color = Color(0x73000000), radius = pivotSize.toPx() * 0.55f, center = Offset(pivotX, pivotY + 2f))
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(Color(0xFFD1D1D1), Color(0xFF595959), Color(0xFFBFBFBF)),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
                radius = pivotSize.toPx() * 0.5f,
                center = Offset(pivotX, pivotY),
            )
            drawCircle(
                color = Color(0xFF1F1F1F),
                radius = pivotSize.toPx() * 0.40f,
                center = Offset(pivotX, pivotY),
            )
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(Color(0xFFF2F2F2), Color(0xFF8C8C8C), Color(0xFFD9D9D9)),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
                radius = pivotSize.toPx() * 0.20f,
                center = Offset(pivotX, pivotY),
            )
            drawCircle(color = Color(0xFF1F1F1F), radius = pivotSize.toPx() * 0.07f, center = Offset(pivotX, pivotY))
        }
    }
}

/** 三段贝塞尔折出唱臂曲线（起点支点 → 中部弯折 → 末端唱头）。 */
private fun tonearmPath(width: Float, height: Float): Path {
    val path = Path()
    path.moveTo(width * 0.50f, 0f)
    path.cubicTo(width * 0.52f, height * 0.10f, width * 0.58f, height * 0.20f, width * 0.58f, height * 0.28f)
    path.cubicTo(width * 0.58f, height * 0.38f, width * 0.45f, height * 0.48f, width * 0.42f, height * 0.55f)
    path.cubicTo(width * 0.38f, height * 0.62f, width * 0.33f, height * 0.70f, width * 0.31f, height * 0.74f)
    return path
}

/** 唱头（headshell + 唱针），画在臂管末端并自带 +24° 的倾角。 */
private fun DrawScope.drawHeadshell(width: Float, height: Float) {
    val headWidth = width * 0.24f
    val headHeight = height * 0.22f
    val cx = width * 0.31f
    val cy = height * 0.74f

    withTransform({
        rotate(degrees = 24f, pivot = Offset(cx, cy))
    }) {
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF525252), Color(0xFF1A1A1A))),
            topLeft = Offset(cx - headWidth / 2f, cy - headHeight / 2f),
            size = Size(headWidth, headHeight),
            cornerRadius = CornerRadius(2.5f),
        )
        drawRoundRect(
            color = Color(0x59FFFFFF),
            topLeft = Offset(cx - headWidth / 2f, cy - headHeight / 2f),
            size = Size(headWidth, headHeight),
            cornerRadius = CornerRadius(2.5f),
            style = Stroke(width = 0.8f),
        )
        // 唱针
        drawRoundRect(
            color = Color(0xFFE8E8E8),
            topLeft = Offset(cx + headWidth * 0.42f, cy - headHeight * 0.32f),
            size = Size(2.5f, headHeight * 0.45f),
            cornerRadius = CornerRadius(1.2f),
        )
        // 线圈（红色小方块）
        drawRoundRect(
            color = Color(0xFFEB3838),
            topLeft = Offset(cx - headWidth * 0.33f, cy + headHeight * 0.12f),
            size = Size(headWidth * 0.65f, 4.5f),
            cornerRadius = CornerRadius(1f),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 网易云风格信息行
// ---------------------------------------------------------------------------------------------

/**
 * 歌曲信息行：左侧「歌名 + VIP / 歌手 + 关注」，右侧「红心 + 点赞数」「评论气泡」。
 *
 * @param likeCount 真实点赞数；为 null 时只画图标不画数字（本项目 `Song` 模型没有该字段）
 * @param commentCount 真实评论数；为 null 时只画气泡
 * @param compact 极简版式用：字号更小、不显示专辑名
 * @param foreground 前景色；黑胶 / 歌词版式叠了深色底，需要传 [PlayerOnVinylLabel]
 */
@Composable
fun PlayerNowPlayingRow(
    song: Song?,
    isLiked: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    likeCount: Int? = null,
    commentCount: Int? = null,
    foreground: Color = Color.Unspecified,
    onToggleFavorite: (() -> Unit)? = null,
    onComment: (() -> Unit)? = null,
) {
    val colors = BeansTheme.colors
    val fg = if (foreground == Color.Unspecified) colors.label else foreground
    val dim = if (foreground == Color.Unspecified) colors.comment else OnVinylDim
    var followed by remember(song?.identityKey) { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song?.name ?: beansLocalized("未在播放", "Nothing playing"),
                    color = fg,
                    fontSize = if (compact) 16.sp else 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song?.isVIP == true) {
                    Spacer(Modifier.width(6.dp))
                    BeansVIPBadge(text = "VIP")
                }
            }
            Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song?.artists.orEmpty(),
                    color = dim,
                    fontSize = if (compact) 12.sp else 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song != null) {
                    Spacer(Modifier.width(8.dp))
                    FollowPill(
                        followed = followed,
                        foreground = fg,
                        onToggle = {
                            BeansHaptics.tap()
                            followed = !followed
                        },
                    )
                }
            }
        }

        if (onToggleFavorite != null) {
            Spacer(Modifier.width(10.dp))
            PlayerCountIcon(
                icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                tint = if (isLiked) colors.accent else fg,
                count = likeCount,
                onClick = onToggleFavorite,
            )
        }
        if (onComment != null) {
            Spacer(Modifier.width(4.dp))
            PlayerCountIcon(
                icon = Icons.Rounded.ChatBubbleOutline,
                tint = fg,
                count = commentCount,
                onClick = onComment,
            )
        }
    }
}

/** 「关注」小胶囊；关注状态只存在于当前播放页会话（本项目没有关注接口）。 */
@Composable
private fun FollowPill(followed: Boolean, foreground: Color, onToggle: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .height(21.dp)
            .background(
                if (followed) colors.accent.copy(alpha = 0.18f) else foreground.copy(alpha = 0.12f),
                CircleShape,
            )
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.94f,
                onClick = onToggle,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = if (followed) Icons.Rounded.Check else Icons.Rounded.Add,
            contentDescription = null,
            tint = if (followed) colors.accent else foreground,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = if (followed) beansLocalized("已关注", "Following") else beansLocalized("关注", "Follow"),
            color = if (followed) colors.accent else foreground,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 图标 + 可选计数（计数为 null 时只显示图标）。 */
@Composable
private fun PlayerCountIcon(
    icon: ImageVector,
    tint: Color,
    count: Int?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .height(34.dp)
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.90f,
                onClick = onClick,
            )
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
        if (count != null) {
            Text(
                text = formatCount(count),
                color = tint,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/** `650w+` 风格的大数缩写（万 / 亿，与网易云一致）。 */
fun formatCount(value: Int): String = when {
    value >= 100_000_000 -> String.format("%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format("%.1fw+", value / 10_000.0)
    else -> value.toString()
}

// ---------------------------------------------------------------------------------------------
// MARK: - 进度行（左时间 / 中音质 / 右总时长）
// ---------------------------------------------------------------------------------------------

/** `hires` → 极高、`lossless` → 无损 … 用 [BeansAudioQuality] 的中文名。 */
fun audioQualityLabel(raw: String): String {
    val quality = BeansAudioQuality.fromRaw(raw)
    return when (quality) {
        BeansAudioQuality.HIRES -> beansLocalized("极高", "Hi-Res")
        else -> quality.zh
    }
}

/**
 * 细进度条 + 三段时间信息：左「已播放」、中「音质」、右「总时长」。
 *
 * 拖动条沿用 Material3 [Slider]（`onValueChangeFinished` 才 seek），
 * 拖动过程中只更新本地值，避免抖动。
 */
@Composable
fun PlayerProgressRow(
    positionSeconds: Double,
    durationSeconds: Double,
    qualityLabel: String?,
    modifier: Modifier = Modifier,
    onSeek: (Double) -> Unit,
) {
    val colors = BeansTheme.colors
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shown = dragValue ?: positionSeconds.toFloat()
    val range = maxOf(durationSeconds.toFloat(), 0.1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = shown.coerceIn(0f, range),
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeek(it.toDouble()) }
                dragValue = null
            },
            valueRange = 0f..range,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.comment.copy(alpha = 0.28f),
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlayerTime(shown.toDouble()),
                color = colors.comment,
                fontSize = 11.sp,
            )
            if (!qualityLabel.isNullOrBlank()) {
                Text(
                    text = qualityLabel,
                    color = colors.accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = formatPlayerTime(durationSeconds),
                color = colors.comment,
                fontSize = 11.sp,
            )
        }
    }
}

/** `m:ss`。 */
fun formatPlayerTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

// ---------------------------------------------------------------------------------------------
// MARK: - 深色背景（黑胶 / 歌词版式）
// ---------------------------------------------------------------------------------------------

/**
 * 黑胶版式那层深靛蓝渐变。
 *
 * 不替换 [PlayerScreen] 原有的氛围背景（封面模糊层），只在它上面叠一层半透明深靛蓝，
 * 既拿到参考图的质感，又保留跟随封面的环境色。
 */
@Composable
fun VinylBackdropOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xF20A0D24),
                    0.45f to Color(0xE60C1030),
                    1f to Color(0xF0070918),
                ),
            ),
    )
}

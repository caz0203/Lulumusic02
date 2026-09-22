package com.lulu.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.beansPressClickable
import com.lulu.music.ui.theme.BeansTheme

/**
 * 全屏播放器的四种版式（对应 iOS `CoverPlayerStyle` 的 vinyl / classic 分支 +
 * 「歌词优先」「极简」两种补充版式）。
 *
 * 通过 [com.lulu.music.data.prefs.SettingsStore.playerLayout] 持久化；
 * [raw] 即写入 `beans.playerLayout` 的字符串，改名会丢失用户已保存的选择。
 */
enum class PlayerLayout(val raw: String, val zh: String, val en: String) {
    /** 当前默认版式：方形大封面，点击封面切换到歌词。 */
    COVER("cover", "专辑封面", "Cover"),

    /** 黑胶唱盘（参考 iOS `VinylTurntableView` / 网易云黑胶播放器）。 */
    VINYL("vinyl", "黑胶唱片", "Vinyl"),

    /** 歌词优先：整屏歌词，底部保留信息行与进度条。 */
    LYRICS("lyrics", "歌词优先", "Lyrics"),

    /** 极简：小封面 + 歌名歌手 + 细进度条 + 控制行。 */
    MINIMAL("minimal", "极简", "Minimal");

    val displayName: String get() = beansLocalized(zh, en)

    companion object {
        val DEFAULT = COVER

        /** 解析持久化的字符串；未知值回落到 [DEFAULT]。 */
        fun fromRaw(raw: String?): PlayerLayout =
            entries.firstOrNull { it.raw == raw } ?: DEFAULT
    }
}

/** 版式选择器里的图标 / 副标题。 */
private fun PlayerLayout.icon(): ImageVector = when (this) {
    PlayerLayout.COVER -> Icons.Rounded.Album
    PlayerLayout.VINYL -> Icons.Rounded.Audiotrack
    PlayerLayout.LYRICS -> Icons.Rounded.Description
    PlayerLayout.MINIMAL -> Icons.Rounded.CropSquare
}

private fun PlayerLayout.subtitle(): String = when (this) {
    PlayerLayout.COVER -> beansLocalized("方形大封面，轻点封面看歌词", "Large square artwork; tap it for lyrics")
    PlayerLayout.VINYL -> beansLocalized("黑胶唱盘 + 唱臂，随播放转动", "Spinning record with a tonearm")
    PlayerLayout.LYRICS -> beansLocalized("整屏歌词，底部保留进度与控制", "Full-screen lyrics with controls below")
    PlayerLayout.MINIMAL -> beansLocalized("小封面 + 歌名 + 进度 + 控制", "Compact art, title, progress and controls")
}

/**
 * 「播放器布局」选择面板（播放页顶栏的版式按钮打开，与设置页写入同一个 key）。
 *
 * 选择后立即生效并持久化，不关闭面板 —— 方便连续对比四种版式。
 */
@Composable
fun PlayerLayoutSheet(
    current: PlayerLayout,
    onPick: (PlayerLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BeansTheme.colors
    BeansBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = beansLocalized("播放器布局", "Player layout"),
                color = colors.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = beansLocalized("选择后立即生效，可在设置中随时更改", "Applied immediately; change it any time in Settings"),
                color = colors.comment,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(14.dp))

            PlayerLayout.entries.forEach { layout ->
                PlayerLayoutRow(
                    layout = layout,
                    selected = layout == current,
                    onClick = {
                        BeansHaptics.select()
                        onPick(layout)
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun PlayerLayoutRow(layout: PlayerLayout, selected: Boolean, onClick: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) colors.accent.copy(alpha = 0.10f) else colors.glassFill,
                shape,
            )
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.98f,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 迷你预览缩略图（不加载网络图片，纯色块示意版式结构）
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 42.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(colors.accent.copy(alpha = 0.28f), colors.card),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = layout.icon(),
                contentDescription = null,
                tint = if (selected) colors.accent else colors.comment,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = layout.displayName,
                color = if (selected) colors.accent else colors.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = layout.subtitle(),
                color = colors.comment,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }

        Spacer(Modifier.width(8.dp))

        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Spacer(Modifier.size(18.dp))
        }
    }
}

/** 版式按钮用的图标（顶栏复用）。 */
fun playerLayoutIcon(layout: PlayerLayout): ImageVector = layout.icon()

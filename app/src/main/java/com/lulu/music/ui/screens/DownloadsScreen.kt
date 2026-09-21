package com.lulu.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.store.DownloadStore
import com.lulu.music.playback.DownloadManager
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.PlayerOpenRequest
import com.lulu.music.ui.components.BeansCoverImage
import com.lulu.music.ui.components.BeansEmptyState
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.theme.BeansTheme

/**
 * Offline library: everything downloaded for playback without a connection.
 * Playback automatically prefers these files (see `BeansDataSourceFactory`).
 */
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val colors = BeansTheme.colors
    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val records by DownloadStore.records.collectAsState()
    val tasks by DownloadManager.tasks.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BeansGlassIconButton(
                icon = Icons.Rounded.ArrowBack,
                onClick = onBack,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = beansLocalized("已下载", "Downloads"),
                color = colors.label,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (records.isNotEmpty()) {
                Text(
                    text = beansLocalized("全部删除", "Delete all"),
                    color = colors.accent,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        BeansHaptics.medium()
                        DownloadManager.deleteAll()
                        BeansToastCenter.show(beansLocalized("已删除全部下载", "All downloads deleted"))
                    },
                )
            }
        }

        if (records.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BeansEmptyState(
                    icon = Icons.Rounded.Download,
                    text = beansLocalized("还没有下载的歌曲", "Nothing downloaded yet"),
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = 190.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(records, key = { _, r -> r.song.identityKey }) { _, record ->
                val song = record.song
                val task = tasks[song.identityKey]
                val interaction = remember(song.identityKey) { MutableInteractionSource() }
                BeansGlass(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    style = uiStyle,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                            ) {
                                BeansHaptics.tap()
                                PlaybackController.play(
                                    songs = records.map { it.song },
                                    startIndex = records.indexOf(record),
                                )
                                PlayerOpenRequest.request()
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BeansCoverImage(url = song.coverURL, size = 46.dp, cornerRadius = 10.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.name,
                                color = colors.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = buildString {
                                    append(song.artists)
                                    if (song.artists.isNotEmpty()) append(" · ")
                                    append(record.quality)
                                    append(" · ")
                                    append(formatBytes(record.bytes))
                                },
                                color = colors.comment,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (task != null && task.state == DownloadManager.State.RUNNING) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${(task.progress * 100).toInt()}%",
                                    color = colors.accent,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        BeansGlassIconButton(
                            icon = Icons.Rounded.Delete,
                            onClick = {
                                BeansHaptics.tap()
                                DownloadManager.delete(song)
                            },
                            size = 38.dp,
                        )
                    }
                }
            }
        }
    }
}

/** Entry card shown on the profile page; kept here so the profile screen stays untouched. */
@Composable
fun ProfileDownloadsEntry(style: com.lulu.music.data.prefs.BeansUIStyle, onOpen: () -> Unit) {
    val colors = BeansTheme.colors
    val records by DownloadStore.records.collectAsState()
    val interaction = remember { MutableInteractionSource() }

    BeansGlass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        style = style,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onOpen,
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = beansLocalized("已下载", "Downloads"),
                    color = colors.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (records.isEmpty()) {
                        beansLocalized("离线播放的歌曲会出现在这里", "Offline songs appear here")
                    } else {
                        String.format(
                            beansLocalized("%d 首可离线播放", "%d available offline"),
                            records.size,
                        )
                    },
                    color = colors.comment,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = "›",
                color = colors.comment,
                fontSize = 18.sp,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

package com.lulu.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.store.LocalPlaylist
import com.lulu.music.data.store.LocalPlaylistStore
import com.lulu.music.ui.theme.BeansTheme

// ---------------------------------------------------------------------------------------------
// MARK: - 纯逻辑（可在 JVM 单测里直接驱动，不需要 Compose）
// ---------------------------------------------------------------------------------------------

/** 「添加到本地歌单」选择器里的一行。 */
data class PlaylistPickerRow(
    val id: String,
    val name: String,
    val songCount: Int,
    /** 这首歌是不是已经在这个歌单里（读的是 [LocalPlaylist.songs]，不是任何缓存猜测）。 */
    val alreadyContains: Boolean,
)

/**
 * 把 store 里的歌单投影成选择器要画的行。
 *
 * 纯函数：不读全局状态、不写任何东西，因此选择器的「已在该歌单中」状态可以被单测直接钉住。
 */
fun playlistPickerRows(playlists: List<LocalPlaylist>, song: Song): List<PlaylistPickerRow> =
    playlists.map { playlist ->
        PlaylistPickerRow(
            id = playlist.id,
            name = playlist.name,
            songCount = playlist.songCount,
            alreadyContains = playlist.songs.any { it.identityKey == song.identityKey },
        )
    }

/** 一次「加入歌单」的真实结果。 */
enum class PlaylistAddOutcome {
    /** 真的新增了（[LocalPlaylistStore.addSongs] 返回 > 0）。 */
    ADDED,

    /** 歌单存在，但去重之后一首都没加进去 —— 也就是「已在该歌单中」。 */
    ALREADY_PRESENT,

    /** 歌单不存在（被别处删掉了 / id 传错）。 */
    FAILED,
}

/**
 * 把歌加进一个本地歌单，并**如实**区分三种结果。
 *
 * `addSongs` 的返回值本身分不清「已在歌单中」和「歌单不存在」（两者都是 0），
 * 所以这里先查一次歌单是否存在 —— 提示文案必须诚实，不能把「没有这个歌单」说成「已在该歌单中」。
 */
fun addSongToLocalPlaylist(playlistId: String, song: Song): PlaylistAddOutcome {
    val exists = LocalPlaylistStore.playlist(playlistId) != null
    val added = LocalPlaylistStore.addSongs(playlistId, listOf(song))
    return when {
        added > 0 -> PlaylistAddOutcome.ADDED
        exists -> PlaylistAddOutcome.ALREADY_PRESENT
        else -> PlaylistAddOutcome.FAILED
    }
}

/** 新建一个只含这首歌的歌单；名称为空白时返回 null（与 [LocalPlaylistStore.create] 一致）。 */
fun createLocalPlaylistWithSong(name: String, song: Song): LocalPlaylist? =
    LocalPlaylistStore.create(name, listOf(song))

/** 加入歌单的提示文案。 */
fun playlistAddMessage(playlistName: String, outcome: PlaylistAddOutcome): String = when (outcome) {
    PlaylistAddOutcome.ADDED -> beansLocalized("已添加到「$playlistName」", "Added to \"$playlistName\"")
    PlaylistAddOutcome.ALREADY_PRESENT -> beansLocalized("已在该歌单中", "Already in this playlist")
    PlaylistAddOutcome.FAILED -> beansLocalized("添加到「$playlistName」失败", "Could not add to \"$playlistName\"")
}

/** 新建歌单成功后的提示文案。 */
fun createdPlaylistMessage(playlist: LocalPlaylist): String =
    beansLocalized("已新建「${playlist.name}」并加入", "Created \"${playlist.name}\" and added it")

/** 点一行之后的完整结果：提示什么、要不要收起选择器。 */
data class PlaylistPickResult(val outcome: PlaylistAddOutcome, val dismiss: Boolean)

/**
 * 点一行的完整处理。只有真的新增了才收起选择器：命中「已在该歌单中」时保持打开，
 * 让用户能看见那一行本来就打着勾，而不是被一句 toast 打发走。
 */
fun applyPlaylistPick(row: PlaylistPickerRow, song: Song): PlaylistPickResult {
    val outcome = addSongToLocalPlaylist(row.id, song)
    return PlaylistPickResult(outcome = outcome, dismiss = outcome == PlaylistAddOutcome.ADDED)
}

// ---------------------------------------------------------------------------------------------
// MARK: - 选择器内容（不带浮层宿主，便于复用 / 渲染测试）
// ---------------------------------------------------------------------------------------------

/**
 * 「添加到本地歌单」的选择器内容。
 *
 * 刻意**不自带** [BeansBottomSheet]：行菜单里的操作面板把它当作「换页」内容直接画在同一个浮层里，
 * 播放页的 `···` 面板则自己开一个浮层包它（见 [LocalPlaylistPickerSheet]）。这样同一个选择器
 * 既能从行菜单到达、也能从播放页到达，而且不会出现「浮层套浮层」。
 *
 * @param onDismiss 选择器要求关闭自己（成功加入一首之后）
 */
@Composable
fun LocalPlaylistPickerContent(
    song: Song,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    val playlists by LocalPlaylistStore.playlists.collectAsState()
    val rows = remember(playlists, song.identityKey) { playlistPickerRows(playlists, song) }

    var showCreate by remember(song.identityKey) { mutableStateOf(false) }
    var newName by remember(song.identityKey) { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = beansLocalized("添加到本地歌单", "Add to local playlist"),
                color = colors.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = song.name,
                color = colors.comment,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
        }

        PlaylistPickerActionRow(
            icon = Icons.Rounded.Add,
            title = beansLocalized("新建歌单", "New playlist"),
            subtitle = beansLocalized("用这首歌新建一个本地歌单", "Create a local playlist with this song"),
            onClick = {
                BeansHaptics.tap()
                newName = ""
                showCreate = true
            },
        )

        if (rows.isEmpty()) {
            Text(
                text = beansLocalized(
                    "还没有本地歌单，先新建一个吧",
                    "No local playlists yet — create one above",
                ),
                color = colors.comment,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 2.dp,
                ),
            ) {
                items(items = rows, key = { it.id }) { row ->
                    PlaylistPickerRowItem(
                        row = row,
                        onClick = {
                            BeansHaptics.tap()
                            val result = applyPlaylistPick(row, song)
                            BeansToastCenter.show(playlistAddMessage(row.name, result.outcome))
                            if (result.dismiss) onDismiss()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
    }

    if (showCreate) {
        LocalPlaylistNameDialog(
            value = newName,
            onValueChange = { newName = it },
            onConfirm = {
                val created = createLocalPlaylistWithSong(newName, song)
                if (created == null) {
                    // 与 LibraryScreen 的新建歌单对话框同一句话：名称为空不算成功。
                    BeansToastCenter.show(beansLocalized("请输入歌单名称", "Enter a playlist name"))
                } else {
                    BeansToastCenter.show(createdPlaylistMessage(created))
                    showCreate = false
                    onDismiss()
                }
            },
            onDismiss = { showCreate = false },
        )
    }
}

/** 自开浮层的版本（播放页 `···` 面板用；行菜单直接复用内容，见上）。 */
@Composable
fun LocalPlaylistPickerSheet(
    song: Song,
    onDismiss: () -> Unit,
) {
    BeansBottomSheet(
        onDismissRequest = onDismiss,
        detents = listOf(BeansDetent.Medium, BeansDetent.Large),
    ) {
        LocalPlaylistPickerContent(song = song, onDismiss = onDismiss)
    }
}

/** 选择器里的功能行（左侧图标 + 标题 + 说明）。 */
@Composable
private fun PlaylistPickerActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.98f,
                pressedBrightness = 0f,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.label,
                fontSize = 14.sp,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = colors.comment,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 一行歌单：左侧方框勾选态，中间歌单名，右侧「N 首」/「已在歌单中」。
 *
 * 勾选态直接来自 [PlaylistPickerRow.alreadyContains]，也就是 store 里的真实内容 ——
 * 没有本地副本可以走样。
 */
@Composable
private fun PlaylistPickerRowItem(
    row: PlaylistPickerRow,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.98f,
                pressedBrightness = 0f,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(shape)
                .background(
                    color = if (row.alreadyContains) colors.accent else Color.Transparent,
                    shape = shape,
                )
                .border(
                    width = if (row.alreadyContains) 0.dp else 1.4.dp,
                    color = if (row.alreadyContains) colors.accent else colors.comment.copy(alpha = 0.6f),
                    shape = shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (row.alreadyContains) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Text(
            text = row.name,
            color = colors.label,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(6.dp))

        if (row.alreadyContains) {
            Text(
                text = beansLocalized("已在歌单中", "Already in"),
                color = colors.accent,
                fontSize = 11.sp,
                maxLines = 1,
            )
        } else {
            Text(
                text = beansLocalized("${row.songCount} 首", "${row.songCount} songs"),
                color = colors.comment,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/** 新建歌单的名称输入框（与音乐库页的新建 / 重命名对话框同一套语义）。 */
@Composable
private fun LocalPlaylistNameDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(beansLocalized("新建歌单", "New playlist")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    label = { Text(beansLocalized("歌单名称", "Playlist name")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = beansLocalized(
                        "本地歌单保存在本机，覆盖安装不会丢失",
                        "Local playlists live on this device and survive updates",
                    ),
                    color = BeansTheme.colors.comment,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(beansLocalized("新建", "Create")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(beansLocalized("取消", "Cancel")) }
        },
    )
}

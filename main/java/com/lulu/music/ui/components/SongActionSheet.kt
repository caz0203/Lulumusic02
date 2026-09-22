package com.lulu.music.ui.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.store.CoverStore
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.playback.DownloadManager
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// MARK: - 纯逻辑（行顺序 / 条件 / 状态文案：可在 JVM 单测里直接钉住）
// ---------------------------------------------------------------------------------------------

/** 歌曲操作面板里的核心行。枚举顺序**就是**设计要求的面板顺序。 */
enum class SongActionKind {
    PLAY_NEXT,
    ADD_TO_PLAYLIST,
    REPLACE_COVER,
    REMOVE_COVER,
    FAVORITE,
    DOWNLOAD,
}

/**
 * 面板要画哪些行、按什么顺序。
 *
 * 规则（与设计一致，且只有这一份实现）：
 *  - 顺序恒为 下一首播放 → 添加到本地歌单 → 更换自定义封面 → 收藏 → 下载；
 *  - 「移除自定义封面」只在真的有自定义封面时插在「更换自定义封面」之后
 *    —— 没有封面就没有可移除的东西，给一个点了没反应的菜单项是不诚实的。
 */
fun songActionKinds(hasCustomCover: Boolean): List<SongActionKind> = buildList {
    add(SongActionKind.PLAY_NEXT)
    add(SongActionKind.ADD_TO_PLAYLIST)
    add(SongActionKind.REPLACE_COVER)
    if (hasCustomCover) add(SongActionKind.REMOVE_COVER)
    add(SongActionKind.FAVORITE)
    add(SongActionKind.DOWNLOAD)
}

/** 「收藏」行右侧的状态文案。 */
fun favoriteActionTrailing(isFavorite: Boolean): String =
    if (isFavorite) beansLocalized("已收藏", "Added") else beansLocalized("未收藏", "Not added")

/** 「下载」行右侧的状态文案。 */
fun downloadActionTrailing(isDownloaded: Boolean): String =
    if (isDownloaded) beansLocalized("已下载", "Downloaded") else beansLocalized("未下载", "Not downloaded")

/**
 * 收藏切换之后的提示文案。
 *
 * [FavoritesStore.toggle] 只在**真的写成功**时返回 true：网易云被云端拒绝时会回滚并返回 false，
 * 所以那时必须说「失败」，绝不能因为「本地已经记上了」就报「已收藏」。
 */
fun favoriteToggleMessage(ok: Boolean, isFavorite: Boolean): String = when {
    !ok -> beansLocalized("收藏失败", "Could not update favourite")
    isFavorite -> beansLocalized("已收藏", "Added to favourites")
    else -> beansLocalized("已取消收藏", "Removed from favourites")
}

/** 面板整体高度上限：行多时滚动而不是被裁掉。 */
private val SongActionSheetMaxHeight = 520.dp

// ---------------------------------------------------------------------------------------------
// MARK: - 调用方附加行
// ---------------------------------------------------------------------------------------------

/**
 * 调用方给面板补的一行（例如本地歌单详情里的「移出歌单」）。
 *
 * @param destructive true 时用警示色画图标与文字（删除类动作）
 */
class SongActionExtra(
    val title: String,
    val icon: ImageVector,
    val trailing: String = "",
    val destructive: Boolean = false,
    val onSelect: () -> Unit,
)

/** 删除类附加行的便捷构造（本地歌单详情的「移出歌单」）。 */
fun songActionRemoveFromPlaylist(onSelect: () -> Unit): SongActionExtra = SongActionExtra(
    title = beansLocalized("移出歌单", "Remove from playlist"),
    icon = Icons.Rounded.Delete,
    destructive = true,
    onSelect = onSelect,
)

// ---------------------------------------------------------------------------------------------
// MARK: - 面板宿主
// ---------------------------------------------------------------------------------------------

/**
 * 全局共用的**歌曲操作面板**：所有歌曲行最右侧那个 `⋮` 都开它。
 *
 * 行与顺序由 [songActionKinds] 决定：下一首播放 / 添加到本地歌单 / 更换自定义封面 /
 * 移除自定义封面（仅有封面时）/ 收藏 / 下载。
 *
 * 「添加到本地歌单」不另开一个浮层，而是把面板内容就地换成 [LocalPlaylistPickerContent] ——
 * 避免浮层套浮层，也让同一个选择器既能从行菜单到达、也能从播放页的 `···` 面板到达。
 *
 * @param extraRows 调用方附加的行（画在六个核心行之后）
 */
@Composable
fun SongActionSheet(
    song: Song,
    onDismiss: () -> Unit,
    extraRows: List<SongActionExtra> = emptyList(),
) {
    var showPlaylistPicker by remember(song.identityKey) { mutableStateOf(false) }

    BeansBottomSheet(onDismissRequest = onDismiss) {
        if (showPlaylistPicker) {
            LocalPlaylistPickerContent(
                song = song,
                onDismiss = {
                    showPlaylistPicker = false
                    onDismiss()
                },
            )
        } else {
            SongActionSheetContent(
                song = song,
                onAddToPlaylist = { showPlaylistPicker = true },
                extraRows = extraRows,
            )
        }
    }
}

/**
 * 面板内容（不自带浮层宿主，因此可以在渲染测试里直接组合，也能被别的浮层复用）。
 *
 * @param onAddToPlaylist 由宿主决定「就地换页」还是另开浮层
 */
@Composable
fun SongActionSheetContent(
    song: Song,
    modifier: Modifier = Modifier,
    onAddToPlaylist: () -> Unit = {},
    extraRows: List<SongActionExtra> = emptyList(),
) {
    val colors = BeansTheme.colors
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    // 订阅三个平台的收藏列表与封面索引：任一变化都会让右侧状态与菜单项跟着变。
    FavoritesStore.neteaseFavoriteSongs.collectAsState()
    FavoritesStore.qqFavoriteSongs.collectAsState()
    FavoritesStore.kugouFavoriteSongs.collectAsState()
    val covers by CoverStore.covers.collectAsState()

    val isFavorite = FavoritesStore.isLiked(song)
    val isDownloaded = DownloadManager.isDownloaded(song)
    val customCover = covers[song.identityKey]?.takeIf { it.isNotBlank() }
    val pickCover = rememberSongCoverPicker(song)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = SongActionSheetMaxHeight)
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = song.name,
            color = colors.label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = song.artists.ifBlank { beansLocalized("未知歌手", "Unknown artist") },
            color = colors.comment,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))

        songActionKinds(hasCustomCover = customCover != null).forEach { kind ->
            when (kind) {
                SongActionKind.PLAY_NEXT -> SongActionRow(
                    icon = Icons.Rounded.QueuePlayNext,
                    title = beansLocalized("下一首播放", "Play next"),
                    onClick = {
                        BeansHaptics.tap()
                        PlaybackController.playNext(song)
                        BeansToastCenter.show(beansLocalized("已加入下一首", "Will play next"))
                    },
                )

                SongActionKind.ADD_TO_PLAYLIST -> SongActionRow(
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    title = beansLocalized("添加到本地歌单", "Add to local playlist"),
                    onClick = {
                        BeansHaptics.tap()
                        onAddToPlaylist()
                    },
                )

                SongActionKind.REPLACE_COVER -> SongActionRow(
                    icon = Icons.Rounded.Image,
                    title = beansLocalized("更换自定义封面", "Custom cover"),
                    trailing = if (customCover != null) {
                        beansLocalized("已设置", "Set")
                    } else {
                        beansLocalized("未设置", "Not set")
                    },
                    onClick = {
                        BeansHaptics.tap()
                        pickCover()
                    },
                )

                SongActionKind.REMOVE_COVER -> SongActionRow(
                    icon = Icons.Rounded.Restore,
                    title = beansLocalized("移除自定义封面", "Remove custom cover"),
                    destructive = true,
                    onClick = {
                        BeansHaptics.tap()
                        val cleared = CoverStore.clear(song)
                        BeansToastCenter.show(
                            if (cleared) {
                                beansLocalized("已移除自定义封面", "Custom cover removed")
                            } else {
                                beansLocalized("没有可移除的封面", "No custom cover to remove")
                            },
                        )
                    },
                )

                SongActionKind.FAVORITE -> SongActionRow(
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    title = beansLocalized("收藏", "Favourite"),
                    trailing = favoriteActionTrailing(isFavorite),
                    tint = if (isFavorite) colors.accent else null,
                    onClick = {
                        BeansHaptics.tap()
                        scope.launch {
                            val ok = runCatching { FavoritesStore.toggle(song) }.getOrDefault(false)
                            val now = FavoritesStore.isLiked(song)
                            BeansToastCenter.show(favoriteToggleMessage(ok, now))
                        }
                    },
                )

                SongActionKind.DOWNLOAD -> SongActionRow(
                    icon = if (isDownloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                    title = beansLocalized("下载", "Download"),
                    trailing = downloadActionTrailing(isDownloaded),
                    onClick = {
                        BeansHaptics.tap()
                        if (DownloadManager.isDownloaded(song)) {
                            DownloadManager.delete(song)
                            BeansToastCenter.show(beansLocalized("已删除下载", "Download removed"))
                        } else {
                            DownloadManager.download(song)
                            BeansToastCenter.show(beansLocalized("开始下载", "Download started"))
                        }
                    },
                )
            }
        }

        extraRows.forEach { extra ->
            SongActionRow(
                icon = extra.icon,
                title = extra.title,
                trailing = extra.trailing,
                destructive = extra.destructive,
                onClick = {
                    BeansHaptics.tap()
                    extra.onSelect()
                },
            )
        }

        Spacer(Modifier.height(18.dp))
    }
}

/** 面板 / 播放页 `···` 共用的一行：左图标 + 标题 + 右侧状态。 */
@Composable
fun SongActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String = "",
    tint: Color? = null,
    destructive: Boolean = false,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val iconTint = when {
        destructive -> SongActionDestructiveTint
        tint != null -> tint
        else -> colors.accent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.98f,
                pressedBrightness = 0f,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            color = if (destructive) SongActionDestructiveTint else colors.label,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = trailing,
            color = colors.comment,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** 删除类动作的警示色（与主题强调色区分开）。 */
private val SongActionDestructiveTint = Color(red = 0.90f, green = 0.28f, blue = 0.26f)

/**
 * 每一行最右侧的 `⋮`：打开 [SongActionSheet]。
 *
 * 全仓库共用同一个按钮，因此无障碍标签只有一份（双语），不会出现某一行忘了写
 * `contentDescription` 的情况。
 */
@Composable
fun SongRowMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.88f,
                pressedBrightness = 0f,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = beansLocalized("更多操作", "More actions"),
            tint = colors.comment,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

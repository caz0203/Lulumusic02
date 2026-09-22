package com.lulu.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.api.KugouMusicApi
import com.lulu.music.data.api.NetEaseApi
import com.lulu.music.data.api.QQMusicApi
import com.lulu.music.data.auth.AuthStore
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.stats.UserStatsStore
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.PlayerOpenRequest
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansCoverImage
import com.lulu.music.ui.components.BeansEmptyState
import com.lulu.music.ui.components.BeansErrorState
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansLoadingState
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// MARK: - 歌单排序（Port of `PlaylistSortMode`）
// ---------------------------------------------------------------------------------------------

/** Port of iOS `PlaylistSortMode`. */
private enum class PlaylistSortMode(val zh: String, val en: String) {
    ORIGINAL("默认", "Default"),
    NAME("歌名", "Title"),
    DURATION("时长", "Duration");

    val displayName: String get() = beansLocalized(zh, en)
}

// ---------------------------------------------------------------------------------------------
// MARK: - 参考图里的固定数值
// ---------------------------------------------------------------------------------------------

/** 参考图：页头封面 ~112dp / 圆角 14dp。 */
private val PlaylistCoverSize = 112.dp
private val PlaylistCoverCorner = 14.dp

/** 参考图：两个大胶囊按钮高度 52dp。 */
private val PlaylistPillHeight = 52.dp

/** 参考图：搜索框圆角 14dp。 */
private val PlaylistSearchCorner = 14.dp

/** 参考图：曲目行封面 48dp / 圆角 8dp；行内上下留白 7dp。 */
private val TrackCoverSize = 48.dp
private val TrackCoverCorner = 8.dp
private val TrackRowVerticalPadding = 7.dp

/**
 * 「随机播放」那种浅灰填充：浅色模式下非常浅的灰，深色模式下是略亮于底色的表面色。
 *
 * 设计系统没有单独的 `lightGreyFill`，这里用 `glassFill` 提纯（去掉玻璃高光与描边）
 * 得到一块干净的浅灰 / 深灰，两种模式下都能和实心强调色按钮拉开层次。
 */
@Composable
private fun beansLightChipFill(): Color {
    val colors = BeansTheme.colors
    return if (colors.isDark) {
        colors.label.copy(alpha = 0.09f)
    } else {
        Color.Black.copy(alpha = 0.055f)
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 歌单详情
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `PlaylistView`（音乐库 → 歌单详情）。
 *
 * 加载顺序与 iOS 完全一致：酷狗走 `playlistSongs(listID: Int)`（**只在 API 边界把 id 转 Int**），
 * QQ 走 `playlistSongs(listID: Long)`，其余走网易云 `playlistTracks(id:)`；
 * QQ「我喜欢」网络返回为空时回退到本机已同步的 QQ 收藏，避免进页面变空白。
 *
 * 与 iOS 的差异：iOS 用 `SyncedPlaylistCache` 做本地曲目缓存（先展示缓存再后台刷新），
 * Android 数据层没有对应的缓存 Store，因此这里每次进入都走网络并呈现 加载 / 错误 / 空 三态。
 *
 * @param playCount 覆盖「播放 N」那一行的数值。默认 null 时按本机统计
 *   （[UserStatsStore.songPlayCounts] 里本歌单所有曲目的次数之和）实时计算；
 *   传入具体值则优先使用。数据源拿不到时该行不渲染 —— 不臆造播放量。
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    source: SongSource,
    onBack: () -> Unit,
    playCount: Int? = null,
) {
    val colors = BeansTheme.colors
    val uiStyle by SettingsStore.uiStyle.collectAsState()

    val neteasePlaylists by remember { mutableStateOf(AuthStore.playlists) }
    val qqFavorites by FavoritesStore.qqFavoriteSongs.collectAsState()

    var tracks by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    var searchText by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(PlaylistSortMode.ORIGINAL) }
    var showSort by remember { mutableStateOf(false) }

    // 歌单元数据：网易云用 AuthStore 的歌单列表补齐封面 / 创建者 / 曲目数；
    // QQ / 酷狗在 Android 数据层没有「按 id 取单个歌单」的接口，因此只拿到 id + 来源。
    val playlist: Playlist = remember(playlistId, source, neteasePlaylists) {
        if (source == SongSource.NET_EASE) {
            neteasePlaylists.firstOrNull { it.id == playlistId }
                ?: Playlist(id = playlistId, name = "歌单", source = source)
        } else {
            Playlist(id = playlistId, name = beansLocalized("歌单", "Playlist"), source = source)
        }
    }

    LaunchedEffect(playlistId, source, reloadKey) {
        loading = true
        errorMessage = null
        val result = runCatching {
            when (source) {
                SongSource.KUGOU -> KugouMusicApi.playlistSongs(playlistId.toInt())
                SongSource.QQ -> {
                    val songs = QQMusicApi.playlistSongs(listID = playlistId)
                    // 对应 iOS：云端收藏被风控 / 返回空时，至少展示已同步到本机的 QQ 收藏。
                    // iOS 会先判断 id == QQMusicAPI.qqLikedPlaylistID；Android 的 QQMusicApi 没有
                    // 公开该常量，因此这里对任意空结果都做同样的兜底（只会把本机收藏作为展示回退）。
                    if (songs.isEmpty()) FavoritesStore.qqFavoriteSongs.value else songs
                }

                SongSource.NET_EASE -> NetEaseApi.playlistTracks(id = playlistId)
            }
        }
        result.onSuccess {
            tracks = it
            errorMessage = null
        }.onFailure {
            if (tracks.isEmpty()) errorMessage = it.message ?: it.toString()
        }
        loading = false
    }

    // 歌单内搜索 + 排序（对应 iOS `displayedTracks`）
    val displayedTracks = remember(tracks, searchText, sortMode) {
        val keyword = searchText.trim().lowercase()
        val filtered = if (keyword.isEmpty()) {
            tracks
        } else {
            tracks.filter { song ->
                song.name.lowercase().contains(keyword) ||
                    song.artists.lowercase().contains(keyword) ||
                    song.album.lowercase().contains(keyword)
            }
        }
        when (sortMode) {
            PlaylistSortMode.ORIGINAL -> filtered
            PlaylistSortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            PlaylistSortMode.DURATION -> filtered.sortedBy { it.duration }
        }
    }

    val current by PlaybackController.currentSong.collectAsState()

    // `播放 N`：优先用调用方传入的值；否则用本机统计里本歌单所有曲目的次数之和。
    // `Playlist` 模型本身没有播放量字段，接口也不返回，所以这里只展示**真实**的本地累计值。
    val userStats by UserStatsStore.stats.collectAsState()
    val resolvedPlayCount: Int? = playCount ?: remember(tracks, userStats.songPlayCounts) {
        if (tracks.isEmpty()) {
            null
        } else {
            tracks.sumOf { song -> userStats.songPlayCounts[song.identityKey] ?: 0 }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- 顶部返回栏（参考图里只有返回 + 右侧工具按钮，歌单名走页头大标题） ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BeansGlassIconButton(
                icon = Icons.Rounded.ChevronLeft,
                onClick = {
                    BeansHaptics.tap()
                    onBack()
                },
                contentDescription = beansLocalized("返回", "Back"),
                size = 40.dp,
                style = uiStyle,
            )
            Spacer(Modifier.weight(1f))
            BeansGlassIconButton(
                icon = Icons.Rounded.SwapVert,
                onClick = {
                    BeansHaptics.tap()
                    showSort = true
                },
                contentDescription = beansLocalized("排序", "Sort"),
                size = 40.dp,
                active = sortMode != PlaylistSortMode.ORIGINAL,
                style = uiStyle,
            )
        }

        when {
            loading -> BeansLoadingState()

            errorMessage != null -> BeansErrorState(
                message = errorMessage ?: "",
                onRetry = { reloadKey++ },
                style = uiStyle,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 180.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    PlaylistHeader(
                        playlist = playlist,
                        trackCount = tracks.size,
                        playCount = resolvedPlayCount,
                        source = source,
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onOpenSort = { showSort = true },
                        onCreateFallback = {
                            BeansToastCenter.show(
                                beansLocalized(
                                    "暂无法获取歌单信息，已按曲目列表展示",
                                    "Playlist info unavailable; showing tracks",
                                ),
                            )
                        },
                        onPlayAll = {
                            if (displayedTracks.isNotEmpty()) {
                                BeansHaptics.tap()
                                PlaybackController.play(displayedTracks, 0)
                                PlayerOpenRequest.request()
                            }
                        },
                        onShuffle = {
                            if (displayedTracks.isNotEmpty()) {
                                BeansHaptics.tap()
                                PlaybackController.play(displayedTracks.shuffled(), 0)
                                PlayerOpenRequest.request()
                            }
                        },
                    )
                }

                if (displayedTracks.isEmpty()) {
                    item {
                        BeansEmptyState(
                            systemName = if (tracks.isEmpty()) "music.note.list" else "magnifyingglass",
                            text = if (tracks.isEmpty()) {
                                beansLocalized("这个歌单还没有歌曲", "This playlist has no songs yet")
                            } else {
                                beansLocalized("没有找到匹配歌曲", "No matching songs")
                            },
                        )
                    }
                } else {
                    itemsIndexed(displayedTracks, key = { _, song -> song.identityKey }) { index, song ->
                        TrackRow(
                            index = index + 1,
                            song = song,
                            isCurrent = current?.identityKey == song.identityKey,
                            onClick = {
                                BeansHaptics.tap()
                                PlaybackController.play(displayedTracks, index)
                                PlayerOpenRequest.request()
                            },
                            onLike = {
                                BeansHaptics.tap()
                                // 收藏写回按平台分流（FavoritesStore 内部处理，网易云失败会回滚）
                                toggleFavorite(song)
                            },
                        )
                    }
                }
            }
        }
    }

    if (showSort) {
        SortDialog(
            current = sortMode,
            onPick = {
                sortMode = it
                showSort = false
            },
            onDismiss = { showSort = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 页头（Port of `PlaylistView.header`）
// ---------------------------------------------------------------------------------------------

/**
 * 页头：大圆角封面 + 标题 / 创建者 / 曲目数 + 两个大胶囊按钮 + 灰色曲目数 + 搜索框。
 *
 * 参考图里页头是"贴在背景上"的，没有卡片底板；这里刻意不用 [BeansGlass]，
 * 只用留白和 14dp 圆角把层级做出来，两种主题下都不会出现多余的玻璃描边。
 */
@Composable
private fun PlaylistHeader(
    playlist: Playlist,
    trackCount: Int,
    playCount: Int?,
    source: SongSource,
    searchText: String,
    onSearchChange: (String) -> Unit,
    onOpenSort: () -> Unit,
    onCreateFallback: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    val colors = BeansTheme.colors
    val uiStyle by SettingsStore.uiStyle.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- 封面 + 元信息 ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BeansCoverImage(
                url = playlist.coverURL,
                size = PlaylistCoverSize,
                cornerRadius = PlaylistCoverCorner,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PlaylistCoverSize),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = playlist.name,
                    color = colors.label,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (playlist.creatorName.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 创建者头像点（参考图里是一个很小的圆形头像占位）
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(beansLightChipFill()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = colors.comment,
                                modifier = Modifier.size(9.dp),
                            )
                        }
                        Text(
                            text = playlist.creatorName,
                            color = colors.comment,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Text(
                    text = beansLocalized("$trackCount 首", "$trackCount songs"),
                    color = colors.comment,
                    fontSize = 12.sp,
                )

                // 参考图里的 `播放 1741`：本机统计里有该歌单的播放记录时才显示。
                if (playCount != null && playCount > 0) {
                    Text(
                        text = beansLocalized("播放 $playCount", "$playCount plays"),
                        color = colors.comment,
                        fontSize = 12.sp,
                    )
                } else {
                    SourceBadge(source = source)
                }

                // 元数据缺失时（QQ / 酷狗没有按 id 取歌单的接口）给一次解释性提示
                if (playlist.coverURL == null && playlist.creatorName.isBlank()) {
                    Row(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onCreateFallback() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = beansLocalized("歌单信息", "Playlist info"),
                            color = colors.comment.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.comment.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }

        // ---- 两个大胶囊按钮：播放全部（实心强调色，略宽）/ 随机播放（浅灰） ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlaylistPillButton(
                title = beansLocalized("播放全部", "Play all"),
                icon = Icons.Rounded.PlayArrow,
                prominent = true,
                onClick = onPlayAll,
                modifier = Modifier.weight(1.15f),
            )
            PlaylistPillButton(
                title = beansLocalized("随机播放", "Shuffle"),
                icon = Icons.Rounded.Shuffle,
                prominent = false,
                onClick = onShuffle,
                modifier = Modifier.weight(1f),
            )
        }

        // ---- 灰色曲目数 ----
        Text(
            text = beansLocalized("$trackCount 首", "$trackCount songs"),
            color = colors.comment,
            fontSize = 13.sp,
        )

        // ---- 搜索框（整行，圆角 14dp，浅灰底） ----
        PlaylistSearchField(
            value = searchText,
            onValueChange = onSearchChange,
            placeholder = beansLocalized("搜索歌单内歌曲", "Search songs in playlist"),
            onOpenSort = onOpenSort,
            sortActive = false,
            style = uiStyle,
        )
    }
}

/**
 * 参考图里的宽胶囊按钮（52dp 高、全圆角、图标在文字左侧）。
 *
 * 不复用 [com.lulu.music.ui.components.BeansGlassButton]：它固定
 * `padding(vertical = 12dp)` 且带玻璃高光/描边，达不到参考图那种「平整 + 52dp」的观感。
 */
@Composable
private fun PlaylistPillButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    prominent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val contentColor = if (prominent) Color.White else colors.label
    val fill = if (prominent) colors.accent else beansLightChipFill()

    Box(
        modifier = modifier
            .height(PlaylistPillHeight)
            .clip(CircleShape)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                color = contentColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * 歌单内搜索框：整行、圆角 14dp、浅灰填充、前置放大镜。
 *
 * 用 [BasicTextField] 而不是 `OutlinedTextField`：后者的边框 / 内边距 / 最小高度
 * 都与参考图不符，且无法通过参数完全去掉。
 */
@Composable
private fun PlaylistSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onOpenSort: () -> Unit,
    sortActive: Boolean,
    style: BeansUIStyle,
) {
    val colors = BeansTheme.colors
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(PlaylistSearchCorner))
            .background(beansLightChipFill())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = colors.comment,
            modifier = Modifier.size(16.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.label, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = colors.comment,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Clear,
                contentDescription = beansLocalized("清除", "Clear"),
                tint = colors.comment,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onValueChange("") },
            )
        }
        BeansGlassIconButton(
            icon = Icons.Rounded.SwapVert,
            onClick = onOpenSort,
            contentDescription = beansLocalized("排序", "Sort"),
            size = 30.dp,
            active = sortActive,
            style = style,
        )
    }
}

/** 来源小标（对应 iOS 的 `LibraryProvider` 品牌色）。 */
@Composable
private fun SourceBadge(source: SongSource) {
    val (label, tint) = when (source) {
        SongSource.NET_EASE -> "网易云" to Color(red = 0.93f, green = 0.22f, blue = 0.16f)
        SongSource.QQ -> "QQ音乐" to Color(red = 0.05f, green = 0.58f, blue = 0.42f)
        SongSource.KUGOU -> "酷狗" to Color(red = 0.12f, green = 0.58f, blue = 0.95f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 3.dp)
                .background(tint, CircleShape),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 曲目行
// ---------------------------------------------------------------------------------------------

/**
 * 曲目行：封面 48dp / 圆角 8dp，歌名（含紧跟其后的红色 VIP 小胶囊）与
 * 「歌手 · 时长」两行，整行可点。
 *
 * 与参考图的差异：参考图没有行号，但保留行号能帮用户对照歌单顺序，且不影响观感，
 * 因此这里保留了 `BeansNowPlayingIndicator` / 序号那一列。
 */
@Composable
private fun TrackRow(
    index: Int,
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLike: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember(song.identityKey) { MutableInteractionSource() }
    // 订阅三个平台的收藏列表，任一变化都会重算红心状态
    FavoritesStore.neteaseFavoriteSongs.collectAsState()
    FavoritesStore.qqFavoriteSongs.collectAsState()
    FavoritesStore.kugouFavoriteSongs.collectAsState()
    val liked = FavoritesStore.isLiked(song)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 4.dp, vertical = TrackRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent) {
                com.lulu.music.ui.components.BeansNowPlayingIndicator(height = 15.dp)
            } else {
                Text(
                    text = "$index",
                    color = colors.comment,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        BeansCoverImage(url = song.coverURL, size = TrackCoverSize, cornerRadius = TrackCoverCorner)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = song.name,
                    color = if (isCurrent) colors.accent else colors.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 参考图：VIP 小胶囊紧跟在歌名之后
                if (song.isVIP) {
                    BeansVIPBadge(text = "VIP")
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = song.artists.ifBlank { beansLocalized("未知歌手", "Unknown artist") },
                    color = colors.comment,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 参考图：时长右对齐、灰色 13sp
                Text(
                    text = if (song.duration > 0) song.formattedDuration else "--:--",
                    color = colors.comment,
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = beansLocalized("收藏", "Favourite"),
            tint = if (liked) colors.accent else colors.comment.copy(alpha = 0.55f),
            modifier = Modifier
                .size(22.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onLike() },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 排序选择
// ---------------------------------------------------------------------------------------------

@Composable
private fun SortDialog(
    current: PlaylistSortMode,
    onPick: (PlaylistSortMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BeansTheme.colors
    BeansBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = beansLocalized("歌单内排序", "Sort playlist"),
                color = colors.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            PlaylistSortMode.entries.forEach { mode ->
                val selected = mode == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onPick(mode) }
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = mode.displayName,
                        color = if (selected) colors.accent else colors.label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 收藏写入（平台分流由 FavoritesStore 内部处理）
// ---------------------------------------------------------------------------------------------

/**
 * 收藏切换的即发即弃入口（与 `BeansPlayerScreen` 的写法保持一致）。
 *
 * `FavoritesStore.toggle` 是 suspend，且网易云会在云端拒绝时自动回滚，因此这里只负责起协程 + 失败提示。
 */
private fun toggleFavorite(song: Song) {
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main,
    )
    scope.runFavoriteToggle(song)
}

private fun kotlinx.coroutines.CoroutineScope.runFavoriteToggle(song: Song) {
    launch {
        val ok = runCatching { FavoritesStore.toggle(song) }.getOrDefault(false)
        if (!ok && song.source == SongSource.NET_EASE) {
            BeansToastCenter.show(beansLocalized("收藏失败", "Could not update favourite"))
        }
    }
}

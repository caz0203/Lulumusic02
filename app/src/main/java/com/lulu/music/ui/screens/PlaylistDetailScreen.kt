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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
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
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansCoverImage
import com.lulu.music.ui.components.BeansEmptyState
import com.lulu.music.ui.components.BeansErrorState
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansLoadingState
import com.lulu.music.ui.components.BeansSurface
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.beansCardShadow
import com.lulu.music.ui.components.beansSongCountText
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
 */
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    source: SongSource,
    onBack: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // ---- 顶部返回栏 ----
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
            Spacer(Modifier.width(10.dp))
            Text(
                text = playlist.name,
                color = colors.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    PlaylistHeader(
                        playlist = playlist,
                        trackCount = tracks.size,
                        source = source,
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        sortMode = sortMode,
                        onOpenSort = { showSort = true },
                        onCreateFallback = {
                            BeansToastCenter.show(
                                beansLocalized("暂无法获取歌单信息，已按曲目列表展示", "Playlist info unavailable; showing tracks"),
                            )
                        },
                        onPlayAll = {
                            if (displayedTracks.isNotEmpty()) {
                                BeansHaptics.tap()
                                PlaybackController.play(displayedTracks, 0)
                            }
                        },
                        onShuffle = {
                            if (displayedTracks.isNotEmpty()) {
                                BeansHaptics.tap()
                                PlaybackController.play(displayedTracks.shuffled(), 0)
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

@Composable
private fun PlaylistHeader(
    playlist: Playlist,
    trackCount: Int,
    source: SongSource,
    searchText: String,
    onSearchChange: (String) -> Unit,
    sortMode: PlaylistSortMode,
    onOpenSort: () -> Unit,
    onCreateFallback: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    val colors = BeansTheme.colors
    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val nativeClean = uiStyle == BeansUIStyle.NATIVE_CLEAN

    BeansGlass(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .beansCardShadow(radius = 9.dp, y = 3.dp, shape = RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        style = uiStyle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (nativeClean) 18.dp else 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                BeansCoverImage(url = playlist.coverURL, size = 96.dp, cornerRadius = 18.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = playlist.name,
                        color = colors.label,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (playlist.creatorName.isNotBlank()) {
                        Text(
                            text = playlist.creatorName,
                            color = colors.comment,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = beansSongCountText(trackCount),
                            color = colors.comment,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        SourceBadge(source = source)
                        // 元数据缺失时（QQ / 酷狗没有按 id 取歌单的接口）给一次解释性提示
                        if (playlist.coverURL == null && playlist.creatorName.isBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = beansLocalized("歌单信息", "Playlist info"),
                                tint = colors.comment.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onCreateFallback() },
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BeansGlassButton(
                    title = "播放全部",
                    systemName = "play.fill",
                    onClick = onPlayAll,
                    prominent = true,
                    style = uiStyle,
                )
                BeansGlassButton(
                    title = "随机播放",
                    systemName = "shuffle",
                    onClick = onShuffle,
                    style = uiStyle,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BeansSurface(
                    shape = RoundedCornerShape(14.dp),
                    style = uiStyle,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = colors.comment,
                            modifier = Modifier.size(15.dp),
                        )
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = onSearchChange,
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = beansLocalized("搜索歌单内歌曲", "Search songs in playlist"),
                                    color = colors.comment,
                                    fontSize = 14.sp,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (searchText.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = beansLocalized("清除", "Clear"),
                                tint = colors.comment,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onSearchChange("") },
                            )
                        }
                    }
                }

                BeansGlassIconButton(
                    systemName = "arrow.up.arrow.down",
                    onClick = {
                        BeansHaptics.tap()
                        onOpenSort()
                    },
                    contentDescription = beansLocalized("排序", "Sort"),
                    size = 42.dp,
                    active = sortMode != PlaylistSortMode.ORIGINAL,
                    style = uiStyle,
                )
            }
        }
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
    Text(
        text = label,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .background(tint, CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 曲目行
// ---------------------------------------------------------------------------------------------

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
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(26.dp),
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
        BeansCoverImage(url = song.coverURL, size = 44.dp, cornerRadius = 10.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                color = if (isCurrent) colors.accent else colors.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.artists.ifBlank { "未知歌手" },
                    color = colors.comment,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.isVIP) {
                    Spacer(Modifier.width(5.dp))
                    BeansVIPBadge(text = "VIP")
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (song.duration > 0) song.formattedDuration else "--:--",
            color = colors.comment,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(4.dp))
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
    launch {        val ok = runCatching { FavoritesStore.toggle(song) }.getOrDefault(false)
        if (!ok && song.source == SongSource.NET_EASE) {
            BeansToastCenter.show(beansLocalized("收藏失败", "Could not update favourite"))
        }
    }
}

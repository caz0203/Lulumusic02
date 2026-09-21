package com.lulu.music.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lulu.music.data.api.KugouMusicApi
import com.lulu.music.data.api.NetEaseApi
import com.lulu.music.data.api.QQMusicApi
import com.lulu.music.data.auth.AuthStore
import com.lulu.music.data.auth.KugouMusicAuth
import com.lulu.music.data.auth.QQMusicAuth
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.data.store.PlayHistoryStore
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.PlayerOpenRequest
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansCapsule
import com.lulu.music.ui.components.BeansCoverImage
import com.lulu.music.ui.components.BeansEmptyState
import com.lulu.music.ui.components.BeansErrorState
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansLoadingState
import com.lulu.music.ui.components.BeansSectionHeader
import com.lulu.music.ui.components.BeansSurface
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.beansCardShadow
import com.lulu.music.ui.components.beansLocalSongCountText
import com.lulu.music.ui.components.beansSongCountText
import com.lulu.music.ui.components.beansTimeString
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// MARK: - 平台（Port of `LibraryProvider`）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `LibraryProvider`。
 *
 * iOS 用 `LinearGradient` 作为选中胶囊的底色；Compose 里等价物是 [Brush]，选中态直接画在胶囊上。
 * iOS 的 `brandImageName` 指向 Asset Catalog 里的品牌图（BrandNetease / BrandQQ / BrandKugou），
 * Android 侧没有这些资源，因此统一用同一套图标（与 iOS 未收录品牌图时的 `Image(systemName:)` 回退一致）。
 */
private enum class LibraryProvider(
    val titleZh: String,
    val titleEn: String,
    val source: SongSource,
) {
    NETEASE("网易云音乐", "NetEase Cloud Music", SongSource.NET_EASE),
    QQ("QQ音乐", "QQ Music", SongSource.QQ),
    KUGOU("酷狗音乐", "Kugou Music", SongSource.KUGOU);

    val displayName: String get() = beansLocalized(titleZh, titleEn)

    /** Port of `LibraryProvider.tint`. */
    val tint: Brush
        get() = when (this) {
            NETEASE -> Brush.linearGradient(
                listOf(Color(red = 0.93f, green = 0.22f, blue = 0.16f), Color(red = 0.80f, green = 0.15f, blue = 0.12f)),
            )

            QQ -> Brush.linearGradient(
                listOf(Color(red = 0.15f, green = 0.78f, blue = 0.55f), Color(red = 0.05f, green = 0.58f, blue = 0.42f)),
            )

            KUGOU -> Brush.linearGradient(
                listOf(Color(red = 0.12f, green = 0.58f, blue = 0.95f), Color(red = 0.02f, green = 0.32f, blue = 0.72f)),
            )
        }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 本地音乐库数据（iOS 侧为 LocalLibraryStore，Android 侧数据层未提供）
// ---------------------------------------------------------------------------------------------

/**
 * 本机歌单。iOS 的 `LocalPlaylist` 由 `LocalLibraryStore` 持久化（UserDefaults + JSON）。
 *
 * Android 数据层没有对应的 Store，这里在页面内维护同构的内存模型（`id` 用 [String]，
 * 对应 iOS 的 `UUID`），保证「本地音乐库」区块能按原样呈现；进程重启后本地歌单会清空，
 * 详见 `LocalMusicSectionContent` 的说明。
 */
private data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<Song>,
)

/** 本机音频（`MediaStore.Audio` 扫描结果；不属于 `Song`，无法交给播放引擎）。 */
private data class LocalAudioFile(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Double,
    val displayName: String,
    val uri: String,
)

// ---------------------------------------------------------------------------------------------
// MARK: - 音乐库
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `LibraryView`（「音乐库」标签页）。
 *
 * 区块顺序在 iOS `libraryOrder` 的基础上做了调整，并保留本地自定义排序：
 * 「我的歌单」→「最近播放」→「本地音乐库」，外加 iOS 音乐库页从收藏 Store 直接取数的
 * 「我的收藏」区块（`FavoritesStore.neteaseFavoriteSongs` / `qqFavoriteSongs` / `kugouFavoriteSongs`，
 * 固定排在所有可排序区块之前）。
 *
 * 与 iOS 的差异见 `LocalMusicSectionContent` / `LocalAudioSheet` 的 KDoc。
 */
@Composable
fun LibraryScreen() {
    val colors = BeansTheme.colors
    val navigator = LocalBeansNavigator.current

    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val nativeClean = uiStyle == BeansUIStyle.NATIVE_CLEAN

    val neteaseFavorites by FavoritesStore.neteaseFavoriteSongs.collectAsState()
    val qqFavorites by FavoritesStore.qqFavoriteSongs.collectAsState()
    val kugouFavorites by FavoritesStore.kugouFavoriteSongs.collectAsState()
    val history by PlayHistoryStore.history.collectAsState()

    val neteaseLoggedIn by AuthStore.loggedInFlow.collectAsState()
    val qqLoggedIn by QQMusicAuth.loggedInFlow.collectAsState()
    val kugouLoggedIn by KugouMusicAuth.loggedInFlow.collectAsState()

    val scope = rememberCoroutineScope()

    var source by rememberSaveable { mutableStateOf(LibraryProvider.NETEASE) }
    var libraryOrder by remember { mutableStateOf(listOf("我的歌单", "最近播放", "本地音乐库")) }

    // 网易云的歌单由 AuthStore 持有；QQ / 酷狗在 iOS 里由本页面自己拉取并缓存，这里保持一致。
    var qqPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var qqLoading by remember { mutableStateOf(false) }
    var kugouPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var kugouLoading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // 本机歌单（对应 LocalLibraryStore.shared.playlists）
    val localPlaylists = remember { mutableStateListOf<LocalPlaylist>() }
    var localNewName by remember { mutableStateOf("") }
    var showCreateLocal by remember { mutableStateOf(false) }
    var openLocalPlaylistId by remember { mutableStateOf<String?>(null) }
    var showLocalOrder by remember { mutableStateOf(false) }
    var localSyncMessage by remember { mutableStateOf("") }
    var localSyncing by remember { mutableStateOf(false) }
    var showSyncPicker by remember { mutableStateOf(false) }
    val syncTargets = remember { mutableStateListOf<LibraryProvider>() }

    // 本机音频（MediaStore）
    var audioFiles by remember { mutableStateOf<List<LocalAudioFile>>(emptyList()) }
    var audioScanned by remember { mutableStateOf(false) }
    var audioScanning by remember { mutableStateOf(false) }
    var audioDenied by remember { mutableStateOf(false) }
    var showAudioSheet by remember { mutableStateOf(false) }

    // 网易云新建 / 删除歌单
    var showCreateSynced by remember { mutableStateOf(false) }
    var syncedNewName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    var showHistory by remember { mutableStateOf(false) }
    var showSectionSort by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // ---- 权限：READ_MEDIA_AUDIO（API 33+）/ READ_EXTERNAL_STORAGE（API 32-） ----
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    suspend fun scanAudio() {
        audioScanning = true
        audioFiles = withContext(Dispatchers.IO) { queryLocalAudio(context) }
        audioScanned = true
        audioScanning = false
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            audioDenied = false
            scope.launch { scanAudio() }
        } else {
            audioDenied = true
            BeansToastCenter.show(beansLocalized("未获得音频读取权限，无法扫描本机音乐", "Audio permission denied — cannot scan on-device music"))
        }
    }

    fun ensureAudioScan() {
        val granted = ContextCompat.checkSelfPermission(context, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (!audioScanned && !audioScanning) scope.launch { scanAudio() }
        } else {
            audioPermissionLauncher.launch(audioPermission)
        }
    }

    // ---- 歌单同步（网易云缓存 / QQ / 酷狗） ----
    suspend fun loadQQPlaylists(force: Boolean) {
        if (!QQMusicAuth.isLoggedIn) {
            qqPlaylists = emptyList()
            qqLoading = false
            return
        }
        if (!force && qqPlaylists.isNotEmpty()) return
        qqLoading = qqPlaylists.isEmpty()
        // iOS 的 SyncedPlaylistCache 在 Android 数据层没有对应实现，因此这里总是走网络。
        val list = runCatching { QQMusicApi.userPlaylists(QQMusicAuth.uin) }.getOrNull()
        if (!list.isNullOrEmpty()) {
            qqPlaylists = list
            runCatching { FavoritesStore.syncQQFromCloud() }
        }
        qqLoading = false
    }

    suspend fun loadKugouPlaylists(force: Boolean) {
        if (!KugouMusicAuth.isLoggedIn) {
            kugouPlaylists = emptyList()
            kugouLoading = false
            return
        }
        if (!force && kugouPlaylists.isNotEmpty()) return
        kugouLoading = kugouPlaylists.isEmpty()
        val list = runCatching { KugouMusicApi.userPlaylists() }.getOrNull()
        if (!list.isNullOrEmpty()) kugouPlaylists = list
        kugouLoading = false
    }

    suspend fun refreshCurrentSource(force: Boolean) {
        loadError = null
        when (source) {
            LibraryProvider.NETEASE -> {
                if (!AuthStore.isLoggedIn) return
                val result = runCatching { AuthStore.loadLibrary(force = force) }
                loadError = result.exceptionOrNull()?.message
            }

            LibraryProvider.QQ -> loadQQPlaylists(force)
            LibraryProvider.KUGOU -> loadKugouPlaylists(force)
        }
    }

    LaunchedEffect(source) { refreshCurrentSource(force = false) }

    // 登录态变化后回到对应平台并强制刷新（对应 iOS 登录通知监听）
    LaunchedEffect(neteaseLoggedIn) {
        if (neteaseLoggedIn) {
            source = LibraryProvider.NETEASE
            loadError = null
            loadError = runCatching { AuthStore.loadLibrary(force = true) }.exceptionOrNull()?.message
        }
    }
    LaunchedEffect(qqLoggedIn) {
        if (qqLoggedIn) {
            source = LibraryProvider.QQ
            loadQQPlaylists(force = true)
        }
    }
    LaunchedEffect(kugouLoggedIn) {
        if (kugouLoggedIn) {
            source = LibraryProvider.KUGOU
            loadKugouPlaylists(force = true)
        }
    }

    // ---- 主滚动容器 ----
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = if (nativeClean) 24.dp else 16.dp,
            end = if (nativeClean) 24.dp else 16.dp,
            top = if (nativeClean) 20.dp else 8.dp,
            bottom = 190.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (nativeClean) 30.dp else 24.dp),
    ) {
        item {
            LibraryHeader(
                source = source,
                nativeClean = nativeClean,
                style = uiStyle,
                onPick = { picked ->
                    BeansHaptics.tap()
                    if (picked != source) source = picked
                },
                onOpenSectionSort = {
                    BeansHaptics.tap()
                    showSectionSort = true
                },
                onOpenPlaylistSort = {
                    BeansHaptics.tap()
                    showLocalOrder = true
                },
            )
        }

        item {
            LibraryProviderPicker(
                selected = source,
                neteaseEnabled = true,
                qqEnabled = QQMusicAuth.isLoggedIn || source == LibraryProvider.QQ,
                kugouEnabled = KugouMusicAuth.isLoggedIn || source == LibraryProvider.KUGOU,
                style = uiStyle,
                onSelect = { picked ->
                    BeansHaptics.tap()
                    if (picked != source) source = picked
                },
            )
        }

        // 「我的收藏」：iOS 音乐库直接读 FavoritesStore 的三个平台收藏
        item {
            FavoritesSection(
                songs = (qqFavorites + neteaseFavorites + kugouFavorites).distinctBy { it.identityKey },
                style = uiStyle,
                onPlayAll = { list ->
                    if (list.isNotEmpty()) {
                        PlaybackController.play(list, 0)
                        PlayerOpenRequest.request()
                        BeansHaptics.tap()
                    }
                },
            )
        }

        // `libraryOrder` 的默认顺序：我的歌单 → 最近播放 → 本地音乐库（「我的收藏」固定在最前）
        items(libraryOrder) { key ->
            when (key) {
                "我的歌单" -> SyncedPlaylistsSection(
                    source = source,
                    neteaseLoggedIn = neteaseLoggedIn,
                    qqLoggedIn = qqLoggedIn,
                    kugouLoggedIn = kugouLoggedIn,
                    neteasePlaylists = AuthStore.playlists,
                    qqPlaylists = qqPlaylists,
                    kugouPlaylists = kugouPlaylists,
                    loading = qqLoading || kugouLoading,
                    error = loadError,
                    style = uiStyle,
                    onRetry = { scope.launch { refreshCurrentSource(force = true) } },
                    onCreate = {
                        BeansHaptics.tap()
                        syncedNewName = ""
                        showCreateSynced = true
                    },
                    onOpen = { playlist -> navigator.openPlaylistById(playlist.id, playlist.source) },
                    onDelete = { playlist ->
                        BeansHaptics.tap()
                        pendingDelete = playlist
                    },
                    onLogin = {
                        BeansHaptics.tap()
                        navigator.openLogin()
                    },
                )

                "最近播放" -> HistorySection(
                    history = history,
                    style = uiStyle,
                    onPlay = { song ->
                        BeansHaptics.tap()
                        val list = PlayHistoryStore.history.value
                        val index = list.indexOfFirst { it.identityKey == song.identityKey }
                        PlaybackController.play(list, if (index >= 0) index else 0)
                        PlayerOpenRequest.request()
                    },
                    onOpenAll = {
                        BeansHaptics.tap()
                        showHistory = true
                    },
                )

                "本地音乐库" -> LocalMusicSectionContent(
                    playlists = localPlaylists,
                    syncing = localSyncing,
                    syncMessage = localSyncMessage,
                    style = uiStyle,
                    audioCount = audioFiles.size,
                    audioScanned = audioScanned,
                    onOpenAudio = {
                        ensureAudioScan()
                        showAudioSheet = true
                    },
                    onCreate = {
                        BeansHaptics.tap()
                        localNewName = ""
                        showCreateLocal = true
                    },
                    onOpenOrder = {
                        BeansHaptics.tap()
                        showLocalOrder = true
                    },
                    onOpenPlaylist = { id -> openLocalPlaylistId = id },
                    onSync = {
                        BeansHaptics.tap()
                        syncTargets.clear()
                        showSyncPicker = true
                    },
                )
            }
        }
    }

    // ---- 弹窗 / 浮层 ----

    if (showSectionSort) {
        OrderDialog(
            title = "音乐库板块排序",
            entries = libraryOrder,
            labelOf = { it },
            onMove = { from, to ->
                val list = libraryOrder.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)
                libraryOrder = list
            },
            onDismiss = { showSectionSort = false },
        )
    }

    if (showLocalOrder) {
        OrderDialog(
            title = "歌单排序",
            entries = localPlaylists.map { it.id },
            labelOf = { id -> localPlaylists.firstOrNull { it.id == id }?.name.orEmpty() },
            onMove = { from, to ->
                if (from in localPlaylists.indices && to in localPlaylists.indices) {
                    val item = localPlaylists.removeAt(from)
                    localPlaylists.add(to, item)
                }
            },
            onDismiss = { showLocalOrder = false },
        )
    }

    if (showCreateLocal) {
        NameDialog(
            title = "新建本地歌单",
            message = "本地歌单保存在设备上，覆盖安装不会丢失，不依赖平台账号",
            value = localNewName,
            onValueChange = { localNewName = it },
            onConfirm = {
                val name = localNewName.trim()
                if (name.isEmpty()) {
                    BeansToastCenter.show(beansLocalized("请输入歌单名称", "Enter a playlist name"))
                } else {
                    localPlaylists.add(
                        LocalPlaylist(id = "local-${System.currentTimeMillis()}", name = name, songs = emptyList()),
                    )
                    localNewName = ""
                    showCreateLocal = false
                }
            },
            onDismiss = { showCreateLocal = false },
        )
    }

    if (showCreateSynced) {
        NameDialog(
            title = "新建歌单",
            message = "输入歌单名称，创建后同步到${source.displayName}",
            value = syncedNewName,
            onValueChange = { syncedNewName = it },
            onConfirm = {
                val name = syncedNewName.trim()
                if (name.isEmpty()) {
                    BeansToastCenter.show(beansLocalized("请输入歌单名称", "Enter a playlist name"))
                } else {
                    showCreateSynced = false
                    scope.launch {
                        when (source) {
                            LibraryProvider.NETEASE -> {
                                if (!AuthStore.isLoggedIn) {
                                    BeansToastCenter.show(beansLocalized("请先登录后再创建歌单", "Sign in to create a playlist"))
                                    return@launch
                                }
                                val result = runCatching { NetEaseApi.createPlaylist(name) }
                                if (result.isSuccess) {
                                    BeansToastCenter.show("歌单「$name」已创建")
                                    runCatching { AuthStore.loadLibrary(force = true) }
                                } else {
                                    BeansToastCenter.show("创建失败：${result.exceptionOrNull()?.message.orEmpty()}")
                                }
                            }

                            LibraryProvider.QQ -> {
                                if (!QQMusicAuth.isLoggedIn) {
                                    BeansToastCenter.show(beansLocalized("请先登录 QQ 音乐后再创建歌单", "Sign in to QQ Music to create a playlist"))
                                    return@launch
                                }
                                val ok = runCatching { QQMusicApi.createPlaylist(name) }.getOrDefault(false)
                                if (ok) {
                                    BeansToastCenter.show("歌单「$name」已创建")
                                    loadQQPlaylists(force = true)
                                } else {
                                    BeansToastCenter.show(beansLocalized("创建失败，请确认已登录 QQ 音乐", "Failed — check your QQ Music sign-in"))
                                }
                            }

                            LibraryProvider.KUGOU ->
                                BeansToastCenter.show(beansLocalized("酷狗歌单暂不支持新建", "Kugou playlists cannot be created here"))
                        }
                    }
                }
            },
            onDismiss = { showCreateSynced = false },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("确定删除歌单「${target.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        when (source) {
                            LibraryProvider.NETEASE -> {
                                val ok = runCatching { NetEaseApi.deletePlaylist(target.id) }.getOrDefault(false)
                                if (ok) {
                                    BeansToastCenter.show("已删除歌单「${target.name}」")
                                    runCatching { AuthStore.loadLibrary(force = true) }
                                } else {
                                    BeansToastCenter.show(beansLocalized("删除失败，请稍后再试", "Delete failed, try again later"))
                                }
                            }

                            LibraryProvider.QQ -> {
                                // QQ 的删除接口接收 Int 目录 id，这里与 Kugou 一样只在 API 边界转换。
                                val ok = runCatching { QQMusicApi.deletePlaylist(target.id.toInt()) }.getOrDefault(false)
                                if (ok) {
                                    BeansToastCenter.show("已删除歌单「${target.name}」")
                                    loadQQPlaylists(force = true)
                                } else {
                                    BeansToastCenter.show(beansLocalized("删除失败，请确认已登录 QQ 音乐", "Delete failed — check your QQ Music sign-in"))
                                }
                            }

                            LibraryProvider.KUGOU ->
                                BeansToastCenter.show(beansLocalized("酷狗歌单暂不支持删除", "Kugou playlists cannot be deleted here"))
                        }
                    }
                }) { Text(beansLocalized("删除", "Delete"), color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(beansLocalized("取消", "Cancel")) }
            },
        )
    }

    if (showSyncPicker) {
        SyncPlatformDialog(
            selected = syncTargets,
            onToggle = { provider ->
                if (syncTargets.contains(provider)) syncTargets.remove(provider) else syncTargets.add(provider)
            },
            onDismiss = { showSyncPicker = false },
            onConfirm = {
                val targets = syncTargets.toList()
                showSyncPicker = false
                if (targets.size < 2) {
                    BeansToastCenter.show(beansLocalized("至少选择两个平台", "Pick at least two platforms"))
                } else {
                    scope.launch {
                        localSyncing = true
                        localSyncMessage = ""
                        val outcome = syncLocalPlaylist(targets)
                        localSyncing = false
                        localSyncMessage = outcome.detail
                        if (outcome.songs.isEmpty()) {
                            BeansToastCenter.show("没有获取到该平台的喜欢歌曲")
                        } else {
                            val index = localPlaylists.indexOfFirst { it.name == outcome.playlistName }
                            val existing = if (index >= 0) localPlaylists[index].songs else emptyList()
                            val seen = existing.map { it.identityKey }.toMutableSet()
                            val added = outcome.songs.filter { seen.add(it.identityKey) }
                            val merged = existing + added
                            if (index >= 0) {
                                localPlaylists[index] = localPlaylists[index].copy(songs = merged)
                            } else {
                                localPlaylists.add(
                                    LocalPlaylist(
                                        id = "local-${System.currentTimeMillis()}",
                                        name = outcome.playlistName,
                                        songs = merged,
                                    ),
                                )
                            }
                            BeansToastCenter.show(
                                "已同步 ${outcome.songs.size} 首，新增 ${added.size} 首到本地歌单「${outcome.playlistName}」",
                            )
                        }
                    }
                }
            },
        )
    }

    if (showAudioSheet) {
        BeansBottomSheet(onDismissRequest = { showAudioSheet = false }) {
            LocalAudioSheet(
                files = audioFiles,
                scanning = audioScanning,
                denied = audioDenied,
                onRequestPermission = { audioPermissionLauncher.launch(audioPermission) },
                onDismiss = { showAudioSheet = false },
                colors = colors,
            )
        }
    }

    if (showHistory) {
        BeansBottomSheet(onDismissRequest = { showHistory = false }) {
            HistorySheetContent(
                history = history,
                onPlay = { index ->
                    BeansHaptics.tap()
                    val list = PlayHistoryStore.history.value
                    if (index in list.indices) {
                        PlaybackController.play(list, index)
                        PlayerOpenRequest.request()
                        // 弹层是独立 Dialog 窗口，会盖在新打开的播放页上。
                        showHistory = false
                    }
                },
                onClear = {
                    BeansHaptics.tap()
                    PlayHistoryStore.clear()
                    BeansToastCenter.show(beansLocalized("已清空播放历史", "Play history cleared"))
                },
            )
        }
    }

    openLocalPlaylistId?.let { id ->
        val playlist = localPlaylists.firstOrNull { it.id == id }
        if (playlist == null) {
            openLocalPlaylistId = null
        } else {
            LocalPlaylistDetailDialog(
                playlist = playlist,
                allSongs = (qqFavorites + neteaseFavorites + kugouFavorites).distinctBy { it.identityKey },
                onDismiss = { openLocalPlaylistId = null },
                onPlayAll = { songs ->
                    if (songs.isNotEmpty()) {
                        BeansHaptics.tap()
                        PlaybackController.play(songs, 0)
                        PlayerOpenRequest.request()
                        openLocalPlaylistId = null
                    }
                },
                onShuffle = { songs ->
                    if (songs.isNotEmpty()) {
                        BeansHaptics.tap()
                        PlaybackController.play(songs.shuffled(), 0)
                        PlayerOpenRequest.request()
                        openLocalPlaylistId = null
                    }
                },
                onPlaySong = { songs, index ->
                    PlaybackController.play(songs, index)
                    PlayerOpenRequest.request()
                    openLocalPlaylistId = null
                },
                onAddSongs = { songs ->
                    val index = localPlaylists.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        val existing = localPlaylists[index].songs
                        val seen = existing.map { it.identityKey }.toMutableSet()
                        val added = songs.filter { seen.add(it.identityKey) }
                        localPlaylists[index] = localPlaylists[index].copy(songs = existing + added)
                        BeansHaptics.success()
                        BeansToastCenter.show(
                            if (added.size == songs.size) {
                                "已添加 ${added.size} 首"
                            } else {
                                "已添加 ${added.size} 首（重复歌曲已跳过）"
                            },
                        )
                    }
                },
                onRemoveSong = { song ->
                    val index = localPlaylists.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        BeansHaptics.tap()
                        localPlaylists[index] = localPlaylists[index].copy(
                            songs = localPlaylists[index].songs.filterNot { it.identityKey == song.identityKey },
                        )
                    }
                },
                onRename = { name ->
                    val index = localPlaylists.indexOfFirst { it.id == id }
                    if (index >= 0) localPlaylists[index] = localPlaylists[index].copy(name = name)
                },
                onDelete = {
                    localPlaylists.removeAll { it.id == id }
                    openLocalPlaylistId = null
                    BeansToastCenter.show("已删除本地歌单")
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 页头（Port of `LibraryView.header` / `appleHeader`）
// ---------------------------------------------------------------------------------------------

@Composable
private fun LibraryHeader(
    source: LibraryProvider,
    nativeClean: Boolean,
    style: BeansUIStyle,
    onPick: (LibraryProvider) -> Unit,
    onOpenSectionSort: () -> Unit,
    onOpenPlaylistSort: () -> Unit,
) {
    val colors = BeansTheme.colors
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = if (nativeClean) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (nativeClean) 9.dp else 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Port of `libraryTitleButton`：标题 + chevron.down 打开平台菜单
            Row(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { menuOpen = true },
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "音乐库",
                    color = colors.label,
                    fontSize = (if (nativeClean) 34 else 30).sp,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = beansLocalized("切换平台", "Switch platform"),
                    tint = colors.comment.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // Port of iOS 页头的板块排序 / 歌单排序按钮
            BeansGlassIconButton(
                systemName = "arrow.up.arrow.down",
                onClick = onOpenSectionSort,
                contentDescription = "音乐库板块排序",
                size = 40.dp,
                style = style,
            )
            Spacer(Modifier.width(8.dp))
            BeansGlassIconButton(
                systemName = "list.bullet",
                onClick = onOpenPlaylistSort,
                contentDescription = "歌单排序",
                size = 40.dp,
                style = style,
            )

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                LibraryProvider.entries.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.displayName, fontSize = 14.sp) },
                        leadingIcon = {
                            if (candidate == source) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = colors.comment,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onPick(candidate)
                        },
                    )
                }
            }
        }

        Text(
            text = librarySubtitle(source),
            color = colors.comment,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (nativeClean) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.label.copy(alpha = 0.10f))
            )
        }
    }
}

/** Port of `LibraryView.librarySubtitle`. */
private fun librarySubtitle(source: LibraryProvider): String = when (source) {
    LibraryProvider.NETEASE -> beansLocalized("网易云音乐歌单", "NetEase Cloud Music Playlists")
    LibraryProvider.QQ -> "QQ 音乐收藏与歌单"
    LibraryProvider.KUGOU -> "酷狗云端歌单"
}

// ---------------------------------------------------------------------------------------------
// MARK: - 平台选择（Port of `LibraryView.providerPicker`）
// ---------------------------------------------------------------------------------------------

@Composable
private fun LibraryProviderPicker(
    selected: LibraryProvider,
    neteaseEnabled: Boolean,
    qqEnabled: Boolean,
    kugouEnabled: Boolean,
    style: BeansUIStyle,
    onSelect: (LibraryProvider) -> Unit,
) {
    val colors = BeansTheme.colors
    val providers = listOfNotNull(
        LibraryProvider.NETEASE.takeIf { neteaseEnabled },
        LibraryProvider.QQ.takeIf { qqEnabled },
        LibraryProvider.KUGOU.takeIf { kugouEnabled },
    )

    BeansSurface(shape = CircleShape, style = style, modifier = Modifier.beansCardShadow(radius = 6.dp, y = 2.dp, shape = CircleShape)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            providers.forEach { provider ->
                val active = provider == selected
                val interaction = remember(provider) { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .then(
                            if (active) {
                                Modifier.background(brush = provider.tint, shape = CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable(interactionSource = interaction, indication = null) { onSelect(provider) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = if (active) Color.White else colors.comment,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = provider.displayName,
                            color = if (active) Color.White else colors.comment,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 我的收藏
// ---------------------------------------------------------------------------------------------

/**
 * 「我的收藏」。iOS 的音乐库页把 `FavoritesStore` 的三个平台收藏作为红心入口展示，
 * 这里对齐同一数据源（跨平台去重后按平台分色）。
 */
@Composable
private fun FavoritesSection(
    songs: List<Song>,
    style: BeansUIStyle,
    onPlayAll: (List<Song>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BeansSectionHeader(
            title = "我的收藏",
            trailing = if (songs.isEmpty()) null else beansSongCountText(songs.size),
            style = style,
            onTrailingTap = { onPlayAll(songs) },
        )
        if (songs.isEmpty()) {
            BeansEmptyState(
                systemName = "heart",
                text = "还没有收藏的歌曲，在播放页点亮红心即可同步到这里",
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(songs, key = { it.identityKey }) { song ->
                    FavoriteCard(song = song, onPlay = { onPlayAll(songs) })
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(song: Song, onPlay: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(116.dp)
            .clickable(interactionSource = interaction, indication = null) {
                BeansHaptics.tap()
                onPlay()
            },
    ) {
        Box {
            BeansCoverImage(url = song.coverURL, size = 116.dp, cornerRadius = 16.dp)
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(colors.accent.copy(alpha = 0.85f), CircleShape)
                    .padding(3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = song.name,
            color = colors.label,
            fontSize = 13.sp,
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
                Spacer(Modifier.width(4.dp))
                BeansVIPBadge(text = "VIP")
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 本地音乐库（Port of `LocalMusicSection`）
// ---------------------------------------------------------------------------------------------

/**
 * Port of iOS `LocalMusicSection` 的列表部分（同步入口 / 本机歌单 / 新建 / 排序）。
 *
 * 与 iOS 的差异（数据层缺口，非 UI 取舍）：
 * 1. iOS 的 `LocalLibraryStore` 把本机歌单持久化到 UserDefaults；Android 数据层没有该 Store，
 *    因此这里的本机歌单是页面级内存状态：切到别的标签页再回来（`LibraryScreen` 重组）后创建的歌单会丢失。
 * 2. iOS 的「搜索添加歌曲」（`LocalSearchAddSheet`）走各平台搜索接口；Android 侧改为从
 *    「我的收藏」中挑选（`LocalPlaylistDetailDialog` 的「从收藏添加」），避免再引入一套搜索状态机。
 */
@Composable
private fun LocalMusicSectionContent(
    playlists: List<LocalPlaylist>,
    syncing: Boolean,
    syncMessage: String,
    style: BeansUIStyle,
    audioCount: Int,
    audioScanned: Boolean,
    onOpenAudio: () -> Unit,
    onCreate: () -> Unit,
    onOpenOrder: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onSync: () -> Unit,
) {
    val colors = BeansTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本地音乐库",
                color = colors.label,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            BeansGlassIconButton(
                icon = Icons.Rounded.Add,
                onClick = onCreate,
                contentDescription = "新建本地歌单",
                size = 34.dp,
                active = true,
                style = style,
            )
            Spacer(Modifier.width(8.dp))
            BeansGlassIconButton(
                systemName = "arrow.up.arrow.down",
                onClick = onOpenOrder,
                contentDescription = "排序本地歌单",
                size = 34.dp,
                style = style,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BeansGlassButton(
                title = if (syncing) "正在同步歌单…" else "一键同步歌单",
                systemName = "arrow.triangle.2.circlepath",
                onClick = { if (!syncing) onSync() },
                prominent = true,
                style = style,
            )
            Text(
                text = "选择两个或三个平台，合并同步到一个本地歌单",
                color = colors.comment,
                fontSize = 11.sp,
            )
        }

        if (syncMessage.isNotEmpty()) {
            Text(
                text = syncMessage,
                color = colors.sage,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        LocalAudioEntry(
            count = audioCount,
            scanned = audioScanned,
            onOpen = onOpenAudio,
            style = style,
        )

        if (playlists.isEmpty()) {
            BeansEmptyState(
                systemName = "folder",
                text = "还没有本地歌单\n新建一个歌单，把喜欢的歌曲收藏到本机",
            )
        } else {
            BeansGlass(
                modifier = Modifier
                    .fillMaxWidth()
                    .beansCardShadow(radius = 8.dp, y = 3.dp, shape = RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                style = style,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    playlists.forEach { playlist ->
                        LocalPlaylistRow(playlist = playlist, onClick = { onOpenPlaylist(playlist.id) })
                        Divider()
                    }
                }
            }
        }
    }
}

/** 本机音频入口（iOS 无此区块；`MediaStore` 扫描结果的展示入口）。 */
@Composable
private fun LocalAudioEntry(
    count: Int,
    scanned: Boolean,
    onOpen: () -> Unit,
    style: BeansUIStyle,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = interaction, indication = null) { onOpen() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(colors.accent.copy(alpha = 0.75f), colors.accent.copy(alpha = 0.35f)),
                    ),
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "本机音频",
                color = colors.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (scanned) {
                    if (count == 0) "未在本机找到音频文件" else "$count 首 · 本机"
                } else {
                    "点击扫描本机音乐（需要音频读取权限）"
                },
                color = colors.comment,
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = colors.comment.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun LocalPlaylistRow(playlist: LocalPlaylist, onClick: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember(playlist.id) { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null) {
                BeansHaptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(colors.accent.copy(alpha = 0.75f), colors.accent.copy(alpha = 0.35f)),
                    ),
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.QueueMusic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = colors.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = beansLocalSongCountText(playlist.songs.size),
                color = colors.comment,
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = colors.comment.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 我的歌单（同步自登录账号）
// ---------------------------------------------------------------------------------------------

@Composable
private fun SyncedPlaylistsSection(
    source: LibraryProvider,
    neteaseLoggedIn: Boolean,
    qqLoggedIn: Boolean,
    kugouLoggedIn: Boolean,
    neteasePlaylists: List<Playlist>,
    qqPlaylists: List<Playlist>,
    kugouPlaylists: List<Playlist>,
    loading: Boolean,
    error: String?,
    style: BeansUIStyle,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    onLogin: () -> Unit,
) {
    val title = when (source) {
        LibraryProvider.NETEASE -> "我的歌单"
        LibraryProvider.QQ -> "我的 QQ 歌单"
        LibraryProvider.KUGOU -> "我的酷狗歌单"
    }
    val loggedIn = when (source) {
        LibraryProvider.NETEASE -> neteaseLoggedIn
        LibraryProvider.QQ -> qqLoggedIn
        LibraryProvider.KUGOU -> kugouLoggedIn
    }
    val playlists = when (source) {
        LibraryProvider.NETEASE -> neteasePlaylists
        LibraryProvider.QQ -> qqPlaylists
        LibraryProvider.KUGOU -> kugouPlaylists
    }
    val loginHint = when (source) {
        LibraryProvider.NETEASE -> "登录网易云音乐后即可查看你的歌单"
        LibraryProvider.QQ -> "登录 QQ 音乐后即可查看你的歌单"
        LibraryProvider.KUGOU -> "登录酷狗音乐后即可同步云端歌单"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BeansSectionHeader(
            title = title,
            trailing = if (loggedIn && source != LibraryProvider.KUGOU) {
                if (playlists.isEmpty()) "新建" else "新建 · ${playlists.size} 个"
            } else if (loggedIn && playlists.isNotEmpty()) {
                "${playlists.size} 个"
            } else {
                null
            },
            style = style,
            onTrailingTap = onCreate,
        )

        when {
            !loggedIn -> BeansEmptyState(systemName = "music.note.list", text = loginHint)

            loading && playlists.isEmpty() -> BeansLoadingState()

            error != null && playlists.isEmpty() -> BeansErrorState(
                message = error,
                onRetry = onRetry,
                style = style,
            )

            playlists.isEmpty() -> BeansEmptyState(
                systemName = "music.note.list",
                text = when (source) {
                    LibraryProvider.NETEASE -> "还没有歌单，点「新建」创建一个"
                    LibraryProvider.QQ -> "暂无 QQ 歌单"
                    LibraryProvider.KUGOU -> "暂未同步到酷狗歌单，下拉刷新试试"
                },
            )

            else -> BeansGlass(
                modifier = Modifier
                    .fillMaxWidth()
                    .beansCardShadow(radius = 8.dp, y = 3.dp, shape = RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                style = style,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    playlists.forEach { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            showDelete = source != LibraryProvider.KUGOU,
                            onClick = { onOpen(playlist) },
                            onDelete = { onDelete(playlist) },
                        )
                        Divider()
                    }
                }
            }
        }

        // 未登录时的登录入口（iOS 里由「我的」页承担，这里补一个就近入口）
        if (!loggedIn) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BeansGlassButton(
                    title = "登录${source.displayName}",
                    systemName = "person.fill",
                    onClick = onLogin,
                    style = style,
                    haptic = true,
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    showDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember(playlist.id, playlist.source) { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null) {
                BeansHaptics.tap()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BeansCoverImage(url = playlist.coverURL, size = 56.dp, cornerRadius = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = colors.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = beansSongCountText(playlist.trackCount),
                    color = colors.comment,
                    fontSize = 12.sp,
                )
                if (playlist.isNetEaseLikedPlaylist) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        if (showDelete) {
            BeansGlassIconButton(
                icon = Icons.Rounded.Delete,
                onClick = onDelete,
                contentDescription = "删除歌单",
                size = 32.dp,
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = colors.comment.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 最近播放（Port of `LibraryView.historySection` + `HistoryView`）
// ---------------------------------------------------------------------------------------------

@Composable
private fun HistorySection(
    history: List<Song>,
    style: BeansUIStyle,
    onPlay: (Song) -> Unit,
    onOpenAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BeansSectionHeader(
            title = "最近播放",
            trailing = if (history.isEmpty()) null else "查看全部",
            style = style,
            onTrailingTap = onOpenAll,
        )
        if (history.isEmpty()) {
            BeansEmptyState(systemName = "clock", text = "暂无播放记录")
        } else {
            BeansGlass(
                modifier = Modifier
                    .fillMaxWidth()
                    .beansCardShadow(radius = 8.dp, y = 3.dp, shape = RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                style = style,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    // iOS 只展示前 5 首，整页在 HistoryView 里
                    history.take(5).forEach { song ->
                        SongRow(song = song, onClick = { onPlay(song) })
                        Divider()
                    }
                }
            }
        }
    }
}

/** Port of `HistoryView` 的列表内容（Android 侧以底部浮层承载）。 */
@Composable
private fun HistorySheetContent(
    history: List<Song>,
    onPlay: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val colors = BeansTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "最近播放",
                color = colors.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) {
                BeansGlassButton(
                    title = "清空",
                    systemName = "trash",
                    onClick = onClear,
                )
            }
        }
        if (history.isEmpty()) {
            BeansEmptyState(systemName = "clock", text = "暂无播放历史")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            ) {
                itemsIndexed(history, key = { _, song -> song.identityKey }) { index, song ->
                    SongRow(song = song, onClick = { onPlay(index) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 本机音频（MediaStore.Audio 扫描结果）
// ---------------------------------------------------------------------------------------------

/**
 * 本机音频列表。
 *
 * **播放限制（重要）**：Android 的播放链路是
 * `Song` → `SongUri.encode`（`beans://song?src=&id=`）→ `MediaResolver` 按 [SongSource] 解析真实地址。
 * `Song` 没有任何字段能携带本地文件 URI，`MediaResolver` 也只认网易云 / QQ / 酷狗三种来源，
 * 因此**本机音频在当前数据层与播放引擎契约下无法播放**。这里只呈现扫描结果 + 元数据，
 * 点击时明确提示，不做任何「伪造网易云歌曲」的写法。
 */
@Composable
private fun LocalAudioSheet(
    files: List<LocalAudioFile>,
    scanning: Boolean,
    denied: Boolean,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit,
    colors: com.lulu.music.ui.theme.BeansColors,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本机音频",
                    color = colors.label,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (files.isEmpty()) "MediaStore 扫描" else "${files.size} 首 · MediaStore",
                    color = colors.comment,
                    fontSize = 12.sp,
                )
            }
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                tint = colors.comment,
                modifier = Modifier.size(18.dp),
            )
        }

        when {
            denied -> BeansEmptyState(
                systemName = "lock.fill",
                text = "需要音频读取权限\n授权后即可扫描本机音乐",
            )

            scanning -> BeansLoadingState()

            files.isEmpty() -> BeansEmptyState(
                systemName = "folder",
                text = "本机没有找到音频文件",
            )

            else -> {
                Text(
                    text = "点击任意歌曲即可离线播放本机音频",
                    color = colors.comment,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    items(files, key = { it.id }) { file ->
                        LocalAudioRow(file = file, queue = files, onDismiss = onDismiss)
                    }
                }
            }
        }

        if (denied) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                BeansGlassButton(
                    title = "授予权限",
                    systemName = "lock.fill",
                    onClick = onRequestPermission,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LocalAudioRow(file: LocalAudioFile, queue: List<LocalAudioFile>, onDismiss: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember(file.id) { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null) {
                BeansHaptics.tap()
                // Build a Song carrying the device URI; the player uses `localUri` directly and
                // never contacts a platform API for these.
                val songs = queue.map { f ->
                    Song(
                        id = f.id,
                        name = f.title,
                        artists = f.artist,
                        album = f.album,
                        duration = f.durationSeconds,
                        source = SongSource.NET_EASE,
                        localUri = f.uri,
                    )
                }
                val index = queue.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                PlaybackController.play(songs, index)
                PlayerOpenRequest.request()
                // 本机音频弹层是独立 Dialog 窗口，会盖在新打开的播放页上。
                onDismiss()
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(colors.glassFill, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = colors.comment,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.title,
                color = colors.label,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(file.artist, file.album)
                    .filter { it.isNotBlank() && it != "<unknown>" }
                    .joinToString(" · ")
                    .ifBlank { file.displayName },
                color = colors.comment,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = beansTimeString(file.durationSeconds),
            color = colors.comment,
            fontSize = 11.sp,
        )
    }
}

/** `MediaStore.Audio` 扫描（`DISPLAY_NAME` 需要 API 29+，低版本回退到 `DATA` 的文件名）。 */
private fun queryLocalAudio(context: Context): List<LocalAudioFile> {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
    )

    val out = ArrayList<LocalAudioFile>()
    // 读取失败（无权限 / 媒体库异常）时静默返回空列表，由 UI 呈现空态，绝不崩溃
    runCatching {
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val data = cursor.getString(dataCol).orEmpty()
                val displayName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                    }.getOrNull().orEmpty()
                } else {
                    data.substringAfterLast('/')
                }
                out.add(
                    LocalAudioFile(
                        id = id,
                        title = cursor.getString(titleCol).orEmpty().ifBlank { displayName },
                        artist = cursor.getString(artistCol).orEmpty(),
                        album = cursor.getString(albumCol).orEmpty(),
                        durationSeconds = cursor.getLong(durationCol) / 1000.0,
                        displayName = displayName,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                    ),
                )
            }
        }
    }
    return out
}

// ---------------------------------------------------------------------------------------------
// MARK: - 本地歌单详情（Port of `LocalPlaylistDetailSheet`）
// ---------------------------------------------------------------------------------------------

@Composable
private fun LocalPlaylistDetailDialog(
    playlist: LocalPlaylist,
    allSongs: List<Song>,
    onDismiss: () -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onPlaySong: (List<Song>, Int) -> Unit,
    onAddSongs: (List<Song>) -> Unit,
    onRemoveSong: (Song) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BeansTheme.colors
    var keyword by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(playlist.name) }

    val visible = remember(playlist.songs, keyword) {
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) {
            playlist.songs
        } else {
            playlist.songs.filter {
                it.name.lowercase().contains(kw) ||
                    it.artists.lowercase().contains(kw) ||
                    it.album.lowercase().contains(kw)
            }
        }
    }

    BeansBottomSheet(
        onDismissRequest = onDismiss,
        detents = listOf(
            com.lulu.music.ui.components.BeansDetent.Medium,
            com.lulu.music.ui.components.BeansDetent.Large,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        color = colors.label,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = beansLocalSongCountText(playlist.songs.size),
                        color = colors.comment,
                        fontSize = 12.sp,
                    )
                }
                BeansGlassIconButton(
                    icon = Icons.Rounded.Delete,
                    onClick = onDelete,
                    contentDescription = "删除歌单",
                    size = 34.dp,
                )
            }

            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BeansGlassButton(
                    title = "播放全部",
                    systemName = "play.fill",
                    onClick = { onPlayAll(visible) },
                    prominent = true,
                )
                BeansGlassButton(
                    title = "随机播放",
                    systemName = "shuffle",
                    onClick = { onShuffle(visible) },
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    singleLine = true,
                    placeholder = { Text(beansLocalized("搜索本地歌单歌曲", "Search songs in playlist"), fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BeansGlassButton(
                    title = "从收藏添加",
                    systemName = "plus",
                    onClick = { showAdd = true },
                )
                BeansGlassButton(
                    title = "重命名",
                    systemName = "square.and.pencil",
                    onClick = {
                        renameText = playlist.name
                        showRename = true
                    },
                )
            }

            Spacer(Modifier.height(10.dp))

            if (visible.isEmpty()) {
                BeansEmptyState(
                    systemName = "magnifyingglass",
                    text = if (playlist.songs.isEmpty()) "这个本地歌单还是空的，点「从收藏添加」加入歌曲" else "没有找到匹配歌曲",
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(visible, key = { _, song -> song.identityKey }) { index, song ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                SongRow(
                                    song = song,
                                    onClick = {
                                        BeansHaptics.tap()
                                        onPlaySong(visible, index)
                                    },
                                )
                            }
                            BeansGlassIconButton(
                                icon = Icons.Rounded.Delete,
                                onClick = { onRemoveSong(song) },
                                contentDescription = "移除",
                                size = 30.dp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showAdd) {
        AddSongsDialog(
            candidates = allSongs,
            existingKeys = playlist.songs.map { it.identityKey }.toSet(),
            onDismiss = { showAdd = false },
            onConfirm = { picked ->
                onAddSongs(picked)
                showAdd = false
            },
        )
    }

    if (showRename) {
        NameDialog(
            title = "重命名歌单",
            message = "本地歌单名称只保存在本机",
            value = renameText,
            onValueChange = { renameText = it },
            onConfirm = {
                val name = renameText.trim()
                if (name.isEmpty()) {
                    BeansToastCenter.show(beansLocalized("请输入歌单名称", "Enter a playlist name"))
                } else {
                    onRename(name)
                    showRename = false
                }
            },
            onDismiss = { showRename = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 通用行 / 对话框
// ---------------------------------------------------------------------------------------------

/** Song cell used by the library sections (cover + title + artists + duration + VIP badge). */
@Composable
private fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    val current by PlaybackController.currentSong.collectAsState()
    val isCurrent = current?.identityKey == song.identityKey
    val interaction = remember(song.identityKey) { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null) {
                BeansHaptics.tap()
                onClick()
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Spacer(Modifier.width(8.dp))
        if (isCurrent) {
            com.lulu.music.ui.components.BeansNowPlayingIndicator(height = 14.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (song.duration > 0) song.formattedDuration else "--:--",
            color = colors.comment,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun Divider() {
    val colors = BeansTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.comment.copy(alpha = 0.12f))
    )
}

@Composable
private fun NameDialog(
    title: String,
    message: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    placeholder = { Text("歌单名称") },
                )
                Text(text = message, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(beansLocalized("创建", "Create")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(beansLocalized("取消", "Cancel")) } },
    )
}

/** 板块 / 歌单排序：iOS 用拖拽（`onMove`），Android 用上/下移按钮表达同一语义。 */
@Composable
private fun OrderDialog(
    title: String,
    entries: List<String>,
    labelOf: (String) -> String,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BeansTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                entries.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = labelOf(entry),
                            color = colors.label,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        BeansGlassIconButton(
                            icon = Icons.Rounded.KeyboardArrowUp,
                            onClick = { if (index > 0) onMove(index, index - 1) },
                            contentDescription = "上移",
                            size = 30.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        BeansGlassIconButton(
                            icon = Icons.Rounded.KeyboardArrowDown,
                            onClick = { if (index < entries.lastIndex) onMove(index, index + 1) },
                            contentDescription = "下移",
                            size = 30.dp,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(beansLocalized("完成", "Done")) } },
    )
}

/** Port of iOS `SyncPlatformPicker`：勾选 2~3 个平台，把各平台「喜欢」合并成一个本地歌单。 */
@Composable
private fun SyncPlatformDialog(
    selected: List<LibraryProvider>,
    onToggle: (LibraryProvider) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = BeansTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一键同步歌单") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "同步平台", color = colors.comment, fontSize = 12.sp)
                LibraryProvider.entries.forEach { provider ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onToggle(provider) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected.contains(provider), onCheckedChange = { onToggle(provider) })
                        Text(text = provider.displayName, color = colors.label, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (selected.size >= 2) {
                        "所选平台的喜欢歌曲会合并到同一个本地歌单。"
                    } else {
                        "至少选择两个平台。"
                    },
                    color = colors.comment,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selected.size >= 2) {
                Text(beansLocalized("开始同步", "Sync"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(beansLocalized("取消", "Cancel")) } },
    )
}

/** 从「我的收藏」挑选歌曲加入本地歌单（替代 iOS 的 `LocalSearchAddSheet`）。 */
@Composable
private fun AddSongsDialog(
    candidates: List<Song>,
    existingKeys: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<Song>) -> Unit,
) {
    val colors = BeansTheme.colors
    val picked = remember { mutableStateListOf<Song>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从收藏添加歌曲") },
        text = {
            if (candidates.isEmpty()) {
                Text(text = "还没有收藏的歌曲，先到播放页点亮红心", color = colors.comment, fontSize = 13.sp)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                ) {
                    items(candidates, key = { it.identityKey }) { song ->
                        val already = existingKeys.contains(song.identityKey)
                        val checked = picked.any { it.identityKey == song.identityKey }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (!already) {
                                        BeansHaptics.select()
                                        if (checked) {
                                            picked.removeAll { it.identityKey == song.identityKey }
                                        } else {
                                            picked.add(song)
                                        }
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = already || checked,
                                onCheckedChange = {
                                    if (!already) {
                                        if (checked) {
                                            picked.removeAll { it.identityKey == song.identityKey }
                                        } else {
                                            picked.add(song)
                                        }
                                    }
                                },
                                enabled = !already,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.name,
                                    color = colors.label,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (already) "已在歌单中" else song.artists.ifBlank { "未知歌手" },
                                    color = colors.comment,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked.toList()) }, enabled = picked.isNotEmpty()) {
                Text(beansLocalized("添加", "Add"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(beansLocalized("取消", "Cancel")) } },
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 三平台「喜欢」合并同步（Port of `LocalMusicSection.sync(targets:)`）
// ---------------------------------------------------------------------------------------------

private data class SyncOutcome(val songs: List<Song>, val detail: String, val playlistName: String)

/**
 * Port of iOS `LocalMusicSection.sync(targets:)`：逐平台拉「喜欢」并合并去重，写入本地歌单。
 *
 * 差异：
 * - 网易云在 iOS 里先找「喜欢」歌单再取曲目，Android 侧直接读已同步的
 *   `FavoritesStore.neteaseFavoriteSongs`（同一批歌曲，且免去一次 uid → 歌单的额外请求）。
 * - QQ 用 `favoriteSongs()`（与 iOS 一致）。
 * - 酷狗在 iOS 里先找名称含「喜欢 / 收藏 / 红心」的歌单再取曲目，这里保持一致。
 */
private suspend fun syncLocalPlaylist(targets: List<LibraryProvider>): SyncOutcome {
    val songs = ArrayList<Song>()
    val details = ArrayList<String>()

    if (targets.contains(LibraryProvider.NETEASE)) {
        if (AuthStore.isLoggedIn) {
            val cached = FavoritesStore.neteaseFavoriteSongs.value
            songs.addAll(cached)
            details.add("网易云 ${cached.size} 首")
        } else {
            details.add("网易云未登录")
        }
    }

    if (targets.contains(LibraryProvider.QQ)) {
        if (QQMusicAuth.isLoggedIn) {
            val result = runCatching { QQMusicApi.favoriteSongs(limit = 0) }
            val qqSongs = result.getOrNull()
            if (qqSongs == null) {
                details.add("QQ音乐请求失败")
            } else {
                songs.addAll(qqSongs)
                details.add("QQ音乐 ${qqSongs.size} 首")
            }
        } else {
            details.add("QQ音乐未登录")
        }
    }

    if (targets.contains(LibraryProvider.KUGOU)) {
        if (KugouMusicAuth.isLoggedIn) {
            val lists = runCatching { KugouMusicApi.userPlaylists() }.getOrNull()
            if (lists == null) {
                details.add("酷狗音乐请求失败")
            } else {
                val liked = lists.firstOrNull { list ->
                    val name = list.name.replace(" ", "")
                    name.contains("喜欢") || name.contains("收藏") || name.contains("红心")
                }
                if (liked == null) {
                    details.add("酷狗音乐未找到喜欢歌单")
                } else {
                    // 酷狗接口的 id 就是 Int（小 id），只在 API 边界转换
                    val kugouSongs = runCatching { KugouMusicApi.playlistSongs(liked.id.toInt()) }.getOrNull()
                    if (kugouSongs == null) {
                        details.add("酷狗音乐请求失败")
                    } else {
                        songs.addAll(kugouSongs)
                        details.add("酷狗音乐 ${kugouSongs.size} 首")
                    }
                }
            }
        } else {
            details.add("酷狗音乐未登录")
        }
    }

    val seen = HashSet<String>()
    val unique = songs.filter { seen.add(it.identityKey) }
    val playlistName = targets.sortedBy { it.name }.joinToString(" + ") { it.displayName } + "喜欢"
    val detail = details.joinToString("，") + "；合计 ${unique.size} 首"
    return SyncOutcome(songs = unique, detail = detail, playlistName = playlistName)
}


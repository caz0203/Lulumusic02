package com.lulu.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lulu.music.data.api.KugouMusicApi
import com.lulu.music.data.api.NetEaseApi
import com.lulu.music.data.api.QQMusicApi
import com.lulu.music.data.auth.AuthStore
import com.lulu.music.data.auth.KugouMusicAuth
import com.lulu.music.data.model.KugouTopInfo
import com.lulu.music.data.model.Lang
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.QQTopInfo
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.TopList
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.AppLanguage
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.PlayerOpenRequest
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansCapsuleShape
import com.lulu.music.ui.components.BeansCoverImage
import com.lulu.music.ui.components.BeansDetent
import com.lulu.music.ui.components.BeansEmptyState
import com.lulu.music.ui.components.BeansErrorState
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansLoadingState
import com.lulu.music.ui.components.BeansNowPlayingIndicator
import com.lulu.music.ui.components.BeansSectionHeader
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.beansCardShadow
import com.lulu.music.ui.components.beansGlass
import com.lulu.music.ui.components.beansPressClickable
import com.lulu.music.ui.components.beansSectionEntrance
import com.lulu.music.ui.components.beansSongCountText
import com.lulu.music.ui.components.beansTimeString
import com.lulu.music.ui.theme.BeansTheme
import java.util.Calendar
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// MARK: - 榜单名称 / 副标题本地化（Port of `beansChartName(_:)` / `beansChartSubtitle(_:)`）
// ---------------------------------------------------------------------------------------------

/**
 * `Language.swift` 里的两个榜单文案替换表在当前 Android 数据层还没有对应实现，
 * 这里按原表（顺序一致、逐条 `contains` 替换）在本地补齐，保证英文语言的榜单名与 iOS 一致。
 */
private val CHART_NAME_REPLACEMENTS: List<Pair<String, String>> = listOf(
    "巅峰榜·流行指数" to "Peak Chart · Popularity",
    "巅峰榜·网络歌曲" to "Peak Chart · Online Songs",
    "巅峰榜·影视金曲" to "Peak Chart · Soundtracks",
    "巅峰榜·说唱" to "Peak Chart · Rap",
    "巅峰榜·国风" to "Peak Chart · Chinese Style",
    "巅峰榜·音乐人" to "Peak Chart · Musicians",
    "巅峰榜·电音" to "Peak Chart · Electronic",
    "巅峰榜·MV" to "Peak Chart · Music Videos",
    "巅峰榜·K歌金曲" to "Peak Chart · Karaoke Gold",
    "巅峰榜·韩国" to "Peak Chart · Korean Music",
    "巅峰榜·日本" to "Peak Chart · Japanese Music",
    "巅峰榜·热歌" to "Peak Chart · Hot Songs",
    "巅峰榜·新歌" to "Peak Chart · New Songs",
    "巅峰榜·欧美" to "Peak Chart · Western Music",
    "巅峰榜·内地" to "Peak Chart · Mainland China",
    "巅峰榜·港台" to "Peak Chart · Hong Kong & Taiwan",
    "酷狗TOP500" to "Kugou TOP 500",
    "网络红歌榜" to "Viral Songs",
    "网络热歌榜" to "Online Hot Songs",
    "TOP500" to "TOP 500",
    "视频号热歌酷狗榜" to "WeChat Channels Hot Songs",
    "短视频收藏人气榜" to "Short-video Favorites",
    "JOOX香港热歌榜" to "JOOX Hong Kong Hot Songs",
    "80后热歌榜" to "Post-80s Hot Songs",
    "90后热歌榜" to "Post-90s Hot Songs",
    "KKBOX风云榜" to "KKBOX Chart",
    "R&B榜" to "R&B Chart",
    "DJ热歌榜" to "DJ Hot Songs",
    "欧美金曲榜" to "Western Gold Songs",
    "华语新歌榜" to "Chinese New Songs",
    "抖音热歌榜" to "Douyin Hot Songs",
    "电音热歌榜" to "Electronic Hot Songs",
    "电音榜" to "Electronic Music",
    "动漫音乐榜" to "Anime Music",
    "动漫榜" to "Anime Music",
    "古风音乐榜" to "Ancient-style Music",
    "经典老歌榜" to "Classic Songs",
    "KTV点唱榜" to "KTV Favorites",
    "综艺新歌榜" to "Variety Show New Songs",
    "粤语歌曲榜" to "Cantonese Songs",
    "粤语金曲榜" to "Cantonese Gold Songs",
    "抖音榜" to "Douyin Chart",
    "短视频热歌榜" to "Short-video Hot Songs",
    "说唱榜" to "Rap Songs",
    "国风榜" to "Chinese Style Songs",
    "国潮音乐榜" to "Chinese Trend Music",
    "国风热歌榜" to "Chinese Style Hot Songs",
    "国乐榜" to "Chinese Instrumental Music",
    "香港地区榜" to "Hong Kong Chart",
    "台湾地区榜" to "Taiwan Chart",
    "韩国榜" to "Korean Chart",
    "日本榜" to "Japanese Chart",
    "内地榜" to "Mainland China Chart",
    "DJ舞曲榜" to "DJ Dance Chart",
    "听歌识曲榜" to "Song Recognition Chart",
    "游戏音乐榜" to "Game Music",
    "有声榜" to "Audio Chart",
    "纯音乐榜" to "Instrumental Music",
    "民谣榜" to "Folk Music",
    "伤感榜" to "Heartbreak Songs",
    "百万收藏榜" to "Million Favorites",
    "说唱先锋榜" to "Rap Rising",
    "摇滚榜" to "Rock Songs",
    "ACG新歌榜" to "ACG New Songs",
    "热歌榜" to "Hot Songs",
    "新歌榜" to "New Songs",
    "飙升榜" to "Rising Songs",
    "原创榜" to "Original Songs",
    "欧美榜" to "Western Music",
    "华语榜" to "Chinese Music",
    "ACG音乐榜" to "ACG Music",
    "流行指数榜" to "Popularity Chart",
    "巅峰榜" to "Peak Chart",
)

private val CHART_SUBTITLE_REPLACEMENTS: List<Pair<String, String>> = listOf(
    "周五凌晨更新周榜" to "Weekly, updated early Friday",
    "周四更新" to "Updated Thursday",
    "每日更新" to "Updated daily",
    "每天" to "Updated daily",
    "每周更新" to "Updated weekly",
    "每月更新" to "Updated monthly",
    "工作日" to "Updated on weekdays",
    "周一" to "Updated Monday",
    "周三" to "Updated Wednesday",
    "周四" to "Updated Thursday",
    "每年年底" to "Updated at year end",
    "官方热门榜单" to "Official popular chart",
    "酷狗官网热门榜单" to "Kugou popular chart",
    "QQ 峰尖榜" to "QQ Music Peak Chart",
    "峰尖榜" to "Peak Chart",
)

private fun discoverChartName(name: String): String {
    if (Lang.current.value != AppLanguage.ENGLISH) return name
    var result = name
    for ((zh, en) in CHART_NAME_REPLACEMENTS) {
        if (result.contains(zh)) result = result.replace(zh, en)
    }
    return result
}

private fun discoverChartSubtitle(subtitle: String): String {
    if (subtitle.isEmpty()) return subtitle
    if (Lang.current.value != AppLanguage.ENGLISH) return subtitle
    var result = subtitle
    for ((zh, en) in CHART_SUBTITLE_REPLACEMENTS) {
        if (result.contains(zh)) result = result.replace(zh, en)
    }
    return result
}

// ---------------------------------------------------------------------------------------------
// MARK: - 平台文案 / 主题色（Port of `SearchProvider.tint` / `beansPlatformName`）
// ---------------------------------------------------------------------------------------------

private fun providerName(source: SongSource): String = when (source) {
    SongSource.NET_EASE -> beansLocalized("网易云音乐", "NetEase Cloud Music")
    SongSource.QQ -> beansLocalized("QQ音乐", "QQ Music")
    SongSource.KUGOU -> beansLocalized("酷狗音乐", "Kugou Music")
}

private fun providerIcon(source: SongSource): ImageVector = when (source) {
    SongSource.NET_EASE -> Icons.Rounded.Cloud
    SongSource.QQ -> Icons.Rounded.PlayArrow
    SongSource.KUGOU -> Icons.Rounded.MusicNote
}

/** 主题色：网易云红 / QQ 绿 / 酷狗蓝（取 iOS `SearchProvider.tint` 的起点色）。 */
private fun providerTint(source: SongSource): Color = when (source) {
    SongSource.NET_EASE -> Color(0.93f, 0.22f, 0.16f)
    SongSource.QQ -> Color(0.15f, 0.78f, 0.55f)
    SongSource.KUGOU -> Color(0.12f, 0.58f, 0.95f)
}

// ---------------------------------------------------------------------------------------------
// MARK: - 数据
// ---------------------------------------------------------------------------------------------

/** 首页快照（Port of `DiscoverCache.Snapshot`）。 */
private data class DiscoverSnapshot(
    val dailySongs: List<Song> = emptyList(),
    val topLists: List<TopList> = emptyList(),
    val personalized: List<Playlist> = emptyList(),
    val qqTopLists: List<QQTopInfo> = emptyList(),
    val kugouTopLists: List<KugouTopInfo> = emptyList(),
)

/** 榜单 / 每日推荐详情（iOS 用 `DiscoverRoute` 推入详情页，Android 用底部弹层承载）。 */
private data class DiscoverSheetRequest(
    val title: String,
    val subtitle: String,
    val coverURL: String?,
    val emptyText: String,
    val songs: List<Song> = emptyList(),
    val source: SongSource = SongSource.NET_EASE,
    val id: Long = 0L,
)

private class DiscoverRankItem(
    val name: String,
    val subtitle: String,
    val coverURL: String?,
    val onOpen: () -> Unit,
)

/** 网易云排行榜：优先保留官方热门前 5 个榜单，缺失时回落「热歌榜置顶」（Port of `neteaseTopLists`）。 */
private fun orderedNeteaseTopLists(lists: List<TopList>): List<TopList> {
    val preferredIds = listOf(19_723_756L, 3_779_629L, 2_884_035L, 3_778_678L, 60_198L)
    val byId = lists.associateBy { it.id }
    val preferred = preferredIds.mapNotNull { byId[it] }
    if (preferred.isNotEmpty()) return preferred
    val list = lists.toMutableList()
    val hotIndex = list.indexOfFirst { it.name.contains("热歌榜") }
    if (hotIndex > 0) {
        val hot = list.removeAt(hotIndex)
        list.add(0, hot)
    }
    return list
}

/** Port of `fetchSnapshot(for:neteaseCat:)` —— 每个平台的首页快照。 */
private suspend fun fetchDiscoverSnapshot(source: SongSource): DiscoverSnapshot = when (source) {
    // 网易云：三者必须全部成功（与 iOS 的 `try await (a, b, c)` 一致），失败由调用方展示错误态
    SongSource.NET_EASE -> coroutineScope {
        val topLists = async { NetEaseApi.topLists() }
        val daily = async { NetEaseApi.dailyRecommend() }
        val playlists = async { NetEaseApi.recommendedHomePlaylists(AuthStore.isLoggedIn, limit = 18) }
        DiscoverSnapshot(
            topLists = topLists.await(),
            dailySongs = daily.await(),
            personalized = playlists.await(),
        )
    }

    // QQ：三项互相独立，任一失败只影响自己的板块（与 iOS 的 `try?` 一致）
    SongSource.QQ -> DiscoverSnapshot(
        dailySongs = runCatching { QQMusicApi.recommendSongs(limit = 30) }.getOrDefault(emptyList()),
        qqTopLists = runCatching { QQMusicApi.topLists() }.getOrDefault(emptyList()),
        personalized = runCatching { QQMusicApi.hotPlaylists(limit = 18) }.getOrDefault(emptyList()),
    )

    SongSource.KUGOU -> DiscoverSnapshot(
        dailySongs = loadKugouDailySongs(limit = 30),
        kugouTopLists = runCatching { KugouMusicApi.topLists(limit = 10) }.getOrDefault(emptyList()),
        personalized = runCatching { KugouMusicApi.recommendPlaylists(limit = 12) }.getOrDefault(emptyList()),
    )
}

/** Port of `loadKugouDailySongs(limit:)`。 */
private suspend fun loadKugouDailySongs(limit: Int): List<Song> {
    val everyday = runCatching { KugouMusicApi.everydayRecommend(limit = limit) }.getOrDefault(emptyList())
    if (everyday.isNotEmpty()) return everyday
    return runCatching { KugouMusicApi.searchSongs(keyword = "热门歌曲", limit = limit) }.getOrDefault(emptyList())
}

/** 榜单详情歌曲：网易云用歌单曲目，QQ 用峰尖榜接口，酷狗用排行榜接口。 */
private suspend fun fetchRankSongs(source: SongSource, id: Long): List<Song> = when (source) {
    SongSource.NET_EASE -> NetEaseApi.playlistTracks(id)
    SongSource.QQ -> QQMusicApi.topListSongs(topid = id.toInt(), limit = 30)
    SongSource.KUGOU -> KugouMusicApi.rankSongs(rankID = id.toInt(), limit = 100)
}

private fun dailyRecommendationSubtitle(songs: List<Song>): String =
    if (songs.isEmpty()) {
        beansLocalized("每天 6:00 更新", "Refreshes at 6:00 daily")
    } else {
        beansLocalized(
            "${songs.size} 首 · 每天 6:00 更新",
            "${songs.size} songs · refreshes at 6:00 daily",
        )
    }

private fun playlistSectionTitle(source: SongSource): String = when (source) {
    SongSource.NET_EASE -> beansLocalized("推荐歌单", "Recommended Playlists")
    SongSource.QQ -> beansLocalized("QQ音乐热门歌单", "QQ Music Hot Playlists")
    SongSource.KUGOU -> beansLocalized("歌单广场", "Playlist Square")
}

private fun playlistEmptyText(source: SongSource): String = when (source) {
    SongSource.NET_EASE -> beansLocalized("推荐歌单暂时没有内容", "No recommended playlists right now")
    SongSource.QQ -> beansLocalized(
        "QQ音乐热门歌单暂未加载成功\n下拉刷新可重新获取",
        "QQ Music hot playlists failed to load\nPull to refresh to retry",
    )

    SongSource.KUGOU -> beansLocalized("歌单广场暂时没有内容", "Playlist square is empty right now")
}

private const val COLLAPSED_PLAYLIST_COUNT = 6

// ---------------------------------------------------------------------------------------------
// MARK: - 主页（Port of `DiscoverView`）
// ---------------------------------------------------------------------------------------------

/**
 * 发现页（主页）。
 *
 * 与 iOS `DiscoverView` 的板块一一对应，顺序固定为：
 * 1. 顶部问候区（大标题 + 用户名 + 刷新；长按可切换平台）
 * 2. 平台切换（网易云 / QQ音乐 / 酷狗音乐）
 * 3. 每日推荐（网易云：每日推荐 / 私人漫游 / 心动模式三张卡片；QQ：横滑歌曲卡 + 查看更多；酷狗：每日推荐 / 私人漫游两张卡片）
 * 4. 排行榜（收起前 3 / 展开前 10；简单样式为横滑大卡片）
 * 5. 歌单广场（推荐歌单 / QQ音乐热门歌单 / 歌单广场；收起前 6 / 展开全部）
 */
@Composable
fun DiscoverScreen() {
    val colors = BeansTheme.colors
    val nav = LocalBeansNavigator.current

    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val homeProviderRaw by SettingsStore.homeProvider.collectAsState()
    val enabledProvidersRaw by SettingsStore.enabledProviders.collectAsState()
    val nickname by AuthStore.nicknameFlow.collectAsState()
    val currentSong by PlaybackController.currentSong.collectAsState()
    val isPlaying by PlaybackController.isPlaying.collectAsState()

    val nativeClean = uiStyle == BeansUIStyle.NATIVE_CLEAN
    val hPad = if (nativeClean) 24.dp else 16.dp
    val sectionSpacing = if (nativeClean) 34.dp else 26.dp

    val providers = remember(enabledProvidersRaw) {
        enabledProvidersRaw.split(',')
            .mapNotNull { raw -> SongSource.entries.firstOrNull { it.raw == raw.trim() } }
            .ifEmpty { listOf(SongSource.NET_EASE) }
    }
    val source = providers.firstOrNull { it.raw == homeProviderRaw } ?: providers.first()

    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var dailySongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var topLists by remember { mutableStateOf<List<TopList>>(emptyList()) }
    var personalized by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var qqTopLists by remember { mutableStateOf<List<QQTopInfo>>(emptyList()) }
    var kugouTopLists by remember { mutableStateOf<List<KugouTopInfo>>(emptyList()) }
    var ranksExpanded by remember { mutableStateOf(false) }
    var playlistsExpanded by remember { mutableStateOf(false) }
    var actionLoading by remember { mutableStateOf<String?>(null) }
    var showPlatformMenu by remember { mutableStateOf(false) }
    var platformHintDismissed by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<DiscoverSheetRequest?>(null) }

    val scope = rememberCoroutineScope()

    fun hasAnyData(): Boolean =
        dailySongs.isNotEmpty() || topLists.isNotEmpty() || personalized.isNotEmpty() ||
            qqTopLists.isNotEmpty() || kugouTopLists.isNotEmpty()

    /** Port of `load(force:)`：Android 侧没有 `DiscoverCache`，因此每次都重新拉取（失败时保留现有数据）。 */
    suspend fun load(requested: SongSource) {
        loading = true
        errorMessage = null
        val result = runCatching { fetchDiscoverSnapshot(requested) }
        val snapshot = result.getOrNull()
        if (snapshot != null) {
            dailySongs = snapshot.dailySongs
            topLists = snapshot.topLists
            personalized = snapshot.personalized
            qqTopLists = snapshot.qqTopLists
            kugouTopLists = snapshot.kugouTopLists
            loading = false
            errorMessage = null
        } else {
            loading = false
            if (!hasAnyData()) {
                errorMessage = result.exceptionOrNull()?.message
                    ?: beansLocalized("加载失败，请稍后重试", "Failed to load, please try again")
            }
        }
    }

    /** Port of `startPersonalFM()`（网易云私人漫游）。 */
    suspend fun startPersonalFM() {
        if (!AuthStore.isLoggedIn) {
            BeansToastCenter.show(beansLocalized("请先登录网易云音乐", "Please sign in to NetEase Cloud Music"))
            return
        }
        if (actionLoading != null) return
        actionLoading = "fm"
        try {
            val songs = NetEaseApi.personalFM()
            if (songs.isEmpty()) {
                BeansToastCenter.show(beansLocalized("私人漫游暂时没有推荐", "Personal FM has no recommendations yet"))
            } else {
                PlaybackController.play(songs, 0)
                PlayerOpenRequest.request()
                BeansToastCenter.show(beansLocalized("已开启私人漫游", "Personal FM started"))
            }
        } catch (error: Exception) {
            BeansToastCenter.show(beansLocalized("私人漫游加载失败", "Failed to start Personal FM"))
        } finally {
            actionLoading = null
        }
    }

    /** Port of `startKugouPersonalFM()`。 */
    suspend fun startKugouPersonalFM() {
        if (!KugouMusicAuth.isLoggedIn) {
            BeansToastCenter.show(beansLocalized("请先登录酷狗音乐", "Please sign in to Kugou Music"))
            return
        }
        if (actionLoading != null) return
        actionLoading = "kugouFM"
        try {
            val songs = KugouMusicApi.personalFM(limit = 12)
            if (songs.isEmpty()) {
                BeansToastCenter.show(beansLocalized("私人漫游暂时没有推荐", "Personal FM has no recommendations yet"))
            } else {
                PlaybackController.play(songs, 0)
                PlayerOpenRequest.request()
                BeansToastCenter.show(beansLocalized("已开启私人漫游", "Personal FM started"))
            }
        } catch (error: Exception) {
            BeansToastCenter.show(beansLocalized("私人漫游加载失败", "Failed to start Personal FM"))
        } finally {
            actionLoading = null
        }
    }

    /**
     * Port of `startHeartbeatMode()`（心动模式）。
     *
     * 与 iOS 的差异：iOS 优先用「我喜欢的音乐」歌单做种子；Android 侧优先走同一路径
     * （`userPlaylists` → 喜欢歌单 → 随机种子 → `intelligenceList`），
     * 找不到喜欢歌单时依次回落到本地红心 / 每日推荐 / 当前播放歌曲 + `simiSongs`。
     */
    suspend fun startHeartbeatMode() {
        val uid = AuthStore.user?.uid
        if (uid == null) {
            BeansToastCenter.show(beansLocalized("请先登录网易云音乐", "Please sign in to NetEase Cloud Music"))
            return
        }
        if (actionLoading != null) return
        actionLoading = "heartbeat"
        try {
            val playlists = runCatching { NetEaseApi.userPlaylists(uid) }.getOrDefault(emptyList())
            val liked = playlists.firstOrNull { it.isNetEaseLikedPlaylist }
            var songs: List<Song> = emptyList()
            if (liked != null) {
                val likedSongs = runCatching { NetEaseApi.playlistTracks(liked.id) }.getOrDefault(emptyList())
                val seed = likedSongs.randomOrNull()
                if (seed != null) {
                    songs = runCatching { NetEaseApi.intelligenceList(seed.id, liked.id) }.getOrDefault(emptyList())
                }
            }
            if (songs.isEmpty()) {
                val localFavorites = runCatching { com.lulu.music.data.store.FavoritesStore.neteaseFavoriteSongs.value }
                    .getOrDefault(emptyList())
                val seed = localFavorites.randomOrNull()
                    ?: dailySongs.randomOrNull()
                    ?: currentSong
                if (seed == null) {
                    BeansToastCenter.show(beansLocalized("先播放或收藏一些歌曲吧", "Play or favourite some songs first"))
                    return
                }
                songs = runCatching { NetEaseApi.simiSongs(seed.id) }.getOrDefault(emptyList())
            }
            if (songs.isEmpty()) {
                BeansToastCenter.show(beansLocalized("心动模式暂时不可用", "Heartbeat mode is unavailable right now"))
            } else {
                PlaybackController.play(songs, 0)
                PlayerOpenRequest.request()
                BeansToastCenter.show(beansLocalized("已开启心动模式", "Heartbeat mode started"))
            }
        } catch (error: Exception) {
            BeansToastCenter.show(beansLocalized("心动模式加载失败", "Failed to start Heartbeat mode"))
        } finally {
            actionLoading = null
        }
    }

    /** 首次进入加载（对应 iOS `.task(id:)` 的初始触发）。 */
    LaunchedEffect(Unit) { load(source) }

    // ---- 派生数据 ---------------------------------------------------------------------------

    val neteaseOrdered = remember(topLists) { orderedNeteaseTopLists(topLists) }

    val visibleRankCount = when (source) {
        SongSource.NET_EASE -> neteaseOrdered.size
        SongSource.QQ -> qqTopLists.size
        SongSource.KUGOU -> kugouTopLists.size
    }
    val displayedRankCount = if (ranksExpanded) minOf(visibleRankCount, 10) else minOf(visibleRankCount, 3)

    val hasRankData = when (source) {
        SongSource.NET_EASE -> topLists.isNotEmpty()
        SongSource.QQ -> qqTopLists.isNotEmpty()
        SongSource.KUGOU -> kugouTopLists.isNotEmpty()
    }

    val rankItems: List<DiscoverRankItem> = when (source) {
        SongSource.NET_EASE -> neteaseOrdered.map { top ->
            DiscoverRankItem(
                name = discoverChartName(top.name),
                subtitle = discoverChartSubtitle(top.updateFrequency),
                coverURL = top.coverURL,
            ) {
                sheet = DiscoverSheetRequest(
                    title = discoverChartName(top.name),
                    subtitle = top.updateFrequency,
                    coverURL = top.coverURL,
                    emptyText = beansLocalized("该排行榜暂无歌曲", "This chart has no songs yet"),
                    source = SongSource.NET_EASE,
                    id = top.id,
                )
            }
        }

        SongSource.QQ -> qqTopLists.map { info ->
            DiscoverRankItem(
                name = discoverChartName(info.name),
                subtitle = discoverChartSubtitle(info.subTitle),
                coverURL = info.coverURL,
            ) {
                sheet = DiscoverSheetRequest(
                    title = discoverChartName(info.name),
                    subtitle = info.subTitle,
                    coverURL = info.coverURL,
                    emptyText = beansLocalized("该排行榜暂无歌曲", "This chart has no songs yet"),
                    source = SongSource.QQ,
                    id = info.id.toLong(),
                )
            }
        }

        SongSource.KUGOU -> kugouTopLists.map { info ->
            DiscoverRankItem(
                name = discoverChartName(info.name),
                subtitle = discoverChartSubtitle(info.updateFrequency),
                coverURL = info.coverURL,
            ) {
                sheet = DiscoverSheetRequest(
                    title = discoverChartName(info.name),
                    subtitle = info.updateFrequency,
                    coverURL = info.coverURL,
                    emptyText = beansLocalized("该排行榜暂无歌曲", "This chart has no songs yet"),
                    source = SongSource.KUGOU,
                    id = info.id.toLong(),
                )
            }
        }
    }

    val visiblePlaylists = if (playlistsExpanded) personalized else personalized.take(COLLAPSED_PLAYLIST_COUNT)
    val playlistRows = visiblePlaylists.chunked(2)
    val playlistTitle = playlistSectionTitle(source)
    val playlistEmpty = playlistEmptyText(source)

    val greeting = remember(nativeClean) {
        if (nativeClean) {
            beansLocalized("推荐", "Discover")
        } else {
            when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> beansLocalized("早上好", "Good morning")
                in 12..17 -> beansLocalized("下午好", "Good afternoon")
                else -> beansLocalized("晚上好", "Good evening")
            }
        }
    }

    val errorBanner = errorMessage

    // ---- 界面 -------------------------------------------------------------------------------

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = if (nativeClean) 32.dp else 8.dp, bottom = 190.dp),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            item(key = "header") {
                DiscoverHeader(
                    greeting = greeting,
                    nickname = nickname,
                    nativeClean = nativeClean,
                    uiStyle = uiStyle,
                    showPlatformHint = nativeClean && !platformHintDismissed,
                    onDismissPlatformHint = { platformHintDismissed = true },
                    onLongPress = { BeansHaptics.select(); showPlatformMenu = true },
                    onRefresh = { scope.launch { load(source) } },
                    modifier = Modifier
                        .padding(horizontal = hPad)
                        .padding(top = if (nativeClean) 4.dp else 8.dp),
                )
            }

            item(key = "providerPicker") {
                DiscoverProviderPicker(
                    providers = providers,
                    selected = source,
                    uiStyle = uiStyle,
                    modifier = Modifier.padding(horizontal = hPad),
                    onSelect = { picked ->
                        BeansHaptics.tap()
                        if (picked != source) {
                            ranksExpanded = false
                            playlistsExpanded = false
                            SettingsStore.setHomeProvider(picked.raw)
                            scope.launch { load(picked) }
                        }
                    },
                )
            }

            when {
                errorBanner != null -> item(key = "error") {
                    BeansErrorState(
                        message = errorBanner,
                        onRetry = { scope.launch { load(source) } },
                        modifier = Modifier.padding(horizontal = hPad),
                        style = uiStyle,
                    )
                }

                loading -> item(key = "loading") {
                    BeansLoadingState(modifier = Modifier.padding(horizontal = hPad))
                }

                else -> {
                    // ---- 每日推荐 ---------------------------------------------------------------
                    if (source == SongSource.NET_EASE || dailySongs.isNotEmpty()) {
                        item(key = "daily") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .beansSectionEntrance(delayMillis = 0, animationKey = dailySongs.size),
                            ) {
                                when (source) {
                                    SongSource.NET_EASE -> {
                                        BeansSectionHeader(
                                            title = beansLocalized("推荐", "For You"),
                                            modifier = Modifier.padding(horizontal = hPad),
                                            style = uiStyle,
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = hPad, vertical = 3.dp),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                            item(key = "dailyCard") {
                                                RecommendCard(
                                                    title = beansLocalized("每日推荐", "Daily Picks"),
                                                    subtitle = dailyRecommendationSubtitle(dailySongs),
                                                    icon = Icons.Rounded.Schedule,
                                                    coverURL = dailySongs.firstOrNull()?.coverURL,
                                                    gradient = listOf(
                                                        Color(0.95f, 0.36f, 0.28f),
                                                        Color(0.96f, 0.68f, 0.30f),
                                                    ),
                                                    side = if (nativeClean) 184.dp else 168.dp,
                                                    corner = if (nativeClean) 16.dp else 18.dp,
                                                    loading = false,
                                                    enabled = true,
                                                ) {
                                                    BeansHaptics.tap()
                                                    sheet = DiscoverSheetRequest(
                                                        title = beansLocalized("今日推荐", "Today's Picks"),
                                                        subtitle = dailyRecommendationSubtitle(dailySongs),
                                                        coverURL = dailySongs.firstOrNull()?.coverURL,
                                                        emptyText = beansLocalized(
                                                            "今日推荐加载中，下拉刷新试试",
                                                            "Today's picks are still loading — refresh to retry",
                                                        ),
                                                        songs = dailySongs,
                                                    )
                                                }
                                            }
                                            item(key = "fmCard") {
                                                RecommendCard(
                                                    title = beansLocalized("私人漫游", "Personal FM"),
                                                    subtitle = beansLocalized("从喜欢的歌开始漫游", "Roam from the songs you love"),
                                                    icon = Icons.Rounded.Radio,
                                                    coverURL = null,
                                                    gradient = listOf(
                                                        Color(0.16f, 0.22f, 0.42f),
                                                        Color(0.41f, 0.28f, 0.65f),
                                                    ),
                                                    side = if (nativeClean) 184.dp else 168.dp,
                                                    corner = if (nativeClean) 16.dp else 18.dp,
                                                    loading = actionLoading == "fm",
                                                    enabled = actionLoading == null,
                                                ) { scope.launch { startPersonalFM() } }
                                            }
                                            item(key = "heartbeatCard") {
                                                RecommendCard(
                                                    title = beansLocalized("心动模式", "Heartbeat"),
                                                    subtitle = beansLocalized(
                                                        "你的红心歌曲和相似推荐",
                                                        "Your liked songs and similar picks",
                                                    ),
                                                    icon = Icons.Rounded.Favorite,
                                                    coverURL = null,
                                                    gradient = listOf(
                                                        Color(0.84f, 0.16f, 0.38f),
                                                        Color(0.98f, 0.43f, 0.35f),
                                                    ),
                                                    side = if (nativeClean) 184.dp else 168.dp,
                                                    corner = if (nativeClean) 16.dp else 18.dp,
                                                    loading = actionLoading == "heartbeat",
                                                    enabled = actionLoading == null,
                                                ) { scope.launch { startHeartbeatMode() } }
                                            }
                                        }
                                    }

                                    SongSource.KUGOU -> {
                                        BeansSectionHeader(
                                            title = beansLocalized("推荐", "For You"),
                                            modifier = Modifier.padding(horizontal = hPad),
                                            style = uiStyle,
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = hPad, vertical = 3.dp),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                            item(key = "dailyCard") {
                                                RecommendCard(
                                                    title = beansLocalized("每日推荐", "Daily Picks"),
                                                    subtitle = dailyRecommendationSubtitle(dailySongs),
                                                    icon = Icons.Rounded.Schedule,
                                                    coverURL = dailySongs.firstOrNull()?.coverURL,
                                                    gradient = listOf(
                                                        Color(0.95f, 0.36f, 0.28f),
                                                        Color(0.96f, 0.68f, 0.30f),
                                                    ),
                                                    side = if (nativeClean) 184.dp else 168.dp,
                                                    corner = if (nativeClean) 16.dp else 18.dp,
                                                    loading = false,
                                                    enabled = true,
                                                ) {
                                                    BeansHaptics.tap()
                                                    sheet = DiscoverSheetRequest(
                                                        title = beansLocalized("今日推荐", "Today's Picks"),
                                                        subtitle = dailyRecommendationSubtitle(dailySongs),
                                                        coverURL = dailySongs.firstOrNull()?.coverURL,
                                                        emptyText = beansLocalized(
                                                            "今日推荐加载中，下拉刷新试试",
                                                            "Today's picks are still loading — refresh to retry",
                                                        ),
                                                        songs = dailySongs,
                                                    )
                                                }
                                            }
                                            item(key = "kugouFmCard") {
                                                RecommendCard(
                                                    title = beansLocalized("私人漫游", "Personal FM"),
                                                    subtitle = beansLocalized("从喜欢的歌开始漫游", "Roam from the songs you love"),
                                                    icon = Icons.Rounded.Radio,
                                                    coverURL = null,
                                                    gradient = listOf(
                                                        Color(0.08f, 0.46f, 0.82f),
                                                        Color(0.18f, 0.72f, 0.72f),
                                                    ),
                                                    side = if (nativeClean) 184.dp else 168.dp,
                                                    corner = if (nativeClean) 16.dp else 18.dp,
                                                    loading = actionLoading == "kugouFM",
                                                    enabled = actionLoading == null,
                                                ) { scope.launch { startKugouPersonalFM() } }
                                            }
                                        }
                                    }

                                    // QQ：直接横滑每日推荐歌曲卡（没有 SectionHeader，与 iOS 一致）
                                    SongSource.QQ -> {
                                        DailySongCarousel(
                                            songs = dailySongs,
                                            nativeClean = nativeClean,
                                            uiStyle = uiStyle,
                                            hPad = hPad,
                                            currentSong = currentSong,
                                            isPlaying = isPlaying,
                                            onSongClick = { index ->
                                                PlaybackController.play(dailySongs, index)
                                                PlayerOpenRequest.request()
                                            },
                                            onMoreClick = {
                                                BeansHaptics.tap()
                                                sheet = DiscoverSheetRequest(
                                                    title = beansLocalized("今日推荐", "Today's Picks"),
                                                    subtitle = dailyRecommendationSubtitle(dailySongs),
                                                    coverURL = dailySongs.firstOrNull()?.coverURL,
                                                    emptyText = beansLocalized(
                                                        "今日推荐加载中，下拉刷新试试",
                                                        "Today's picks are still loading — refresh to retry",
                                                    ),
                                                    songs = dailySongs,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---- 排行榜 -----------------------------------------------------------------
                    if (hasRankData) {
                        item(key = "ranks") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .beansSectionEntrance(delayMillis = 80, animationKey = rankItems.size),
                            ) {
                                if (nativeClean) {
                                    BeansSectionHeader(
                                        title = beansLocalized("排行榜", "Charts"),
                                        modifier = Modifier.padding(horizontal = hPad),
                                        style = uiStyle,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = hPad, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        itemsIndexed(items = rankItems.take(minOf(visibleRankCount, 10))) { _, rank ->
                                            DiscoverRankCard(
                                                name = rank.name,
                                                subtitle = rank.subtitle,
                                                coverURL = rank.coverURL,
                                                onClick = { BeansHaptics.tap(); rank.onOpen() },
                                            )
                                        }
                                    }
                                } else {
                                    BeansSectionHeader(
                                        title = beansLocalized("排行榜", "Charts"),
                                        modifier = Modifier.padding(horizontal = hPad),
                                        style = uiStyle,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    val shape = RoundedCornerShape(22.dp)
                                    Column(
                                        modifier = Modifier
                                            .padding(horizontal = hPad)
                                            .beansCardShadow(radius = 9.dp, y = 3.dp, shape = shape)
                                            .beansGlass(shape = shape, style = uiStyle)
                                            .background(Color.Black.copy(alpha = 0.06f), shape)
                                            .border(0.8.dp, Color.White.copy(alpha = 0.18f), shape)
                                            .clip(shape)
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        if (ranksExpanded) {
                                            DiscoverRankToggle(
                                                label = beansLocalized("收起", "Collapse"),
                                                icon = Icons.Rounded.KeyboardArrowUp,
                                                onClick = { BeansHaptics.select(); ranksExpanded = false },
                                            )
                                            DiscoverDivider()
                                        }
                                        rankItems.take(displayedRankCount).forEachIndexed { index, rank ->
                                            DiscoverRankRow(
                                                index = index,
                                                name = rank.name,
                                                subtitle = rank.subtitle,
                                                coverURL = rank.coverURL,
                                                onClick = { BeansHaptics.tap(); rank.onOpen() },
                                            )
                                            DiscoverDivider()
                                        }
                                        if (!ranksExpanded && visibleRankCount > 3) {
                                            DiscoverRankToggle(
                                                label = beansLocalized(
                                                    "展开全部（${minOf(visibleRankCount, 10)}）",
                                                    "Show all (${minOf(visibleRankCount, 10)})",
                                                ),
                                                icon = Icons.Rounded.KeyboardArrowDown,
                                                onClick = { BeansHaptics.select(); ranksExpanded = true },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ---- 歌单广场 ---------------------------------------------------------------
                    if (source == SongSource.QQ || personalized.isNotEmpty()) {
                        item(key = "playlistsHeader") {
                            BeansSectionHeader(
                                title = playlistTitle,
                                modifier = Modifier.padding(horizontal = hPad),
                                style = uiStyle,
                            )
                        }

                        if (visiblePlaylists.isEmpty()) {
                            item(key = "playlistsEmpty") {
                                BeansEmptyState(
                                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                                    text = playlistEmpty,
                                    modifier = Modifier.padding(horizontal = hPad),
                                )
                            }
                        } else if (nativeClean && !playlistsExpanded) {
                            item(key = "playlistsCarousel") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .beansSectionEntrance(delayMillis = 160, animationKey = visiblePlaylists.size),
                                ) {
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = hPad, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        itemsIndexed(items = visiblePlaylists) { _, playlist ->
                                            DiscoverPlaylistCarouselCard(
                                                playlist = playlist,
                                                onClick = { BeansHaptics.tap(); nav.openPlaylist(playlist) },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(items = playlistRows) { _, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = hPad)
                                        .beansSectionEntrance(
                                            delayMillis = 160,
                                            animationKey = visiblePlaylists.size,
                                        ),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    row.forEach { playlist ->
                                        DiscoverPlaylistGridCard(
                                            playlist = playlist,
                                            nativeClean = nativeClean,
                                            uiStyle = uiStyle,
                                            modifier = Modifier.weight(1f),
                                            onClick = { BeansHaptics.tap(); nav.openPlaylist(playlist) },
                                        )
                                    }
                                    // 单数时补一个等宽占位，保证最后一张卡片宽度与上面一致
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }

                        if (personalized.size > COLLAPSED_PLAYLIST_COUNT) {
                            item(key = "playlistsToggle") {
                                DiscoverExpandButton(
                                    label = if (playlistsExpanded) {
                                        beansLocalized("收起歌单广场", "Collapse Playlist Square")
                                    } else {
                                        beansLocalized(
                                            "展开全部（${personalized.size}）",
                                            "Show all (${personalized.size})",
                                        )
                                    },
                                    icon = if (playlistsExpanded) {
                                        Icons.Rounded.KeyboardArrowUp
                                    } else {
                                        Icons.Rounded.KeyboardArrowDown
                                    },
                                    uiStyle = uiStyle,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = hPad),
                                    onClick = { BeansHaptics.select(); playlistsExpanded = !playlistsExpanded },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showPlatformMenu) {
            AlertDialog(
                onDismissRequest = { showPlatformMenu = false },
                title = {
                    Text(
                        text = beansLocalized("主页平台", "Home platform"),
                        color = colors.label,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                text = {
                    Column {
                        providers.forEach { provider ->
                            TextButton(
                                onClick = {
                                    BeansHaptics.select()
                                    showPlatformMenu = false
                                    if (provider != source) {
                                        ranksExpanded = false
                                        playlistsExpanded = false
                                        SettingsStore.setHomeProvider(provider.raw)
                                        scope.launch { load(provider) }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (provider == source) {
                                        Icons.Rounded.PlayArrow
                                    } else {
                                        providerIcon(provider)
                                    },
                                    contentDescription = null,
                                    tint = if (provider == source) colors.accent else colors.comment,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = providerName(provider),
                                    color = if (provider == source) colors.accent else colors.label,
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
            )
        }

        sheet?.let { request ->
            DiscoverSongsSheet(
                request = request,
                uiStyle = uiStyle,
                onDismiss = { sheet = null },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 顶部问候区 / 平台切换
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverHeader(
    greeting: String,
    nickname: String,
    nativeClean: Boolean,
    uiStyle: BeansUIStyle,
    showPlatformHint: Boolean,
    onDismissPlatformHint: () -> Unit,
    onLongPress: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = greeting,
                    color = colors.label,
                    fontSize = if (nativeClean) 42.sp else 30.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showPlatformHint) {
                    Text(
                        text = beansLocalized("长按这里可切换平台", "Long-press to switch platform"),
                        color = colors.comment,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = onDismissPlatformHint, onLongClick = {}),
                    )
                }
            }
            Text(
                text = nickname.ifBlank { beansLocalized("发现好音乐", "Discover great music") },
                color = colors.comment,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BeansGlassIconButton(
            icon = Icons.Rounded.Refresh,
            onClick = {
                BeansHaptics.tap()
                onRefresh()
            },
            contentDescription = beansLocalized("刷新", "Refresh"),
            style = uiStyle,
        )
    }
}

@Composable
private fun DiscoverProviderPicker(
    providers: List<SongSource>,
    selected: SongSource,
    uiStyle: BeansUIStyle,
    modifier: Modifier = Modifier,
    onSelect: (SongSource) -> Unit,
) {
    val colors = BeansTheme.colors
    val nativeClean = uiStyle == BeansUIStyle.NATIVE_CLEAN
    Row(
        modifier = modifier
            .fillMaxWidth()
            .beansCardShadow(
                radius = if (nativeClean) 2.dp else 6.dp,
                y = if (nativeClean) 1.dp else 2.dp,
                shape = BeansCapsuleShape,
            )
            .beansGlass(shape = BeansCapsuleShape, style = uiStyle)
            .clip(BeansCapsuleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        providers.forEach { provider ->
            val active = provider == selected
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(BeansCapsuleShape)
                    .background(if (active) providerTint(provider) else Color.Transparent, BeansCapsuleShape)
                    .beansPressClickable(
                        interactionSource = interaction,
                        scale = 0.97f,
                        onClick = { onSelect(provider) },
                    )
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = providerIcon(provider),
                    contentDescription = null,
                    tint = if (active) Color.White else colors.comment,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = providerName(provider),
                    color = if (active) Color.White else colors.comment,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 每日推荐
// ---------------------------------------------------------------------------------------------

/** Port of `neteaseRecommendationCard(...)`（每日推荐 / 私人漫游 / 心动模式 通用卡片）。 */
@Composable
private fun RecommendCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    coverURL: String?,
    gradient: List<Color>,
    side: Dp,
    corner: Dp,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = Modifier
            .size(side)
            .beansCardShadow(radius = 16.dp, y = 8.dp, shape = shape)
            .clip(shape)
            .beansPressClickable(
                interactionSource = interaction,
                enabled = enabled,
                scale = 0.95f,
                onClick = onClick,
            ),
    ) {
        if (!coverURL.isNullOrBlank()) {
            BeansCoverImage(url = coverURL, size = side, cornerRadius = corner)
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.62f))
                        )
                    )
            )
        } else {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(gradient)))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 24.dp, y = (-26).dp)
                    .size(92.dp)
                    .background(Color.White.copy(alpha = 0.16f), CircleShape)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.32f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = 34.dp, y = (-18).dp)
                    .size(46.dp),
            )
        }

        Column(modifier = Modifier.matchParentSize().padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(16.dp),
                )
                if (loading) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Port of `dailySongCards`（QQ：每日推荐前 8 首 + 查看更多）。 */
@Composable
private fun DailySongCarousel(
    songs: List<Song>,
    nativeClean: Boolean,
    uiStyle: BeansUIStyle,
    hPad: Dp,
    currentSong: Song?,
    isPlaying: Boolean,
    onSongClick: (Int) -> Unit,
    onMoreClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val coverSide = if (nativeClean) 156.dp else 108.dp
    val corner = if (nativeClean) 14.dp else 16.dp

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = hPad, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items = songs.take(8)) { index, song ->
            val interaction = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .width(coverSide)
                    .clip(RoundedCornerShape(16.dp))
                    .beansPressClickable(
                        interactionSource = interaction,
                        scale = 0.94f,
                        onClick = { onSongClick(index) },
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(modifier = Modifier.size(coverSide)) {
                    BeansCoverImage(url = song.coverURL, size = coverSide, cornerRadius = corner)
                    if (song.isVIP) {
                        BeansVIPBadge(
                            text = "VIP",
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        )
                    }
                    DiscoverPlayStateBadge(
                        song = song,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
                Text(
                    text = song.name,
                    color = colors.label,
                    fontSize = if (nativeClean) 15.sp else 12.sp,
                    fontWeight = if (nativeClean) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(coverSide),
                )
                Text(
                    text = song.artists.ifEmpty { song.album },
                    color = colors.comment,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(coverSide),
                )
            }
        }

        item(key = "morePlaceholder") {
            val interaction = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .width(if (nativeClean) 58.dp else 56.dp)
                    .height(if (nativeClean) 96.dp else 84.dp)
                    .beansGlass(shape = shape, style = uiStyle)
                    .clip(shape)
                    .beansPressClickable(interactionSource = interaction, scale = 0.94f, onClick = onMoreClick),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.label,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = beansLocalized("查看更多", "More"),
                    color = colors.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Port of `dailyPlayStateBadge(for:)`。 */
@Composable
private fun DiscoverPlayStateBadge(
    song: Song,
    currentSong: Song?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val isCurrent = currentSong?.identityKey == song.identityKey
    Box(
        modifier = modifier.padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent && isPlaying) {
                BeansNowPlayingIndicator(color = Color.White, height = 12.dp)
            } else {
                Icon(
                    imageVector = if (isCurrent) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 排行榜
// ---------------------------------------------------------------------------------------------

/** Port of `rankRow(index:name:subtitle:coverURL:action:)`。 */
@Composable
private fun DiscoverRankRow(
    index: Int,
    name: String,
    subtitle: String,
    coverURL: String?,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .beansPressClickable(interactionSource = interaction, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "${index + 1}",
            color = if (index < 3) colors.accent else colors.comment,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(24.dp),
        )
        BeansCoverImage(url = coverURL, size = 52.dp, cornerRadius = 12.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = name,
                color = colors.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = colors.comment,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

/** Port of `nativeRankCard(...)`（简单样式的横滑榜单大卡）。 */
@Composable
private fun DiscoverRankCard(
    name: String,
    subtitle: String,
    coverURL: String?,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(14.dp))
            .beansPressClickable(interactionSource = interaction, scale = 0.96f, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(148.dp)) {
            BeansCoverImage(url = coverURL, size = 148.dp, cornerRadius = 10.dp)
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.68f))
                        )
                    )
            )
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.18f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = 24.dp, y = (-16).dp)
                    .size(58.dp),
            )
        }
        Text(
            text = name,
            color = colors.label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(148.dp),
        )
        Text(
            text = subtitle,
            color = colors.secondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(148.dp),
        )
    }
}

/** Port of `rankToggleButton(label:icon:)`。 */
@Composable
private fun DiscoverRankToggle(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .beansPressClickable(interactionSource = interaction, scale = 0.98f, onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(13.dp),
        )
    }
}

/** Port of `Divider().overlay(Color.beansComment.opacity(0.12))`。 */
@Composable
private fun DiscoverDivider() {
    val colors = BeansTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.7.dp)
            .background(colors.comment.copy(alpha = 0.12f))
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 歌单广场
// ---------------------------------------------------------------------------------------------

/** Port of `personalizedSection` 横滑形态（简单样式，收起状态）。 */
@Composable
private fun DiscoverPlaylistCarouselCard(
    playlist: Playlist,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(166.dp)
            .clip(RoundedCornerShape(18.dp))
            .beansPressClickable(interactionSource = interaction, scale = 0.96f, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BeansCoverImage(url = playlist.coverURL, size = 166.dp, cornerRadius = 16.dp)
        Text(
            text = playlist.name,
            color = colors.label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(166.dp),
        )
        if (playlist.trackCount > 0) {
            Text(
                text = beansSongCountText(playlist.trackCount),
                color = colors.secondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.width(166.dp),
            )
        }
    }
}

/** Port of `personalizedSection` 双列网格形态。 */
@Composable
private fun DiscoverPlaylistGridCard(
    playlist: Playlist,
    nativeClean: Boolean,
    uiStyle: BeansUIStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (nativeClean) 16.dp else 22.dp)
    Column(
        modifier = modifier
            .beansPressClickable(interactionSource = interaction, scale = 0.96f, onClick = onClick)
            .then(
                if (nativeClean) {
                    Modifier.background(colors.label.copy(alpha = 0.04f), shape)
                } else {
                    Modifier.beansGlass(shape = shape, style = uiStyle)
                }
            )
            .clip(shape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DiscoverAdaptiveCover(
            url = playlist.coverURL,
            cornerRadius = 18.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Text(
            text = playlist.name,
            color = colors.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Port of `presonalizedSection` 底部展开 / 收起按钮。 */
@Composable
private fun DiscoverExpandButton(
    label: String,
    icon: ImageVector,
    uiStyle: BeansUIStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val nativeClean = uiStyle == BeansUIStyle.NATIVE_CLEAN
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .beansPressClickable(interactionSource = interaction, scale = 0.97f, onClick = onClick)
            .then(
                if (nativeClean) {
                    Modifier.background(colors.label.copy(alpha = 0.045f), BeansCapsuleShape)
                } else {
                    Modifier.beansGlass(shape = BeansCapsuleShape, style = uiStyle)
                }
            )
            .clip(BeansCapsuleShape)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(13.dp),
        )
    }
}

/**
 * 宽度自适应的封面（网格卡片用；[BeansCoverImage] 只接受固定边长，
 * 这里直接用 Coil 的 [AsyncImage] + 菱形占位，加载状态不改变布局尺寸）。
 */
@Composable
private fun DiscoverAdaptiveCover(
    url: String?,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.glassFill),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = colors.comment,
                modifier = Modifier.size(28.dp),
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 榜单 / 每日推荐详情弹层
// ---------------------------------------------------------------------------------------------

/**
 * iOS 上 `DiscoverView` 通过 `DiscoverRoute` 推入 `TopListDetailView` / `QQTopListDetailView` /
 * `KugouTopListDetailView` / `DailySongsSheet`；Android 的导航器没有对应的榜单路由，
 * 因此这里用底部弹层承载同一内容（顶部信息 + 播放全部 / 随机播放 + 歌曲列表）。
 */
@Composable
private fun DiscoverSongsSheet(
    request: DiscoverSheetRequest,
    uiStyle: BeansUIStyle,
    onDismiss: () -> Unit,
) {
    val colors = BeansTheme.colors
    BeansBottomSheet(
        onDismissRequest = onDismiss,
        detents = listOf(BeansDetent.Large),
        style = uiStyle,
    ) {
        var songs by remember(request) { mutableStateOf(request.songs) }
        var loading by remember(request) { mutableStateOf(request.songs.isEmpty()) }
        var errorText by remember(request) { mutableStateOf<String?>(null) }
        var reloadKey by remember(request) { mutableStateOf(0) }

        LaunchedEffect(request, reloadKey) {
            if (request.songs.isNotEmpty()) return@LaunchedEffect
            loading = true
            errorText = null
            val result = runCatching { fetchRankSongs(request.source, request.id) }
            val fetched = result.getOrNull()
            if (fetched != null) {
                songs = fetched
                loading = false
            } else {
                errorText = result.exceptionOrNull()?.message
                    ?: beansLocalized("加载失败，请稍后重试", "Failed to load, please try again")
                loading = false
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BeansCoverImage(url = request.coverURL, size = 88.dp, cornerRadius = 8.dp)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = request.title,
                        color = colors.label,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (request.subtitle.isNotBlank()) {
                        Text(
                            text = discoverChartSubtitle(request.subtitle),
                            color = colors.comment,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = beansSongCountText(songs.size),
                        color = colors.comment,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BeansGlassButton(
                    title = beansLocalized("播放全部", "Play all"),
                    icon = Icons.Rounded.PlayArrow,
                    prominent = true,
                    style = uiStyle,
                    onClick = {
                        if (songs.isNotEmpty()) {
                            BeansHaptics.tap()
                            PlaybackController.play(songs, 0)
                            PlayerOpenRequest.request()
                            // 弹层是独立的 Dialog 窗口，会盖在新打开的播放页上，必须先收起。
                            onDismiss()
                        }
                    },
                )
                BeansGlassButton(
                    title = beansLocalized("随机播放", "Shuffle"),
                    icon = Icons.Rounded.Shuffle,
                    style = uiStyle,
                    onClick = {
                        if (songs.isNotEmpty()) {
                            BeansHaptics.tap()
                            PlaybackController.play(songs, Random.nextInt(songs.size))
                            PlayerOpenRequest.request()
                            onDismiss()
                        }
                    },
                )
            }
            Spacer(Modifier.height(12.dp))

            val message = errorText
            when {
                loading -> BeansLoadingState()
                message != null -> BeansErrorState(
                    message = message,
                    onRetry = { reloadKey += 1 },
                    style = uiStyle,
                )

                songs.isEmpty() -> BeansEmptyState(
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    text = request.emptyText,
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 380.dp),
                ) {
                    itemsIndexed(items = songs) { index, song ->
                        DiscoverSongRow(
                            song = song,
                            onClick = {
                                BeansHaptics.tap()
                                PlaybackController.play(songs, index)
                                PlayerOpenRequest.request()
                                onDismiss()
                            },
                        )
                        DiscoverDivider()
                    }
                }
            }
        }
    }
}

/** 榜单 / 每日推荐弹层里的歌曲行（简化版 `SongCell`）。 */
@Composable
private fun DiscoverSongRow(
    song: Song,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .beansPressClickable(interactionSource = interaction, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BeansCoverImage(url = song.coverURL, size = 48.dp, cornerRadius = 10.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = song.name,
                    color = colors.label,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.isVIP) BeansVIPBadge(text = "VIP")
            }
            Text(
                text = song.artists.ifEmpty { song.album },
                color = colors.comment,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (song.duration > 0.0) {
            Text(
                text = beansTimeString(song.duration),
                color = colors.comment,
                fontSize = 11.sp,
            )
        }
    }
}

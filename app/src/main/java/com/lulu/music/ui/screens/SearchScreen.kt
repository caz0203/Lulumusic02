package com.lulu.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.api.KugouMusicApi
import com.lulu.music.data.api.NetEaseApi
import com.lulu.music.data.api.QQMusicApi
import com.lulu.music.data.model.Album
import com.lulu.music.data.model.Artist
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.data.store.SearchHistoryStore
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.PlayerOpenRequest
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansCapsuleShape
import com.lulu.music.ui.components.BeansCoverImage
import com.lulu.music.ui.components.BeansDetent
import com.lulu.music.ui.components.BeansEmptyState
import com.lulu.music.ui.components.BeansErrorState
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansLoadingState
import com.lulu.music.ui.components.BeansNowPlayingIndicator
import com.lulu.music.ui.components.BeansSectionHeader
import com.lulu.music.ui.components.BeansSurface
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.beansPressClickable
import com.lulu.music.ui.components.beansScrollDismissesKeyboard
import com.lulu.music.ui.theme.BeansTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Port of the iOS `SearchView`（Beans/SearchView.swift）——搜索标签页。
 *
 * 结构完全对照原文件：
 *  - 顶部标题 + 平台下拉菜单（`headerTitle`）
 *  - 液态玻璃搜索框（`searchField`，回车 / 「搜索」按钮提交，平台侧停止防抖）
 *  - 平台等宽分段选择（`providerPicker`）
 *  - 关键词为空时：搜索历史（`SearchHistorySection`）+ 各平台热搜标签云（`FlowLayout` → [FlowRow]）
 *  - 关键词非空时：歌曲 / 歌手 / 专辑分类（`typeTabs`）+ 结果区（`resultsArea`）
 *  - 点击歌手 → 歌手主页（`ArtistHomeSheet`：热门歌曲 / 过滤框 / 专辑网格）
 *  - 点击专辑 → 专辑详情（原文件里的 `AlbumDetailView`：专辑歌曲 + 歌手/专辑名匹配回退）
 *
 * 与 iOS 的差异（受 Android 壳层能力限制，均已在下文注释中标注）：
 *  1. iOS 用 `SearchTextField`（UITextField 封装）解决中文组字提交问题；Compose 的
 *     `BasicTextField` + `ImeAction.Search` 由输入法先提交组字再派发动作，无需该 workaround。
 *  2. iOS 的歌手页 / 专辑页是 `.sheet`；Android 壳层没有 artist / album 路由，
 *     这里用 [BeansBottomSheet] 做页内覆盖层（行为一致：加载、重试、播放、关闭）。
 *  3. iOS `@AppStorage("beans.hidePlatformPicker")` 隐藏平台控件、独立保存搜索平台；
 *     Android 的 [SettingsStore] 没有这两个键，因此平台控件常显、搜索平台取自
 *     `SettingsStore.homeProvider`（不写回，避免改写主页音源）。
 *  4. iOS 的 `DetailSongsCache`（专辑歌曲缓存）在 Android 数据层不存在，这里每次都请求。
 *  5. iOS 结果列表底部预留 180 / 130pt 给悬浮 TabBar；Android 的 TabBar 是壳层
 *     `Column` 里独立的兄弟节点、不覆盖内容，因此底部留白收敛为 32 / 24dp。
 *  6. iOS 的 `BeansHaptics.success()`（搜索成功）按原样保留；`BeansLogger` 日志
 *     （Android 数据层没有对应实现）不移植。
 *  7. 歌曲行长按菜单（iOS `.contextMenu`）保留「下一首播放 / 立即播放」；
 *     「添加到歌单 / 下载」在 Android 数据层与下载模块都不存在，改为收藏（FavoritesStore）。
 */
@Composable
fun SearchScreen() {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val enabledProvidersRaw by SettingsStore.enabledProviders.collectAsState()
    val homeProviderRaw by SettingsStore.homeProvider.collectAsState()
    val history by SearchHistoryStore.history.collectAsState()

    val currentSong by PlaybackController.currentSong.collectAsState()
    val isPlaying by PlaybackController.isPlaying.collectAsState()

    // iOS `PlatformPreferenceStore.enabledSearchProviders`（顺序固定、为空时回退网易云）
    val providers = remember(enabledProvidersRaw) { parseEnabledProviders(enabledProvidersRaw) }

    val state = rememberSaveable(saver = SearchUiStateSaver) { SearchUiState() }

    /** 用户是否手动选过平台：避免 DataStore 的首次真实值覆盖用户刚做出的选择。 */
    var providerTouched by remember { mutableStateOf(false) }

    // iOS `onAppear`：初始平台 = 上次选择的平台（"beans.search.provider" → Android 用 homeProvider）
    LaunchedEffect(homeProviderRaw) {
        if (!providerTouched) state.provider = SearchProvider.fromKey(homeProviderRaw)
    }

    // iOS `onReceive(platformPrefs.changes)`：平台被隐藏时回退到第一个可见平台，并重新加载热搜
    LaunchedEffect(providers) {
        if (state.provider !in providers) {
            state.provider = providers.first()
            state.hotLoadedProvider = null
        }
    }

    // iOS `.task(id: provider)`：每个平台只加载一次热搜
    LaunchedEffect(state.provider) {
        val selected = state.provider
        if (state.hotLoadedProvider == selected) return@LaunchedEffect
        state.hotLoadedProvider = selected
        state.hotWords = emptyList()
        state.hotWords = fetchHotWords(selected)
    }

    // iOS `onChange(of: provider)`：切换平台后立即用当前关键词重新搜索（不走防抖）
    LaunchedEffect(state.provider) {
        val trimmed = state.keyword.trim()
        if (trimmed.isEmpty()) return@LaunchedEffect
        state.debounceJob?.cancel()
        state.search(trimmed)
    }

    // iOS `onChange(of: keyword)`：400ms 防抖；清空关键词时立刻清结果
    LaunchedEffect(state.keyword) {
        val trimmed = state.keyword.trim()
        val self = coroutineContext[Job]
        state.debounceJob = self
        if (trimmed.isEmpty()) {
            state.clearResults()
            return@LaunchedEffect
        }
        if (state.consumeSuppressedDebounce(trimmed)) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MILLIS)
        state.search(trimmed)
    }

    /** iOS `submitSearch()`：点「搜索」/ 错误重试 —— 取消防抖、记历史、立即搜索 */
    fun submitSearch() {
        val trimmed = state.keyword.trim()
        if (trimmed.isEmpty()) return
        state.debounceJob?.cancel()
        SearchHistoryStore.record(trimmed)
        focusManager.clearFocus()
        scope.launch { state.search(trimmed) }
    }

    /** iOS 热搜标签 / 历史标签点击：立即搜索并记历史（跳过该关键词的防抖任务） */
    fun applyKeyword(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        state.debounceJob?.cancel()
        if (state.keyword.trim() != trimmed) state.suppressDebounce(trimmed)
        state.keyword = word
        SearchHistoryStore.record(trimmed)
        focusManager.clearFocus()
        scope.launch { state.search(trimmed) }
    }

    /** iOS 分类切换：清空该分类旧结果、立即进入加载态并重新搜索 */
    fun selectResultType(type: SearchResultType) {
        if (state.resultType == type) return
        state.resultType = type
        val trimmed = state.keyword.trim()
        if (trimmed.isEmpty()) return
        state.debounceJob?.cancel()
        state.clearResultsFor(type)
        state.errorMessage = null
        state.searching = true
        scope.launch { state.search(trimmed) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HeaderTitle(
            providers = providers,
            provider = state.provider,
            uiStyle = uiStyle,
            onSelectProvider = { candidate ->
                if (candidate != state.provider) {
                    providerTouched = true
                    state.provider = candidate
                }
            },
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 10.dp),
        )

        SearchField(
            keyword = state.keyword,
            searching = state.searching,
            uiStyle = uiStyle,
            onKeywordChange = { state.keyword = it },
            onSubmit = { submitSearch() },
            onClear = {
                state.debounceJob?.cancel()
                state.keyword = ""
                state.clearResults()
            },
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 10.dp),
        )

        ProviderPicker(
            providers = providers,
            provider = state.provider,
            uiStyle = uiStyle,
            onSelect = { candidate ->
                BeansHaptics.tap()
                if (candidate != state.provider) {
                    providerTouched = true
                    state.provider = candidate
                }
            },
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
        )

        if (state.keyword.isEmpty()) {
            HotSection(
                history = history,
                hotWords = state.hotWords,
                provider = state.provider,
                uiStyle = uiStyle,
                onSelectWord = { applyKeyword(it) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TypeTabs(
                resultType = state.resultType,
                uiStyle = uiStyle,
                onSelect = { selectResultType(it) },
            )
            ResultsArea(
                state = state,
                uiStyle = uiStyle,
                currentSongKey = currentSong?.identityKey,
                isPlaying = isPlaying,
                onRetry = { submitSearch() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // iOS `.sheet(item: $selectedArtist)` / `.sheet(item: $selectedAlbum)`
    state.selectedArtist?.let { artist ->
        SearchArtistSheet(artist = artist, uiStyle = uiStyle, onDismiss = { state.selectedArtist = null })
    }
    state.selectedAlbum?.let { album ->
        SearchAlbumSheet(album = album, uiStyle = uiStyle, onDismiss = { state.selectedAlbum = null })
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 平台 / 分类（Port of `SearchProvider` / `SearchResultType`）
// ---------------------------------------------------------------------------------------------

private const val SEARCH_DEBOUNCE_MILLIS = 400L

private enum class SearchProvider(val key: String, val zh: String, val en: String) {
    NETEASE("netease", "网易云音乐", "NetEase Cloud Music"),
    QQ("qq", "QQ音乐", "QQ Music"),
    KUGOU("kugou", "酷狗音乐", "Kugou Music");

    val displayName: String get() = beansLocalized(zh, en)

    /** iOS `icon`（SF Symbol）→ Material 图标 */
    val icon: ImageVector
        get() = when (this) {
            NETEASE -> Icons.Rounded.Cloud
            QQ -> Icons.Rounded.PlayArrow
            KUGOU -> Icons.Rounded.MusicNote
        }

    /** iOS `tint`：网易云红 / QQ 绿 / 酷狗蓝，topLeading → bottomTrailing 渐变 */
    val tint: Brush
        get() = when (this) {
            NETEASE -> Brush.linearGradient(
                listOf(Color(red = 0.93f, green = 0.22f, blue = 0.16f), Color(red = 0.80f, green = 0.15f, blue = 0.12f))
            )
            QQ -> Brush.linearGradient(
                listOf(Color(red = 0.15f, green = 0.78f, blue = 0.55f), Color(red = 0.05f, green = 0.58f, blue = 0.42f))
            )
            KUGOU -> Brush.linearGradient(
                listOf(Color(red = 0.12f, green = 0.58f, blue = 0.95f), Color(red = 0.02f, green = 0.32f, blue = 0.72f))
            )
        }

    companion object {
        fun fromKey(key: String?): SearchProvider =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: NETEASE
    }
}

private enum class SearchResultType(val key: String, val zh: String, val en: String) {
    SONG("song", "歌曲", "Songs"),
    ARTIST("artist", "歌手", "Artists"),
    ALBUM("album", "专辑", "Albums");

    val displayName: String get() = beansLocalized(zh, en)

    companion object {
        fun fromKey(key: String?): SearchResultType =
            entries.firstOrNull { it.key == key } ?: SONG
    }
}

/** iOS `PlatformPreferenceStore.enabledSearchProviders`：按固定顺序过滤、为空回退网易云。 */
private fun parseEnabledProviders(raw: String): List<SearchProvider> {
    val keys = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val list = SearchProvider.entries.filter { provider -> keys.any { it.equals(provider.key, ignoreCase = true) } }
    return list.ifEmpty { listOf(SearchProvider.NETEASE) }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 状态（对应 iOS SearchView 的 @State 集合）
// ---------------------------------------------------------------------------------------------

private sealed interface SearchOutcome {
    data class Songs(val songs: List<Song>) : SearchOutcome
    data class Artists(val artists: List<Artist>) : SearchOutcome
    data class Albums(val albums: List<Album>) : SearchOutcome
}

private class SearchUiState {
    var keyword by mutableStateOf("")
    var provider by mutableStateOf(SearchProvider.NETEASE)
    var resultType by mutableStateOf(SearchResultType.SONG)
    var songResults by mutableStateOf<List<Song>>(emptyList())
    var artistResults by mutableStateOf<List<Artist>>(emptyList())
    var albumResults by mutableStateOf<List<Album>>(emptyList())
    var hotWords by mutableStateOf<List<String>>(emptyList())
    var searching by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var selectedArtist by mutableStateOf<Artist?>(null)
    var selectedAlbum by mutableStateOf<Album?>(null)

    /** 防抖协程（`LaunchedEffect(keyword)` 自身），对应 iOS `debounceTask`。 */
    var debounceJob: Job? = null

    /** 已加载热搜的平台，避免来回切 tab 反复请求（iOS `hotLoadedProvider`）。 */
    var hotLoadedProvider: SearchProvider? = null

    /** 立即搜索已覆盖的关键词：抑制紧随其后的防抖任务，避免同一关键词请求两次。 */
    private var suppressedDebounce: String? = null

    /** 请求代次：丢弃过期响应（对应 iOS `searchTask?.cancel()` 的可见效果）。 */
    private var generation = 0

    fun suppressDebounce(text: String) {
        suppressedDebounce = text.trim()
    }

    fun consumeSuppressedDebounce(text: String): Boolean {
        if (suppressedDebounce != null && suppressedDebounce == text) {
            suppressedDebounce = null
            return true
        }
        return false
    }

    fun clearResults() {
        songResults = emptyList()
        artistResults = emptyList()
        albumResults = emptyList()
        errorMessage = null
    }

    fun clearResultsFor(type: SearchResultType) {
        when (type) {
            SearchResultType.SONG -> songResults = emptyList()
            SearchResultType.ARTIST -> artistResults = emptyList()
            SearchResultType.ALBUM -> albumResults = emptyList()
        }
    }

    /** iOS `startSearch(_:)`：按「平台 × 分类」分派请求，失败写入 [errorMessage]。 */
    suspend fun search(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val myGeneration = ++generation
        val selectedProvider = provider
        val selectedType = resultType
        searching = true
        errorMessage = null
        try {
            val outcome = requestSearch(trimmed, selectedProvider, selectedType)
            if (myGeneration != generation) return
            when (outcome) {
                is SearchOutcome.Songs -> {
                    songResults = outcome.songs
                    if (outcome.songs.isNotEmpty()) BeansHaptics.success()
                }
                is SearchOutcome.Artists -> artistResults = outcome.artists
                is SearchOutcome.Albums -> albumResults = outcome.albums
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (myGeneration != generation) return
            errorMessage = error.message
                ?: beansLocalized("搜索失败，请稍后重试", "Search failed, please try again")
        } finally {
            if (myGeneration == generation) searching = false
        }
    }
}

private val SearchUiStateSaver = listSaver<SearchUiState, String>(
    save = { listOf(it.keyword, it.provider.key, it.resultType.key) },
    restore = { saved ->
        SearchUiState().apply {
            keyword = saved.getOrElse(0) { "" }
            provider = SearchProvider.fromKey(saved.getOrNull(1))
            resultType = SearchResultType.fromKey(saved.getOrNull(2))
        }
    },
)

/** 与 iOS 一致地吞掉网络异常、但保留协程取消语义。 */
private suspend fun <T> safeApiOrNull(block: suspend () -> T): T? =
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

private suspend fun requestSearch(
    keyword: String,
    provider: SearchProvider,
    type: SearchResultType,
): SearchOutcome = when (provider) {
    SearchProvider.NETEASE -> when (type) {
        SearchResultType.SONG -> SearchOutcome.Songs(NetEaseApi.search(keyword = keyword, limit = 40))
        SearchResultType.ARTIST -> SearchOutcome.Artists(NetEaseApi.searchArtists(keyword = keyword))
        SearchResultType.ALBUM -> SearchOutcome.Albums(NetEaseApi.searchAlbums(keyword = keyword))
    }
    SearchProvider.QQ -> when (type) {
        SearchResultType.SONG -> SearchOutcome.Songs(QQMusicApi.searchSongs(keyword = keyword))
        SearchResultType.ARTIST -> SearchOutcome.Artists(QQMusicApi.searchArtists(keyword = keyword))
        SearchResultType.ALBUM -> SearchOutcome.Albums(QQMusicApi.searchAlbums(keyword = keyword))
    }
    SearchProvider.KUGOU -> when (type) {
        SearchResultType.SONG -> SearchOutcome.Songs(KugouMusicApi.searchSongs(keyword = keyword, limit = 40))
        SearchResultType.ARTIST -> SearchOutcome.Artists(KugouMusicApi.searchArtists(keyword = keyword))
        SearchResultType.ALBUM -> SearchOutcome.Albums(KugouMusicApi.searchAlbums(keyword = keyword))
    }
}

/** iOS `loadHotWords()`：QQ 失败静默保留空列表（`try?`），酷狗不抛错。 */
private suspend fun fetchHotWords(provider: SearchProvider): List<String> = when (provider) {
    SearchProvider.NETEASE -> safeApiOrNull { NetEaseApi.hotSearch() } ?: emptyList()
    SearchProvider.QQ -> safeApiOrNull { QQMusicApi.hotKeys() } ?: emptyList()
    SearchProvider.KUGOU -> safeApiOrNull { KugouMusicApi.hotWords() } ?: emptyList()
}

private fun hotSectionTitle(provider: SearchProvider): String = when (provider) {
    SearchProvider.NETEASE -> beansLocalized("网易云音乐热搜", "NetEase hot searches")
    SearchProvider.QQ -> beansLocalized("QQ音乐热搜", "QQ Music hot searches")
    SearchProvider.KUGOU -> beansLocalized("酷狗音乐热搜", "Kugou hot searches")
}

// ---------------------------------------------------------------------------------------------
// MARK: - 顶部标题 + 平台菜单（Port of `headerTitle`）
// ---------------------------------------------------------------------------------------------

@Composable
private fun HeaderTitle(
    providers: List<SearchProvider>,
    provider: SearchProvider,
    uiStyle: BeansUIStyle,
    onSelectProvider: (SearchProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val enabled = providers.size >= 2

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = beansLocalized("搜索", "Search"),
            color = colors.label,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Box {
            val interaction = remember { MutableInteractionSource() }
            BeansGlass(
                modifier = Modifier.beansPressClickable(
                    interactionSource = interaction,
                    enabled = enabled,
                    onClick = {
                        BeansHaptics.tap()
                        expanded = true
                    },
                ),
                shape = BeansCapsuleShape,
                style = uiStyle,
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = provider.icon,
                        contentDescription = null,
                        tint = colors.comment,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = provider.displayName,
                        color = colors.comment,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.comment,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                providers.forEach { candidate ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (candidate == provider) Icons.Rounded.Check else candidate.icon,
                                    contentDescription = null,
                                    tint = colors.comment,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(text = candidate.displayName, color = colors.label, fontSize = 14.sp)
                            }
                        },
                        onClick = {
                            BeansHaptics.tap()
                            expanded = false
                            onSelectProvider(candidate)
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 搜索框（Port of `searchField`）
// ---------------------------------------------------------------------------------------------

@Composable
private fun SearchField(
    keyword: String,
    searching: Boolean,
    uiStyle: BeansUIStyle,
    onKeywordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    BeansGlass(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        style = uiStyle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.comment,
                modifier = Modifier.size(17.dp),
            )

            BasicTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
                singleLine = true,
                textStyle = TextStyle(color = colors.label, fontSize = 15.sp),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (keyword.isEmpty()) {
                            Text(
                                text = beansLocalized("搜索歌曲、歌手、专辑", "Search songs, artists, or albums"),
                                color = colors.comment.copy(alpha = 0.65f),
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )

            // 固定尺寸的加载位（iOS 用 ZStack + 固定 frame，切换时布局不跳动）
            Box(
                modifier = Modifier.size(width = 20.dp, height = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (searching) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(width = 20.dp, height = 22.dp)
                    .then(
                        if (keyword.isEmpty()) {
                            Modifier
                        } else {
                            val clearInteraction = remember { MutableInteractionSource() }
                            Modifier.beansPressClickable(
                                interactionSource = clearInteraction,
                                scale = 0.9f,
                                onClick = onClear,
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (keyword.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = beansLocalized("清除", "Clear"),
                        tint = colors.comment.copy(alpha = 0.85f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            SmallCapsuleButton(
                text = beansLocalized("搜索", "Search"),
                onClick = onSubmit,
                uiStyle = uiStyle,
                useGlass = true,
                fontSize = 13.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 平台分段控件（Port of `providerPicker`）
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProviderPicker(
    providers: List<SearchProvider>,
    provider: SearchProvider,
    uiStyle: BeansUIStyle,
    onSelect: (SearchProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    BeansSurface(modifier = modifier.fillMaxWidth(), shape = BeansCapsuleShape, style = uiStyle) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            providers.forEach { candidate ->
                val selected = candidate == provider
                val interaction = remember(candidate) { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(BeansCapsuleShape)
                        .then(
                            if (selected) {
                                Modifier.background(brush = candidate.tint, shape = BeansCapsuleShape)
                            } else {
                                Modifier
                            },
                        )
                        .beansPressClickable(
                            interactionSource = interaction,
                            onClick = { onSelect(candidate) },
                        )
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = candidate.icon,
                        contentDescription = null,
                        tint = if (selected) Color.White else colors.comment,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = candidate.displayName,
                        color = if (selected) Color.White else colors.comment,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 分类选择（Port of `typeTabs`）
// ---------------------------------------------------------------------------------------------

@Composable
private fun TypeTabs(
    resultType: SearchResultType,
    uiStyle: BeansUIStyle,
    onSelect: (SearchResultType) -> Unit,
) {
    val colors = BeansTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 4.dp),
    ) {
        BeansSurface(modifier = Modifier.fillMaxWidth(), shape = BeansCapsuleShape, style = uiStyle) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SearchResultType.entries.forEach { type ->
                    val selected = type == resultType
                    val interaction = remember(type) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(BeansCapsuleShape)
                            .then(
                                if (selected) {
                                    Modifier.background(
                                        color = Color.White.copy(alpha = if (colors.isDark) 0.24f else 0.20f),
                                        shape = BeansCapsuleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .beansPressClickable(
                                interactionSource = interaction,
                                onClick = {
                                    BeansHaptics.tap()
                                    onSelect(type)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = type.displayName,
                            color = if (selected) colors.label else colors.comment,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 热搜区（Port of `hotSection` / `hotTag` + SearchHistorySection.swift）
// ---------------------------------------------------------------------------------------------

private val HOT_RANK_COLORS: List<List<Color>> = listOf(
    listOf(Color(red = 1.00f, green = 0.62f, blue = 0.18f), Color(red = 0.95f, green = 0.25f, blue = 0.18f)),
    listOf(Color(red = 1.00f, green = 0.82f, blue = 0.30f), Color(red = 0.98f, green = 0.56f, blue = 0.12f)),
    listOf(Color(red = 0.55f, green = 0.85f, blue = 1.00f), Color(red = 0.30f, green = 0.52f, blue = 0.98f)),
)

/** iOS `hotRankIcons`（crown.fill / flame.fill / sparkles）的 Material 等价物。 */
private val HOT_RANK_ICONS: List<ImageVector> = listOf(
    Icons.Rounded.WorkspacePremium,
    Icons.Rounded.Whatshot,
    Icons.Rounded.AutoAwesome,
)

@Composable
private fun HotSection(
    history: List<String>,
    hotWords: List<String>,
    provider: SearchProvider,
    uiStyle: BeansUIStyle,
    onSelectWord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 顶部是 LazyColumn（结果列表同理），保证「纵向滚动父级里不嵌套 LazyColumn」
    LazyColumn(
        modifier = modifier.beansScrollDismissesKeyboard(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        item(key = "history") {
            SearchHistorySection(
                history = history,
                uiStyle = uiStyle,
                onSelect = onSelectWord,
            )
        }
        item(key = "hot") {
            Column {
                Spacer(Modifier.height(16.dp))
                BeansSectionHeader(title = hotSectionTitle(provider))
                Spacer(Modifier.height(16.dp))
                if (hotWords.isEmpty()) {
                    BeansLoadingState()
                } else {
                    HotWordsFlow(
                        hotWords = hotWords,
                        uiStyle = uiStyle,
                        onSelect = onSelectWord,
                    )
                }
            }
        }
        // iOS 这里留了 130pt 给悬浮 TabBar；Android 的 TabBar 是壳层兄弟节点、不覆盖内容
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HotWordsFlow(
    hotWords: List<String>,
    uiStyle: BeansUIStyle,
    onSelect: (String) -> Unit,
) {
    // iOS `FlowLayout`（iOS 16+）/ 自适应 LazyVGrid 降级 → Compose [FlowRow]
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        hotWords.forEachIndexed { index, word ->
            HotTag(index = index, word = word, uiStyle = uiStyle, onClick = { onSelect(word) })
        }
    }
}

/** iOS `hotTag(index:word:)`：前三名渐变发光圆标，其余显示序号。 */
@Composable
private fun HotTag(
    index: Int,
    word: String,
    uiStyle: BeansUIStyle,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val top3 = index < 3
    val interaction = remember { MutableInteractionSource() }

    BeansGlass(
        modifier = Modifier.beansPressClickable(
            interactionSource = interaction,
            scale = 0.92f,
            onClick = {
                BeansHaptics.tap()
                onClick()
            },
        ),
        shape = BeansCapsuleShape,
        style = uiStyle,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (top3) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                brush = Brush.linearGradient(HOT_RANK_COLORS[index]),
                                shape = CircleShape,
                            )
                            .border(width = 1.dp, color = Color.White.copy(alpha = 0.6f), shape = CircleShape),
                    )
                    Icon(
                        imageVector = HOT_RANK_ICONS[index],
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${index + 1}",
                        color = colors.comment,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = word,
                color = if (top3) colors.label else colors.comment,
                fontSize = if (top3) 15.sp else 14.sp,
                fontWeight = if (top3) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Port of `SearchHistorySection`：历史胶囊 + 单条删除 + 清空。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistorySection(
    history: List<String>,
    uiStyle: BeansUIStyle,
    onSelect: (String) -> Unit,
) {
    if (history.isEmpty()) return
    val colors = BeansTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = beansLocalized("历史搜索", "Recent searches"),
                color = colors.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier.beansPressClickable(
                    interactionSource = interaction,
                    onClick = {
                        BeansHaptics.tap()
                        SearchHistoryStore.clear()
                        BeansToastCenter.show(beansLocalized("已清空搜索历史", "Search history cleared"))
                    },
                ),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = colors.comment,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = beansLocalized("清空", "Clear"),
                    color = colors.comment,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { word ->
                HistoryChip(word = word, uiStyle = uiStyle, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun HistoryChip(
    word: String,
    uiStyle: BeansUIStyle,
    onSelect: (String) -> Unit,
) {
    val colors = BeansTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val interaction = remember { MutableInteractionSource() }
        BeansGlass(
            modifier = Modifier.beansPressClickable(
                interactionSource = interaction,
                onClick = {
                    BeansHaptics.tap()
                    onSelect(word)
                },
            ),
            shape = BeansCapsuleShape,
            style = uiStyle,
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = colors.label,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = word,
                    color = colors.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val removeInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color = colors.glassFill, shape = CircleShape)
                .beansPressClickable(
                    interactionSource = removeInteraction,
                    onClick = {
                        BeansHaptics.tap()
                        SearchHistoryStore.remove(word)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = beansLocalized("删除", "Delete"),
                tint = colors.comment.copy(alpha = 0.8f),
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 结果区（Port of `resultsArea` / `songResultsArea` / `artistResultsArea` / `albumResultsArea`）
// ---------------------------------------------------------------------------------------------

@Composable
private fun ResultsArea(
    state: SearchUiState,
    uiStyle: BeansUIStyle,
    currentSongKey: String?,
    isPlaying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.resultType) {
        SearchResultType.SONG -> SongResultsArea(
            songs = state.songResults,
            provider = state.provider,
            searching = state.searching,
            errorMessage = state.errorMessage,
            uiStyle = uiStyle,
            currentSongKey = currentSongKey,
            isPlaying = isPlaying,
            onRetry = onRetry,
            onPlayAll = {
                PlaybackController.play(state.songResults, 0)
                PlayerOpenRequest.request()
            },
            onPlay = { index ->
                PlaybackController.play(state.songResults, index)
                PlayerOpenRequest.request()
            },
            modifier = modifier,
        )

        SearchResultType.ARTIST -> ArtistResultsArea(
            artists = state.artistResults,
            provider = state.provider,
            searching = state.searching,
            errorMessage = state.errorMessage,
            uiStyle = uiStyle,
            onRetry = onRetry,
            onOpen = { state.selectedArtist = it },
            modifier = modifier,
        )

        SearchResultType.ALBUM -> AlbumResultsArea(
            albums = state.albumResults,
            provider = state.provider,
            searching = state.searching,
            errorMessage = state.errorMessage,
            uiStyle = uiStyle,
            onRetry = onRetry,
            onOpen = { state.selectedAlbum = it },
            modifier = modifier,
        )
    }
}

@Composable
private fun SongResultsArea(
    songs: List<Song>,
    provider: SearchProvider,
    searching: Boolean,
    errorMessage: String?,
    uiStyle: BeansUIStyle,
    currentSongKey: String?,
    isPlaying: Boolean,
    onRetry: () -> Unit,
    onPlayAll: () -> Unit,
    onPlay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    when {
        errorMessage != null && songs.isEmpty() ->
            BeansErrorState(message = errorMessage, onRetry = onRetry, style = uiStyle)

        searching && songs.isEmpty() -> BeansLoadingState()

        songs.isEmpty() -> BeansEmptyState(
            systemName = "music.note",
            text = beansLocalized(
                "${provider.zh}未找到相关歌曲",
                "No songs found on ${provider.en}",
            ),
        )

        else -> LazyColumn(
            modifier = modifier.beansScrollDismissesKeyboard(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "count") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = beansLocalized(
                            "找到 ${songs.size} 首 · ${provider.zh}",
                            "Found ${songs.size} songs · ${provider.en}",
                        ),
                        color = colors.comment,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SmallCapsuleButton(
                        text = beansLocalized("播放全部", "Play all"),
                        onClick = onPlayAll,
                        uiStyle = uiStyle,
                        icon = Icons.Rounded.PlayArrow,
                    )
                }
            }
            // iOS 顶部悬浮的小菊花（播放按钮旁的加载提示）
            if (searching) {
                item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            itemsIndexed(songs, key = { _, song -> song.identityKey }) { index, song ->
                SearchSongRow(
                    song = song,
                    isCurrent = song.identityKey == currentSongKey,
                    isPlaying = isPlaying,
                    uiStyle = uiStyle,
                    onClick = {
                        BeansHaptics.tap()
                        onPlay(index)
                    },
                    onPlayNext = {
                        BeansHaptics.medium()
                        PlaybackController.playNext(song)
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtistResultsArea(
    artists: List<Artist>,
    provider: SearchProvider,
    searching: Boolean,
    errorMessage: String?,
    uiStyle: BeansUIStyle,
    onRetry: () -> Unit,
    onOpen: (Artist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    when {
        errorMessage != null && artists.isEmpty() ->
            BeansErrorState(message = errorMessage, onRetry = onRetry, style = uiStyle)

        searching && artists.isEmpty() -> BeansLoadingState()

        artists.isEmpty() -> BeansEmptyState(
            icon = Icons.Rounded.Person,
            text = beansLocalized(
                "${provider.zh}未找到相关歌手",
                "No artists found on ${provider.en}",
            ),
        )

        else -> LazyColumn(
            modifier = modifier.beansScrollDismissesKeyboard(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "count") {
                Text(
                    text = beansLocalized(
                        "找到 ${artists.size} 位 · ${provider.zh}",
                        "Found ${artists.size} artists · ${provider.en}",
                    ),
                    color = colors.comment,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
            if (searching) {
                item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            items(artists, key = { "${it.source.raw}-${it.id}" }) { artist ->
                val interaction = remember { MutableInteractionSource() }
                BeansSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .beansPressClickable(
                            interactionSource = interaction,
                            scale = 0.97f,
                            onClick = {
                                BeansHaptics.tap()
                                onOpen(artist)
                            },
                        ),
                    shape = RoundedCornerShape(14.dp),
                    style = uiStyle,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BeansCoverImage(url = artist.coverURL, size = 46.dp, cornerRadius = 23.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artist.name,
                                color = colors.label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = beansLocalized("查看歌手主页", "Open artist page"),
                                color = colors.comment,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = colors.comment,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumResultsArea(
    albums: List<Album>,
    provider: SearchProvider,
    searching: Boolean,
    errorMessage: String?,
    uiStyle: BeansUIStyle,
    onRetry: () -> Unit,
    onOpen: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    when {
        errorMessage != null && albums.isEmpty() ->
            BeansErrorState(message = errorMessage, onRetry = onRetry, style = uiStyle)

        searching && albums.isEmpty() -> BeansLoadingState()

        albums.isEmpty() -> BeansEmptyState(
            systemName = "square.stack",
            text = beansLocalized(
                "${provider.zh}未找到相关专辑",
                "No albums found on ${provider.en}",
            ),
        )

        else -> LazyColumn(
            modifier = modifier.beansScrollDismissesKeyboard(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "count") {
                Text(
                    text = beansLocalized(
                        "找到 ${albums.size} 张 · ${provider.zh}",
                        "Found ${albums.size} albums · ${provider.en}",
                    ),
                    color = colors.comment,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
            if (searching) {
                item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            items(albums, key = { "${it.source.raw}-${it.id}" }) { album ->
                val interaction = remember { MutableInteractionSource() }
                BeansSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .beansPressClickable(
                            interactionSource = interaction,
                            scale = 0.97f,
                            onClick = {
                                BeansHaptics.tap()
                                onOpen(album)
                            },
                        ),
                    shape = RoundedCornerShape(14.dp),
                    style = uiStyle,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BeansCoverImage(url = album.coverURL, size = 46.dp, cornerRadius = 10.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = album.name,
                                color = colors.label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = album.artistName.ifEmpty { beansLocalized("未知歌手", "Unknown artist") },
                                color = colors.comment,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = colors.comment,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 歌曲行（Port of SongCell.swift 在搜索结果里的用法）
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchSongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    uiStyle: BeansUIStyle,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
) {
    val colors = BeansTheme.colors
    val scope = rememberCoroutineScope()
    val rowInteraction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }
    var liked by remember(song.identityKey) { mutableStateOf(FavoritesStore.isLiked(song)) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        BeansSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            style = uiStyle,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = rowInteraction,
                        indication = null,
                        onLongClick = { menuOpen = true },
                        onClick = onClick,
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BeansCoverImage(url = song.coverURL, size = 46.dp, cornerRadius = 10.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = song.name,
                            color = if (isCurrent) colors.accent else colors.label,
                            fontSize = 15.sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (song.isVIP) BeansVIPBadge("VIP")
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (song.artists.isEmpty()) song.album else song.artists,
                        color = colors.comment,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isCurrent && isPlaying) {
                    BeansNowPlayingIndicator()
                } else {
                    Text(
                        text = song.formattedDuration,
                        color = colors.comment,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // iOS `.contextMenu`（长按菜单）：下一首播放 / 添加到歌单 / 下载 / 立即播放。
        // Android 侧没有 AddToLocalPlaylistSheet 与下载队列，这里保留可用的三项，
        // 并用 FavoritesStore 提供收藏项。
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(beansLocalized("下一首播放", "Play next")) },
                onClick = {
                    menuOpen = false
                    onPlayNext()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (liked) {
                            beansLocalized("取消收藏", "Remove from favourites")
                        } else {
                            beansLocalized("收藏", "Add to favourites")
                        },
                    )
                },
                onClick = {
                    menuOpen = false
                    BeansHaptics.tap()
                    scope.launch {
                        val stuck = FavoritesStore.toggle(song)
                        liked = FavoritesStore.isLiked(song)
                        BeansToastCenter.show(
                            when {
                                !stuck -> beansLocalized("操作失败，请稍后重试", "Action failed, please try again")
                                liked -> beansLocalized("已收藏", "Added to favourites")
                                else -> beansLocalized("已取消收藏", "Removed from favourites")
                            },
                        )
                    }
                },
            )
            if (!isCurrent) {
                DropdownMenuItem(
                    text = { Text(beansLocalized("立即播放", "Play now")) },
                    onClick = {
                        menuOpen = false
                        BeansHaptics.tap()
                        onClick()
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 小胶囊按钮（iOS「播放全部」/「搜索」这类小按钮）
// ---------------------------------------------------------------------------------------------

@Composable
private fun SmallCapsuleButton(
    text: String,
    onClick: () -> Unit,
    uiStyle: BeansUIStyle,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    useGlass: Boolean = false,
    fontSize: TextUnit = 12.sp,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val clickModifier = Modifier.beansPressClickable(
        interactionSource = interaction,
        scale = 0.9f,
        onClick = {
            BeansHaptics.tap()
            onClick()
        },
    )
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = text,
                color = colors.accent,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }

    if (useGlass) {
        BeansGlass(
            modifier = modifier.then(clickModifier),
            shape = BeansCapsuleShape,
            style = uiStyle,
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    } else {
        BeansSurface(
            modifier = modifier.then(clickModifier),
            shape = BeansCapsuleShape,
            style = uiStyle,
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 歌手主页（Port of ArtistHomeSheet.swift）
// ---------------------------------------------------------------------------------------------

private sealed interface ArtistLoadResult {
    data class Success(val artist: Artist?, val songs: List<Song>, val albums: List<Album>) : ArtistLoadResult
    data class Failure(val message: String) : ArtistLoadResult
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchArtistSheet(
    artist: Artist,
    uiStyle: BeansUIStyle,
    onDismiss: () -> Unit,
) {
    val colors = BeansTheme.colors
    val scope = rememberCoroutineScope()

    var resolvedArtist by remember(artist) { mutableStateOf<Artist?>(artist) }
    var hotSongs by remember(artist) { mutableStateOf<List<Song>>(emptyList()) }
    var albums by remember(artist) { mutableStateOf<List<Album>>(emptyList()) }
    var loading by remember(artist) { mutableStateOf(true) }
    var errorMessage by remember(artist) { mutableStateOf<String?>(null) }
    var filter by remember(artist) { mutableStateOf("") }
    var reloadToken by remember(artist) { mutableIntStateOf(0) }

    LaunchedEffect(artist, reloadToken) {
        loading = true
        errorMessage = null
        when (val result = loadArtistContent(artist)) {
            is ArtistLoadResult.Success -> {
                resolvedArtist = result.artist ?: artist
                hotSongs = result.songs
                albums = result.albums
            }
            is ArtistLoadResult.Failure -> errorMessage = result.message
        }
        loading = false
    }

    val displayed = remember(hotSongs, filter) {
        val keyword = filter.trim().lowercase()
        if (keyword.isEmpty()) {
            hotSongs
        } else {
            hotSongs.filter { song ->
                song.name.lowercase().contains(keyword) ||
                    song.artists.lowercase().contains(keyword) ||
                    song.album.lowercase().contains(keyword)
            }
        }
    }

    BeansBottomSheet(
        onDismissRequest = onDismiss,
        detents = listOf(BeansDetent.Large),
        style = uiStyle,
    ) {
        val error = errorMessage
        when {
            loading -> BeansLoadingState()
            error != null -> BeansErrorState(
                message = error,
                onRetry = { reloadToken++ },
                style = uiStyle,
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .beansScrollDismissesKeyboard(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
            ) {
                item(key = "header") {
                    ArtistHeader(
                        artist = resolvedArtist ?: artist,
                        songCount = hotSongs.size,
                        albumCount = albums.size,
                    )
                }
                item(key = "hot-header") {
                    Column {
                        Text(
                            text = beansLocalized("热门歌曲", "Popular songs"),
                            color = colors.label,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        if (hotSongs.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SheetPrimaryButton(
                                    text = beansLocalized("播放全部", "Play all"),
                                    icon = Icons.Rounded.PlayArrow,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        BeansHaptics.tap()
                                        PlaybackController.play(displayed, 0)
                                        PlayerOpenRequest.request()
                                        onDismiss()
                                    },
                                )
                                SheetOutlineButton(
                                    text = beansLocalized("随机播放", "Shuffle"),
                                    icon = Icons.Rounded.Shuffle,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        BeansHaptics.tap()
                                        PlaybackController.play(displayed.shuffled(), 0)
                                        PlayerOpenRequest.request()
                                        onDismiss()
                                    },
                                )
                            }
                            ArtistFilterField(
                                value = filter,
                                onValueChange = { filter = it },
                                uiStyle = uiStyle,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(top = 8.dp),
                            )
                        } else {
                            Text(
                                text = beansLocalized("暂无歌曲", "No songs"),
                                color = colors.comment,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                itemsIndexed(displayed, key = { _, song -> song.identityKey }) { index, song ->
                    ArtistSongRow(
                        index = index,
                        song = song,
                        onClick = {
                            BeansHaptics.tap()
                            PlaybackController.play(displayed, index)
                            PlayerOpenRequest.request()
                            // 弹层是独立 Dialog 窗口，会盖在新打开的播放页上。
                            onDismiss()
                        },
                    )
                }
                if (artist.source == SongSource.NET_EASE) {
                    item(key = "album-header") {
                        Text(
                            text = beansLocalized("专辑", "Albums"),
                            color = colors.label,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp),
                        )
                    }
                    if (albums.isEmpty()) {
                        item(key = "album-empty") {
                            Text(
                                text = beansLocalized("暂无专辑", "No albums"),
                                color = colors.comment,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    } else {
                        items(albums.chunked(3)) { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                row.forEach { album ->
                                    ArtistAlbumCard(
                                        album = album,
                                        uiStyle = uiStyle,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            BeansHaptics.tap()
                                            scope.launch {
                                                val id = album.id.removePrefix("netease-").toLongOrNull()
                                                val songs = if (id == null) {
                                                    emptyList()
                                                } else {
                                                    safeApiOrNull { NetEaseApi.albumSongs(albumID = id) } ?: emptyList()
                                                }
                                                if (songs.isNotEmpty()) {
                                                    PlaybackController.play(songs, 0)
                                                    PlayerOpenRequest.request()
                                                    onDismiss()
                                                } else {
                                                    BeansToastCenter.show(
                                                        beansLocalized("专辑歌曲加载失败", "Failed to load album tracks"),
                                                        2_000L,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                                repeat(3 - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(
    artist: Artist,
    songCount: Int,
    albumCount: Int,
) {
    val colors = BeansTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BeansCoverImage(url = artist.coverURL, size = 72.dp, cornerRadius = 36.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                color = colors.label,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (artist.source == SongSource.NET_EASE) {
                    beansLocalized(
                        "热门歌曲 $songCount 首 · 专辑 $albumCount 张",
                        "Popular songs: $songCount · Albums: $albumCount",
                    )
                } else {
                    beansLocalized("热门歌曲 $songCount 首", "Popular songs: $songCount")
                },
                color = colors.comment,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SheetPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(BeansCapsuleShape)
            .background(color = colors.accent, shape = BeansCapsuleShape)
            .beansPressClickable(interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SheetOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(BeansCapsuleShape)
            .border(width = 1.dp, color = colors.accent.copy(alpha = 0.5f), shape = BeansCapsuleShape)
            .beansPressClickable(interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                color = colors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ArtistFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    uiStyle: BeansUIStyle,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    BeansSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        style = uiStyle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.comment,
                modifier = Modifier.size(14.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = colors.label, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = beansLocalized("搜索歌手歌曲", "Search artist songs"),
                                color = colors.comment.copy(alpha = 0.65f),
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .beansPressClickable(
                            interactionSource = interaction,
                            scale = 0.9f,
                            onClick = { onValueChange("") },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = beansLocalized("清除", "Clear"),
                        tint = colors.comment,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistSongRow(
    index: Int,
    song: Song,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .beansPressClickable(interactionSource = interaction, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "${index + 1}",
            color = if (index < 3) colors.accent else colors.comment,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(22.dp),
        )
        BeansCoverImage(url = song.coverURL, size = 40.dp, cornerRadius = 8.dp)
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
                text = song.album,
                color = colors.comment,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArtistAlbumCard(
    album: Album,
    uiStyle: BeansUIStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    BeansSurface(
        modifier = modifier.beansPressClickable(interactionSource = interaction, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        style = uiStyle,
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            BeansCoverImage(
                url = album.coverURL,
                size = 88.dp,
                cornerRadius = 12.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = album.name,
                color = colors.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val count = album.trackCount
            if (count != null) {
                Text(
                    text = com.lulu.music.ui.components.beansSongCountText(count),
                    color = colors.comment,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** iOS `ArtistHomeSheet.load()` 分派。 */
private suspend fun loadArtistContent(artist: Artist): ArtistLoadResult = when (artist.source) {
    SongSource.NET_EASE -> loadNetEaseArtistContent(artist)
    SongSource.QQ -> loadQQArtistContent(artist)
    SongSource.KUGOU -> loadKugouArtistContent(artist)
}

private suspend fun loadNetEaseArtistContent(artist: Artist): ArtistLoadResult {
    val rawID = artist.id.removePrefix("netease-").toLongOrNull()?.takeIf { it > 0 }
    val resolved: Artist
    val artistID: Long
    if (rawID != null) {
        resolved = artist
        artistID = rawID
    } else {
        val found = safeApiOrNull { NetEaseApi.searchArtists(keyword = artist.name, limit = 5) }?.firstOrNull()
            ?: return ArtistLoadResult.Failure(
                beansLocalized("未找到歌手「${artist.name}」", "Artist \"${artist.name}\" not found")
            )
        val parsed = found.id.removePrefix("netease-").toLongOrNull()?.takeIf { it > 0 }
            ?: return ArtistLoadResult.Failure(
                beansLocalized("未找到歌手「${artist.name}」", "Artist \"${artist.name}\" not found")
            )
        resolved = found
        artistID = parsed
    }
    val songs = safeApiOrNull { NetEaseApi.artistHotSongs(artistID = artistID, limit = 300) } ?: emptyList()
    val albums = safeApiOrNull { NetEaseApi.artistAlbums(artistID = artistID) } ?: emptyList()
    val finalSongs = if (songs.isEmpty()) netEaseArtistFallbackSongs(artist.name) else songs
    return ArtistLoadResult.Success(resolved, finalSongs, albums)
}

/** iOS：接口异常时用分页搜索兜底，避免只剩首批 30 首。 */
private suspend fun netEaseArtistFallbackSongs(name: String): List<Song> {
    val collected = mutableListOf<Song>()
    for (offset in 0 until 300 step 30) {
        val page = safeApiOrNull { NetEaseApi.search(keyword = name, limit = 30, offset = offset) } ?: emptyList()
        if (page.isEmpty()) break
        collected += page
        if (page.size < 30) break
    }
    return collected
}

private suspend fun loadQQArtistContent(artist: Artist): ArtistLoadResult {
    var resolved: Artist? = artist
    var mid: String? = artist.id.takeIf { it.isNotEmpty() && !it.startsWith("qq-") }
    if (mid == null) {
        val found = safeApiOrNull { QQMusicApi.searchArtists(keyword = artist.name, limit = 5) }?.firstOrNull()
        if (found != null) {
            resolved = found
            mid = found.id
        }
    }
    var songs = safeApiOrNull { QQMusicApi.artistHotSongs(mid = mid, name = artist.name, limit = 300) }
        ?: emptyList()
    if (songs.isEmpty()) {
        val fallback = mutableListOf<Song>()
        for (offset in 0 until 300 step 30) {
            val page = safeApiOrNull {
                QQMusicApi.searchSongs(keyword = artist.name, limit = 30, offset = offset)
            } ?: emptyList()
            if (page.isEmpty()) break
            fallback += page
            if (page.size < 30) break
        }
        val seen = mutableSetOf<String>()
        songs = fallback.filter { seen.add(it.identityKey) }
    }
    return ArtistLoadResult.Success(resolved, songs, emptyList())
}

private suspend fun loadKugouArtistContent(artist: Artist): ArtistLoadResult {
    val rawID = artist.id.removePrefix("kugou-")
    val resolved: Artist = if (rawID.isNotEmpty() && !rawID.startsWith("qq-")) {
        artist
    } else {
        safeApiOrNull { KugouMusicApi.searchArtists(keyword = artist.name, limit = 10) }?.firstOrNull() ?: artist
    }

    val songs = mutableListOf<Song>()
    val seen = mutableSetOf<String>()
    val authorID = resolved.id.removePrefix("kugou-")
    if (authorID.isNotEmpty() && !authorID.startsWith("qq-")) {
        val pageSize = 100
        val maxSongs = 1_000
        for (page in 1..(maxSongs / pageSize)) {
            val batch = safeApiOrNull {
                KugouMusicApi.artistSongs(authorID = authorID, page = page, limit = pageSize)
            } ?: emptyList()
            if (batch.isEmpty()) break
            val before = songs.size
            for (song in batch) {
                if (seen.add(song.identityKey)) {
                    songs += song
                    if (songs.size >= maxSongs) break
                }
            }
            if (songs.size >= maxSongs || songs.size == before) break
        }
    }

    // 作者接口历史上只返回 19 行，不足 100 首时用分页搜索补齐
    if (songs.size < 100) {
        val candidates = listOf(
            safeApiOrNull { KugouMusicApi.searchSongs(keyword = artist.name, limit = 300) } ?: emptyList(),
            safeApiOrNull { KugouMusicApi.searchSongs(keyword = "${artist.name} 歌曲", limit = 300) } ?: emptyList(),
        )
        for (song in candidates.flatten()) {
            if (seen.add(song.identityKey)) songs += song
        }
    }

    if (songs.isEmpty()) {
        val batches = listOfNotNull(
            safeApiOrNull { KugouMusicApi.searchSongs(keyword = artist.name, limit = 300) },
            safeApiOrNull { KugouMusicApi.searchSongs(keyword = "${artist.name} 热门", limit = 200) },
            safeApiOrNull { KugouMusicApi.searchSongs(keyword = "${artist.name} 歌曲", limit = 200) },
        )
        val seenFallback = mutableSetOf<String>()
        songs += batches.flatten().filter { seenFallback.add(it.identityKey) }
    }

    return ArtistLoadResult.Success(resolved, songs.take(1_000), emptyList())
}

// ---------------------------------------------------------------------------------------------
// MARK: - 专辑详情（Port of SearchView.swift 内的 `AlbumDetailView`）
// ---------------------------------------------------------------------------------------------

private data class AlbumTracksResult(val tracks: List<Song>, val error: String?)

@Composable
private fun SearchAlbumSheet(
    album: Album,
    uiStyle: BeansUIStyle,
    onDismiss: () -> Unit,
) {
    val colors = BeansTheme.colors
    var tracks by remember(album) { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember(album) { mutableStateOf(true) }
    var errorMessage by remember(album) { mutableStateOf<String?>(null) }
    var reloadToken by remember(album) { mutableIntStateOf(0) }

    LaunchedEffect(album, reloadToken) {
        loading = true
        errorMessage = null
        val result = loadAlbumTracks(album)
        tracks = result.tracks
        errorMessage = result.error
        loading = false
    }

    BeansBottomSheet(
        onDismissRequest = onDismiss,
        detents = listOf(BeansDetent.Large),
        style = uiStyle,
    ) {
        val error = errorMessage
        val currentSong by PlaybackController.currentSong.collectAsState()
        val isPlaying by PlaybackController.isPlaying.collectAsState()
        when {
            loading -> BeansLoadingState()
            error != null -> BeansErrorState(
                message = error,
                onRetry = { reloadToken++ },
                style = uiStyle,
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .beansScrollDismissesKeyboard(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
            ) {
                item(key = "header") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            BeansCoverImage(url = album.coverURL, size = 92.dp, cornerRadius = 16.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = album.name,
                                    color = colors.label,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = album.artistName.ifEmpty { beansLocalized("未知歌手", "Unknown artist") },
                                    color = colors.comment,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = com.lulu.music.ui.components.beansSongCountText(tracks.size),
                                    color = colors.comment,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        if (tracks.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SmallCapsuleButton(
                                text = beansLocalized("播放全部", "Play all"),
                                onClick = {
                                    BeansHaptics.tap()
                                    PlaybackController.play(tracks, 0)
                                    PlayerOpenRequest.request()
                                    onDismiss()
                                },
                                uiStyle = uiStyle,
                                icon = Icons.Rounded.PlayArrow,
                            )
                        }
                    }
                }
                itemsIndexed(tracks, key = { _, song -> song.identityKey }) { index, song ->
                    SearchSongRow(
                        song = song,
                        isCurrent = song.identityKey == currentSong?.identityKey,
                        isPlaying = isPlaying,
                        uiStyle = uiStyle,
                        onClick = {
                            BeansHaptics.tap()
                            PlaybackController.play(tracks, index)
                            PlayerOpenRequest.request()
                            onDismiss()
                        },
                        onPlayNext = {
                            BeansHaptics.medium()
                            PlaybackController.playNext(song)
                        },
                    )
                }
            }
        }
    }
}

private suspend fun loadAlbumTracks(album: Album): AlbumTracksResult {
    // 网易云先走专辑歌曲接口；为空（或非网易云）再按「歌手 + 专辑名」搜索回退
    if (album.source == SongSource.NET_EASE) {
        val id = album.id.removePrefix("netease-").toLongOrNull()
        if (id != null) {
            val direct = safeApiOrNull { NetEaseApi.albumSongs(albumID = id) } ?: emptyList()
            if (direct.isNotEmpty()) return AlbumTracksResult(direct, null)
        }
    }

    if (normalizedArtistName(album.artistName).isEmpty()) {
        return AlbumTracksResult(emptyList(), beansLocalized("未找到专辑歌曲", "No album tracks found"))
    }

    val queries = listOf(albumSearchQuery(album), album.name)
    val tried = mutableSetOf<String>()
    for (query in queries) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !tried.add(trimmed)) continue
        val songs = when (album.source) {
            SongSource.NET_EASE -> safeApiOrNull { NetEaseApi.search(keyword = trimmed, limit = 100) } ?: emptyList()
            SongSource.QQ -> safeApiOrNull { QQMusicApi.searchSongs(keyword = trimmed, limit = 100) } ?: emptyList()
            SongSource.KUGOU -> safeApiOrNull { KugouMusicApi.searchSongs(keyword = trimmed, limit = 100) } ?: emptyList()
        }
        val matches = songs.filter { albumSongMatches(album, it) }
        if (matches.isNotEmpty()) {
            val seen = mutableSetOf<String>()
            return AlbumTracksResult(matches.filter { seen.add(it.identityKey) }, null)
        }
    }
    // 宁可空结果，也不展示歌手的不相关歌曲（与原注释一致）
    return AlbumTracksResult(emptyList(), beansLocalized("未找到专辑歌曲", "No album tracks found"))
}

private fun albumSearchQuery(album: Album): String {
    val artist = album.artistName.trim()
    return if (artist.isEmpty()) album.name else "$artist ${album.name}"
}

private fun albumSongMatches(album: Album, song: Song): Boolean =
    albumNamesMatch(song.album, album.name) && artistsMatch(album.artistName, song.artists)

private val ARTIST_NAME_PUNCTUATION: Set<Char> =
    "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~·、。，！？；：（）【】《》“”‘’".toSet()

private fun normalizedArtistName(value: String): String =
    value.lowercase()
        .replace('（', '(')
        .replace('）', ')')
        .replace(Regex("\\(.*?\\)"), "")
        .filter { !it.isWhitespace() && it !in ARTIST_NAME_PUNCTUATION }

private fun artistTokens(value: String): List<String> =
    value.split(Regex("[/／,，、&＆+＋|｜;；]"))
        .map { normalizedArtistName(it) }
        .filter { it.isNotEmpty() }

private fun artistsMatch(expected: String, actual: String): Boolean {
    val expectedTokens = artistTokens(expected)
    val actualTokens = artistTokens(actual)
    if (expectedTokens.isEmpty() || actualTokens.isEmpty()) return false
    // 歌曲可能带上合作歌手：只要有一个主歌手精确匹配即可；前缀匹配仅限较长名字
    return expectedTokens.any { expectedToken ->
        actualTokens.any { actualToken ->
            if (expectedToken == actualToken) return@any true
            if (minOf(expectedToken.length, actualToken.length) < 3) return@any false
            expectedToken.startsWith(actualToken) || actualToken.startsWith(expectedToken)
        }
    }
}

private fun albumNamesMatch(lhs: String, rhs: String): Boolean {
    fun normalize(value: String): String =
        value.lowercase()
            .replace('（', '(')
            .replace('）', ')')
            .replace(Regex("\\(.*?\\)"), "")
            .filter { !it.isWhitespace() && it != '-' && it != '·' }

    val a = normalize(lhs)
    val b = normalize(rhs)
    if (a.isEmpty() || b.isEmpty()) return false
    return a == b || a.contains(b) || b.contains(a)
}

package com.lulu.music.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SavedSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.BeansTabIconStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.update.UpdateChecker
import com.lulu.music.data.update.UpdateConfig
import com.lulu.music.data.update.UpdateInfo
import com.lulu.music.ui.components.BeansFloatingEffectOverlay
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansToastHost
import com.lulu.music.ui.components.GlassBackdrop
import com.lulu.music.ui.screens.BeansLoginScreen
import com.lulu.music.ui.screens.BeansPlayerScreen
import com.lulu.music.ui.screens.BeansSettingsScreen
import com.lulu.music.ui.screens.DiscoverHomeFlags
import com.lulu.music.ui.screens.DiscoverScreen
import com.lulu.music.ui.screens.DiscoverSection
import com.lulu.music.ui.screens.DownloadsScreen
import com.lulu.music.ui.screens.LibraryScreen
import com.lulu.music.ui.screens.PlaylistDetailScreen
import com.lulu.music.ui.screens.ProfileScreen
import com.lulu.music.ui.screens.SearchScreen
import com.lulu.music.ui.screens.ThirdPartySourceScreen
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.delay

/**
 * Bottom navigation destinations, mirroring the reference design's five-tab bar.
 *
 * **Declaration order is the tab order**, and index 0 must stay 主页 / Home: `BeansApp()` reads
 * `selectedTab == 0` to decide `GlassBackdrop(homeMode = …)`, so reordering entries would silently
 * disable the wallpaper / background colour on the home page.
 */
enum class RootTab(val zh: String, val en: String, val icon: ImageVector) {
    HOME("主页", "Home", Icons.Rounded.Home),
    FEATURED("精选", "Featured", Icons.Rounded.GridView),
    PLAYLISTS("歌单", "Playlists", Icons.Rounded.LibraryMusic),
    PROFILE("我的", "Profile", Icons.Rounded.Person),
    SEARCH("搜索", "Search", Icons.Rounded.Search),
}

/**
 * 底栏图标样式 → 图标（参考图「底栏图标样式：Apple Music / SF Symbols / 圆润样式」）。
 *
 * 三种样式对同一个标签必须给出**不同**的图标，否则又是一个点了没反应的设置。
 * 纯函数：`ui/BeansApp.kt` 的 `BeansTabBar` 是唯一消费方，测试可以直接断言三组图标两两不同。
 */
fun beansTabIcon(tab: RootTab, style: BeansTabIconStyle): ImageVector = when (style) {
    BeansTabIconStyle.APPLE_MUSIC -> tab.icon
    BeansTabIconStyle.SF_SYMBOLS -> when (tab) {
        RootTab.HOME -> Icons.Outlined.Home
        RootTab.FEATURED -> Icons.Outlined.GridView
        RootTab.PLAYLISTS -> Icons.Outlined.LibraryMusic
        RootTab.PROFILE -> Icons.Outlined.Person
        RootTab.SEARCH -> Icons.Outlined.Search
    }

    BeansTabIconStyle.ROUNDED -> when (tab) {
        RootTab.HOME -> Icons.Rounded.Cottage
        RootTab.FEATURED -> Icons.Rounded.Dashboard
        RootTab.PLAYLISTS -> Icons.AutoMirrored.Rounded.QueueMusic
        RootTab.PROFILE -> Icons.Rounded.AccountCircle
        // 搜索：Apple Music 与 SF Symbols 已经用了 Rounded.Search / Outlined.Search，
        // 这里必须换一个字形，否则「圆润样式」在搜索标签上看不出任何变化。
        RootTab.SEARCH -> Icons.Rounded.SavedSearch
    }
}

/**
 * 「底部栏显示」：逗号分隔的 [RootTab] 名称 → 要渲染的标签下标（升序）。
 *
 * 空串 = 全部显示（默认）。**主页（下标 0）永远保留**：否则用户可以把主页入口整个关掉，
 * 而 `selectedTab == 0` 又是主页背景（`homeMode`）的判定依据，关掉之后首页会变成一个没有任何
 * 标签可以回去的孤儿页面。
 */
fun visibleRootTabIndices(raw: String): List<Int> {
    val names = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isEmpty()) return RootTab.entries.indices.toList()
    val picked = RootTab.entries.mapIndexedNotNull { index, tab -> index.takeIf { tab.name in names } }
    return (listOf(0) + picked).distinct().sorted()
}

/** 「底部栏显示」里切换一个入口；主页（下标 0）不可关闭，永远原样返回。 */
fun beansToggleRootTab(raw: String, index: Int): String {
    if (index == 0) return raw
    val current = visibleRootTabIndices(raw).toMutableSet()
    if (!current.remove(index)) current.add(index)
    current.add(0)
    return current.sorted().mapNotNull { RootTab.entries.getOrNull(it)?.name }.joinToString(",")
}

/** 低系统悬浮底栏的滑块量程（`SettingsScreen` 与 [beansDockGeometry] 共用同一组常量）。 */
val BeansDockRadiusRange = 12f..44f
val BeansDockWidthRange = 240f..420f
val BeansDockOffsetRange = -80f..80f

/** 低系统悬浮底栏的几何：全部按量程夹紧，防止写坏的偏好把底栏顶出屏幕。 */
data class BeansDockGeometry(
    val cornerRadius: Dp,
    val widthCap: Dp,
    val offsetX: Dp,
    val offsetY: Dp,
)

/** 「低系统悬浮底栏」4 个滑块 → 实际几何（纯函数，消费方是 [BeansTabBar]）。 */
fun beansDockGeometry(
    radius: Float,
    width: Float,
    offsetX: Float,
    offsetY: Float,
): BeansDockGeometry = BeansDockGeometry(
    cornerRadius = radius.coerceIn(BeansDockRadiusRange).dp,
    widthCap = width.coerceIn(BeansDockWidthRange).dp,
    offsetX = offsetX.coerceIn(BeansDockOffsetRange).dp,
    offsetY = offsetY.coerceIn(BeansDockOffsetRange).dp,
)

/**
 * 主页背景色的取值规则（纯函数，消费方是 [BeansApp] 里的 `GlassBackdrop(customColor = ...)`）。
 *
 * 浅色 / 深色两支按当前明暗取一支；两支都为空时回落到旧的通用背景色
 * `beans.customBackgroundHex`（老用户升级后原来的背景色不会丢）。
 */
fun beansHomeBackgroundHex(
    isDark: Boolean,
    lightHex: String,
    darkHex: String,
    legacyHex: String,
): String = (if (isDark) darkHex else lightHex).ifBlank { legacyHex }

/**
 * Navigation callbacks injected through a CompositionLocal so screens do not need lambda drilling
 * through the whole tree.
 */
class BeansNavigator(
    val openPlayer: () -> Unit,
    val openSettings: () -> Unit,
    val openLogin: () -> Unit,
    val openDownloads: () -> Unit,
    val openSources: () -> Unit,
    val openPlaylist: (Playlist) -> Unit,
    val openPlaylistById: (Long, SongSource) -> Unit,
)

val LocalBeansNavigator = staticCompositionLocalOf<BeansNavigator> {
    error("BeansNavigator was not provided")
}

private object Routes {
    const val ROOT = "root"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val DOWNLOADS = "downloads"
    const val SOURCES = "sources"
    const val PLAYLIST = "playlist/{id}/{source}"
    fun playlist(id: Long, source: SongSource) = "playlist/$id/${source.raw}"
}

@Composable
fun BeansApp() {
    val navController = rememberNavController()
    val context = LocalContext.current

    /**
     * 启动检查更新（对齐 iOS 的「检查更新」）。
     *
     * 只在首帧之后触发一次，且完全跑在后台协程里：网络慢 / 断网都不会拖慢启动。
     * [UpdateChecker.check] 自身绝不抛异常，失败时返回 [UpdateChecker.Result.Failed]，
     * 这里对应的分支什么都不做 —— 静默失败，不弹窗也不 toast。
     */
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        if (UpdateConfig.MANIFEST_URL.isBlank()) return@LaunchedEffect
        delay(1) // 让第一帧先画出来，检查更新只做「尽力而为」的后台补充。
        when (val result = UpdateChecker.check()) {
            is UpdateChecker.Result.Available ->
                if (result.info.versionCode != UpdateChecker.skippedVersionCode()) {
                    updateInfo = result.info
                }
            UpdateChecker.Result.UpToDate -> Unit
            UpdateChecker.Result.Failed -> Unit
        }
    }

    /**
     * 用户点歌后自动展开全屏播放器（对齐网易云：点歌即进播放页）。
     *
     * 只在用户点按的路径上由 [PlayerOpenRequest.request] 触发 —— 上一首 / 下一首 / 自动续播 /
     * 启动恢复会话都会改变 `currentSong`，但不会经过这里，所以不会在用户浏览歌单时抢屏。
     *
     * 播放页已经在前台时直接忽略，避免连点叠加出多层播放页（返回键要按很多次才能退出）。
     */
    LaunchedEffect(navController) {
        PlayerOpenRequest.requests.collect {
            val route = navController.currentBackStackEntry?.destination?.route
            if (route != Routes.PLAYER) {
                navController.navigate(Routes.PLAYER)
            }
        }
    }

    val navigator = remember(navController) {
        BeansNavigator(
            openPlayer = { navController.navigate(Routes.PLAYER) },
            openSettings = { navController.navigate(Routes.SETTINGS) },
            openLogin = { navController.navigate(Routes.LOGIN) },
            openDownloads = { navController.navigate(Routes.DOWNLOADS) },
            openSources = { navController.navigate(Routes.SOURCES) },
            openPlaylist = { p -> navController.navigate(Routes.playlist(p.id, p.source)) },
            openPlaylistById = { id, source -> navController.navigate(Routes.playlist(id, source)) },
        )
    }

    val themeMode by SettingsStore.themeMode.collectAsState()
    val accentHex by SettingsStore.accentHex.collectAsState()
    val labelHex by SettingsStore.labelColorHex.collectAsState()
    val commentHex by SettingsStore.commentColorHex.collectAsState()
    val backgroundSyncAll by SettingsStore.backgroundSyncAll.collectAsState()
    val customBackgroundHex by SettingsStore.customBackgroundHex.collectAsState()
    val homeLightBackgroundHex by SettingsStore.homeLightBackgroundHex.collectAsState()
    val homeDarkBackgroundHex by SettingsStore.homeDarkBackgroundHex.collectAsState()
    val backgroundImagePath by SettingsStore.backgroundImagePath.collectAsState()
    val wallpaperBlur by SettingsStore.wallpaperBlur.collectAsState()
    val uiStyle by SettingsStore.uiStyle.collectAsState()
    // 当前标签提到这里：全屏背景层必须知道「现在是不是主页」，因为主页无论
    // 「同步到其他页面」开关是否打开都要显示壁纸 / 背景色（对应 iOS 的 homeMode）。
    // 之前这段状态在 BeansRootScaffold 里，外层背景层拿不到，于是 homeMode 永远是 false，
    // 加上 backgroundSyncAll 默认关闭 —— 结果壁纸和背景色在**任何页面都不显示**。
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    com.lulu.music.ui.theme.BeansTheme(
        themeMode = themeMode,
        accentKey = accentHex.ifBlank { "amber" },
        customAccent = com.lulu.music.ui.theme.parseHexColor(accentHex),
        labelOverride = com.lulu.music.ui.theme.parseHexColor(labelHex),
        commentOverride = com.lulu.music.ui.theme.parseHexColor(commentHex),
    ) {
        val colors = BeansTheme.colors
        // 主页背景色分「浅色模式 / 深色模式」两支：按当前明暗取对应的一支，
        // 两支都为空时回落到旧的通用背景色 `beans.customBackgroundHex`（老用户设置不丢）。
        val homeBackgroundHex = beansHomeBackgroundHex(
            isDark = colors.isDark,
            lightHex = homeLightBackgroundHex,
            darkHex = homeDarkBackgroundHex,
            legacyHex = customBackgroundHex,
        )
        CompositionLocalProvider(LocalBeansNavigator provides navigator) {
            GlassBackdrop(
                modifier = Modifier.fillMaxSize(),
                customColor = com.lulu.music.ui.theme.parseHexColor(homeBackgroundHex),
                wallpaperPath = backgroundImagePath.ifBlank { null },
                homeMode = selectedTab == 0,
                backgroundSyncAll = backgroundSyncAll,
                wallpaperBlur = wallpaperBlur.dp,
                style = uiStyle,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.ROOT,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(Routes.ROOT) {
                        BeansRootScaffold(
                            navigator = navigator,
                            selected = selectedTab,
                            onSelect = { selectedTab = it },
                        )
                    }
                    composable(Routes.PLAYER) {
                        BeansPlayerScreen(onDismiss = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS) {
                        BeansSettingsScreen(onDismiss = { navController.popBackStack() })
                    }
                    composable(Routes.LOGIN) {
                        BeansLoginScreen(onDismiss = { navController.popBackStack() })
                    }
                    composable(Routes.DOWNLOADS) {
                        DownloadsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SOURCES) {
                        ThirdPartySourceScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        route = Routes.PLAYLIST,
                        arguments = listOf(
                            navArgument("id") { type = NavType.LongType },
                            navArgument("source") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val id = entry.arguments?.getLong("id") ?: 0L
                        val source = SongSource.fromRaw(entry.arguments?.getString("source"))
                        PlaylistDetailScreen(
                            playlistId = id,
                            source = source,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }

                // 全局漂浮特效（关闭时这个 composable 自己什么都不画，也不订阅动画）。
                // 放在 NavHost 之后、Toast 之前：粒子能盖住页面，但不会盖住提示条。
                BeansFloatingEffectOverlay()

                BeansToastHost(bottomPadding = 150.dp)
            }

            // 检查更新弹窗：只有 check() 真的拿到更高版本、且该版本没被「以后再说」忽略时才存在。
            val pendingUpdate = updateInfo
            if (pendingUpdate != null) {
                BeansAppUpdateDialog(
                    info = pendingUpdate,
                    context = context,
                    onDismiss = { updateInfo = null },
                    onSkip = {
                        UpdateChecker.skip(pendingUpdate.versionCode)
                        updateInfo = null
                    },
                )
            }
        }
    }
}

/**
 * 启动时的「发现新版本」弹窗。
 *
 * - 立即更新 → 用 ACTION_VIEW 把新 APK 的下载地址交给浏览器 / 下载器；
 * - 以后再说 → 记住该版本码，同一版本不再打扰（[UpdateChecker.skip]）；
 * - [UpdateInfo.force] 为 true 时不提供「以后再说」（强制更新）。
 */
@Composable
private fun BeansAppUpdateDialog(
    info: UpdateInfo,
    context: Context,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = BeansTheme.colors
    AlertDialog(
        // 强制更新时点击外部 / 返回键都不关闭，只能走「立即更新」。
        onDismissRequest = { if (!info.force) onDismiss() },
        containerColor = colors.card,
        title = {
            Text(
                text = beansLocalized("发现新版本", "New version available"),
                color = colors.label,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = beansLocalized(
                        "当前版本 ${UpdateChecker.currentVersionName} → ${info.versionName}",
                        "Current ${UpdateChecker.currentVersionName} → ${info.versionName}",
                    ),
                    color = colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (info.notes.isNotBlank()) {
                    Text(text = info.notes, color = colors.comment, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    updateOpenUrl(context, info.url)
                    onDismiss()
                },
            ) {
                Text(
                    text = beansLocalized("立即更新", "Update now"),
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = if (info.force) {
            null
        } else {
            {
                TextButton(onClick = onSkip) {
                    Text(text = beansLocalized("以后再说", "Later"), color = colors.comment)
                }
            }
        },
    )
}

/** 把新 APK 地址交给系统浏览器 / 下载器；失败时只提示一句，不影响继续使用。 */
private fun updateOpenUrl(context: Context, url: String) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
    if (!opened) {
        BeansToastCenter.show(beansLocalized("无法打开下载链接", "Could not open the download link"))
    }
}

/**
 * The five-tab shell with the mini player docked above the custom glass tab bar.
 * Tabs keep their own scroll state; switching does not recreate the screens.
 */
@Composable
private fun BeansRootScaffold(
    navigator: BeansNavigator,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = BeansTheme.colors
    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val visibleOrder by SettingsStore.tabIconOrder.collectAsState()
    // 主页的三个隐藏开关 + 每日推荐旧版样式：在这里读偏好，显式传给发现页。
    val hideHomeNickname by SettingsStore.hideHomeNickname.collectAsState()
    val hideTopProviderStrip by SettingsStore.hideTopProviderStrip.collectAsState()
    val hideHomeRefresh by SettingsStore.hideHomeRefresh.collectAsState()
    val dailyPicksLegacy by SettingsStore.dailyPicksLegacy.collectAsState()
    val hideDonation by SettingsStore.hideDonation.collectAsState()
    val hideSortButtons by SettingsStore.hideSortButtons.collectAsState()
    val discoverFlags = DiscoverHomeFlags(
        nickname = !hideHomeNickname,
        refreshButton = !hideHomeRefresh,
        providerStrip = !hideTopProviderStrip,
        legacyDailyPicks = dailyPicksLegacy,
    )

    // 「底部栏显示」把当前标签关掉时，把这个页面一起切走：否则会停在一个底栏里已经不存在的
    // 标签上（没有任何高亮，用户只能靠猜）。主页（0）永远不会被关掉，所以这里一定有落点。
    val visibleTabs = visibleRootTabIndices(visibleOrder)
    LaunchedEffect(visibleTabs, selected) {
        if (selected !in visibleTabs) onSelect(visibleTabs.first())
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (RootTab.entries[selected]) {
                // 主页与精选是同一个 Discover 界面的两个分区：共用一个状态持有者与一次数据加载。
                RootTab.HOME -> DiscoverScreen(DiscoverSection.HOME, homeFlags = discoverFlags)
                RootTab.FEATURED -> DiscoverScreen(DiscoverSection.FEATURED, homeFlags = discoverFlags)
                RootTab.PLAYLISTS -> LibraryScreen()
                RootTab.PROFILE -> ProfileScreen(
                    hideDonation = hideDonation,
                    hideSortButtons = hideSortButtons,
                )
                RootTab.SEARCH -> SearchScreen()
            }
        }

        MiniPlayerBar(onExpand = navigator.openPlayer)

        BeansTabBar(
            selected = selected,
            onSelect = {
                BeansHaptics.tap()
                onSelect(it)
            },
            uiStyle = uiStyle,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

/** Custom glass tab bar — matches the iOS liquid bar rather than Material's NavigationBar. */
@Composable
private fun BeansTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    uiStyle: com.lulu.music.data.prefs.BeansUIStyle,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    val showLabels by SettingsStore.tabLabelsVisible.collectAsState()
    val iconStyle by SettingsStore.tabIconStyle.collectAsState()
    val visibleOrder by SettingsStore.tabIconOrder.collectAsState()
    val dockRadius by SettingsStore.dockRadius.collectAsState()
    val dockWidth by SettingsStore.dockWidth.collectAsState()
    val dockOffsetX by SettingsStore.dockOffsetX.collectAsState()
    val dockOffsetY by SettingsStore.dockOffsetY.collectAsState()

    // 低系统悬浮底栏的 4 个滑块 → 真实几何（圆润度 / 长度 / X / Y 位置）。
    val dock = beansDockGeometry(dockRadius, dockWidth, dockOffsetX, dockOffsetY)
    val visibleTabs = visibleRootTabIndices(visibleOrder)

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .offset(x = dock.offsetX, y = dock.offsetY),
        contentAlignment = Alignment.Center,
    ) {
        BeansGlass(
            modifier = Modifier.fillMaxWidth().widthIn(max = dock.widthCap),
            shape = RoundedCornerShape(dock.cornerRadius),
            style = uiStyle,
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleTabs.forEach { index ->
                    val tab = RootTab.entries[index]
                    val isSelected = index == selected
                    val tint by animateFloatAsState(if (isSelected) 1f else 0f, label = "tabTint")
                    val source = remember { MutableInteractionSource() }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp)
                            .graphicsLayer { alpha = 0.55f + 0.45f * tint }
                            .then(
                                Modifier.beansTap(source) { onSelect(index) },
                            ),
                    ) {
                        Icon(
                            imageVector = beansTabIcon(tab, iconStyle),
                            contentDescription = tab.zh,
                            tint = if (isSelected) colors.accent else colors.comment,
                            modifier = Modifier.size(23.dp),
                        )
                        if (showLabels) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = com.lulu.music.data.model.beansLocalized(tab.zh, tab.en),
                                color = if (isSelected) colors.accent else colors.comment,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Minimal tap helper (kept local to avoid pulling the press-scale style into the tab bar). */
private fun Modifier.beansTap(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.clickable(
    interactionSource = interaction,
    indication = null,
    onClick = onClick,
)

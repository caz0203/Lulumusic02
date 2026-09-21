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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.update.UpdateChecker
import com.lulu.music.data.update.UpdateConfig
import com.lulu.music.data.update.UpdateInfo
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansToastHost
import com.lulu.music.ui.components.GlassBackdrop
import com.lulu.music.ui.screens.BeansLoginScreen
import com.lulu.music.ui.screens.BeansPlayerScreen
import com.lulu.music.ui.screens.BeansSettingsScreen
import com.lulu.music.ui.screens.DiscoverScreen
import com.lulu.music.ui.screens.DownloadsScreen
import com.lulu.music.ui.screens.LibraryScreen
import com.lulu.music.ui.screens.PlaylistDetailScreen
import com.lulu.music.ui.screens.ProfileScreen
import com.lulu.music.ui.screens.SearchScreen
import com.lulu.music.ui.screens.ThirdPartySourceScreen
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.delay

/** Bottom navigation destinations, mirroring the iOS `RootTab`. */
enum class RootTab(val zh: String, val en: String, val icon: ImageVector) {
    DISCOVER("主页", "Home", Icons.Rounded.Home),
    SEARCH("搜索", "Search", Icons.Rounded.Search),
    LIBRARY("音乐库", "Library", Icons.Rounded.LibraryMusic),
    PROFILE("我的", "Profile", Icons.Rounded.Person),
}

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
    val backgroundImagePath by SettingsStore.backgroundImagePath.collectAsState()
    val wallpaperBlur by SettingsStore.wallpaperBlur.collectAsState()
    val uiStyle by SettingsStore.uiStyle.collectAsState()

    com.lulu.music.ui.theme.BeansTheme(
        themeMode = themeMode,
        accentKey = accentHex.ifBlank { "amber" },
        customAccent = com.lulu.music.ui.theme.parseHexColor(accentHex),
        labelOverride = com.lulu.music.ui.theme.parseHexColor(labelHex),
        commentOverride = com.lulu.music.ui.theme.parseHexColor(commentHex),
    ) {
        val colors = BeansTheme.colors
        CompositionLocalProvider(LocalBeansNavigator provides navigator) {
            GlassBackdrop(
                modifier = Modifier.fillMaxSize(),
                customColor = com.lulu.music.ui.theme.parseHexColor(customBackgroundHex),
                wallpaperPath = backgroundImagePath.ifBlank { null },
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
                        BeansRootScaffold(navigator = navigator)
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
 * The four-tab shell with the mini player docked above the custom glass tab bar.
 * Tabs keep their own scroll state; switching does not recreate the screens.
 */
@Composable
private fun BeansRootScaffold(navigator: BeansNavigator) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val colors = BeansTheme.colors
    val uiStyle by SettingsStore.uiStyle.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (RootTab.entries[selected]) {
                RootTab.DISCOVER -> DiscoverScreen()
                RootTab.SEARCH -> SearchScreen()
                RootTab.LIBRARY -> LibraryScreen()
                RootTab.PROFILE -> ProfileScreen()
            }
        }

        MiniPlayerBar(onExpand = navigator.openPlayer)

        BeansTabBar(
            selected = selected,
            onSelect = {
                BeansHaptics.tap()
                selected = it
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

    Box(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        BeansGlass(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
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
                RootTab.entries.forEachIndexed { index, tab ->
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
                            imageVector = tab.icon,
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

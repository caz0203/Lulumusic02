package com.lulu.music.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lulu.music.BuildConfig
import com.lulu.music.data.auth.AuthStore
import com.lulu.music.data.auth.KugouMusicAuth
import com.lulu.music.data.auth.QQMusicAuth
import com.lulu.music.data.model.Lang
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.AppLanguage
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.stats.SyncState
import com.lulu.music.data.stats.UserStatsStore
import com.lulu.music.data.stats.UserStatsTracker
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.components.BeansCapsule
import com.lulu.music.ui.components.BeansDetent
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansIcons
import com.lulu.music.ui.components.BeansPressable
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.beansCardShadow
import com.lulu.music.ui.components.beansGlass
import com.lulu.music.ui.components.beansPressClickable
import com.lulu.music.ui.components.beansScrollIndicatorsHidden
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// MARK: - 参考图里的固定尺寸
// ---------------------------------------------------------------------------------------------

/** 参考图：`我的` 大标题 34–38sp。 */
private val ProfileTitleSize = 36.sp

/** 参考图：大统计卡片圆角 ~20dp。 */
private val ProfileCardCorner = 20.dp

/** 参考图：功能行卡片圆角 ~18dp。 */
private val ProfileRowCorner = 18.dp

/** 参考图：头像 64dp。 */
private val ProfileAvatarSize = 64.dp

/**
 * 「我的」标签页。
 *
 * 版式按参考图重做：大标题 + 两个圆形图标按钮 → 大统计卡片（头像 / 昵称 / `ID · xxxxxx` /
 * 波形图标 / 分隔线 / 听歌时长 + 播放次数 / 恢复我的ID）→ 功能行（外观与设置 / 我的下载 /
 * 交流群 / 自愿赞助）→ 版本页脚。
 *
 * 与参考图的差异（都是「没有数据源」而不是「没做」）：
 *  - 参考图里的「点我有惊喜」行**没有实现**：用户还没决定它做什么。
 *  - 自定义昵称 / 头像没有对应的设置入口，因此显示为占位样式；点这张卡片仍然进入登录 / 账号中心。
 *  - 波形图标只做静态装饰（参考图如此），不接入均衡器状态。
 *
 * 保留的既有能力：账号卡片（网易云 / QQ / 酷狗的平台状态）、登录入口、下载入口、
 * 设置入口、板块排序（[ProfileSectionSortSheet]）。板块排序仅在当前页面实例内生效 ——
 * iOS 的 `SectionOrderStore` 在 Android 侧没有对应实现，因此这里仍用 `rememberSaveable`。
 */
@Composable
fun ProfileScreen() {
    val colors = BeansTheme.colors
    val navigator = LocalBeansNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val language by SettingsStore.language.collectAsState()
    val themeMode by SettingsStore.themeMode.collectAsState()
    val enabledProvidersRaw by SettingsStore.enabledProviders.collectAsState()

    // 本地统计（ID / 听歌时长 / 播放次数）。`stats` 首次访问会自动 load 并生成 6 位 ID。
    val userStats by UserStatsStore.stats.collectAsState()

    val neteaseLoggedIn by AuthStore.loggedInFlow.collectAsState()
    val neteaseNickname by AuthStore.nicknameFlow.collectAsState()
    val neteaseVip by AuthStore.vipBadgeFlow.collectAsState()
    val qqLoggedIn by QQMusicAuth.loggedInFlow.collectAsState()
    val qqNickname by QQMusicAuth.nicknameFlow.collectAsState()
    val qqVip by QQMusicAuth.vipBadgeFlow.collectAsState()
    val kugouLoggedIn by KugouMusicAuth.loggedInFlow.collectAsState()
    val kugouNickname by KugouMusicAuth.nicknameFlow.collectAsState()
    val kugouVip by KugouMusicAuth.vipBadgeFlow.collectAsState()

    // Keep the shared language mirror in sync so `beansLocalized` resolves correctly.
    LaunchedEffect(language) { Lang.current.value = language }

    // iOS `.task`: refresh the account (and the QQ VIP badge) once when the page first appears.
    // Every network call degrades to "leave the cached state alone" instead of crashing.
    LaunchedEffect(Unit) {
        runCatching { UserStatsStore.load() }
        runCatching { AuthStore.refreshAccount() }
        if (QQMusicAuth.isLoggedIn) {
            runCatching { QQMusicAuth.fetchVIPStatus() }
        }
    }

    var showSectionSort by remember { mutableStateOf(false) }
    var showRestoreId by remember { mutableStateOf(false) }
    var orderRaw by rememberSaveable { mutableStateOf("stats,account,settings") }

    val isEnglish = language == AppLanguage.ENGLISH
    val isNativeClean = uiStyle == BeansUIStyle.NATIVE_CLEAN
    val enabledProviders = enabledProvidersRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val neteaseEnabled = enabledProviders.contains("netease")
    val qqEnabled = enabledProviders.contains("qq")
    val kugouEnabled = enabledProviders.contains("kugou")

    val platformSummary = platformSummaryText(enabledProviders, isEnglish)
    val accountStatusLine = accountStatusLine(
        neteaseEnabled = neteaseEnabled,
        neteaseLoggedIn = neteaseLoggedIn,
        neteaseNickname = neteaseNickname,
        neteaseUid = AuthStore.user?.uid,
        qqEnabled = qqEnabled,
        qqLoggedIn = qqLoggedIn,
        qqNickname = qqNickname,
        kugouEnabled = kugouEnabled,
        kugouLoggedIn = kugouLoggedIn,
        kugouNickname = kugouNickname,
        summary = platformSummary,
    )
    val order = orderRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 860.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .beansScrollIndicatorsHidden()
                .statusBarsPadding()
                .padding(horizontal = if (isNativeClean) 24.dp else 16.dp)
                .padding(top = if (isNativeClean) 14.dp else 10.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- 大标题 + 右上角两个圆形按钮（深色模式 / 设置） ----
            ProfileHeader(
                darkMode = themeMode == BeansThemeMode.DARK,
                onToggleTheme = {
                    BeansHaptics.tap()
                    // SYSTEM 视为「跟随系统」，点一下直接切到明确的 DARK / LIGHT。
                    val next = if (themeMode == BeansThemeMode.DARK) {
                        BeansThemeMode.LIGHT
                    } else {
                        BeansThemeMode.DARK
                    }
                    SettingsStore.setThemeMode(next)
                },
                onSort = {
                    BeansHaptics.tap()
                    showSectionSort = true
                },
                onSettings = {
                    BeansHaptics.tap()
                    navigator.openSettings()
                },
                style = uiStyle,
            )

            // ---- 排序后的板块（默认：统计卡 → 账号 → 外观与设置） ----
            order.forEach { key ->
                when (key) {
                    "stats" -> ProfileStatsCard(
                        style = uiStyle,
                        userId = userStats.userId,
                        listeningSeconds = userStats.listeningSeconds,
                        playCount = userStats.playCount,
                        displayName = neteaseNickname,
                        accountStatusLine = accountStatusLine,
                        avatarURL = AuthStore.user?.avatarURL,
                        neteaseLoggedIn = neteaseLoggedIn,
                        neteaseVip = neteaseVip,
                        onCopyId = {
                            BeansHaptics.tap()
                            copyToClipboard(context, userStats.userId)
                            BeansToastCenter.show(
                                beansLocalized("ID 已复制，请妥善保存", "ID copied — keep it safe"),
                            )
                        },
                        onRestoreId = {
                            BeansHaptics.tap()
                            showRestoreId = true
                        },
                        onSyncTap = {
                            // 双击保护：`Syncing` 时状态行自己会禁用点击，这里再兜一层。
                            if (UserStatsTracker.syncConfigured &&
                                UserStatsTracker.status.value.state != SyncState.Syncing
                            ) {
                                BeansHaptics.tap()
                                scope.launch { runCatching { UserStatsTracker.syncNow() } }
                            }
                        },
                        onOpenAccount = {
                            BeansHaptics.tap()
                            navigator.openLogin()
                        },
                    )

                    "account" -> ProfileAccountCard(
                        isEnglish = isEnglish,
                        accountStatusLine = accountStatusLine,
                        neteaseEnabled = neteaseEnabled,
                        neteaseLoggedIn = neteaseLoggedIn,
                        neteaseNickname = neteaseNickname,
                        neteaseVip = neteaseVip,
                        neteaseUid = AuthStore.user?.uid,
                        qqEnabled = qqEnabled,
                        qqLoggedIn = qqLoggedIn,
                        qqNickname = qqNickname,
                        qqVip = qqVip,
                        kugouEnabled = kugouEnabled,
                        kugouLoggedIn = kugouLoggedIn,
                        kugouNickname = kugouNickname,
                        kugouVip = kugouVip,
                        style = uiStyle,
                        onOpen = {
                            BeansHaptics.tap()
                            navigator.openLogin()
                        },
                    )

                    "settings" -> ProfileSettingsEntry(
                        style = uiStyle,
                        onOpen = {
                            BeansHaptics.tap()
                            navigator.openSettings()
                        },
                    )
                }
            }

            ProfileMenuRow(
                title = beansLocalized("我的下载", "Downloads"),
                subtitle = beansLocalized("已下载到本机，离线也能播放", "Saved on this device for offline play"),
                icon = Icons.Rounded.Download,
                trailing = ProfileMenuTrailing.CHEVRON,
                style = uiStyle,
                onClick = {
                    BeansHaptics.tap()
                    navigator.openDownloads()
                },
            )

            // 交流群与自愿赞助：展示内容由 LuluLinks.kt 配置，未填写时显示占位。
            LuluCommunityCard(style = uiStyle)
            LuluDonationCard(style = uiStyle)

            ProfileVersionFooter()
        }
    }

    if (showSectionSort) {
        ProfileSectionSortSheet(
            order = order,
            onMove = { from, to ->
                val next = order.toMutableList()
                if (from in next.indices && to in next.indices) {
                    val item = next.removeAt(from)
                    next.add(to, item)
                    orderRaw = next.joinToString(",")
                }
            },
            onDismiss = { showSectionSort = false },
            style = uiStyle,
        )
    }

    if (showRestoreId) {
        RestoreIdDialog(
            currentId = userStats.userId,
            syncConfigured = UserStatsTracker.syncConfigured,
            onConfirm = { input ->
                val ok = UserStatsStore.adoptUserId(input)
                if (ok) {
                    BeansHaptics.success()
                    if (UserStatsTracker.syncConfigured) {
                        BeansToastCenter.show(
                            beansLocalized(
                                "已切换到 ID $input，正在拉取云端统计",
                                "Switched to ID $input; pulling cloud stats",
                            ),
                        )
                        // `adoptUserId` 只做本地换号；真正的「拉回云端记录」由 tracker 同步完成。
                        scope.launch { runCatching { UserStatsTracker.syncNow() } }
                    } else {
                        // 未配置 Gitee 仓库：换号是合法的本地操作，但**什么都没下载**，必须说清楚。
                        BeansToastCenter.show(
                            beansLocalized(
                                "已切换到 ID $input；未配置 Gitee 仓库，没有下载任何云端数据",
                                "Switched to ID $input — no Gitee repo configured, nothing was downloaded",
                            ),
                        )
                    }
                } else {
                    BeansToastCenter.show(
                        beansLocalized("ID 无效，请输入 6 位数字", "Invalid ID — enter 6 digits"),
                    )
                }
                ok
            },
            onDismiss = { showRestoreId = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 顶部标题
// ---------------------------------------------------------------------------------------------

/** 大标题 `我的` + 右上角「深色模式」与「设置」两个圆形按钮。 */
@Composable
private fun ProfileHeader(
    darkMode: Boolean,
    onToggleTheme: () -> Unit,
    onSort: () -> Unit,
    onSettings: () -> Unit,
    style: BeansUIStyle,
) {
    val colors = BeansTheme.colors

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = beansLocalized("我的", "Profile"),
                color = colors.label,
                fontSize = ProfileTitleSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BeansGlassIconButton(
                    icon = if (darkMode) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    onClick = onToggleTheme,
                    contentDescription = beansLocalized("切换深色模式", "Toggle dark mode"),
                    size = 40.dp,
                    forceLiquid = true,
                    style = style,
                )
                BeansGlassIconButton(
                    systemName = "gearshape",
                    onClick = onSettings,
                    contentDescription = beansLocalized("设置", "Settings"),
                    size = 40.dp,
                    forceLiquid = true,
                    style = style,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 板块排序入口（原来的 `arrow.up.arrow.down` 按钮）：只在需要时出现，弱化成文字按钮。
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onSort() }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.SwapVert,
                contentDescription = null,
                tint = colors.comment,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = beansLocalized("板块排序", "Section order"),
                color = colors.comment,
                fontSize = 12.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 统计卡片（头像 / 昵称 / ID / 听歌时长 / 播放次数 / 恢复我的ID）
// ---------------------------------------------------------------------------------------------

/**
 * 参考图里那张大卡片：白 / surface 底、20dp 圆角。
 *
 * 上半部分（头像 + 昵称 + ID + 波形）整块可点，进入登录 / 账号中心，
 * 保证用户的账号信息仍然可达；下半部分是统计数据与「恢复我的ID」。
 *
 * `ID · xxxxxx` 下方多了一行极小的云端同步状态（[CloudSyncStatusLine]）：
 * 未配置 Gitee 仓库时明确说出「未启用」，而不是让用户以为同步坏了。
 */
@Composable
private fun ProfileStatsCard(
    style: BeansUIStyle,
    userId: String,
    listeningSeconds: Long,
    playCount: Int,
    displayName: String,
    accountStatusLine: String,
    avatarURL: String?,
    neteaseLoggedIn: Boolean,
    neteaseVip: String?,
    onCopyId: () -> Unit,
    onRestoreId: () -> Unit,
    onSyncTap: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(ProfileCardCorner)
    val rowInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .beansCardShadow(radius = 10.dp, y = 4.dp, shape = shape)
            .beansGlass(shape = shape, style = style)
            .clip(shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- 头像 + 昵称 + ID + 波形 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .beansPressClickable(
                    interactionSource = rowInteraction,
                    scale = 0.98f,
                    pressedBrightness = 0f,
                    onClick = onOpenAccount,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.size(ProfileAvatarSize)) {
                // 头像占位：灰色圆 + 人形图标（有真实头像时显示真实头像）
                Box(
                    modifier = Modifier
                        .size(ProfileAvatarSize)
                        .clip(CircleShape)
                        .background(colors.glassFill),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!avatarURL.isNullOrBlank()) {
                        AsyncImage(
                            model = avatarURL,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = colors.comment,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                // 头像右下角的小红点（参考图里的消息 / 状态徽标）
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(colors.card),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 昵称：没有昵称时按参考图显示 `自定义昵称` 占位（用 comment 色表达「未设置」）
                    val hasNickname = displayName.isNotBlank()
                    Text(
                        text = displayName.ifBlank {
                            beansLocalized("自定义昵称", "Custom nickname")
                        },
                        color = if (hasNickname) colors.label else colors.comment,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (neteaseLoggedIn && !neteaseVip.isNullOrBlank()) {
                        BeansVIPBadge(text = neteaseVip)
                    }
                }

                // `ID · xxxxxx` + 复制按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = beansLocalized("ID · $userId", "ID · $userId"),
                        color = colors.comment,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                    CopyIdButton(onClick = onCopyId)
                }

                // 云端同步状态：紧贴 ID 行下方的一行小字（11sp，不抢视觉）。
                CloudSyncStatusLine(onRetry = onSyncTap)

                if (accountStatusLine.isNotBlank()) {
                    Text(
                        text = accountStatusLine,
                        color = colors.comment.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 参考图右侧的波形图标（纯装饰）
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = colors.label.copy(alpha = 0.35f),
                modifier = Modifier.size(26.dp),
            )
        }

        // ---- 分隔线 ----
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.label.copy(alpha = 0.08f))
        )

        // ---- 两项统计：听歌时长 / 播放次数 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            ProfileStatItem(
                icon = Icons.Rounded.Timer,
                label = beansLocalized("听歌时长", "Listening time"),
                value = beansListeningTimeText(listeningSeconds),
                modifier = Modifier.weight(1f),
            )
            ProfileStatItem(
                icon = Icons.Rounded.GraphicEq,
                label = beansLocalized("播放次数", "Play count"),
                value = beansLocalized("$playCount 次", "$playCount plays"),
                modifier = Modifier.weight(1f),
            )
        }

        // ---- 「恢复我的ID」：低存在感的文字按钮 ----
        RestoreIdAffordance(onClick = onRestoreId)
    }
}

/** 复制 ID 的小按钮（图标 + 「复制」，足够显眼但不抢视觉）。 */
@Composable
private fun CopyIdButton(onClick: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.accent.copy(alpha = 0.12f))
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = beansLocalized("复制", "Copy"),
            color = colors.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 云端同步状态行：11sp 的小字，紧贴 `ID · xxxxxx` 下方，不抢卡片的视觉重心。
 *
 * 五种状态：
 * - 未配置 Gitee 仓库 → 琥珀色「云端同步未启用」+ 灰色补充说明（这是「还没配置」，不是「坏了」）；
 * - 已配置 + Idle → 灰色「云端同步已开启」；
 * - Syncing → 灰色「正在同步…」；
 * - Success → 绿色「已同步 · N 分钟前」（页面停留时每 30 秒自己走时）；
 * - Failed → 红色「同步失败：`原因`」，单行截断。
 *
 * 已配置时整行可点，点一下重新同步；`Syncing` 期间 `enabled = false`，天然防双击。
 */
@Composable
private fun CloudSyncStatusLine(onRetry: () -> Unit) {
    val colors = BeansTheme.colors
    val configured = UserStatsTracker.syncConfigured
    val status by UserStatsTracker.status.collectAsState()
    val interaction = remember { MutableInteractionSource() }

    // 「已同步 · N 分钟前」需要在用户停留在本页时继续走时；只在成功状态下开一个 30 秒的轻量计时器。
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.state, status.atMillis) {
        if (status.state != SyncState.Success) return@LaunchedEffect
        while (true) {
            delay(30_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val syncing = status.state == SyncState.Syncing
    val primary: String
    val hint: String?
    val color: Color
    when {
        !configured || status.state == SyncState.NotConfigured -> {
            primary = beansLocalized("云端同步未启用", "Cloud sync is off")
            hint = beansLocalized(
                "（未配置 Gitee 仓库，统计只保存在本机）",
                "(no Gitee repo configured — stats stay on this device)",
            )
            color = syncWarningColor(colors.isDark)
        }
        syncing -> {
            primary = beansLocalized("正在同步…", "Syncing…")
            hint = null
            color = colors.comment
        }
        status.state == SyncState.Success -> {
            primary = beansLocalized(
                "已同步 · ${beansSyncRelativeTime(status.atMillis, nowMillis)}",
                "Synced · ${beansSyncRelativeTime(status.atMillis, nowMillis)}",
            )
            hint = null
            color = colors.sage
        }
        status.state == SyncState.Failed -> {
            val reason = status.message.ifBlank {
                beansLocalized("未知错误", "unknown error")
            }
            primary = beansLocalized("同步失败：$reason", "Sync failed: $reason")
            hint = null
            color = syncFailureColor(colors.isDark)
        }
        else -> {
            primary = beansLocalized("云端同步已开启", "Cloud sync is on")
            hint = null
            color = colors.comment
        }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .beansPressClickable(
                interactionSource = interaction,
                onClick = onRetry,
                enabled = configured && !syncing,
                scale = 0.98f,
                pressedBrightness = 0f,
            )
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = primary,
            color = color,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hint != null) {
            // 空间不够时优先牺牲补充说明（主文案永远完整）。
            Text(
                text = hint,
                color = colors.comment.copy(alpha = 0.8f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** 单项统计：红色小图标 + 灰色 12sp 标签 + 深色 15sp 半粗值。 */
@Composable
private fun ProfileStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label,
                color = colors.comment,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Text(
            text = value,
            color = colors.label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** 「恢复我的ID」：卡片内部右对齐的低存在感文字按钮。 */
@Composable
private fun RestoreIdAffordance(onClick: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    // `Modifier.align` 只在 BoxScope 里可用，因此这里套一层 Box 来做右对齐。
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null) { onClick() }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = beansLocalized("恢复我的ID", "Restore my ID"),
                color = colors.comment,
                fontSize = 12.sp,
            )
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.comment,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * 恢复 ID 对话框：输入 6 位数字 → `UserStatsStore.adoptUserId(id)`。
 *
 * [syncConfigured] 为 false 时（Gitee 仓库没配）对话框会明说「拉不到云端数据」，
 * 确认按钮也改成「仍然切换」：本地换号是合法操作，但绝不是「从云端恢复」。
 *
 * 返回 true 表示接受（调用方已 toast），false 表示格式非法，对话框保持打开并给出错误。
 */
@Composable
private fun RestoreIdDialog(
    currentId: String,
    syncConfigured: Boolean,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val colors = BeansTheme.colors
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        titleContentColor = colors.label,
        textContentColor = colors.comment,
        title = {
            Text(
                text = beansLocalized("恢复我的ID", "Restore my ID"),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (syncConfigured) {
                    Text(
                        text = beansLocalized(
                            "输入重装前保存的 6 位 ID，即可把云端统计恢复到本机。",
                            "Enter the 6-digit ID you saved before reinstalling to restore your cloud stats.",
                        ),
                        fontSize = 13.sp,
                    )
                } else {
                    // 未配置 Gitee 仓库：先把话说清楚，免得用户以为「恢复失败」。
                    Text(
                        text = beansLocalized(
                            "当前未配置 Gitee 仓库，无法从云端拉取统计。",
                            "No Gitee repo is configured, so cloud stats cannot be pulled.",
                        ),
                        color = syncWarningColor(colors.isDark),
                        fontSize = 13.sp,
                    )
                    Text(
                        text = beansLocalized(
                            "继续只会把本机统计改挂到这个 ID 名下，不会下载任何云端数据。",
                            "Continuing only re-points your local stats at this ID; no cloud data is downloaded.",
                        ),
                        fontSize = 13.sp,
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { raw ->
                        input = raw.filter { it.isDigit() }.take(6)
                        error = null
                    },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = beansLocalized("6 位数字", "6 digits"),
                            color = colors.comment,
                            fontSize = 14.sp,
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (syncConfigured) {
                        beansLocalized(
                            "当前 ID：$currentId（换号后本机计数从零开始，随后与云端合并）",
                            "Current ID: $currentId (counters restart and then merge with the cloud)",
                        )
                    } else {
                        beansLocalized(
                            "当前 ID：$currentId（换号后本机计数从零开始；云端同步未启用，暂时不会与云端合并）",
                            "Current ID: $currentId (counters restart; cloud sync is off, so nothing merges yet)",
                        )
                    },
                    color = colors.comment.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                )
                if (error != null) {
                    Text(text = error ?: "", color = colors.accent, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = input.trim()
                if (id.length != 6 || !id.all { it.isDigit() }) {
                    error = beansLocalized("请输入 6 位数字", "Enter exactly 6 digits")
                } else if (!onConfirm(id)) {
                    error = beansLocalized("恢复失败，请稍后重试", "Restore failed, try again later")
                }
            }) {
                Text(
                    text = if (syncConfigured) {
                        beansLocalized("恢复", "Restore")
                    } else {
                        // 说清楚这一步只是本地换号，不是「从云端恢复」。
                        beansLocalized("仍然切换", "Switch anyway")
                    },
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = beansLocalized("取消", "Cancel"), color = colors.comment)
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 账号卡片
// ---------------------------------------------------------------------------------------------

/** Port of the iOS `userCard` + `platformStatusRow` + `platformChip`. */
@Composable
private fun ProfileAccountCard(
    isEnglish: Boolean,
    accountStatusLine: String,
    neteaseEnabled: Boolean,
    neteaseLoggedIn: Boolean,
    neteaseNickname: String,
    neteaseVip: String?,
    neteaseUid: Long?,
    qqEnabled: Boolean,
    qqLoggedIn: Boolean,
    qqNickname: String,
    qqVip: String?,
    kugouEnabled: Boolean,
    kugouLoggedIn: Boolean,
    kugouNickname: String,
    kugouVip: String?,
    style: BeansUIStyle,
    onOpen: () -> Unit,
) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(ProfileCardCorner)

    BeansPressable(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        scale = 0.97f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .beansCardShadow(radius = 10.dp, y = 4.dp, shape = shape)
                .beansGlass(shape = shape, style = style)
                .clip(shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 头像：主题渐变描边环（Android 用 glassFill 底 + 圆形裁剪还原）
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(colors.glassFill),
                    contentAlignment = Alignment.Center,
                ) {
                    val avatar = AuthStore.user?.avatarURL
                    if (!avatar.isNullOrBlank()) {
                        AsyncImage(
                            model = avatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = colors.comment,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = accountTitle(
                                neteaseLoggedIn = neteaseLoggedIn,
                                nickname = neteaseNickname,
                            ),
                            color = colors.label,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (neteaseLoggedIn && !neteaseVip.isNullOrBlank()) {
                            BeansVIPBadge(text = neteaseVip)
                        }
                    }
                    Text(
                        text = accountStatusLine,
                        color = colors.comment,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.comment.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }

            val hasVisibleLogin = (neteaseEnabled && neteaseLoggedIn) ||
                (qqEnabled && qqLoggedIn) ||
                (kugouEnabled && kugouLoggedIn)
            if (hasVisibleLogin) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (neteaseEnabled && neteaseLoggedIn) {
                        PlatformChip(
                            monogram = "网",
                            brandColor = Color(0xFFC20C0C),
                            name = beansLocalized("网易云音乐", "NetEase Cloud Music"),
                            status = neteaseNickname.ifBlank {
                                neteaseUid?.let { "UID $it" } ?: beansLocalized("已登录", "Signed in")
                            },
                            badge = neteaseVip,
                        )
                    }
                    if (qqEnabled && qqLoggedIn) {
                        PlatformChip(
                            monogram = "Q",
                            brandColor = Color(0xFF31C27C),
                            name = beansLocalized("QQ 音乐", "QQ Music"),
                            status = qqNickname.ifBlank { beansLocalized("已登录", "Signed in") },
                            badge = qqVip,
                        )
                    }
                    if (kugouEnabled && kugouLoggedIn) {
                        PlatformChip(
                            monogram = "酷",
                            brandColor = Color(0xFF1478FF),
                            name = beansLocalized("酷狗音乐", "Kugou Music"),
                            status = kugouNickname.ifBlank { beansLocalized("已登录", "Signed in") },
                            badge = kugouVip,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformChip(
    monogram: String,
    brandColor: Color,
    name: String,
    status: String,
    badge: String?,
) {
    val colors = BeansTheme.colors
    BeansCapsule(
        contentPadding = PaddingValues(
            horizontal = 10.dp,
            vertical = 6.dp,
        )
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brandColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = monogram,
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Text(
            text = name,
            color = colors.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = status,
            color = colors.comment,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!badge.isNullOrBlank()) {
            BeansVIPBadge(text = badge)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 外观 / 设置入口
// ---------------------------------------------------------------------------------------------

/**
 * 外观与设置入口。`gearshape` 按钮已经在标题栏里，这里再放一张卡片保证这页不是一个死胡同，
 * 也保留改版前就把设置放在卡片列表里的习惯。
 */
@Composable
private fun ProfileSettingsEntry(
    style: BeansUIStyle,
    onOpen: () -> Unit,
) {
    ProfileMenuRow(
        title = beansLocalized("外观与设置", "Appearance & Settings"),
        subtitle = beansLocalized(
            "主题模式、播放、均衡器、歌词与平台",
            "Theme, playback, equalizer, lyrics and platforms",
        ),
        icon = BeansIcons.of("paintbrush", Icons.Rounded.Person),
        trailing = ProfileMenuTrailing.CHEVRON,
        style = style,
        onClick = onOpen,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 通用功能行
// ---------------------------------------------------------------------------------------------

/** 功能行的尾部指示符。 */
private enum class ProfileMenuTrailing { CHEVRON, EXTERNAL }

/** 参考图里的功能行卡片：圆角 18dp、整行可点、左侧图标 + 文案 + 右侧指示符。 */
@Composable
private fun ProfileMenuRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    trailing: ProfileMenuTrailing,
    style: BeansUIStyle,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(ProfileRowCorner)

    BeansPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        scale = 0.98f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .beansCardShadow(radius = 8.dp, y = 3.dp, shape = shape)
                .beansGlass(shape = shape, style = style)
                .clip(shape)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = colors.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = colors.comment,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = when (trailing) {
                    ProfileMenuTrailing.CHEVRON -> Icons.Rounded.ChevronRight
                    ProfileMenuTrailing.EXTERNAL -> Icons.AutoMirrored.Rounded.OpenInNew
                },
                contentDescription = null,
                tint = colors.comment.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 版本页脚
// ---------------------------------------------------------------------------------------------

/**
 * 版本页脚：`LuluMusic · <版本>` 居中灰字（参考图如此）。
 *
 * ID 只在统计卡片里出现一次（不在这里重复）：如果用户真的丢了 ID，
 * 卡片里的「恢复我的ID」旁边就能看到自己当前的 ID，再复制一次即可。
 */
@Composable
private fun ProfileVersionFooter() {
    val colors = BeansTheme.colors
    Text(
        text = "LuluMusic · ${BuildConfig.VERSION_NAME}",
        color = colors.comment.copy(alpha = 0.7f),
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 板块排序（对应 iOS SectionOrderSheet，仅本页实例内生效）
// ---------------------------------------------------------------------------------------------

@Composable
private fun ProfileSectionSortSheet(
    order: List<String>,
    onMove: (from: Int, to: Int) -> Unit,
    onDismiss: () -> Unit,
    style: BeansUIStyle,
) {
    val colors = BeansTheme.colors
    BeansBottomSheet(
        onDismissRequest = onDismiss,
        detents = listOf(BeansDetent.Height(340.dp)),
        style = style,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = beansLocalized("我的板块排序", "Profile section order"),
                color = colors.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            order.forEachIndexed { index, key ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .beansGlass(shape = RoundedCornerShape(14.dp), style = style)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = sectionTitle(key),
                        color = colors.label,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    BeansGlassIconButton(
                        icon = Icons.Rounded.KeyboardArrowUp,
                        onClick = { if (index > 0) onMove(index, index - 1) },
                        size = 34.dp,
                        forceLiquid = true,
                        style = style,
                    )
                    BeansGlassIconButton(
                        icon = Icons.Rounded.KeyboardArrowDown,
                        onClick = { if (index < order.lastIndex) onMove(index, index + 1) },
                        size = 34.dp,
                        forceLiquid = true,
                        style = style,
                    )
                }
            }
            BeansGlassButton(
                title = beansLocalized("完成", "Done"),
                onClick = onDismiss,
                prominent = true,
                style = style,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun sectionTitle(key: String): String = when (key) {
    "stats" -> beansLocalized("听歌统计", "Listening stats")
    "account" -> beansLocalized("账号", "Account")
    "settings" -> beansLocalized("外观与设置", "Appearance & Settings")
    else -> key
}

// ---------------------------------------------------------------------------------------------
// MARK: - 文案 / 工具
// ---------------------------------------------------------------------------------------------

/**
 * 听歌时长格式化：`2 小时 24 分钟` / `45 分钟` / `12 秒`。
 *
 * 与参考图一致：小时 / 分钟都只在非零时出现，秒只在不足 1 分钟时兜底，
 * 这样 `0` 会显示成 `0 秒` 而不是空字符串。
 */
internal fun beansListeningTimeText(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> beansLocalized("$hours 小时 $minutes 分钟", "$hours h $minutes min")
        hours > 0 -> beansLocalized("$hours 小时", "$hours h")
        minutes > 0 -> beansLocalized("$minutes 分钟", "$minutes min")
        else -> beansLocalized("$total 秒", "$total s")
    }
}

/**
 * 云端同步状态用的相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前。
 *
 * [atMillis] 为 0（从未同步过）或时间在未来时都按「刚刚」处理，绝不出现负数。
 */
internal fun beansSyncRelativeTime(
    atMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    val elapsed = (nowMillis - atMillis).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    val hours = elapsed / 3_600_000L
    val days = elapsed / 86_400_000L
    return when {
        minutes < 1L -> beansLocalized("刚刚", "just now")
        hours < 1L -> beansLocalized("$minutes 分钟前", "$minutes min ago")
        days < 1L -> beansLocalized("$hours 小时前", "$hours h ago")
        else -> beansLocalized("$days 天前", "$days d ago")
    }
}

/**
 * 「云端同步未启用」的琥珀色。
 *
 * 浅色模式下压暗（白底对比度约 5.9:1）、深色模式下提亮（卡片底约 9:1），两种模式都清晰可读。
 */
private fun syncWarningColor(isDark: Boolean): Color =
    if (isDark) Color(0xFFF0B45A) else Color(0xFF8A5A00)

/**
 * 同步失败的红色。
 *
 * 不用纯 `Color.Red`：浅色模式下偏刺眼、深色模式下偏暗；这里两档都保持在 5:1 以上。
 */
private fun syncFailureColor(isDark: Boolean): Color =
    if (isDark) Color(0xFFFF6B6B) else Color(0xFFC62828)

/** 把 ID 放进系统剪贴板（失败静默：toast 由调用方负责）。 */
private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("LuluMusic ID", text))
    }
}

private fun platformSummaryText(enabled: List<String>, isEnglish: Boolean): String {
    if (enabled.isEmpty()) return beansLocalized("暂无平台", "No platform")
    return enabled.joinToString(" / ") { raw ->
        when (raw) {
            "netease" -> if (isEnglish) "NetEase Cloud Music" else "网易云音乐"
            "qq" -> if (isEnglish) "QQ Music" else "QQ 音乐"
            "kugou" -> if (isEnglish) "Kugou Music" else "酷狗音乐"
            else -> raw
        }
    }
}

/** Port of the iOS `accountStatusLine`. */
private fun accountStatusLine(
    neteaseEnabled: Boolean,
    neteaseLoggedIn: Boolean,
    neteaseNickname: String,
    neteaseUid: Long?,
    qqEnabled: Boolean,
    qqLoggedIn: Boolean,
    qqNickname: String,
    kugouEnabled: Boolean,
    kugouLoggedIn: Boolean,
    kugouNickname: String,
    summary: String,
): String {
    val parts = mutableListOf<String>()
    if (neteaseEnabled && neteaseLoggedIn) {
        parts += if (neteaseNickname.isNotBlank()) {
            "${beansLocalized("网易云音乐", "NetEase Cloud Music")} $neteaseNickname"
        } else {
            beansLocalized(
                "网易云音乐 UID ${neteaseUid ?: 0}",
                "NetEase Cloud Music UID ${neteaseUid ?: 0}",
            )
        }
    }
    if (qqEnabled && qqLoggedIn) {
        parts += qqNickname.ifBlank { beansLocalized("QQ 已登录", "QQ Music Logged In") }
    }
    if (kugouEnabled && kugouLoggedIn) {
        parts += kugouNickname.ifBlank { beansLocalized("酷狗已登录", "Kugou Music Logged In") }
    }
    if (parts.isEmpty()) {
        return beansLocalized(
            "登录后可同步 $summary 歌单",
            "Sign in to sync $summary playlists",
        )
    }
    return parts.joinToString(" · ")
}

/**
 * 卡片上显示的昵称：有昵称时直接展示，没有时退回参考图里的 `自定义昵称` 占位样式。
 *
 * 返回的是**已本地化的显示文案**；[ProfileStatsCard] 用「原始昵称是否为空」决定
 * 用 `colors.comment`（占位）还是 `colors.label`（真实昵称）。
 */
private fun accountTitle(neteaseLoggedIn: Boolean, nickname: String): String = when {
    nickname.isNotBlank() -> nickname
    neteaseLoggedIn -> beansLocalized("自定义昵称", "Custom nickname")
    else -> beansLocalized("免登录 · 点击登录", "Guest · Tap to Sign In")
}

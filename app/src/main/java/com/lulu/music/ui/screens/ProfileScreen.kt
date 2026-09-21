package com.lulu.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.components.BeansCapsule
import com.lulu.music.ui.components.BeansDetent
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansIcons
import com.lulu.music.ui.components.BeansPressable
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.beansCardShadow
import com.lulu.music.ui.components.beansGlass
import com.lulu.music.ui.components.beansScrollIndicatorsHidden
import com.lulu.music.ui.theme.BeansTheme

/**
 * 「我的」标签页 — port of the **modified** iOS `ProfileView`.
 *
 * The iOS source this is ported from has already had its community-group card, its donation card and
 * its entire self-update entry removed; this screen therefore contains only: the header (title +
 * section-sort + settings gear), the account card with the signed-in platforms, the
 * appearance/settings entry and the version footer. None of those removed features are re-added here,
 * and no invite link, payment URL or download/update action exists in this file.
 *
 * Layout differences from iOS (documented, not silent):
 *  - `SectionOrderStore` has no Android counterpart, so the section order chosen in the sort sheet is
 *    kept for the current screen instance only (`rememberSaveable`), not persisted.
 *  - iOS renders brand artwork (`BrandNetease` / `BrandQQ` / `BrandKugou`); the Android port has no
 *    such drawables, so each platform chip carries a small tinted monogram instead.
 */
@Composable
fun ProfileScreen() {
    val colors = BeansTheme.colors
    val navigator = LocalBeansNavigator.current

    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val language by SettingsStore.language.collectAsState()
    val enabledProvidersRaw by SettingsStore.enabledProviders.collectAsState()

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
        runCatching { AuthStore.refreshAccount() }
        if (QQMusicAuth.isLoggedIn) {
            runCatching { QQMusicAuth.fetchVIPStatus() }
        }
    }

    var showSectionSort by remember { mutableStateOf(false) }
    var orderRaw by rememberSaveable { mutableStateOf("account,settings") }

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
                .padding(top = if (isNativeClean) 14.dp else 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(if (isNativeClean) 26.dp else 22.dp),
        ) {
            ProfileHeader(
                subtitle = beansLocalized(
                    "$platformSummary 账号与外观设置",
                    "$platformSummary account and appearance settings",
                ),
                statusLine = accountStatusLine,
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

            order.forEach { key ->
                when (key) {
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

            ProfileDownloadsEntry(
                style = uiStyle,
                onOpen = {
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
}

// ---------------------------------------------------------------------------------------------
// MARK: - 顶部标题
// ---------------------------------------------------------------------------------------------

/** Port of the iOS `header` / `appleHeader` (title + sort + settings gear). */
@Composable
private fun ProfileHeader(
    subtitle: String,
    statusLine: String,
    onSort: () -> Unit,
    onSettings: () -> Unit,
    style: BeansUIStyle,
) {
    val colors = BeansTheme.colors
    val isNativeClean = style == BeansUIStyle.NATIVE_CLEAN
    val title = beansLocalized("我的", "Profile")

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = colors.label,
                fontSize = if (isNativeClean) 38.sp else 30.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BeansGlassIconButton(
                    systemName = "arrow.up.arrow.down",
                    onClick = onSort,
                    size = 40.dp,
                    style = style,
                )
                BeansGlassIconButton(
                    systemName = "gearshape",
                    onClick = onSettings,
                    size = 40.dp,
                    forceLiquid = true,
                    style = style,
                )
            }
        }

        if (isNativeClean) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = statusLine,
                color = colors.comment,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.label.copy(alpha = 0.10f))
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = colors.comment,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
    val shape = RoundedCornerShape(24.dp)

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
                        .size(64.dp)
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
                            modifier = Modifier.size(26.dp),
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
                                isEnglish = isEnglish,
                                neteaseLoggedIn = neteaseLoggedIn,
                                nickname = neteaseNickname,
                            ),
                            color = colors.label,
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
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
 * The single appearance & settings entry that remains on the page after the community-group,
 * donation and self-update sections were removed from the iOS source (iOS keeps this destination
 * behind the header gear button; on Android the same destination is also reachable as a card so the
 * page is not a dead end).
 */
@Composable
private fun ProfileSettingsEntry(
    style: BeansUIStyle,
    onOpen: () -> Unit,
) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(18.dp)

    BeansPressable(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        scale = 0.97f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .beansGlass(shape = shape, style = style)
                .clip(shape)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.glassFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = BeansIcons.of("paintbrush", Icons.Rounded.Person),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = beansLocalized("外观与设置", "Appearance & Settings"),
                    color = colors.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = beansLocalized(
                        "主题模式、播放、均衡器、歌词与平台",
                        "Theme, playback, equalizer, lyrics and platforms",
                    ),
                    color = colors.comment,
                    fontSize = 11.sp,
                    maxLines = 2,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
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

/** Port of the iOS `profileVersionFooter` (`LuluMusic · <CFBundleShortVersionString>`). */
@Composable
private fun ProfileVersionFooter() {
    val colors = BeansTheme.colors
    Text(
        text = "LuluMusic · ${BuildConfig.VERSION_NAME}",
        color = colors.comment.copy(alpha = 0.7f),
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
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
        detents = listOf(BeansDetent.Height(300.dp)),
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
    "account" -> beansLocalized("账号", "Account")
    "settings" -> beansLocalized("外观与设置", "Appearance & Settings")
    else -> key
}

// ---------------------------------------------------------------------------------------------
// MARK: - 文案
// ---------------------------------------------------------------------------------------------

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

private fun accountTitle(isEnglish: Boolean, neteaseLoggedIn: Boolean, nickname: String): String = when {
    nickname.isNotBlank() -> nickname
    neteaseLoggedIn -> beansLocalized("网易云音乐已登录", "NetEase Cloud Music Logged In")
    else -> beansLocalized("免登录 · 点击登录", "Guest · Tap to Sign In")
}

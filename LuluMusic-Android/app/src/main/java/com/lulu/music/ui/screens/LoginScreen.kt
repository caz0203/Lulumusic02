package com.lulu.music.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lulu.music.data.api.KugouMusicApi
import com.lulu.music.data.api.KugouQRState
import com.lulu.music.data.api.NetEaseApi
import com.lulu.music.data.auth.AuthStore
import com.lulu.music.data.auth.KugouMusicAuth
import com.lulu.music.data.auth.QQMusicAuth
import com.lulu.music.data.model.Lang
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.ui.components.BeansCapsule
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansIcons
import com.lulu.music.ui.components.BeansPressable
import com.lulu.music.ui.components.BeansQRCodeView
import com.lulu.music.ui.components.BeansSectionHeader
import com.lulu.music.ui.components.BeansSurface
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.beansGlass
import com.lulu.music.ui.components.beansPressClickable
import com.lulu.music.ui.components.beansScrollIndicatorsHidden
import com.lulu.music.ui.theme.BeansTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号登录 — port of the iOS `AccountHubSheet` plus the login flows it opens
 * (`LoginView` + `NetEaseWebLoginSheet`, `QQLoginSheet` + `QQWebLoginSheet`, `KugouLoginSheet`).
 *
 * Three platforms, each with login / logout, plus:
 *  - 网易云音乐: in-app QR login (key → `qrLoginURL` → rendered locally with ZXing) and the in-app
 *    web login panel (WebView + cookie import).
 *  - QQ 音乐: in-app QR login (`fetchQRCode()` PNG bytes → bitmap) and the manual "paste Cookie"
 *    fallback; `fetchVIPStatus()` and `FavoritesStore.syncQQFromCloud()` run after a success.
 *  - 酷狗音乐: in-app QR login (`KugouMusicApi.qrKey()` / `pollQR()`).
 *
 * Every network call is wrapped in `runCatching`, so a failure shows a message instead of crashing.
 *
 * Documented deviations: iOS' `WKWebView` panels are replaced by an Android `WebView` +
 * `CookieManager` (NetEase only — QQ keeps the QR + paste-cookie paths); iOS' `Timer`-driven polling
 * becomes a coroutine loop that stops as soon as the panel leaves composition.
 */
@Composable
fun BeansLoginScreen(onDismiss: () -> Unit) {
    val colors = BeansTheme.colors
    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val language by SettingsStore.language.collectAsState()

    LaunchedEffect(language) { Lang.current.value = language }

    var flow by remember { mutableStateOf(LoginFlow.HUB) }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BeansGlassIconButton(
                systemName = if (flow == LoginFlow.HUB) "xmark" else "chevron.left",
                onClick = {
                    BeansHaptics.tap()
                    if (flow == LoginFlow.HUB) onDismiss() else flow = LoginFlow.HUB
                },
                size = 40.dp,
                forceLiquid = true,
                style = uiStyle,
            )
            Text(
                text = loginFlowTitle(flow),
                color = colors.label,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (flow == LoginFlow.HUB) {
                BeansGlassButton(
                    title = beansLocalized("完成", "Done"),
                    onClick = {
                        BeansHaptics.tap()
                        onDismiss()
                    },
                    prominent = true,
                    style = uiStyle,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .beansScrollIndicatorsHidden()
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (flow) {
                    LoginFlow.HUB -> AccountHub(style = uiStyle, onOpen = { flow = it })
                    LoginFlow.NETEASE_QR -> NetEaseQrPanel(
                        style = uiStyle,
                        onSuccess = { flow = LoginFlow.HUB },
                        onWebLogin = { flow = LoginFlow.NETEASE_WEB },
                    )

                    LoginFlow.NETEASE_WEB -> NetEaseWebPanel(
                        style = uiStyle,
                        onSuccess = { flow = LoginFlow.HUB },
                    )

                    LoginFlow.QQ -> QQPanel(
                        style = uiStyle,
                        onSuccess = { flow = LoginFlow.HUB },
                    )

                    LoginFlow.KUGOU -> KugouQrPanel(
                        style = uiStyle,
                        onSuccess = { flow = LoginFlow.HUB },
                    )
                }
            }
        }
    }
}

private enum class LoginFlow { HUB, NETEASE_QR, NETEASE_WEB, QQ, KUGOU }

/** Same state machine as the iOS `QRStatus`. */
private sealed interface LoginQRStatus {
    data object Loading : LoginQRStatus
    data object Waiting : LoginQRStatus
    data object Scanned : LoginQRStatus
    data object Success : LoginQRStatus
    data object Expired : LoginQRStatus
    data class Error(val message: String) : LoginQRStatus
}

private fun loginFlowTitle(flow: LoginFlow): String = when (flow) {
    LoginFlow.HUB -> beansLocalized("账号登录", "Sign in")
    LoginFlow.NETEASE_QR -> beansLocalized("登录网易云音乐", "NetEase Cloud Music")
    LoginFlow.NETEASE_WEB -> beansLocalized("网页登录", "Web sign-in")
    LoginFlow.QQ -> beansLocalized("登录 QQ 音乐", "QQ Music")
    LoginFlow.KUGOU -> beansLocalized("登录酷狗音乐", "Kugou Music")
}

// ---------------------------------------------------------------------------------------------
// MARK: - 账号面板（网易云 / QQ / 酷狗）
// ---------------------------------------------------------------------------------------------

@Composable
private fun AccountHub(
    style: BeansUIStyle,
    onOpen: (LoginFlow) -> Unit,
) {
    val enabledRaw by SettingsStore.enabledProviders.collectAsState()
    val neteaseLoggedIn by AuthStore.loggedInFlow.collectAsState()
    val neteaseNickname by AuthStore.nicknameFlow.collectAsState()
    val neteaseVip by AuthStore.vipBadgeFlow.collectAsState()
    val qqLoggedIn by QQMusicAuth.loggedInFlow.collectAsState()
    val qqNickname by QQMusicAuth.nicknameFlow.collectAsState()
    val qqVip by QQMusicAuth.vipBadgeFlow.collectAsState()
    val kugouLoggedIn by KugouMusicAuth.loggedInFlow.collectAsState()
    val kugouNickname by KugouMusicAuth.nicknameFlow.collectAsState()
    val kugouVip by KugouMusicAuth.vipBadgeFlow.collectAsState()

    val enabled = enabledRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val neteaseEnabled = enabled.contains("netease")
    val qqEnabled = enabled.contains("qq")
    val kugouEnabled = enabled.contains("kugou")

    var pendingLogout by remember { mutableStateOf<LoginFlow?>(null) }
    val scope = rememberCoroutineScope()

    BeansSectionHeader(title = beansLocalized("账号", "Accounts"))

    if (neteaseEnabled) {
        AccountPlatformCard(
            monogram = "网",
            brandColor = Color(0xFFC20C0C),
            name = beansLocalized("网易云音乐", "NetEase Cloud Music"),
            status = if (neteaseLoggedIn) {
                neteaseNickname.ifBlank {
                    AuthStore.user?.uid?.let { "UID $it" } ?: beansLocalized("已登录", "Signed in")
                }
            } else {
                beansLocalized("未登录 · 扫码登录同步歌单", "Not signed in · scan to sync playlists")
            },
            badge = if (neteaseLoggedIn) neteaseVip else null,
            loggedIn = neteaseLoggedIn,
            style = style,
            onClick = {
                BeansHaptics.tap()
                if (neteaseLoggedIn) pendingLogout = LoginFlow.NETEASE_QR else onOpen(LoginFlow.NETEASE_QR)
            },
        )
    }

    if (qqEnabled) {
        AccountPlatformCard(
            monogram = "Q",
            brandColor = Color(0xFF31C27C),
            name = beansLocalized("QQ 音乐", "QQ Music"),
            status = if (qqLoggedIn) {
                qqNickname.ifBlank { beansLocalized("已登录", "Signed in") }
            } else {
                beansLocalized("未登录 · 扫码 / 粘贴 Cookie 登录", "Not signed in · QR or paste Cookie")
            },
            badge = if (qqLoggedIn) qqVip else null,
            loggedIn = qqLoggedIn,
            style = style,
            onClick = {
                BeansHaptics.tap()
                if (qqLoggedIn) pendingLogout = LoginFlow.QQ else onOpen(LoginFlow.QQ)
            },
        )
    }

    if (kugouEnabled) {
        AccountPlatformCard(
            monogram = "酷",
            brandColor = Color(0xFF1478FF),
            name = beansLocalized("酷狗音乐", "Kugou Music"),
            status = if (kugouLoggedIn) {
                kugouNickname.ifBlank { beansLocalized("已登录", "Signed in") }
            } else {
                beansLocalized("未登录 · App 扫码同步歌单", "Not signed in · scan to sync playlists")
            },
            badge = if (kugouLoggedIn) kugouVip else null,
            loggedIn = kugouLoggedIn,
            style = style,
            onClick = {
                BeansHaptics.tap()
                if (kugouLoggedIn) pendingLogout = LoginFlow.KUGOU else onOpen(LoginFlow.KUGOU)
            },
        )
    }

    Text(
        text = beansLocalized(
            "登录后可同步歌单并提升可播成功率",
            "Sign in to sync playlists and improve playback availability",
        ),
        color = BeansTheme.colors.comment,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    pendingLogout?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingLogout = null },
            title = { Text(text = logoutTitle(target)) },
            text = null,
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLogout = null
                        scope.launch {
                            when (target) {
                                LoginFlow.NETEASE_QR -> {
                                    AuthStore.logout()
                                    BeansToastCenter.show(beansLocalized("已退出网易云账号", "Signed out of NetEase"))
                                }

                                LoginFlow.QQ -> {
                                    QQMusicAuth.logout()
                                    BeansToastCenter.show(beansLocalized("已退出 QQ 音乐", "Signed out of QQ Music"))
                                }

                                LoginFlow.KUGOU -> {
                                    KugouMusicAuth.logout()
                                    BeansToastCenter.show(beansLocalized("已退出酷狗音乐", "Signed out of Kugou"))
                                }

                                else -> Unit
                            }
                        }
                    },
                ) {
                    Text(
                        text = beansLocalized("退出登录", "Sign out"),
                        color = Color(0xFFFF453A),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLogout = null }) {
                    Text(text = beansLocalized("取消", "Cancel"))
                }
            },
            containerColor = BeansTheme.colors.card,
            titleContentColor = BeansTheme.colors.label,
        )
    }
}

private fun logoutTitle(target: LoginFlow): String = when (target) {
    LoginFlow.QQ -> beansLocalized("退出 QQ 音乐？", "Sign out of QQ Music?")
    LoginFlow.KUGOU -> beansLocalized("退出酷狗音乐？", "Sign out of Kugou Music?")
    else -> beansLocalized("退出网易云登录？", "Sign out of NetEase Cloud Music?")
}

@Composable
private fun AccountPlatformCard(
    monogram: String,
    brandColor: Color,
    name: String,
    status: String,
    badge: String?,
    loggedIn: Boolean,
    style: BeansUIStyle,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(20.dp)

    BeansPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        scale = 0.97f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .beansGlass(shape = shape, style = style)
                .clip(shape)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(brandColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = monogram,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = name,
                    color = colors.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = status,
                        color = colors.comment,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!badge.isNullOrBlank()) {
                        BeansVIPBadge(text = badge)
                    }
                }
            }

            BeansCapsule(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                Text(
                    text = if (loggedIn) {
                        beansLocalized("退出", "Sign out")
                    } else {
                        beansLocalized("登录", "Sign in")
                    },
                    color = if (loggedIn) Color(0xFFFF453A) else colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 网易云：扫码登录
// ---------------------------------------------------------------------------------------------

@Composable
private fun NetEaseQrPanel(
    style: BeansUIStyle,
    onSuccess: () -> Unit,
    onWebLogin: () -> Unit,
) {
    var key by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<LoginQRStatus>(LoginQRStatus.Loading) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    // 生成二维码：NetEaseAPI.qrKey() → qrLoginURL(key) → 本地 ZXing 渲染
    LaunchedEffect(attempt) {
        status = LoginQRStatus.Loading
        image = null
        key = null
        val fetched = runCatching { NetEaseApi.qrKey() }
        val newKey = fetched.getOrNull()
        if (newKey.isNullOrBlank()) {
            status = LoginQRStatus.Error(
                fetched.exceptionOrNull()?.message ?: beansLocalized("二维码获取失败", "Could not fetch the QR code")
            )
            return@LaunchedEffect
        }
        val rendered = withContext(Dispatchers.Default) {
            loginQRCodeBitmap(NetEaseApi.qrLoginURL(newKey))
        }
        if (rendered == null) {
            status = LoginQRStatus.Error(beansLocalized("二维码生成失败", "Could not render the QR code"))
            return@LaunchedEffect
        }
        key = newKey
        image = rendered
        status = LoginQRStatus.Waiting
    }

    // 轮询：iOS 用 2 秒 Timer；网络抖动时跳过本次，继续等待
    LaunchedEffect(key, attempt) {
        val currentKey = key ?: return@LaunchedEffect
        while (true) {
            delay(2_000)
            val code = runCatching { AuthStore.qrCheck(currentKey) }.getOrNull() ?: continue
            when (code) {
                800 -> {
                    status = LoginQRStatus.Expired
                    return@LaunchedEffect
                }

                802 -> status = LoginQRStatus.Scanned
                803 -> {
                    status = LoginQRStatus.Success
                    runCatching { AuthStore.finishLogin() }
                        .onSuccess {
                            BeansHaptics.success()
                            BeansToastCenter.show(beansLocalized("网易云登录成功", "Signed in to NetEase"))
                            onSuccess()
                        }
                        .onFailure {
                            status = LoginQRStatus.Error(
                                it.message ?: beansLocalized("同步账号失败", "Could not sync the account")
                            )
                        }
                    return@LaunchedEffect
                }

                else -> if (status == LoginQRStatus.Scanned) status = LoginQRStatus.Waiting
            }
        }
    }

    LoginBrandHeader(
        title = "LuluMusic",
        subtitle = beansLocalized("登录网易云音乐，同步你的歌单", "Sign in to NetEase Cloud Music to sync your playlists"),
    )

    LoginQRCard(
        image = image,
        status = status,
        onRefresh = { attempt += 1 },
    )

    LoginStatusLine(
        status = status,
        waitingText = beansLocalized("请使用网易云音乐 App 扫码", "Scan with the NetEase Cloud Music app"),
    )

    BeansGlassButton(
        title = beansLocalized("网页登录", "Web sign-in"),
        systemName = "globe",
        onClick = {
            BeansHaptics.tap()
            onWebLogin()
        },
        style = style,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 网易云：应用内网页登录
// ---------------------------------------------------------------------------------------------

@Composable
private fun NetEaseWebPanel(
    style: BeansUIStyle,
    onSuccess: () -> Unit,
) {
    val colors = BeansTheme.colors
    var pageLoaded by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    Text(
        text = beansLocalized(
            "在下方网页中完成网易云登录，完成后手动点击下方「同步登录状态」",
            "Sign in on the page below, then tap \"Sync sign-in state\".",
        ),
        color = colors.comment,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.glassFill),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageLoaded = true
                        }
                    }
                    loadUrl("https://music.163.com/#/login")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!pageLoaded) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        }
    }

    if (message.isNotBlank()) {
        Text(
            text = message,
            color = if (message.startsWith("✓")) colors.sage else Color(0xFFFF453A).copy(alpha = 0.85f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    BeansGlassButton(
        title = if (syncing) {
            beansLocalized("正在读取登录状态…", "Reading sign-in state…")
        } else {
            beansLocalized("同步登录状态", "Sync sign-in state")
        },
        systemName = "arrow.triangle.2.circlepath",
        prominent = true,
        onClick = {
            if (syncing) return@BeansGlassButton
            message = ""
            val cookie = runCatching { CookieManager.getInstance().getCookie("https://music.163.com") }.getOrNull()
            val cookies = parseCookiePairs(cookie)
            if (cookies["MUSIC_U"].isNullOrBlank()) {
                message = beansLocalized(
                    "未检测到有效登录态，请先在网页中完成网易云登录",
                    "No valid session found — finish signing in on the page first.",
                )
                return@BeansGlassButton
            }
            NetEaseApi.importWebCookies(cookies)
            syncing = true
            scope.launch {
                val result = runCatching { AuthStore.finishLogin() }
                syncing = false
                result
                    .onSuccess {
                        BeansHaptics.success()
                        BeansToastCenter.show(beansLocalized("网易云登录成功", "Signed in to NetEase"))
                        onSuccess()
                    }
                    .onFailure {
                        message = beansLocalized(
                            "同步账号失败：${it.message ?: "未知错误"}",
                            "Could not sync the account: ${it.message ?: "unknown error"}",
                        )
                    }
            }
        },
        style = style,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - QQ 音乐：扫码 / 粘贴 Cookie
// ---------------------------------------------------------------------------------------------

@Composable
private fun QQPanel(
    style: BeansUIStyle,
    onSuccess: () -> Unit,
) {
    var mode by remember { mutableIntStateOf(0) }

    LoginModeSwitch(
        options = listOf(
            beansLocalized("扫码登录", "QR code"),
            beansLocalized("粘贴 Cookie", "Paste Cookie"),
        ),
        selectedIndex = mode,
        onSelect = { mode = it },
    )

    if (mode == 0) {
        QQScanPanel(style = style, onSuccess = onSuccess)
    } else {
        QQCookiePanel(style = style, onSuccess = onSuccess)
    }
}

@Composable
private fun QQScanPanel(
    style: BeansUIStyle,
    onSuccess: () -> Unit,
) {
    var status by remember { mutableStateOf<LoginQRStatus>(LoginQRStatus.Loading) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        status = LoginQRStatus.Loading
        image = null
        val fetched = runCatching { QQMusicAuth.fetchQRCode() }
        val bytes = fetched.getOrNull()
        val decoded = if (bytes == null || bytes.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
        if (decoded == null) {
            status = LoginQRStatus.Error(
                fetched.exceptionOrNull()?.message
                    ?: beansLocalized("二维码获取失败", "Could not fetch the QR code")
            )
            return@LaunchedEffect
        }
        image = decoded
        status = LoginQRStatus.Waiting

        while (true) {
            delay(1_500)
            val state = runCatching { QQMusicAuth.poll() }.getOrNull() ?: continue
            when (state) {
                is QQMusicAuth.ScanState.Waiting -> if (status == LoginQRStatus.Scanned) {
                    status = LoginQRStatus.Waiting
                }

                is QQMusicAuth.ScanState.Scanned -> status = LoginQRStatus.Scanned
                is QQMusicAuth.ScanState.Expired -> {
                    status = LoginQRStatus.Expired
                    return@LaunchedEffect
                }

                is QQMusicAuth.ScanState.Success -> {
                    status = LoginQRStatus.Success
                    runCatching { QQMusicAuth.fetchVIPStatus() }
                    runCatching { FavoritesStore.syncQQFromCloud() }
                    BeansHaptics.success()
                    BeansToastCenter.show(
                        beansLocalized(
                            "QQ 音乐已登录：${state.nickname}",
                            "Signed in to QQ Music: ${state.nickname}",
                        )
                    )
                    onSuccess()
                    return@LaunchedEffect
                }

                is QQMusicAuth.ScanState.Error -> {
                    status = LoginQRStatus.Error(state.message)
                    return@LaunchedEffect
                }
            }
        }
    }

    LoginQRCard(
        image = image,
        status = status,
        onRefresh = { attempt += 1 },
    )

    LoginStatusLine(
        status = status,
        waitingText = beansLocalized("请使用 QQ 或 QQ 音乐 App 扫码", "Scan with QQ or the QQ Music app"),
    )

    Text(
        text = beansLocalized(
            "扫码登录会保存 QQ 音乐移动端凭证，用于同步云端歌单。",
            "QR sign-in stores the QQ Music mobile credentials used to sync your cloud playlists.",
        ),
        color = BeansTheme.colors.comment,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun QQCookiePanel(
    style: BeansUIStyle,
    onSuccess: () -> Unit,
) {
    val colors = BeansTheme.colors
    var cookieText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Text(
        text = beansLocalized(
            "电脑浏览器打开 https://y.qq.com 登录 QQ 后，按 F12 → Network → 刷新页面，点任意请求，复制 Request Headers 里的整段 Cookie 粘贴到下方",
            "Sign in at https://y.qq.com on a desktop browser, then F12 → Network → refresh → open any request and paste its full Cookie request header below.",
        ),
        color = colors.comment,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = cookieText,
        onValueChange = { cookieText = it },
        minLines = 4,
        maxLines = 8,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )

    if (message.isNotBlank()) {
        Text(
            text = message,
            color = if (message.startsWith("✓")) colors.sage else Color(0xFFFF453A).copy(alpha = 0.85f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    BeansGlassButton(
        title = beansLocalized("导入 Cookie", "Import Cookie"),
        systemName = "arrow.down.circle",
        prominent = true,
        onClick = {
            message = ""
            val payload = runCatching { QQMusicAuth.parseCookieHeader(cookieText) }.getOrDefault(emptyMap())
            if (!QQMusicAuth.hasValidLogin(payload)) {
                message = beansLocalized(
                    "Cookie 格式或登录态无效，请确认已完整复制",
                    "The Cookie is invalid — make sure it was copied completely.",
                )
                return@BeansGlassButton
            }
            QQMusicAuth.importCookies(payload, null)
            BeansHaptics.success()
            BeansToastCenter.show(beansLocalized("QQ 音乐登录成功", "Signed in to QQ Music"))
            message = beansLocalized("✓ QQ 音乐登录成功", "✓ Signed in to QQ Music")
            onSuccess()
        },
        style = style,
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 酷狗音乐：扫码登录
// ---------------------------------------------------------------------------------------------

@Composable
private fun KugouQrPanel(
    style: BeansUIStyle,
    onSuccess: () -> Unit,
) {
    var status by remember { mutableStateOf<LoginQRStatus>(LoginQRStatus.Loading) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        status = LoginQRStatus.Loading
        image = null
        runCatching { KugouMusicAuth.prepareDevice() }

        val fetched = runCatching { KugouMusicApi.qrKey() }
        val login = fetched.getOrNull()
        if (login == null) {
            status = LoginQRStatus.Error(
                fetched.exceptionOrNull()?.message
                    ?: beansLocalized("二维码获取失败", "Could not fetch the QR code")
            )
            return@LaunchedEffect
        }
        val rendered = withContext(Dispatchers.Default) { loginQRCodeBitmap(login.url) }
        if (rendered == null) {
            status = LoginQRStatus.Error(beansLocalized("二维码生成失败", "Could not render the QR code"))
            return@LaunchedEffect
        }
        image = rendered
        status = LoginQRStatus.Waiting

        // iOS 侧轮询间隔 1.2 秒
        while (true) {
            delay(1_200)
            val state = runCatching { KugouMusicApi.pollQR(login.key) }.getOrNull() ?: continue
            when (state) {
                is KugouQRState.Waiting -> if (status == LoginQRStatus.Scanned) {
                    status = LoginQRStatus.Waiting
                }

                is KugouQRState.Scanned -> status = LoginQRStatus.Scanned
                is KugouQRState.Expired -> {
                    status = LoginQRStatus.Expired
                    return@LaunchedEffect
                }

                is KugouQRState.Success -> {
                    status = LoginQRStatus.Success
                    BeansHaptics.success()
                    BeansToastCenter.show(
                        beansLocalized(
                            "酷狗音乐已登录：${state.nickname}",
                            "Signed in to Kugou Music: ${state.nickname}",
                        )
                    )
                    onSuccess()
                    return@LaunchedEffect
                }

                is KugouQRState.Failure -> {
                    status = LoginQRStatus.Error(state.message)
                    return@LaunchedEffect
                }
            }
        }
    }

    LoginQRCard(
        image = image,
        status = status,
        onRefresh = { attempt += 1 },
    )

    LoginStatusLine(
        status = status,
        waitingText = beansLocalized("请使用酷狗音乐 App 扫码", "Scan with the Kugou Music app"),
    )

    Text(
        text = beansLocalized(
            "请使用酷狗音乐 App 扫码。登录成功后会保存移动端 token、设备 mid 和 dfid，用于同步云端歌单。",
            "Scan with the Kugou Music app. A successful sign-in stores the mobile token, device mid and dfid used to sync your cloud playlists.",
        ),
        color = BeansTheme.colors.comment,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------------------------
// MARK: - 共用零件
// ---------------------------------------------------------------------------------------------

@Composable
private fun LoginBrandHeader(title: String, subtitle: String) {
    val colors = BeansTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = BeansIcons.of("music.mic", Icons.Rounded.Refresh),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = title,
            color = colors.label,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            color = colors.comment,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** QR 展示区：`BeansQRCodeView` 只负责显示，位图由登录流程生成后传入。 */
@Composable
private fun LoginQRCard(
    image: ImageBitmap?,
    status: LoginQRStatus,
    onRefresh: () -> Unit,
) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(30.dp)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        BeansSurface(
            shape = shape,
            modifier = Modifier.padding(18.dp),
        ) {
            Box(
                modifier = Modifier.size(250.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    BeansQRCodeView(image = image, size = 214.dp)
                } else {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }

        val failure = (status as? LoginQRStatus.Error)?.message
        if (status == LoginQRStatus.Expired || failure != null) {
            Column(
                modifier = Modifier
                    .size(210.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.55f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = colors.label,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = failure ?: beansLocalized("二维码已过期", "QR code expired"),
                    color = colors.label,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Spacer(Modifier.height(10.dp))
                BeansGlassButton(
                    title = beansLocalized("刷新", "Refresh"),
                    systemName = "arrow.clockwise",
                    prominent = true,
                    onClick = {
                        BeansHaptics.tap()
                        onRefresh()
                    },
                )
            }
        }
    }
}

@Composable
private fun LoginStatusLine(
    status: LoginQRStatus,
    waitingText: String,
) {
    val colors = BeansTheme.colors
    val text = when (status) {
        LoginQRStatus.Loading -> beansLocalized("正在生成二维码…", "Generating QR code…")
        LoginQRStatus.Waiting -> waitingText
        LoginQRStatus.Scanned -> beansLocalized("已扫码，请在手机上确认登录", "Scanned — confirm on your phone")
        LoginQRStatus.Success -> beansLocalized("登录成功，正在同步歌单…", "Signed in — syncing playlists…")
        LoginQRStatus.Expired -> beansLocalized("二维码已过期", "QR code expired")
        is LoginQRStatus.Error -> status.message
    }
    val color = when (status) {
        LoginQRStatus.Scanned, LoginQRStatus.Success -> colors.sage
        is LoginQRStatus.Error -> Color(0xFFFF453A).copy(alpha = 0.85f)
        else -> colors.comment
    }

    Box(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoginModeSwitch(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = BeansTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.label.copy(alpha = if (colors.isDark) 0.08f else 0.05f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, title ->
            val isSelected = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) colors.card else Color.Transparent)
                    .beansPressClickable(
                        interactionSource = interaction,
                        scale = 0.97f,
                        onClick = {
                            BeansHaptics.select()
                            onSelect(index)
                        },
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    color = if (isSelected) colors.accent else colors.label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 工具
// ---------------------------------------------------------------------------------------------

/**
 * QR *generation* — Android has no built-in encoder, so ZXing is used
 * (`com.google.zxing:core`, added to `app/build.gradle.kts`). Returns `null` on any failure.
 */
private fun loginQRCodeBitmap(text: String, size: Int = 640): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 1,
    )
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    val on = android.graphics.Color.BLACK
    val off = android.graphics.Color.WHITE
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) on else off)
        }
    }
    bitmap.asImageBitmap()
}.getOrNull()

/** `a=1; b=2` → `{a=1, b=2}` (WebView `CookieManager` returns the header form). */
private fun parseCookiePairs(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    raw.split(';').forEach { part ->
        val index = part.indexOf('=')
        if (index > 0) {
            val name = part.substring(0, index).trim()
            val value = part.substring(index + 1).trim()
            if (name.isNotEmpty()) out[name] = value
        }
    }
    return out
}

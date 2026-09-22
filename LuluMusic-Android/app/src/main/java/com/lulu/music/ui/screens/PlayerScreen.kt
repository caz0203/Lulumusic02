package com.lulu.music.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import com.lulu.music.data.model.LyricLine
import com.lulu.music.data.model.LyricParser
import com.lulu.music.data.model.LyricTiming
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.playback.DownloadManager
import com.lulu.music.playback.PlaybackController
import com.lulu.music.playback.RepeatMode
import com.lulu.music.ui.PlayerOpenRequest
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansCoverImage
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.components.beansPressClickable
import com.lulu.music.ui.theme.BeansTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// MARK: - 参考图里的固定尺寸
// ---------------------------------------------------------------------------------------------

/** 参考图：顶栏返回 / 右侧图标按钮 36dp。 */
private val PlayerTopIconSize = 36.dp

/** 参考图：`歌曲百科` 小胶囊高度。 */
private val PlayerWikiPillHeight = 24.dp

/** 参考图：进度条上方那排图标的点击区尺寸。 */
private val PlayerActionsIconSize = 34.dp

/** 参考图：底部 5 个小图标的点击区尺寸（图标本身 ~28dp）。 */
private val PlayerBottomIconSize = 28.dp

/**
 * 全屏播放器，移植 iOS `PlayerView` 的核心：封面 / 歌词 / 黑胶 / 极简四种版式，
 * 传输控制、可拖动进度条、收藏、随机 / 循环、倍速、定时关闭、播放队列，
 * 以及带翻译的滚动歌词。
 *
 * 版式通过 [SettingsStore.playerLayout]（key `beans.playerLayout`）持久化：
 * 顶栏的版式按钮打开 [PlayerLayoutSheet]，设置页「播放设置」里写入同一个 key。
 *
 * @param onDismiss 关闭播放页（下拉手势 / 顶栏返回按钮）
 */
@Composable
fun BeansPlayerScreen(onDismiss: () -> Unit) {
    val colors = BeansTheme.colors
    val song by PlaybackController.currentSong.collectAsState()
    val isPlaying by PlaybackController.isPlaying.collectAsState()
    val isBuffering by PlaybackController.isBuffering.collectAsState()
    val positionMs by PlaybackController.positionMs.collectAsState()
    val durationMs by PlaybackController.durationMs.collectAsState()
    val repeatMode by PlaybackController.repeatMode.collectAsState()
    val shuffle by PlaybackController.shuffle.collectAsState()
    val speed by PlaybackController.speed.collectAsState()
    val sleepRemaining by PlaybackController.sleepRemainingMs.collectAsState()
    val queue by PlaybackController.queue.collectAsState()
    val queueIndex by PlaybackController.queueIndex.collectAsState()
    val downloadTasks by DownloadManager.tasks.collectAsState()

    val lyricFontSize by SettingsStore.lyricsFontSize.collectAsState()
    val lyricAlign by SettingsStore.lyricsAlign.collectAsState()
    val lyricTranslation by SettingsStore.lyricsTranslation.collectAsState()
    val lyricOffset by SettingsStore.lyricOffset.collectAsState()
    val audioQuality by SettingsStore.audioQuality.collectAsState()
    val layoutRaw by SettingsStore.playerLayout.collectAsState()
    val layout = PlayerLayout.fromRaw(layoutRaw)

    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showLayouts by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showWiki by remember { mutableStateOf(false) }
    var followed by remember { mutableStateOf(false) }

    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricError by remember { mutableStateOf<String?>(null) }
    var isLiked by remember { mutableStateOf(false) }
    var commentCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    val current = song

    // 歌词：随曲目变化重新拉取。
    LaunchedEffect(current?.identityKey) {
        lyrics = emptyList()
        lyricError = null
        followed = false
        val s = current ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { runCatching { loadLyrics(s) } }
        result.onSuccess { lines ->
            lyrics = lines
            if (lines.isEmpty()) lyricError = beansLocalized("暂无歌词", "No lyrics")
        }.onFailure {
            lyricError = beansLocalized("歌词加载失败", "Failed to load lyrics")
        }
    }

    LaunchedEffect(current?.identityKey) {
        isLiked = FavoritesStore.isLiked(current)
    }

    // 评论数：只有网易云有现成的 total；失败时静默保持 null（只显示图标）。
    LaunchedEffect(current?.identityKey) {
        commentCount = null
        val s = current ?: return@LaunchedEffect
        if (s.source != SongSource.NET_EASE) return@LaunchedEffect
        commentCount = withContext(Dispatchers.IO) { PlayerCommentCounts.count(s) }
    }

    val progressSeconds = positionMs / 1000.0
    val durationSeconds = if (durationMs > 0) durationMs / 1000.0 else (current?.duration ?: 0.0)
    val qualityLabel = audioQualityLabel(audioQuality)

    // 「歌词优先」整屏歌词；其余版式沿用「封面 / 黑胶 / 极简」+ 点击切换歌词的状态。
    val lyricsMode = showLyrics || layout == PlayerLayout.LYRICS

    val toggleFavorite: () -> Unit = {
        BeansHaptics.tap()
        val target = current ?: Unit
        if (target is Song) {
            // 乐观更新；网易云拒绝时 FavoritesStore 会回滚。
            isLiked = !isLiked
            scope.launch {
                val ok = FavoritesStore.toggle(target)
                isLiked = FavoritesStore.isLiked(target)
                if (!ok) {
                    BeansToastCenter.show(beansLocalized("收藏失败", "Could not update favourite"))
                }
            }
        }
    }

    val toggleDownload: () -> Unit = {
        BeansHaptics.tap()
        current?.let { target ->
            if (DownloadManager.isDownloaded(target)) {
                DownloadManager.delete(target)
                BeansToastCenter.show(beansLocalized("已删除下载", "Download removed"))
            } else {
                DownloadManager.download(target)
                BeansToastCenter.show(beansLocalized("开始下载", "Download started"))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 跟随封面的氛围背景；黑胶 / 歌词版式再叠一层深靛蓝。
        PlayerBackground(coverURL = current?.coverURL)
        if (layout == PlayerLayout.VINYL || layout == PlayerLayout.LYRICS) {
            VinylBackdropOverlay()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- 顶栏（参考图：‹ 收起 / 居中标题+歌手+关注 / 历史图标） ----
            PlayerTopBar(
                song = current,
                followed = followed,
                onToggleFollow = {
                    BeansHaptics.tap()
                    followed = !followed
                },
                onDismiss = onDismiss,
                onOpenMore = {
                    BeansHaptics.tap()
                    showMore = true
                },
            )

            Spacer(Modifier.height(6.dp))

            // ---- `歌曲百科` 小胶囊 ----
            PlayerWikiPill(onClick = { showWiki = true })

            Spacer(Modifier.height(6.dp))

            // ---- 中间内容（四种版式） ---------------------------------------
            when (layout) {
                PlayerLayout.VINYL -> {
                    // 黑胶版式保留唱盘上方的紧凑信息行（歌名 / 歌手）。
                    PlayerNowPlayingRow(
                        song = current,
                        isLiked = isLiked,
                        likeCount = null,
                        commentCount = commentCount,
                        foreground = PlayerOnVinylLabel,
                        showActions = false,
                        compact = true,
                        onToggleFavorite = toggleFavorite,
                        onComment = { PlayerCommentCounts.showToast(commentCount) },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    VinylStage(
                        coverURL = current?.coverURL,
                        // 歌词浮层盖住唱盘时暂停转动：不可见时没必要继续跑动画。
                        isPlaying = isPlaying && !showLyrics,
                        trackKey = current?.identityKey,
                        onTap = { showLyrics = !showLyrics },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }

                PlayerLayout.MINIMAL -> {
                    Spacer(Modifier.weight(1f))
                    BeansCoverImage(
                        url = current?.coverURL,
                        size = 150.dp,
                        cornerRadius = 18.dp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { showLyrics = true },
                    )
                    Spacer(Modifier.height(18.dp))
                    PlayerNowPlayingRow(
                        song = current,
                        isLiked = isLiked,
                        compact = true,
                        likeCount = null,
                        commentCount = commentCount,
                        showActions = false,
                        onToggleFavorite = toggleFavorite,
                        onComment = { PlayerCommentCounts.showToast(commentCount) },
                    )
                    Spacer(Modifier.weight(1f))
                }

                // ---- 歌词优先：整屏歌词，底部仍是同一套信息行 / 进度 / 控制 -----
                PlayerLayout.LYRICS -> {
                    LyricsSection(
                        lyrics = lyrics,
                        errorText = lyricError,
                        progress = progressSeconds,
                        userOffset = lyricOffset.toDouble(),
                        showTranslation = lyricTranslation,
                        fontSize = lyricFontSize,
                        align = lyricAlign,
                        hint = beansLocalized("长按屏幕分享歌词", "Long-press to share the lyrics"),
                        onSeek = { line ->
                            BeansHaptics.tap()
                            PlaybackController.seekTo(
                                (LyricTiming.seekTime(line, lyricOffset.toDouble()) * 1000).toLong(),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }

                // ---- 专辑封面：封面 / 歌词相互切换（原版式） --------------------
                PlayerLayout.COVER -> {
                    Spacer(Modifier.weight(1f))
                    Crossfade(targetState = showLyrics, label = "coverLyrics") { lyricsOn ->
                        if (lyricsOn) {
                            LyricsSection(
                                lyrics = lyrics,
                                errorText = lyricError,
                                progress = progressSeconds,
                                userOffset = lyricOffset.toDouble(),
                                showTranslation = lyricTranslation,
                                fontSize = lyricFontSize,
                                align = lyricAlign,
                                onSeek = { line ->
                                    BeansHaptics.tap()
                                    PlaybackController.seekTo(
                                        (LyricTiming.seekTime(line, lyricOffset.toDouble()) * 1000).toLong(),
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(330.dp),
                            )
                        } else {
                            val interaction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                BeansCoverImage(
                                    url = current?.coverURL,
                                    size = 300.dp,
                                    cornerRadius = 22.dp,
                                    modifier = Modifier.clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                    ) { showLyrics = true },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(6.dp))

            // ---- 进度条上方那排图标：… / 红心+计数 / 评论+计数 / 识别 ----
            PlayerActionsRow(
                isLiked = isLiked,
                likeCount = null,
                commentCount = commentCount,
                onMore = {
                    BeansHaptics.tap()
                    showMore = true
                },
                onToggleFavorite = toggleFavorite,
                onComment = { PlayerCommentCounts.showToast(commentCount) },
                onIdentify = {
                    BeansHaptics.tap()
                    BeansToastCenter.show(
                        beansLocalized("听歌识曲：暂未接入识别服务", "Song ID is not available yet"),
                    )
                },
            )

            // ---- 进度（左时间 / 中音质 / 右总时长） --------------------------
            PlayerProgressRow(
                positionSeconds = progressSeconds,
                durationSeconds = durationSeconds,
                qualityLabel = qualityLabel,
                onSeek = { PlaybackController.seekTo((it * 1000).toLong()) },
            )

            Spacer(Modifier.height(2.dp))

            // ---- 传输控制（无实心圆底的大号播放暂停） ------------------------
            PlayerTransportRow(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                shuffle = shuffle,
                repeatMode = repeatMode,
                onToggleShuffle = {
                    BeansHaptics.tap()
                    PlaybackController.setShuffle(!shuffle)
                },
                onPrevious = {
                    BeansHaptics.tap()
                    PlaybackController.previous()
                },
                onTogglePlayPause = {
                    BeansHaptics.tap()
                    PlaybackController.togglePlayPause()
                },
                onNext = {
                    BeansHaptics.tap()
                    PlaybackController.next()
                },
                onCycleRepeat = {
                    BeansHaptics.tap()
                    PlaybackController.cycleRepeatMode()
                },
            )

            Spacer(Modifier.height(4.dp))

            // ---- 底部 5 个次要图标（参考图：均衡器 / 音质 / 词 / 下载 / 更多） ----
            PlayerBottomIconRow(
                qualityLabel = qualityLabel,
                lyricsActive = lyricsMode,
                downloaded = current?.let { downloadTasks[it.identityKey] }
                    ?.state == DownloadManager.State.DONE,
                onEqualizer = {
                    BeansHaptics.tap()
                    BeansToastCenter.show(
                        beansLocalized("均衡器在「设置 → 均衡器」中调整", "Open Settings → Equalizer to tune it"),
                    )
                },
                onQuality = {
                    BeansHaptics.tap()
                    BeansToastCenter.show(
                        beansLocalized("当前音质：$qualityLabel", "Current quality: $qualityLabel"),
                    )
                },
                onToggleLyrics = {
                    if (layout == PlayerLayout.LYRICS) {
                        // 歌词优先版式：点了就切回封面版式，避免被困在整屏歌词里。
                        SettingsStore.setPlayerLayout(PlayerLayout.COVER.raw)
                    } else {
                        showLyrics = !showLyrics
                    }
                },
                onDownload = toggleDownload,
                onMore = {
                    BeansHaptics.tap()
                    showMore = true
                },
            )

            Spacer(Modifier.height(10.dp))
        }
    }

    // ---- 面板 --------------------------------------------------------------
    if (showLayouts) {
        PlayerLayoutSheet(
            current = layout,
            onPick = { SettingsStore.setPlayerLayout(it.raw) },
            onDismiss = { showLayouts = false },
        )
    }

    if (showMore) {
        PlayerMoreSheet(
            sourceText = sourceLabel(current?.source),
            speed = speed,
            sleepRemaining = sleepRemaining,
            layout = layout,
            onOpenLayouts = {
                showMore = false
                showLayouts = true
            },
            onOpenSpeed = {
                showMore = false
                showSpeed = true
            },
            onOpenSleep = {
                showMore = false
                showSleep = true
            },
            onDismiss = { showMore = false },
        )
    }

    if (showWiki) {
        SongWikiSheet(song = current, qualityLabel = qualityLabel, onDismiss = { showWiki = false })
    }

    if (showQueue) {
        BeansBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueSheetContent(
                queue = queue,
                currentIndex = queueIndex,
                onSelect = { index ->
                    BeansHaptics.tap()
                    PlaybackController.play(queue, index)
                    // 播放页已经在前台时 BeansApp 会忽略这次请求（不会叠出第二层播放页）。
                    PlayerOpenRequest.request()
                },
                onRemove = { index -> PlaybackController.removeFromQueue(index) },
                onClear = { PlaybackController.clearQueue() },
            )
        }
    }

    if (showSpeed) {
        BeansBottomSheet(onDismissRequest = { showSpeed = false }) {
            SpeedSheetContent(
                current = speed,
                onPick = { v ->
                    PlaybackController.setSpeed(v)
                    showSpeed = false
                },
            )
        }
    }

    if (showSleep) {
        BeansBottomSheet(onDismissRequest = { showSleep = false }) {
            SleepSheetContent(
                onPick = { minutes ->
                    PlaybackController.startSleepTimer(minutes)
                    showSleep = false
                    BeansToastCenter.show(
                        if (minutes > 0) {
                            String.format(beansLocalized("%d 分钟后停止播放", "Stops in %d min"), minutes)
                        } else {
                            beansLocalized("已取消定时关闭", "Sleep timer cancelled")
                        },
                    )
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 播放页组件
// ---------------------------------------------------------------------------------------------

/**
 * 无底色的大号播放 / 暂停字形（参考图里播放键没有实心圆底）。
 *
 * [BeansGlassIconButton] 一定带圆形玻璃底，无法表达这种「裸字形」，
 * 因此这里直接画一个带按压动效的 [Icon]；缓冲中仍用 [androidx.compose.material3.CircularProgressIndicator]。
 */
@Composable
private fun PlayerGlyphButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = PlayerActionsIconSize,
    iconSize: Dp = 22.dp,
    tint: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val resolvedTint = if (tint == Color.Unspecified) colors.label else tint

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (background == Color.Unspecified) {
                    Modifier
                } else {
                    Modifier.background(background, CircleShape)
                }
            )
            .clip(CircleShape)
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.9f,
                pressedBrightness = 0f,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolvedTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 顶栏：左侧收起、中间「歌名 / 歌手 + 关注」、右侧历史（更多）图标。
 *
 * 布局用 [BoxWithConstraints] 而不是 `Row + weight(1f)`：参考图的标题是**屏幕居中**的，
 * 左右按钮宽度不同（36dp vs 36dp + 关注胶囊）时，weight 会让标题偏移。
 */
@Composable
private fun PlayerTopBar(
    song: Song?,
    followed: Boolean,
    onToggleFollow: () -> Unit,
    onDismiss: () -> Unit,
    onOpenMore: () -> Unit,
) {
    val colors = BeansTheme.colors

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 56.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = song?.name ?: beansLocalized("未在播放", "Nothing playing"),
                    color = colors.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song?.isVIP == true) {
                    BeansVIPBadge(text = "VIP")
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = song?.artists.orEmpty(),
                    color = colors.comment,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song != null) {
                    FollowPill(
                        followed = followed,
                        foreground = colors.label,
                        onToggle = onToggleFollow,
                    )
                }
            }
        }

        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            PlayerGlyphButton(
                icon = Icons.Rounded.KeyboardArrowDown,
                onClick = onDismiss,
                contentDescription = beansLocalized("收起", "Collapse"),
                size = PlayerTopIconSize,
                iconSize = 24.dp,
            )
        }

        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            PlayerGlyphButton(
                icon = Icons.Rounded.Tune,
                onClick = onOpenMore,
                contentDescription = beansLocalized("更多", "More"),
                size = PlayerTopIconSize,
                iconSize = 20.dp,
            )
        }
    }
}

/** 参考图里标题下方那颗 `歌曲百科` 小胶囊（前置 ⓘ 图标）。 */
@Composable
private fun PlayerWikiPill(onClick: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .height(PlayerWikiPillHeight)
            .clip(CircleShape)
            .background(
                if (colors.isDark) colors.label.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f),
            )
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.95f,
                pressedBrightness = 0f,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = colors.comment,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = beansLocalized("歌曲百科", "Song wiki"),
            color = colors.comment,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * 进度条上方那排紧凑图标：`…` 在左，右侧依次是红心（带计数）、评论（带计数）、识别。
 *
 * @param likeCount 真实点赞数；为 null 时只画图标不画数字（`Song` 模型没有点赞字段）
 * @param commentCount 真实评论数；为 null 时只画气泡
 */
@Composable
private fun PlayerActionsRow(
    isLiked: Boolean,
    likeCount: Int?,
    commentCount: Int?,
    onMore: () -> Unit,
    onToggleFavorite: () -> Unit,
    onComment: () -> Unit,
    onIdentify: () -> Unit,
) {
    val colors = BeansTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerGlyphButton(
            icon = Icons.Rounded.MoreHoriz,
            onClick = onMore,
            contentDescription = beansLocalized("更多", "More"),
        )
        Spacer(Modifier.weight(1f))
        PlayerCountIcon(
            icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            tint = if (isLiked) colors.accent else colors.label,
            count = likeCount,
            onClick = onToggleFavorite,
        )
        Spacer(Modifier.width(6.dp))
        PlayerCountIcon(
            icon = Icons.Rounded.ChatBubbleOutline,
            tint = colors.label,
            count = commentCount,
            onClick = onComment,
        )
        Spacer(Modifier.width(6.dp))
        PlayerGlyphButton(
            icon = Icons.Rounded.Mic,
            onClick = onIdentify,
            contentDescription = beansLocalized("听歌识曲", "Identify song"),
            iconSize = 20.dp,
        )
    }
}

/**
 * 底部 5 个次要图标（参考图：均衡器 / 音质 / 歌词 / 下载 / 更多），等距、偏暗。
 *
 * 这些入口在改版前位于「次要操作行」的文字按钮里（倍速 / 定时 / 词）；
 * 倍速与定时关闭已移入 [PlayerMoreSheet]，功能一个都没少。
 */
@Composable
private fun PlayerBottomIconRow(
    qualityLabel: String,
    lyricsActive: Boolean,
    downloaded: Boolean,
    onEqualizer: () -> Unit,
    onQuality: () -> Unit,
    onToggleLyrics: () -> Unit,
    onDownload: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = BeansTheme.colors
    val dim = colors.label.copy(alpha = 0.55f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerGlyphButton(
            icon = Icons.Rounded.GraphicEq,
            onClick = onEqualizer,
            contentDescription = beansLocalized("均衡器", "Equalizer"),
            size = PlayerBottomIconSize + 8.dp,
            iconSize = PlayerBottomIconSize,
            tint = dim,
        )
        PlayerGlyphButton(
            icon = Icons.Rounded.Speed,
            onClick = onQuality,
            contentDescription = beansLocalized("音质：$qualityLabel", "Quality: $qualityLabel"),
            size = PlayerBottomIconSize + 8.dp,
            iconSize = PlayerBottomIconSize,
            tint = dim,
        )
        PlayerGlyphButton(
            icon = Icons.Rounded.ScreenRotation,
            onClick = onToggleLyrics,
            contentDescription = beansLocalized("歌词", "Lyrics"),
            size = PlayerBottomIconSize + 8.dp,
            iconSize = PlayerBottomIconSize,
            tint = if (lyricsActive) colors.accent else dim,
        )
        PlayerGlyphButton(
            icon = if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
            onClick = onDownload,
            contentDescription = beansLocalized("下载", "Download"),
            size = PlayerBottomIconSize + 8.dp,
            iconSize = PlayerBottomIconSize,
            tint = if (downloaded) colors.accent else dim,
        )
        PlayerGlyphButton(
            icon = Icons.Rounded.MoreHoriz,
            onClick = onMore,
            contentDescription = beansLocalized("更多", "More"),
            size = PlayerBottomIconSize + 8.dp,
            iconSize = PlayerBottomIconSize,
            tint = dim,
        )
    }
}

/**
 * 黑胶舞台：按可用宽高算出唱片直径，保证唱臂与唱盘都不会溢出。
 *
 * 高度预算：`唱盘 + 唱臂上探 + 呼吸间距` ≈ `discSize * 1.55`，因此
 * `discSize = min(可用宽 * 0.92, 可用高 / 1.55, 300dp)`。
 */
@Composable
private fun VinylStage(
    coverURL: String?,
    isPlaying: Boolean,
    trackKey: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Dp 只能用 minOf / maxOf，不能用 kotlin.math。
        val discSize = maxOf(
            140.dp,
            minOf(
                maxWidth * 0.92f,
                maxHeight / 1.55f,
                300.dp,
            ),
        )
        VinylPlayerView(
            coverURL = coverURL,
            isPlaying = isPlaying,
            trackKey = trackKey,
            discSize = discSize,
            onTap = onTap,
        )
    }
}

/**
 * 黑胶 / 极简版式的紧凑信息行（歌名 / VIP / 歌手），可选地附带收藏与评论按钮。
 *
 * 改版后详情版式的操作按钮统一由 [PlayerActionsRow] 承担，因此这里用
 * [showActions] = false 关掉行内按钮，避免同一屏出现两组红心 / 评论。
 */
@Composable
private fun PlayerNowPlayingRow(
    song: Song?,
    isLiked: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    likeCount: Int? = null,
    commentCount: Int? = null,
    foreground: Color = Color.Unspecified,
    showActions: Boolean = true,
    onToggleFavorite: (() -> Unit)? = null,
    onComment: (() -> Unit)? = null,
) {
    val colors = BeansTheme.colors
    val fg = if (foreground == Color.Unspecified) colors.label else foreground
    // 黑胶 / 极简版式叠了深色底：次级文字用近白的 70% 不透明度，和 [PlayerLayoutViews] 的取色保持一致。
    val dim = if (foreground == Color.Unspecified) {
        colors.comment
    } else {
        foreground.copy(alpha = 0.70f)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song?.name ?: beansLocalized("未在播放", "Nothing playing"),
                    color = fg,
                    fontSize = if (compact) 16.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song?.isVIP == true) {
                    Spacer(Modifier.width(6.dp))
                    BeansVIPBadge(text = "VIP")
                }
            }
            Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
            Text(
                text = song?.artists.orEmpty(),
                color = dim,
                fontSize = if (compact) 12.sp else 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (showActions && onToggleFavorite != null) {
            Spacer(Modifier.width(10.dp))
            PlayerCountIcon(
                icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                tint = if (isLiked) colors.accent else fg,
                count = likeCount,
                onClick = onToggleFavorite,
            )
        }
        if (showActions && onComment != null) {
            Spacer(Modifier.width(4.dp))
            PlayerCountIcon(
                icon = Icons.Rounded.ChatBubbleOutline,
                tint = fg,
                count = commentCount,
                onClick = onComment,
            )
        }
    }
}

/** 「关注」小胶囊；关注状态只存在于当前播放页会话（本项目没有关注接口）。 */
@Composable
private fun FollowPill(followed: Boolean, foreground: Color, onToggle: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .height(20.dp)
            .background(
                if (followed) colors.accent.copy(alpha = 0.18f) else foreground.copy(alpha = 0.12f),
                CircleShape,
            )
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.94f,
                onClick = onToggle,
            )
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = if (followed) Icons.Rounded.Check else Icons.Rounded.Add,
            contentDescription = null,
            tint = if (followed) colors.accent else foreground,
            modifier = Modifier.size(9.dp),
        )
        Text(
            text = if (followed) beansLocalized("已关注", "Following") else beansLocalized("关注", "Follow"),
            color = if (followed) colors.accent else foreground,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 图标 + 可选计数（计数为 null 时只显示图标）。 */
@Composable
private fun PlayerCountIcon(
    icon: ImageVector,
    tint: Color,
    count: Int?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .height(PlayerActionsIconSize)
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.90f,
                pressedBrightness = 0f,
                onClick = onClick,
            )
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        if (count != null) {
            Text(
                text = formatCount(count),
                color = tint,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/** 传输控制行：随机 / 上一首 / 播放暂停 / 下一首 / 循环（无实心圆底，等距排列）。 */
@Composable
private fun PlayerTransportRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val colors = BeansTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerGlyphButton(
            icon = Icons.Rounded.Shuffle,
            onClick = onToggleShuffle,
            contentDescription = beansLocalized("随机播放", "Shuffle"),
            size = 42.dp,
            iconSize = 22.dp,
            tint = if (shuffle) colors.accent else colors.label,
        )
        PlayerGlyphButton(
            icon = Icons.Rounded.SkipPrevious,
            onClick = onPrevious,
            contentDescription = beansLocalized("上一首", "Previous"),
            size = 48.dp,
            iconSize = 32.dp,
        )
        // 参考图：大号播放/暂停字形，没有实心圆底。
        PlayerGlyphButton(
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            onClick = onTogglePlayPause,
            contentDescription = beansLocalized("播放/暂停", "Play/Pause"),
            size = 62.dp,
            iconSize = 42.dp,
            tint = if (isBuffering) colors.accent else colors.label,
        )
        PlayerGlyphButton(
            icon = Icons.Rounded.SkipNext,
            onClick = onNext,
            contentDescription = beansLocalized("下一首", "Next"),
            size = 48.dp,
            iconSize = 32.dp,
        )
        PlayerGlyphButton(
            icon = if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            onClick = onCycleRepeat,
            contentDescription = beansLocalized("循环模式", "Repeat mode"),
            size = 42.dp,
            iconSize = 22.dp,
            tint = if (repeatMode != RepeatMode.OFF) colors.accent else colors.label,
        )
    }
}

/** 跟随封面的环境渐变 + 模糊封面。 */
@Composable
private fun PlayerBackground(coverURL: String?) {
    val colors = BeansTheme.colors
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.accent.copy(alpha = 0.22f),
                            colors.background,
                            colors.background,
                        ),
                    ),
                ),
        )
        if (!coverURL.isNullOrBlank()) {
            AsyncImage(
                model = coverURL,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.20f },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                colors.background.copy(alpha = 0.55f),
                                colors.background.copy(alpha = 0.92f),
                            ),
                        ),
                    ),
            )
        }
    }
}

/**
 * 滚动歌词：当前行高亮居中。
 *
 * 移植 iOS `LyricsSection`：按（叠加偏移后的）进度二分查找当前行、只有当前行显示翻译、
 * 其余行按与当前行的距离淡出（距离越远越暗，模仿网易云的层次）。
 * `作词:` / `作曲:` / `编曲:` 这类制作人信息在接口里就是普通歌词行，因此同样渲染为
 * 普通歌词行（与参考图一致），不做特殊标记。
 *
 * 长按复制全部歌词（对应参考图里「长按屏幕分享歌词」的提示）；
 * 单击某一行仍然跳转到该行（原有行为，未改动）。
 */
@Composable
fun LyricsSection(
    lyrics: List<LyricLine>,
    errorText: String?,
    progress: Double,
    userOffset: Double,
    showTranslation: Boolean,
    fontSize: Float,
    align: String,
    onSeek: (LyricLine) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val colors = BeansTheme.colors
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val textAlign = when (align) {
        "left" -> TextAlign.Start
        "right" -> TextAlign.End
        else -> TextAlign.Center
    }

    if (lyrics.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = errorText ?: beansLocalized("暂无歌词", "No lyrics"),
                color = colors.comment,
                fontSize = 14.sp,
            )
        }
        return
    }

    val effective = LyricTiming.effectiveProgress(progress, userOffset)
    val currentIndex = remember(lyrics, effective) { currentLyricIndex(lyrics, effective) }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < lyrics.size) {
            // 让当前行保持在垂直中线附近。
            //
            // 拖动进度条 / 点歌词跳转时目标行可能离当前行很远，逐行动画会排队几百毫秒以上；
            // 因此近距离用动画、远距离直接定位（观感与网易云一致：跳转不拖泥带水）。
            val distance = kotlin.math.abs(currentIndex - listState.firstVisibleItemIndex)
            runCatching {
                if (distance > 8) {
                    listState.scrollToItem(index = currentIndex, scrollOffset = -200)
                } else {
                    listState.animateScrollToItem(index = currentIndex, scrollOffset = -200)
                }
            }
        }
    }

    Column(modifier = modifier) {
        if (hint != null) {
            Text(
                text = hint,
                color = colors.comment.copy(alpha = 0.65f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(lyrics) {
                    detectTapGestures(
                        onLongPress = {
                            clipboard.setText(AnnotatedString(lyrics.joinToString("\n") { it.text }))
                            BeansHaptics.tap()
                            BeansToastCenter.show(
                                beansLocalized("歌词已复制", "Lyrics copied"),
                            )
                        },
                    )
                },
            contentPadding = PaddingValues(vertical = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(lyrics) { index, line ->
                val isCurrent = index == currentIndex
                val distance = kotlin.math.abs(index - currentIndex)
                // 越靠近当前行越亮：0 / 1 / 2 / 3 / 4 / ≥5 共 6 档，模仿网易云的渐变层次。
                val alpha = when (distance) {
                    0 -> 1f
                    1 -> 0.62f
                    2 -> 0.42f
                    3 -> 0.28f
                    4 -> 0.20f
                    else -> 0.14f
                }
                val interaction = remember(line.id) { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSeek(line) }
                        .padding(vertical = 9.dp, horizontal = 12.dp),
                    horizontalAlignment = when (textAlign) {
                        TextAlign.Start -> Alignment.Start
                        TextAlign.End -> Alignment.End
                        else -> Alignment.CenterHorizontally
                    },
                ) {
                    Text(
                        text = line.text.ifBlank { "♪" },
                        color = if (isCurrent) colors.accent else colors.label,
                        fontSize = (if (isCurrent) fontSize + 4f else fontSize).sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = textAlign,
                        lineHeight = ((if (isCurrent) fontSize + 4f else fontSize) * 1.35f).sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { this.alpha = alpha },
                    )
                    val translation = line.translation
                    if (isCurrent && showTranslation && !translation.isNullOrEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = translation,
                            color = colors.comment,
                            fontSize = (fontSize - 3f).sp,
                            textAlign = textAlign,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** 二分查找最后一个时间戳已过的行。 */
private fun currentLyricIndex(lyrics: List<LyricLine>, progress: Double): Int {
    if (lyrics.isEmpty()) return -1
    var low = 0
    var high = lyrics.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (lyrics[mid].time <= progress) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/** 按曲目来源拉取歌词。 */
private suspend fun loadLyrics(song: Song): List<LyricLine> = when (song.source) {
    SongSource.NET_EASE -> {
        val result = NetEaseApi.lyricWithTranslation(song.id)
        LyricParser.parse(result.lrc.orEmpty(), result.tlyric)
    }

    SongSource.QQ -> {
        val lrc = song.qqMid?.let { QQMusicApi.lyric(it) }
        LyricParser.parse(lrc.orEmpty())
    }

    SongSource.KUGOU -> {
        val hash = song.kugouHash
        if (hash.isNullOrBlank()) emptyList()
        else LyricParser.parse(KugouMusicApi.lyric(hash, song.duration))
    }
}

/**
 * 评论数缓存（**可选**功能）。
 *
 * 参考图里的点赞数（`650w+`）在本项目中没有任何数据源：`Song` 模型没有点赞字段，
 * 也没有对应的接口，因此点赞只画图标不画数字，绝不臆造数据。
 * 评论数则可以从既有的 [NetEaseApi.songComments] 的 `total` 拿到，成本很低（每首歌一次），
 * 于是按曲目 id 做进程内缓存，失败时静默返回 null。
 */
private object PlayerCommentCounts {

    private val cache = mutableMapOf<Long, Int?>()

    suspend fun count(song: Song): Int? {
        synchronized(cache) { cache[song.id]?.let { return it } }
        val total = runCatching { NetEaseApi.songComments(song.id, limit = 1, offset = 0).total }
            .getOrNull()
        synchronized(cache) { cache[song.id] = total }
        return total
    }

    fun showToast(count: Int?) {
        BeansToastCenter.show(
            if (count != null) {
                beansLocalized("共 $count 条评论", "$count comments")
            } else {
                beansLocalized("评论加载中", "Loading comments")
            },
        )
    }
}

/**
 * 「更多」面板：把改版前底部文字按钮里的入口集中到一处。
 *
 * 包含版式选择、倍速、定时关闭与播放来源 —— 功能没有删减，
 * 只是把常驻控件让位给参考图要求的 5 个底部小图标。
 */
@Composable
private fun PlayerMoreSheet(
    sourceText: String,
    speed: Float,
    sleepRemaining: Long,
    layout: PlayerLayout,
    onOpenLayouts: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleep: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BeansTheme.colors
    BeansBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = beansLocalized("更多", "More"),
                color = colors.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            if (sourceText.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = beansLocalized("播放来源：$sourceText", "Source: $sourceText"),
                    color = colors.comment,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(14.dp))

            PlayerMoreRow(
                icon = Icons.Rounded.Tune,
                title = beansLocalized("播放器布局", "Player layout"),
                trailing = layout.displayName,
                onClick = onOpenLayouts,
            )
            PlayerMoreRow(
                icon = Icons.Rounded.Mic,
                title = beansLocalized("播放速度", "Playback speed"),
                trailing = "${String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')}x",
                onClick = onOpenSpeed,
            )
            PlayerMoreRow(
                icon = Icons.Rounded.GraphicEq,
                title = beansLocalized("定时关闭", "Sleep timer"),
                trailing = if (sleepRemaining > 0) {
                    formatPlayerTime(sleepRemaining / 1000.0)
                } else {
                    beansLocalized("关闭", "Off")
                },
                onClick = onOpenSleep,
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun PlayerMoreRow(
    icon: ImageVector,
    title: String,
    trailing: String,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.98f,
                pressedBrightness = 0f,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            color = colors.label,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = trailing,
            color = colors.comment,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/**
 * `歌曲百科` 面板。
 *
 * 参考图里的百科是网易云的一个网页/接口；本项目没有该数据源，
 * 因此这里只呈现**已有且真实**的元信息（专辑 / 来源 / 时长 / 当前音质），
 * 不编造任何百科正文。
 */
@Composable
private fun SongWikiSheet(song: Song?, qualityLabel: String, onDismiss: () -> Unit) {
    val colors = BeansTheme.colors
    BeansBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(
                text = beansLocalized("歌曲百科", "Song wiki"),
                color = colors.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))

            if (song == null) {
                Text(
                    text = beansLocalized("未在播放", "Nothing playing"),
                    color = colors.comment,
                    fontSize = 13.sp,
                )
            } else {
                SongWikiRow(beansLocalized("歌曲", "Title"), song.name)
                SongWikiRow(beansLocalized("歌手", "Artist"), song.artists.ifBlank { "—" })
                SongWikiRow(beansLocalized("专辑", "Album"), song.album.ifBlank { "—" })
                SongWikiRow(beansLocalized("来源", "Source"), sourceLabel(song.source))
                SongWikiRow(
                    beansLocalized("时长", "Duration"),
                    if (song.duration > 0) song.formattedDuration else "—",
                )
                SongWikiRow(beansLocalized("音质", "Quality"), qualityLabel)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SongWikiRow(label: String, value: String) {
    val colors = BeansTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = colors.comment,
            fontSize = 13.sp,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            color = colors.label,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QueueSheetContent(
    queue: List<Song>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val colors = BeansTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = String.format(
                    beansLocalized("播放队列 (%d)", "Queue (%d)"),
                    queue.size,
                ),
                color = colors.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = beansLocalized("清空", "Clear"),
                color = colors.accent,
                fontSize = 13.sp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClear,
                ),
            )
        }
        LazyColumn(modifier = Modifier.height(420.dp)) {
            itemsIndexed(queue) { index, item ->
                val isCurrent = index == currentIndex
                val interaction = remember(item.identityKey) { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelect(index) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BeansCoverImage(url = item.coverURL, size = 40.dp, cornerRadius = 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            color = if (isCurrent) colors.accent else colors.label,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.artists,
                            color = colors.comment,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = beansLocalized("移除", "Remove"),
                        color = colors.comment,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onRemove(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedSheetContent(current: Float, onPick: (Float) -> Unit) {
    val colors = BeansTheme.colors
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = beansLocalized("播放速度", "Playback speed"),
            color = colors.label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { v ->
            val selected = kotlin.math.abs(current - v) < 0.01f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(v) }
                    .padding(vertical = 11.dp),
            ) {
                Text(
                    text = "${String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')}x",
                    color = if (selected) colors.accent else colors.label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun SleepSheetContent(onPick: (Int) -> Unit) {
    val colors = BeansTheme.colors
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = beansLocalized("定时关闭", "Sleep timer"),
            color = colors.label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        listOf(0, 15, 30, 45, 60, 90).forEach { minutes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(minutes) }
                    .padding(vertical = 11.dp),
            ) {
                Text(
                    text = if (minutes == 0) {
                        beansLocalized("关闭定时", "Turn off")
                    } else {
                        String.format(beansLocalized("%d 分钟", "%d min"), minutes)
                    },
                    color = colors.label,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private fun sourceLabel(source: SongSource?): String = when (source) {
    SongSource.NET_EASE -> "网易云音乐"
    SongSource.QQ -> "QQ 音乐"
    SongSource.KUGOU -> "酷狗音乐"
    null -> ""
}

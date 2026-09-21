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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
            // ---- 顶栏 -----------------------------------------------------
            PlayerTopBar(
                sourceText = sourceLabel(current?.source),
                queueOpen = showQueue,
                onDismiss = onDismiss,
                onOpenLayouts = { showLayouts = true },
                onOpenQueue = { showQueue = true },
            )

            // ---- 中间内容（四种版式） ---------------------------------------
            when (layout) {
                PlayerLayout.VINYL -> {
                    // 信息行放在唱盘上方（与参考图一致：歌名 / 关注 / 红心在最上面）。
                    PlayerNowPlayingRow(
                        song = current,
                        isLiked = isLiked,
                        likeCount = null,
                        commentCount = commentCount,
                        foreground = PlayerOnVinylLabel,
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
                        onToggleFavorite = toggleFavorite,
                        onComment = { PlayerCommentCounts.showToast(commentCount) },
                    )
                    Spacer(Modifier.weight(1f))
                }

                // ---- 歌词优先：整屏歌词，底部仍是同一套信息行 / 进度 / 控制 -----
                PlayerLayout.LYRICS -> {
                    PlayerNowPlayingRow(
                        song = current,
                        isLiked = isLiked,
                        compact = true,
                        likeCount = null,
                        commentCount = commentCount,
                        foreground = PlayerOnVinylLabel,
                        onToggleFavorite = toggleFavorite,
                        onComment = { PlayerCommentCounts.showToast(commentCount) },
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
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

                    if (!showLyrics) {
                        PlayerInfoRow(
                            song = current,
                            isLiked = isLiked,
                            likeCount = null,
                            commentCount = commentCount,
                            onToggleFavorite = toggleFavorite,
                            onComment = { PlayerCommentCounts.showToast(commentCount) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            // ---- 进度（左时间 / 中音质 / 右总时长） --------------------------
            PlayerProgressRow(
                positionSeconds = progressSeconds,
                durationSeconds = durationSeconds,
                qualityLabel = qualityLabel,
                onSeek = { PlaybackController.seekTo((it * 1000).toLong()) },
            )

            Spacer(Modifier.height(6.dp))

            // ---- 传输控制 ---------------------------------------------------
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

            Spacer(Modifier.height(10.dp))

            // ---- 次要操作 ---------------------------------------------------
            PlayerSecondaryActions(
                speed = speed,
                sleepRemaining = sleepRemaining,
                lyricsActive = lyricsMode,
                downloaded = current?.let { downloadTasks[it.identityKey] }
                    ?.state == DownloadManager.State.DONE,
                onDownload = {
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
                },
                onOpenSpeed = { showSpeed = true },
                onOpenSleep = { showSleep = true },
                onToggleLyrics = {
                    if (layout == PlayerLayout.LYRICS) {
                        // 歌词优先版式：点了就切回封面版式，避免被困在整屏歌词里。
                        SettingsStore.setPlayerLayout(PlayerLayout.COVER.raw)
                    } else {
                        showLyrics = !showLyrics
                    }
                },
            )
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

/** 顶栏：收起 / 来源 / 版式切换 / 队列。 */
@Composable
private fun PlayerTopBar(
    sourceText: String,
    queueOpen: Boolean,
    onDismiss: () -> Unit,
    onOpenLayouts: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val colors = BeansTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BeansGlassIconButton(
            icon = Icons.Rounded.KeyboardArrowDown,
            onClick = onDismiss,
            size = 40.dp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = sourceText,
            color = colors.comment,
            fontSize = 12.sp,
        )
        Spacer(Modifier.weight(1f))
        BeansGlassIconButton(
            icon = Icons.Rounded.Tune,
            onClick = {
                BeansHaptics.tap()
                onOpenLayouts()
            },
            size = 40.dp,
        )
        Spacer(Modifier.width(8.dp))
        BeansGlassIconButton(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            onClick = onOpenQueue,
            size = 40.dp,
            active = queueOpen,
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
 * 专辑封面版式的信息行：保留原来的居中排版（歌名 / VIP / 歌手），
 * 右侧补上「红心（可切换收藏）」与「评论气泡」，与参考图一致。
 */
@Composable
private fun PlayerInfoRow(
    song: Song?,
    isLiked: Boolean,
    likeCount: Int?,
    commentCount: Int?,
    onToggleFavorite: () -> Unit,
    onComment: () -> Unit,
) {
    val colors = BeansTheme.colors

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = song?.name ?: beansLocalized("未在播放", "Nothing playing"),
                color = colors.label,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song?.artists.orEmpty(),
                    color = colors.comment,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song?.isVIP == true) {
                    Spacer(Modifier.width(6.dp))
                    BeansVIPBadge(text = "VIP")
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        BeansGlassIconButton(
            icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            onClick = onToggleFavorite,
            size = 38.dp,
            active = isLiked,
        )
        Spacer(Modifier.width(6.dp))
        BeansGlassIconButton(
            icon = Icons.Rounded.ChatBubbleOutline,
            onClick = onComment,
            size = 38.dp,
        )
    }
}

/** 传输控制行：随机 / 上一首 / 播放暂停 / 下一首 / 循环。 */
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BeansGlassIconButton(
            icon = Icons.Rounded.Shuffle,
            onClick = onToggleShuffle,
            size = 42.dp,
            active = shuffle,
        )
        BeansGlassIconButton(
            icon = Icons.Rounded.SkipPrevious,
            onClick = onPrevious,
            size = 46.dp,
        )
        PlayPauseButton(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            onClick = onTogglePlayPause,
        )
        BeansGlassIconButton(
            icon = Icons.Rounded.SkipNext,
            onClick = onNext,
            size = 46.dp,
        )
        BeansGlassIconButton(
            icon = if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            onClick = onCycleRepeat,
            size = 42.dp,
            active = repeatMode != RepeatMode.OFF,
        )
    }
}

/** 次要操作行：下载 / 倍速 / 定时关闭 / 歌词。 */
@Composable
private fun PlayerSecondaryActions(
    speed: Float,
    sleepRemaining: Long,
    lyricsActive: Boolean,
    downloaded: Boolean,
    onDownload: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleep: () -> Unit,
    onToggleLyrics: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BeansGlassIconButton(
            icon = if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
            onClick = onDownload,
            size = 40.dp,
            active = downloaded,
        )
        PlayerTextButton(
            label = "${String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')}x",
            active = kotlin.math.abs(speed - 1f) > 0.01f,
            onClick = onOpenSpeed,
        )
        PlayerTextButton(
            label = if (sleepRemaining > 0) formatPlayerTime(sleepRemaining / 1000.0) else "Z",
            active = sleepRemaining > 0,
            onClick = onOpenSleep,
        )
        PlayerTextButton(
            label = beansLocalized("词", "Ly"),
            active = lyricsActive,
            onClick = onToggleLyrics,
        )
    }
}

/** 紧凑圆形文字按钮（倍速 / 定时 / 歌词）。 */
@Composable
private fun PlayerTextButton(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) colors.accent.copy(alpha = 0.16f) else colors.glassFill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) colors.accent else colors.label,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, isBuffering: Boolean, onClick: () -> Unit) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(66.dp)
            .clip(RoundedCornerShape(33.dp))
            .background(colors.accent.copy(alpha = 0.18f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            androidx.compose.material3.CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(26.dp),
            )
        } else {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(38.dp),
            )
        }
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
 * 其余行按与当前行的距离淡出。
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
            runCatching {
                listState.animateScrollToItem(
                    index = currentIndex,
                    scrollOffset = -200,
                )
            }
        }
    }

    Column(modifier = modifier) {
        if (hint != null) {
            Text(
                text = hint,
                color = colors.comment,
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
                val alpha = when {
                    isCurrent -> 1f
                    distance == 1 -> 0.55f
                    distance == 2 -> 0.32f
                    else -> 0.18f
                }
                val interaction = remember(line.id) { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSeek(line) }
                        .padding(vertical = 7.dp, horizontal = 12.dp),
                    horizontalAlignment = when (textAlign) {
                        TextAlign.Start -> Alignment.Start
                        TextAlign.End -> Alignment.End
                        else -> Alignment.CenterHorizontally
                    },
                ) {
                    Text(
                        text = line.text.ifBlank { "♪" },
                        color = if (isCurrent) colors.accent else colors.label,
                        fontSize = (if (isCurrent) fontSize + 3f else fontSize).sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = textAlign,
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

package com.lulu.music.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansCoverImage
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansVIPBadge
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Full-screen player, port of the iOS `PlayerView` core: cover art, transport, seek bar, favourite,
 * shuffle/repeat, speed, sleep timer, queue, and the scrolling lyric sheet with translation.
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

    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricError by remember { mutableStateOf<String?>(null) }
    var isLiked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val current = song

    // Load lyrics whenever the track changes.
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

    val progressSeconds = positionMs / 1000.0
    val durationSeconds = if (durationMs > 0) durationMs / 1000.0 else (current?.duration ?: 0.0)

    Box(modifier = Modifier.fillMaxSize()) {
        // Ambient background derived from the artwork.
        PlayerBackground(coverURL = current?.coverURL)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- top bar -------------------------------------------------
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
                    text = sourceLabel(current?.source),
                    color = colors.comment,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                BeansGlassIconButton(
                    icon = Icons.Rounded.PlaylistPlay,
                    onClick = { showQueue = true },
                    size = 40.dp,
                    active = showQueue,
                )
            }

            Spacer(Modifier.weight(1f))

            // ---- cover / lyrics ------------------------------------------
            Crossfade(targetState = showLyrics, label = "coverLyrics") { lyricsMode ->
                if (lyricsMode) {
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

            // ---- title / artists ------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = current?.name ?: beansLocalized("未在播放", "Nothing playing"),
                        color = colors.label,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = current?.artists.orEmpty(),
                            color = colors.comment,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (current?.isVIP == true) {
                            Spacer(Modifier.width(6.dp))
                            BeansVIPBadge(text = "VIP")
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- seek ------------------------------------------------------
            var dragValue by remember { mutableStateOf<Float?>(null) }
            val shown = dragValue ?: progressSeconds.toFloat()
            Slider(
                value = shown.coerceIn(0f, maxOf(durationSeconds.toFloat(), 0.1f)),
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { PlaybackController.seekTo((it * 1000).toLong()) }
                    dragValue = null
                },
                valueRange = 0f..maxOf(durationSeconds.toFloat(), 0.1f),
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.comment.copy(alpha = 0.28f),
                ),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatTime(shown.toDouble()),
                    color = colors.comment,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(durationSeconds),
                    color = colors.comment,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(6.dp))

            // ---- transport -------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BeansGlassIconButton(
                    icon = Icons.Rounded.Shuffle,
                    onClick = {
                        BeansHaptics.tap()
                        PlaybackController.setShuffle(!shuffle)
                    },
                    size = 42.dp,
                    active = shuffle,
                )
                BeansGlassIconButton(
                    icon = Icons.Rounded.SkipPrevious,
                    onClick = {
                        BeansHaptics.tap()
                        PlaybackController.previous()
                    },
                    size = 46.dp,
                )
                PlayPauseButton(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onClick = {
                        BeansHaptics.tap()
                        PlaybackController.togglePlayPause()
                    },
                )
                BeansGlassIconButton(
                    icon = Icons.Rounded.SkipNext,
                    onClick = {
                        BeansHaptics.tap()
                        PlaybackController.next()
                    },
                    size = 46.dp,
                )
                BeansGlassIconButton(
                    icon = if (repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    onClick = {
                        BeansHaptics.tap()
                        PlaybackController.cycleRepeatMode()
                    },
                    size = 42.dp,
                    active = repeatMode != RepeatMode.OFF,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ---- secondary actions -----------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BeansGlassIconButton(
                    icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    onClick = {
                        BeansHaptics.tap()
                        val target = current ?: return@BeansGlassIconButton
                        // Optimistic UI; FavoritesStore rolls NetEase back if the cloud rejects it.
                        isLiked = !isLiked
                        scope.launch {
                            val ok = FavoritesStore.toggle(target)
                            isLiked = FavoritesStore.isLiked(target)
                            if (!ok) {
                                BeansToastCenter.show(
                                    beansLocalized("收藏失败", "Could not update favourite"),
                                )
                            }
                        }
                    },
                    size = 40.dp,
                    active = isLiked,
                )
                val dlTask = current?.let { downloadTasks[it.identityKey] }
                val downloaded = dlTask?.state == DownloadManager.State.DONE
                BeansGlassIconButton(
                    icon = if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                    onClick = {
                        BeansHaptics.tap()
                        val target = current ?: return@BeansGlassIconButton
                        if (DownloadManager.isDownloaded(target)) {
                            DownloadManager.delete(target)
                            BeansToastCenter.show(
                                beansLocalized("已删除下载", "Download removed"),
                            )
                        } else {
                            DownloadManager.download(target)
                            BeansToastCenter.show(
                                beansLocalized("开始下载", "Download started"),
                            )
                        }
                    },
                    size = 40.dp,
                    active = downloaded,
                )
                PlayerTextButton(
                    label = "${String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')}x",
                    active = kotlin.math.abs(speed - 1f) > 0.01f,
                    onClick = { showSpeed = true },
                )
                PlayerTextButton(
                    label = if (sleepRemaining > 0) formatTime(sleepRemaining / 1000.0) else "Z",
                    active = sleepRemaining > 0,
                    onClick = { showSleep = true },
                )
                PlayerTextButton(
                    label = beansLocalized("词", "Ly"),
                    active = showLyrics,
                    onClick = { showLyrics = !showLyrics },
                )
            }
        }
    }

    // ---- sheets ----------------------------------------------------------
    if (showQueue) {
        BeansBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueSheetContent(
                queue = queue,
                currentIndex = queueIndex,
                onSelect = { index ->
                    BeansHaptics.tap()
                    PlaybackController.play(queue, index)
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

/** Compact circular text button (speed / sleep / lyrics toggles). */
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

/** Ambient gradient + blurred artwork behind the player. */
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
 * Scrolling lyrics with the current line highlighted and centred.
 *
 * Port of the iOS `LyricsSection`: the current line is found by binary search over the effective
 * (offset-applied) progress, only the current line shows its translation, and non-current lines
 * fade with distance from the focus line.
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
) {
    val colors = BeansTheme.colors
    val listState = rememberLazyListState()

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
            // Keep the active line vertically centred.
            runCatching {
                listState.animateScrollToItem(
                    index = currentIndex,
                    scrollOffset = -200,
                )
            }
        }
    }

    val textAlign = when (align) {
        "left" -> TextAlign.Start
        "right" -> TextAlign.End
        else -> TextAlign.Center
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 150.dp),
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

/** Binary search for the last line whose timestamp has passed. */
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

/** Fetch lyrics for whichever platform the track came from. */
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

private fun formatTime(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

package com.lulu.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.source.PlaybackSource
import com.lulu.music.data.source.UnblockService
import com.lulu.music.data.source.UnblockSourceStore
import com.lulu.music.data.store.CrashLog
import com.lulu.music.ui.components.BeansToastCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Repeat modes exposed to the UI. */
enum class RepeatMode { OFF, ALL, ONE }

/** 播放失败的可读现场：写进 [CrashLog]，用户可在 设置 → 崩溃日志 里直接看到原因。 */
private class PlaybackFailure(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * App-side playback facade.
 *
 * Owns queue bookkeeping and mirrors the MediaSession's state into Flows the Compose UI can collect.
 * The actual playback lives in [BeansPlayerService]; this object talks to it through a [MediaController]
 * so playback continues with the UI gone (background playback + lock-screen controls).
 *
 * Songs are enqueued as lazily-resolved `beans://song` items, so the whole queue is visible to the
 * media session (next/previous work) without resolving every stream URL up front.
 */
object PlaybackController {

    private const val EXTRA_SONG = "beans.song"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private var controller: MediaController? = null
    private var positionTicker: Job? = null
    private var sleepJob: Job? = null

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(0)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Remaining sleep-timer time in ms; 0 when no timer is armed. */
    private val _sleepRemainingMs = MutableStateFlow(0L)
    val sleepRemainingMs: StateFlow<Long> = _sleepRemainingMs.asStateFlow()

    private var initialised = false

    /**
     * 本轮队列里已经走过后备重试的歌曲（identityKey）。
     *
     * 官方地址「拿得到但播不了」（VIP 试听片段、过期直链、CDN 403/404、空音频）时，
     * 只有真的播放失败才知道，所以后备重试发生在播放错误之后；这里记录已重试过的歌，
     * 保证同一首歌在同一轮队列里最多重试一次，坏掉的歌不会无限循环。
     */
    private val retriedViaFallback = HashSet<String>()

    /** 最近一次后备重试的歌曲；用来区分「换歌」和「重试原地替换当前条目」。 */
    private var lastFallbackKey: String? = null

    /** 正在解析中的歌曲；避免同一次失败触发多次解析。 */
    private var fallbackInFlightKey: String? = null

    /** Idempotent. Safe to call from Activity#onCreate. */
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, BeansPlayerService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                runCatching { future.get() }.onSuccess { c ->
                    controller = c
                    c.addListener(playerListener)
                    syncFromController()
                    syncPreferencesInto(c)
                }
            },
            androidx.core.content.ContextCompat.getMainExecutor(appContext),
        )
        startPositionTicker()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentSong.value = mediaItem?.let(::songFromMediaItem)
            _queueIndex.value = controller?.currentMediaItemIndex ?: 0
            // 后备重试是把当前条目原地换成直链（mediaId 不变），那不是「换歌」；
            // 真正切到别的歌时才算新一轮，清掉重试记录让下一首也能享受后备。
            val id = mediaItem?.mediaId
            if (id == null || id != lastFallbackKey) {
                retriedViaFallback.clear()
                lastFallbackKey = null
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startPositionTicker()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 监听器里绝不向外抛异常：任何意外只写进崩溃日志。
            runCatching { onPlaybackError(error) }
                .onFailure { runCatching { CrashLog.write(it) } }
        }
    }

    // ---- 播放失败 → 第三方音源后备 -----------------------------------------

    /**
     * 官方接口返回「非空但不可用」的地址（VIP 试听片段、过期直链、CDN 403/404、空音频）时，
     * `MediaResolver` 无从判断，只有播放真的失败才知道。这里对齐 iOS `PlayerManager`：
     * 失败后再去问第三方音源，拿到直链就用**直接地址**重播同一首歌（不再走 `beans://`，
     * 否则占位 URI 会重新解析回那个坏掉的官方地址）。
     */
    private fun onPlaybackError(error: PlaybackException) {
        val c = controller ?: return
        val code = error.errorCodeName
        val detail = error.message?.takeIf { it.isNotBlank() } ?: "无详细信息"
        val song = c.currentMediaItem?.let(::songFromMediaItem) ?: _currentSong.value
        if (song == null) {
            CrashLog.write(PlaybackFailure("播放失败（当前没有歌曲）：错误码=$code｜详情=$detail", error))
            return
        }
        val key = song.identityKey

        // 1) 先留现场：以前失败是完全静默的，用户/开发者都无从查起。
        CrashLog.write(
            PlaybackFailure(
                "播放失败：${song.name} - ${song.artists}｜歌曲=$key｜错误码=$code｜详情=$detail",
                error,
            ),
        )

        // 2) 设备本地文件没有第三方解析可言，直接报错。
        if (song.localUri != null) {
            reportUnplayable(song, "设备本地文件播放失败")
            return
        }

        val playbackSource = PlaybackSource.fromKey(SettingsStore.playbackSource.value)
        val enabledSources = UnblockSourceStore.enabledSources
        if (playbackSource == PlaybackSource.OFFICIAL) {
            reportUnplayable(song, "播放来源=仅官方，不尝试第三方音源")
            return
        }
        if (enabledSources.isEmpty()) {
            reportUnplayable(song, "没有已启用的第三方音源")
            return
        }
        if (key in retriedViaFallback || fallbackInFlightKey == key) {
            reportUnplayable(song, "第三方后备地址也无法播放")
            return
        }

        val resumeMs = c.currentPosition.coerceAtLeast(0L)
        val quality = BeansAudioQuality.fromRaw(SettingsStore.audioQuality.value)
        retriedViaFallback.add(key)
        lastFallbackKey = key
        fallbackInFlightKey = key

        scope.launch {
            val resolved = runCatching { UnblockService.resolveStream(song, quality) }.getOrNull()
            if (fallbackInFlightKey == key) fallbackInFlightKey = null
            val url = resolved?.url?.takeIf { it.isNotBlank() }
            if (url == null) {
                reportUnplayable(song, "第三方音源没有解析出可用地址")
                return@launch
            }
            // 解析期间用户可能已经切歌 / 清空队列，这时不要动播放器。
            val live = controller
            if (live !== c || live.currentMediaItem?.mediaId != key) return@launch

            // 直链本身可能需要音源自己的请求头（UA / Referer / Cookie / X-*）。
            MediaResolver.rememberStreamHeaders(url, resolved?.headers)

            val index = live.currentMediaItemIndex
            runCatching {
                // 改动播放器一律回到主线程（当前协程已经在主线程，可立即执行）。
                onMain {
                    live.replaceMediaItem(index, directMediaItemFor(song, url))
                    live.prepare()
                    if (resumeMs > 0) live.seekTo(index, resumeMs)
                    live.play()
                }
            }.onSuccess {
                CrashLog.write(
                    PlaybackFailure("第三方音源后备重试：${song.name} - ${song.artists}｜歌曲=$key｜已从 ${resumeMs}ms 继续"),
                )
            }.onFailure { failure ->
                reportUnplayable(song, "切换第三方地址失败：${failure.message ?: failure}")
            }
        }
    }

    /** 兜底反馈：一句中文提示 + 一条可读的崩溃日志。 */
    private fun reportUnplayable(song: Song, reason: String) {
        runCatching { CrashLog.write(PlaybackFailure("无法播放：${song.name} - ${song.artists}｜${song.identityKey}｜$reason")) }
        onMain { BeansToastCenter.show("该歌曲无法播放") }
    }

    private fun syncFromController() {
        val c = controller ?: return
        _isPlaying.value = c.isPlaying
        _isBuffering.value = c.playbackState == Player.STATE_BUFFERING
        _durationMs.value = c.duration.takeIf { it > 0 } ?: _durationMs.value
        _queueIndex.value = c.currentMediaItemIndex.coerceAtLeast(0)
        _repeatMode.value = when (c.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        _shuffle.value = c.shuffleModeEnabled
        _speed.value = c.playbackParameters.speed
        _currentSong.value = c.currentMediaItem?.let(::songFromMediaItem) ?: _currentSong.value
        if (_queue.value.size != c.mediaItemCount) {
            _queue.value = (0 until c.mediaItemCount).mapNotNull { songFromMediaItem(c.getMediaItemAt(it)) }
        }
    }

    private fun syncPreferencesInto(c: MediaController) {
        val repeat = SettingsStore.repeatMode.value
        c.repeatMode = when (repeat) {
            "one" -> Player.REPEAT_MODE_ONE
            "all" -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        c.shuffleModeEnabled = SettingsStore.shuffle.value
        c.setPlaybackSpeed(SettingsStore.playbackSpeed.value)
    }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (true) {
                val c = controller
                if (c != null) {
                    _positionMs.value = c.currentPosition.coerceAtLeast(0)
                    val d = c.duration
                    if (d > 0) _durationMs.value = d
                    _isBuffering.value = c.playbackState == Player.STATE_BUFFERING
                }
                delay(if (_isPlaying.value) 250L else 1000L)
            }
        }
    }

    // ---- media item <-> song ------------------------------------------------

    private fun mediaItemFor(song: Song): MediaItem {
        // Device-local tracks bypass the platform resolvers entirely.
        val uri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: SongUri.encode(song)
        return mediaItemFor(song, uri)
    }

    /**
     * 用第三方直链构造条目；媒体元数据 / extras 与占位条目完全一致，
     * 所以 UI、锁屏（mediaId 不变）与播放历史都照旧。
     */
    private fun directMediaItemFor(song: Song, url: String): MediaItem {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: SongUri.encode(song)
        return mediaItemFor(song, uri)
    }

    private fun mediaItemFor(song: Song, uri: Uri): MediaItem {
        // 占位 URI 只带 id；懒解析时第三方（关键词 / 脚本）音源还要用到歌名与歌手。
        QueuedSongs.remember(song)
        val extras = Bundle().apply {
            runCatching { putString(EXTRA_SONG, json.encodeToString(Song.serializer(), song)) }
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(song.identityKey)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.name)
                    .setArtist(song.artists)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(song.coverURL?.let { runCatching { Uri.parse(it) }.getOrNull() })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun songFromMediaItem(item: MediaItem): Song? {
        item.mediaMetadata.extras?.getString(EXTRA_SONG)?.let { raw ->
            runCatching { json.decodeFromString(Song.serializer(), raw) }.getOrNull()?.let { return it }
        }
        // Fallback for items restored by the system after process death.
        val uri = item.localConfiguration?.uri ?: return null
        if (!SongUri.isSongUri(uri)) {
            // A local file restored without its metadata: keep it playable.
            return Song(
                id = uri.toString().hashCode().toLong(),
                name = item.mediaMetadata.title?.toString().orEmpty(),
                artists = item.mediaMetadata.artist?.toString().orEmpty(),
                album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                source = SongSource.NET_EASE,
                localUri = uri.toString(),
            )
        }
        val decoded = SongUri.decode(uri) ?: return null
        return Song(
            id = decoded.id,
            name = item.mediaMetadata.title?.toString().orEmpty(),
            artists = item.mediaMetadata.artist?.toString().orEmpty(),
            album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            coverURL = item.mediaMetadata.artworkUri?.toString(),
            source = decoded.source,
            qqMid = decoded.qqMid,
            qqMediaMid = decoded.qqMediaMid,
            kugouHash = decoded.kugouHash,
            kugouAlbumAudioId = decoded.kugouAlbumAudioId,
            kugouAlbumId = decoded.kugouAlbumId,
        )
    }

    // ---- transport ---------------------------------------------------------

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun play(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)
        // 新的一轮播放：清掉上一轮的后备重试记录。
        retriedViaFallback.clear()
        lastFallbackKey = null
        fallbackInFlightKey = null
        _queue.value = songs
        _queueIndex.value = index
        _currentSong.value = songs[index]
        onMain {
            val c = controller ?: return@onMain
            c.setMediaItems(songs.map(::mediaItemFor), index, 0L)
            c.prepare()
            c.play()
        }
    }

    /** Replace the queue with a single song, or insert at the end when `enqueue` is true. */
    fun playSong(song: Song, queue: List<Song> = listOf(song), enqueue: Boolean = false) {
        if (enqueue) {
            addToQueue(song)
            return
        }
        play(queue, queue.indexOfFirst { it.identityKey == song.identityKey }.coerceAtLeast(0))
    }

    fun togglePlayPause() = onMain {
        val c = controller ?: return@onMain
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun pause() = onMain { controller?.pause() }

    fun resume() = onMain {
        val c = controller ?: return@onMain
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun next() = onMain { controller?.seekToNextMediaItem() }

    fun previous() = onMain {
        val c = controller ?: return@onMain
        // Match the platform convention: restart the track unless we are near the very start.
        if (c.currentPosition > 3_000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) = onMain { controller?.seekTo(positionMs.coerceAtLeast(0)) }

    fun stop() = onMain { controller?.stop() }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
        SettingsStore.setRepeatMode(
            when (mode) {
                RepeatMode.OFF -> "off"
                RepeatMode.ALL -> "all"
                RepeatMode.ONE -> "one"
            },
        )
        onMain {
            controller?.repeatMode = when (mode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
        }
    }

    fun cycleRepeatMode() {
        setRepeatMode(
            when (_repeatMode.value) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            },
        )
    }

    fun setShuffle(enabled: Boolean) {
        _shuffle.value = enabled
        SettingsStore.setShuffle(enabled)
        onMain { controller?.shuffleModeEnabled = enabled }
    }

    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(0.5f, 3f)
        _speed.value = clamped
        SettingsStore.setPlaybackSpeed(clamped)
        onMain { controller?.setPlaybackSpeed(clamped) }
    }

    // ---- queue editing -----------------------------------------------------

    fun addToQueue(song: Song) {
        _queue.value = _queue.value + song
        onMain { controller?.addMediaItem(mediaItemFor(song)) }
    }

    fun playNext(song: Song) {
        val list = _queue.value.toMutableList()
        val insertAt = (_queueIndex.value + 1).coerceIn(0, list.size)
        list.add(insertAt, song)
        _queue.value = list
        onMain { controller?.addMediaItem(insertAt, mediaItemFor(song)) }
    }

    fun removeFromQueue(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _queue.value = list
        onMain { controller?.removeMediaItem(index) }
    }

    fun clearQueue() {
        retriedViaFallback.clear()
        lastFallbackKey = null
        fallbackInFlightKey = null
        _queue.value = emptyList()
        _currentSong.value = null
        onMain { controller?.clearMediaItems() }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        _queue.value = list
        onMain { controller?.moveMediaItem(from, to) }
    }

    // ---- sleep timer -------------------------------------------------------

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepRemainingMs.value = 0L
            SettingsStore.setSleepTimerMinutes(0)
            return
        }
        SettingsStore.setSleepTimerMinutes(minutes)
        val totalMs = minutes * 60_000L
        sleepJob = scope.launch {
            var remaining = totalMs
            _sleepRemainingMs.value = remaining
            while (remaining > 0) {
                delay(1_000)
                remaining -= 1_000
                _sleepRemainingMs.value = remaining.coerceAtLeast(0)
            }
            pause()
            _sleepRemainingMs.value = 0L
            SettingsStore.setSleepTimerMinutes(0)
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = 0L
        SettingsStore.setSleepTimerMinutes(0)
    }

    /** True when the current song is one the platform flags as VIP/paid — used for badges and hints. */
    fun isCurrentSongVIP(): Boolean = _currentSong.value?.isVIP == true

    fun hasNext(): Boolean {
        val c = controller ?: return false
        return c.hasNextMediaItem() || _repeatMode.value == RepeatMode.ALL
    }

    fun hasPrevious(): Boolean {
        val c = controller ?: return false
        return c.hasPreviousMediaItem()
    }

    private fun SongSource.displayName(): String = when (this) {
        SongSource.NET_EASE -> "网易云音乐"
        SongSource.QQ -> "QQ 音乐"
        SongSource.KUGOU -> "酷狗音乐"
    }
}

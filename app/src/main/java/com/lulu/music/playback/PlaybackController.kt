package com.lulu.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.SettingsStore
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
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startPositionTicker()
        }
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
        val extras = Bundle().apply {
            runCatching { putString(EXTRA_SONG, json.encodeToString(Song.serializer(), song)) }
        }
        // Device-local tracks bypass the platform resolvers entirely.
        val uri = song.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: SongUri.encode(song)
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

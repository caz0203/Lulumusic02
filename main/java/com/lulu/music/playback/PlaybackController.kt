package com.lulu.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.model.beansLocalized
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** Repeat modes exposed to the UI. */
enum class RepeatMode { OFF, ALL, ONE }

/**
 * 底排那颗「播放模式」按钮的 4 个状态（顺序播放 → 列表循环 → 单曲循环 → 随机播放）。
 *
 * 这是**纯状态机**：每个状态和它对应的 `(shuffle, repeatMode)` 一一对应，
 * 不持有任何 Android / Compose 依赖，所以可以直接单元测试（见 `PlayModeCycleTest`）。
 *
 * 为什么有 4 个状态而不是 3 个：用户点名的是「随机 / 顺序 / 单曲循环」三个名字，
 * 但 App 从改造前就有**列表循环**（`RepeatMode.ALL`）这一档。合并成一个按钮时把它删掉
 * 等于悄悄砍掉一个已有能力，所以这里保留它，并放在「顺序播放」之后（这也是 iOS 音乐 App
 * 与国内主流播放器的共同顺序）。
 *
 * 落库仍然走既有的两个入口（`beans.shuffle` / `beans.repeat`），没有新的偏好键。
 *
 * @param shuffleEnabled 该状态对应的 `PlaybackController.shuffle`
 * @param repeat 该状态对应的 [RepeatMode]
 * @param next 循环里的下一个状态（末尾回到开头）
 */
enum class PlayMode(
    val shuffleEnabled: Boolean,
    val repeat: RepeatMode,
) {
    /** 顺序播放：不随机、不循环（放完最后一首就停）。 */
    SEQUENTIAL(shuffleEnabled = false, repeat = RepeatMode.OFF),

    /** 列表循环：不随机，整张列表放完从头再来。 */
    LIST_LOOP(shuffleEnabled = false, repeat = RepeatMode.ALL),

    /** 单曲循环：不随机，一直重播当前这首。 */
    SINGLE_LOOP(shuffleEnabled = false, repeat = RepeatMode.ONE),

    /** 随机播放：随机顺序 + 整张列表循环（随机必然要循环，否则放完随机队列就断了）。 */
    SHUFFLE(shuffleEnabled = true, repeat = RepeatMode.ALL),
    ;

    /** 点一下按钮之后要切到的状态；末尾回到第一个。 */
    val next: PlayMode get() = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * 反查：把任意 `(shuffle, repeatMode)` 组合映射回一个状态。
         *
         * 正常路径上这两支只会由 [next] 写出，所以只会出现 4 种组合；
         * 但用户可以直接改 `beans.shuffle` / `beans.repeat`（备份导入、旧版本升级），
         * 因此这里必须对**任意**组合都有确定结果：随机优先（`shuffle = true` 时 repeat 无意义）。
         */
        fun of(shuffle: Boolean, repeat: RepeatMode): PlayMode = when {
            shuffle -> SHUFFLE
            repeat == RepeatMode.OFF -> SEQUENTIAL
            repeat == RepeatMode.ALL -> LIST_LOOP
            else -> SINGLE_LOOP
        }
    }
}

/** 播放失败的可读现场：写进 [CrashLog]，用户可在 设置 → 崩溃日志 里直接看到原因。 */
private class PlaybackFailure(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * 连接播放服务（MediaSession）失败 / 掉线的可读现场。
 *
 * 和 [PlaybackFailure] 分开是为了让「歌播不了」和「根本连不上播放服务」在崩溃日志里一眼可辨。
 */
private class PlaybackConnectionFailure(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * 一次「还没来得及下发给播放器」的播放请求。
 *
 * 只有最近一次点按有意义，所以新的请求会整体替换掉旧的（见 `rememberPendingPlay`）。
 * [requestedAtMs] 是 [SystemClock.elapsedRealtime]，用来算「等了多久」；
 * [notified] 保证「等待超时」的提示 / 日志只出现一次，绝不刷屏。
 */
private class PendingPlay(
    val songs: List<Song>,
    val index: Int,
    val requestedAtMs: Long,
    var notified: Boolean = false,
)

/**
 * App-side playback facade.
 *
 * Owns queue bookkeeping and mirrors the MediaSession's state into Flows the Compose UI can collect.
 * The actual playback lives in [BeansPlayerService]; this object talks to it through a [MediaController]
 * so playback continues with the UI gone (background playback + lock-screen controls).
 *
 * Songs are enqueued as lazily-resolved `beans://song` items, so the whole queue is visible to the
 * media session (next/previous work) without resolving every stream URL up front.
 *
 * 连接是**自愈**的（修复「点播放没反应，而且一辈子都没反应」）：连接失败会一直退避重试到进程结束，
 * 掉线会自动重连，连上之前用户点下的播放请求会被记成 pending 并补播；万一真的连不上，
 * 3 秒后会提示用户并写进崩溃日志 —— 任何一条失败路径都不会再静默。
 */
object PlaybackController {

    private const val EXTRA_SONG = "beans.song"

    /** 连接重试退避（毫秒）：立刻一次，然后 500ms / 1s / 2s / 4s，之后固定 [CONNECT_STEADY_RETRY_MS]。 */
    private val CONNECT_BACKOFF_MS = longArrayOf(0L, 500L, 1_000L, 2_000L, 4_000L)

    /** 退避用完之后固定每 5 秒重试一次，永不放弃。 */
    private const val CONNECT_STEADY_RETRY_MS = 5_000L

    /** 播放请求等了这么久还没能下发，就提示用户一次（不是每次重试都提示）。 */
    private const val PENDING_PLAY_NOTIFY_MS = 3_000L

    /**
     * 单次连接尝试的等待上限。
     *
     * 正常失败（服务没起来 / 会话被拒 / 绑定被拒）是**立刻**返回的，退避节奏完全按
     * [CONNECT_BACKOFF_MS] 走；这个上限只是兜底：万一某个 future 永远不结算，
     * 也能当作一次失败继续重试，而不是把重试循环永久卡死（那又会变成另一个「永远没声音」）。
     */
    private const val CONNECT_ATTEMPT_TIMEOUT_MS = 15_000L

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

    // ---- 连接状态（自愈）------------------------------------------------------

    /**
     * 一次性初始化的闸门：`init` 只负责「绑定上下文 + 造 SessionToken + 起位置轮询」。
     *
     * 注意它**不是**连接闸门 —— 旧的实现用同一个 `initialised` 把「连接只尝试一次」也锁死了，
     * 于是一次失败就永久没声音。连接由 [connectLoop] 负责，可以无限重试。
     */
    private var boundToContext = false

    /**
     * 测试钩子：置为 false 时 [init] 只绑定上下文与计时器，不连接 `BeansPlayerService`。
     *
     * 生产代码从不修改它（默认 `true`），全仓库唯一的赋值点是 `app/src/test/` 里的
     * `TestBeansApplication`：Robolectric 里没有真正的 `MediaSessionService`，janky 的假
     * `bindService` 会让 media3 在 Espresso 投递的 runnable 里抛 NPE，把整个 Compose 测试
     * 干掉。置 false 后 [controller] 保持 null、不构造 `MediaController.Builder`、不起重试
     * 循环，于是渲染测试只测屏幕本身。
     */
    @androidx.annotation.VisibleForTesting
    internal var connectEnabled: Boolean = true

    private var appContext: Context? = null
    private var sessionToken: SessionToken? = null

    /**
     * 进程内**最多一个**连接重试循环。它非空且 `isActive` 就等于
     * 「有一次连接尝试在飞 / 正在退避等待」，用这个保证尝试绝不重叠。
     *
     * 循环只在 [controller] 非空（连上了）或自己抛错时结束；掉线由 [controllerListener] 再拉起来。
     */
    private var connectLoop: Job? = null

    /** 最近一次「还没能下发」的播放请求；新的点按会替换掉它。 */
    private var pendingPlay: PendingPlay? = null

    /** 「等了太久」提示的定时任务；请求被替换 / 执行 / 清掉时取消。 */
    private var pendingNotifyJob: Job? = null

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

    /**
     * 最近一次真正下发给播放器的队列（identityKey 顺序）与起始下标。
     *
     * `MediaController` 的命令是异步的：`setMediaItems` 的回显要等下一次主线程消息。
     * 同一次用户点按如果在回显之前又走到 [play]（重复 / 嵌套的点击回调），
     * 只凭 controller 的当前状态会判成「不等价」而重建队列、把进度清零。
     * 这条记录用来识别**刚刚下发、还没回显**的同一个请求。
     *
     * 注意：「点的是当前正在播放的那首歌」那条分支（[MediaController.currentItemIs]）
     * 根本不会下发队列，所以不需要这里兜底 —— 它靠 controller 的 mediaId 就能判定。
     *
     * 任何其它改队列的操作（增删 / 移动 / 清空）都会 [forgetPushedQueue]，
     * 所以它只描述「刚下发、尚无回显」的那一次请求，不会退化成陈旧缓存。
     */
    private var pushedQueueKeys: List<String>? = null
    private var pushedIndex: Int = -1

    private fun forgetPushedQueue() {
        pushedQueueKeys = null
        pushedIndex = -1
    }

    /**
     * 幂等，可以从 Activity#onCreate 反复调用。
     *
     * 一次性初始化（上下文 / SessionToken / 位置轮询）只做一次；**连接**则每次都确保在跑：
     * 已经连着、或已经有循环在重试时它什么都不做，否则立刻踢一次（见 [startConnectLoop]）。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        onMain {
            if (!boundToContext) {
                boundToContext = true
                appContext = app
                sessionToken = SessionToken(app, ComponentName(app, BeansPlayerService::class.java))
                startPositionTicker()
            }
            startConnectLoop()
        }
    }

    // ---- 连接播放服务：失败重试 + 掉线自愈 --------------------------------------

    private fun connectBackoffMs(attempt: Int): Long =
        CONNECT_BACKOFF_MS.getOrElse(attempt) { CONNECT_STEADY_RETRY_MS }

    /**
     * 连接 [BeansPlayerService] 的 MediaSession；失败绝不放弃，按退避节奏一直重试到进程结束。
     *
     * 播放服务「暂时不在」是完全可恢复的（通知权限刚被拒、系统正在重启服务、上一次连接被会话拒绝），
     * 所以这里永不 latch 成死状态：立刻试一次 → 500ms → 1s → 2s → 4s → 之后每 5s，直到连上为止。
     * 掉线由 [controllerListener] 把这个循环重新拉起来。
     *
     * 循环跑在主线程（[scope] 是 Dispatchers.Main），同一时刻只有一个尝试在飞，
     * 所以不会出现两个 MediaController 抢同一个会话，也不会互相覆盖状态。
     */
    private fun startConnectLoop() {
        // 测试钩子（默认 true，生产行为不变）：见 connectEnabled 的说明。
        if (!connectEnabled) return
        if (controller != null) return
        if (connectLoop?.isActive == true) return
        connectLoop = scope.launch {
            val self = coroutineContext[Job]
            try {
                var attempt = 0
                while (controller == null) {
                    val waitMs = connectBackoffMs(attempt)
                    if (waitMs > 0L) delay(waitMs)
                    // 退避期间可能已经被别处连上了（例如 init 之后的另一次调用）。
                    if (controller != null) break
                    val connected = try {
                        withTimeoutOrNull(CONNECT_ATTEMPT_TIMEOUT_MS) { awaitController() }
                            ?: throw java.util.concurrent.TimeoutException(
                                "等待播放服务响应超过 ${CONNECT_ATTEMPT_TIMEOUT_MS}ms",
                            )
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        // 每一次失败都留现场：类型 + 消息，用户能在崩溃日志里看到原因。
                        logConnectFailure(attempt + 1, failure)
                        null
                    }
                    // 连上之后 while 条件会自然结束循环；如果 onControllerConnected 里的补播
                    // 又失败、把 controller 丢了，这里会继续退避重试（attempt 继续增长，不会空转）。
                    if (connected != null) onControllerConnected(connected)
                    attempt++
                }
            } finally {
                if (connectLoop === self) connectLoop = null
            }
        }
    }

    /**
     * 建立一次 [MediaController] 连接，并等它出结果。
     *
     * future 完成时回调一定在主线程（[ContextCompat.getMainExecutor]），已完成的 future 会立刻回调，
     * 所以不会阻塞主线程。失败以异常形式抛出，由 [startConnectLoop] 统一 `runCatching` 并记日志。
     */
    private suspend fun awaitController(): MediaController {
        val ctx = appContext ?: throw IllegalStateException("播放上下文尚未初始化（init 还没被调用）")
        val token = sessionToken ?: throw IllegalStateException("MediaSession 令牌尚未创建")
        val future = MediaController.Builder(ctx, token)
            .setListener(controllerListener)
            .buildAsync()
        return suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    val outcome = runCatching { future.get() }
                    if (!continuation.isActive) {
                        // 这次尝试已经被放弃（超时 / 协程取消）：结果没人要了，
                        // 真连上了就把 controller 放掉，别让它悄悄占着会话。
                        outcome.getOrNull()?.let { late -> runCatching { late.release() } }
                        return@addListener
                    }
                    val failure = outcome.exceptionOrNull()
                    if (failure == null) {
                        continuation.resumeWith(outcome)
                    } else {
                        continuation.resumeWith(Result.failure(rootCause(failure)))
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )
        }
    }

    /**
     * Guava 的 future 失败时可能再包一层 `ExecutionException`：
     * 尽量还原最里层的真实原因（`SecurityException` / `RemoteException` / 服务未绑定等）。
     */
    private fun rootCause(failure: Throwable): Throwable {
        val cause = failure.cause
        return if (failure is java.util.concurrent.ExecutionException && cause != null) cause else failure
    }

    /** 连接失败留现场：异常类型 + 消息 + 第几次尝试。 */
    private fun logConnectFailure(attempt: Int, failure: Throwable) {
        runCatching {
            CrashLog.write(
                PlaybackConnectionFailure(
                    "连接播放服务失败（第 $attempt 次尝试）：" +
                        "${failure::class.java.name}：${failure.message ?: "无详细信息"}",
                    failure,
                ),
            )
        }
    }

    /**
     * 新 controller 就绪：接上状态镜像、偏好同步，并把连上之前用户点下的歌补播掉。
     */
    private fun onControllerConnected(c: MediaController) {
        val existing = controller
        if (existing != null && existing !== c) {
            // 理论上不会发生（同一时刻只有一个尝试在飞）；真发生了就保留先到的那个，放掉多余的。
            runCatching { c.release() }
            return
        }
        controller = c
        c.addListener(playerListener)
        // 新 controller 的队列是空的 / 由系统恢复的：旧的「刚下发」记录不再适用。
        forgetPushedQueue()
        syncFromController()
        syncPreferencesInto(c)
        // 连上之前用户点过的那一次播放请求，现在立刻执行。
        executePendingPlay()
    }

    /**
     * MediaController.Listener 的自愈入口：会话掉线（服务被杀 / 被拒绝 / 我们主动 release）时，
     * 清掉 controller 与「刚下发队列」记录，然后重新拉起连接循环。
     *
     * 只处理**当前正在用的那一个** controller：迟到的回调、以及我们自己 `release()` 触发的回调，
     * 都不能把已经建立好的新连接踢掉。
     */
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            runCatching { handleDisconnected(controller) }
                .onFailure { runCatching { CrashLog.write(it) } }
        }
    }

    private fun handleDisconnected(disconnected: MediaController) {
        onMain {
            if (controller !== disconnected) return@onMain
            controller = null
            // 队列记录描述的是那个已经消失的会话，不再适用。
            forgetPushedQueue()
            // 播放器没了，界面不该再显示「正在播放」。
            _isPlaying.value = false
            _isBuffering.value = false
            runCatching {
                CrashLog.write(
                    PlaybackConnectionFailure("播放服务连接已断开（MediaSession 掉线），已开始自动重连"),
                )
            }
            startConnectLoop()
        }
    }

    // ---- 下发不出去的播放请求（pending）----------------------------------------

    /**
     * 记下「现在还下发不出去」的播放请求。
     *
     * 只保留最近一次点按（新的整体替换旧的），并在它等了超过 [PENDING_PLAY_NOTIFY_MS]
     * 仍然没连上时提示用户 / 记日志一次（[PendingPlay.notified] 保证不刷屏）。
     * [alreadyNotified] 用于「已经当场提示过」的路径，避免同一次失败提示两遍。
     */
    private fun rememberPendingPlay(songs: List<Song>, index: Int, alreadyNotified: Boolean = false) {
        if (songs.isEmpty()) return
        val safeIndex = index.coerceIn(0, songs.lastIndex)
        pendingPlay = PendingPlay(songs, safeIndex, SystemClock.elapsedRealtime(), alreadyNotified)
        pendingNotifyJob?.cancel()
        pendingNotifyJob = null
        if (alreadyNotified) return
        pendingNotifyJob = scope.launch {
            delay(PENDING_PLAY_NOTIFY_MS)
            val pending = pendingPlay ?: return@launch
            if (pending.notified || controller != null) return@launch
            pending.notified = true
            val waited = (SystemClock.elapsedRealtime() - pending.requestedAtMs).coerceAtLeast(0L)
            runCatching {
                CrashLog.write(
                    PlaybackConnectionFailure(
                        "播放请求等待播放服务超时：已等待 ${waited}ms 仍未建立 MediaController，" +
                            "队列（${pending.songs.size} 首，起始下标 ${pending.index}）无法下发",
                    ),
                )
            }
            onMain {
                runCatching {
                    BeansToastCenter.show(cannotConnectToast())
                }
            }
        }
    }

    /** 连上之后立刻执行挂起的播放请求；执行完就清掉（补播仍然走 [play]，所以去重逻辑完全一致）。 */
    private fun executePendingPlay() {
        val pending = pendingPlay ?: return
        pendingPlay = null
        pendingNotifyJob?.cancel()
        pendingNotifyJob = null
        runCatching { play(pending.songs, pending.index) }
            .onFailure { runCatching { CrashLog.write(it) } }
    }

    /** 丢掉挂起的播放请求（例如队列被清空：再补播就等于把清掉的队列装回来）。 */
    private fun discardPendingPlay() {
        pendingPlay = null
        pendingNotifyJob?.cancel()
        pendingNotifyJob = null
    }

    /** controller 还不存在时的「播放 / 继续」：把当前队列记成 pending，并踢一次连接。 */
    private fun resumePendingFromCurrentQueue() {
        val songs = _queue.value
        if (songs.isEmpty()) return
        rememberPendingPlay(songs, _queueIndex.value.coerceIn(0, songs.lastIndex))
        startConnectLoop()
    }

    private fun cannotConnectToast(): String =
        beansLocalized("无法连接播放服务，请重试", "Cannot connect to the playback service. Please try again.")

    // ---- 播放器监听 -----------------------------------------------------------

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

    /**
     * 开始播放一轮队列。
     *
     * **幂等保护（修复「点歌进播放页 → 歌曲从头重播」）**：点歌的点击回调在
     * `play(...)` 之后紧接着调用 `PlayerOpenRequest.request()`（见 `ui/PlayerOpenRequest.kt`）
     * 来展开全屏播放器。旧的实现每次都无条件 `setMediaItems(..., startPositionMs = 0)`，
     * 于是只要点到的歌**已经是当前正在播放的那一首**（歌单里高亮的那一行本来就是进播放页
     * 最自然的入口），进度就被清零、歌曲从 0:00 重播 —— 这正是用户报的 bug。
     *
     * 现在有两条「不重建队列」的路径，命中任一条都只补齐「可用 / 正在播放」状态，
     * **绝不重设媒体条目、绝不动播放位置**：
     *  1. 当前媒体条目的 `mediaId` 就是这次请求的那首歌（[MediaController.currentItemIs]）——
     *     不管点的是哪个歌单、队列是否等价。用户明确要求「进播放页不能影响播放进度」，
     *     而且当前条目可能是第三方直链后备（`mediaId` 不变、URI 是直链），重建会把坏掉的
     *     官方占位地址重新解析一遍 —— 保持不动才是对的。
     *  2. 队列等价（identityKey 顺序完全一致），或这次请求就是刚下发、还没回显的那一次
     *     （[isSameQueueAs] / [matchesPushedQueue]）—— 良性的重复点按 / 页面重入。
     *
     * 代价（刻意取舍，见分支 1）：点当前歌不会重启，也**不会把点它的那个歌单变成播放队列**，
     * 上一首 / 下一首仍按原队列走；点一首**不同的**歌则完全照旧（重建队列、从 0:00 开始）。
     * 将来若需要「点当前歌 = 回到 0:00」，应该在调用方的点击回调里显式 `seekTo(0)`，
     * 而不是让 [play] 重建队列。
     */
    fun play(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val index = startIndex.coerceIn(0, songs.lastIndex)
        val keys = songs.map { it.identityKey }
        onMain {
            val c = controller
            if (c != null &&
                (c.currentItemIs(keys, index) || matchesPushedQueue(keys, index) || c.isSameQueueAs(keys, index))
            ) {
                // 队列保持不动：状态从播放器回读 —— 点的是别的歌单时请求的 index
                // 与真实队列下标并不相同，不能把请求值直接写进 _queue / _queueIndex。
                syncFromController()
                when (c.playbackState) {
                    // 队列已播完：只有回到开头才能再播，这不是「丢掉进度」（进度本来就在末尾）。
                    Player.STATE_ENDED -> c.seekTo(0L)
                    // 空闲（stop() 之后）：补齐 prepare，位置保持不动。
                    Player.STATE_IDLE -> c.prepare()
                    else -> Unit
                }
                if (!c.isPlaying) c.play()
                return@onMain
            }
            // 真正的新一轮播放：清掉上一轮的后备重试记录，然后重建队列。
            _queue.value = songs
            _queueIndex.value = index
            _currentSong.value = songs[index]
            retriedViaFallback.clear()
            lastFallbackKey = null
            fallbackInFlightKey = null
            if (c == null) {
                // 还没有 controller（服务没起来 / 掉线了）：把这次请求记下来，连上之后立刻补播。
                // 以前这里是 `c?.setMediaItems(...)` 的空转 —— 点歌毫无反应而且永远不恢复。
                rememberPendingPlay(songs, index)
                startConnectLoop()
                return@onMain
            }
            pushNewQueue(c, songs, index, keys)
        }
    }

    /**
     * 把「新一轮播放」真正下发给播放器。
     *
     * `setMediaItems` / `prepare` / `play` 在 controller 实际已经失效时可能抛异常
     * （会话被拒、服务刚被杀）。这种「点了没反应」绝不再静默：留现场、提示用户、
     * 把请求记成 pending，然后丢掉这个 controller 重连 —— 连上后 [executePendingPlay] 补播。
     */
    private fun pushNewQueue(c: MediaController, songs: List<Song>, index: Int, keys: List<String>) {
        pushedQueueKeys = keys
        pushedIndex = index
        val failure = runCatching {
            c.setMediaItems(songs.map(::mediaItemFor), index, 0L)
            c.prepare()
            c.play()
        }.exceptionOrNull() ?: return
        pushedQueueKeys = null
        pushedIndex = -1
        val song = songs[index]
        runCatching {
            CrashLog.write(
                PlaybackFailure(
                    "下发播放队列失败：${song.name} - ${song.artists}｜共 ${songs.size} 首" +
                        "｜起始下标 $index｜${failure::class.java.name}：${failure.message ?: "无详细信息"}",
                    failure,
                ),
            )
        }
        runCatching { BeansToastCenter.show(cannotConnectToast()) }
        // 这次提示已经给过了，pending 不再重复提示。
        rememberPendingPlay(songs, index, alreadyNotified = true)
        if (controller === c) {
            controller = null
            runCatching { c.release() }
            startConnectLoop()
        }
    }

    /**
     * 播放器当前条目就是这次请求的那首歌吗（只比 `mediaId`，也就是 identityKey）。
     *
     * 队列可以完全不同 —— 用户从搜索结果开始播放、随后在某个歌单里点到同一首歌时就是这样。
     * 命中时 [play] 不重建队列，所以进度不会被清零。
     *
     * 只在主线程读 [MediaController]（由 [play] 的 [onMain] 保证）。
     */
    private fun MediaController.currentItemIs(keys: List<String>, index: Int): Boolean =
        currentMediaItem?.mediaId == keys[index]

    /**
     * 当前播放器是否已经在放 [keys] 里的第 [index] 首，且队列等价
     * （条目数相同、逐条的 `mediaId` 与 identityKey 一致且顺序相同）。
     *
     * 等价即「同一轮队列」：[play] 据此跳过 `setMediaItems`，避免把进度重置为 0。
     * 注意 `shuffled()` 出来的顺序不同会被判为不等价，因此「随机播放」仍然会重建队列（符合预期）。
     *
     * 只在主线程读 [MediaController]（由 [play] 的 [onMain] 保证）。
     */
    private fun MediaController.isSameQueueAs(keys: List<String>, index: Int): Boolean {
        if (currentMediaItemIndex != index) return false
        if (mediaItemCount != keys.size) return false
        for (i in keys.indices) {
            if (getMediaItemAt(i).mediaId != keys[i]) return false
        }
        return true
    }

    /** 这次请求是不是我们刚下发、controller 还没来得及回显的那一次（见 [pushedQueueKeys]）。 */
    private fun matchesPushedQueue(keys: List<String>, index: Int): Boolean =
        pushedIndex == index && pushedQueueKeys == keys

    /** Replace the queue with a single song, or insert at the end when `enqueue` is true. */
    fun playSong(song: Song, queue: List<Song> = listOf(song), enqueue: Boolean = false) {
        if (enqueue) {
            addToQueue(song)
            return
        }
        play(queue, queue.indexOfFirst { it.identityKey == song.identityKey }.coerceAtLeast(0))
    }

    fun togglePlayPause() = onMain {
        val c = controller
        if (c == null) {
            // controller 还没连上：别静默吞掉这次点按，把「继续播放当前队列」挂成 pending。
            resumePendingFromCurrentQueue()
            return@onMain
        }
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun pause() = onMain { controller?.pause() }

    fun resume() = onMain {
        val c = controller
        if (c == null) {
            resumePendingFromCurrentQueue()
            return@onMain
        }
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

    /**
     * 底排那颗按钮的唯一入口：按 [PlayMode] 的固定顺序切到下一个状态。
     *
     * 复用既有的 [setShuffle] / [setRepeatMode]，所以落库键、MediaController 同步、
     * 以及「随机时要不要重建队列」这些既有语义全部不变（没有新的播放器代码路径）。
     * 没有变化的其中一支不重复写偏好，避免每次点击都白写一次 DataStore。
     */
    fun cyclePlayMode() {
        val next = PlayMode.of(_shuffle.value, _repeatMode.value).next
        if (next.shuffleEnabled != _shuffle.value) setShuffle(next.shuffleEnabled)
        if (next.repeat != _repeatMode.value) setRepeatMode(next.repeat)
    }

    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(0.5f, 3f)
        _speed.value = clamped
        SettingsStore.setPlaybackSpeed(clamped)
        onMain { controller?.setPlaybackSpeed(clamped) }
    }

    // ---- queue editing -----------------------------------------------------

    fun addToQueue(song: Song) {
        // 队列被改动后，「刚下发的队列」这条记录不再描述播放器里的队列。
        forgetPushedQueue()
        _queue.value = _queue.value + song
        onMain { controller?.addMediaItem(mediaItemFor(song)) }
    }

    /**
     * 「下一首播放」：把 [song] 放到**当前正在播放的这一首之后**。
     *
     * 语义（三条都不能破）：
     *  1. [song] 已经在队列里 → **移动**它，绝不新增重复条目；已经在目标位置就是 no-op。
     *  2. [song] 不在队列里 → 插到当前曲目之后；队列为空 / 没有正在播放的歌就追加到末尾。
     *  3. **绝不动当前曲目，也绝不动播放进度**：不重建队列、不 `seekTo`、不碰 `currentSong`。
     *     这是「打开播放页不能把正在播的歌从头重播」那条既有修复的同一条底线，见 [play]。
     *
     * 点的是**当前正在播放的那首**时直接返回：把它移到「下一首」等于立刻换歌（当前曲目就变了），
     * 与第 3 条冲突；它本来就在播，也不需要排队。
     *
     * 队列下标：移动一个在当前曲目**之前**的条目会让当前曲目的下标前移一位，所以移动之后
     * 用 identityKey 重新定位 `queueIndex`（媒体会话侧的 `moveMediaItem` 自己会跟踪当前条目，
     * 两者结果一致）。移动用 `removeAt` + `add`，与 `moveQueueItem(from, to)` 的插入语义**一致**
     * （那个方法本身不改，它也处理不了「移到自己后面 = 末尾」这种越界目标），只是这里把目标下标
     * 钳进合法区间。
     *
     * 队列已经推给媒体会话时会一并下发（`moveMediaItem` / `addMediaItem`）；没有 controller 时
     * 只更新本地队列，等连上之后 [play] / 用户操作照旧生效。
     */
    fun playNext(song: Song) {
        val current = _queue.value
        // 没有正在播放的东西时（队列为空 / currentSong 还没建立）一律「追加到末尾」。
        val playingKey = _currentSong.value?.identityKey
        val hasCurrent = current.isNotEmpty() && playingKey != null
        val playingIndex = _queueIndex.value.coerceIn(0, (current.size - 1).coerceAtLeast(0))
        val desired = if (hasCurrent) playingIndex + 1 else current.size
        val existingIndex = current.indexOfFirst { it.identityKey == song.identityKey }

        // 点的是当前正在播放的那首：什么都不做（既不移动也不重启）。
        if (existingIndex >= 0 && playingKey == song.identityKey) return

        if (existingIndex >= 0) {
            // 已经在队列里 → 移动（绝不新增重复条目）。最终下标 = 目标位置。
            val moveTo = desired.coerceAtMost(current.size - 1)
            if (existingIndex == moveTo) return
            val list = current.toMutableList()
            val item = list.removeAt(existingIndex)
            list.add(moveTo.coerceIn(0, list.size), item)
            forgetPushedQueue()
            _queue.value = list
            relocateCurrentIndex(list, playingKey)
            onMain {
                runCatching { controller?.moveMediaItem(existingIndex, moveTo) }
                    .onFailure { runCatching { CrashLog.write(it) } }
            }
            return
        }

        // 不在队列里 → 插到当前曲目之后（没有正在播放的就追加到末尾）。
        val insertAt = desired.coerceIn(0, current.size)
        val list = current.toMutableList()
        list.add(insertAt, song)
        forgetPushedQueue()
        _queue.value = list
        relocateCurrentIndex(list, playingKey)
        onMain {
            runCatching { controller?.addMediaItem(insertAt, mediaItemFor(song)) }
                .onFailure { runCatching { CrashLog.write(it) } }
        }
    }

    /**
     * 队列被重排之后，用当前曲目的 identityKey 重新算出它的下标。
     *
     * 只在当前曲目确实还在队列里时改写；当前曲目本身永远不被替换。
     */
    private fun relocateCurrentIndex(list: List<Song>, playingKey: String?) {
        if (playingKey == null) return
        val index = list.indexOfFirst { it.identityKey == playingKey }
        if (index >= 0) _queueIndex.value = index
    }

    fun removeFromQueue(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        forgetPushedQueue()
        _queue.value = list
        onMain { controller?.removeMediaItem(index) }
    }

    fun clearQueue() {
        retriedViaFallback.clear()
        lastFallbackKey = null
        fallbackInFlightKey = null
        forgetPushedQueue()
        // 队列被清空了，那就不该再有「连上之后补播」的挂起请求（否则会把清掉的队列又装回来）。
        discardPendingPlay()
        _queue.value = emptyList()
        _currentSong.value = null
        onMain { controller?.clearMediaItems() }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        forgetPushedQueue()
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

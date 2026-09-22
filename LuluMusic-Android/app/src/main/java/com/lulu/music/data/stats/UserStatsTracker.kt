package com.lulu.music.data.stats

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.lulu.music.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 云端同步的最近一次状态。 */
enum class SyncState {
    /** 还没尝试过（已配置，等第一次自动 / 手动同步）。 */
    Idle,

    /** 正在上传 / 下载。 */
    Syncing,

    /** 最近一次同步成功。 */
    Success,

    /** [GiteeConfig.isConfigured] 为 false：一次网络请求都不会发。 */
    NotConfigured,

    /** 最近一次同步失败；原因在 [SyncStatus.message]。 */
    Failed,
}

/**
 * 云端同步状态快照。
 *
 * [atMillis] 是这条状态发生的时间，UI 用它渲染「已同步 · 3 分钟前」；[SyncState.Idle]
 * 的初始值为 0（没有发生过任何事）。
 */
data class SyncStatus(
    val state: SyncState,
    val message: String = "",
    val atMillis: Long = 0L,
)

/**
 * 播放驱动的统计累计 + 自动云同步。
 *
 * **不改 [PlaybackController] 的任何公共 API**：这里只是观察它已经公开的两个
 * `StateFlow`（[PlaybackController.isPlaying] / [PlaybackController.positionMs]）
 * 和 [PlaybackController.currentSong]，在自己的协程里按秒累加。
 *
 * 规则：
 * - 只要 `isPlaying == true` **且播放位置确实在前进**，每秒 `addListeningSeconds(1)`
 *   （位置没动说明卡住了，不计时）。
 * - 一首歌第一次真正播起来时 `recordPlay(song)`；判重靠 `Song.identityKey`，
 *   同一首歌反复拖动进度 / 暂停继续不会重复计数。
 * - 累计播放满 [AUTO_SYNC_EVERY_SECONDS] 秒、或切歌时触发一次自动同步；
 *   自动同步有 [AUTO_SYNC_MIN_GAP_MS] 的最小间隔，防止用户疯狂切歌把 Gitee 打到限流。
 * - 自动同步只在 [GiteeConfig.isConfigured] 为 true 时发生，未配置时**一次网络请求都不发**。
 * - 所有异常都在内部吞掉并写日志：绝不阻塞播放、绝不向外抛。
 */
object UserStatsTracker {

    private const val TAG = "UserStatsTracker"

    private const val TICK_MS = 1_000L

    /** 每累计 60 秒播放时间自动同步一次。 */
    private const val AUTO_SYNC_EVERY_SECONDS = 60L

    /** 自动同步的最小间隔。 */
    private const val AUTO_SYNC_MIN_GAP_MS = 20_000L

    /** 撞号时最多尝试几个新 ID。 */
    private const val ID_ATTEMPTS = 3

    private val uploadMutex = Mutex()

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null
    private var appContext: Context? = null
    private var started = false

    private var lastRecordedKey: String? = null
    private var secondsSinceAutoSync = 0L
    private var lastAutoSyncAt = 0L

    private val _status = MutableStateFlow(SyncStatus(SyncState.Idle))

    /**
     * 最近一次云同步的状态。
     *
     * 「我的」页直接 collect 它来决定那行小字（未启用 / 正在同步 / 已同步 · N 分钟前 / 失败原因）。
     * 状态本身**不影响**同步行为，只是把已经发生的事情如实说出来。
     */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** 当前是否可同步（配置齐全）。 */
    val syncConfigured: Boolean
        get() = GiteeConfig.isConfigured

    /** 由 App 启动时调用一次；内部自行观察播放状态，按秒累加。 */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        runCatching {
            appContext = context.applicationContext
            UserStatsStore.load()
            // 未配置时先把状态摆正，这样「我的」页在第一帧就能说出「未启用」而不是永远停在 Idle。
            if (!GiteeConfig.isConfigured) publishState(SyncState.NotConfigured)
            lastRecordedKey = null
            val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
            ticker?.cancel()
            ticker = s.launch { tickLoop() }
        }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
    }

    /** 触发一次上传；返回结果。不会抛异常。 */
    suspend fun syncNow(): SyncResult {
        if (!GiteeConfig.isConfigured) {
            // 一次网络请求都不发，也不闪一下「正在同步…」：直接如实报告「未配置」。
            publishState(SyncState.NotConfigured)
            return SyncResult.NotConfigured
        }
        publishState(SyncState.Syncing)
        return try {
            val result = uploadMutex.withLock { performSync() }
            when (result) {
                SyncResult.Success -> publishState(SyncState.Success)
                SyncResult.NotConfigured -> publishState(SyncState.NotConfigured)
                is SyncResult.Failed -> publishState(SyncState.Failed, result.reason)
            }
            result
        } catch (t: Throwable) {
            Log.w(TAG, "syncNow failed: ${t.message}")
            val reason = t.message ?: "同步失败"
            publishState(SyncState.Failed, reason)
            SyncResult.Failed(reason)
        }
    }

    /**
     * 发布一条同步状态。
     *
     * `MutableStateFlow` 的赋值是原子的，写入顺序即发布顺序（不会出现 Success 被迟到的 Syncing
     * 覆盖），Compose 侧只在主线程收集它，所以这里无需切线程 —— 也绝不抛异常。
     */
    private fun publishState(state: SyncState, message: String = "") {
        runCatching {
            _status.value = SyncStatus(
                state = state,
                message = message,
                atMillis = System.currentTimeMillis(),
            )
        }.onFailure { Log.w(TAG, "publishState failed: ${it.message}") }
    }

    // ---- 播放观察 ----------------------------------------------------------

    private suspend fun tickLoop() {
        var lastPosition = -1L
        while (true) {
            delay(TICK_MS)
            runCatching {
                val playing = PlaybackController.isPlaying.value
                val position = PlaybackController.positionMs.value
                val song = PlaybackController.currentSong.value
                val moved = position != lastPosition
                lastPosition = position

                if (!playing || !moved) return@runCatching

                UserStatsStore.addListeningSeconds(1L)
                secondsSinceAutoSync += 1L

                val key = song?.identityKey
                if (song != null && key != lastRecordedKey) {
                    // 「换了一首真正不同的歌」：首次播起来时记一次播放次数。
                    lastRecordedKey = key
                    UserStatsStore.recordPlay(song)
                    requestAutoSync()
                }

                if (secondsSinceAutoSync >= AUTO_SYNC_EVERY_SECONDS) {
                    secondsSinceAutoSync = 0L
                    requestAutoSync()
                }
            }.onFailure { Log.w(TAG, "tick failed: ${it.message}") }
        }
    }

    /** 自动同步：只在配置齐全、有网、且距上次超过最小间隔时真正发起。 */
    private fun requestAutoSync() {
        if (!GiteeConfig.isConfigured) return
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncAt < AUTO_SYNC_MIN_GAP_MS) return
        if (!hasNetwork()) return
        lastAutoSyncAt = now
        val s = scope ?: return
        s.launch { runCatching { syncNow() }.onFailure { Log.w(TAG, "auto sync failed: ${it.message}") } }
    }

    private fun hasNetwork(): Boolean = runCatching {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        manager == null || manager.activeNetwork != null
    }.getOrDefault(true)

    // ---- 同步流程 ----------------------------------------------------------

    private suspend fun performSync(): SyncResult {
        UserStatsStore.load()
        val local = UserStatsStore.stats.value
        if (local.userId.isBlank()) return SyncResult.Failed("本地用户 ID 尚未生成")

        // 用户清空过统计：直接覆盖云端，否则下载 + 取最大值会把旧数据合并回来。
        if (UserStatsStore.isResetPending) {
            val result = GiteeSync.upload(local)
            if (result is SyncResult.Success) UserStatsStore.onResetUploaded()
            return result
        }

        // 从未和云端对上过的 ID（只可能是本机随机生成的）→ 先确认没有撞号。
        if (!UserStatsStore.isIdVerified) return firstSync(local)

        val remote = GiteeSync.download(local.userId) ?: return GiteeSync.upload(local)
        return GiteeSync.upload(UserStatsStore.mergeWithRemote(remote))
    }

    /**
     * 首次同步。
     *
     * 6 位数字只有 90 万个组合，随机生成的 ID 有极小概率撞上云端已存在的记录。
     * 处理方式：**先探测**，一旦发现云端已有同名文件（而本机这个 ID 从未同步过），
     * 就换一个新 ID 再传，并在日志里留痕；本地统计原样跟着新 ID 走，用户不会丢数据。
     * 用户自己输入 / 导入的 ID 永远不会被换掉（[UserStatsStore.isIdGenerated] 为 false 时
     * 走的是普通下载合并路径，见 [performSync]）。
     */
    private suspend fun firstSync(local: UserStats): SyncResult {
        var candidate = local
        repeat(ID_ATTEMPTS) {
            when (val probe = GiteeSync.probe(candidate.userId)) {
                is GiteeSync.Probe.Error -> return SyncResult.Failed(probe.reason)
                GiteeSync.Probe.Missing -> {
                    val result = GiteeSync.upload(candidate)
                    if (result is SyncResult.Success) UserStatsStore.markIdVerified()
                    return result
                }
                is GiteeSync.Probe.Found -> {
                    if (!UserStatsStore.isIdGenerated) {
                        // 用户自己输入 / 导入的 ID：这条云端记录就是他本人的，正常合并，绝不换号。
                        UserStatsStore.markIdVerified()
                        return GiteeSync.upload(UserStatsStore.mergeWithRemote(probe.stats))
                    }
                    Log.w(TAG, "6 位 ID ${candidate.userId} 与云端已有记录冲突，改用新 ID")
                    candidate = UserStatsStore.regenerateId()
                }
            }
        }
        return SyncResult.Failed("没有可用的 6 位 ID")
    }
}

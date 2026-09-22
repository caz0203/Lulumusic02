package com.lulu.music.data.stats

import android.content.Context
import android.util.Log
import com.lulu.music.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放驱动的统计累计（**纯本地**，不联网、不同步）。
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
 * - 计数只写本机（[UserStatsStore] → SharedPreferences）。想把统计带到新安装，
 *   走设置页的「备份与恢复」导出 / 导入备份文件，不经过任何服务器。
 * - 所有异常都在内部吞掉并写日志：绝不阻塞播放、绝不向外抛。
 */
object UserStatsTracker {

    private const val TAG = "UserStatsTracker"

    private const val TICK_MS = 1_000L

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null
    private var started = false

    private var lastRecordedKey: String? = null

    /**
     * 由 App 启动时调用一次（`BeansApplication.onCreate`）；内部自行观察播放状态，按秒累加。
     *
     * [context] 只为保持入口签名：统计全部落在本机 `SharedPreferences`，不需要 Context 本身。
     */
    @Synchronized
    fun start(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (started) return
        started = true
        runCatching {
            UserStatsStore.load()
            lastRecordedKey = null
            val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
            ticker?.cancel()
            ticker = s.launch { tickLoop() }
        }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
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

                val key = song?.identityKey
                if (song != null && key != lastRecordedKey) {
                    // 「换了一首真正不同的歌」：首次播起来时记一次播放次数。
                    lastRecordedKey = key
                    UserStatsStore.recordPlay(song)
                }
            }.onFailure { Log.w(TAG, "tick failed: ${it.message}") }
        }
    }
}

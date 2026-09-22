package com.lulu.music.data.stats

import android.util.Log
import com.lulu.music.data.model.Song
import com.lulu.music.data.store.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * 用户听歌统计。
 *
 * `userId` 是 6 位数字字符串（如 `"365363"`），用户在「我的」页抄下来即可；
 * 卸载重装后输入同一串数字就能把云端统计拉回来（见 [GiteeSync]）。
 */
@Serializable
data class UserStats(
    val userId: String,                                  // 6 位数字字符串，如 "365363"
    val listeningSeconds: Long = 0L,
    val playCount: Int = 0,
    val songPlayCounts: Map<String, Int> = emptyMap(),   // key = Song.identityKey
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 6 位数字 ID 的合法性：长度 6 且全为数字。 */
internal fun isValidUserId(id: String): Boolean =
    id.length == 6 && id.all { it in '0'..'9' }

/**
 * 本地统计仓库（SharedPreferences + kotlinx.serialization）。
 *
 * 设计要点：
 * - **公共方法绝不抛异常**：读写失败只记日志并降级（内存里的统计仍然可用）。
 * - 状态更新走 [MutableStateFlow]；调用方（[UserStatsTracker] / Compose UI）都在主线程，
 *   StateFlow 本身也是线程安全的，`@Synchronized` 保证并发调用下计数不丢。
 * - 磁盘写入节流：收听秒数每秒都在涨，[addListeningSeconds] 最多每 5 秒落盘一次
 *   （内存值始终是最新的），换歌 / 清空 / 导入等操作会立即落盘。
 *   进程被杀的极端情况下最多丢最后 5 秒收听时长。
 */
object UserStatsStore {

    private const val TAG = "UserStatsStore"

    private const val KEY_STATS = "beans.stats.v1"
    private const val KEY_ID_ORIGIN = "beans.stats.id.origin"
    private const val KEY_ID_VERIFIED = "beans.stats.id.verified"
    private const val KEY_RESET_PENDING = "beans.stats.reset.pending"

    private const val ORIGIN_GENERATED = "generated"
    private const val ORIGIN_ADOPTED = "adopted"

    /** 收听秒数的落盘节流窗口。 */
    private const val PERSIST_THROTTLE_MS = 5_000L

    private val random = SecureRandom()

    private val _stats = MutableStateFlow(UserStats(userId = ""))
    private val readOnlyStats: StateFlow<UserStats> = _stats.asStateFlow()

    /**
     * 当前统计。首次访问会自动 [load]（幂等），所以即使调用方忘了在启动时 load，
     * 拿到手的也一定是带合法 `userId` 的数据。
     */
    val stats: StateFlow<UserStats>
        get() {
            load()
            return readOnlyStats
        }

    private var loaded = false
    private var lastPersistAt = 0L

    /** 幂等；从本地读取。App 启动时调用。 */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true

        val raw = runCatching { Prefs.readString(KEY_STATS, "") }.getOrDefault("")
        val parsed = raw.takeIf { it.isNotBlank() }
            ?.let { text -> runCatching { Prefs.json.decodeFromString(UserStats.serializer(), text) }.getOrNull() }
        val storedId = parsed?.userId?.takeIf { isValidUserId(it) }

        // 没有本地记录（首次安装 / 卸载重装）或记录里的 ID 不合法 → 生成一个新的 6 位 ID。
        val id = storedId ?: generateUserId()
        val origin = runCatching { Prefs.readString(KEY_ID_ORIGIN, "") }.getOrDefault("")

        _stats.value = normalize(parsed?.copy(userId = id) ?: UserStats(userId = id))

        if (storedId == null) {
            // 新生成的 ID 标记来源：首次同步时会用它判断「撞号要不要换号」。
            runCatching { Prefs.writeString(KEY_ID_ORIGIN, ORIGIN_GENERATED) }
            lastPersistAt = 0L
            persist()
        } else if (origin.isBlank()) {
            // 理论上不会出现（本功能上线时才会写 origin）；缺省按「用户自己输入的 ID」处理，
            // 宁可保守也不要悄悄把用户的 ID 换掉。
            runCatching { Prefs.writeString(KEY_ID_ORIGIN, ORIGIN_ADOPTED) }
        }
    }

    /** 播放一首歌：playCount +1，songPlayCounts[key] +1。 */
    @Synchronized
    fun recordPlay(song: Song) {
        runCatching {
            val current = ensureLoaded()
            val now = System.currentTimeMillis()
            val key = song.identityKey
            _stats.value = current.copy(
                playCount = current.playCount + 1,
                songPlayCounts = current.songPlayCounts + (key to (current.songPlayCounts[key] ?: 0) + 1),
                createdAt = current.createdAt.takeIf { it > 0L } ?: now,
                updatedAt = now,
            )
            persist()
        }.onFailure { Log.w(TAG, "recordPlay failed: ${it.message}") }
    }

    /** 累加收听秒数（由播放进度驱动）。 */
    @Synchronized
    fun addListeningSeconds(seconds: Long) {
        if (seconds <= 0L) return
        runCatching {
            val current = ensureLoaded()
            val now = System.currentTimeMillis()
            _stats.value = current.copy(
                listeningSeconds = current.listeningSeconds + seconds,
                createdAt = current.createdAt.takeIf { it > 0L } ?: now,
                updatedAt = now,
            )
            // 节流：内存值永远最新，磁盘每 5 秒写一次（第一次调用必定写）。
            if (now - lastPersistAt >= PERSIST_THROTTLE_MS) persist()
        }.onFailure { Log.w(TAG, "addListeningSeconds failed: ${it.message}") }
    }

    /** 当前 6 位用户 ID。 */
    val userId: String
        @Synchronized get() = ensureLoaded().userId

    /**
     * 重装后输入已有 ID 以恢复云端数据。格式非法返回 false。
     *
     * 语义：把本地统计**切换到**这个 ID 名下 —— 上一个 ID 的计数不会被搬到新 ID 上
     * （否则会把别人的云端记录顶掉），新 ID 从零开始，随后由
     * [UserStatsTracker.syncNow] 下载云端数据并合并回本地。
     * 输入的 ID 与当前 ID 相同时什么都不做，直接返回 true。
     */
    @Synchronized
    fun adoptUserId(id: String): Boolean {
        val target = id.trim()
        if (!isValidUserId(target)) return false
        return runCatching {
            val current = ensureLoaded()
            if (current.userId != target) {
                val now = System.currentTimeMillis()
                _stats.value = UserStats(userId = target, createdAt = now, updatedAt = now)
                runCatching {
                    Prefs.writeString(KEY_ID_ORIGIN, ORIGIN_ADOPTED)
                    // 用户主动输入的 ID 永远不参与「撞号自动换号」。
                    Prefs.writeString(KEY_ID_VERIFIED, "1")
                    Prefs.remove(KEY_RESET_PENDING)
                }
                persist()
            }
            true
        }.onFailure { Log.w(TAG, "adoptUserId failed: ${it.message}") }.getOrDefault(false)
    }

    /**
     * 清空本地统计。
     *
     * **保留 ID**（用户抄下来的那串数字必须继续有效），只把计数归零，
     * 并打上「待覆盖云端」标记：否则下一次同步会先下载云端旧数据再取最大值合并，
     * 清空立刻就会被撤销。带标记同步成功后，云端记录会被这份空统计覆盖。
     */
    @Synchronized
    fun clear() {
        runCatching {
            val current = ensureLoaded()
            val now = System.currentTimeMillis()
            _stats.value = UserStats(userId = current.userId, createdAt = now, updatedAt = now)
            runCatching { Prefs.writeString(KEY_RESET_PENDING, "1") }
            persist()
        }.onFailure { Log.w(TAG, "clear failed: ${it.message}") }
    }

    /** 导出为 JSON 字符串（供备份文件使用）；失败返回空串，绝不抛异常。 */
    fun exportJson(): String = runCatching {
        Prefs.json.encodeToString(UserStats.serializer(), ensureLoaded())
    }.onFailure { Log.w(TAG, "exportJson failed: ${it.message}") }.getOrDefault("")

    /**
     * 从 JSON 导入；失败返回 false，不得抛异常。
     *
     * 整体替换本地统计；JSON 里的 `userId` 合法时一并采用（当作一次显式换号）。
     */
    @Synchronized
    fun importJson(json: String): Boolean = runCatching {
        val current = ensureLoaded()
        val parsed = Prefs.json.decodeFromString(UserStats.serializer(), json)
        val id = if (isValidUserId(parsed.userId)) parsed.userId else current.userId
        if (!isValidUserId(id)) {
            false
        } else {
            val imported = normalize(parsed.copy(userId = id)).let {
                if (it.createdAt > 0L) it else it.copy(createdAt = System.currentTimeMillis())
            }
            _stats.value = imported
            if (id != current.userId) {
                runCatching {
                    Prefs.writeString(KEY_ID_ORIGIN, ORIGIN_ADOPTED)
                    Prefs.writeString(KEY_ID_VERIFIED, "1")
                    Prefs.remove(KEY_RESET_PENDING)
                }
            }
            persist()
            true
        }
    }.onFailure { Log.w(TAG, "importJson failed: ${it.message}") }.getOrDefault(false)

    // ---- 供 UserStatsTracker / GiteeSync 使用的内部能力（不属于冻结公共 API）----

    /** 当前 ID 是否由本机随机生成（false = 用户输入 / 导入得到的）。 */
    internal val isIdGenerated: Boolean
        get() = runCatching { Prefs.readString(KEY_ID_ORIGIN, "") }.getOrDefault("") == ORIGIN_GENERATED

    /** 这个 ID 是否已经和云端对上过（首次创建成功 / 用户主动输入）。 */
    internal val isIdVerified: Boolean
        get() = runCatching { Prefs.readString(KEY_ID_VERIFIED, "") }.getOrDefault("") == "1"

    /** 用户清空过统计，下一次同步要覆盖云端而不是合并。 */
    internal val isResetPending: Boolean
        get() = runCatching { Prefs.readString(KEY_RESET_PENDING, "") }.getOrDefault("") == "1"

    internal fun markIdVerified() {
        runCatching { Prefs.writeString(KEY_ID_VERIFIED, "1") }
    }

    /** 清空已成功覆盖到云端，撤掉待覆盖标记。 */
    internal fun onResetUploaded() {
        runCatching { Prefs.remove(KEY_RESET_PENDING) }
    }

    /**
     * 换一个全新的 6 位 ID（本地统计原样保留，只是改挂到新 ID 名下）。
     * 用于「随机生成的 ID 撞上云端已有记录」这一极小概率场景。
     */
    @Synchronized
    internal fun regenerateId(): UserStats {
        val current = ensureLoaded()
        val fresh = current.copy(userId = generateUserId(), updatedAt = System.currentTimeMillis())
        _stats.value = fresh
        runCatching {
            Prefs.writeString(KEY_ID_ORIGIN, ORIGIN_GENERATED)
            Prefs.remove(KEY_ID_VERIFIED)
        }
        persist()
        return fresh
    }

    /**
     * 把云端记录合并进本地并返回合并结果（同时写回本地）。
     *
     * 合并策略是**逐项取最大值**：单调、幂等，重复同步永远不会把计数刷成两倍；
     * 代价是多设备同时听歌时可能少算一些增量 —— 相比「重复计数」这是更安全的取舍。
     */
    @Synchronized
    internal fun mergeWithRemote(remote: UserStats): UserStats {
        val local = ensureLoaded()
        val merged = maxMerge(local, remote)
        _stats.value = merged
        persist()
        return merged
    }

    // ---- 内部工具 ----------------------------------------------------------

    private fun ensureLoaded(): UserStats {
        load()
        return _stats.value
    }

    private fun persist() {
        runCatching {
            Prefs.writeString(KEY_STATS, Prefs.json.encodeToString(UserStats.serializer(), _stats.value))
            lastPersistAt = System.currentTimeMillis()
        }.onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }

    /** 100000..999999：刻意避开前导 0，用户抄写 / 输入时不存在「少打一位 0」的歧义。 */
    private fun generateUserId(): String = (100_000 + random.nextInt(900_000)).toString()

    private fun normalize(value: UserStats): UserStats = value.copy(
        listeningSeconds = value.listeningSeconds.coerceAtLeast(0L),
        playCount = value.playCount.coerceAtLeast(0),
        songPlayCounts = value.songPlayCounts.filterValues { it > 0 },
        createdAt = value.createdAt.coerceAtLeast(0L),
        updatedAt = value.updatedAt.coerceAtLeast(0L),
    )
}

/** 逐项取最大值的合并；`userId` 以本地为准。 */
internal fun maxMerge(local: UserStats, remote: UserStats): UserStats {
    val counts = HashMap<String, Int>(local.songPlayCounts)
    for ((key, value) in remote.songPlayCounts) {
        counts[key] = maxOf(counts[key] ?: 0, value)
    }
    val created = listOf(local.createdAt, remote.createdAt).filter { it > 0L }.minOrNull() ?: 0L
    return UserStats(
        userId = local.userId,
        listeningSeconds = maxOf(local.listeningSeconds, remote.listeningSeconds),
        playCount = maxOf(local.playCount, remote.playCount),
        songPlayCounts = counts,
        createdAt = created,
        updatedAt = System.currentTimeMillis(),
    )
}

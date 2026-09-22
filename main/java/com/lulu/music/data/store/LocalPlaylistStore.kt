package com.lulu.music.data.store

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.lulu.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * 本机歌单（iOS 的 `LocalPlaylist` / `LocalLibraryStore` 的 Android 版本）。
 *
 * 只保存**本机**数据：歌单里放的是完整的 [Song]，因此它和收藏 / 最近播放一样，
 * 用不着任何平台账号，也不依赖网络。同名 iOS 模型里 `id` 是 `UUID`，这里保持 `String`。
 */
@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    /** epoch millis；0 表示「旧数据 / 未知」（反序列化旧 JSON 时不会炸）。 */
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val songCount: Int get() = songs.size
}

/**
 * 本地歌单仓库（SharedPreferences + kotlinx.serialization，与 `FavoritesStore` /
 * `PlayHistoryStore` / `DownloadStore` 完全同一套持久化机制）。
 *
 * ## 它取代了什么
 *
 * 以前 `ui/screens/LibraryScreen.kt` 用 `remember { mutableStateListOf<LocalPlaylist>() }`
 * 在页面里存歌单，进程（甚至只是页面重组）一过就全没了，而新建歌单的弹窗却写着
 * 「本地歌单保存在设备上，覆盖安装不会丢失」—— 那句话当时是假的。现在歌单真的落在
 * `SharedPreferences("beans_prefs")` 的 `beans.local.playlists.v1` 键里（JSON 数组），
 * 那句话成立。
 *
 * ## 数据存在哪、能活过什么
 *
 *  - 路径：应用私有的 `SharedPreferences("beans_prefs")`（`/data/data/com.lulu.music/shared_prefs/beans_prefs.xml`）
 *    里的 `beans.local.playlists.v1` 键。
 *  - 进程重启：**保留**（每次启动 [load] 读回）。
 *  - 覆盖安装 / 升级：**保留**（SharedPreferences 属于应用数据，升级不清）。
 *  - 卸载：**丢失**（Android 会删掉整个应用数据目录）；要带走请用设置页的备份与恢复。
 *
 * ## 线程 / 一致性
 *
 * 所有公共写入口都是 `@Synchronized` 的「读当前值 → 造新列表 → 写 flow → 落盘」，
 * 落盘与内存更新在同一次调用里完成，所以 [playlists] 永远是可用的最新快照。
 * 读写失败只记日志并降级，**绝不抛异常**（启动路径上不能崩）。
 */
object LocalPlaylistStore {

    private const val TAG = "LocalPlaylistStore"

    /** store 自己落盘用的键；`BackupManager` 里有一份同名常量（既有约定：各自文件独立）。 */
    private const val KEY = "beans.local.playlists.v1"

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    private val readOnlyPlaylists: StateFlow<List<LocalPlaylist>> = _playlists.asStateFlow()

    private var loaded = false

    /**
     * 当前全部歌单（列表顺序 = 用户自定义顺序）。
     *
     * 首次访问会自动 [load]（幂等），所以 Compose 侧直接
     * `collectAsState()` 就能用，不必依赖启动顺序。
     */
    val playlists: StateFlow<List<LocalPlaylist>>
        get() {
            load()
            return readOnlyPlaylists
        }

    /** 幂等；从本地读回歌单。App 启动时（`BeansApplication.onCreate`）调用。 */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val raw = runCatching { Prefs.readList(KEY, LocalPlaylist.serializer()) }
            .onFailure { Log.w(TAG, "load failed: ${it.message}") }
            .getOrDefault(emptyList())
        val normalized = normalize(raw)
        _playlists.value = normalized
        // 旧版本 / 手改过的数据里可能有重复 id、空 id、重复歌曲：修正后落盘一次。
        if (normalized != raw) persist(normalized)
    }

    /**
     * 丢掉内存状态并重新读盘。
     *
     * 内存与磁盘本来就同步（每次写都落盘），这里只服务两个场景：
     *  - 备份导入把原始 JSON 直接写进了 SharedPreferences，需要让内存里的 flow 跟上；
     *  - 测试里模拟「进程重启」，验证歌单真的从磁盘回来了。
     */
    @Synchronized
    fun reload() {
        loaded = false
        _playlists.value = emptyList()
        load()
    }

    // ---------------------------------------------------------------------------------------
    // 读
    // ---------------------------------------------------------------------------------------

    fun playlist(id: String): LocalPlaylist? = snapshot().firstOrNull { it.id == id }

    /** 歌单里的歌曲；歌单不存在时是空列表（不是异常）。 */
    fun songs(id: String): List<Song> = playlist(id)?.songs ?: emptyList()

    /** 不含状态快照的读（避免 `playlists.value` 触发自动 load 的副作用）。 */
    private fun snapshot(): List<LocalPlaylist> {
        load()
        return _playlists.value
    }

    // ---------------------------------------------------------------------------------------
    // 写
    // ---------------------------------------------------------------------------------------

    /**
     * 新建歌单。名称去空白后为空则**不创建**并返回 null（UI 据此提示「请输入歌单名称」）。
     *
     * `id` 用 `local-<毫秒>-<随机>`：既有格式以 `local-` 开头，加上随机段是为了
     * 「同一毫秒内连续新建多个歌单」不会撞 id（旧实现只用毫秒，会撞）。
     */
    @Synchronized
    fun create(name: String, songs: List<Song> = emptyList()): LocalPlaylist? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylist(
            id = "local-$now-${UUID.randomUUID().toString().take(8)}",
            name = trimmed,
            songs = dedupe(songs),
            createdAt = now,
            updatedAt = now,
        )
        val list = snapshot().toMutableList()
        list.add(playlist)
        persist(list)
        return playlist
    }

    /** 重命名。歌单不存在或名称为空返回 false。 */
    @Synchronized
    fun rename(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val list = snapshot().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        if (list[index].name == trimmed) return true
        list[index] = list[index].copy(name = trimmed, updatedAt = System.currentTimeMillis())
        persist(list)
        return true
    }

    /** 删除歌单；返回是否真的删掉了。 */
    @Synchronized
    fun delete(id: String): Boolean {
        val list = snapshot()
        if (list.none { it.id == id }) return false
        persist(list.filterNot { it.id == id })
        return true
    }

    /**
     * 调整歌单顺序。语义与页面里的排序对话框完全一致（`removeAt(from)` + `add(to, item)`）：
     * 相邻下移退化成「交换」，`from == to` / 越界都是 no-op。
     *
     * 这里刻意不改这套语义 —— 改了就会让「上移 / 下移」按钮的行为与旧版本不一致。
     */
    @Synchronized
    fun movePlaylist(from: Int, to: Int): Boolean {
        val list = snapshot().toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return false
        val item = list.removeAt(from)
        list.add(to, item)
        persist(list)
        return true
    }

    /**
     * 往歌单里加歌，按 [Song.identityKey] 去重（已在歌单里的不会被重复加入）。
     *
     * @return 真正新增的歌曲数量（调用方据此提示「重复歌曲已跳过」）。
     */
    @Synchronized
    fun addSongs(id: String, songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val list = snapshot().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return 0
        val existing = list[index].songs
        val seen = existing.mapTo(HashSet()) { it.identityKey }
        val added = songs.filter { seen.add(it.identityKey) }
        if (added.isEmpty()) return 0
        list[index] = list[index].copy(
            songs = existing + added,
            updatedAt = System.currentTimeMillis(),
        )
        persist(list)
        return added.size
    }

    /** 从歌单里移除一首歌（按 [Song.identityKey] 匹配，所有同 key 的条目都会被移除）。 */
    @Synchronized
    fun removeSong(id: String, song: Song): Boolean {
        val list = snapshot().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        val remaining = list[index].songs.filterNot { it.identityKey == song.identityKey }
        if (remaining.size == list[index].songs.size) return false
        list[index] = list[index].copy(songs = remaining, updatedAt = System.currentTimeMillis())
        persist(list)
        return true
    }

    /** 清空全部本地歌单（目前只有测试与「恢复一份空备份」会用到）。 */
    @Synchronized
    fun clear() {
        _playlists.value = emptyList()
        runCatching { Prefs.remove(KEY) }
    }

    // ---------------------------------------------------------------------------------------
    // 备份：JSON 导出 / 导入
    // ---------------------------------------------------------------------------------------

    /** 导出为 JSON 数组字符串（备份文件用）；失败返回空串，绝不抛异常。 */
    fun exportJson(): String = runCatching {
        Prefs.json.encodeToString(ListSerializer(LocalPlaylist.serializer()), snapshot())
    }.onFailure { Log.w(TAG, "exportJson failed: ${it.message}") }.getOrDefault("")

    /**
     * 从 JSON 数组导入并**整体替换**本地歌单；格式不合法返回 false 且**不动**现有数据。
     *
     * 导入的内容会经过 [normalize] 清洗（补 / 去重 id、按 identityKey 去重歌曲），
     * 所以一份手工改坏的备份也不会让歌单里出现重复歌曲。
     */
    @Synchronized
    fun importJson(json: String): Boolean = runCatching {
        val decoded = Prefs.json.decodeFromString(ListSerializer(LocalPlaylist.serializer()), json)
        persist(normalize(decoded))
        true
    }.onFailure { Log.w(TAG, "importJson failed: ${it.message}") }.getOrDefault(false)

    // ---------------------------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------------------------

    /**
     * 测试钩子：丢掉内存状态并重新读盘（等价于「杀掉进程再启动」）。
     * 生产代码从不调用它；这里保留给单测模拟进程重启，见 `LocalPlaylistStoreTest`。
     */
    @VisibleForTesting
    internal fun resetForTests() {
        reload()
    }

    private fun persist(list: List<LocalPlaylist>) {
        _playlists.value = list
        runCatching { Prefs.writeList(KEY, LocalPlaylist.serializer(), list) }
            .onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }

    /** 清洗：补空 id、去重 id、名称去空白、歌单内歌曲按 identityKey 去重。 */
    private fun normalize(raw: List<LocalPlaylist>): List<LocalPlaylist> {
        val seenIds = HashSet<String>()
        val out = ArrayList<LocalPlaylist>(raw.size)
        for (entry in raw) {
            val id = entry.id.takeIf { it.isNotBlank() } ?: "local-${UUID.randomUUID()}"
            if (!seenIds.add(id)) continue
            val name = entry.name.trim()
            if (name.isEmpty()) continue
            out.add(entry.copy(id = id, name = name, songs = dedupe(entry.songs)))
        }
        return out
    }

    private fun dedupe(songs: List<Song>): List<Song> {
        if (songs.size < 2) return songs
        val seen = HashSet<String>(songs.size)
        return songs.filter { seen.add(it.identityKey) }
    }
}

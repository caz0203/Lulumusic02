package com.lulu.music.data.donors

import com.lulu.music.data.net.Http
import com.lulu.music.data.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 「自愿赞助」名单的自动同步（Android）。
 *
 * 结构照着 `data/update/UpdateChecker.kt` 抄：**纯数据层 + 绝不抛异常 + 尽力而为的后台同步**，
 * 区别是它多了一份「解析后落盘」的本地缓存，所以断网启动也能看到上一次同步到的名单。
 *
 * 行为约定：
 *  - [load] 只读本地缓存，不联网、不阻塞（`BeansApplication.onCreate` 里同步调用）；
 *  - [refresh] 在后台拉一次 [DonorsConfig.MANIFEST_URL]；网络错误 / 超时 / HTTP 非 2xx /
 *    JSON 读不懂，全部静默失败并返回 false —— **绝不覆盖已有缓存**；
 *  - 只有在 JSON 结构读懂了（[DonorsManifest.Parsed]，含合法的空数组）时才更新内存与缓存；
 *  - [donors] 始终是「按金额降序」的成品列表，界面直接画。
 */
object DonorsStore {

    /** 解析后的名单在本地缓存的落盘键（SharedPreferences，走 [Prefs]）。 */
    const val CACHE_KEY: String = "beans.donors.cache"

    private val _donors = MutableStateFlow<List<Donor>>(emptyList())

    /** 当前榜单（金额降序）。Compose 直接 `collectAsState()`。 */
    val donors: StateFlow<List<Donor>> = _donors.asStateFlow()

    private var loaded = false

    /**
     * 榜单专用 HTTP 客户端：沿用 [Http] 的连接池，只把超时收紧到 [DonorsConfig.TIMEOUT_MS]，
     * 避免启动时的后台同步长时间占着连接。
     */
    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(DonorsConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(DonorsConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DonorsConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 幂等：启动时读一次本地缓存（纯本地、不联网、不阻塞）。 */
    fun load() {
        if (loaded) return
        loaded = true
        reloadFromCache()
    }

    /**
     * 从本地缓存重新读取榜单。
     *
     * 既给 [load] 用，也给「想看缓存里到底存了什么」的测试用（等价于下次进程启动会读到的内容）。
     */
    fun reloadFromCache() {
        _donors.value = Prefs.readList(CACHE_KEY, Donor.serializer())
    }

    /**
     * 应用一次远程文本。[raw] 解析成功后更新内存与缓存并返回 true；解析失败返回 false 且
     * **不动**任何现有数据（这是「失败的拉取绝不冲掉好缓存」的唯一实现点，测试直接钉它）。
     */
    fun applyFetched(raw: String?): Boolean = when (val manifest = parseDonorsManifest(raw)) {
        DonorsManifest.Invalid -> false
        is DonorsManifest.Parsed -> {
            _donors.value = manifest.donors
            Prefs.writeList(CACHE_KEY, Donor.serializer(), manifest.donors)
            true
        }
    }

    /**
     * 拉取远程名单并落缓存。**绝不抛异常**：断网、超时、HTTP 非 2xx、JSON 读不懂一律返回 false，
     * 已有缓存原样保留（界面继续显示上一次同步到的名单，不弹错误）。
     */
    suspend fun refresh(): Boolean {
        val url = DonorsConfig.MANIFEST_URL
        if (url.isBlank()) return false
        return try {
            applyFetched(fetchText(url))
        } catch (e: Throwable) {
            false
        }
    }

    /** 在 IO 线程拉取清单文本；失败抛异常，由 [refresh] 统一降级。 */
    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        // 测试用的离线闸门在这里也生效：单元测试里绝不真的发请求。
        Http.guardNetwork()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }
}

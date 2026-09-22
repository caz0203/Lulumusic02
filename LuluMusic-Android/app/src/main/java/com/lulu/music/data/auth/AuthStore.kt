package com.lulu.music.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.lulu.music.BeansApplication
import com.lulu.music.data.api.NetEaseApi
import com.lulu.music.data.model.NetEaseUser
import com.lulu.music.data.model.Playlist
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.net.BeansApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * 网易云账号态（对应 iOS 的 `AuthStore`）。
 *
 * 与原实现一致：账号资料整份以 JSON 持久化（登录后由只读接口重新拉取），
 * 歌单缓存单独持久化；缓存缺失/损坏一律降级为空歌单，异常不冒泡到 UI。
 */
object AuthStore {

    // MARK: - 持久化

    private const val PREFS_NAME = "beans_auth"

    /** 账号资料（与 iOS `UserDefaults` 的 `beans.user` 同键）。 */
    private const val USER_KEY = "beans.user"

    /** 歌单缓存（对应 iOS `SyncedPlaylistCache`，按平台 + 账号维度分键）。 */
    private const val PLAYLIST_CACHE_PREFIX = "beans.syncedPlaylists"
    private const val PLAYLIST_CACHE_FRESH_MS = 30L * 60L * 1000L

    /** 网易云「我喜欢的音乐」在歌单列表里单独处理，与原实现一致地过滤掉。 */
    private const val LIKED_PLAYLIST_NAME = "我喜欢的音乐"

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences
        get() = BeansApplication.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // MARK: - 对外状态

    private val _user = MutableStateFlow<NetEaseUser?>(null)
    private val _loggedIn = MutableStateFlow(false)
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    private val _nicknameState = MutableStateFlow("")
    private val _vipBadgeState = MutableStateFlow<String?>(null)

    /** Compose 观察用镜像，与下面的普通属性读的是同一份数据。 */
    val loggedInFlow: StateFlow<Boolean> = _loggedIn.asStateFlow()
    val nicknameFlow: StateFlow<String> = _nicknameState.asStateFlow()
    val vipBadgeFlow: StateFlow<String?> = _vipBadgeState.asStateFlow()

    val user: NetEaseUser? get() = _user.value
    val isLoggedIn: Boolean get() = _loggedIn.value
    val playlists: List<Playlist> get() = _playlists.value
    val nickname: String get() = _nicknameState.value

    /** 网易云会员标识：nil 无 / "VIP" / "SVIP"（vipType >= 11），与 `NetEaseUser.vipBadge` 同规则 */
    val vipBadge: String? get() = _vipBadgeState.value

    init {
        val saved = loadUser()
        if (saved != null) {
            _user.value = saved
            syncUserFlows(saved)
            _loggedIn.value = true
            val cached: List<Playlist> = cachedPlaylists(SongSource.NET_EASE, "${saved.uid}")?.playlists ?: emptyList()
            _playlists.value = cached.filter { playlist: Playlist -> playlist.name != LIKED_PLAYLIST_NAME }
        }
    }

    // MARK: - 登录

    /**
     * 轮询网易云扫码状态（调用方以 3 秒间隔重复调用直到返回值不再是 800/801）。
     *
     * 对应 iOS 登录页的 `NetEaseAPI.shared.qrCheck(key:)`；网络异常会抛出
     * [BeansApiException]，由调用方决定是否继续轮询。
     */
    suspend fun qrCheck(key: String): Int = NetEaseApi.qrCheck(key)

    /** 轮询直到扫码有了结果（成功/失败/过期），成功时返回 true 并写入登录态。 */
    suspend fun awaitQRLogin(key: String, maxAttempts: Int = 100): Boolean {
        var code = -1
        for (attempt in 0 until maxAttempts) {
            code = NetEaseApi.qrCheck(key)
            // 800 二维码过期 / 801 已扫码待确认：网易云在确认前会持续返回 801，必须继续轮询
            if (code != 800 && code != 801) break
            delay(3_000)
        }
        return when (code) {
            803 -> {
                finishLogin()
                true
            }
            800 -> throw BeansApiException.Unknown("二维码已过期，请刷新后重试")
            802 -> throw BeansApiException.Unknown("已取消登录")
            else -> throw BeansApiException.Unknown("登录未完成，请重试")
        }
    }

    /** 登录完成后拉取账号资料与歌单；账号接口失败时重试 3 次（对应 iOS `finishLogin`）。 */
    suspend fun finishLogin() {
        var account: NetEaseUser? = null
        for (attempt in 0 until 3) {
            try {
                account = NetEaseApi.account()
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 2) throw e
                delay(1_200)
            }
        }
        val resolved = account ?: throw BeansApiException.Unknown("获取账号信息失败")
        val loaded = try {
            NetEaseApi.userPlaylists(resolved.uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val filtered = loaded.filter { it.name != LIKED_PLAYLIST_NAME }
        _user.value = resolved
        syncUserFlows(resolved)
        _playlists.value = filtered
        if (filtered.isNotEmpty()) {
            savePlaylists(filtered, SongSource.NET_EASE, "${resolved.uid}")
        }
        _loggedIn.value = true
        persistUser(resolved)
    }

    /// 刷新账号资料（VIP 状态等），登录后保持最新会员标识
    suspend fun refreshAccount() {
        val current = _user.value ?: return
        val fresh = try {
            NetEaseApi.account()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        _user.value = fresh
        syncUserFlows(fresh)
        persistUser(fresh)
        if (fresh.uid != current.uid) {
            _loggedIn.value = true
        }
    }

    suspend fun loadLibrary(force: Boolean = false) {
        val current = _user.value ?: return
        val accountID = "${current.uid}"
        val cached = cachedPlaylists(SongSource.NET_EASE, accountID)
        if (cached != null) {
            _playlists.value = cached.playlists.filter { it.name != LIKED_PLAYLIST_NAME }
            if (!force && isFresh(cached)) return
        }
        val loaded = try {
            NetEaseApi.userPlaylists(current.uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        val filtered = loaded.filter { it.name != LIKED_PLAYLIST_NAME }
        if (filtered.isNotEmpty()) {
            _playlists.value = filtered
            savePlaylists(filtered, SongSource.NET_EASE, accountID)
        }
    }

    /** 退出登录：清空 Cookie 与本地账号资料（歌单缓存保留，与原实现一致）。 */
    fun logout() {
        NetEaseApi.clearCookies()
        _user.value = null
        _playlists.value = emptyList()
        _loggedIn.value = false
        _nicknameState.value = ""
        _vipBadgeState.value = null
        try {
            prefs.edit().remove(USER_KEY).apply()
        } catch (e: Exception) {
            // 存储不可用时保持内存态已清空
        }
    }

    // MARK: - 状态同步 / 持久化

    private fun syncUserFlows(user: NetEaseUser) {
        _nicknameState.value = user.nickname
        _vipBadgeState.value = user.vipBadge
    }

    /** 账号资料以 JSON 字符串保存（对应 iOS 的 JSONEncoder + UserDefaults）。 */
    private fun persistUser(user: NetEaseUser) {
        try {
            prefs.edit().putString(USER_KEY, json.encodeToString(NetEaseUser.serializer(), user)).apply()
        } catch (e: Exception) {
            // 持久化失败不影响本次登录
        }
    }

    /** 读取时容错：缺失/损坏/结构不符一律按未登录处理，不抛异常。 */
    private fun loadUser(): NetEaseUser? {
        val raw = try {
            prefs.getString(USER_KEY, null)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            json.decodeFromString(NetEaseUser.serializer(), raw)
        } catch (e: Exception) {
            null
        }
    }

    // MARK: - 歌单缓存

    private data class CachedPlaylists(val playlists: List<Playlist>, val savedAt: Long)

    private fun cacheKey(source: SongSource, accountID: String): String =
        "$PLAYLIST_CACHE_PREFIX.${source.raw}.$accountID"

    private fun cachedPlaylists(source: SongSource, accountID: String): CachedPlaylists? {
        val raw = try {
            prefs.getString(cacheKey(source, accountID), null)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            val wrapper = JSONObject(raw)
            CachedPlaylists(
                playlists = json.decodeFromString(
                    ListSerializer(Playlist.serializer()),
                    wrapper.optString("playlists", "[]"),
                ),
                savedAt = wrapper.optLong("savedAt", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun savePlaylists(playlists: List<Playlist>, source: SongSource, accountID: String) {
        try {
            val payload = JSONObject()
                .put("playlists", json.encodeToString(ListSerializer(Playlist.serializer()), playlists))
                .put("savedAt", System.currentTimeMillis())
            prefs.edit().putString(cacheKey(source, accountID), payload.toString()).apply()
        } catch (e: Exception) {
            // 缓存失败不影响登录
        }
    }

    private fun isFresh(cached: CachedPlaylists): Boolean =
        System.currentTimeMillis() - cached.savedAt < PLAYLIST_CACHE_FRESH_MS
}

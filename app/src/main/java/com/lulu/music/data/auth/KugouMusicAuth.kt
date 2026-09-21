package com.lulu.music.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.lulu.music.BeansApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * 酷狗登录态（WebView 网页登录换取的 token/userid + 设备参数）。
 *
 * 与原实现一致：设备参数（GUID / MID / MAC / DEV）与登录态放在同一个字典里持久化，
 * `mid` 由 GUID 的 MD5 前 15 位十六进制转十进制得到；Cookie 串按
 * userid / token / KUGOU_API_MID / dfid 顺序拼接，会员账号额外带上 vipType。
 */
object KugouMusicAuth {

    // MARK: - 对外状态

    private val _loggedIn = MutableStateFlow(false)
    private val _userId = MutableStateFlow("")
    private val _nickname = MutableStateFlow("")
    private val _avatarURL = MutableStateFlow<String?>(null)
    private val _vipBadge = MutableStateFlow<String?>(null)

    /** Compose 观察用镜像，与下面的普通属性读的是同一份数据。 */
    val loggedInFlow: StateFlow<Boolean> = _loggedIn.asStateFlow()
    val nicknameFlow: StateFlow<String> = _nickname.asStateFlow()
    val vipBadgeFlow: StateFlow<String?> = _vipBadge.asStateFlow()

    val isLoggedIn: Boolean get() = _loggedIn.value
    val userId: String get() = _userId.value
    val nickname: String get() = _nickname.value
    val avatarURL: String? get() = _avatarURL.value

    /** 酷狗会员标识：nil 无 / "VIP"（vipType > 0） */
    val vipBadge: String? get() = _vipBadge.value

    // MARK: - 持久化

    private const val PREFS_NAME = "beans_auth"
    private const val KEY = "beans.kugou.auth.v2"

    /** 状态与 auth 字典的写入都在此锁内完成，保证两者始终是同一份快照。 */
    private val lock = Any()

    /** 内存中的登录态/设备参数字典；每次变更整体替换，读方法无需加锁。 */
    @Volatile
    private var auth: Map<String, String> = emptyMap()

    private val prefs: SharedPreferences
        get() = BeansApplication.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val saved = loadAuth()
        if (saved.isNotEmpty()) {
            auth = saved
            apply(saved)
        }
    }

    /** 与 iOS `defaults.dictionary(forKey:)` 对应：字典以 JSON 字符串保存，损坏时按未登录处理。 */
    private fun loadAuth(): Map<String, String> {
        val raw = try {
            prefs.getString(KEY, null)
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        for (key in json.keys()) {
            if (json.isNull(key)) continue
            out[key] = json.optString(key, "")
        }
        return out
    }

    private fun persist(value: Map<String, String>) {
        val json = JSONObject()
        for ((key, entry) in value) json.put(key, entry)
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    // MARK: - 登录信息

    val token: String get() = auth["token"] ?: ""

    val mid: String get() = (auth["KUGOU_API_MID"] ?: auth["mid"])?.takeIf { it.isNotEmpty() } ?: defaultMid

    val dfid: String get() = auth["dfid"] ?: auth["DFID"] ?: "-"

    val guid: String get() = auth["KUGOU_API_GUID"] ?: ""

    val vipType: Int get() = (auth["vipType"] ?: auth["vip_type"] ?: "0").toIntOrNull() ?: 0

    val hasMembership: Boolean get() = vipType > 0

    val cookieHeader: String
        get() {
            val items = mutableListOf(
                "userid" to userId,
                "token" to token,
                "KUGOU_API_MID" to mid,
                "dfid" to dfid,
            )
            val vip = vipType
            if (vip > 0) {
                items.add("vipType" to "$vip")
                items.add("viptype" to "$vip")
            }
            return items
                .filter { it.second.isNotEmpty() }
                .joinToString("; ") { "${it.first}=${it.second}" }
        }

    // MARK: - 写入

    fun prepareDevice() {
        val next = ensureDevice(auth)
        if (next == auth) return
        save(next)
    }

    fun saveLogin(userId: String, token: String, nickname: String, avatar: String, vipType: Int) {
        val next = LinkedHashMap(ensureDevice(auth))
        next["userid"] = userId
        next["token"] = token
        next["nickname"] = nickname
        next["avatar"] = avatar
        next["vipType"] = "$vipType"
        save(next)
    }

    fun saveDeviceDFID(dfid: String) {
        if (dfid.isEmpty()) return
        if (auth["dfid"] == dfid) return
        val next = LinkedHashMap(auth)
        next["dfid"] = dfid
        save(next)
    }

    fun updateVIPType(vipType: Int) {
        if (vipType <= this.vipType) return
        val next = LinkedHashMap(auth)
        next["vipType"] = "$vipType"
        save(next)
    }

    fun logout() {
        synchronized(lock) {
            auth = emptyMap()
            writeState("", false, "", null, null)
        }
        prefs.edit().remove(KEY).apply()
    }

    private fun save(value: Map<String, String>) {
        synchronized(lock) {
            auth = value
            apply(value)
        }
        persist(value)
    }

    private fun apply(value: Map<String, String>) {
        val nextUserId = (value["userid"] ?: value["user_id"] ?: "")
            .filter { it.code in 0x30..0x39 } // Swift 用 `isNumber` 过滤成数字串
        val tokenValue = value["token"] ?: ""
        val nextIsLoggedIn = nextUserId.isNotEmpty() && tokenValue.isNotEmpty()
        val rawNickname = value["nickname"]
        val nextNickname = if (rawNickname == null) "" else percentDecode(rawNickname) ?: rawNickname
        val nextAvatarURL = value["avatar"]?.takeIf { it.isNotEmpty() }
        val vip = (value["vipType"] ?: value["vip_type"] ?: "0").toIntOrNull() ?: 0

        // 与 Swift 一致：整份快照一次性写入，避免 UI 看到半更新状态
        writeState(nextUserId, nextIsLoggedIn, nextNickname, nextAvatarURL, if (vip > 0) "VIP" else null)
    }

    /** 必须在 [lock] 内调用。 */
    private fun writeState(
        userId: String,
        isLoggedIn: Boolean,
        nickname: String,
        avatarURL: String?,
        vipBadge: String?,
    ) {
        if (_userId.value != userId) _userId.value = userId
        if (_loggedIn.value != isLoggedIn) _loggedIn.value = isLoggedIn
        if (_nickname.value != nickname) _nickname.value = nickname
        if (_avatarURL.value != avatarURL) _avatarURL.value = avatarURL
        if (_vipBadge.value != vipBadge) _vipBadge.value = vipBadge
    }

    // MARK: - 设备参数

    /** 补齐 GUID / MID / MAC / DEV；已存在的不覆盖。 */
    fun ensureDevice(source: Map<String, String>): Map<String, String> {
        val obj = LinkedHashMap(source)
        val guid = obj["KUGOU_API_GUID"]?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        obj["KUGOU_API_GUID"] = guid
        val mid = obj["KUGOU_API_MID"]?.takeIf { it.isNotEmpty() } ?: calculateMid(guid)
        obj["KUGOU_API_MID"] = mid
        val mac = obj["KUGOU_API_MAC"]?.takeIf { it.isNotEmpty() } ?: randomString(12)
        obj["KUGOU_API_MAC"] = mac
        val dev = obj["KUGOU_API_DEV"]?.takeIf { it.isNotEmpty() } ?: randomString(16)
        obj["KUGOU_API_DEV"] = dev
        return obj
    }

    private val defaultMid: String by lazy {
        val seed = try {
            Settings.Secure.getString(
                BeansApplication.instance.contentResolver,
                Settings.Secure.ANDROID_ID,
            )
        } catch (e: Exception) {
            null
        }
        calculateMid(seed?.takeIf { it.isNotEmpty() } ?: "beans-kugou")
    }

    private fun calculateMid(seed: String): String {
        val hex = seed.toByteArray(Charsets.UTF_8).md5Hex()
        val prefix = hex.take(15)
        // 对应 Swift `UInt64(prefix, radix: 16)`：全为十六进制时转十进制字符串
        val value = prefix.toULongOrNull(16)
        return value?.toString() ?: hex
    }

    private fun randomString(length: Int): String {
        val chars = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val sb = StringBuilder(length)
        repeat(length) { sb.append(chars[Random.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun ByteArray.md5Hex(): String {
        val digest = MessageDigest.getInstance("MD5").digest(this)
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) sb.append(String.format(Locale.US, "%02x", byte.toInt() and 0xFF))
        return sb.toString()
    }

    /** `raw.removingPercentEncoding`：非法转义序列返回 null（保持原串）。 */
    private fun percentDecode(raw: String): String? {
        if (raw.indexOf('%') < 0) return raw
        var i = 0
        val bytes = ArrayList<Byte>()
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%') {
                if (i + 2 >= raw.length) return null
                val value = raw.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.add(value.toByte())
                i += 3
            } else {
                bytes.add(c.code.toByte())
                i += 1
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}

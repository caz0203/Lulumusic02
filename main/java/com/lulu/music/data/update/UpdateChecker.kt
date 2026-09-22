package com.lulu.music.data.update

import com.lulu.music.BuildConfig
import com.lulu.music.data.net.bool
import com.lulu.music.data.net.str
import com.lulu.music.data.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 服务器清单里描述的一个新版本。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val url: String,
    val force: Boolean,
)

/**
 * 应用内更新检查（Android）。
 *
 * 设计原则（对齐 iOS 的 检查更新）：
 *  - **绝不抛异常**：网络错误、HTTP 非 2xx、JSON 解析失败、字段缺失、URL 非法，
 *    一律返回 [Result.Failed]，调用方在最坏情况下什么都不显示（静默失败）。
 *  - **不阻塞启动**：调用方在首帧之后于后台协程里 `check()`，本地版本码直接读
 *    [BuildConfig]，不需要任何 IO。
 *  - 纯数据层：本文件不引用任何 Android UI（只有 SharedPreferences 作为持久化）。
 */
object UpdateChecker {

    /** 检查结果。 */
    sealed interface Result {
        data object UpToDate : Result
        data class Available(val info: UpdateInfo) : Result
        data object Failed : Result // 网络错误 / 解析失败 / 未配置
    }

    /** 「以后再说」忽略的版本码存放位置（SharedPreferences 键）。 */
    private const val KEY_SKIPPED_VERSION = "update_skipped_version_code"

    /** 清单里要求的字段：`versionCode` 与可用的 `url`。 */
    private const val KEY_VERSION_CODE = "versionCode"
    private const val KEY_VERSION_NAME = "versionName"
    private const val KEY_NOTES = "notes"
    private const val KEY_URL = "url"
    private const val KEY_FORCE = "force"

    /**
     * 检查更新专用的 HTTP 客户端：沿用 [com.lulu.music.data.net.Http] 的连接池，
     * 只把超时收紧到 [UpdateConfig.TIMEOUT_MS]，避免拖慢启动。
     */
    private val client: OkHttpClient by lazy {
        com.lulu.music.data.net.Http.client.newBuilder()
            .callTimeout(UpdateConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(UpdateConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(UpdateConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 本地版本码，来自 [BuildConfig.VERSION_CODE]。 */
    val currentVersionCode: Int = BuildConfig.VERSION_CODE

    /** 本地版本名，来自 [BuildConfig.VERSION_NAME]。 */
    val currentVersionName: String = BuildConfig.VERSION_NAME

    /**
     * 拉取清单并比较版本。**绝不抛异常**；任何失败都返回 [Failed]。
     * 若返回的版本码 <= 本地版本码，返回 [UpToDate]。
     */
    suspend fun check(): Result {
        val url = UpdateConfig.MANIFEST_URL
        if (url.isBlank()) return Result.Failed
        return try {
            val json = fetchManifest(url)
            val remoteCode = json.getInt(KEY_VERSION_CODE)
            val downloadUrl = json.str(KEY_URL).trim()
            // 没有可用下载地址时绝不提示更新，否则用户会点到一个打不开的按钮。
            if (downloadUrl.isBlank()) return Result.Failed
            if (remoteCode <= currentVersionCode) return Result.UpToDate

            Result.Available(
                UpdateInfo(
                    versionCode = remoteCode,
                    versionName = json.str(KEY_VERSION_NAME).ifBlank { remoteCode.toString() },
                    notes = json.str(KEY_NOTES).trim(),
                    url = downloadUrl,
                    force = json.bool(KEY_FORCE, false),
                ),
            )
        } catch (e: Throwable) {
            // 网络错误 / 解析失败 / 未配置 —— 全部静默降级。
            Result.Failed
        }
    }

    /** 在 IO 线程上拉取并解析清单文本。 */
    private suspend fun fetchManifest(url: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            JSONObject(body.trim().removePrefix("\uFEFF"))
        }
    }

    /** 被用户「以后再说」忽略的版本码；忽略后同一版本不再弹窗。0 = 没有忽略过。 */
    fun skippedVersionCode(): Int =
        runCatching { Prefs.readString(KEY_SKIPPED_VERSION).trim().toInt() }.getOrDefault(0)

    /** 记住「以后再说」：等价版本的清单不再弹窗（更高版本码仍然会弹）。 */
    fun skip(versionCode: Int) {
        runCatching { Prefs.writeString(KEY_SKIPPED_VERSION, versionCode.toString()) }
    }

    /** 清除忽略记录（例如「我的 → 检查更新」手动检查时先调它）。 */
    fun clearSkip() {
        runCatching { Prefs.remove(KEY_SKIPPED_VERSION) }
    }
}

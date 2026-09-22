package com.lulu.music.data.backup

import android.content.Context
import android.os.Build
import android.util.Base64
import coil.imageLoader
import com.lulu.music.BuildConfig
import com.lulu.music.data.auth.KugouMusicAuth
import com.lulu.music.data.auth.QQMusicAuth
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.store.DownloadRecord
import com.lulu.music.data.store.Prefs
import com.lulu.music.data.stats.UserStatsStore
import com.lulu.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 备份与恢复（Android）。
 *
 * 备份文件是**单个 JSON 文档**（*不是* zip）：所有内容序列化进同一个对象，
 * 需要随备份携带的壁纸图片以 base64 内联在 `images[].base64` 字段里。
 * 这样导入方只需要读一个文件、解析一次，损坏任意一段都不会让整份备份变成不可读的二进制。
 *
 * 顶层结构：
 * ```json
 * {
 *   "format":  "lulumusic.backup",
 *   "version": 1,
 *   "createdAt": 1730000000000,
 *   "appVersion": "1.8.1", "appVersionCode": 45, "sdkInt": 34,
 *   "settings": { "beans.themeMode": {"t":"s","v":"DARK"}, ... },
 *   "data":     { "favorites": {...}, "history": [...], "downloads": [...], "searchHistory": [...] },
 *   "imageIncluded": false,
 *   "images": [],
 *   "auth":  { ... } | null,
 *   "stats": { ... } | null
 * }
 * ```
 *
 * 包含内容与开关的关系（与设置页「备份与恢复」两个开关一一对应）：
 *  - **始终包含**：`SettingsStore` 的全部普通偏好 + 本地收藏 / 最近播放 / 下载索引 / 搜索历史
 *    + 用户统计（[UserStatsStore]）。
 *  - **`备份登录信息` 打开时**：追加 `auth`（QQ / 酷狗 / 网易云能读到的登录态）。
 *  - **`备份壁纸图片` 打开时**：追加 `images`（base64 壁纸字节，以及它们在 `filesDir` 下的相对路径）。
 *    关闭时 `images` 是空数组且 `imageIncluded == false`——「不含图片字节」在文件里是显式的。
 *
 * 所有 public 方法**绝不抛异常**：解析/写入失败都会退化为 [Result.failure]，由 UI 弹提示。
 *
 * 已知限制（对应到具体的 store，不去动别人负责的文件）：
 *  - [com.lulu.music.data.store.FavoritesStore] / [com.lulu.music.data.store.PlayHistoryStore] /
 *    [com.lulu.music.data.store.DownloadStore] / [com.lulu.music.data.store.SearchHistoryStore]
 *    只暴露 `load()` + 读写单个条目的方法，没有 import/export。因此这里按它们**持久化时用的同一组
 *    KEY 和同一套 `Prefs` 序列化**直接读写 SharedPreferences（导出走 `Prefs.readList`，导入走
 *    `Prefs.writeList`，格式与 store 自己写的完全一致；重启后 store 的 `load()` 会读到导入的内容）。
 *  - 网易云登录态（`NetEaseApi`）只公开了 `importWebCookies(...)`，**没有**读取原始 Cookie 的公开
 *    API，所以导出时无法得到它；导入时仅当备份里确实带有网易云 Cookie 才会尝试回填。
 *  - 第三方音源（`UnblockSourceStore`）由另一个 agent 负责，这里不读写。
 */
object BackupManager {

    const val FORMAT = "lulumusic.backup"

    /** 结构版本；将来结构不兼容时 +1，导入侧据此拒绝。 */
    const val VERSION = 1

    /** 分享备份文件时使用的 FileProvider authority（见 AndroidManifest + res/xml/file_paths.xml）。 */
    const val AUTHORITY = "com.lulu.music.fileprovider"

    // ---- 与各 store 持久化时一致的 KEY（见各 store 文件里的 private const） ----
    private const val KEY_FAV_NETEASE = "beans.fav.netease.v1"
    private const val KEY_FAV_QQ = "beans.fav.qq.v1"
    private const val KEY_FAV_KUGOU = "beans.fav.kugou.v1"
    private const val KEY_HISTORY = "beans.play.history.v1"
    private const val KEY_DOWNLOADS = "beans.downloads.v1"
    private const val KEY_SEARCH_HISTORY = "beans.search.history.v1"

    /** 稳定的每台设备安装标识（纯应用内生成，不读取任何硬件序列号）。 */
    private const val KEY_DEVICE_ID = "beans.backup.deviceId"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    // ---------------------------------------------------------------------------------------
    // MARK: - 导出
    // ---------------------------------------------------------------------------------------

    /** 备份内容清单（给 UI 显示 / 给文件名用）。 */
    data class Options(
        val includeLogin: Boolean = false,
        val includeImages: Boolean = false,
    )

    /** 导出/导入结果；[message] 已本地化为中文，UI 直接展示。 */
    sealed interface Result {
        data class Exported(val file: File, val bytes: Long) : Result
        data class Imported(val warnings: List<String>) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * 导出到 `cacheDir/backups/` 下的一个 JSON 文件（调用方随后用 [android.content.Intent.ACTION_SEND]
     * + FileProvider 分享）。**绝不抛异常。**
     */
    suspend fun export(context: Context, options: Options): Result = withContext(Dispatchers.IO) {
        runCatching {
            val text = buildJson(options).toString()
            val dir = File(context.cacheDir, "backups").apply { mkdirs() }
            // 旧备份顺手清掉，避免缓存目录无限增长。
            dir.listFiles()?.forEach { stale -> runCatching { stale.delete() } }
            val file = File(dir, "LuluMusic-backup-${fileStamp.format(Date())}.json")
            file.writeText(text, Charsets.UTF_8)
            Result.Exported(file = file, bytes = file.length())
        }.getOrElse { error ->
            Result.Failed("导出失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** 只组装备份 JSON（便于单测 / 调试）；导出文件与剪贴板文本走的是同一份实现。 */
    fun buildJson(options: Options): JsonObject {
        val warnings = mutableListOf<String>()
        val settings = snapshotSettings()
        val data = buildJsonObject {
            put("favorites", buildJsonObject {
                put("netease", raw(KEY_FAV_NETEASE))
                put("qq", raw(KEY_FAV_QQ))
                put("kugou", raw(KEY_FAV_KUGOU))
            })
            put("history", raw(KEY_HISTORY))
            put("downloads", raw(KEY_DOWNLOADS))
            put("searchHistory", raw(KEY_SEARCH_HISTORY))
        }

        val images = if (options.includeImages) collectImages() else emptyList()
        if (options.includeImages && images.isEmpty()) {
            warnings += "没有找到可备份的壁纸图片"
        }

        val auth: JsonObject? = if (options.includeLogin) {
            val (node, note) = snapshotAuth()
            if (note != null) warnings += note
            node
        } else {
            null
        }

        val stats: JsonElement? = runCatching {
            Json.parseToJsonElement(UserStatsStore.exportJson())
        }.getOrElse { error ->
            warnings += "用户统计导出失败：${error.message ?: "未知错误"}"
            null
        }

        return buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            put("version", JsonPrimitive(VERSION))
            put("createdAt", JsonPrimitive(System.currentTimeMillis()))
            put("appVersion", JsonPrimitive(BuildConfig.VERSION_NAME))
            put("appVersionCode", JsonPrimitive(BuildConfig.VERSION_CODE))
            put("sdkInt", JsonPrimitive(Build.VERSION.SDK_INT))
            put("imageIncluded", JsonPrimitive(images.isNotEmpty()))
            put("includeLogin", JsonPrimitive(options.includeLogin))
            put("includeImages", JsonPrimitive(options.includeImages))
            put("settings", settings)
            put("data", data)
            put("images", JsonArray(images))
            put("auth", auth ?: JsonNull)
            put("stats", stats ?: JsonNull)
            // warnings 只是给用户看的诊断信息，导入侧不依赖它。
            put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
        }
    }

    // ---------------------------------------------------------------------------------------
    // MARK: - 导入
    // ---------------------------------------------------------------------------------------

    /**
     * 从 `content://` URI 读取并应用一份备份。**绝不抛异常**：
     * 读取失败 / JSON 损坏 / 格式不符 / 结构版本不支持都返回 [Result.Failed]。
     */
    suspend fun import(context: Context, text: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val format = root["format"]?.jsonPrimitive?.contentOrNull ?: ""
            if (format != FORMAT) {
                return@runCatching Result.Failed("不是 LuluMusic 的备份文件（format=$format）")
            }
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: 0
            if (version > VERSION) {
                return@runCatching Result.Failed("备份来自更新的版本（v$version），请先升级 App")
            }

            val warnings = mutableListOf<String>()
            root["settings"]?.let { element ->
                runCatching { restoreSettings(element.jsonObject) }
                    .onFailure { warnings += "偏好恢复部分失败：${it.message ?: "未知错误"}" }
            }
            root["data"]?.let { element ->
                runCatching { restoreData(element.jsonObject) }
                    .onFailure { warnings += "本地数据恢复部分失败：${it.message ?: "未知错误"}" }
            }
            val imageCount = restoreImages(context, root)
            if (imageCount > 0) {
                // 恢复完图片字节再回填路径，保证设置指向的文件确实存在。
                runCatching { restoreWallpaperPath(context, root) }
            }
            root["auth"]?.let { element ->
                if (element !is JsonNull) {
                    val note = runCatching { restoreAuth(element.jsonObject) }
                        .getOrElse { "登录信息恢复失败：${it.message ?: "未知错误"}" }
                    if (note != null) warnings += note
                }
            }
            root["stats"]?.let { element ->
                if (element !is JsonNull) {
                    val ok = runCatching { UserStatsStore.importJson(element.toString()) }
                        .getOrDefault(false)
                    if (!ok) warnings += "用户统计恢复失败"
                }
            }
            Result.Imported(warnings)
        }.getOrElse { error ->
            Result.Failed("导入失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // MARK: - 偏好快照 / 恢复
    // ---------------------------------------------------------------------------------------

    /**
     * 读取 `SettingsStore` 当前的全部偏好。
     *
     * 这里读的都是各 flow 的 Value（DataStore 的 `stateIn(Eagerly)` 已经有一份内存快照），
     * **没有**新增任何 key、也**没有**把任何 `by lazy` 改成饿汉式初始化。
     */
    private fun snapshotSettings(): JsonObject = buildJsonObject {
        put("beans.themeMode", JsonPrimitive(SettingsStore.themeMode.value.name))
        put("beans.uiStyle", JsonPrimitive(SettingsStore.uiStyle.value.name))
        put("beans.language", JsonPrimitive(SettingsStore.language.value.name))
        put("beans.accentHex", JsonPrimitive(SettingsStore.accentHex.value))
        put("beans.labelColorHex", JsonPrimitive(SettingsStore.labelColorHex.value))
        put("beans.commentColorHex", JsonPrimitive(SettingsStore.commentColorHex.value))
        put("beans.backgroundSyncAll", JsonPrimitive(SettingsStore.backgroundSyncAll.value))
        put("beans.customBackgroundHex", JsonPrimitive(SettingsStore.customBackgroundHex.value))
        put("beans.backgroundImagePath", JsonPrimitive(SettingsStore.backgroundImagePath.value))
        put("beans.homeWallpaperBlur", JsonPrimitive(SettingsStore.wallpaperBlur.value))
        put("beans.audioQuality", JsonPrimitive(SettingsStore.audioQuality.value))
        put("beans.playbackSource", JsonPrimitive(SettingsStore.playbackSource.value))
        put("beans.enableHighRefresh", JsonPrimitive(SettingsStore.highRefresh.value))
        put("beans.hapticsEnabled", JsonPrimitive(SettingsStore.hapticsEnabled.value))
        put("beans.playback.autoResumeLast", JsonPrimitive(SettingsStore.autoResumeLast.value))
        put("beans.playbackSpeed", JsonPrimitive(SettingsStore.playbackSpeed.value))
        put("beans.playback.shuffle", JsonPrimitive(SettingsStore.shuffle.value))
        put("beans.playback.repeat", JsonPrimitive(SettingsStore.repeatMode.value))
        put("beans.sleepTimerMinutes", JsonPrimitive(SettingsStore.sleepTimerMinutes.value))
        put("beans.eq.enabled", JsonPrimitive(SettingsStore.eqEnabled.value))
        put("beans.eq.preset", JsonPrimitive(SettingsStore.eqPreset.value))
        put("beans.eq.bands", JsonPrimitive(SettingsStore.eqBands.value))
        put("beans.eq.bassBoost", JsonPrimitive(SettingsStore.bassBoost.value))
        put("beans.eq.virtualizer", JsonPrimitive(SettingsStore.virtualizer.value))
        put("beans.lyrics.translation", JsonPrimitive(SettingsStore.lyricsTranslation.value))
        put("beans.lyrics.fontSize", JsonPrimitive(SettingsStore.lyricsFontSize.value))
        put("beans.lyrics.align", JsonPrimitive(SettingsStore.lyricsAlign.value))
        put("beans.lyricOffset", JsonPrimitive(SettingsStore.lyricOffset.value))
        put("beans.playerLayout", JsonPrimitive(SettingsStore.playerLayout.value))
        put("beans.enabledProviders", JsonPrimitive(SettingsStore.enabledProviders.value))
        put("beans.homeSource", JsonPrimitive(SettingsStore.homeProvider.value))
        put("beans.tabLabelsVisible", JsonPrimitive(SettingsStore.tabLabelsVisible.value))
    }

    /** 逐条恢复偏好；缺失的键保持现状（旧备份 / 手工精简过的备份都能用）。 */
    private fun restoreSettings(settings: JsonObject) {
        fun text(key: String): String? = settings[key]?.jsonPrimitive?.contentOrNull
        fun flag(key: String): Boolean? = settings[key]?.jsonPrimitive?.booleanOrNull
        fun number(key: String): Float? = settings[key]?.jsonPrimitive?.floatOrNull
        fun whole(key: String): Int? = settings[key]?.jsonPrimitive?.intOrNull

        text("beans.themeMode")?.let { value ->
            runCatching { com.lulu.music.data.prefs.BeansThemeMode.valueOf(value) }
                .onSuccess { SettingsStore.setThemeMode(it) }
        }
        text("beans.uiStyle")?.let { value ->
            runCatching { com.lulu.music.data.prefs.BeansUIStyle.valueOf(value) }
                .onSuccess { SettingsStore.setUIStyle(it) }
        }
        text("beans.language")?.let { value ->
            runCatching { com.lulu.music.data.prefs.AppLanguage.valueOf(value) }
                .onSuccess { SettingsStore.setLanguage(it) }
        }
        text("beans.accentHex")?.let { SettingsStore.setAccentHex(it) }
        text("beans.labelColorHex")?.let { SettingsStore.setLabelColorHex(it) }
        text("beans.commentColorHex")?.let { SettingsStore.setCommentColorHex(it) }
        flag("beans.backgroundSyncAll")?.let { SettingsStore.setBackgroundSyncAll(it) }
        text("beans.customBackgroundHex")?.let { SettingsStore.setCustomBackgroundHex(it) }
        number("beans.homeWallpaperBlur")?.let { SettingsStore.setWallpaperBlur(it) }
        text("beans.audioQuality")?.let { SettingsStore.setAudioQuality(it) }
        text("beans.playbackSource")?.let { SettingsStore.setPlaybackSource(it) }
        flag("beans.enableHighRefresh")?.let { SettingsStore.setHighRefresh(it) }
        flag("beans.hapticsEnabled")?.let { SettingsStore.setHapticsEnabled(it) }
        flag("beans.playback.autoResumeLast")?.let { SettingsStore.setAutoResume(it) }
        number("beans.playbackSpeed")?.let { SettingsStore.setPlaybackSpeed(it) }
        flag("beans.playback.shuffle")?.let { SettingsStore.setShuffle(it) }
        text("beans.playback.repeat")?.let { SettingsStore.setRepeatMode(it) }
        whole("beans.sleepTimerMinutes")?.let { SettingsStore.setSleepTimerMinutes(it) }
        flag("beans.eq.enabled")?.let { SettingsStore.setEqEnabled(it) }
        text("beans.eq.preset")?.let { SettingsStore.setEqPreset(it) }
        text("beans.eq.bands")?.let { SettingsStore.setEqBands(it) }
        whole("beans.eq.bassBoost")?.let { SettingsStore.setBassBoost(it) }
        whole("beans.eq.virtualizer")?.let { SettingsStore.setVirtualizer(it) }
        flag("beans.lyrics.translation")?.let { SettingsStore.setLyricsTranslation(it) }
        number("beans.lyrics.fontSize")?.let { SettingsStore.setLyricsFontSize(it) }
        text("beans.lyrics.align")?.let { SettingsStore.setLyricsAlign(it) }
        number("beans.lyricOffset")?.let { SettingsStore.setLyricOffset(it) }
        text("beans.playerLayout")?.let { SettingsStore.setPlayerLayout(it) }
        text("beans.enabledProviders")?.let { SettingsStore.setEnabledProviders(it) }
        text("beans.homeSource")?.let { SettingsStore.setHomeProvider(it) }
        flag("beans.tabLabelsVisible")?.let { SettingsStore.setTabLabelsVisible(it) }

        // 壁纸 / 背景路径**不在这里**恢复：如果备份带图，由 restoreWallpaperPath 回填到真实文件；
        // 如果备份不带图，就保留本机当前设置，避免指向一个本机不存在的路径。
    }

    // ---------------------------------------------------------------------------------------
    // MARK: - 本地数据（收藏 / 历史 / 下载 / 搜索历史）
    // ---------------------------------------------------------------------------------------

    /** 直接取 store 自己写进 SharedPreferences 的原始 JSON 文本，导入时原样写回。 */
    private fun raw(key: String): JsonElement =
        runCatching { Json.parseToJsonElement(Prefs.readString(key, "[]")) }
            .getOrElse { JsonArray(emptyList()) }

    private fun restoreData(data: JsonObject) {
        val favorites = data["favorites"]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        }
        favorites?.get("netease")?.let { writeRaw(KEY_FAV_NETEASE, it) }
        favorites?.get("qq")?.let { writeRaw(KEY_FAV_QQ, it) }
        favorites?.get("kugou")?.let { writeRaw(KEY_FAV_KUGOU, it) }
        data["history"]?.let { writeRaw(KEY_HISTORY, it) }
        data["downloads"]?.let { writeRaw(KEY_DOWNLOADS, it) }
        data["searchHistory"]?.let { writeRaw(KEY_SEARCH_HISTORY, it) }
    }

    /**
     * 写回原始 JSON。先按对应 store 的序列化器试解析一次：解析不了的（半截 / 结构不符）直接拒绝，
     * 绝不把损坏内容写进 SharedPreferences。
     *
     * 写回后本会话内 `FavoritesStore` 等已经 `load()` 过的对象不会再刷新（它们的 `loaded` 标记是私有的，
     * 由各自文件的负责 agent 掌管），下次启动会读到导入的内容——这一点在 UI 提示里已写明。
     */
    private fun writeRaw(key: String, element: JsonElement) {
        val text = element.toString()
        val decoded = runCatching {
            when (key) {
                KEY_FAV_NETEASE, KEY_FAV_QQ, KEY_FAV_KUGOU, KEY_HISTORY ->
                    json.decodeFromString(ListSerializer(Song.serializer()), text)
                KEY_DOWNLOADS ->
                    json.decodeFromString(ListSerializer(DownloadRecord.serializer()), text)
                KEY_SEARCH_HISTORY ->
                    json.decodeFromString(ListSerializer(String.serializer()), text)
                else -> emptyList<Unit>()
            }
        }
        if (decoded.isFailure) throw IllegalArgumentException("备份里的 $key 不是有效数据")
        Prefs.writeString(key, text)
    }

    // ---------------------------------------------------------------------------------------
    // MARK: - 壁纸图片
    // ---------------------------------------------------------------------------------------

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    /**
     * 收集 `filesDir/wallpapers/` 下的图片，base64 内联进备份。
     *
     * 壁纸目录与设置页「选择壁纸」写入的目录一致（`File(context.filesDir, "wallpapers")`），
     * 另外把 `backgroundImagePath` 直接指向的单个文件也带上（用户可能选过别处的图）。
     */
    private fun collectImages(): List<JsonObject> {
        val context = com.lulu.music.BeansApplication.instance
        val filesDir = context.filesDir.absolutePath
        val candidates = linkedMapOf<String, File>()

        File(context.filesDir, "wallpapers").listFiles()?.forEach { file ->
            if (file.isFile && file.extension.lowercase(Locale.US) in imageExtensions) {
                candidates[file.absolutePath] = file
            }
        }
        val currentPath = SettingsStore.backgroundImagePath.value
        if (currentPath.isNotBlank()) {
            val file = File(currentPath)
            if (file.isFile) candidates[file.absolutePath] = file
        }

        return candidates.values.mapNotNull { file ->
            runCatching {
                val bytes = file.readBytes()
                buildJsonObject {
                    put("name", JsonPrimitive(file.name))
                    // 相对 filesDir 的路径：导入端据此把文件放回同一位置。
                    put(
                        "path",
                        JsonPrimitive(
                            file.absolutePath
                                .removePrefix(filesDir)
                                .trimStart(File.separatorChar)
                                .replace(File.separatorChar, '/'),
                        ),
                    )
                    put("bytes", JsonPrimitive(bytes.size))
                    put("base64", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                }
            }.getOrNull()
        }
    }

    /** 把备份里的图片写回 `filesDir`，返回成功写出的数量。 */
    private fun restoreImages(context: Context, root: JsonObject): Int {
        val array = runCatching { root["images"]?.jsonArray }.getOrNull() ?: return 0
        var written = 0
        for (element in array) {
            val node = runCatching { element.jsonObject }.getOrNull() ?: continue
            val encoded = node["base64"]?.jsonPrimitive?.contentOrNull ?: continue
            val relative = node["path"]?.jsonPrimitive?.contentOrNull
                ?: node["name"]?.jsonPrimitive?.contentOrNull
                ?: continue
            val ok = runCatching {
                val target = File(context.filesDir, relative.replace('/', File.separatorChar))
                target.parentFile?.mkdirs()
                target.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
                true
            }.getOrDefault(false)
            if (ok) written += 1
        }
        return written
    }

    /**
     * 备份里带了图片时，把壁纸设置指回刚写出的文件。
     *
     * 优先用备份记录的原路径（图片就是按这个相对路径写回去的）；原路径对应的文件不存在时，
     * 退回备份里记的第一张图。备份没有带图时**什么也不做**：保留本机当前壁纸设置。
     */
    private fun restoreWallpaperPath(context: Context, root: JsonObject) {
        val original = root["settings"]?.let { element ->
            runCatching { element.jsonObject["beans.backgroundImagePath"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
        }
        if (!original.isNullOrBlank() && File(original).isFile) {
            SettingsStore.setBackgroundImagePath(original)
            return
        }
        val array = runCatching { root["images"]?.jsonArray }.getOrNull() ?: return
        val first = array.firstOrNull()?.let { element ->
            runCatching { element.jsonObject["path"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        } ?: return
        val restored = File(context.filesDir, first.replace('/', File.separatorChar))
        if (restored.isFile) SettingsStore.setBackgroundImagePath(restored.absolutePath)
    }

    // ---------------------------------------------------------------------------------------
    // MARK: - 登录信息
    // ---------------------------------------------------------------------------------------

    /**
     * 读取能读到的登录态。
     *
     * - QQ：`cookieHeader` 是公开 API，直接存；恢复时用 `parseCookieHeader` + `importCookies` 走官方入口。
     * - 酷狗：只有 `userId` / `token` / `mid` / `dfid` 这几个公开只读属性，恢复走 `saveLogin` + `saveDeviceDFID`。
     * - 网易云：[com.lulu.music.data.api.NetEaseApi] **没有**公开的 Cookie 读取 API，
     *   导出时只能记一条说明；导入时若备份里恰好带 `cookies` 才回填。
     */
    private fun snapshotAuth(): Pair<JsonObject, String?> {
        var note: String? = "网易云登录态没有公开的读取 API，本次备份未包含它"
        val qq: JsonObject? = runCatching {
            if (!QQMusicAuth.isLoggedIn) null else buildJsonObject {
                put("cookieHeader", JsonPrimitive(QQMusicAuth.cookieHeader))
                put("nickname", JsonPrimitive(QQMusicAuth.nickname))
            }
        }.getOrNull()

        val kugou: JsonObject? = runCatching {
            if (!KugouMusicAuth.isLoggedIn) null else buildJsonObject {
                put("userId", JsonPrimitive(KugouMusicAuth.userId))
                put("token", JsonPrimitive(KugouMusicAuth.token))
                put("mid", JsonPrimitive(KugouMusicAuth.mid))
                put("dfid", JsonPrimitive(KugouMusicAuth.dfid))
                put("nickname", JsonPrimitive(KugouMusicAuth.nickname))
                put("vipType", JsonPrimitive(KugouMusicAuth.vipType))
            }
        }.getOrNull()

        val node = buildJsonObject {
            put("qq", qq ?: JsonNull)
            put("kugou", kugou ?: JsonNull)
            put("neteaseReadable", JsonPrimitive(false))
        }
        return node to note
    }

    /** 回填登录态；返回需要提示给用户的说明（null = 全部成功）。 */
    private fun restoreAuth(auth: JsonObject): String? {
        val notes = mutableListOf<String>()

        auth["qq"]?.let { element ->
            if (element is JsonNull) return@let
            val header = runCatching {
                element.jsonObject["cookieHeader"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (!header.isNullOrBlank()) {
                val nickname = runCatching {
                    element.jsonObject["nickname"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                val cookies = QQMusicAuth.parseCookieHeader(header)
                if (cookies.isEmpty()) {
                    notes += "QQ 登录信息为空，已跳过"
                } else {
                    runCatching { QQMusicAuth.importCookies(cookies, nickname) }
                        .onFailure { notes += "QQ 登录信息恢复失败" }
                }
            }
        }

        auth["kugou"]?.let { element ->
            if (element is JsonNull) return@let
            val node = runCatching { element.jsonObject }.getOrNull() ?: return@let
            val userId = node["userId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val token = node["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (userId.isNotBlank() && token.isNotBlank()) {
                val nickname = node["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val vip = node["vipType"]?.jsonPrimitive?.intOrNull ?: 0
                runCatching { KugouMusicAuth.saveLogin(userId, token, nickname, "", vip) }
                    .onFailure { notes += "酷狗登录信息恢复失败" }
                node["dfid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { dfid ->
                    runCatching { KugouMusicAuth.saveDeviceDFID(dfid) }
                }
            }
        }

        auth["netease"]?.let { element ->
            if (element is JsonNull) return@let
            val cookies = runCatching { element.jsonObject }.getOrNull() ?: return@let
            val dict = cookies.entries.mapNotNull { (key, value) ->
                value.jsonPrimitive.contentOrNull?.let { key to it }
            }.toMap()
            if (dict.isEmpty()) {
                notes += "备份里没有网易云登录信息"
            } else {
                runCatching { com.lulu.music.data.api.NetEaseApi.importWebCookies(dict) }
                    .onFailure { notes += "网易云登录信息恢复失败" }
            }
        }

        return notes.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    // ---------------------------------------------------------------------------------------
    // MARK: - 设备标识 / 环境
    // ---------------------------------------------------------------------------------------

    /**
     * 稳定的每安装标识：优先复用已保存的值，否则生成一个随机 UUID 并持久化。
     * 不使用 `Settings.Secure.ANDROID_ID`（可被重置）也不使用任何硬件序列号。
     */
    fun deviceId(): String {
        val existing = runCatching { Prefs.readString(KEY_DEVICE_ID) }.getOrDefault("")
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString().replace("-", "").take(16)
        runCatching { Prefs.writeString(KEY_DEVICE_ID, generated) }
        return generated
    }

    // ---------------------------------------------------------------------------------------
    // MARK: - 清除图片缓存
    // ---------------------------------------------------------------------------------------

    /**
     * 清掉 Coil 的内存 / 磁盘缓存与 App 自己的图片缓存目录，返回释放的字节数。
     *
     * 只动缓存目录，**不碰** `filesDir/wallpapers`（那是用户数据，不是缓存）。
     * 磁盘上无法精确统计的部分按目录大小估算。
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearImageCache(context: Context): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        // 1. Coil 的内存缓存（不占磁盘，但清掉才能真正「立刻少占内存」）
        runCatching {
            context.imageLoader.memoryCache?.clear()
        }
        // 2. Coil 的磁盘缓存
        runCatching {
            context.imageLoader.diskCache?.clear()
        }
        // 3. 磁盘上的图片缓存目录（Coil 的 image_cache + Android 的 image cache）
        val dirs = listOf(
            File(context.cacheDir, "image_cache"),
            File(context.cacheDir, "coil_cache"),
            File(context.filesDir, "image_cache"),
        )
        dirs.forEach { dir ->
            freed += runCatching { directorySize(dir) }.getOrDefault(0L)
            runCatching { dir.deleteRecursively() }
        }
        freed
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { child ->
            if (child.isDirectory) directorySize(child) else child.length()
        } ?: 0L
    }

    /** 人类可读的字节数（1.2 MB / 340 KB）。 */
    fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

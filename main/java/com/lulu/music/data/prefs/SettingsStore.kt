package com.lulu.music.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lulu.music.data.store.CrashLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Appearance mode, mirroring the iOS `BeansThemeMode`. */
enum class BeansThemeMode { SYSTEM, LIGHT, DARK }

/**
 * UI style, mirroring the iOS `BeansUIStyle`.
 *
 * 参考图（`UI-PLAN.md` 第 4 节）是四个值：默认液态 / 磨砂玻璃 / 紧凑淡雅 / Apple 简洁。
 * 每个值都必须在渲染层有真实分支（见 `ui/components/BeansGlass.kt` 的 [BeansGlassSpec]），
 * 否则就是「主题自定义无效」的翻版。
 */
enum class BeansUIStyle(val titleZh: String, val titleEn: String) {
    LIQUID("默认液态", "Liquid"),
    FROSTED("磨砂玻璃", "Frosted"),
    COMPACT("紧凑淡雅", "Compact"),
    NATIVE_CLEAN("Apple 简洁", "Apple Clean"),
}

/** 全局漂浮特效（参考图「更多外观与主页设置」里的分段控件）。 */
enum class BeansFloatingEffect(val titleZh: String, val titleEn: String) {
    OFF("关闭", "Off"),
    SNOW("雪花", "Snow"),
    TEXT("文字 / Emoji", "Text / Emoji"),
}

/** 底栏图标样式（参考图分段：Apple Music / SF Symbols / 圆润样式）。 */
enum class BeansTabIconStyle(val titleZh: String, val titleEn: String) {
    APPLE_MUSIC("Apple Music", "Apple Music"),
    SF_SYMBOLS("SF Symbols", "SF Symbols"),
    ROUNDED("圆润样式", "Rounded"),
}

/** App language. */
enum class AppLanguage(val tag: String) {
    CHINESE("zh-Hans"),
    ENGLISH("en"),
}

/**
 * Central DataStore-backed preference store.
 *
 * Mirrors the `@AppStorage` keys used throughout the iOS app so that the Android port keeps the
 * same user-visible behaviour (theme, wallpapers, playback, equalizer, lyrics, platforms).
 *
 * IMPORTANT — initialisation order:
 * [store] is only available after [init] has been called from `Application.onCreate`. Every
 * StateFlow below is therefore declared with `by lazy`, so that constructing this object does NOT
 * touch [store]. Declaring them as eager property initialisers dereferences the `lateinit` store
 * while the object is still being constructed, which throws `ExceptionInInitializerError` at
 * launch (a runtime failure the Kotlin compiler cannot catch). Keep the `by lazy` delegates.
 */
object SettingsStore {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("beans_settings")

    private lateinit var store: DataStore<Preferences>

    /**
     * Application Context。
     *
     * 只有「删壁纸时顺手删掉磁盘上的文件」需要它（见 [removeWallpaper]）：删除前必须确认文件
     * 真的在 `filesDir/wallpapers` 里，绝不能把用户相册里的原图当成我们自己的副本删掉。
     */
    private lateinit var appContext: Context

    /**
     * 写入失败必须被吞掉，不能逃逸成「未捕获协程异常」。
     *
     * 每个 setter 都是 `scope.launch { store.edit { … } }`；DataStore 在磁盘满、文件损坏、
     * 存储被撤销时会抛 IOException。协程里未捕获的异常会交给线程的默认处理器，而
     * `CrashLog` 安装的全局处理器会**直接结束进程** —— 也就是「改个设置把 App 弄崩」。
     * 这里挂一个处理器把异常只记进崩溃日志；`SupervisorJob` 保证写入之间互不影响。
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        runCatching { CrashLog.write(throwable) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // ---- keys -------------------------------------------------------------
    private val KEY_THEME_MODE = stringPreferencesKey("beans.themeMode")
    private val KEY_UI_STYLE = stringPreferencesKey("beans.uiStyle")
    private val KEY_LANGUAGE = stringPreferencesKey("beans.language")

    private val KEY_ACCENT = stringPreferencesKey("beans.accentHex")
    private val KEY_LABEL_COLOR = stringPreferencesKey("beans.labelColorHex")
    private val KEY_COMMENT_COLOR = stringPreferencesKey("beans.commentColorHex")

    /** 颜色选择器里用 ＋ 存下来的自定义色板（逗号分隔的 `#RRGGBB` / `#AARRGGBB`）。 */
    private val KEY_CUSTOM_SWATCHES = stringPreferencesKey("beans.customColorSwatches")
    private val KEY_BACKGROUND_SYNC_ALL = booleanPreferencesKey("beans.backgroundSyncAll")
    private val KEY_BACKGROUND_COLOR = stringPreferencesKey("beans.customBackgroundHex")

    /**
     * 当前生效的壁纸（绝对路径；空串 = 默认背景）。
     *
     * **语义没有变，也不许变**：`BeansApp.kt` 把它交给 `GlassBackdrop(wallpaperPath = …)`，
     * 「当前是哪一张」永远只由这一个键回答。
     */
    private val KEY_BACKGROUND_IMAGE = stringPreferencesKey("beans.backgroundImagePath")

    /**
     * 壁纸画廊（换行分隔的绝对路径，最多 [BeansWallpaperLimit] 张）。
     *
     * 只回答「有哪些壁纸」；「哪一张是当前」仍然由 [KEY_BACKGROUND_IMAGE] 回答 —— 两个真值源
     * 迟早会漂移，所以刻意不引入第二个「当前」键。
     */
    private val KEY_WALLPAPER_PATHS = stringPreferencesKey("beans.wallpaperPaths")
    private val KEY_WALLPAPER_BLUR = floatPreferencesKey("beans.homeWallpaperBlur")

    private val KEY_AUDIO_QUALITY = stringPreferencesKey("beans.audioQuality")
    /** 播放来源：auto（优先官方，失败后第三方）/ official / third_party */
    private val KEY_PLAYBACK_SOURCE = stringPreferencesKey("beans.playbackSource")
    private val KEY_HIGH_REFRESH = booleanPreferencesKey("beans.enableHighRefresh")
    private val KEY_HAPTICS = booleanPreferencesKey("beans.hapticsEnabled")
    private val KEY_AUTO_RESUME = booleanPreferencesKey("beans.playback.autoResumeLast")

    private val KEY_SLEEP_TIMER_MINUTES = intPreferencesKey("beans.sleepTimerMinutes")
    private val KEY_PLAYBACK_SPEED = floatPreferencesKey("beans.playbackSpeed")
    private val KEY_SHUFFLE = booleanPreferencesKey("beans.playback.shuffle")
    private val KEY_REPEAT = stringPreferencesKey("beans.playback.repeat")

    private val KEY_EQ_ENABLED = booleanPreferencesKey("beans.eq.enabled")
    private val KEY_EQ_PRESET = stringPreferencesKey("beans.eq.preset")
    private val KEY_EQ_BANDS = stringPreferencesKey("beans.eq.bands")
    private val KEY_BASS_BOOST = intPreferencesKey("beans.eq.bassBoost")
    private val KEY_VIRTUALIZER = intPreferencesKey("beans.eq.virtualizer")

    private val KEY_LYRICS_TRANSLATION = booleanPreferencesKey("beans.lyrics.translation")
    private val KEY_LYRICS_FONT_SIZE = floatPreferencesKey("beans.lyrics.fontSize")
    private val KEY_LYRICS_ALIGN = stringPreferencesKey("beans.lyrics.align")
    private val KEY_LYRIC_OFFSET = floatPreferencesKey("beans.lyricOffset")

    /** 全屏播放器版式：cover / vinyl / lyrics / minimal（见 `PlayerLayout.fromRaw`）。 */
    private val KEY_PLAYER_LAYOUT = stringPreferencesKey("beans.playerLayout")

    private val KEY_ENABLED_PROVIDERS = stringPreferencesKey("beans.enabledProviders")
    private val KEY_HOME_PROVIDER = stringPreferencesKey("beans.homeSource")
    private val KEY_TAB_LABELS = booleanPreferencesKey("beans.tabLabelsVisible")

    /** 备份与恢复：是否把登录信息写进备份（默认关闭）。 */
    private val KEY_BACKUP_INCLUDE_LOGIN = booleanPreferencesKey("beans.backup.includeLogin")

    /** 备份与恢复：是否把壁纸图片写进备份（默认关闭；关闭时不写入任何图片字节）。 */
    private val KEY_BACKUP_INCLUDE_IMAGES = booleanPreferencesKey("beans.backup.includeImages")

    // ---- 参考图新增的外观 / 主页开关（SETTINGS-PLAN.md §A） ------------------
    private val KEY_FLOATING_EFFECT = stringPreferencesKey("beans.floatingEffect")
    private val KEY_TAB_ICON_STYLE = stringPreferencesKey("beans.tabIconStyle")
    private val KEY_TAB_ICON_ORDER = stringPreferencesKey("beans.tabIconOrder")
    private val KEY_SHOW_VIP_BADGE = booleanPreferencesKey("beans.showVipBadge")
    private val KEY_DOCK_RADIUS = floatPreferencesKey("beans.dockRadius")
    private val KEY_DOCK_WIDTH = floatPreferencesKey("beans.dockWidth")
    private val KEY_DOCK_OFFSET_X = floatPreferencesKey("beans.dockOffsetX")
    private val KEY_DOCK_OFFSET_Y = floatPreferencesKey("beans.dockOffsetY")
    private val KEY_LIQUID_TINT = stringPreferencesKey("beans.liquidTintHex")
    private val KEY_HOME_LIGHT_BACKGROUND = stringPreferencesKey("beans.homeLightBackgroundHex")
    private val KEY_HOME_DARK_BACKGROUND = stringPreferencesKey("beans.homeDarkBackgroundHex")
    private val KEY_HIDE_HOME_NICKNAME = booleanPreferencesKey("beans.hideHomeNickname")
    private val KEY_HIDE_SORT_BUTTONS = booleanPreferencesKey("beans.hideSortButtons")
    private val KEY_HIDE_TOP_PROVIDER_STRIP = booleanPreferencesKey("beans.hideTopProviderStrip")
    private val KEY_HIDE_HOME_REFRESH = booleanPreferencesKey("beans.hideHomeRefresh")
    private val KEY_DAILY_PICKS_LEGACY = booleanPreferencesKey("beans.dailyPicksLegacy")
    private val KEY_HIDE_DONATION = booleanPreferencesKey("beans.hideDonation")

    fun init(context: Context) {
        val application = context.applicationContext
        appContext = application
        store = application.dataStore
    }

    private fun <T> watch(key: Preferences.Key<T>, default: T): Flow<T> =
        store.data.map { it[key] ?: default }

    private fun <T> set(key: Preferences.Key<T>, value: T) {
        scope.launch { store.edit { it[key] = value } }
    }

    // ---- theme ------------------------------------------------------------
    val themeMode: StateFlow<BeansThemeMode> by lazy {
        watch(KEY_THEME_MODE, BeansThemeMode.SYSTEM.name)
            .map { runCatching { BeansThemeMode.valueOf(it) }.getOrDefault(BeansThemeMode.SYSTEM) }
            .stateIn(scope, SharingStarted.Eagerly, BeansThemeMode.SYSTEM)
    }

    fun setThemeMode(mode: BeansThemeMode) = set(KEY_THEME_MODE, mode.name)

    val uiStyle: StateFlow<BeansUIStyle> by lazy {
        watch(KEY_UI_STYLE, BeansUIStyle.LIQUID.name)
            .map { runCatching { BeansUIStyle.valueOf(it) }.getOrDefault(BeansUIStyle.LIQUID) }
            .stateIn(scope, SharingStarted.Eagerly, BeansUIStyle.LIQUID)
    }

    fun setUIStyle(style: BeansUIStyle) = set(KEY_UI_STYLE, style.name)

    val language: StateFlow<AppLanguage> by lazy {
        watch(KEY_LANGUAGE, AppLanguage.CHINESE.name)
            .map { runCatching { AppLanguage.valueOf(it) }.getOrDefault(AppLanguage.CHINESE) }
            .stateIn(scope, SharingStarted.Eagerly, AppLanguage.CHINESE)
    }

    fun setLanguage(language: AppLanguage) = set(KEY_LANGUAGE, language.name)

    // ---- appearance -------------------------------------------------------
    val accentHex: StateFlow<String> by lazy {
        watch(KEY_ACCENT, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setAccentHex(hex: String) = set(KEY_ACCENT, hex)

    val labelColorHex: StateFlow<String> by lazy {
        watch(KEY_LABEL_COLOR, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setLabelColorHex(hex: String) = set(KEY_LABEL_COLOR, hex)

    val commentColorHex: StateFlow<String> by lazy {
        watch(KEY_COMMENT_COLOR, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setCommentColorHex(hex: String) = set(KEY_COMMENT_COLOR, hex)

    /**
     * 颜色选择器里的自定义色板（逗号分隔的十六进制，最多 [BeansColorSwatchLimit] 个）。
     *
     * 消费方：`ui/components/BeansColorPicker.kt` 的预设色板行；写入走 [addCustomColorSwatch]
     * 或 [setCustomColorSwatches]。格式与备份文档里的 `beans.customColorSwatches` 完全一致，
     * 所以 `BackupManager` 直接读写同一串文本。
     */
    val customColorSwatches: StateFlow<String> by lazy {
        watch(KEY_CUSTOM_SWATCHES, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setCustomColorSwatches(value: String) = set(KEY_CUSTOM_SWATCHES, value)

    /**
     * 把一个颜色追加进自定义色板（去重、上限 [BeansColorSwatchLimit]）。
     *
     * 读改写全部在同一个 `store.edit` 事务里完成：色板是「读当前值 → 追加」的语义，
     * 如果先读 [customColorSwatches] 的 `value` 再写，冷启动（StateFlow 还没收到第一次发射）
     * 时会把已有色板覆盖成空的。
     */
    fun addCustomColorSwatch(hex: String) {
        val normalized = normalizeSwatchHex(hex) ?: return
        scope.launch {
            store.edit { preferences ->
                val current = preferences[KEY_CUSTOM_SWATCHES].orEmpty()
                preferences[KEY_CUSTOM_SWATCHES] = beansAppendSwatch(current, normalized)
            }
        }
    }

    val backgroundSyncAll: StateFlow<Boolean> by lazy {
        // 默认 true：对齐参考图里默认打开的「同步到搜索 / 音乐库 / 我的」。
        // 之前默认 false，而全应用唯一的背景层又从不传 homeMode，等于壁纸/背景色永远不生效。
        watch(KEY_BACKGROUND_SYNC_ALL, true).stateIn(scope, SharingStarted.Eagerly, true)
    }

    fun setBackgroundSyncAll(value: Boolean) = set(KEY_BACKGROUND_SYNC_ALL, value)

    val customBackgroundHex: StateFlow<String> by lazy {
        watch(KEY_BACKGROUND_COLOR, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setCustomBackgroundHex(hex: String) = set(KEY_BACKGROUND_COLOR, hex)

    val backgroundImagePath: StateFlow<String> by lazy {
        watch(KEY_BACKGROUND_IMAGE, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setBackgroundImagePath(path: String) = set(KEY_BACKGROUND_IMAGE, path)

    // ---- 壁纸画廊（最多 BeansWallpaperLimit 张） ---------------------------

    /**
     * 画廊视图：**列表 + 当前**一次读完，读取时顺带完成老用户迁移（见 [beansWallpaperGallery]）。
     *
     * 迁移刻意做成「读时派生」而不是「启动时写一次」：
     * 老用户只有一个 `beans.backgroundImagePath`，派生出来的画廊第一项就是它，界面/备份/删除
     * 一律走同一套纯函数；用户第一次真正改动画廊时，派生结果自然被写回。这样即使写盘失败，
     * 壁纸也绝不会丢。
     */
    val wallpaperGallery: StateFlow<BeansWallpaperGallery> by lazy {
        store.data
            .map { preferences ->
                beansWallpaperGallery(
                    raw = preferences[KEY_WALLPAPER_PATHS].orEmpty(),
                    currentPath = preferences[KEY_BACKGROUND_IMAGE].orEmpty(),
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, BeansWallpaperGallery())
    }

    /**
     * 画廊里的路径列表（顺序 = 界面上缩略图的顺序）。
     *
     * 「当前是哪一张」不在这里：界面拿 [backgroundImagePath] 与本列表比对即可
     * （相等 = 缩略图上的「当前」角标）。
     */
    val wallpaperPaths: StateFlow<List<String>> by lazy {
        wallpaperGallery.map { it.paths }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /**
     * 画廊的读改写事务。
     *
     * 读旧值、算新值、写回**必须在同一个 `store.edit` 里**：先读 [wallpaperPaths]`.value`
     * 再写回，会把冷启动（StateFlow 还没收到第一次发射）时的空列表覆盖上去 ——
     * 与 [addCustomColorSwatch] 是同一个坑。返回 `false` = 没写（上限拒绝 / 无变化 / 写失败）。
     */
    private suspend fun editWallpapers(
        block: (BeansWallpaperGallery) -> BeansWallpaperGallery?,
    ): Boolean = runCatching {
        var wrote = false
        store.edit { preferences ->
            val gallery = beansWallpaperGallery(
                raw = preferences[KEY_WALLPAPER_PATHS].orEmpty(),
                currentPath = preferences[KEY_BACKGROUND_IMAGE].orEmpty(),
            )
            val next = block(gallery) ?: return@edit
            preferences[KEY_WALLPAPER_PATHS] = beansSerializeWallpaperPaths(next.paths)
            preferences[KEY_BACKGROUND_IMAGE] = next.current
            wrote = true
        }
        wrote
    }.getOrElse { error ->
        // 与 scope 里的 exceptionHandler 同一策略：写失败只记崩溃日志，绝不把异常抛给界面。
        runCatching { CrashLog.write(error) }
        false
    }

    /**
     * 追加一张壁纸并把它设为当前。
     *
     * 画廊已满（[BeansWallpaperLimit]）时**拒绝**：返回 `false`，偏好与磁盘都不动
     * （界面上那颗「＋」也已经被禁用并给出说明）。
     */
    suspend fun addWallpaper(path: String): Boolean {
        val target = path.trim()
        if (target.isEmpty()) return false
        return editWallpapers { gallery ->
            when {
                // 已经在画廊里的路径：只是切到它，不算新增，满员也允许。
                target in gallery.paths -> beansAddWallpaper(gallery, target)
                gallery.isFull -> null // 第 6 张：拒绝
                else -> beansAddWallpaper(gallery, target)
            }
        }
    }

    /** 选定一张壁纸（`path` 为空串 = 回到默认背景，画廊里的图一张都不删）。 */
    suspend fun selectWallpaper(path: String): Boolean =
        editWallpapers { gallery -> beansSelectWallpaper(gallery, path).takeIf { it != gallery } }

    /**
     * 从画廊里删掉一张壁纸，并删掉它**留在磁盘上的副本**。
     *
     * 删的是当前那张时，[beansRemoveWallpaper] 会把当前顺位落到下一张 / 上一张，一张都不剩时
     * 回到默认背景 —— 也就是 `beans.backgroundImagePath` 绝不会继续指向一个刚被删掉的文件。
     * 删除文件放在偏好写成功之后：反过来会留下一条指向不存在文件的死路径。
     */
    suspend fun removeWallpaper(path: String): Boolean {
        val target = path.trim()
        if (target.isEmpty()) return false
        val wrote = editWallpapers { gallery ->
            beansRemoveWallpaper(gallery, target).takeIf { it != gallery }
        }
        if (wrote) withContext(Dispatchers.IO) { deleteWallpaperFile(target) }
        return wrote
    }

    /**
     * 备份恢复：一次性写回画廊与当前壁纸。
     *
     * @param paths 备份里的画廊；**空列表 = 保留本机现有画廊**（旧备份没有这个键）
     * @param currentPath 备份里的当前壁纸；不在 [paths] 里时按需补进画廊，保证「当前」可见
     */
    suspend fun restoreWallpaperGallery(paths: List<String>, currentPath: String): Boolean =
        editWallpapers { gallery ->
            val incoming = beansParseWallpaperPaths(paths.joinToString(WALLPAPER_SEPARATOR))
            val base = if (incoming.isEmpty()) gallery else BeansWallpaperGallery(incoming, "")
            beansSelectWallpaper(base, currentPath).takeIf { it != gallery }
        }

    /**
     * 只删 `filesDir/wallpapers` 里的文件。
     *
     * 画廊里的路径理论上都在这个目录下（上传时就是复制进来的），但老用户的
     * `beans.backgroundImagePath` 可能指向别处；那种文件属于用户的相册原图，**绝不能删**。
     */
    private fun deleteWallpaperFile(path: String) {
        runCatching {
            val dir = File(appContext.filesDir, BeansWallpaperDirName).canonicalFile
            val file = File(path).canonicalFile
            if (file.parentFile == dir) file.delete()
        }
    }

    val wallpaperBlur: StateFlow<Float> by lazy {
        watch(KEY_WALLPAPER_BLUR, 0f).stateIn(scope, SharingStarted.Eagerly, 0f)
    }

    fun setWallpaperBlur(value: Float) = set(KEY_WALLPAPER_BLUR, value)

    // ---- playback ---------------------------------------------------------
    val audioQuality: StateFlow<String> by lazy {
        watch(KEY_AUDIO_QUALITY, "hires").stateIn(scope, SharingStarted.Eagerly, "hires")
    }

    fun setAudioQuality(value: String) = set(KEY_AUDIO_QUALITY, value)

    /** 播放来源偏好（对应设置里的「播放来源」分段控件）。 */
    val playbackSource: StateFlow<String> by lazy {
        watch(KEY_PLAYBACK_SOURCE, "auto").stateIn(scope, SharingStarted.Eagerly, "auto")
    }

    fun setPlaybackSource(value: String) = set(KEY_PLAYBACK_SOURCE, value)

    val highRefresh: StateFlow<Boolean> by lazy {
        watch(KEY_HIGH_REFRESH, true).stateIn(scope, SharingStarted.Eagerly, true)
    }

    fun setHighRefresh(value: Boolean) = set(KEY_HIGH_REFRESH, value)

    val hapticsEnabled: StateFlow<Boolean> by lazy {
        watch(KEY_HAPTICS, true).stateIn(scope, SharingStarted.Eagerly, true)
    }

    fun setHapticsEnabled(value: Boolean) = set(KEY_HAPTICS, value)

    val autoResumeLast: StateFlow<Boolean> by lazy {
        watch(KEY_AUTO_RESUME, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setAutoResume(value: Boolean) = set(KEY_AUTO_RESUME, value)

    val playbackSpeed: StateFlow<Float> by lazy {
        watch(KEY_PLAYBACK_SPEED, 1f).stateIn(scope, SharingStarted.Eagerly, 1f)
    }

    fun setPlaybackSpeed(value: Float) = set(KEY_PLAYBACK_SPEED, value)

    val shuffle: StateFlow<Boolean> by lazy {
        watch(KEY_SHUFFLE, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setShuffle(value: Boolean) = set(KEY_SHUFFLE, value)

    val repeatMode: StateFlow<String> by lazy {
        watch(KEY_REPEAT, "off").stateIn(scope, SharingStarted.Eagerly, "off")
    }

    fun setRepeatMode(value: String) = set(KEY_REPEAT, value)

    val sleepTimerMinutes: StateFlow<Int> by lazy {
        watch(KEY_SLEEP_TIMER_MINUTES, 0).stateIn(scope, SharingStarted.Eagerly, 0)
    }

    fun setSleepTimerMinutes(value: Int) = set(KEY_SLEEP_TIMER_MINUTES, value)

    // ---- equalizer --------------------------------------------------------
    val eqEnabled: StateFlow<Boolean> by lazy {
        watch(KEY_EQ_ENABLED, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setEqEnabled(value: Boolean) = set(KEY_EQ_ENABLED, value)

    val eqPreset: StateFlow<String> by lazy {
        watch(KEY_EQ_PRESET, "flat").stateIn(scope, SharingStarted.Eagerly, "flat")
    }

    fun setEqPreset(value: String) = set(KEY_EQ_PRESET, value)

    /** Comma-separated `index:millibels` pairs; empty = use the named preset. */
    val eqBands: StateFlow<String> by lazy {
        watch(KEY_EQ_BANDS, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setEqBands(value: String) = set(KEY_EQ_BANDS, value)

    val bassBoost: StateFlow<Int> by lazy {
        watch(KEY_BASS_BOOST, 0).stateIn(scope, SharingStarted.Eagerly, 0)
    }

    fun setBassBoost(value: Int) = set(KEY_BASS_BOOST, value)

    val virtualizer: StateFlow<Int> by lazy {
        watch(KEY_VIRTUALIZER, 0).stateIn(scope, SharingStarted.Eagerly, 0)
    }

    fun setVirtualizer(value: Int) = set(KEY_VIRTUALIZER, value)

    // ---- lyrics -----------------------------------------------------------
    val lyricsTranslation: StateFlow<Boolean> by lazy {
        watch(KEY_LYRICS_TRANSLATION, true).stateIn(scope, SharingStarted.Eagerly, true)
    }

    fun setLyricsTranslation(value: Boolean) = set(KEY_LYRICS_TRANSLATION, value)

    val lyricsFontSize: StateFlow<Float> by lazy {
        watch(KEY_LYRICS_FONT_SIZE, 17f).stateIn(scope, SharingStarted.Eagerly, 17f)
    }

    fun setLyricsFontSize(value: Float) = set(KEY_LYRICS_FONT_SIZE, value)

    val lyricsAlign: StateFlow<String> by lazy {
        watch(KEY_LYRICS_ALIGN, "center").stateIn(scope, SharingStarted.Eagerly, "center")
    }

    fun setLyricsAlign(value: String) = set(KEY_LYRICS_ALIGN, value)

    /** User lyric timing offset in seconds. */
    val lyricOffset: StateFlow<Float> by lazy {
        watch(KEY_LYRIC_OFFSET, 0f).stateIn(scope, SharingStarted.Eagerly, 0f)
    }

    fun setLyricOffset(value: Float) = set(KEY_LYRIC_OFFSET, value)

    // ---- player layout ----------------------------------------------------
    /**
     * 全屏播放器版式，原始字符串（`cover` / `vinyl` / `lyrics` / `minimal`）。
     *
     * 这里刻意存字符串而不是 `PlayerLayout` 枚举：`data.prefs` 不反向依赖 UI 层，
     * 由 `ui.screens.PlayerLayout.fromRaw(...)` 负责解析，未知值回落到 `cover`。
     */
    val playerLayout: StateFlow<String> by lazy {
        watch(KEY_PLAYER_LAYOUT, "cover").stateIn(scope, SharingStarted.Eagerly, "cover")
    }

    fun setPlayerLayout(value: String) = set(KEY_PLAYER_LAYOUT, value)

    // ---- platforms --------------------------------------------------------
    /** Comma-separated enabled providers, e.g. "netease,qq,kugou". */
    val enabledProviders: StateFlow<String> by lazy {
        watch(KEY_ENABLED_PROVIDERS, "netease,qq,kugou")
            .stateIn(scope, SharingStarted.Eagerly, "netease,qq,kugou")
    }

    fun setEnabledProviders(value: String) = set(KEY_ENABLED_PROVIDERS, value)

    val homeProvider: StateFlow<String> by lazy {
        watch(KEY_HOME_PROVIDER, "netease").stateIn(scope, SharingStarted.Eagerly, "netease")
    }

    fun setHomeProvider(value: String) = set(KEY_HOME_PROVIDER, value)

    val tabLabelsVisible: StateFlow<Boolean> by lazy {
        watch(KEY_TAB_LABELS, true).stateIn(scope, SharingStarted.Eagerly, true)
    }

    fun setTabLabelsVisible(value: Boolean) = set(KEY_TAB_LABELS, value)

    // ---- 备份与恢复 -------------------------------------------------------
    /**
     * 是否在备份里包含登录信息。**默认 false**（对齐参考图：不带账号登录信息）。
     *
     * 与上面所有 flow 一样保持 `by lazy`：任何 flow 都不能写成饿汉式初始化，
     * 否则对象构造时就会解引用 [store] 并在启动时抛 `ExceptionInInitializerError`。
     */
    val backupIncludeLogin: StateFlow<Boolean> by lazy {
        watch(KEY_BACKUP_INCLUDE_LOGIN, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setBackupIncludeLogin(value: Boolean) = set(KEY_BACKUP_INCLUDE_LOGIN, value)

    /**
     * 是否在备份里包含壁纸图片。**默认 false**；关闭时备份只写普通设置，
     * 不写入任何图片字节（备份文件里 `imageIncluded: false` 是显式的）。
     */
    val backupIncludeImages: StateFlow<Boolean> by lazy {
        watch(KEY_BACKUP_INCLUDE_IMAGES, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setBackupIncludeImages(value: Boolean) = set(KEY_BACKUP_INCLUDE_IMAGES, value)

    // ---- 参考图新增的外观 / 主页开关（SETTINGS-PLAN.md §A） ------------------
    /**
     * 全局漂浮特效。消费方：`ui/components/BeansComponents.kt` 的 `BeansFloatingEffectOverlay`，
     * 由 `ui/BeansApp.kt` 铺在整棵导航树之上（关闭时该 composable 直接不组合任何东西）。
     */
    val floatingEffect: StateFlow<BeansFloatingEffect> by lazy {
        watch(KEY_FLOATING_EFFECT, BeansFloatingEffect.OFF.name)
            .map { runCatching { BeansFloatingEffect.valueOf(it) }.getOrDefault(BeansFloatingEffect.OFF) }
            .stateIn(scope, SharingStarted.Eagerly, BeansFloatingEffect.OFF)
    }

    fun setFloatingEffect(value: BeansFloatingEffect) = set(KEY_FLOATING_EFFECT, value.name)

    /** 底栏图标样式。消费方：`ui/BeansApp.kt` 的 `beansTabIcon(...)`。 */
    val tabIconStyle: StateFlow<BeansTabIconStyle> by lazy {
        watch(KEY_TAB_ICON_STYLE, BeansTabIconStyle.SF_SYMBOLS.name)
            .map { runCatching { BeansTabIconStyle.valueOf(it) }.getOrDefault(BeansTabIconStyle.SF_SYMBOLS) }
            .stateIn(scope, SharingStarted.Eagerly, BeansTabIconStyle.SF_SYMBOLS)
    }

    fun setTabIconStyle(value: BeansTabIconStyle) = set(KEY_TAB_ICON_STYLE, value.name)

    /**
     * 底部栏显示哪些主页入口（逗号分隔的 `RootTab` 名称，空串 = 全部显示）。
     * 消费方：`ui/BeansApp.kt` 的 `visibleRootTabIndices(...)` + `BeansTabBar`。
     */
    val tabIconOrder: StateFlow<String> by lazy {
        watch(KEY_TAB_ICON_ORDER, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setTabIconOrder(value: String) = set(KEY_TAB_ICON_ORDER, value)

    /** 显示歌曲 VIP 图标。消费方：`ui/components/BeansComponents.kt` 的 `BeansVIPBadge`。 */
    val showVipBadge: StateFlow<Boolean> by lazy {
        watch(KEY_SHOW_VIP_BADGE, true).stateIn(scope, SharingStarted.Eagerly, true)
    }

    fun setShowVipBadge(value: Boolean) = set(KEY_SHOW_VIP_BADGE, value)

    /** 低系统悬浮底栏 · 圆润度。消费方：`ui/BeansApp.kt` 的 `BeansTabBar`。 */
    val dockRadius: StateFlow<Float> by lazy {
        watch(KEY_DOCK_RADIUS, 32f).stateIn(scope, SharingStarted.Eagerly, 32f)
    }

    fun setDockRadius(value: Float) = set(KEY_DOCK_RADIUS, value)

    /** 低系统悬浮底栏 · 长度。消费方：`ui/BeansApp.kt` 的 `BeansTabBar`。 */
    val dockWidth: StateFlow<Float> by lazy {
        watch(KEY_DOCK_WIDTH, 356f).stateIn(scope, SharingStarted.Eagerly, 356f)
    }

    fun setDockWidth(value: Float) = set(KEY_DOCK_WIDTH, value)

    /** 低系统悬浮底栏 · X 位置。消费方：`ui/BeansApp.kt` 的 `BeansTabBar`。 */
    val dockOffsetX: StateFlow<Float> by lazy {
        watch(KEY_DOCK_OFFSET_X, 0f).stateIn(scope, SharingStarted.Eagerly, 0f)
    }

    fun setDockOffsetX(value: Float) = set(KEY_DOCK_OFFSET_X, value)

    /** 低系统悬浮底栏 · Y 位置。消费方：`ui/BeansApp.kt` 的 `BeansTabBar`。 */
    val dockOffsetY: StateFlow<Float> by lazy {
        watch(KEY_DOCK_OFFSET_Y, 0f).stateIn(scope, SharingStarted.Eagerly, 0f)
    }

    fun setDockOffsetY(value: Float) = set(KEY_DOCK_OFFSET_Y, value)

    /**
     * 液态容器颜色。消费方：`ui/components/BeansGlass.kt` 的 `Modifier.beansGlass`，
     * 只影响液态系（`LIQUID` / `FROSTED`）容器，Apple 简洁 / 紧凑淡雅保持系统原生玻璃。
     */
    val liquidTintHex: StateFlow<String> by lazy {
        watch(KEY_LIQUID_TINT, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setLiquidTintHex(value: String) = set(KEY_LIQUID_TINT, value)

    /**
     * 主页背景色 · 浅色模式。消费方：`ui/BeansApp.kt` 的 `GlassBackdrop(customColor = ...)`，
     * 按当前 `colors.isDark` 在浅色 / 深色两支里取值；两支都为空时回落到旧的 `customBackgroundHex`。
     */
    val homeLightBackgroundHex: StateFlow<String> by lazy {
        watch(KEY_HOME_LIGHT_BACKGROUND, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setHomeLightBackgroundHex(value: String) = set(KEY_HOME_LIGHT_BACKGROUND, value)

    /** 主页背景色 · 深色模式。消费方同 [homeLightBackgroundHex]。 */
    val homeDarkBackgroundHex: StateFlow<String> by lazy {
        watch(KEY_HOME_DARK_BACKGROUND, "").stateIn(scope, SharingStarted.Eagerly, "")
    }

    fun setHomeDarkBackgroundHex(value: String) = set(KEY_HOME_DARK_BACKGROUND, value)

    /** 隐藏主页用户名。消费方：`ui/screens/DiscoverScreen.kt` 的 `DiscoverHeader`。 */
    val hideHomeNickname: StateFlow<Boolean> by lazy {
        watch(KEY_HIDE_HOME_NICKNAME, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setHideHomeNickname(value: Boolean) = set(KEY_HIDE_HOME_NICKNAME, value)

    /** 隐藏所有界面排序按钮。消费方：`LibraryScreen` 两处 + `ProfileScreen` 板块排序入口。 */
    val hideSortButtons: StateFlow<Boolean> by lazy {
        watch(KEY_HIDE_SORT_BUTTONS, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setHideSortButtons(value: Boolean) = set(KEY_HIDE_SORT_BUTTONS, value)

    /** 隐藏顶部平台列表。消费方：`ui/screens/DiscoverScreen.kt` 的 `providerPicker` 板块。 */
    val hideTopProviderStrip: StateFlow<Boolean> by lazy {
        watch(KEY_HIDE_TOP_PROVIDER_STRIP, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setHideTopProviderStrip(value: Boolean) = set(KEY_HIDE_TOP_PROVIDER_STRIP, value)

    /** 隐藏主页刷新按钮。消费方：`ui/screens/DiscoverScreen.kt` 的 `DiscoverHeader`。 */
    val hideHomeRefresh: StateFlow<Boolean> by lazy {
        watch(KEY_HIDE_HOME_REFRESH, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setHideHomeRefresh(value: Boolean) = set(KEY_HIDE_HOME_REFRESH, value)

    /** 每日推荐使用旧版样式。消费方：`ui/screens/DiscoverScreen.kt` 的 `dailyPicksPresentation`。 */
    val dailyPicksLegacy: StateFlow<Boolean> by lazy {
        watch(KEY_DAILY_PICKS_LEGACY, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setDailyPicksLegacy(value: Boolean) = set(KEY_DAILY_PICKS_LEGACY, value)

    /** 隐藏自愿赞助。消费方：`ui/screens/ProfileScreen.kt` 的 `LuluDonationCard`。 */
    val hideDonation: StateFlow<Boolean> by lazy {
        watch(KEY_HIDE_DONATION, false).stateIn(scope, SharingStarted.Eagerly, false)
    }

    fun setHideDonation(value: Boolean) = set(KEY_HIDE_DONATION, value)
}

// ---------------------------------------------------------------------------------------------
// MARK: - 自定义色板的纯文本规则（与 DataStore 无关，可单独测试）
// ---------------------------------------------------------------------------------------------

/** 自定义色板的容量上限：再多就撑爆色板行（多出来的会被丢掉，最近用的留在最后）。 */
const val BeansColorSwatchLimit: Int = 12

/**
 * 把一个颜色文本规范化成 `#RRGGBB` / `#AARRGGBB`（大写带 #）。
 *
 * 只接受 6 / 8 位十六进制 —— 与 `ui/theme/BeansPalette.kt` 的 `parseHexColor` 支持的范围一致。
 * 其它一律返回 null（调用方据此忽略这次保存，而不是写进一个永远解析不出来的垃圾值）。
 */
fun normalizeSwatchHex(hex: String?): String? {
    val cleaned = hex?.trim()?.removePrefix("#")?.trim().orEmpty()
    if (cleaned.length != 6 && cleaned.length != 8) return null
    if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return "#" + cleaned.uppercase()
}

/** 逗号分隔的自定义色板 → 列表（去空、逐个规范化，无法解析的项直接丢掉）。 */
fun beansParseSwatchList(raw: String): List<String> =
    raw.split(',')
        .mapNotNull { normalizeSwatchHex(it) }
        .distinct()

/**
 * 追加一个自定义色板并重新序列化。
 *
 * 语义：同色不重复（已存在的先移除再加到末尾，也就是「最近用的排在最后」），
 * 超过 [BeansColorSwatchLimit] 时丢掉最旧的那些。
 */
fun beansAppendSwatch(raw: String, hex: String, limit: Int = BeansColorSwatchLimit): String {
    val normalized = normalizeSwatchHex(hex) ?: return raw
    val existing = beansParseSwatchList(raw).filterNot { it.equals(normalized, ignoreCase = true) }
    val capped = (existing + normalized).takeLast(limit.coerceAtLeast(1))
    return capped.joinToString(",")
}

// ---------------------------------------------------------------------------------------------
// MARK: - 壁纸画廊的纯逻辑（与 DataStore 无关，可单独测试）
// ---------------------------------------------------------------------------------------------

/** 壁纸画廊的容量上限：参考图的「上传壁纸（可多张）」最多 5 张，第 6 张必须被拒绝。 */
const val BeansWallpaperLimit: Int = 5

/**
 * 壁纸副本所在目录名（`filesDir/wallpapers`）。
 *
 * 设置页上传、[BackupManager] 收集图片、删除文件三处共用同一个常量，避免哪天改了一处、
 * 另一处还在读写旧目录（那会表现为「删了壁纸但文件还在」或者「备份里没有壁纸」）。
 */
const val BeansWallpaperDirName: String = "wallpapers"

/**
 * 画廊列表的分隔符：**换行**。
 *
 * 其它列表型偏好（`beans.customColorSwatches` / `beans.tabIconOrder` / `beans.enabledProviders`）
 * 都用逗号，这里刻意不用：Android 的路径里唯一不可能出现的字符就是换行，用逗号会让
 * 带逗号的文件名被拆成两项，进而在界面上多出一张打不开的「幽灵壁纸」。
 */
private const val WALLPAPER_SEPARATOR = "\n"

/** 换行分隔的画廊文本 → 路径列表（去空、去重、按上限截断）。 */
fun beansParseWallpaperPaths(raw: String): List<String> =
    raw.split(WALLPAPER_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(BeansWallpaperLimit)

/** 路径列表 → 换行分隔的画廊文本（与 [beansParseWallpaperPaths] 严格互逆，并强制上限）。 */
fun beansSerializeWallpaperPaths(paths: List<String>): String =
    beansParseWallpaperPaths(paths.joinToString(WALLPAPER_SEPARATOR)).joinToString(WALLPAPER_SEPARATOR)

/**
 * 画廊的完整状态。
 *
 * @param paths 画廊里的壁纸（顺序 = 界面缩略图的顺序，最多 [BeansWallpaperLimit] 张）
 * @param current 当前生效的那张，即 `beans.backgroundImagePath` 的值；空串 = 默认背景
 */
data class BeansWallpaperGallery(
    val paths: List<String> = emptyList(),
    val current: String = "",
) {
    /** 当前壁纸是否就是画廊里的某一张（false 时界面显示「当前：默认背景」）。 */
    val currentInGallery: Boolean get() = current.isNotEmpty() && current in paths

    /** 画廊是否已经装满（第 6 张要被拒绝）。 */
    val isFull: Boolean get() = paths.size >= BeansWallpaperLimit
}

/**
 * 读画廊：**老用户迁移就发生在这里**。
 *
 * 1. 画廊为空而 `beans.backgroundImagePath` 有值 → 那张老壁纸成为画廊的第一项，绝不丢；
 * 2. 画廊非空但当前壁纸不在里面 → 只要还有空位就把它补进去，否则界面上既标不出「当前」，
 *    用户也没有入口再选回它；
 * 3. 画廊已满又补不进时保持原状（当前值仍然是 `backgroundImagePath` 本身）——
 *    「宁可在画廊里少显示一项，也绝不静默丢掉任何一张已有壁纸」。
 */
fun beansWallpaperGallery(raw: String, currentPath: String): BeansWallpaperGallery {
    val current = currentPath.trim()
    val parsed = beansParseWallpaperPaths(raw)
    if (parsed.isEmpty()) {
        return BeansWallpaperGallery(
            paths = if (current.isEmpty()) emptyList() else listOf(current),
            current = current,
        )
    }
    val paths = if (current.isEmpty() || current in parsed || parsed.size >= BeansWallpaperLimit) {
        parsed
    } else {
        parsed + current
    }
    return BeansWallpaperGallery(paths, current)
}

/**
 * 追加一张壁纸并设为当前。
 *
 * 已存在的路径先移除再加到末尾（最近上传的排在最后）；已经满了且是新路径时原样返回，
 * 调用方（[SettingsStore.addWallpaper]）据此拒绝第 6 张。
 */
fun beansAddWallpaper(gallery: BeansWallpaperGallery, path: String): BeansWallpaperGallery {
    val target = path.trim()
    if (target.isEmpty()) return gallery
    if (target !in gallery.paths && gallery.isFull) return gallery
    val paths = (gallery.paths.filterNot { it == target } + target).take(BeansWallpaperLimit)
    return BeansWallpaperGallery(paths, target)
}

/**
 * 选定一张壁纸。
 *
 * `path` 为空串 = 回到默认背景（画廊保留，一张图都不删）；不在画廊里的路径只要还有空位就补进去
 * （保证「当前」一定能在画廊里看到）；画廊已满时既不动画廊也不改当前值。
 */
fun beansSelectWallpaper(gallery: BeansWallpaperGallery, path: String): BeansWallpaperGallery {
    val target = path.trim()
    if (target.isEmpty()) return gallery.copy(current = "")
    if (target in gallery.paths) return gallery.copy(current = target)
    if (gallery.isFull) return gallery
    return BeansWallpaperGallery(gallery.paths + target, target)
}

/**
 * 删掉一张壁纸（磁盘文件由调用方负责删）。
 *
 * 删的正好是当前那张时，当前顺位落到**下一张**（同下标），没有下一张就落到上一张；
 * 一张都不剩时 `current` 变成空串 = 默认背景。于是「当前值绝不会指向刚被删掉的文件」
 * 这条不变量由纯函数本身保证，与 DataStore 的读写时序无关。
 */
fun beansRemoveWallpaper(gallery: BeansWallpaperGallery, path: String): BeansWallpaperGallery {
    val target = path.trim()
    val index = gallery.paths.indexOf(target)
    if (index < 0) return gallery
    val remaining = gallery.paths.filterIndexed { position, _ -> position != index }
    val fallback = remaining.getOrNull(index) ?: remaining.lastOrNull() ?: ""
    val current = if (gallery.current == target) fallback else gallery.current
    return BeansWallpaperGallery(remaining, current)
}



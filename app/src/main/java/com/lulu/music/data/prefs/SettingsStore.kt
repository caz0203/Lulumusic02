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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Appearance mode, mirroring the iOS `BeansThemeMode`. */
enum class BeansThemeMode { SYSTEM, LIGHT, DARK }

/** UI style, mirroring the iOS `BeansUIStyle`. */
enum class BeansUIStyle { LIQUID, NATIVE_CLEAN }

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- keys -------------------------------------------------------------
    private val KEY_THEME_MODE = stringPreferencesKey("beans.themeMode")
    private val KEY_UI_STYLE = stringPreferencesKey("beans.uiStyle")
    private val KEY_LANGUAGE = stringPreferencesKey("beans.language")

    private val KEY_ACCENT = stringPreferencesKey("beans.accentHex")
    private val KEY_LABEL_COLOR = stringPreferencesKey("beans.labelColorHex")
    private val KEY_COMMENT_COLOR = stringPreferencesKey("beans.commentColorHex")
    private val KEY_BACKGROUND_SYNC_ALL = booleanPreferencesKey("beans.backgroundSyncAll")
    private val KEY_BACKGROUND_COLOR = stringPreferencesKey("beans.customBackgroundHex")
    private val KEY_BACKGROUND_IMAGE = stringPreferencesKey("beans.backgroundImagePath")
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

    fun init(context: Context) {
        store = context.applicationContext.dataStore
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

    val backgroundSyncAll: StateFlow<Boolean> by lazy {
        watch(KEY_BACKGROUND_SYNC_ALL, false).stateIn(scope, SharingStarted.Eagerly, false)
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
}

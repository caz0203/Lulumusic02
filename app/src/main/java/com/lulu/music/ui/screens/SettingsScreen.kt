package com.lulu.music.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.Lang
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.AppLanguage
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.source.PlaybackSource
import com.lulu.music.data.source.UnblockSourceStore
import com.lulu.music.data.store.CrashLog
import com.lulu.music.playback.EqualizerController
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.LocalBeansNavigator
import com.lulu.music.ui.components.BeansCapsule
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansIcons
import com.lulu.music.ui.components.BeansPressable
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.beansCardShadow
import com.lulu.music.ui.components.beansGlass
import com.lulu.music.ui.components.beansPressClickable
import com.lulu.music.ui.components.beansScrollIndicatorsHidden
import com.lulu.music.ui.theme.BeansTheme
import com.lulu.music.ui.theme.parseHexColor
import java.io.File

/**
 * 设置页 — port of the iOS `SettingsView` sheet that the 「我的」 gear button opens.
 *
 * Ported groups: appearance (theme mode / UI style / accent / label + comment colours / wallpaper +
 * blur / background sync), playback (audio quality / speed / high refresh / haptics / auto-resume),
 * equalizer (enable / presets / band sliders / bass boost / virtualizer), lyrics (translation / font
 * size / alignment / offset), language and platforms (enabled providers / home provider / tab labels).
 *
 * Documented Android deviations:
 *  - SwiftUI's `ColorPicker` has no Material3 counterpart, so every colour option is a preset swatch
 *    row plus an editable hex field (same stored keys, same "恢复预设 / 恢复默认" actions).
 *  - `WallpaperPhotoPicker` (PHPicker, multi-select) becomes a single-image `GetContent` picker whose
 *    result is copied into `filesDir/wallpapers` before the path is stored.
 *  - The equalizer band sliders are driven by the *device's* bands ([EqualizerController.bands]); they
 *    only exist while an audio session is attached, so a hint is shown when nothing is available.
 *  - The iOS-only groups (changelog, backup/restore, log viewer, third-party sources, legacy tab bar,
 *    greeting/font importers) belong to other screens and are intentionally not duplicated here.
 *  - Android extra: a 「崩溃日志」 in-app viewer ([CrashLogRow]) at the end of the platforms group, so a
 *    tester without adb can read / copy / clear `filesDir/crash.log` (the iOS `logSection` counterpart).
 */
@Composable
fun BeansSettingsScreen(onDismiss: () -> Unit) {
    val colors = BeansTheme.colors
    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val language by SettingsStore.language.collectAsState()

    LaunchedEffect(language) { Lang.current.value = language }

    var appearanceExpanded by rememberSaveable { mutableStateOf(true) }
    var playbackExpanded by rememberSaveable { mutableStateOf(false) }
    var equalizerExpanded by rememberSaveable { mutableStateOf(false) }
    var lyricsExpanded by rememberSaveable { mutableStateOf(false) }
    var platformExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        SettingsTopBar(
            title = beansLocalized("设置", "Settings"),
            onClose = {
                BeansHaptics.tap()
                onDismiss()
            },
            style = uiStyle,
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .beansScrollIndicatorsHidden()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppearanceSection(
                    expanded = appearanceExpanded,
                    onToggle = { appearanceExpanded = !appearanceExpanded },
                    style = uiStyle,
                )
                PlaybackSection(
                    expanded = playbackExpanded,
                    onToggle = { playbackExpanded = !playbackExpanded },
                    style = uiStyle,
                )
                EqualizerSection(
                    expanded = equalizerExpanded,
                    onToggle = { equalizerExpanded = !equalizerExpanded },
                    style = uiStyle,
                )
                LyricsSection(
                    expanded = lyricsExpanded,
                    onToggle = { lyricsExpanded = !lyricsExpanded },
                    style = uiStyle,
                )
                LanguageSection(style = uiStyle)
                PlatformSection(
                    expanded = platformExpanded,
                    onToggle = { platformExpanded = !platformExpanded },
                    style = uiStyle,
                )
                SettingsFooterNote()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 外观
// ---------------------------------------------------------------------------------------------

@Composable
private fun AppearanceSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    style: BeansUIStyle,
) {
    val themeMode by SettingsStore.themeMode.collectAsState()
    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val accentHex by SettingsStore.accentHex.collectAsState()
    val labelHex by SettingsStore.labelColorHex.collectAsState()
    val commentHex by SettingsStore.commentColorHex.collectAsState()
    val backgroundHex by SettingsStore.customBackgroundHex.collectAsState()
    val backgroundImagePath by SettingsStore.backgroundImagePath.collectAsState()
    val backgroundSyncAll by SettingsStore.backgroundSyncAll.collectAsState()
    val wallpaperBlur by SettingsStore.wallpaperBlur.collectAsState()

    val context = LocalContext.current
    val pickWallpaper = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
            val target = File(dir, "wallpaper_${System.currentTimeMillis()}.jpg")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选图片")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            target.absolutePath
        }
        result
            .onSuccess { path ->
                SettingsStore.setBackgroundImagePath(path)
                BeansHaptics.success()
                BeansToastCenter.show(beansLocalized("壁纸已应用", "Wallpaper applied"))
            }
            .onFailure { error ->
                BeansToastCenter.show(
                    beansLocalized(
                        "壁纸导入失败：${error.message ?: "未知错误"}",
                        "Could not import wallpaper: ${error.message ?: "unknown error"}",
                    )
                )
            }
    }

    SettingsGroupCard(
        title = beansLocalized("主题模式", "Theme"),
        subtitle = beansLocalized("主题、配色、壁纸与背景同步", "Theme, colours, wallpaper and background sync"),
        systemName = "paintbrush",
        expanded = expanded,
        onToggle = onToggle,
        style = style,
    ) {
        SettingsFieldLabel(beansLocalized("主题模式", "Theme mode"))
        SettingsSegmented(
            options = BeansThemeMode.entries,
            selected = themeMode,
            label = { beansLocalized(themeModeTitleZh(it), themeModeTitleEn(it)) },
            onSelect = {
                SettingsStore.setThemeMode(it)
                BeansHaptics.select()
            },
        )

        SettingsFieldLabel(beansLocalized("全局 UI 样式", "Global UI style"))
        SettingsSegmented(
            options = BeansUIStyle.entries,
            selected = uiStyle,
            label = {
                when (it) {
                    BeansUIStyle.LIQUID -> beansLocalized("液态玻璃", "Liquid")
                    BeansUIStyle.NATIVE_CLEAN -> beansLocalized("原生简洁", "Apple Clean")
                }
            },
            onSelect = {
                SettingsStore.setUIStyle(it)
                BeansHaptics.select()
            },
        )

        SettingsDivider()

        SettingsColorRow(
            title = beansLocalized("自定义强调色", "Custom accent colour"),
            hint = beansLocalized("用于按钮、进度与高亮", "Used for buttons, progress and highlights"),
            hex = accentHex,
            fallback = colorsAccent(),
            onApply = { SettingsStore.setAccentHex(it) },
            onReset = {
                SettingsStore.setAccentHex("")
                BeansHaptics.select()
            },
        )

        SettingsColorRow(
            title = beansLocalized("主文字颜色", "Label colour"),
            hint = beansLocalized("全 App 主文字颜色", "Label colour across the whole app"),
            hex = labelHex,
            fallback = colorsLabel(),
            onApply = { SettingsStore.setLabelColorHex(it) },
            onReset = {
                SettingsStore.setLabelColorHex("")
                BeansHaptics.select()
            },
        )

        SettingsColorRow(
            title = beansLocalized("注释文字颜色", "Comment colour"),
            hint = beansLocalized("全 App 说明文字颜色", "Secondary text colour across the whole app"),
            hex = commentHex,
            fallback = colorsComment(),
            onApply = { SettingsStore.setCommentColorHex(it) },
            onReset = {
                SettingsStore.setCommentColorHex("")
                BeansHaptics.select()
            },
        )

        SettingsDivider()

        SettingsFieldLabel(beansLocalized("主页背景色", "Home background colour"))
        SettingsColorRow(
            title = beansLocalized("背景颜色", "Background colour"),
            hint = beansLocalized("同步开启时作用于所有页面", "Applies to every page while sync is on"),
            hex = backgroundHex,
            fallback = colorsBackground(),
            onApply = { SettingsStore.setCustomBackgroundHex(it) },
            onReset = {
                SettingsStore.setCustomBackgroundHex("")
                BeansHaptics.select()
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = beansLocalized("主页壁纸", "Home wallpaper"),
                    color = colorsLabel(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (backgroundImagePath.isBlank()) {
                        beansLocalized("当前：默认背景", "Current: default background")
                    } else {
                        beansLocalized("当前：已应用壁纸", "Current: wallpaper applied")
                    },
                    color = colorsComment(),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BeansGlassButton(
                title = beansLocalized("选择壁纸", "Choose"),
                systemName = "photo",
                onClick = { pickWallpaper.launch("image/*") },
                style = style,
            )
        }

        if (backgroundImagePath.isNotBlank()) {
            BeansGlassButton(
                title = beansLocalized("清除当前背景", "Clear wallpaper"),
                systemName = "trash",
                onClick = {
                    SettingsStore.setBackgroundImagePath("")
                    BeansHaptics.select()
                },
                style = style,
            )
        }

        SettingsSliderRow(
            title = beansLocalized("主页壁纸模糊度", "Wallpaper blur"),
            valueText = "${wallpaperBlur.toInt()}",
            value = wallpaperBlur,
            valueRange = 0f..30f,
            steps = 29,
            onValueChange = { SettingsStore.setWallpaperBlur(it) },
        )

        SettingsDivider()

        SettingsToggleRow(
            title = beansLocalized("同步到搜索 / 音乐库 / 我的", "Sync to Search / Library / Profile"),
            description = null,
            systemName = "square.grid.2x2",
            checked = backgroundSyncAll,
            onCheckedChange = {
                SettingsStore.setBackgroundSyncAll(it)
                BeansHaptics.select()
            },
        )

        BeansGlassButton(
            title = beansLocalized("恢复默认背景", "Restore default background"),
            systemName = "arrow.clockwise",
            onClick = {
                SettingsStore.setCustomBackgroundHex("")
                SettingsStore.setBackgroundImagePath("")
                BeansHaptics.select()
            },
            style = style,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 播放
// ---------------------------------------------------------------------------------------------

@Composable
private fun PlaybackSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    style: BeansUIStyle,
) {
    val audioQuality by SettingsStore.audioQuality.collectAsState()
    val speed by SettingsStore.playbackSpeed.collectAsState()
    val highRefresh by SettingsStore.highRefresh.collectAsState()
    val hapticsEnabled by SettingsStore.hapticsEnabled.collectAsState()
    val autoResume by SettingsStore.autoResumeLast.collectAsState()
    val playbackSource by SettingsStore.playbackSource.collectAsState()

    SettingsGroupCard(
        title = beansLocalized("播放设置", "Playback"),
        subtitle = beansLocalized("音质、倍速、刷新率与启动行为", "Quality, speed, refresh rate and launch behaviour"),
        systemName = "play.circle",
        expanded = expanded,
        onToggle = onToggle,
        style = style,
    ) {
        SettingsFieldLabel(beansLocalized("播放音质", "Playback quality"))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BeansAudioQuality.entries.forEach { quality ->
                SettingsChip(
                    title = quality.displayName,
                    selected = quality.level == audioQuality,
                    onClick = {
                        SettingsStore.setAudioQuality(quality.level)
                        BeansHaptics.select()
                    },
                )
            }
        }
        Text(
            text = beansLocalized(
                "按列表选择，无法使用时会由平台自动降级。",
                "Choose a quality below; the platform will downgrade automatically when unavailable.",
            ),
            color = colorsComment(),
            fontSize = 11.sp,
        )

        SettingsDivider()

        SettingsSliderRow(
            title = beansLocalized("播放速度", "Playback speed"),
            valueText = String.format(java.util.Locale.US, "%.2fx", speed),
            value = speed,
            valueRange = 0.5f..3f,
            steps = 49,
            onValueChange = {
                SettingsStore.setPlaybackSpeed(it)
                PlaybackController.setSpeed(it)
            },
        )

        SettingsDivider()

        SettingsToggleRow(
            title = beansLocalized("120Hz 高刷新", "120Hz high refresh"),
            description = beansLocalized(
                "关闭后动画按系统刷新率运行",
                "Turn off to let animations follow the system refresh rate",
            ),
            systemName = "sparkles",
            checked = highRefresh,
            onCheckedChange = { SettingsStore.setHighRefresh(it) },
        )

        SettingsToggleRow(
            title = beansLocalized("触感反馈", "Haptic feedback"),
            description = null,
            systemName = "iphone.radiowaves.left.and.right",
            checked = hapticsEnabled,
            onCheckedChange = {
                SettingsStore.setHapticsEnabled(it)
                if (it) BeansHaptics.tap()
            },
        )

        SettingsToggleRow(
            title = beansLocalized("启动时自动播放上次歌曲", "Auto-play the last song on launch"),
            description = beansLocalized(
                "打开软件后自动恢复上次未播放完的歌曲",
                "Automatically resume the last unfinished song when the app starts.",
            ),
            systemName = "play.square.stack",
            checked = autoResume,
            onCheckedChange = { SettingsStore.setAutoResume(it) },
        )

        // ---------------------------------------------------------------- 播放来源
        SettingsDivider()

        SettingsFieldLabel(beansLocalized("播放来源", "Playback source"))
        SettingsSegmented(
            options = PlaybackSource.entries,
            selected = PlaybackSource.fromKey(playbackSource),
            label = { beansLocalized(it.zh, it.en) },
            onSelect = {
                SettingsStore.setPlaybackSource(it.key)
                BeansHaptics.select()
            },
        )
        Text(
            text = when (PlaybackSource.fromKey(playbackSource)) {
                PlaybackSource.AUTO -> beansLocalized(
                    "优先官方，失败后尝试已启用音源",
                    "Prefer official, then try enabled sources on failure",
                )

                PlaybackSource.OFFICIAL -> beansLocalized(
                    "仅使用官方接口",
                    "Use official APIs only",
                )

                PlaybackSource.THIRD_PARTY -> beansLocalized(
                    "优先使用已启用的第三方音源",
                    "Prefer enabled third-party sources",
                )
            },
            color = colorsComment(),
            fontSize = 11.sp,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 均衡器
// ---------------------------------------------------------------------------------------------

@Composable
private fun EqualizerSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    style: BeansUIStyle,
) {
    val eqEnabled by SettingsStore.eqEnabled.collectAsState()
    val eqPreset by SettingsStore.eqPreset.collectAsState()
    val eqBandsRaw by SettingsStore.eqBands.collectAsState()
    val bassBoost by SettingsStore.bassBoost.collectAsState()
    val virtualizer by SettingsStore.virtualizer.collectAsState()

    val bands = remember(eqEnabled) { EqualizerController.bands() }
    val bandGains = remember(eqBandsRaw) { parseEqualizerBands(eqBandsRaw) }

    SettingsGroupCard(
        title = beansLocalized("均衡器", "Equalizer"),
        subtitle = if (eqEnabled) {
            beansLocalized("已开启", "On")
        } else {
            beansLocalized("已关闭", "Off")
        },
        systemName = "waveform",
        expanded = expanded,
        onToggle = onToggle,
        style = style,
    ) {
        SettingsToggleRow(
            title = beansLocalized("启用均衡器", "Enable equalizer"),
            description = beansLocalized(
                "播放时实时应用当前调节",
                "Apply the current tuning while playing",
            ),
            systemName = "waveform",
            checked = eqEnabled,
            onCheckedChange = {
                EqualizerController.setEnabled(it)
                BeansHaptics.select()
            },
        )

        Text(
            text = beansLocalized(
                "均衡器仅处理 LuluMusic 当前播放的音乐，不影响其他 App。",
                "The equalizer only affects music playing in LuluMusic.",
            ),
            color = colorsComment(),
            fontSize = 11.sp,
        )

        if (!EqualizerController.isAvailable) {
            Text(
                text = beansLocalized(
                    "均衡器会在开始播放后可用：Android 的音频效果需要绑定播放会话。",
                    "The equalizer becomes available once playback starts: Android audio effects need an audio session.",
                ),
                color = colorsComment(),
                fontSize = 11.sp,
            )
        }

        SettingsDivider()

        SettingsFieldLabel(beansLocalized("预设", "Preset"))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EqualizerController.Preset.entries.forEach { preset ->
                SettingsChip(
                    title = beansLocalized(preset.zh, preset.en),
                    selected = preset == EqualizerController.Preset.fromKey(eqPreset),
                    onClick = {
                        EqualizerController.usePreset(preset)
                        BeansHaptics.select()
                    },
                )
            }
        }

        SettingsDivider()

        if (bands.isEmpty()) {
            Text(
                text = beansLocalized(
                    "频段调节需要设备暴露均衡器频段，连接播放会话后在此显示。",
                    "Band sliders appear here once the device exposes equalizer bands through an audio session.",
                ),
                color = colorsComment(),
                fontSize = 11.sp,
            )
        } else {
            SettingsFieldLabel(
                beansLocalized("自定义调节", "Custom tuning") + " · " +
                    beansLocalized("范围按设备频段", "device band range")
            )
            bands.forEach { band ->
                val valueDb = (bandGains[band.index] ?: 0) / 100f
                SettingsSliderRow(
                    title = frequencyLabel(band.centerFreqHz),
                    valueText = String.format(java.util.Locale.US, "%+.1f dB", valueDb),
                    value = valueDb,
                    valueRange = (band.minLevelMb / 100f)..(band.maxLevelMb / 100f),
                    steps = 0,
                    onValueChange = { db ->
                        EqualizerController.setBandLevel(
                            index = band.index,
                            levelMb = (db * 100f).toInt().toShort(),
                        )
                    },
                )
            }
        }

        SettingsDivider()

        SettingsSliderRow(
            title = beansLocalized("低音增强", "Bass boost"),
            valueText = "${(bassBoost * 100 / 1000)}%",
            value = bassBoost.toFloat(),
            valueRange = 0f..1000f,
            steps = 19,
            onValueChange = { EqualizerController.setBassBoost(it.toInt()) },
        )

        SettingsSliderRow(
            title = beansLocalized("环绕声 / 虚拟化", "Virtualizer"),
            valueText = "${(virtualizer * 100 / 1000)}%",
            value = virtualizer.toFloat(),
            valueRange = 0f..1000f,
            steps = 19,
            onValueChange = { EqualizerController.setVirtualizer(it.toInt()) },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 歌词
// ---------------------------------------------------------------------------------------------

@Composable
private fun LyricsSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    style: BeansUIStyle,
) {
    val translation by SettingsStore.lyricsTranslation.collectAsState()
    val fontSize by SettingsStore.lyricsFontSize.collectAsState()
    val align by SettingsStore.lyricsAlign.collectAsState()
    val offset by SettingsStore.lyricOffset.collectAsState()

    SettingsGroupCard(
        title = beansLocalized("歌词", "Lyrics"),
        subtitle = beansLocalized("翻译、字号、对齐与时间轴偏移", "Translation, font size, alignment and offset"),
        systemName = "text.alignleft",
        expanded = expanded,
        onToggle = onToggle,
        style = style,
    ) {
        SettingsToggleRow(
            title = beansLocalized("显示歌词翻译", "Show lyric translation"),
            description = null,
            systemName = "doc.text",
            checked = translation,
            onCheckedChange = { SettingsStore.setLyricsTranslation(it) },
        )

        SettingsSliderRow(
            title = beansLocalized("歌词字号", "Lyric font size"),
            valueText = "${fontSize.toInt()}",
            value = fontSize,
            valueRange = 12f..32f,
            steps = 19,
            onValueChange = { SettingsStore.setLyricsFontSize(it) },
        )

        SettingsFieldLabel(beansLocalized("歌词对齐", "Lyric alignment"))
        SettingsSegmented(
            options = listOf("left", "center"),
            selected = if (align == "left") "left" else "center",
            label = {
                if (it == "left") {
                    beansLocalized("左对齐", "Left")
                } else {
                    beansLocalized("居中", "Center")
                }
            },
            onSelect = {
                SettingsStore.setLyricsAlign(it)
                BeansHaptics.select()
            },
        )

        SettingsSliderRow(
            title = beansLocalized("歌词时间轴偏移", "Lyric offset"),
            valueText = String.format(java.util.Locale.US, "%+.1fs", offset),
            value = offset,
            valueRange = -5f..5f,
            steps = 49,
            onValueChange = { SettingsStore.setLyricOffset(it) },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 语言
// ---------------------------------------------------------------------------------------------

@Composable
private fun LanguageSection(style: BeansUIStyle) {
    val language by SettingsStore.language.collectAsState()
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .beansGlass(shape = shape, style = style)
            .clip(shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = BeansIcons.of("globe", Icons.Rounded.Settings),
                contentDescription = null,
                tint = colorsAccent(),
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = beansLocalized("语言", "Language"),
                color = colorsLabel(),
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
        }
        SettingsSegmented(
            options = AppLanguage.entries,
            selected = language,
            label = {
                when (it) {
                    AppLanguage.CHINESE -> "简体中文"
                    AppLanguage.ENGLISH -> "English"
                }
            },
            onSelect = {
                SettingsStore.setLanguage(it)
                BeansHaptics.select()
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 平台
// ---------------------------------------------------------------------------------------------

@Composable
private fun PlatformSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    style: BeansUIStyle,
) {
    val enabledRaw by SettingsStore.enabledProviders.collectAsState()
    val homeProvider by SettingsStore.homeProvider.collectAsState()
    val tabLabelsVisible by SettingsStore.tabLabelsVisible.collectAsState()
    val thirdPartySources by UnblockSourceStore.sources.collectAsState()
    val navigator = LocalBeansNavigator.current

    val enabled = enabledRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val homeOptions = enabled.ifEmpty { listOf("netease") }

    SettingsGroupCard(
        title = beansLocalized("平台显示", "Platforms"),
        subtitle = beansLocalized("启用音源、主页默认来源与底栏文字", "Providers, home source and tab labels"),
        systemName = "square.grid.2x2",
        expanded = expanded,
        onToggle = onToggle,
        style = style,
    ) {
        SettingsToggleRow(
            title = beansLocalized("网易云音乐", "NetEase Cloud Music"),
            description = null,
            systemName = null,
            checked = enabled.contains("netease"),
            onCheckedChange = {
                SettingsStore.setEnabledProviders(toggleProvider(enabledRaw, "netease"))
                BeansHaptics.select()
            },
        )
        SettingsToggleRow(
            title = beansLocalized("QQ 音乐", "QQ Music"),
            description = null,
            systemName = null,
            checked = enabled.contains("qq"),
            onCheckedChange = {
                SettingsStore.setEnabledProviders(toggleProvider(enabledRaw, "qq"))
                BeansHaptics.select()
            },
        )
        SettingsToggleRow(
            title = beansLocalized("酷狗音乐", "Kugou Music"),
            description = null,
            systemName = null,
            checked = enabled.contains("kugou"),
            onCheckedChange = {
                SettingsStore.setEnabledProviders(toggleProvider(enabledRaw, "kugou"))
                BeansHaptics.select()
            },
        )
        Text(
            text = beansLocalized(
                "至少保留一个平台。",
                "At least one platform must stay enabled.",
            ),
            color = colorsComment(),
            fontSize = 11.sp,
        )

        SettingsDivider()

        SettingsFieldLabel(beansLocalized("主页默认来源", "Home provider"))
        SettingsSegmented(
            options = homeOptions,
            selected = homeOptions.firstOrNull { it == homeProvider } ?: homeOptions.first(),
            label = { providerTitle(it) },
            onSelect = {
                SettingsStore.setHomeProvider(it)
                BeansHaptics.select()
            },
        )

        SettingsDivider()

        SettingsToggleRow(
            title = beansLocalized("底栏显示文字", "Show tab labels"),
            description = null,
            systemName = null,
            checked = tabLabelsVisible,
            onCheckedChange = { SettingsStore.setTabLabelsVisible(it) },
        )

        // ---------------------------------------------------------------- 第三方音源
        SettingsDivider()

        SettingsNavigationRow(
            title = beansLocalized("导入和管理第三方音源", "Import & manage sources"),
            description = if (thirdPartySources.isEmpty()) {
                beansLocalized("尚未导入音源", "No sources imported")
            } else {
                beansLocalized(
                    "已导入 ${thirdPartySources.size} 个音源",
                    "${thirdPartySources.size} sources imported",
                )
            },
            systemName = "square.stack",
            onClick = {
                BeansHaptics.tap()
                navigator.openSources()
            },
        )

        // ---------------------------------------------------------------- 崩溃日志
        // 放在屏幕最后一个分组的末尾（页脚之前）：[CrashLog] 的诞生背景就是第三方音源导入闪退，
        // 和上面这行「导入和管理第三方音源」属于同一类排查入口，所以不再单开一个分组标题。
        SettingsDivider()

        CrashLogRow(style = style)
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 崩溃日志
// ---------------------------------------------------------------------------------------------

/**
 * 崩溃日志入口：真机闪退、又拿不到 adb 的用户，可以直接在 App 内查看并复制堆栈。
 *
 * 副标题：有记录时显示最新一条的时间（`最近记录：2026-09-21 16:41`），没有记录时显示「暂无崩溃记录」。
 * 点击后打开 [CrashLogDialog]；每次打开都重新读盘，保证看到的是最新内容。
 */
@Composable
private fun CrashLogRow(style: BeansUIStyle) {
    var logText by remember { mutableStateOf(readCrashLogSafely()) }
    var showDialog by remember { mutableStateOf(false) }

    val latest = crashLogLatestTime(logText)

    SettingsNavigationRow(
        title = beansLocalized("崩溃日志", "Crash log"),
        description = if (latest == null) {
            beansLocalized("暂无崩溃记录", "No crashes recorded")
        } else {
            beansLocalized("最近记录：$latest", "Last entry: $latest")
        },
        systemName = "exclamationmark.triangle",
        onClick = {
            BeansHaptics.tap()
            logText = readCrashLogSafely()
            showDialog = true
        },
    )

    if (showDialog) {
        CrashLogDialog(
            text = logText,
            onClear = {
                CrashLog.clear()
                logText = readCrashLogSafely()
                BeansToastCenter.show(beansLocalized("已清空崩溃日志", "Crash log cleared"))
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * 崩溃日志查看弹窗：等宽小字号 + 可滚动，底部提供「复制 / 清空 / 关闭」。
 *
 * 「清空」会先把弹窗切到确认态（同一弹窗内切换，不再叠一层），确认后才调用 [CrashLog.clear]。
 */
@Composable
private fun CrashLogDialog(
    text: String,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BeansTheme.colors
    val context = LocalContext.current
    var confirmingClear by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = {
            Text(
                text = if (confirmingClear) {
                    beansLocalized("清空崩溃日志", "Clear crash log")
                } else {
                    beansLocalized("崩溃日志", "Crash log")
                },
                color = colors.label,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            when {
                confirmingClear -> Text(
                    text = beansLocalized(
                        "清空后无法恢复，确定要清空全部崩溃记录吗？",
                        "This cannot be undone. Clear every recorded crash?",
                    ),
                    color = colors.comment,
                    fontSize = 13.sp,
                )

                text.isBlank() -> Text(
                    text = beansLocalized("暂无崩溃记录", "No crashes recorded"),
                    color = colors.comment,
                    fontSize = 13.sp,
                )

                else -> Text(
                    text = text,
                    color = colors.label,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            if (confirmingClear) {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        BeansHaptics.medium()
                        onClear()
                    },
                ) {
                    Text(
                        text = beansLocalized("清空", "Clear"),
                        color = colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        val copied = clipboard != null && runCatching {
                            clipboard.setPrimaryClip(ClipData.newPlainText("LuluMusic crash log", text))
                        }.isSuccess
                        BeansHaptics.success()
                        BeansToastCenter.show(
                            if (copied) {
                                beansLocalized("已复制崩溃日志", "Crash log copied")
                            } else {
                                beansLocalized("复制失败", "Could not copy crash log")
                            }
                        )
                    },
                ) {
                    Text(
                        text = beansLocalized("复制", "Copy"),
                        color = colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        dismissButton = {
            if (confirmingClear) {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(text = beansLocalized("取消", "Cancel"), color = colors.comment)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { confirmingClear = true }) {
                        Text(text = beansLocalized("清空", "Clear"), color = colors.comment)
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = beansLocalized("关闭", "Close"), color = colors.comment)
                    }
                }
            }
        },
    )
}

/** 读盘永不抛异常：任何失败都退化成空串（[CrashLog.read] 内部本身也包了 `runCatching`）。 */
private fun readCrashLogSafely(): String = runCatching { CrashLog.read() }.getOrDefault("")

/** 从日志里取最新一条的时间；日志是最新在前，所以第一个时间戳就是最近一次崩溃。 */
private fun crashLogLatestTime(text: String): String? {
    val match = crashLogTimePattern.find(text) ?: return null
    val (date, hour, minute) = match.destructured
    return "$date $hour:$minute"
}

/** 匹配 `CrashLog` 条目头里的 `yyyy-MM-dd HH:mm:ss(.SSS)`，只取到分钟。 */
private val crashLogTimePattern = Regex("""(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2}):\d{2}""")

@Composable
private fun SettingsFooterNote() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = beansLocalized(
                "LuluMusic · 仅供学习交流，纯 AI 实现此应用",
                "LuluMusic · For learning only, fully AI-implemented",
            ),
            color = colorsComment().copy(alpha = 0.7f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = beansLocalized(
                "接入网易云音乐、QQ 音乐等公开接口",
                "Built on public NetEase Cloud Music / QQ Music interfaces",
            ),
            color = colorsComment().copy(alpha = 0.7f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 通用零件
// ---------------------------------------------------------------------------------------------

@Composable
private fun SettingsTopBar(
    title: String,
    onClose: () -> Unit,
    style: BeansUIStyle,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BeansGlassIconButton(
            systemName = "xmark",
            onClick = onClose,
            size = 40.dp,
            forceLiquid = true,
            style = style,
        )
        Text(
            text = title,
            color = colorsLabel(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        BeansGlassButton(
            title = beansLocalized("完成", "Done"),
            onClick = onClose,
            prominent = true,
            style = style,
        )
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    subtitle: String?,
    systemName: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    style: BeansUIStyle,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BeansTheme.colors
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BeansPressable(
            onClick = {
                BeansHaptics.select()
                onToggle()
            },
            modifier = Modifier.fillMaxWidth(),
            scale = 0.98f,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .beansGlass(shape = RoundedCornerShape(18.dp), style = style)
                    .clip(RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = BeansIcons.of(systemName, Icons.Rounded.Settings),
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        color = colors.label,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            color = colors.comment,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.comment.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .beansCardShadow(radius = 9.dp, y = 3.dp, shape = RoundedCornerShape(22.dp))
                    .beansGlass(shape = RoundedCornerShape(22.dp), style = style)
                    .clip(RoundedCornerShape(22.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String?,
    systemName: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = BeansTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (systemName != null) {
            Icon(
                imageVector = BeansIcons.of(systemName, Icons.Rounded.Settings),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = colors.label, fontSize = 15.sp)
            if (!description.isNullOrBlank()) {
                Text(text = description, color = colors.comment, fontSize = 11.sp, maxLines = 2)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { value ->
                BeansHaptics.select()
                onCheckedChange(value)
            },
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
        )
    }
}

/**
 * 可点击的导航行：图标 + 标题 + 副标题 + 右向箭头。
 *
 * 视觉上与 [SettingsToggleRow] 同族（同样的图标尺寸 / 字号 / 间距），
 * 只是把右侧的开关换成箭头，用于「导入和管理第三方音源」这类跳转到子页面的入口。
 */
@Composable
private fun SettingsNavigationRow(
    title: String,
    description: String?,
    systemName: String,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    BeansPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        scale = 0.98f,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = BeansIcons.of(systemName, Icons.Rounded.Settings),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, color = colors.label, fontSize = 15.sp, maxLines = 1)
                if (!description.isNullOrBlank()) {
                    Text(text = description, color = colors.comment, fontSize = 11.sp, maxLines = 2)
                }
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.comment.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SettingsFieldLabel(text: String) {
    Text(
        text = text,
        color = colorsComment(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = colorsComment().copy(alpha = 0.15f))
}

@Composable
private fun <T> SettingsSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colors = BeansTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.label.copy(alpha = if (colors.isDark) 0.08f else 0.05f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) colors.card else Color.Transparent)
                    .beansPressClickable(
                        interactionSource = interaction,
                        scale = 0.97f,
                        onClick = { onSelect(option) },
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) colors.accent else colors.label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SettingsChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }
    BeansCapsule(
        fill = if (selected) colors.accent.copy(alpha = 0.14f) else colors.label.copy(alpha = 0.055f),
        borderColor = if (selected) colors.accent.copy(alpha = 0.42f) else colors.label.copy(alpha = 0.08f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier
            .height(31.dp)
            .beansPressClickable(
                interactionSource = interaction,
                scale = 0.95f,
                onClick = onClick,
            ),
    ) {
        Text(
            text = title,
            color = if (selected) colors.accent else colors.label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

@Composable
private fun SettingsSliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    val colors = BeansTheme.colors
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = colors.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                color = colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.comment.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun SettingsColorRow(
    title: String,
    hint: String?,
    hex: String,
    fallback: Color,
    onApply: (String) -> Unit,
    onReset: () -> Unit,
) {
    val colors = BeansTheme.colors
    var draft by remember(hex) { mutableStateOf(hex) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = colors.label, fontSize = 14.sp)
                if (!hint.isNullOrBlank()) {
                    Text(text = hint, color = colors.comment, fontSize = 11.sp, maxLines = 1)
                }
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(parseHexColor(hex) ?: fallback)
                    .border(0.8.dp, colors.label.copy(alpha = 0.15f), CircleShape),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            beansColorPresets.forEach { preset ->
                val isSelected = preset.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(preset) ?: Color.Gray)
                        .border(
                            width = if (isSelected) 2.dp else 0.8.dp,
                            color = if (isSelected) colors.label else colors.label.copy(alpha = 0.2f),
                            shape = CircleShape,
                        )
                        .clickable {
                            BeansHaptics.select()
                            onApply(preset)
                        },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { input ->
                    draft = input
                    if (isValidHex(input)) onApply(normalizeHex(input))
                },
                singleLine = true,
                placeholder = { Text("#RRGGBB", fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.weight(1f),
            )
            BeansGlassButton(
                title = beansLocalized("恢复默认", "Reset"),
                onClick = {
                    draft = ""
                    onReset()
                },
                style = BeansUIStyle.LIQUID,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 工具
// ---------------------------------------------------------------------------------------------

@Composable
private fun colorsLabel(): Color = BeansTheme.colors.label

@Composable
private fun colorsComment(): Color = BeansTheme.colors.comment

@Composable
private fun colorsAccent(): Color = BeansTheme.colors.accent

@Composable
private fun colorsBackground(): Color = BeansTheme.colors.background

private val beansColorPresets = listOf(
    "#FF9F0A",
    "#FF375F",
    "#BF5AF2",
    "#0A84FF",
    "#64D2FF",
    "#30D158",
    "#FFD60A",
    "#8E8E93",
)

private fun themeModeTitleZh(mode: BeansThemeMode): String = when (mode) {
    BeansThemeMode.SYSTEM -> "跟随系统"
    BeansThemeMode.LIGHT -> "浅色"
    BeansThemeMode.DARK -> "深色"
}

private fun themeModeTitleEn(mode: BeansThemeMode): String = when (mode) {
    BeansThemeMode.SYSTEM -> "System"
    BeansThemeMode.LIGHT -> "Light"
    BeansThemeMode.DARK -> "Dark"
}

private fun providerTitle(raw: String): String = when (raw) {
    "netease" -> beansLocalized("网易云音乐", "NetEase")
    "qq" -> beansLocalized("QQ 音乐", "QQ Music")
    "kugou" -> beansLocalized("酷狗音乐", "Kugou")
    else -> raw
}

private fun frequencyLabel(centerFreqHz: Int): String {
    if (centerFreqHz >= 1000) {
        val value = centerFreqHz / 1000.0
        return if (value == value.toInt().toDouble()) {
            "${value.toInt()} kHz"
        } else {
            String.format(java.util.Locale.US, "%.1f kHz", value)
        }
    }
    return "$centerFreqHz Hz"
}

/** `index:millibels,index:millibels,...` → map (same persisted format as `EqualizerController`). */
private fun parseEqualizerBands(raw: String): Map<Short, Short> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(',').mapNotNull { part ->
        val bits = part.split(':')
        if (bits.size != 2) return@mapNotNull null
        val index = bits[0].trim().toShortOrNull() ?: return@mapNotNull null
        val level = bits[1].trim().toShortOrNull() ?: return@mapNotNull null
        index to level
    }.toMap()
}

private fun toggleProvider(raw: String, key: String): String {
    val list = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    if (list.contains(key)) {
        // Never allow the last provider to be switched off (the home tab needs a source).
        if (list.size > 1) list.remove(key)
    } else {
        list.add(key)
    }
    return list.joinToString(",")
}

private fun isValidHex(value: String): Boolean {
    val cleaned = value.removePrefix("#").trim()
    return cleaned.length == 6 && cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}

private fun normalizeHex(value: String): String = "#" + value.removePrefix("#").trim().uppercase()

package com.lulu.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.data.prefs.SettingsStore

val LocalBeansColors = staticCompositionLocalOf {
    beansColors(isDark = true)
}

/** Convenience accessor matching `Color.beansAmber` / `.beansLabel` on iOS. */
object BeansTheme {
    val colors: BeansColors
        @Composable get() = LocalBeansColors.current
}

@Composable
fun BeansTheme(
    themeMode: BeansThemeMode = BeansThemeMode.SYSTEM,
    accentKey: String = "amber",
    customAccent: Color? = null,
    labelOverride: Color? = null,
    commentOverride: Color? = null,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        BeansThemeMode.SYSTEM -> systemDark
        BeansThemeMode.LIGHT -> false
        BeansThemeMode.DARK -> true
    }

    val accent = AccentTheme.fromKey(accentKey)
    val colors = remember(isDark, accentKey, customAccent, labelOverride, commentOverride) {
        beansColors(
            isDark = isDark,
            accent = accent,
            customAccent = customAccent,
            labelOverride = labelOverride,
            commentOverride = commentOverride,
        )
    }

    val scheme = remember(colors) {
        if (colors.isDark) {
            darkColorScheme(
                primary = colors.accent,
                onPrimary = Color.Black,
                secondary = colors.sage,
                background = colors.background,
                onBackground = colors.label,
                surface = colors.card,
                onSurface = colors.label,
                surfaceVariant = colors.card,
                onSurfaceVariant = colors.comment,
            )
        } else {
            lightColorScheme(
                primary = colors.accent,
                onPrimary = Color.White,
                secondary = colors.sage,
                background = colors.background,
                onBackground = colors.label,
                surface = colors.card,
                onSurface = colors.label,
                surfaceVariant = colors.card,
                onSurfaceVariant = colors.comment,
            )
        }
    }

    CompositionLocalProvider(LocalBeansColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Root-level theme that reads appearance preferences from [SettingsStore].
 * All appearance options mirror the iOS 「我的 → 外观」 settings.
 */
@Composable
fun BeansAppTheme(content: @Composable () -> Unit) {
    val themeMode by SettingsStore.themeMode.collectAsState()
    val accentHex by SettingsStore.accentHex.collectAsState()
    val labelHex by SettingsStore.labelColorHex.collectAsState()

    BeansTheme(
        themeMode = themeMode,
        accentKey = accentHex.ifBlank { "amber" },
        customAccent = parseHexColor(accentHex),
        labelOverride = parseHexColor(labelHex),
        content = content,
    )
}

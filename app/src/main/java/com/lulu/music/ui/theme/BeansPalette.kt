package com.lulu.music.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Port of the iOS `AccentTheme` enum.
 *
 * [tintLight] / [tintDark] correspond to `tintLight` / `tintDark` in Theme.swift,
 * [highlightStart] / [highlightEnd] to the two stops of `AccentTheme.highlight`.
 */
enum class AccentTheme(
    val key: String,
    val titleZh: String,
    val titleEn: String,
    val tintLight: Color,
    val tintDark: Color,
    val highlightStart: Color,
    val highlightEnd: Color,
) {
    AMBER(
        "amber", "琥珀", "Amber",
        Color(0.72f, 0.44f, 0.03f), Color(0.96f, 0.70f, 0.35f),
        Color(0.949f, 0.639f, 0.235f), Color(0.753f, 0.478f, 0.039f),
    ),
    MINT(
        "mint", "湖绿", "Mint",
        Color(0.15f, 0.53f, 0.39f), Color(0.45f, 0.80f, 0.64f),
        Color(0.42f, 0.78f, 0.62f), Color(0.16f, 0.55f, 0.42f),
    ),
    PINK(
        "pink", "樱粉", "Pink",
        Color(0.78f, 0.33f, 0.53f), Color(0.97f, 0.60f, 0.74f),
        Color(0.96f, 0.56f, 0.70f), Color(0.82f, 0.37f, 0.56f),
    ),
    SKY(
        "sky", "星蓝", "Sky",
        Color(0.20f, 0.48f, 0.84f), Color(0.45f, 0.72f, 0.98f),
        Color(0.39f, 0.71f, 0.96f), Color(0.23f, 0.48f, 0.84f),
    ),
    VIOLET(
        "violet", "罗兰", "Violet",
        Color(0.46f, 0.34f, 0.77f), Color(0.71f, 0.62f, 0.92f),
        Color(0.70f, 0.62f, 0.86f), Color(0.49f, 0.34f, 0.76f),
    ),
    CYBER(
        "cyber", "赛博青", "Cyber",
        Color(0.05f, 0.48f, 0.55f), Color(0.35f, 0.95f, 0.88f),
        Color(0.25f, 0.90f, 0.85f), Color(0.05f, 0.55f, 0.65f),
    ),
    PEACH(
        "peach", "蜜桃", "Peach",
        Color(0.82f, 0.32f, 0.45f), Color(1.00f, 0.68f, 0.58f),
        Color(1.00f, 0.62f, 0.52f), Color(0.95f, 0.38f, 0.55f),
    ),
    GOLD(
        "gold", "鎏金", "Gold",
        Color(0.55f, 0.40f, 0.08f), Color(0.94f, 0.80f, 0.45f),
        Color(0.92f, 0.75f, 0.35f), Color(0.45f, 0.33f, 0.10f),
    ),
    EMERALD(
        "emerald", "翡翠", "Emerald",
        Color(0.08f, 0.45f, 0.30f), Color(0.42f, 0.92f, 0.62f),
        Color(0.30f, 0.85f, 0.55f), Color(0.05f, 0.50f, 0.35f),
    );

    companion object {
        fun fromKey(key: String?): AccentTheme =
            entries.firstOrNull { it.key == key } ?: AMBER
    }
}

/** Resolved colour set for the current theme mode + accent. */
data class BeansColors(
    val background: Color,
    val card: Color,
    val label: Color,
    val comment: Color,
    val secondary: Color,
    val accent: Color,
    val highlight: Color,
    val highlightEnd: Color,
    val sage: Color,
    val glassFill: Color,
    val isDark: Boolean,
)

/** Port of the iOS `beansDynamic(light:dark:)` helper. */
private fun dynamic(light: Color, dark: Color, isDark: Boolean) = if (isDark) dark else light

fun beansColors(
    isDark: Boolean,
    accent: AccentTheme = AccentTheme.AMBER,
    customAccent: Color? = null,
    labelOverride: Color? = null,
    commentOverride: Color? = null,
): BeansColors {
    val resolvedAccent = when {
        customAccent != null -> dynamic(customAccent.shade(0.75f), customAccent, isDark)
        else -> dynamic(accent.tintLight, accent.tintDark, isDark)
    }
    return BeansColors(
        background = dynamic(Color(0xFFF2F2F7), Color(0xFF0A0A0C), isDark),
        card = dynamic(Color.White, Color(0xFF1C1C1E), isDark),
        label = labelOverride ?: dynamic(Color.Black, Color.White, isDark),
        comment = commentOverride ?: dynamic(Color(0xFF6C6C70), Color(0xFF98989F), isDark),
        secondary = dynamic(Color(0xFF6C6C70), Color(0xFF98989F), isDark),
        accent = resolvedAccent,
        highlight = accent.highlightStart,
        highlightEnd = accent.highlightEnd,
        sage = dynamic(Color(0.384f, 0.482f, 0.310f), Color(0.560f, 0.650f, 0.480f), isDark),
        glassFill = dynamic(Color(0.96f, 0.96f, 0.96f, 0.55f), Color(0.07f, 0.07f, 0.07f, 0.55f), isDark),
        isDark = isDark,
    )
}

/** Port of `UIColor.shaded(_:)` — scale RGB toward black. */
fun Color.shade(factor: Float): Color = copy(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
)

/** Parse `#RRGGBB` / `#AARRGGBB`, returning null when unparseable. */
fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val cleaned = hex.removePrefix("#").trim()
    return try {
        when (cleaned.length) {
            6 -> Color(0xFF000000L.toInt() or cleaned.toLong(16).toInt())
            8 -> Color(cleaned.toLong(16).toInt())
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}

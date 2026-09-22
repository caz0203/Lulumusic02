package com.lulu.music.data.model

import com.lulu.music.data.prefs.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Port of the iOS global `beansLocalized(_:_:)` helper.
 *
 * The active language is mirrored into a [MutableStateFlow] so Compose can react
 * to language changes without recreating the whole tree.
 */
object Lang {
    val current = MutableStateFlow(AppLanguage.CHINESE)

    fun localized(zh: String, en: String): String =
        if (current.value == AppLanguage.ENGLISH) en else zh
}

internal fun beansLocalized(zh: String, en: String): String = Lang.localized(zh, en)

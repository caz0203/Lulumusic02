package com.lulu.music.data.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Search history, port of the iOS `SearchHistoryStore`.
 * Newest first, case-insensitively de-duplicated, capped at 20 entries.
 */
object SearchHistoryStore {

    private const val KEY = "beans.search.history.v1"
    private const val MAX_COUNT = 20

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        _history.value = Prefs.readStringList(KEY)
    }

    fun record(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        val list = _history.value
            .filterNot { it.equals(trimmed, ignoreCase = true) }
            .toMutableList()
        list.add(0, trimmed)
        while (list.size > MAX_COUNT) list.removeAt(list.size - 1)
        _history.value = list
        Prefs.writeStringList(KEY, list)
    }

    fun remove(keyword: String) {
        val list = _history.value.filterNot { it == keyword }
        _history.value = list
        Prefs.writeStringList(KEY, list)
    }

    fun clear() {
        _history.value = emptyList()
        Prefs.remove(KEY)
    }
}

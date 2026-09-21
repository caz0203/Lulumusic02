package com.lulu.music.data.store

import com.lulu.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Playback history ("最近播放").
 *
 * Mirrors the iOS `PlayerManager.history`: newest first, de-duplicated by cross-platform identity
 * so a song does not appear twice when replayed, capped so the list cannot grow without bound.
 */
object PlayHistoryStore {

    private const val KEY = "beans.play.history.v1"
    private const val MAX_COUNT = 200

    private val _history = MutableStateFlow<List<Song>>(emptyList())
    val history: StateFlow<List<Song>> = _history.asStateFlow()

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        _history.value = Prefs.readList(KEY, Song.serializer())
    }

    /** Record a song as played, moving it to the top. */
    fun record(song: Song) {
        val list = _history.value
            .filterNot { it.identityKey == song.identityKey }
            .toMutableList()
        list.add(0, song)
        while (list.size > MAX_COUNT) list.removeAt(list.size - 1)
        _history.value = list
        Prefs.writeList(KEY, Song.serializer(), list)
    }

    fun clear() {
        _history.value = emptyList()
        Prefs.remove(KEY)
    }
}

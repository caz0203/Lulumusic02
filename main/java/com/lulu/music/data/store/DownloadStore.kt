package com.lulu.music.data.store

import com.lulu.music.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A song that has been downloaded for offline playback. */
@Serializable
data class DownloadRecord(
    val song: Song,
    val fileName: String,
    /** Quality key the file was fetched at (see `ThirdPartyAudioQuality`). */
    val quality: String,
    val bytes: Long,
    /** epoch millis */
    val downloadedAt: Long,
)

/**
 * Offline library. Audio lives in the app's private `filesDir/downloads`, and the index here is
 * persisted in SharedPreferences as JSON — so the list survives restarts and a missing file is
 * detected (and pruned) rather than crashing playback.
 */
object DownloadStore {

    private const val KEY = "beans.downloads.v1"

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        _records.value = Prefs.readList(KEY, DownloadRecord.serializer())
    }

    fun isDownloaded(song: Song?): Boolean {
        if (song == null) return false
        return _records.value.any { it.song.identityKey == song.identityKey }
    }

    fun recordFor(song: Song?): DownloadRecord? {
        if (song == null) return null
        return _records.value.firstOrNull { it.song.identityKey == song.identityKey }
    }

    fun upsert(record: DownloadRecord) {
        val list = _records.value
            .filterNot { it.song.identityKey == record.song.identityKey }
            .toMutableList()
        list.add(0, record)
        _records.value = list
        Prefs.writeList(KEY, DownloadRecord.serializer(), list)
    }

    fun remove(song: Song) {
        val list = _records.value.filterNot { it.song.identityKey == song.identityKey }
        _records.value = list
        Prefs.writeList(KEY, DownloadRecord.serializer(), list)
    }

    fun clear() {
        _records.value = emptyList()
        Prefs.remove(KEY)
    }
}

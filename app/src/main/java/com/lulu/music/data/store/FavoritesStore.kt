package com.lulu.music.data.store

import com.lulu.music.data.api.NetEaseApi
import com.lulu.music.data.api.QQMusicApi
import com.lulu.music.data.auth.QQMusicAuth
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Favourites ("红心"), port of the iOS `FavoritesStore`.
 *
 * NetEase favourites are synced to the cloud and rolled back locally when the server rejects the
 * change; QQ favourites are persisted locally and best-effort synced (a failed sync must NOT lose
 * the local entry); Kugou has no stable cloud write API, so it stays local-only.
 */
object FavoritesStore {

    private const val KEY_NETEASE = "beans.fav.netease.v1"
    private const val KEY_QQ = "beans.fav.qq.v1"
    private const val KEY_KUGOU = "beans.fav.kugou.v1"

    private val _netease = MutableStateFlow<List<Song>>(emptyList())
    val neteaseFavoriteSongs: StateFlow<List<Song>> = _netease.asStateFlow()

    private val _qq = MutableStateFlow<List<Song>>(emptyList())
    val qqFavoriteSongs: StateFlow<List<Song>> = _qq.asStateFlow()

    private val _kugou = MutableStateFlow<List<Song>>(emptyList())
    val kugouFavoriteSongs: StateFlow<List<Song>> = _kugou.asStateFlow()

    private var loaded = false

    /** Idempotent; reads the persisted collections once. */
    fun load() {
        if (loaded) return
        loaded = true
        _netease.value = Prefs.readList(KEY_NETEASE, Song.serializer())
        _qq.value = Prefs.readList(KEY_QQ, Song.serializer())
        _kugou.value = Prefs.readList(KEY_KUGOU, Song.serializer())
    }

    fun isLiked(song: Song?): Boolean {
        if (song == null) return false
        return when (song.source) {
            SongSource.NET_EASE -> _netease.value.any { it.id == song.id }
            SongSource.QQ -> {
                val mid = song.qqMid
                if (!mid.isNullOrEmpty()) {
                    _qq.value.any { it.qqMid == mid || it.identityKey == song.identityKey }
                } else {
                    _qq.value.any { it.identityKey == song.identityKey }
                }
            }
            SongSource.KUGOU -> _kugou.value.any { it.identityKey == song.identityKey }
        }
    }

    /** All favourites across platforms, newest first — used by the library screen. */
    fun all(): List<Song> = _qq.value + _netease.value + _kugou.value

    /**
     * Toggle the favourite state. Returns true when the change stuck.
     * NetEase rolls back on a failed/ failed-cloud write, mirroring iOS.
     */
    suspend fun toggle(song: Song): Boolean = when (song.source) {
        SongSource.NET_EASE -> {
            val liked = !isLiked(song)
            updateNetease(song, liked)
            val ok = runCatching { NetEaseApi.like(song.id, liked) }.getOrDefault(false)
            if (!ok) updateNetease(song, !liked)
            ok
        }

        SongSource.QQ -> {
            val liked = !isLiked(song)
            updateQQ(song, liked)
            val mid = song.qqMid
            if (!mid.isNullOrEmpty() && QQMusicAuth.isLoggedIn) {
                // Cloud sync is best-effort: a failure keeps the local favourite.
                runCatching { QQMusicApi.like(mid, liked) }
            }
            true
        }

        SongSource.KUGOU -> {
            updateKugou(song, !isLiked(song))
            true
        }
    }

    fun removeQQFavorite(song: Song) = updateQQ(song, false)

    /**
     * Pull "我喜欢" from the QQ cloud after login. Only overwrites when the cloud actually returns
     * songs, so an empty/failed response can never wipe the local list.
     */
    suspend fun syncQQFromCloud() {
        if (!QQMusicAuth.isLoggedIn) return
        val songs = runCatching { QQMusicApi.favoriteSongs() }.getOrNull()
        if (songs.isNullOrEmpty()) return
        _qq.value = songs
        Prefs.writeList(KEY_QQ, Song.serializer(), songs)
    }

    private fun updateNetease(song: Song, liked: Boolean) {
        val list = _netease.value.filterNot { it.id == song.id }.toMutableList()
        if (liked) list.add(0, song)
        _netease.value = list
        Prefs.writeList(KEY_NETEASE, Song.serializer(), list)
    }

    private fun updateQQ(song: Song, liked: Boolean) {
        val list = _qq.value.filterNot { existing ->
            existing.identityKey == song.identityKey ||
                (existing.qqMid != null && song.qqMid != null && existing.qqMid == song.qqMid)
        }.toMutableList()
        if (liked) list.add(0, song)
        _qq.value = list
        Prefs.writeList(KEY_QQ, Song.serializer(), list)
    }

    private fun updateKugou(song: Song, liked: Boolean) {
        val list = _kugou.value.filterNot { it.identityKey == song.identityKey }.toMutableList()
        if (liked) list.add(0, song)
        _kugou.value = list
        Prefs.writeList(KEY_KUGOU, Song.serializer(), list)
    }

    /** Called when the NetEase account logs out — clears the NetEase cache but keeps QQ/Kugou. */
    fun resetNetease() {
        _netease.value = emptyList()
        Prefs.remove(KEY_NETEASE)
    }
}

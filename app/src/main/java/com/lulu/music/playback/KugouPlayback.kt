package com.lulu.music.playback

import com.lulu.music.data.api.KugouMusicApi
import com.lulu.music.data.model.BeansAudioQuality

/**
 * Kugou playback-URL resolution.
 *
 * Kept as a thin adapter so the player does not depend on the Kugou API surface directly.
 * Kugou identifies tracks by hash rather than a numeric id, and the API layer applies its own
 * quality fallback chain internally.
 */
object KugouPlayback {

    suspend fun resolve(
        hash: String?,
        albumAudioId: String?,
        albumId: String?,
        quality: BeansAudioQuality,
    ): String? {
        if (hash.isNullOrBlank() && albumAudioId.isNullOrBlank()) return null
        return runCatching {
            KugouMusicApi.songURL(
                hash = hash.orEmpty(),
                albumAudioId = albumAudioId,
                albumId = albumId,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

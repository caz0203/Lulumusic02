package com.lulu.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.lulu.music.data.api.NetEaseApi
import com.lulu.music.data.api.QQMusicApi
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.source.PlaybackSource
import com.lulu.music.data.source.UnblockService
import kotlinx.coroutines.runBlocking

/**
 * Songs are queued as `beans://song?src=...&id=...` placeholder URIs carrying only what is needed to
 * obtain a real playback URL. The real URL is resolved lazily by [BeansDataSourceFactory] at the moment
 * ExoPlayer is about to load the item.
 *
 * This keeps the whole queue visible to the media session (lock-screen next/previous work across the
 * entire queue) without pre-resolving every URL up front, which would be slow and would expire.
 */
object SongUri {

    private const val SCHEME = "beans"
    private const val HOST = "song"

    fun encode(song: Song): Uri {
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("src", song.source.raw)
            .appendQueryParameter("id", song.id.toString())
        song.qqMid?.let { builder.appendQueryParameter("mid", it) }
        song.qqMediaMid?.let { builder.appendQueryParameter("mmedia", it) }
        song.kugouHash?.let { builder.appendQueryParameter("hash", it) }
        song.kugouAlbumAudioId?.let { builder.appendQueryParameter("aaid", it) }
        song.kugouAlbumId?.let { builder.appendQueryParameter("aid", it) }
        // Cross-platform identity, so offline playback can find a downloaded file without
        // re-deriving it from the per-platform fields.
        builder.appendQueryParameter("k", song.identityKey)
        return builder.build()
    }

    fun isSongUri(uri: Uri): Boolean = uri.scheme == SCHEME && uri.authority == HOST

    /** Returns the source + identifiers encoded in a placeholder URI. */
    fun decode(uri: Uri): Decoded? {
        if (!isSongUri(uri)) return null
        val source = SongSource.fromRaw(uri.getQueryParameter("src"))
        val id = uri.getQueryParameter("id")?.toLongOrNull() ?: return null
        return Decoded(
            source = source,
            id = id,
            qqMid = uri.getQueryParameter("mid"),
            qqMediaMid = uri.getQueryParameter("mmedia"),
            kugouHash = uri.getQueryParameter("hash"),
            kugouAlbumAudioId = uri.getQueryParameter("aaid"),
            kugouAlbumId = uri.getQueryParameter("aid"),
        )
    }

    data class Decoded(
        val source: SongSource,
        val id: Long,
        val qqMid: String?,
        val qqMediaMid: String?,
        val kugouHash: String?,
        val kugouAlbumAudioId: String?,
        val kugouAlbumId: String?,
    )
}

/** Turns a placeholder song URI into a real, playable CDN URL. */
object MediaResolver {

    /**
     * Blocking on purpose: this runs on ExoPlayer's loading thread, never on the main thread.
     * Returns null when no URL could be obtained (the item then fails cleanly instead of hanging).
     */
    fun resolve(uri: Uri): Uri? {
        val decoded = SongUri.decode(uri) ?: return null
        val quality = BeansAudioQuality.fromRaw(SettingsStore.audioQuality.value)
        val url = runCatching { resolveBlocking(decoded, quality) }.getOrNull()
        return url?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    /**
     * Resolve the real stream URL for a song. Shared by offline playback (the data source) and by
     * the download manager, so both use exactly the same quality and fallback behaviour.
     *
     * Honours the 「播放来源」 setting:
     *  - `official`    只走官方接口
     *  - `third_party` 先试已启用的第三方音源，失败后再回落官方
     *  - `auto`        优先官方，失败后尝试已启用音源
     */
    suspend fun resolveUrl(song: Song, quality: BeansAudioQuality): String? {
        val official: suspend () -> String? = {
            runCatching { officialUrl(song, quality) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        val thirdParty: suspend () -> String? = {
            runCatching { UnblockService.resolve(song, quality) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        return when (PlaybackSource.fromKey(SettingsStore.playbackSource.value)) {
            PlaybackSource.OFFICIAL -> official()
            PlaybackSource.THIRD_PARTY -> thirdParty() ?: official()
            PlaybackSource.AUTO -> official() ?: thirdParty()
        }
    }

    private fun resolveBlocking(decoded: SongUri.Decoded, quality: BeansAudioQuality): String? =
        runBlocking {
            when (decoded.source) {
                SongSource.NET_EASE ->
                    NetEaseApi.songURLs(listOf(decoded.id), quality.level)[decoded.id]

                SongSource.QQ -> {
                    val mid = decoded.qqMid ?: return@runBlocking null
                    QQMusicApi.songURL(mid, decoded.qqMediaMid, quality)
                        ?: QQMusicApi.songURL(mid, decoded.qqMediaMid, BeansAudioQuality.STANDARD)
                }

                SongSource.KUGOU -> KugouPlayback.resolve(
                    hash = decoded.kugouHash,
                    albumAudioId = decoded.kugouAlbumAudioId,
                    albumId = decoded.kugouAlbumId,
                    quality = quality,
                )
            }
        }

    /** Official platform resolution only (no third-party sources). */
    private suspend fun officialUrl(song: Song, quality: BeansAudioQuality): String? =
        when (song.source) {
            SongSource.NET_EASE ->
                NetEaseApi.songURLs(listOf(song.id), quality.level)[song.id]

            SongSource.QQ -> {
                val mid = song.qqMid
                if (mid.isNullOrBlank()) null
                else QQMusicApi.songURL(mid, song.qqMediaMid, quality)
                    ?: QQMusicApi.songURL(mid, song.qqMediaMid, BeansAudioQuality.STANDARD)
            }

            SongSource.KUGOU -> KugouPlayback.resolve(
                hash = song.kugouHash,
                albumAudioId = song.kugouAlbumAudioId,
                albumId = song.kugouAlbumId,
                quality = quality,
            )
        }
}

/**
 * Wraps the HTTP data source so ExoPlayer resolves `beans://song` URIs on demand.
 * A downloaded copy always wins over streaming, so offline playback is automatic.
 * Any other URI (mp3/flac/local file) passes straight through untouched.
 */
class BeansDataSourceFactory(
    private val upstream: DataSource.Factory,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val base = upstream.createDataSource()
        return ResolvingDataSource(base) { dataSpec: DataSpec ->
            if (SongUri.isSongUri(dataSpec.uri)) {
                val key = dataSpec.uri.getQueryParameter("k")
                val local = key?.let { DownloadManager.localFileFor(it) }
                if (local != null) {
                    dataSpec.withUri(Uri.fromFile(local))
                } else {
                    val resolved = MediaResolver.resolve(dataSpec.uri)
                    if (resolved != null) dataSpec.withUri(resolved) else dataSpec
                }
            } else {
                dataSpec
            }
        }
    }
}

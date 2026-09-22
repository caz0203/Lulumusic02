package com.lulu.music.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Foreground media service hosting the single ExoPlayer instance and the MediaSession.
 *
 * The MediaSession is what gives us the lock-screen / notification controls, Bluetooth controls and
 * audio-focus handling. Playback URLs for `beans://song` items are resolved lazily through
 * [BeansDataSourceFactory].
 */
class BeansPlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()

        // 刻意不在这里 setUserAgent：DefaultHttpDataSource 会在逐请求头之后再写一次工厂 UA，
        // 那样 [StreamHeaderDataSourceFactory] 为 QQ / 酷狗设置的平台 UA 会被覆盖。
        // UA 现在统一由 MediaResolver.streamRequestHeaders 逐请求给出。
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

        // 平台相关的请求头（QQ / 酷狗的 Referer + Cookie）在 [StreamHeaderDataSourceFactory] 里
        // 按最终地址逐请求设置；外面再套一层 DefaultDataSource，让 file://（已下载文件）、
        // content://（本地导入）等非 HTTP 地址也能正常播放。
        val streamFactory = DefaultDataSource.Factory(this, StreamHeaderDataSourceFactory(httpFactory))
        val mediaSourceFactory = DefaultMediaSourceFactory(BeansDataSourceFactory(streamFactory))

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        player = exo
        EqualizerController.attach(exo.audioSessionId)

        mediaSession = MediaSession.Builder(this, exo).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Keep playing when the task is swiped away, but stop the service when nothing is playing so we
     * do not hold a foreground slot forever.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        EqualizerController.release()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        /** 默认拉流 UA（网易云等平台用浏览器 UA 即可）。逐请求写进请求头，见 MediaResolver。 */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}

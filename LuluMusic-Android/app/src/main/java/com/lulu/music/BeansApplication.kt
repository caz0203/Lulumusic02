package com.lulu.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.store.CrashLog
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.data.store.PlayHistoryStore
import com.lulu.music.data.store.SearchHistoryStore
import com.lulu.music.data.source.LxScriptRunner
import com.lulu.music.data.source.UnblockService
import com.lulu.music.data.source.UnblockSourceStore
import com.lulu.music.data.stats.UserStatsTracker
import com.lulu.music.playback.DownloadManager
import com.lulu.music.playback.PlaybackController
import com.lulu.music.ui.components.BeansHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class BeansApplication : Application(), ImageLoaderFactory {

    /** Application-lifetime scope for fire-and-forget work (no UI blocking). */
    val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Crash log FIRST: anything that fails later in this method (or at any point afterwards)
        // is then recorded to filesDir/crash.log instead of vanishing with the process.
        CrashLog.install(this)
        // SettingsStore MUST be initialised first: every other store's Compose state depends on it.
        SettingsStore.init(this)
        BeansHaptics.init(this)
        FavoritesStore.load()
        SearchHistoryStore.load()
        PlayHistoryStore.load()
        DownloadManager.init(this)
        // 第三方音源：读取已导入的音源，并准备 LX 脚本的运行环境。
        UnblockSourceStore.load()
        UnblockService.load()
        LxScriptRunner.init(this)
        // Connect to the media session up-front so playback state survives Activity recreation.
        PlaybackController.init(this)
        // 收听时长 / 播放次数统计 + Gitee 云同步（未配置 Gitee 时完全离线，不发请求）。
        UserStatsTracker.start(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: BeansApplication
            private set
    }
}

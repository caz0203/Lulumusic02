package com.lulu.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lulu.music.data.donors.DonorsStore
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.store.CoverStore
import com.lulu.music.data.store.CrashLog
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.data.store.LocalPlaylistStore
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
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * `open` 是给测试用的：`app/src/test/` 里的 `TestBeansApplication` 需要继承它，才能让
 * Robolectric 走**真实**的 `onCreate()`（只是先关掉两道测试闸门）。
 * 让类可继承不改变任何运行时行为，也没有任何 `app/src/main/` 代码去继承它。
 */
open class BeansApplication : Application(), ImageLoaderFactory {

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
        // 本地歌单（真的落盘，不是页面内存）与每首歌的自定义封面索引。
        LocalPlaylistStore.load()
        CoverStore.init(this)
        DownloadManager.init(this)
        // 第三方音源：读取已导入的音源，并准备 LX 脚本的运行环境。
        UnblockSourceStore.load()
        UnblockService.load()
        LxScriptRunner.init(this)
        // Connect to the media session up-front so playback state survives Activity recreation.
        PlaybackController.init(this)
        // 赞助名单（donors.json）：先读本地缓存（纯本地、不阻塞），再在后台拉一次远程清单。
        // 拉取失败只会保留缓存，不弹任何错误 —— 见 data/donors/DonorsStore.kt。
        DonorsStore.load()
        appScope.launch { DonorsStore.refresh() }
        // 收听时长 / 播放次数统计（纯本地：只写本机 SharedPreferences，不联网）。
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

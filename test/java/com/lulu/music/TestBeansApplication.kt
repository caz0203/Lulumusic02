package com.lulu.music

import com.lulu.music.data.net.Http
import com.lulu.music.playback.PlaybackController

/**
 * 测试专用的 Application：在**真实的** [BeansApplication.onCreate] 跑起来之前，先把两个
 * 「测试专用闸门」关掉。Robolectric 用 `@Config(application = TestBeansApplication::class)`
 * 构造它，所以 `Application.onCreate()` 里的 `PlaybackController.init(this)` /
 * 各个 store 的初始化走的是与生产完全相同的代码路径，只是不去连播放服务、不去联网。
 *
 * ## 为什么必须在 `super.onCreate()` 之前
 *
 * 1. [PlaybackController] 是进程级 `object`，`BeansApplication.onCreate` 会调用
 *    `PlaybackController.init(this)`。Robolectric 里没有真正的 `MediaSessionService`：
 *    `MediaController.Builder(...).buildAsync()` 会让假 `bindService` 投递一个连接回调，
 *    media3 随即抛 `NullPointerException`（`SessionServiceConnection.onServiceConnected`
 *    里拿到的 `ComponentName` 是 null）。那个 NPE 抛在 Espresso 投递的 runnable 里，
 *    任何 Compose 测试配置都拦不住 —— 唯一的修法是**别让它去连**。必须在 init 之前置位。
 * 2. [Http.markOffline] 同理：必须在任何屏幕的 `LaunchedEffect` 有机会发请求之前生效。
 *
 * ## 这里**只**关测试闸门，不改任何生产默认值
 *
 * 两个闸门的默认值都是「生产行为」（连接开启、网络开启），`app/src/main/` 里没有任何
 * 代码会给它们赋反向值；本文件是测试源码，不会进 APK。
 */
class TestBeansApplication : BeansApplication() {

    override fun onCreate() {
        // 顺序很重要：先断网、先禁连接，再让真实的 onCreate 跑。
        Http.markOffline()
        PlaybackController.connectEnabled = false
        super.onCreate()
    }
}

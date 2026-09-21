package com.lulu.music.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 「点歌后自动展开全屏播放器」的一次性请求通道。
 *
 * 为什么需要它：点歌（`PlaybackController.play(...)`）只负责开始播放，播放页仍要用户自己点底部迷你条
 * 才能打开；网易云的交互是「点了歌就进播放页」，本类就是补这一跳。
 *
 * 为什么不用 `currentSong` 状态监听：自动切歌（上一首 / 下一首 / 自动续播 / 定时恢复 / 启动恢复会话）
 * 同样会改变 `currentSong`，如果监听状态就会在用户正在浏览歌单时把播放页推上来。
 * 因此只在**用户点按**的 handler 里显式调用 [request]，由 [BeansApp] 统一导航。
 *
 * `extraBufferCapacity = 1` 且无 replay：这是一次性事件，没有订阅者时丢弃即可
 * （UI 点按必然发生在首帧之后，此时收集方已经在跑）。
 */
object PlayerOpenRequest {

    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 收集方见 `com.lulu.music.ui.BeansApp`。 */
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    /** 由 UI 的点击回调调用：请求打开全屏播放器（幂等，播放页已在前台时不会有额外效果）。 */
    fun request() {
        _requests.tryEmit(Unit)
    }
}

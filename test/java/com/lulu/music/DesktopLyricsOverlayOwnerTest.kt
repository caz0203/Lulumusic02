package com.lulu.music

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.lulu.music.playback.BeansOverlayLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 悬浮窗宿主 [BeansOverlayLifecycleOwner] 的**创建顺序**（真机崩溃的根因）。
 *
 * ## 真机上发生了什么
 *
 * ```
 * java.lang.IllegalStateException: Restarter must be created only during owner's initialization stage
 *     at androidx.savedstate.SavedStateRegistryController.performAttach(SavedStateRegistryController.kt:45)
 *     at com.lulu.music.playback.BeansOverlayLifecycleOwner.create(DesktopLyricsOverlay.kt:666)
 *     at com.lulu.music.playback.BeansDesktopLyricsWindow.show(DesktopLyricsOverlay.kt:418)
 * ```
 *
 * `create()` 原来是「先把 `LifecycleRegistry` 推到 `CREATED`，再
 * `performAttach()`」—— 而 `performAttach()` 有一句硬 `check`：宿主必须**仍然是
 * `INITIALIZED`**。于是每一次挂悬浮窗都会在 `show()` 里抛异常，被 `applyWindow` 的
 * `runCatching` 兜住，用户只看到一句「桌面歌词无法显示」—— 权限明明是好的。
 *
 * ## 这个文件测什么
 *
 * 1. **正面**：用真的 `LifecycleRegistry` / `SavedStateRegistryController`（Robolectric 提供
 *    `android.os.Bundle` 的真实实现，所以不用假装）把 `create()` / `resume()` / `destroy()`
 *    走一遍，断言**不抛异常**、状态正确、SavedStateRegistry 已经 restore 过；
 * 2. **反面**：故意按错误的顺序（先 CREATED 再 attach）建一个最小宿主，断言它抛的就是
 *    真机上那句话 —— 万一有人把顺序改回去，这条会立刻变红。
 *
 * ## 测不到的（**没有模拟器 / 真机，不做任何假装**）
 *
 * `WindowManager.addView` 能不能成功、真实触摸（单击 / 长按 / 拖动）送达悬浮条的行为 —— 这些在
 * JVM 里根本跑不起来（见 `DesktopLyricsTest` / `DesktopLyricsPermissionTest` 的说明）。
 * 这里只证明「宿主本身不再抛异常」，也就是那次闪退的最后一块拼图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class DesktopLyricsOverlayOwnerTest {

    @Test(timeout = 60_000L)
    fun theOverlayOwnerIsCreatedWithTheRealLifecycleAndSavedStateClasses() {
        val owner = BeansOverlayLifecycleOwner()

        // 真机上这一行抛过 IllegalStateException（Restarter must be created only during ...）。
        owner.create()

        assertEquals(
            "create() 之后 lifecycle 必须到 CREATED（ComposeView 的 Recomposer 至少要它）",
            Lifecycle.State.CREATED,
            owner.lifecycle.currentState,
        )
        assertTrue(
            "create() 必须把 SavedStateRegistry 也 restore 过（rememberSaveable 依赖它）",
            owner.savedStateRegistry.isRestored,
        )

        // 挂上窗口之后才会调用（Recomposer 需要 RESUMED 才跑重组循环）。
        owner.resume()
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)

        // 撤窗口时调用；重复调用也必须安全（dispose 是可重复的）。
        owner.destroy()
        owner.destroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }

    @Test(timeout = 60_000L)
    fun movingTheRegistryToCreatedBeforeAttachIsExactlyTheDeviceCrash() {
        val wrongOrder = WrongOrderOwner()
        // 错误的顺序：先离开 INITIALIZED，再 performAttach。
        wrongOrder.registry.currentState = Lifecycle.State.CREATED

        val thrown = assertThrows(IllegalStateException::class.java) {
            wrongOrder.controller.performAttach()
        }
        assertTrue(
            "必须抛真机上那一句：${thrown.message}",
            thrown.message.orEmpty().contains("initialization stage"),
        )
    }

    /**
     * 最小宿主，专门用来复现「顺序错了」那一种写法。
     *
     * 与 [BeansOverlayLifecycleOwner] 的关键差别只在两行的先后：这里先推 lifecycle，再 attach。
     */
    private class WrongOrderOwner : LifecycleOwner, SavedStateRegistryOwner {

        val registry = LifecycleRegistry(this)
        val controller = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry

        override val savedStateRegistry get() = controller.savedStateRegistry
    }
}

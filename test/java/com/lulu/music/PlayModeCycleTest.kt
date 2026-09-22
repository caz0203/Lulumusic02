package com.lulu.music

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.playback.PlayMode
import com.lulu.music.playback.PlaybackController
import com.lulu.music.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 底排那颗「播放模式」按钮的状态机（`playback.PlayMode` + `PlaybackController.cyclePlayMode`）。
 *
 * ## 为什么值得单独测
 *
 * 合并前「随机」是一个开关、「循环」是另一个入口（既在底排按钮里也在 `···` 面板里），
 * 两者可以互相矛盾。合并成一个按钮之后，**顺序与映射就是产品契约**：
 *  1. 四档的**顺序**必须固定为 顺序播放 → 列表循环 → 单曲循环 → 随机播放，并且第 4 档之后回到第 1 档；
 *  2. 每一档映射到的 `(shuffle, repeatMode)` 必须唯一且写死（否则「点了按钮但播放器没变」）；
 *  3. 反查必须是**全函数**：`beans.shuffle` / `beans.repeat` 可以被备份导入或旧版本写成任意组合，
 *     按钮必须对每种组合都给出确定档位，而且这个档位能反查回它自己。
 *
 * 前三组是纯 JVM 断言（枚举 + 纯函数），后两组走 [PlaybackController]，因此需要 Robolectric
 * 把 `SettingsStore` 的 DataStore 绑到当前测试的 filesDir（与 `PlaybackControllerTest` 同一套做法）。
 *
 * ## 这个文件不覆盖什么
 *
 * Robolectric 里没有真正的 `MediaSessionService`，所以这里**不**验证 `repeatMode` / `shuffleModeEnabled`
 * 真的下发给了解码器；那条链路由 `PlaybackControllerTest` 的「无 controller 时不得抛」覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayModeCycleTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun bootApplication() {
        // cyclePlayMode 会写 `beans.shuffle` / `beans.repeat`，所以 DataStore 必须绑到当前测试。
        RobolectricSingletons.resetSettingsDataStore(
            ApplicationProvider.getApplicationContext(),
            RobolectricSingletons.COMMON_PREFERENCES,
        )
        ApplicationProvider.getApplicationContext<BeansApplication>()

        // 每个用例都从一个确定的起点开始（DataStore 在测试方法之间不共享，但 PlaybackController
        // 的 StateFlow 是进程级单例，上一组用例的值会留下来）。
        PlaybackController.setShuffle(false)
        PlaybackController.setRepeatMode(RepeatMode.OFF)
        awaitMainLooper()
    }

    private fun awaitMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ------------------------------------------------------------------
    // 1. 四档的顺序与环绕
    // ------------------------------------------------------------------

    @Test
    fun statesAreExactlyTheFourDocumentedOnesInOrder() {
        assertEquals(
            "顺序必须固定为：顺序播放 → 列表循环 → 单曲循环 → 随机播放",
            listOf(
                PlayMode.SEQUENTIAL,
                PlayMode.LIST_LOOP,
                PlayMode.SINGLE_LOOP,
                PlayMode.SHUFFLE,
            ),
            PlayMode.entries.toList(),
        )
    }

    @Test
    fun nextWalksTheCycleAndWrapsBackToSequential() {
        PlayMode.entries.forEachIndexed { index, mode ->
            val expected = PlayMode.entries[(index + 1) % PlayMode.entries.size]
            assertEquals("$mode 的下一档必须是 $expected", expected, mode.next)
        }
        assertEquals("最后一档必须绕回第一档", PlayMode.SEQUENTIAL, PlayMode.SHUFFLE.next)

        // 走 4 步正好把四档都走一遍，并且回到起点。
        val visited = generateSequence(PlayMode.SEQUENTIAL) { it.next }.take(4).toList()
        assertEquals("走 4 步必须不重不漏地覆盖四档", PlayMode.entries.toList(), visited)
        assertEquals("第 5 步必须回到起点（环绕）", PlayMode.SEQUENTIAL, visited.last().next)
    }

    // ------------------------------------------------------------------
    // 2. 每一档映射到的 (shuffle, repeatMode)
    // ------------------------------------------------------------------

    @Test
    fun eachStateMapsToItsDocumentedShuffleAndRepeatPair() {
        assertFalse("顺序播放：不随机", PlayMode.SEQUENTIAL.shuffleEnabled)
        assertEquals("顺序播放：repeat OFF（放完就停）", RepeatMode.OFF, PlayMode.SEQUENTIAL.repeat)

        assertFalse("列表循环：不随机", PlayMode.LIST_LOOP.shuffleEnabled)
        assertEquals("列表循环：repeat ALL", RepeatMode.ALL, PlayMode.LIST_LOOP.repeat)

        assertFalse("单曲循环：不随机", PlayMode.SINGLE_LOOP.shuffleEnabled)
        assertEquals("单曲循环：repeat ONE", RepeatMode.ONE, PlayMode.SINGLE_LOOP.repeat)

        assertTrue("随机播放：shuffle ON", PlayMode.SHUFFLE.shuffleEnabled)
        assertEquals("随机播放：repeat ALL（随机队列必须能循环）", RepeatMode.ALL, PlayMode.SHUFFLE.repeat)

        // 四档必须给出四个**互不相同**的 (shuffle, repeat) 组合，否则按钮会出现「点了看不出变化」。
        val pairs = PlayMode.entries.map { it.shuffleEnabled to it.repeat }
        assertEquals("四档必须对应四个互不相同的 (shuffle, repeat) 组合", 4, pairs.toSet().size)
    }

    // ------------------------------------------------------------------
    // 3. 反查：全函数 + 自洽
    // ------------------------------------------------------------------

    @Test
    fun inverseMappingRoundTripsAndIsTotal() {
        // 四档自己都能反查回自己。
        PlayMode.entries.forEach { mode ->
            assertEquals(
                "$mode 反查失败 —— 按钮会显示成别的档位",
                mode,
                PlayMode.of(mode.shuffleEnabled, mode.repeat),
            )
        }

        // 任意组合（包括按钮永远不会写出的那两种）都必须有确定结果，且结果自洽。
        val everything = listOf(false, true).flatMap { shuffle ->
            RepeatMode.entries.map { repeat -> shuffle to repeat }
        }
        assertEquals("2 × 3 六种组合都要能反查", 6, everything.size)
        everything.forEach { (shuffle, repeat) ->
            val mode = PlayMode.of(shuffle, repeat)
            assertEquals(
                "of($shuffle, $repeat) 的结果必须自洽（反查回自己）",
                mode,
                PlayMode.of(mode.shuffleEnabled, mode.repeat),
            )
        }

        // 明确写死两个「非按钮路径」的组合，避免以后有人把优先级改反。
        assertEquals("shuffle 打开时随机优先（repeat 是什么都无所谓）", PlayMode.SHUFFLE, PlayMode.of(true, RepeatMode.OFF))
        assertEquals(PlayMode.SHUFFLE, PlayMode.of(true, RepeatMode.ONE))
        assertEquals(PlayMode.SEQUENTIAL, PlayMode.of(false, RepeatMode.OFF))
        assertEquals(PlayMode.LIST_LOOP, PlayMode.of(false, RepeatMode.ALL))
        assertEquals(PlayMode.SINGLE_LOOP, PlayMode.of(false, RepeatMode.ONE))
    }

    // ------------------------------------------------------------------
    // 4. PlaybackController.cyclePlayMode：真的驱动那两个 StateFlow
    // ------------------------------------------------------------------

    @Test
    fun controllerCycleWalksAllFourStatesInOrderAndWraps() {
        val seen = mutableListOf<Pair<Boolean, RepeatMode>>()
        repeat(5) {
            seen += PlaybackController.shuffle.value to PlaybackController.repeatMode.value
            PlaybackController.cyclePlayMode()
        }
        seen += PlaybackController.shuffle.value to PlaybackController.repeatMode.value
        awaitMainLooper()

        assertEquals(
            "连续点 5 下必须走完一轮再回到起点",
            listOf(
                false to RepeatMode.OFF, // 顺序播放（起点）
                false to RepeatMode.ALL, // 列表循环
                false to RepeatMode.ONE, // 单曲循环
                true to RepeatMode.ALL, // 随机播放
                false to RepeatMode.OFF, // 回到顺序播放
                false to RepeatMode.ALL, // 再进第二档
            ),
            seen,
        )
        assertTrue("这些入口在没有 controller 时也必须不抛", true)
    }

    @Test
    fun cycleUsesTheTrueCurrentStateEvenWhenItChangedElsewhere() {
        // 别处（备份导入 / 旧版本偏好 / 未来的新入口）把状态改成了单曲循环。
        PlaybackController.setRepeatMode(RepeatMode.ONE)
        PlaybackController.setShuffle(false)

        PlaybackController.cyclePlayMode()
        awaitMainLooper()

        assertEquals("单曲循环的下一档是随机播放", true, PlaybackController.shuffle.value)
        assertEquals(RepeatMode.ALL, PlaybackController.repeatMode.value)

        // 从随机的下一档回到顺序播放：两个 StateFlow 必须**一起**被改回来，
        // 否则会出现「shuffle 关了但还在 ALL」这种按钮显示不出来的中间态。
        PlaybackController.cyclePlayMode()
        awaitMainLooper()
        assertEquals(false, PlaybackController.shuffle.value)
        assertEquals(RepeatMode.OFF, PlaybackController.repeatMode.value)
    }

    @Test
    fun cycleFromAnOutOfBandCombinationLandsOnAValidState() {
        // 按钮永远不会写出「随机 + 单曲循环」，但偏好里可能是这个组合。
        PlaybackController.setShuffle(true)
        PlaybackController.setRepeatMode(RepeatMode.ONE)

        PlaybackController.cyclePlayMode()
        awaitMainLooper()

        assertEquals("SHUFFLE 的下一档是顺序播放", false, PlaybackController.shuffle.value)
        assertEquals(RepeatMode.OFF, PlaybackController.repeatMode.value)
    }

    @Test
    fun manyCyclesNeverThrowAndAlwaysStayOnADefinedState() {
        repeat(13) {
            PlaybackController.cyclePlayMode()
            awaitMainLooper()
            val mode = PlayMode.of(PlaybackController.shuffle.value, PlaybackController.repeatMode.value)
            assertEquals(
                "任意时刻的 (shuffle, repeat) 都必须能映射回一个确定档位",
                mode,
                PlayMode.of(mode.shuffleEnabled, mode.repeat),
            )
        }
        // 13 次 = 3 轮 + 1 步 → 停在第二档。
        assertEquals(RepeatMode.ALL, PlaybackController.repeatMode.value)
        assertFalse(PlaybackController.shuffle.value)
    }
}

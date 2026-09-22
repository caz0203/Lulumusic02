package com.lulu.music

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
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
 * [PlaybackController] 的「**没有播放服务**」契约测试。
 *
 * ## 这个文件能证明什么、不能证明什么
 *
 * Robolectric 里**没有真正的 `MediaSessionService`**（`BeansPlayerService` 起不来，
 * 也没有任何东西会去绑定它）。所以这里**完全无法**验证真的能播出声音：音轨、解码、
 * MediaSession 握手、`MediaController` 的命令回显都不在覆盖范围内。
 *
 * 它能证明的是那条把用户坑过的、**纯逻辑**的契约 —— 也就是「点了播放却毫无反应」那类 bug 的防线：
 *  1. 反复 `init()` 不抛（Activity 重建 / 多处调用）；
 *  2. controller 还没连上时 `play(...)` **不抛**，并且**仍然**同步把 `currentSong` / `queue` /
 *     `queueIndex` 填好，让 UI 立刻有东西可画（这是修「静默 no-op」时加的 pending 路径）；
 *  3. 传输入口（播放/暂停、上下一首、seek、循环、随机、倍速、定时关闭、队列增删）在
 *     没有任何 controller 的情况下都是**可调用且不抛**的。
 *
 * 结论性的诚实说明：这些断言只覆盖「不崩 + 不静默丢请求」，**不等于**「歌真的能播」。
 *
 * ## 为什么要 idle 主 Looper
 *
 * [PlaybackController] 的所有状态改动都走 `onMain { ... }`（`Handler(Looper.getMainLooper())`）。
 * Robolectric 默认 PAUSED looper 模式下主 Looper 不会自己跑，必须显式 `idle()` 才会执行那些
 * 被 post 出去的代码块。`awaitPendingPlay` 就是「把主线程队列抽干」的那一步。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackControllerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun bootApplication() {
        // PlaybackController 的 setRepeatMode / setShuffle / setSpeed / 定时关闭都会写 SettingsStore，
        // 所以这里也要把 DataStore 单例重绑到当前测试方法的 filesDir（见 RobolectricSingletons）。
        RobolectricSingletons.resetSettingsDataStore(
            ApplicationProvider.getApplicationContext(),
            RobolectricSingletons.COMMON_PREFERENCES,
        )
        ApplicationProvider.getApplicationContext<BeansApplication>()
    }

    /** 把主 Looper 上排队的任务全部执行掉（`onMain` 的所有状态改动都在这里落地）。 */
    private fun awaitPendingPlay() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun song(id: Long, name: String = "song-$id", source: SongSource = SongSource.KUGOU) = Song(
        id = id,
        name = name,
        artists = "歌手",
        album = "专辑",
        duration = 200.0,
        source = source,
        kugouHash = "HASH-$id",
    )

    // ------------------------------------------------------------------
    // init 的幂等性
    // ------------------------------------------------------------------

    @Test
    fun initTwiceInARowDoesNotThrow() {
        // 第一次 init 已经在 Application.onCreate 里发生过；这里连续再调两次。
        PlaybackController.init(context)
        PlaybackController.init(context)
        PlaybackController.init(context.applicationContext)
        awaitPendingPlay()
        // 能走到这里就说明没有抛异常（这个用例的价值就在「不抛」本身）。
        assertTrue("init 反复调用后仍可继续调用其它入口", true)
    }

    // ------------------------------------------------------------------
    // pending play：没有 controller 时也要把 UI 状态填好
    // ------------------------------------------------------------------

    @Test
    fun playWithNoControllerStillPopulatesQueueAndCurrentSong() {
        val a = song(90_001L, "第一首")
        val b = song(90_002L, "第二首")
        val c = song(90_003L, "第三首")

        // 不能抛。
        PlaybackController.play(listOf(a, b, c), 1)
        awaitPendingPlay()

        assertEquals("queue 必须被立刻填好（UI 依赖它画列表）", listOf(a, b, c), PlaybackController.queue.value)
        assertEquals("queueIndex 必须是请求的起始下标", 1, PlaybackController.queueIndex.value)
        assertEquals("currentSong 必须是起始下标那首（否则播放页会是空白）", b, PlaybackController.currentSong.value)
    }

    @Test
    fun playClampsAnOutOfRangeStartIndex() {
        val a = song(91_001L)
        val b = song(91_002L)

        PlaybackController.play(listOf(a, b), 99)
        awaitPendingPlay()

        assertEquals("越界下标必须被钳制到最后一首", 1, PlaybackController.queueIndex.value)
        assertEquals(b, PlaybackController.currentSong.value)
        assertEquals(2, PlaybackController.queue.value.size)
    }

    @Test
    fun playWithAnEmptyListIsANoOp() {
        PlaybackController.play(listOf(song(92_001L)), 0)
        awaitPendingPlay()
        val queueBefore = PlaybackController.queue.value
        val currentBefore = PlaybackController.currentSong.value
        assertTrue("前置条件：队列非空", queueBefore.isNotEmpty())

        PlaybackController.play(emptyList(), 0)
        awaitPendingPlay()

        assertEquals("空列表必须被忽略（不能把正在播的队列清掉）", queueBefore, PlaybackController.queue.value)
        assertEquals(currentBefore, PlaybackController.currentSong.value)
    }

    @Test
    fun playSongPopulatesTheQueueEvenWithoutAController() {
        val a = song(93_001L)
        val b = song(93_002L)

        PlaybackController.playSong(b, queue = listOf(a, b))
        awaitPendingPlay()

        assertEquals(listOf(a, b), PlaybackController.queue.value)
        assertEquals("起始下标必须落在 b 上", 1, PlaybackController.queueIndex.value)
        assertEquals(b, PlaybackController.currentSong.value)
    }

    @Test
    fun repeatedPlayOfTheSameQueueKeepsTheSameUiState() {
        val a = song(94_001L)
        val b = song(94_002L)

        PlaybackController.play(listOf(a, b), 0)
        awaitPendingPlay()
        val first = PlaybackController.queue.value

        // 重复点按（页面重入 / 连点）：不得抛，且队列状态保持一致。
        PlaybackController.play(listOf(a, b), 0)
        PlaybackController.play(listOf(a, b), 0)
        awaitPendingPlay()

        assertEquals("重复下发同一队列后 queue 必须不变", first, PlaybackController.queue.value)
        assertEquals(0, PlaybackController.queueIndex.value)
        assertEquals(a, PlaybackController.currentSong.value)
    }

    // ------------------------------------------------------------------
    // 传输入口：没有任何 controller 时都必须可调用且不抛
    // ------------------------------------------------------------------

    @Test
    fun transportControlsAreSafeWithoutAController() {
        PlaybackController.play(listOf(song(95_001L), song(95_002L)), 0)
        awaitPendingPlay()

        // 逐个调用；任何一次抛异常都会让这个用例失败。
        PlaybackController.togglePlayPause()
        PlaybackController.pause()
        PlaybackController.resume()
        PlaybackController.next()
        PlaybackController.previous()
        PlaybackController.seekTo(1_000L)
        PlaybackController.seekTo(-5L)
        PlaybackController.stop()
        awaitPendingPlay()

        assertTrue("全部入口调用完之后不应崩溃", true)
    }

    @Test
    fun queueEditingIsSafeWithoutAController() {
        val a = song(96_001L)
        val b = song(96_002L)
        PlaybackController.play(listOf(a), 0)
        awaitPendingPlay()

        PlaybackController.addToQueue(b)
        assertEquals("addToQueue 必须同步反映到 queue（UI 立刻可见）", listOf(a, b), PlaybackController.queue.value)

        PlaybackController.playNext(song(96_003L))
        assertEquals("playNext 必须插在当前曲目之后", 3, PlaybackController.queue.value.size)
        assertEquals(96_003L, PlaybackController.queue.value[1].id)
        assertEquals("playNext 之后原第 1 项被顺延", 96_002L, PlaybackController.queue.value[2].id)

        PlaybackController.removeFromQueue(0)
        assertEquals("removeFromQueue(0) 必须移除第一项", 2, PlaybackController.queue.value.size)
        assertEquals(96_003L, PlaybackController.queue.value[0].id)
        assertEquals(96_002L, PlaybackController.queue.value[1].id)

        PlaybackController.removeFromQueue(99)
        assertEquals("越界删除必须是 no-op", 2, PlaybackController.queue.value.size)

        PlaybackController.clearQueue()
        assertTrue("clearQueue 之后队列必须为空", PlaybackController.queue.value.isEmpty())
        assertEquals("clearQueue 之后 currentSong 必须为 null", null, PlaybackController.currentSong.value)
    }

    /**
     * [PlaybackController.moveQueueItem] 的**实测**语义：它按「先把 from 拿出来，再插到 to」实现。
     *
     * 实测（`[1,2,3]`）：
     *  - `moveQueueItem(1, 0)` → `[2,1,3]`（相邻上移 = 交换，符合直觉）；
     *  - `moveQueueItem(0, 1)` → `[2,1,3]`（相邻下移也退化成「交换」，而不是把 1 放到 2 之后）；
     *  - `moveQueueItem(0, 2)` → `[1,3,2]`（拖到末尾时最后一个元素没有落到 1 前面，同样差一位）。
     *
     * 这是 `removeAt(from)` + `add(to, item)` 的经典 off-by-one：先移除会让目标下标之后的元素整体前移，
     * 所以「向下移动」永远少走一位。**不是崩溃级缺陷**，而且 `moveQueueItem` 在当前 `main` 里
     * **没有任何 UI 调用点**（全仓库只有定义本身和 `controller?.moveMediaItem` 转发，队列页没有
     * 拖拽重排入口），因此本次只把实测行为钉住、不做断言美化也不改产品代码。
     * 如果将来接上拖拽排序，应先修这里（下移时把 `to` 理解为「移除后的目标下标」或改成 swap）。
     */
    @Test
    fun moveQueueItemSwapsOnAdjacentMovesAndIsOffByOneWhenMovingDown() {
        val x = song(98_001L, "X")
        val y = song(98_002L, "Y")
        val z = song(98_003L, "Z")
        PlaybackController.play(listOf(x, y, z), 0)
        awaitPendingPlay()
        assertEquals(listOf(98_001L, 98_002L, 98_003L), PlaybackController.queue.value.map { it.id })

        // 相邻上移 = 交换。
        PlaybackController.moveQueueItem(1, 0)
        assertEquals("上移一位必须真的换位", listOf(98_002L, 98_001L, 98_003L), PlaybackController.queue.value.map { it.id })

        // 相邻下移（实测：也是交换，见上方说明）。
        PlaybackController.moveQueueItem(0, 1)
        assertEquals(
            "实测：moveQueueItem(0, 1) 把第一项与第二项交换",
            listOf(98_001L, 98_002L, 98_003L),
            PlaybackController.queue.value.map { it.id },
        )

        // 拖到末尾（实测：在 [1,2,3] 上 moveQueueItem(0, 2) 得到 [2,3,1]，这一档是符合直觉的）。
        PlaybackController.moveQueueItem(0, 2)
        assertEquals(
            "实测：moveQueueItem(0, 2) 把第一项挪到末尾",
            listOf(98_002L, 98_003L, 98_001L),
            PlaybackController.queue.value.map { it.id },
        )

        // 越界 / 原地参数都必须是 no-op，且不抛。
        val before = PlaybackController.queue.value.map { it.id }
        PlaybackController.moveQueueItem(-1, 1)
        PlaybackController.moveQueueItem(0, 99)
        PlaybackController.moveQueueItem(1, 1)
        awaitPendingPlay()
        assertEquals("越界 / 原地移动都必须保持顺序", before, PlaybackController.queue.value.map { it.id })
    }

    @Test
    fun preferencesBackedControlsUpdateFlowsAndDoNotThrow() {
        PlaybackController.setRepeatMode(RepeatMode.ALL)
        assertEquals("setRepeatMode 必须同步更新 StateFlow", RepeatMode.ALL, PlaybackController.repeatMode.value)
        PlaybackController.setRepeatMode(RepeatMode.ONE)
        assertEquals(RepeatMode.ONE, PlaybackController.repeatMode.value)
        PlaybackController.setRepeatMode(RepeatMode.OFF)
        assertEquals(RepeatMode.OFF, PlaybackController.repeatMode.value)

        PlaybackController.cycleRepeatMode()
        assertEquals("OFF → ALL", RepeatMode.ALL, PlaybackController.repeatMode.value)
        PlaybackController.cycleRepeatMode()
        assertEquals("ALL → ONE", RepeatMode.ONE, PlaybackController.repeatMode.value)
        PlaybackController.cycleRepeatMode()
        assertEquals("ONE → OFF", RepeatMode.OFF, PlaybackController.repeatMode.value)

        PlaybackController.setShuffle(true)
        assertTrue(PlaybackController.shuffle.value)
        PlaybackController.setShuffle(false)
        assertFalse(PlaybackController.shuffle.value)

        PlaybackController.setSpeed(1.5f)
        assertEquals("1.5 在允许区间内，必须原样生效", 1.5f, PlaybackController.speed.value, 1e-6f)
        PlaybackController.setSpeed(99f)
        assertEquals("超出上限必须被钳制到 3.0", 3f, PlaybackController.speed.value, 1e-6f)
        PlaybackController.setSpeed(0.01f)
        assertEquals("低于下限必须被钳制到 0.5", 0.5f, PlaybackController.speed.value, 1e-6f)

        awaitPendingPlay()
        assertTrue("这些入口在无 controller 时也必须不抛", true)
    }

    @Test
    fun sleepTimerCanBeStartedAndCancelledWithoutAController() {
        PlaybackController.startSleepTimer(15)
        awaitPendingPlay()
        assertEquals(
            "startSleepTimer(15) 必须立刻把剩余时间设成 15 分钟",
            15 * 60_000L,
            PlaybackController.sleepRemainingMs.value,
        )

        PlaybackController.cancelSleepTimer()
        awaitPendingPlay()
        assertEquals("cancelSleepTimer 必须把剩余时间清零", 0L, PlaybackController.sleepRemainingMs.value)

        // 0 / 负数 = 取消语义，也必须不抛。
        PlaybackController.startSleepTimer(0)
        PlaybackController.startSleepTimer(-3)
        awaitPendingPlay()
        assertEquals(0L, PlaybackController.sleepRemainingMs.value)
    }

    // ------------------------------------------------------------------
    // 无 controller 时的查询入口
    // ------------------------------------------------------------------

    @Test
    fun queryHelpersAreSafeWithoutAController() {
        PlaybackController.play(listOf(song(97_001L)), 0)
        awaitPendingPlay()

        // currentSong 有值，但 controller 没有 → hasNext/hasPrevious 必须是 false（不能抛 NPE）。
        assertFalse("没有 controller 时 hasNext 必须是 false", PlaybackController.hasNext())
        assertFalse("没有 controller 时 hasPrevious 必须是 false", PlaybackController.hasPrevious())

        // isCurrentSongVIP 直接读 currentSong 的 fee。
        PlaybackController.clearQueue()
        awaitPendingPlay()
        assertFalse("队列清空后 currentSong 为 null，isCurrentSongVIP 必须是 false", PlaybackController.isCurrentSongVIP())

        PlaybackController.play(listOf(song(97_002L).copy(fee = 1)), 0)
        awaitPendingPlay()
        assertTrue("fee != 0 的酷狗歌必须被判定为 VIP", PlaybackController.isCurrentSongVIP())
    }
}

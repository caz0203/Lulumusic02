package com.lulu.music

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.playback.PlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [PlaybackController.playNext] 的契约测试（「下一首播放」）。
 *
 * 与 `PlaybackControllerTest` 一样，这里**没有真正的 `MediaSessionService`**：Robolectric 里
 * `MediaController` 连不上，所以测的是「本地队列 + UI 状态」这条纯逻辑链路。能证明的三条：
 *  1. [PlaybackController.playNext] 对**不在**队列里的歌是**插入**：插到当前曲目之后；
 *  2. 对**已经**在队列里的歌是**移动**：不新增重复条目；
 *  3. **当前曲目 / 它的下标 / 播放位置 / 播放状态一律不变** —— 这正是「打开播放页不能把正在播的
 *     歌从头重播」那条既有修复的同一底线（`play()` 里的去重逻辑，见 PlaybackControllerTest）。
 *
 * 主线程队列要用 `shadowOf(Looper.getMainLooper()).idle()` 抽干（`onMain { … }` 的改动都在里面）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackControllerPlayNextTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun bootApplication() {
        RobolectricSingletons.resetSettingsDataStore(
            ApplicationProvider.getApplicationContext(),
            RobolectricSingletons.COMMON_PREFERENCES,
        )
        ApplicationProvider.getApplicationContext<BeansApplication>()
    }

    private fun awaitMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun song(id: Long, name: String = "song-$id") = Song(
        id = id,
        name = name,
        artists = "歌手",
        album = "专辑",
        duration = 200.0,
        source = SongSource.KUGOU,
        kugouHash = "HASH-$id",
    )

    private fun ids() = PlaybackController.queue.value.map { it.id }

    // ------------------------------------------------------------------
    // 插入
    // ------------------------------------------------------------------

    @Test
    fun playNextInsertsASongRightAfterTheCurrentOne() {
        val a = song(101_001L, "A")
        val b = song(101_002L, "B")
        val c = song(101_003L, "C")
        val added = song(101_004L, "D")

        PlaybackController.play(listOf(a, b, c), 1)
        awaitMain()

        PlaybackController.playNext(added)
        awaitMain()

        assertEquals("必须插在当前曲目之后", listOf(a, b, added, c), PlaybackController.queue.value)
        assertEquals("队列只多了 1 条", 4, PlaybackController.queue.value.size)
    }

    @Test
    fun playNextInsertsAfterTheCurrentItemWhenTheSongIsNotInTheQueueAndCurrentIsLast() {
        val a = song(102_001L)
        val b = song(102_002L)
        val added = song(102_003L)

        PlaybackController.play(listOf(a, b), 1)
        awaitMain()

        PlaybackController.playNext(added)
        awaitMain()

        assertEquals("当前是最后一首时插入到末尾", listOf(a, b, added), PlaybackController.queue.value)
    }

    @Test
    fun playNextAppendsWhenNothingIsPlaying() {
        PlaybackController.clearQueue()
        awaitMain()
        assertNull("前置条件：没有当前曲目", PlaybackController.currentSong.value)

        val added = song(103_001L)
        PlaybackController.playNext(added)
        awaitMain()

        assertEquals("没有正在播放的歌就追加到末尾", listOf(added), PlaybackController.queue.value)
        assertNull("playNext 不得凭空开始播放", PlaybackController.currentSong.value)
    }

    // ------------------------------------------------------------------
    // 移动（不产生重复条目）
    // ------------------------------------------------------------------

    @Test
    fun playNextMovesASongThatIsAlreadyInTheQueueInsteadOfDuplicatingIt() {
        val a = song(110_001L, "A")
        val b = song(110_002L, "B")
        val c = song(110_003L, "C")
        val d = song(110_004L, "D")

        PlaybackController.play(listOf(a, b, c, d), 0)
        awaitMain()

        PlaybackController.playNext(d)
        awaitMain()

        assertEquals("必须是移动而不是新增", 4, PlaybackController.queue.value.size)
        assertEquals("D 必须落到当前曲目之后", listOf(a, d, b, c), PlaybackController.queue.value)
    }

    @Test
    fun playNextMovesASongFromBehindTheCurrentItemToTheEndWhenCurrentIsLast() {
        val a = song(111_001L, "A")
        val b = song(111_002L, "B")
        val c = song(111_003L, "C")

        PlaybackController.play(listOf(a, b, c), 2)
        awaitMain()
        assertEquals("前置条件：当前曲目是 C", c.identityKey, PlaybackController.currentSong.value?.identityKey)

        PlaybackController.playNext(a)
        awaitMain()

        assertEquals("A 必须被移到队列末尾，且不新增条目", listOf(b, c, a), PlaybackController.queue.value)
        assertEquals("当前曲目仍然是 C", c.identityKey, PlaybackController.currentSong.value?.identityKey)
        assertEquals("当前曲目在队列里的新下标必须是 1", 1, PlaybackController.queueIndex.value)
    }

    @Test
    fun playNextOfTheCurrentSongItselfIsANoOp() {
        val a = song(112_001L, "A")
        val b = song(112_002L, "B")

        PlaybackController.play(listOf(a, b), 0)
        awaitMain()
        val before = ids()

        PlaybackController.playNext(a)
        awaitMain()

        assertEquals("点当前正在播放的那首必须什么都不做（既不重排也不重启）", before, ids())
        assertEquals(a.identityKey, PlaybackController.currentSong.value?.identityKey)
        assertEquals(0, PlaybackController.queueIndex.value)
    }

    @Test
    fun playNextOfASongAlreadyRightAfterTheCurrentOneIsANoOp() {
        val a = song(113_001L, "A")
        val b = song(113_002L, "B")
        val c = song(113_003L, "C")

        PlaybackController.play(listOf(a, b, c), 0)
        awaitMain()
        val before = ids()

        PlaybackController.playNext(b)
        awaitMain()

        assertEquals("已经在目标位置就不是「移动」，而是 no-op", before, ids())
        assertEquals(0, PlaybackController.queueIndex.value)
    }

    // ------------------------------------------------------------------
    // 绝不动当前曲目 / 进度
    // ------------------------------------------------------------------

    @Test
    fun playNextNeverChangesTheCurrentItemItsIndexOrThePlaybackPosition() {
        val a = song(120_001L, "A")
        val b = song(120_002L, "B")
        val c = song(120_003L, "C")
        val d = song(120_004L, "D")

        PlaybackController.play(listOf(a, b, c), 1)
        awaitMain()
        PlaybackController.seekTo(42_000L)
        awaitMain()

        val currentBefore = PlaybackController.currentSong.value
        val indexBefore = PlaybackController.queueIndex.value
        val positionBefore = PlaybackController.positionMs.value
        val playingBefore = PlaybackController.isPlaying.value
        assertEquals("前置条件：当前曲目是 B", b, currentBefore)
        assertEquals(1, indexBefore)

        PlaybackController.playNext(d)
        awaitMain()

        assertEquals("当前曲目不得被换掉", currentBefore, PlaybackController.currentSong.value)
        assertEquals("当前曲目的下标不得被改掉（插在它后面）", indexBefore, PlaybackController.queueIndex.value)
        assertEquals("播放位置不得被重置", positionBefore, PlaybackController.positionMs.value)
        assertEquals("播放 / 暂停状态不得被改动", playingBefore, PlaybackController.isPlaying.value)
    }

    @Test
    fun playNextIsSafeWithoutAControllerAndForEveryQueueShape() {
        // 空队列 / 单曲队列 / 越界过的下标…… 全都不该抛。
        PlaybackController.clearQueue()
        awaitMain()
        PlaybackController.playNext(song(130_001L))
        PlaybackController.playNext(song(130_001L))
        PlaybackController.playNext(song(130_002L))
        awaitMain()
        assertEquals("重复 playNext 同一首歌不得产生重复条目", 2, PlaybackController.queue.value.size)

        PlaybackController.play(listOf(song(130_003L)), 0)
        awaitMain()
        PlaybackController.playNext(song(130_004L))
        PlaybackController.playNext(song(130_003L))
        awaitMain()
        assertEquals("对当前曲目调用是 no-op，队列不应增长", 2, PlaybackController.queue.value.size)
        assertEquals(130_003L, PlaybackController.currentSong.value?.id)
    }
}

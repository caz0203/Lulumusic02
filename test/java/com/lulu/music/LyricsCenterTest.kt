package com.lulu.music

import com.lulu.music.data.model.LyricLine
import com.lulu.music.data.model.LyricWord
import com.lulu.music.playback.LyricsFetchGate
import com.lulu.music.playback.lyricsLineIndex
import com.lulu.music.playback.lyricsTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词中心（`playback/LyricsCenter.kt`）里**可测的那一半**：拉取时序 + 「现在唱到哪一行」。
 *
 * 纯 JVM 测试（不需要 Robolectric）：被测的全部是纯函数 / 纯状态机，
 * 悬浮窗、权限、窗口 flag 那些真正需要设备的部分在 [DesktopLyricsTest] 与
 * [DesktopLyricsPermissionTest] 里说明「到哪一步为止能测」。
 *
 * 为什么这一组必须存在：歌词中心的两个 bug 模式（**重复拉取**、**换歌后旧结果覆盖新歌词**）
 * 在真机上表现为「偶尔闪一下上一首的歌词」「同一首歌请求两次」，靠手点几乎复现不出来，
 * 只能靠这里把时序钉死。
 */
class LyricsCenterTest {

    private fun line(time: Double, text: String): LyricLine = LyricLine(time = time, text = text)

    private fun wordedLine(time: Double, text: String, words: List<LyricWord>): LyricLine =
        LyricLine(time = time, text = text, words = words)

    private fun assertProgress(expected: Float, actual: Float, delta: Float = 1e-4f) {
        assertEquals(expected, actual, delta)
    }

    // ------------------------------------------------------------------
    // 拉取时序（LyricsFetchGate）
    // ------------------------------------------------------------------

    @Test
    fun songChangeRequestsAFetchExactlyOnce() {
        val gate = LyricsFetchGate()
        val request = gate.onSongChanged("netease-1")
        assertNotNull("第一次确定曲目必须发起拉取", request)
        requireNotNull(request)
        assertEquals("netease-1", request.songKey)
        assertEquals("第一代请求", 1L, request.generation)
        assertTrue("在飞期间必须标记为在拉", gate.isFetching)

        // PlaybackController 会为暂停 / 拖动 / 位置更新反复回显同一首歌。
        assertNull("同一首歌重复回显不能重复拉", gate.onSongChanged("netease-1"))
        assertTrue("重复回显不能把 in-flight 清掉", gate.isFetching)
    }

    @Test
    fun songChangeMidFetchInvalidatesTheOlderResult() {
        val gate = LyricsFetchGate()
        val first = gate.onSongChanged("netease-1")
        requireNotNull(first)
        val second = gate.onSongChanged("netease-2")
        requireNotNull(second)

        assertTrue("最新一次请求有效", gate.shouldAccept(second))
        assertFalse("换歌中途回来的旧结果必须被丢掉", gate.shouldAccept(first))
        assertTrue("generation 必须真的前进", second.generation > first.generation)
        assertFalse(
            "就算 key 又轮回到同一首，旧的 generation 依然无效",
            gate.shouldAccept(first.copy(songKey = second.songKey)),
        )
    }

    @Test
    fun goingBackToTheSameSongFetchesAgain() {
        val gate = LyricsFetchGate()
        val first = gate.onSongChanged("A")
        requireNotNull(first)
        gate.onSongChanged("B")
        val back = gate.onSongChanged("A")
        requireNotNull(back)

        assertNotEquals("A→B→A 是一次新的拉取（界面已经清空，不能拿被丢弃的旧结果）", first.generation, back.generation)
        assertFalse(gate.shouldAccept(first))
        assertTrue(gate.shouldAccept(back))
    }

    @Test
    fun noCurrentSongClearsEverything() {
        val gate = LyricsFetchGate()
        val request = gate.onSongChanged("A")
        requireNotNull(request)

        assertNull("清空播放状态时没有可拉的东西", gate.onSongChanged(null))
        assertNull(gate.currentSongKey)
        assertFalse("in-flight 必须被清掉，否则下一次播放会被误判成「已在拉」", gate.isFetching)
        assertFalse(gate.shouldAccept(request))
    }

    @Test
    fun finishedRequestOnlyClearsItsOwnInFlightMark() {
        val gate = LyricsFetchGate()
        val a = gate.onSongChanged("A")
        requireNotNull(a)
        val b = gate.onSongChanged("B")
        requireNotNull(b)

        gate.onFinished(a)
        assertTrue("迟到的 A 不能把 B 的 in-flight 标记清掉", gate.isFetching)

        gate.onFinished(b)
        assertFalse(gate.isFetching)
    }

    @Test
    fun forceReloadIssuesANewRequestForTheCurrentSong() {
        val gate = LyricsFetchGate()
        val first = gate.onSongChanged("A")
        requireNotNull(first)

        val retried = gate.forceReload()
        requireNotNull(retried)
        assertEquals("A", retried.songKey)
        assertTrue(retried.generation > first.generation)
        assertTrue(gate.shouldAccept(retried))
        assertFalse(gate.shouldAccept(first))

        gate.onSongChanged(null)
        assertNull("没有正在播放的歌时重试什么都不做", gate.forceReload())
    }

    // ------------------------------------------------------------------
    // 「现在唱到哪一行」
    // ------------------------------------------------------------------

    @Test
    fun lineIndexFindsTheLastLineThatAlreadyStarted() {
        val lines = listOf(line(1.0, "a"), line(3.0, "b"), line(5.5, "c"))

        assertEquals("还没唱到第一行", -1, lyricsLineIndex(lines, 0.99))
        assertEquals(0, lyricsLineIndex(lines, 1.0))
        assertEquals(0, lyricsLineIndex(lines, 2.999))
        assertEquals(1, lyricsLineIndex(lines, 3.0))
        assertEquals("唱完之后停在最后一行", 2, lyricsLineIndex(lines, 999.0))
        assertEquals("没有歌词就没有当前行", -1, lyricsLineIndex(emptyList(), 5.0))
    }

    @Test
    fun timelineExposesCurrentNextAndLineSweep() {
        val lines = listOf(line(0.0, "第一行"), line(4.0, "第二行"), line(8.0, "第三行"))

        val start = lyricsTimeline(lines, positionSeconds = 2.0, offsetSeconds = 0.0)
        assertEquals(0, start.index)
        assertEquals("第一行", start.current?.text)
        assertEquals("第二行", start.next?.text)
        assertProgress(0.5f, start.sweep) // 整行插值：0s → 4s，走到 2s 正好一半
        assertTrue(start.hasLyrics)
        assertFalse("整行 LRC 没有逐字时间轴", start.hasWordTiming)

        val middle = lyricsTimeline(lines, positionSeconds = 5.0, offsetSeconds = 0.0)
        assertEquals(1, middle.index)
        assertEquals("第三行", middle.next?.text)

        val last = lyricsTimeline(lines, positionSeconds = 9.0, offsetSeconds = 0.0)
        assertEquals(2, last.index)
        assertNull("最后一行没有下一行", last.next)
        assertProgress(1.0f, last.sweep)
    }

    @Test
    fun timelineBeforeTheFirstLineStillOffersTheUpcomingLine() {
        val lines = listOf(line(1.0, "前奏"), line(3.0, "a"))
        val intro = lyricsTimeline(lines, positionSeconds = 0.2, offsetSeconds = 0.0)

        assertEquals(-1, intro.index)
        assertNull(intro.current)
        assertEquals("还没到第一行时，下一行就是第一行", "前奏", intro.next?.text)
        assertProgress(0f, intro.sweep)
    }

    @Test
    fun timelineAppliesTheUserOffsetBeforePickingTheLine() {
        val lines = listOf(line(1.0, "a"), line(5.0, "b"))

        assertEquals("没有偏移时还在第 0 行", 0, lyricsTimeline(lines, 4.2, 0.0).index)
        assertEquals("提前 1s 的偏移让用户看到第 1 行", 1, lyricsTimeline(lines, 4.2, 1.0).index)
        assertEquals(
            "负偏移把进度夹到 0，不会出现负数下标",
            -1,
            lyricsTimeline(lines, 0.2, -5.0).index,
        )
    }

    @Test
    fun timelineReportsWordTimingFromTheCurrentLineUsingSweepProgress() {
        // 逐字行：a 唱 0.0~0.5，b 唱 0.5~1.0（两个词，共 2 个字）。
        val worded = wordedLine(
            time = 0.0,
            text = "ab",
            words = listOf(LyricWord(0.0, 0.5, "a"), LyricWord(0.5, 0.5, "b")),
        )
        val plain = line(2.0, "整行歌词")
        val lines = listOf(worded, plain)

        val during = lyricsTimeline(lines, positionSeconds = 0.6, offsetSeconds = 0.0)
        assertEquals(0, during.index)
        assertTrue("当前行有真逐字数据", during.hasWordTiming)
        // a 唱完（1 个字）+ b 唱了 20% → 1.2 / 2 = 0.6，与 LyricHighlight.sweepProgress 一致。
        assertProgress(0.6f, during.sweep)

        val after = lyricsTimeline(lines, positionSeconds = 2.5, offsetSeconds = 0.0)
        assertEquals(1, after.index)
        assertFalse("当前行没有逐字数据时要退回整行高亮", after.hasWordTiming)
    }

    @Test
    fun emptyLyricsAreReportedAsNoLyricsInsteadOfAnEmptyTimeline() {
        val none = lyricsTimeline(emptyList(), positionSeconds = 3.0, offsetSeconds = 0.0)
        assertEquals(-1, none.index)
        assertNull(none.current)
        assertNull(none.next)
        assertFalse(none.hasLyrics)
        assertFalse(none.hasWordTiming)
        assertProgress(0f, none.sweep)
    }
}

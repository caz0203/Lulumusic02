package com.lulu.music

import com.lulu.music.data.model.LyricHighlight
import com.lulu.music.data.model.LyricLine
import com.lulu.music.data.model.LyricWord
import com.lulu.music.ui.screens.LyricInk
import com.lulu.music.ui.screens.LyricPaintMode
import com.lulu.music.ui.screens.lyricPaintPlan
import com.lulu.music.ui.screens.lyricSweepMaxExtrapolationSeconds
import com.lulu.music.ui.screens.smoothLyricPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡拉 OK 渲染层的**纯逻辑**：一行歌词该整行高亮还是该逐字扫过（[lyricPaintPlan]），
 * 以及两次位置更新之间的外推（[smoothLyricPosition]）。
 *
 * 这两件事以前散在 Compose 里、只能靠肉眼验；现在它们是普通函数，跑在普通 JUnit 上
 * （不需要 Robolectric，也不碰网络）。真正的「画出来长什么样」由 `LyricSweepRenderTest`
 * 覆盖；歌词时间本身的算术由数据层的 `WordLyricTest` 覆盖，这里**一行都不重算**。
 */
class LyricSweepTest {

    /** "Hello world"，两个词加起来正好 11 个字符：10.0s → 11.0s。 */
    private val wordedLine = LyricLine(
        time = 10.0,
        text = "Hello world",
        words = listOf(
            LyricWord(time = 10.0, duration = 0.5, text = "Hello"),
            LyricWord(time = 10.5, duration = 0.5, text = " world"),
        ),
    )

    private fun assertFraction(expected: Double, actual: Float, message: String = "sweepFraction") {
        assertEquals(message, expected, actual.toDouble(), 1e-6)
    }

    // ------------------------------------------------------------------
    // 1. 有真数据就用真的：当前行 + 真逐字 → 扫过
    // ------------------------------------------------------------------

    @Test
    fun currentLineWithRealWordTimingSweeps() {
        val plan = lyricPaintPlan(wordedLine, isCurrent = true, progress = 10.25, nextLineTime = 20.0)

        assertEquals(LyricPaintMode.SWEEP, plan.mode)
        assertEquals("未唱到的部分用 UNSUNG", LyricInk.UNSUNG, plan.baseInk)
        assertEquals("唱到的部分用当前行的强调色", LyricInk.CURRENT, plan.sweepInk)
        // "Hello" 唱了一半 → 2.5 / 11 个字符。
        assertFraction(2.5 / 11.0, plan.sweepFraction)
        assertEquals(
            "比例必须直接来自 LyricHighlight.sweepProgress（渲染层不许自己写歌词算术）",
            LyricHighlight.sweepProgress(wordedLine, 20.0, 10.25).toDouble(),
            plan.sweepFraction.toDouble(),
            1e-6,
        )
    }

    @Test
    fun sweepFractionIsMonotonicFromZeroToOneAcrossTheLine() {
        var previous = -1f
        var progress = 10.0
        while (progress <= 11.0 + 1e-9) {
            val fraction = lyricPaintPlan(wordedLine, true, progress, 20.0).sweepFraction
            assertTrue(
                "逐字比例只能向前（progress=$progress, $previous → $fraction）",
                fraction >= previous - 1e-6f,
            )
            assertTrue("比例必须落在 0..1（progress=$progress, fraction=$fraction）", fraction in 0f..1f)
            previous = fraction
            progress += 0.01
        }
        assertFraction(1.0, previous, "整行唱完必须是 1")
    }

    @Test
    fun sweepUsesTheNextLineTimeOnlyAsADocumentedFallback() {
        // 有真逐字数据时，下一行的起点不参与插值 —— 两个不同的 nextLineTime 必须得到同一个比例。
        val near = lyricPaintPlan(wordedLine, true, 10.7, nextLineTime = 12.0)
        val far = lyricPaintPlan(wordedLine, true, 10.7, nextLineTime = 60.0)
        val none = lyricPaintPlan(wordedLine, true, 10.7, nextLineTime = null)
        assertEquals(near, far)
        assertEquals(near, none)
        // 10.5 起唱 " world"（6 个字符、0.5s），到 10.7 唱了它的 2/5：
        // 已经覆盖 5 个字符 + 6 × 0.4 = 7.4，总共 11 个字符。
        assertFraction(7.4 / 11.0, near.sweepFraction)
    }

    @Test
    fun wordTimedLineBeforeItsOwnStartIsAtZeroAndNotNegative() {
        val plan = lyricPaintPlan(wordedLine, true, 9.0, nextLineTime = 20.0)
        assertEquals(LyricPaintMode.SWEEP, plan.mode)
        assertFraction(0.0, plan.sweepFraction)
    }

    // ------------------------------------------------------------------
    // 2. 没有真数据就退回整行高亮（今天的画法）
    // ------------------------------------------------------------------

    @Test
    fun currentLineWithoutRealWordTimingFallsBackToTheWholeLineHighlight() {
        val notWordLevel = listOf(
            "空 words（三个平台在只有整行时间时的返回值）" to LyricLine(time = 10.0, text = "Hello world"),
            "只有一个词：一个时间点不是时间轴" to LyricLine(
                time = 10.0,
                text = "Hello world",
                words = listOf(LyricWord(10.0, 1.0, "Hello world")),
            ),
            "所有词时长都是 0：没有可插值的真实时间" to LyricLine(
                time = 10.0,
                text = "Hello world",
                words = listOf(LyricWord(10.0, 0.0, "Hello"), LyricWord(10.5, 0.0, " world")),
            ),
        )

        for ((why, line) in notWordLevel) {
            assertTrue("前提错了：$why 不该被判成有逐字时间", !LyricHighlight.hasWordTiming(line))

            val plan = lyricPaintPlan(line, isCurrent = true, progress = 10.5, nextLineTime = 20.0)
            assertEquals("$why → 必须整行高亮", LyricPaintMode.WHOLE_LINE, plan.mode)
            assertEquals("$why → 底色就是今天的当前行色", LyricInk.CURRENT, plan.baseInk)
            assertEquals("$why → 没有扫过层，扫过色不参与绘制", LyricInk.CURRENT, plan.sweepInk)
            assertFraction(0.0, plan.sweepFraction, "$why → 整行高亮时比例恒为 0")
        }
    }

    @Test
    fun nonCurrentLinesNeverSweepEvenWhenTheyHaveWordTiming() {
        val plan = lyricPaintPlan(wordedLine, isCurrent = false, progress = 10.6, nextLineTime = 20.0)
        assertEquals("非当前行与改动前逐像素一致：整行、暗色、不扫", LyricPaintMode.WHOLE_LINE, plan.mode)
        assertEquals(LyricInk.DIM, plan.baseInk)
        assertEquals(LyricInk.DIM, plan.sweepInk)
        assertFraction(0.0, plan.sweepFraction)
    }

    @Test
    fun sweepFractionStaysInsideZeroOneEvenForJunkWordData() {
        val junk = listOf(
            LyricLine(time = 0.0, text = "", words = listOf(LyricWord(0.0, 1.0, ""), LyricWord(1.0, 1.0, ""))),
            LyricLine(
                time = 5.0,
                text = "x",
                words = listOf(LyricWord(99.0, 1.0, "x"), LyricWord(100.0, 1.0, "y")),
            ),
            LyricLine(
                time = 0.0,
                text = "ab",
                words = listOf(LyricWord(0.0, 1e9, "a"), LyricWord(1e9, 1e9, "b")),
            ),
        )
        for (line in junk) {
            for (progress in listOf(-1.0, 0.0, 0.5, 5.0, 99.5, 1000.0, 1e12)) {
                val fraction = lyricPaintPlan(line, true, progress, 10.0).sweepFraction
                assertTrue(
                    "畸形逐字数据也只能给出 0..1（progress=$progress, fraction=$fraction）",
                    fraction in 0f..1f,
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. 平滑时钟的算术
    // ------------------------------------------------------------------

    @Test
    fun smoothClockExtrapolatesForwardByRealTimeAndSpeed() {
        assertEquals(10.1, smoothLyricPosition(10.0, 0.1, 1f), 1e-9)
        assertEquals("2x 播放时歌词也要按 2x 走", 10.4, smoothLyricPosition(10.0, 0.2, 2f), 1e-9)
        assertEquals("0.5x 播放时按一半走", 10.1, smoothLyricPosition(10.0, 0.2, 0.5f), 1e-9)
    }

    @Test
    fun smoothClockNeverRunsBackwardsAndNeverDriftsAwayWhenThePositionStopsUpdating() {
        assertEquals(
            "真实时间倒流（时钟被改 / 影子时钟）不能让歌词倒退",
            10.0,
            smoothLyricPosition(10.0, -5.0, 1f),
            1e-9,
        )
        assertEquals(
            "位置更新停了就冻在锚点 + 一个上限，绝不越飘越远",
            10.0 + lyricSweepMaxExtrapolationSeconds,
            smoothLyricPosition(10.0, 60.0, 1f),
            1e-9,
        )
        assertEquals(
            "上限同样受倍速影响",
            10.0 + lyricSweepMaxExtrapolationSeconds * 2.0,
            smoothLyricPosition(10.0, 60.0, 2f),
            1e-9,
        )
    }

    @Test
    fun smoothClockIsTotalForIllegalInput() {
        assertEquals("NaN 倍速按 1x 处理", 10.2, smoothLyricPosition(10.0, 0.2, Float.NaN), 1e-9)
        assertEquals("0 倍速按 1x 处理", 10.2, smoothLyricPosition(10.0, 0.2, 0f), 1e-9)
        assertEquals("负倍速按 1x 处理", 10.2, smoothLyricPosition(10.0, 0.2, -3f), 1e-9)
        assertEquals("NaN 经过时间就是不动", 10.0, smoothLyricPosition(10.0, Double.NaN, 1f), 1e-9)
        assertEquals("NaN 锚点退化成 0，绝不往下游吐 NaN", 0.0, smoothLyricPosition(Double.NaN, 0.5, 1f), 1e-9)
        assertEquals(
            "非法上限退化成「不额外外推」",
            10.0,
            smoothLyricPosition(10.0, 0.5, 1f, maxExtrapolationSeconds = Double.NaN),
            1e-9,
        )
        assertEquals(0.0, smoothLyricPosition(0.0, 0.0, 1f), 1e-9)
    }

    @Test
    fun smoothClockIsMonotonicInElapsedTime() {
        var previous = Double.NEGATIVE_INFINITY
        var elapsed = 0.0
        while (elapsed <= 2.0) {
            val position = smoothLyricPosition(30.0, elapsed, 1f)
            assertTrue("elapsed=$elapsed 让位置倒退了", position >= previous)
            previous = position
            elapsed += 0.01
        }
        assertTrue("位置必须真的往前走", previous > 30.0)
    }
}

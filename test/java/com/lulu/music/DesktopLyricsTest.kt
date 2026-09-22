package com.lulu.music

import com.lulu.music.playback.BeansDesktopLyricsEvent
import com.lulu.music.playback.BeansDesktopLyricsPanel
import com.lulu.music.playback.BeansDesktopLyricsPanelAutoHideMs
import com.lulu.music.playback.BeansDesktopLyricsState
import com.lulu.music.playback.BeansOverlayPoint
import com.lulu.music.playback.beansClampOverlayPosition
import com.lulu.music.playback.beansDefaultOverlayPosition
import com.lulu.music.playback.beansDesktopLyricsPanelKeepAlive
import com.lulu.music.playback.beansDesktopLyricsPanelShouldHide
import com.lulu.music.playback.beansDesktopLyricsPanelTap
import com.lulu.music.playback.beansDesktopLyricsPanelTick
import com.lulu.music.playback.beansDesktopLyricsPanelVisible
import com.lulu.music.playback.beansDesktopLyricsReduce
import com.lulu.music.playback.beansOverlayPositionSaved
import com.lulu.music.playback.beansResolveOverlayPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面歌词的状态机 + 控制面板 + 落点几何（纯 JVM 测试）。
 *
 * **这一组测到的**：窗口什么时候该出现（有歌就出现，暂停也算）、缺少悬浮窗权限时是不是
 * 「诚实的失败」、锁定之后**还吃不吃触摸**（只锁拖动）、点「关闭」是不是把开关一起关掉、
 * 控制面板的展开 / 收起 / 自动收起、以及拖动落点会不会被夹出屏幕。
 *
 * **这一组测不到的**（本仓库没有模拟器 / 真机）：`WindowManager.addView` 到底成不成功、
 * 窗口 flag 在真实窗口管理器里的行为、系统权限弹窗、真实触摸 —— 见
 * [DesktopLyricsPermissionTest] 的说明。这里只把「决定这些行为的那套逻辑」钉死。
 */
class DesktopLyricsTest {

    /** 「开关开着、有歌在放、有权限」的基准状态。 */
    private val visible = BeansDesktopLyricsState(
        enabled = true,
        hasSong = true,
        isPlaying = true,
        permissionGranted = true,
    )

    // ------------------------------------------------------------------
    // 什么时候该有窗口
    // ------------------------------------------------------------------

    @Test
    fun windowNeedsToggleASongAndPermission() {
        assertFalse("默认什么都没有", BeansDesktopLyricsState().windowAttached)
        assertFalse("只打开开关、一首歌都没有 → 不出现", BeansDesktopLyricsState(enabled = true).windowAttached)
        assertFalse(
            "开关 + 有歌但没权限 → 不出现",
            BeansDesktopLyricsState(enabled = true, hasSong = true).windowAttached,
        )
        assertTrue("三个条件齐了才出现", visible.windowAttached)
    }

    @Test
    fun theWindowFollowsTheSongNotThePlaybackState() {
        // 需求点名的四种状态：在放 / 暂停 / 没有歌 / 播放结束。
        val playing = beansDesktopLyricsReduce(visible, BeansDesktopLyricsEvent.PLAYING)
        assertTrue("在放：窗口在", playing.windowAttached)

        val paused = beansDesktopLyricsReduce(playing, BeansDesktopLyricsEvent.PAUSED)
        assertTrue("暂停：窗口**留着**（用户要求暂停也能看到歌词）", paused.windowAttached)
        assertTrue("暂停：歌还在", paused.hasSong)
        assertFalse("暂停：播放状态要如实变假（面板上的图标靠它）", paused.isPlaying)

        val stopped = beansDesktopLyricsReduce(paused, BeansDesktopLyricsEvent.STOPPED)
        assertFalse("播放结束（队列空了）：没有歌 → 窗口收掉", stopped.windowAttached)
        assertFalse(stopped.isPlaying)

        val loadedAgain = beansDesktopLyricsReduce(stopped, BeansDesktopLyricsEvent.SONG_LOADED)
        assertTrue("重新装载一首歌（还没开始放）→ 窗口立刻回来", loadedAgain.windowAttached)
        assertFalse(loadedAgain.isPlaying)

        val nothing = beansDesktopLyricsReduce(visible, BeansDesktopLyricsEvent.SONG_NONE)
        assertFalse("清空队列 / 播放器断开 → 窗口消失", nothing.windowAttached)
        assertFalse(nothing.isPlaying)
    }

    @Test
    fun playbackStoppingWithoutASongRemovesTheWindowButKeepsTheToggle() {
        val stopped = beansDesktopLyricsReduce(visible, BeansDesktopLyricsEvent.STOPPED)
        assertFalse("没有歌之后窗口必须消失", stopped.windowAttached)
        assertTrue("但开关仍然是用户设的那个值，下次播放会自己回来", stopped.enabled)
        assertTrue(beansDesktopLyricsReduce(stopped, BeansDesktopLyricsEvent.PLAYING).windowAttached)
    }

    @Test
    fun playingAlwaysImpliesSomethingIsLoaded() {
        // 「不在放但有歌」（暂停）是合法状态；反过来「在放却没有歌」会让可见性判据自相矛盾。
        BeansDesktopLyricsEvent.entries.forEach { event ->
            val state = beansDesktopLyricsReduce(
                BeansDesktopLyricsState(enabled = true, permissionGranted = true),
                event,
            )
            assertFalse("$event 之后不允许出现「在放却没有歌」：$state", state.isPlaying && !state.hasSong)
        }
    }

    @Test
    fun missingPermissionIsAVisibleFailureNotASilentNoOp() {
        val blocked = visible.copy(permissionGranted = false)
        assertTrue("想显示却没有权限 —— 必须能被识别出来并给出说明", blocked.permissionBlocked)
        assertFalse(blocked.windowAttached)
        assertFalse("有权限时不该报错", visible.permissionBlocked)
        assertFalse(
            "开关关着时也不该报错（那只是用户没开）",
            BeansDesktopLyricsState(enabled = false, hasSong = true).permissionBlocked,
        )
    }

    @Test
    fun closingTheBarAlsoTurnsTheToggleOff() {
        val closed = beansDesktopLyricsReduce(visible, BeansDesktopLyricsEvent.CLOSE)
        assertFalse("关闭按钮必须把开关一起关掉，两者永远不失配", closed.enabled)
        assertFalse(closed.windowAttached)
        assertFalse("锁定也一起清掉", closed.locked)

        val closedWhileLocked = beansDesktopLyricsReduce(
            visible.copy(locked = true),
            BeansDesktopLyricsEvent.CLOSE,
        )
        assertFalse(closedWhileLocked.enabled)
        assertFalse(closedWhileLocked.locked)
        assertTrue(
            "锁定时窗口照样接收触摸，所以「关闭」这个事件永远送得到（这就是「关闭无效」的修复）",
            visible.copy(locked = true).touchable,
        )
    }

    // ------------------------------------------------------------------
    // 控制面板：点一下出现 / 再点一下收起 / 几秒不动自动收起
    // ------------------------------------------------------------------

    @Test
    fun tappingTheLyricsTogglesTheControlPanel() {
        var panel = BeansDesktopLyricsPanel.Hidden
        assertFalse("默认没有面板：悬浮条上只有两行歌词", panel.visible)

        panel = beansDesktopLyricsPanelTap(panel, nowMs = 1_000L)
        assertTrue("单击（长按同理）→ 面板出现", panel.visible)
        assertEquals("出现的那一刻就开始计时", 1_000L, panel.lastInteractionMs)

        panel = beansDesktopLyricsPanelTap(panel, nowMs = 2_000L)
        assertFalse("再点一下 → 收起", panel.visible)
        assertEquals("收起后不再带着上次的计时", BeansDesktopLyricsPanel.Hidden, panel)
    }

    @Test
    fun thePanelAutoHidesOnlyAfterTheSilenceTimeout() {
        val shown = beansDesktopLyricsPanelTap(BeansDesktopLyricsPanel.Hidden, nowMs = 0L)

        assertFalse(
            "还差 1ms 不能收",
            beansDesktopLyricsPanelShouldHide(shown, nowMs = BeansDesktopLyricsPanelAutoHideMs - 1),
        )
        assertTrue(
            "静默满超时必须收",
            beansDesktopLyricsPanelShouldHide(shown, nowMs = BeansDesktopLyricsPanelAutoHideMs),
        )
        assertEquals(
            BeansDesktopLyricsPanel.Hidden,
            beansDesktopLyricsPanelTick(shown, nowMs = BeansDesktopLyricsPanelAutoHideMs),
        )
    }

    @Test
    fun anInteractionInsideThePanelPushesTheAutoHideDeadlineBack() {
        val shown = beansDesktopLyricsPanelTap(BeansDesktopLyricsPanel.Hidden, nowMs = 0L)
        val poked = beansDesktopLyricsPanelKeepAlive(shown, nowMs = BeansDesktopLyricsPanelAutoHideMs - 1)

        assertTrue("交互只改计时，不改显隐", poked.visible)
        assertEquals(
            "刚交互过 → 到点也不能收",
            poked,
            beansDesktopLyricsPanelTick(poked, nowMs = BeansDesktopLyricsPanelAutoHideMs),
        )
        assertTrue(
            "从这次交互起再等满一个超时才收",
            beansDesktopLyricsPanelTick(
                poked,
                nowMs = poked.lastInteractionMs + BeansDesktopLyricsPanelAutoHideMs,
            ) == BeansDesktopLyricsPanel.Hidden,
        )
    }

    @Test
    fun aHiddenPanelNeverPopsUpByItself() {
        assertFalse(
            "已经收起的面板，再久也不该自己出现",
            beansDesktopLyricsPanelShouldHide(BeansDesktopLyricsPanel.Hidden, nowMs = 10 * BeansDesktopLyricsPanelAutoHideMs),
        )
        assertEquals(
            BeansDesktopLyricsPanel.Hidden,
            beansDesktopLyricsPanelTick(BeansDesktopLyricsPanel.Hidden, nowMs = 10 * BeansDesktopLyricsPanelAutoHideMs),
        )
        assertEquals(
            "收起状态下「交互」不该把面板变出来（它只重置计时）",
            BeansDesktopLyricsPanel.Hidden,
            beansDesktopLyricsPanelKeepAlive(BeansDesktopLyricsPanel.Hidden, nowMs = 99L),
        )
    }

    @Test
    fun aClockRollbackNeverFlashesThePanelAway() {
        val shown = beansDesktopLyricsPanelTap(BeansDesktopLyricsPanel.Hidden, nowMs = 5_000L)

        assertFalse(
            "时刻倒退（NTP / 时区抖动）时差值变负 —— 只能判「还不该收」",
            beansDesktopLyricsPanelShouldHide(shown, nowMs = 0L),
        )
    }

    @Test
    fun thePanelDiesWithTheWindow() {
        val panel = beansDesktopLyricsPanelTap(BeansDesktopLyricsPanel.Hidden, nowMs = 1_000L)

        assertTrue("窗口在：面板可以显示", beansDesktopLyricsPanelVisible(panel, visible.windowAttached))

        val closed = beansDesktopLyricsReduce(visible, BeansDesktopLyricsEvent.CLOSE)
        assertFalse(closed.windowAttached)
        assertFalse(
            "关闭之后面板不可能还显示（面板与窗口同生共死）",
            beansDesktopLyricsPanelVisible(panel, closed.windowAttached),
        )

        val noSong = beansDesktopLyricsReduce(visible, BeansDesktopLyricsEvent.SONG_NONE)
        assertFalse("没有歌之后同样不可能留着面板", beansDesktopLyricsPanelVisible(panel, noSong.windowAttached))
        assertFalse("收起的面板在任何窗口状态下都不显示", beansDesktopLyricsPanelVisible(BeansDesktopLyricsPanel.Hidden, true))
    }

    // ------------------------------------------------------------------
    // 锁定 / 拖动
    // ------------------------------------------------------------------

    @Test
    fun lockingOnlyLocksDraggingAndStaysTappable() {
        val locked = visible.copy(locked = true)
        assertTrue("锁定的有效值成立", locked.lockedEffective)
        assertFalse("锁定 = 不能拖动", locked.draggable)
        assertTrue("锁定 = 窗口**照常**接收触摸：关闭 / 解锁永远点得到", locked.touchable)
        assertTrue("锁定不影响窗口是否存在", locked.windowAttached)

        val unlocked = visible.copy(locked = false)
        assertTrue("解锁后恢复可拖拽", unlocked.draggable)
        assertTrue(unlocked.touchable)
        assertFalse(unlocked.lockedEffective)
    }

    @Test
    fun lockNeverSurvivesTheToggleBeingTurnedOff() {
        val locked = visible.copy(locked = true)
        val disabled = beansDesktopLyricsReduce(locked, BeansDesktopLyricsEvent.DISABLED)
        assertFalse(disabled.enabled)
        assertFalse("关掉开关必须把锁定一起清掉", disabled.locked)
        assertFalse(disabled.lockedEffective)
        assertFalse(disabled.draggable)
    }

    @Test
    fun lockIsIgnoredWhileTheToggleIsOff() {
        val off = BeansDesktopLyricsState(enabled = false, locked = false)
        assertFalse(
            "开关没打开时「锁定」不该生效（否则会留下一个开关关着、锁定还亮着的分裂状态）",
            beansDesktopLyricsReduce(off, BeansDesktopLyricsEvent.LOCK).locked,
        )
        assertTrue("开关打开后锁定才生效", beansDesktopLyricsReduce(visible, BeansDesktopLyricsEvent.LOCK).locked)
        assertFalse(
            "解锁随时有效",
            beansDesktopLyricsReduce(visible.copy(locked = true), BeansDesktopLyricsEvent.UNLOCK).locked,
        )
    }

    @Test
    fun lockedAlwaysImpliesEnabledForEveryEventSequence() {
        val events = BeansDesktopLyricsEvent.entries
        val starts = listOf(
            BeansDesktopLyricsState(),
            visible,
            visible.copy(locked = true),
            BeansDesktopLyricsState(enabled = true, hasSong = true, locked = true),
        )
        starts.forEach { start ->
            events.forEach { first ->
                events.forEach { second ->
                    val state = beansDesktopLyricsReduce(
                        beansDesktopLyricsReduce(start, first),
                        second,
                    )
                    assertFalse(
                        "不变式被破坏：$start → $first → $second 得到 $state",
                        state.locked && !state.enabled,
                    )
                    assertTrue(
                        "窗口必须**永远**接收触摸（锁定只锁拖动）：$start → $first → $second 得到 $state",
                        state.touchable,
                    )
                    assertFalse(
                        "锁定 + 可以拖动不可能同时成立：$state",
                        state.lockedEffective && state.draggable,
                    )
                    assertFalse(
                        "「在放却没有歌」不可能出现：$state",
                        state.isPlaying && !state.hasSong,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 落点：拖动 + 夹紧
    // ------------------------------------------------------------------

    @Test
    fun clampingKeepsTheBarOnScreen() {
        assertEquals(
            "拖到屏幕左上角之外 → 贴边",
            BeansOverlayPoint(0, 0),
            beansClampOverlayPosition(-500, -900, windowWidth = 300, windowHeight = 80, screenWidth = 1080, screenHeight = 1920),
        )
        assertEquals(
            "拖到右下角之外 → 最多贴到「屏幕 - 窗口」",
            BeansOverlayPoint(780, 1840),
            beansClampOverlayPosition(5000, 5000, windowWidth = 300, windowHeight = 80, screenWidth = 1080, screenHeight = 1920),
        )
        assertEquals(
            "屏幕内的落点原样保留（拖动是自由的）",
            BeansOverlayPoint(120, 640),
            beansClampOverlayPosition(120, 640, windowWidth = 300, windowHeight = 80, screenWidth = 1080, screenHeight = 1920),
        )
    }

    @Test
    fun oversizedWindowSticksToTheTopLeftInsteadOfGoingNegative() {
        assertEquals(
            "窗口比屏幕还大（超窄屏 / 超大字号）：贴左上角，仍然可见可抓",
            BeansOverlayPoint(0, 0),
            beansClampOverlayPosition(50, 50, windowWidth = 1200, windowHeight = 2200, screenWidth = 1080, screenHeight = 1920),
        )
    }

    @Test
    fun defaultPositionIsCentredNearTheTop() {
        val point = beansDefaultOverlayPosition(windowWidth = 300, windowHeight = 80, screenWidth = 1080, screenHeight = 1920)
        assertEquals("水平居中", 390, point.x)
        assertEquals("垂直约 12%", 230, point.y)
        assertTrue(beansOverlayPositionSaved(point.x, point.y))
    }

    @Test
    fun savedPositionWinsButIsStillClamped() {
        assertEquals(
            "用户拖过一次之后永远用他留下的位置",
            BeansOverlayPoint(120, 640),
            beansResolveOverlayPosition(120, 640, 300, 80, 1080, 1920),
        )
        assertEquals(
            "换机 / 旋转之后旧落点可能已经在屏幕外 → 夹回可见区域",
            BeansOverlayPoint(780, 1840),
            beansResolveOverlayPosition(4000, 4000, 300, 80, 1080, 1920),
        )
    }

    @Test
    fun unsavedSentinelFallsBackToTheDefaultPosition() {
        assertFalse("负值 = 用户还没拖过", beansOverlayPositionSaved(-1, -1))
        assertFalse("-1 只有一个也是「没拖过」", beansOverlayPositionSaved(-1, 300))
        assertEquals(
            beansDefaultOverlayPosition(300, 80, 1080, 1920),
            beansResolveOverlayPosition(-1, -1, 300, 80, 1080, 1920),
        )
    }

    // ------------------------------------------------------------------
    // 落点：不许压在通知栏上（用户截图里「歌词贴在状态栏下面 / 后面」那一条）
    // ------------------------------------------------------------------

    /**
     * `minTop`（状态栏底边）= 落点的**上边界**：任何来源的落点都不会再落到状态栏里面。
     *
     * 窗口用的是 `FLAG_LAYOUT_IN_SCREEN`，`y = 0` 就是状态栏**里面**；旧实现只夹到
     * `0..屏幕高 - 窗口高`，于是一个持久化下来的 `y = 0`（拖到贴顶、或从老备份读出来）
     * 会让歌词永远压在通知栏上。不传 `minTop` 时行为与改造前逐像素一致 —— 上面那几条
     * 既有断言就是靠这一点继续成立的。
     */
    @Test
    fun theTopInsetKeepsTheBarBelowTheStatusBar() {
        assertEquals(
            "落点在状态栏里面 → 被推到状态栏下方",
            BeansOverlayPoint(0, 120),
            beansClampOverlayPosition(0, 0, windowWidth = 300, windowHeight = 80, screenWidth = 1080, screenHeight = 1920, minTop = 120),
        )
        assertEquals(
            "状态栏下方（哪怕是紧贴下沿）的落点原样保留",
            BeansOverlayPoint(0, 120),
            beansClampOverlayPosition(0, 120, windowWidth = 300, windowHeight = 80, screenWidth = 1080, screenHeight = 1920, minTop = 120),
        )
        assertEquals(
            "更靠下的落点同样不动",
            BeansOverlayPoint(400, 640),
            beansClampOverlayPosition(400, 640, windowWidth = 300, windowHeight = 80, screenWidth = 1080, screenHeight = 1920, minTop = 120),
        )
        assertEquals(
            "不传 minTop = 改造前的行为（纯几何调用方不受影响）",
            BeansOverlayPoint(0, 0),
            beansClampOverlayPosition(0, 0, windowWidth = 300, windowHeight = 80, screenWidth = 1080, screenHeight = 1920),
        )
    }

    /** 用户从老备份 / 手改偏好里读出来一个「状态栏里的落点」时，也要被抬到状态栏下方。 */
    @Test
    fun aSavedPositionInsideTheStatusBarIsLiftedBelowIt() {
        assertEquals(
            "保存过（y = 0，压在通知栏上）→ 抬到状态栏下方，x 仍然是用户拖到的那个 x",
            BeansOverlayPoint(120, 120),
            beansResolveOverlayPosition(120, 0, windowWidth = 300, windowHeight = 80, screenWidth = 1080, screenHeight = 1920, minTop = 120),
        )
    }

    /**
     * 默认落点：水平居中 + 「12% 与状态栏下界取较大值」。
     *
     * 常规屏幕上 12% 远低于状态栏，取值之后仍是 12%（默认观感不变）；屏幕很矮 / 状态栏很高时
     * 才用状态栏下界 —— 于是「默认位置在通知栏下方」这句话在任何屏幕上都成立。
     */
    @Test
    fun theDefaultPositionStaysCentredAndNeverInsideTheStatusBar() {
        val normal = beansDefaultOverlayPosition(300, 80, 1080, 1920, minTop = 120)
        assertEquals("常规屏幕：仍然是 12%（230）", 230, normal.y)
        assertEquals("水平居中", 390, normal.x)

        val shortScreen = beansDefaultOverlayPosition(300, 80, 600, 700, minTop = 120)
        assertEquals("矮屏：12% = 84 会落进状态栏 → 用状态栏下界", 120, shortScreen.y)
        assertEquals("水平仍然居中", 150, shortScreen.x)
    }

    /**
     * 窗口比屏幕还大（超窄屏 / 超大字号）时，上界 `maxY` 会小于 `minTop`。
     *
     * 这时下界必须**退让**到 0：不退让的话 `coerceIn(下界, 上界)` 会抛
     * `IllegalArgumentException` —— 那会变成「换个字体大小就把悬浮窗搞崩」。
     */
    @Test
    fun anOversizedWindowNeverBlowsUpTheClampWhenThereIsATopInset() {
        assertEquals(
            "窗口比屏幕还高：贴左上角，绝不抛异常",
            BeansOverlayPoint(0, 0),
            beansClampOverlayPosition(50, 50, windowWidth = 1200, windowHeight = 2200, screenWidth = 1080, screenHeight = 1920, minTop = 120),
        )
    }
}

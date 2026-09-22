package com.lulu.music

import com.lulu.music.data.model.Lang
import com.lulu.music.data.prefs.AppLanguage
import com.lulu.music.playback.BeansOverlayFailureKind
import com.lulu.music.playback.beansOverlayFailureKind
import com.lulu.music.playback.beansOverlayFailureLogLine
import com.lulu.music.playback.beansOverlayFailureText
import com.lulu.music.playback.beansOverlayRetryVisible
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 悬浮窗失败提示的**映射**：每一种「权限 / 异常」组合都必须给出**对应那一句**。
 *
 * 背景：真机上报的失败以前只有一句「桌面歌词无法显示」，真正的原因只写进了 `CrashLog` ——
 * 用户明明已经把「显示在其他应用上层」打开了，却只看到一句「无法显示」，无从下手。
 * 这个文件把「什么原因 → 哪一句中文 / 英文」钉死。
 *
 * ## 能测的（纯 JVM，不需要 Robolectric）
 *
 *  - 异常 → [BeansOverlayFailureKind] 的分类（判据是异常类名 / message，都是纯 JVM 信息）；
 *  - 分类 → 用户可见的那句话（中英两版，靠 `Lang.current` 切换）；
 *  - 「原始异常只进日志、绝不进用户提示」这条分工；
 *  - 设置页「重试」的显示条件。
 *
 * ## 测不到的（**没有模拟器 / 真机，不做任何假装**）
 *
 * `WindowManager.addView` 到底会不会抛、真机上 `Settings.canDrawOverlays` 与 ROM 的
 * AppOps 是否一致 —— 这些都跑不起来。所以这里只验「拿到异常之后说的话对不对」，
 * 不假装验过「窗口能不能挂上」。
 */
class DesktopLyricsFailureMessageTest {

    @Before
    fun switchToChinese() {
        Lang.current.value = AppLanguage.CHINESE
    }

    @After
    fun restoreChinese() {
        // Lang 是进程级单例，别的测试类依赖默认中文（ErrorMessagesTest 会把英文留在那里）。
        Lang.current.value = AppLanguage.CHINESE
    }

    // ------------------------------------------------------------------
    // 一句话：每种原因都不一样
    // ------------------------------------------------------------------

    @Test
    fun everyFailureKindHasItsOwnNonEmptySentence() {
        val sentences = BeansOverlayFailureKind.entries.associateWith { beansOverlayFailureText(it) }

        sentences.forEach { (kind, text) ->
            assertTrue("$kind 必须有一句能读的话", text.isNotBlank())
        }
        assertEquals(
            "四种失败必须说四句不同的话，否则「说明原因」等于没说",
            BeansOverlayFailureKind.entries.size,
            sentences.values.toSet().size,
        )
    }

    @Test
    fun missingPermissionSentenceSaysWhichPermissionAndThatItRetriesItself() {
        val text = beansOverlayFailureText(BeansOverlayFailureKind.NO_PERMISSION)

        assertTrue("必须点名是哪一个权限：$text", text.contains("显示在其他应用上层"))
        assertTrue("必须告诉用户不用自己再点一次：$text", text.contains("自动重试"))
        assertFalse("没有权限时不能提「已授予」：$text", text.contains("已授予"))
    }

    @Test
    fun rejectedWindowSentenceNamesTheChineseRomExtraPermission() {
        val text = beansOverlayFailureText(BeansOverlayFailureKind.WINDOW_REJECTED)

        assertTrue("必须点明「权限已授予但系统仍然拒绝」这个矛盾：$text", text.contains("已授予"))
        assertTrue("必须给出国产 ROM 的额外权限名（后台弹出界面）：$text", text.contains("后台弹出界面"))
        assertTrue("同一条权限在别的 ROM 上叫法不同，也要写出来：$text", text.contains("后台显示界面"))
        assertTrue("必须给用户下一步动作：$text", text.contains("重试"))
        assertTrue("必须提到国产 ROM 这个语境：$text", text.contains("ROM"))
    }

    @Test
    fun viewBuildAndUnknownFailuresAreDifferentSentencesThatBothPointAtRetry() {
        val viewFailed = beansOverlayFailureText(BeansOverlayFailureKind.VIEW_BUILD_FAILED)
        val unknown = beansOverlayFailureText(BeansOverlayFailureKind.UNKNOWN)

        assertNotEquals(viewFailed, unknown)
        assertTrue("视图创建失败要指向「重试」：$viewFailed", viewFailed.contains("重试"))
        assertTrue("原因不明要指向「重试」：$unknown", unknown.contains("重试"))
        assertTrue("原因不明要如实说「没识别出来」：$unknown", unknown.contains("未能识别"))
    }

    // ------------------------------------------------------------------
    // 中英双语
    // ------------------------------------------------------------------

    @Test
    fun englishSentencesAreUsedWhenTheLanguageIsEnglish() {
        Lang.current.value = AppLanguage.ENGLISH

        assertTrue(
            beansOverlayFailureText(BeansOverlayFailureKind.NO_PERMISSION).contains("display over other apps"),
        )
        assertTrue(
            beansOverlayFailureText(BeansOverlayFailureKind.WINDOW_REJECTED)
                .contains("pop-up windows in the background"),
        )
        assertTrue(
            beansOverlayFailureText(BeansOverlayFailureKind.VIEW_BUILD_FAILED).contains("could not be created"),
        )
        assertTrue(
            beansOverlayFailureText(BeansOverlayFailureKind.UNKNOWN).contains("cause not recognised"),
        )
    }

    @Test
    fun userSentencesNeverLeakRawExceptionText() {
        BeansOverlayFailureKind.entries.forEach { kind ->
            listOf(AppLanguage.CHINESE, AppLanguage.ENGLISH).forEach { language ->
                Lang.current.value = language
                val text = beansOverlayFailureText(kind)
                listOf("Exception", "java.lang", "android.view", "\\tat ", "at com.").forEach { leak ->
                    assertFalse(
                        "$kind / $language 的用户提示里不能出现原始异常文本（$leak）：$text",
                        text.contains(leak),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 异常 → 分类
    // ------------------------------------------------------------------

    @Test
    fun addViewRejectionsAreClassifiedAsWindowRejected() {
        assertEquals(
            "BadTokenException 是「系统拒绝窗口」的典型",
            BeansOverlayFailureKind.WINDOW_REJECTED,
            beansOverlayFailureKind(BadTokenException("Unable to add window -- token null is not valid")),
        )
        assertEquals(
            "SecurityException（权限被 ROM 撤销 / 窗口类型被拒）同样算系统拒绝",
            BeansOverlayFailureKind.WINDOW_REJECTED,
            beansOverlayFailureKind(SecurityException("permission denied for window type 2038")),
        )
        assertEquals(
            "InvalidDisplayException 也是窗口层面的失败",
            BeansOverlayFailureKind.WINDOW_REJECTED,
            beansOverlayFailureKind(WindowManagerInvalidDisplayException("display is gone")),
        )
        assertEquals(
            "只靠 message 也必须认出来（ROM 会把真实异常包一层）",
            BeansOverlayFailureKind.WINDOW_REJECTED,
            beansOverlayFailureKind(RuntimeException("Unable to add window")),
        )
    }

    @Test
    fun windowRejectionIsFoundThroughTheCauseChain() {
        val wrapped = RuntimeException(
            "attach failed",
            IllegalStateException(
                "wrapper",
                SecurityException("permission denied for window type 2038"),
            ),
        )

        assertEquals(
            "真实设备上 addView 的失败经常被包着抛出来，必须顺着 cause 找到根因",
            BeansOverlayFailureKind.WINDOW_REJECTED,
            beansOverlayFailureKind(wrapped),
        )
    }

    @Test
    fun ownerAndCompositionSetupFailuresAreClassifiedAsViewBuildFailures() {
        assertEquals(
            "真机上的原话（BeansOverlayLifecycleOwner 的 attach 顺序错了）必须落到「视图创建失败」，" +
                "而不是被说成「系统拒绝窗口」或者「原因不明」",
            BeansOverlayFailureKind.VIEW_BUILD_FAILED,
            beansOverlayFailureKind(
                IllegalStateException("Restarter must be created only during owner's initialization stage"),
            ),
        )
        assertEquals(
            "Compose 缺 ViewTreeLifecycleOwner 的经典崩溃也属于同一类",
            BeansOverlayFailureKind.VIEW_BUILD_FAILED,
            beansOverlayFailureKind(
                IllegalStateException("ViewTreeLifecycleOwner not found from androidx.compose.ui.platform.ComposeView"),
            ),
        )
    }

    @Test
    fun unrelatedFailuresAreNeverReportedAsAPermissionProblem() {
        assertEquals(
            "普通异常不能被说成「没有权限」：那句提示在 canDrawOverlays 为 true 时是错的",
            BeansOverlayFailureKind.UNKNOWN,
            beansOverlayFailureKind(IllegalArgumentException("boom")),
        )
        assertEquals(
            BeansOverlayFailureKind.UNKNOWN,
            beansOverlayFailureKind(RuntimeException("attach failed", java.io.IOException("socket closed"))),
        )
        assertEquals("没有异常时只能是「未识别」", BeansOverlayFailureKind.UNKNOWN, beansOverlayFailureKind(null))
    }

    // ------------------------------------------------------------------
    // 原始文本只进日志
    // ------------------------------------------------------------------

    @Test
    fun theLogLineKeepsTheRawExceptionWhileTheUserSentenceDoesNot() {
        val raw = BadTokenException("Unable to add window -- token null is not valid")
        val kind = beansOverlayFailureKind(raw)
        val logLine = beansOverlayFailureLogLine(kind, raw)
        val userText = beansOverlayFailureText(kind)

        assertTrue("日志里必须有分类：$logLine", logLine.contains(kind.name))
        assertTrue("日志里必须有异常类名：$logLine", logLine.contains("BadTokenException"))
        assertTrue("日志里必须有异常 message：$logLine", logLine.contains("token null is not valid"))
        assertFalse("用户提示里不能有异常类名：$userText", userText.contains("BadTokenException"))
        assertFalse("用户提示里不能有异常 message：$userText", userText.contains("token null"))
    }

    @Test
    fun theRealDeviceFailureIsLoggedWithItsMessageAndExplainedWithoutIt() {
        // 真机上报的那一条：BeansOverlayLifecycleOwner.create() 里 attach 与 lifecycle 的顺序错了。
        val raw = IllegalStateException("Restarter must be created only during owner's initialization stage")
        val kind = beansOverlayFailureKind(raw)
        val logLine = beansOverlayFailureLogLine(kind, raw)
        val userText = beansOverlayFailureText(kind)

        assertEquals(BeansOverlayFailureKind.VIEW_BUILD_FAILED, kind)
        assertTrue(
            "日志必须带上那句话（旧实现只写了异常类名，消息被丢掉了，等于没写）：$logLine",
            logLine.contains("Restarter must be created"),
        )
        assertTrue("日志必须说清是哪一类：$logLine", logLine.contains("VIEW_BUILD_FAILED"))
        assertFalse("用户提示里不能出现 Restarter 这种内部术语：$userText", userText.contains("Restarter"))
        assertTrue("用户提示要给出下一步：$userText", userText.contains("重试"))
    }

    @Test
    fun theLogLineStillWorksWhenThereIsNoThrowable() {
        val logLine = beansOverlayFailureLogLine(BeansOverlayFailureKind.NO_PERMISSION, null)

        assertTrue("没有异常时日志至少要说清是「没有权限」：$logLine", logLine.contains("NO_PERMISSION"))
        assertTrue(logLine.startsWith("桌面歌词无法显示"))
    }

    // ------------------------------------------------------------------
    // 设置页「重试」的显示条件
    // ------------------------------------------------------------------

    @Test
    fun retryIsOfferedExactlyWhenTheWindowIsMissingWhileItIsWanted() {
        assertTrue(
            "正在放 + 权限已给 + 窗口没挂上 → 必须给「重试」（用户否则无路可走）",
            beansOverlayRetryVisible(enabled = true, permissionGranted = true, windowShown = false),
        )
        assertFalse(
            "窗口已经挂上就不该再显示「重试」",
            beansOverlayRetryVisible(enabled = true, permissionGranted = true, windowShown = true),
        )
        assertFalse(
            "开关关着时没有「重试」可言",
            beansOverlayRetryVisible(enabled = false, permissionGranted = true, windowShown = false),
        )
        assertFalse(
            "没权限时该显示的是「去授权」，不是「重试」",
            beansOverlayRetryVisible(enabled = true, permissionGranted = false, windowShown = false),
        )
    }

    /**
     * 与 `android.view.WindowManager.BadTokenException` **同名**的本地替身。
     *
     * 分类只看 `javaClass.name` 与 `message`，所以这个替身在纯 JVM 测试里与真类等价 ——
     * 而不带 Robolectric 的 JVM 测试拿不到 `android.view` 的实例。
     */
    private class BadTokenException(message: String) : RuntimeException(message)

    /** 与 `android.view.WindowManager.InvalidDisplayException` 同形的替身（类名里带 Window + Exception）。 */
    private class WindowManagerInvalidDisplayException(message: String) : RuntimeException(message)
}

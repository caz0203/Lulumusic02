package com.lulu.music

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.playback.BeansDesktopLyricsActionClose
import com.lulu.music.playback.BeansDesktopLyricsActionLock
import com.lulu.music.playback.BeansDesktopLyricsEvent
import com.lulu.music.playback.DesktopLyricsOverlay
import com.lulu.music.playback.beansDesktopLyricsActionEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 桌面歌词在**系统媒体通知**上的那一版接线已经按用户要求**回退**：通知重新是标准媒体卡片
 * （上一首 / 播放暂停 / 下一首），锁定 / 关闭不再有自定义按钮。
 *
 * ## 这个文件覆盖什么
 *
 *  1. **接线真的没了**（源码扫描，不假装有真机通知）：`BeansPlayerService.kt` 里没有
 *     `setMediaButtonPreferences` / `CommandButton` / `onCustomCommand` / `SessionCommand`
 *     （这些正是上一轮加那两颗按钮的四步），而 `MediaSession` 本身**照旧被创建** ——
 *     锁屏控件、蓝牙控件、音频焦点全都来自它，删掉自定义按钮不该碰它一根汗毛；
 *  2. **上一轮那两个辅助函数彻底消失**（`beansDesktopLyricsSessionCommands` /
 *     `beansDesktopLyricsNotificationButtons`）以及它们唯一的资源依赖（那三张通知图标）；
 *  3. **动作名 → 状态机的语义没变**（纯函数）：锁定 / 解锁共用一颗按钮的语义、未知动作必须
 *     交回 media3、关闭蕴含开关一起关；
 *  4. **锁定 / 关闭两条路都通**：关闭只有设置里的开关（写偏好 → 状态机 DISABLED → 窗口消失，
 *     `windowAttached` 变假）；锁定有两个入口（悬浮条控制条上的按钮 = [DesktopLyricsOverlay.toggleLock]、
 *     设置里的开关）。**没有任何一项功能变得够不着**。
 *
 * ## 测不到的（**没有模拟器 / 真机，不做任何假装**）
 *
 *  - 通知卡片在真机上**长什么样**、Android 13+ 的系统渲染展示哪几颗按钮（那是 SystemUI 的事）；
 *  - 用户真的点了通知上的播放按钮之后 `PendingIntent` 的投递；
 *  - 「偏好变化 → 服务里的订阅把 DISABLED 事件喂给状态机」这一段需要真的起一个
 *    `MediaSessionService`（JVM 单测里起不来）：这里只覆盖它两端的两个事实
 *    （偏好真的被写下去 / DISABLED 真的让窗口消失）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesktopLyricsNotificationTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val desktopKey = booleanPreferencesKey("beans.lyrics.desktop")
    private val lockedKey = booleanPreferencesKey("beans.lyrics.desktopLocked")

    @Before
    fun bootApplication() {
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
        ApplicationProvider.getApplicationContext<BeansApplication>()
        // 每个用例都从「开关关着、没锁定」的状态机开始（它是进程级单例）。
        DesktopLyricsOverlay.detach()
    }

    // ------------------------------------------------------------------
    // 1. 通知回到标准媒体卡片：自定义动作的接线与辅助函数都不在
    // ------------------------------------------------------------------

    /**
     * 源码扫描：`BeansPlayerService.kt` 里**没有**任何自定义通知按钮的接线。
     *
     * 为什么这样测：`setMediaButtonPreferences` / `CommandButton` / `onCustomCommand` /
     * `SessionCommand` 是给通知加按钮的**全部**四步（media3 的官方做法），任何一步都不会在
     * JVM 单测里产生可观测的差别 —— 但它们只要出现在这个文件里，通知就不再是标准卡片。
     * 扫描源码是本仓库既有的手法（`BeansHapticsTest` 用同样的方式钉住「只有一处碰马达」）。
     *
     * 同时正向断言：**`MediaSession` 仍然被创建**（锁屏 / 蓝牙 / 音频焦点全部依赖它）——
     * 只删按钮、不动会话，这条正向断言就是「删过头了」的警报。
     */
    @Test
    fun theServiceWiresNoCustomNotificationButtonAndStillBuildsTheMediaSession() {
        val source = codeOnly(serviceSource())

        listOf(
            "setMediaButtonPreferences" to "通知上的媒体按钮偏好",
            "CommandButton" to "自定义按钮",
            "onCustomCommand" to "自定义命令回调",
            "SessionCommand" to "自定义会话命令",
            "SessionCommands" to "自定义会话命令集合",
        ).forEach { (needle, what) ->
            assertFalse(
                "回退之后 $what（$needle）不该再出现在 BeansPlayerService 里 —— 它会让通知不再是标准媒体卡片",
                source.contains(needle),
            )
        }

        assertTrue(
            "MediaSession 必须照旧被创建（锁屏 / 蓝牙 / 音频焦点都靠它）",
            source.contains("MediaSession.Builder(this, exo).build()"),
        )
        assertTrue(
            "会话必须照旧交给系统（MediaSessionService.onGetSession）",
            source.contains("override fun onGetSession("),
        )
        assertTrue(
            "桌面歌词悬浮窗仍然挂在这个服务上（删按钮不该把悬浮条一起删了）",
            source.contains("DesktopLyricsOverlay.attach(this)"),
        )
    }

    /**
     * 上一轮为那两颗按钮写的东西**彻底消失**：两个辅助函数、三张通知图标。
     *
     * 留着它们就会出现「有一套没人调用的通知按钮代码」——下一个人很容易以为功能还在。
     */
    @Test
    fun theRetiredNotificationHelpersAndTheirIconsAreGone() {
        val offenders = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = codeOnly(file.readText())
                text.contains("beansDesktopLyricsSessionCommands") ||
                    text.contains("beansDesktopLyricsNotificationButtons")
            }
            .map { it.name }
            .toList()
        assertTrue("这两个函数必须彻底删除（代码里不能再出现），实际还留在：$offenders", offenders.isEmpty())

        val icons = listOf(
            "beans_notification_lock.xml",
            "beans_notification_unlock.xml",
            "beans_notification_close.xml",
        )
        val drawableDir = requireNotNull(mainSourceRoot().parentFile?.let { File(it, "res/drawable") }) {
            "找不到 res/drawable（user.dir=${System.getProperty("user.dir")}）"
        }
        assertTrue("必须能找到 res/drawable 目录，否则这条断言是空的", drawableDir.isDirectory)
        icons.forEach { icon ->
            assertFalse(
                "$icon 只服务于那两颗已删除的通知按钮，不该留在工程里",
                File(drawableDir, icon).exists(),
            )
        }
    }

    /**
     * 动作名这两个常量**只**活在「动作名 → 状态机事件」的纯逻辑那一个文件里，
     * 任何生产文件（服务 / 别的界面）都不再引用它们 —— 也就是说通知上确实没有发送方了。
     */
    @Test
    fun noProductionFileSendsTheDesktopLyricsNotificationActions() {
        val senders = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "DesktopLyricsState.kt" }
            .filter { file ->
                val text = codeOnly(file.readText())
                text.contains("BeansDesktopLyricsActionLock") || text.contains("BeansDesktopLyricsActionClose")
            }
            .map { it.name }
            .toList()

        assertTrue(
            "动作名只在 DesktopLyricsState.kt 里定义与做纯映射，没有任何生产文件发送它们（通知上已经没有那两颗按钮）；" +
                "但这里发现了引用者：$senders",
            senders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 2. 动作名 → 状态机事件（纯逻辑，语义与回退前一致）
    // ------------------------------------------------------------------

    @Test
    fun theLockActionTogglesAndTheCloseActionCloses() {
        assertEquals(
            "未锁定时点「锁定」→ 锁定",
            BeansDesktopLyricsEvent.LOCK,
            beansDesktopLyricsActionEvent(BeansDesktopLyricsActionLock, locked = false),
        )
        assertEquals(
            "已锁定时点同一颗按钮 → 解锁（锁定只用一颗按钮，状态由入参决定）",
            BeansDesktopLyricsEvent.UNLOCK,
            beansDesktopLyricsActionEvent(BeansDesktopLyricsActionLock, locked = true),
        )
        assertEquals(
            "关闭 → CLOSE（它同时把开关关掉，见状态机的不变式）",
            BeansDesktopLyricsEvent.CLOSE,
            beansDesktopLyricsActionEvent(BeansDesktopLyricsActionClose, locked = false),
        )
    }

    @Test
    fun unknownActionsAreNotSwallowed() {
        listOf("", "lock", "close", "com.example.other.ACTION", BeansDesktopLyricsActionLock + "X").forEach { action ->
            assertNull("不是桌面歌词的动作必须原样交回 media3：$action", beansDesktopLyricsActionEvent(action, false))
        }
    }

    // ------------------------------------------------------------------
    // 3. 锁定 / 关闭两条路都通（状态机端到端，落进真实偏好）
    // ------------------------------------------------------------------

    @Test
    fun tappingLockOnTheNotificationReallyLocksAndUnlocksTheOverlay() = runBlocking {
        // 先让状态机进入「开关打开」的状态（真机上这一步由偏好流驱动，见 DesktopLyricsOverlay.attach）。
        DesktopLyricsOverlay.onEvent(BeansDesktopLyricsEvent.ENABLED)
        awaitFlag(desktopKey, true)

        assertTrue(
            "动作名入口必须被认领（binder 那头据此回 RESULT_SUCCESS）",
            DesktopLyricsOverlay.onNotificationAction(BeansDesktopLyricsActionLock),
        )
        assertEquals("锁定必须真的落进偏好（设置页的开关据此显示已锁定）", true, awaitFlag(lockedKey, true))

        assertTrue(DesktopLyricsOverlay.onNotificationAction(BeansDesktopLyricsActionLock))
        assertEquals("已锁定时再点一次 = 解锁", false, awaitFlag(lockedKey, false))
    }

    /**
     * 悬浮条控制条上那颗锁定按钮（[DesktopLyricsOverlay.toggleLock]）走的是**同一个状态机**。
     *
     * 这是回退之后锁定功能的新主入口：通知上那颗按钮没有了，但锁定这件事必须仍然够得着，
     * 而且与设置里的开关必须是同一个真值源（不能各写各的）。
     */
    @Test
    fun theControlRowLockButtonTogglesTheSameStateMachine() = runBlocking {
        // 开关没打开时点锁定 = 无效（不变式：锁定蕴含开启）。
        DesktopLyricsOverlay.toggleLock()
        assertEquals("开关没开时锁定不该生效", false, awaitFlag(lockedKey, false))

        DesktopLyricsOverlay.onEvent(BeansDesktopLyricsEvent.ENABLED)
        awaitFlag(desktopKey, true)

        DesktopLyricsOverlay.toggleLock()
        assertEquals("控制条上的锁定按钮必须真的锁上", true, awaitFlag(lockedKey, true))
        assertTrue("状态机自己也认为锁定了（面板图标据此显示「解锁」）", DesktopLyricsOverlay.currentState.lockedEffective)

        DesktopLyricsOverlay.toggleLock()
        assertEquals("再点一次 = 解锁", false, awaitFlag(lockedKey, false))
        assertFalse(DesktopLyricsOverlay.currentState.lockedEffective)
    }

    /**
     * 关闭只剩设置里的开关这一个入口，但它**必须仍然能用**：
     * 开关关掉 → 状态机 DISABLED → 窗口的派生状态变假（悬浮条消失）。
     */
    @Test
    fun theSettingsToggleStillTurnsTheOverlayOff() = runBlocking {
        // 真值源三项都成立 → 该有窗口（权限在 Robolectric 里给不给都行，这里直接喂事件）。
        DesktopLyricsOverlay.onEvent(BeansDesktopLyricsEvent.ENABLED)
        DesktopLyricsOverlay.onEvent(BeansDesktopLyricsEvent.SONG_LOADED)
        DesktopLyricsOverlay.onEvent(BeansDesktopLyricsEvent.LOCK)
        DesktopLyricsOverlay.onEvent(BeansDesktopLyricsEvent.PERMISSION_GRANTED)
        awaitFlag(lockedKey, true)
        assertTrue("前提：这时窗口是该有的", DesktopLyricsOverlay.currentState.windowAttached)

        // 设置页那个开关写的就是这个偏好。
        SettingsStore.setDesktopLyrics(false)
        assertEquals("设置里的开关必须真的把偏好关掉", false, awaitFlag(desktopKey, false))

        // 服务里的订阅会把「开关关了」翻译成 DISABLED（那一段需要真服务，见文件头说明）；
        // 这里断言的是它的结果：窗口的派生状态变假，而且锁定一起清掉。
        DesktopLyricsOverlay.onEvent(BeansDesktopLyricsEvent.DISABLED)

        assertFalse("关掉开关之后不该再有窗口", DesktopLyricsOverlay.currentState.windowAttached)
        assertFalse("锁定必须一起清掉（锁定蕴含开启）", DesktopLyricsOverlay.currentState.lockedEffective)
        assertEquals(false, awaitFlag(lockedKey, false))
        assertFalse(
            "设置页也不会再显示「重试」（那是「该有窗口却没挂上」才出现的）",
            DesktopLyricsOverlay.currentState.permissionBlocked,
        )
    }

    @Test
    fun anUnknownActionIsReportedAsUnhandledSoMedia3CanDealWithIt() {
        assertFalse(
            "不认识的动作必须返回 false（否则会吞掉别人的自定义命令）",
            DesktopLyricsOverlay.onNotificationAction("com.example.other.DO_SOMETHING"),
        )
    }

    // ------------------------------------------------------------------
    // 设施
    // ------------------------------------------------------------------

    private fun serviceSource(): String {
        val file = File(mainSourceRoot(), "com/lulu/music/playback/BeansPlayerService.kt")
        assertTrue("找不到 BeansPlayerService.kt（这条用例必须真的读到源码）：$file", file.isFile)
        return file.readText()
    }

    /**
     * 去掉行注释与块注释之后的**代码**。
     *
     * 源码扫描必须只看代码：这次改造的文档里**故意**写着「没有 `setMediaButtonPreferences`…」
     * 这类话，如果连注释一起扫，那些说明本身就会把用例判成失败 —— 那是断言写错了，不是代码错了。
     * 逐字符扫描（而不是正则）：正则里要写出注释定界符本身，那种写法在本仓库是个已知的雷。
     */
    private fun codeOnly(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var inBlockComment = false
        var inLineComment = false
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when {
                inBlockComment -> if (current == '*' && next == '/') {
                    inBlockComment = false
                    index++
                }

                inLineComment -> if (current == '\n') {
                    inLineComment = false
                    out.append(current)
                }

                current == '/' && next == '*' -> {
                    inBlockComment = true
                    index++
                }

                current == '/' && next == '/' -> {
                    inLineComment = true
                    index++
                }

                else -> out.append(current)
            }
            index++
        }
        return out.toString()
    }

    /**
     * 找 `src/main/java`：Gradle 单测的工作目录通常是 `android/app`，但为了在别的调用方式下也能
     * 跑，这里从工作目录往上找几层（与 `BeansHapticsTest` 同一套设施）。
     */
    private fun mainSourceRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (directory != null && depth < 6) {
            File(directory, "src/main/java").takeIf { it.isDirectory }?.let { return it }
            File(directory, "app/src/main/java").takeIf { it.isDirectory }?.let { return it }
            directory = directory.parentFile
            depth++
        }
        throw IllegalStateException("找不到 src/main/java（user.dir=${System.getProperty("user.dir")}）")
    }

    private suspend fun awaitFlag(key: Preferences.Key<Boolean>, expected: Boolean): Boolean? =
        withTimeoutOrNull(30_000L) {
            dataStore().data.first { it[key] == expected }[key]
        }

    /** `SettingsStore` 当前真正在用的 DataStore（反射读 `private lateinit var store`）。 */
    @Suppress("UNCHECKED_CAST")
    private fun dataStore(): DataStore<Preferences> {
        val holder = SettingsStore::class.java
        val instance = holder.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        return holder.getDeclaredField("store").apply { isAccessible = true }.get(instance)
            as DataStore<Preferences>
    }
}

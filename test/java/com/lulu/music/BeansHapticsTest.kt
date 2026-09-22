package com.lulu.music

import android.os.Build
import android.os.VibrationEffect
import com.lulu.music.ui.components.BeansHapticAction
import com.lulu.music.ui.components.BeansHapticDefaultAmplitude
import com.lulu.music.ui.components.BeansHapticEffect
import com.lulu.music.ui.components.BeansHapticEngine
import com.lulu.music.ui.components.BeansHapticPredefined
import com.lulu.music.ui.components.BeansHapticSdkOneShotEffect
import com.lulu.music.ui.components.BeansHapticSdkPredefinedEffect
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.androidPredefinedId
import com.lulu.music.ui.components.beansHapticEffect
import com.lulu.music.ui.components.beansHapticPredefinedFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 触感反馈：**开关真的挡得住**、**按 SDK 选对了 API**、**参数终于能被感觉到**。
 *
 * ## 用户在抱怨什么，以及这个文件怎么钉住修复
 *
 * 用户说「触感反馈开关没用」时，接线其实是**对的**（开关写 `beans.hapticsEnabled`，
 * `BeansHaptics.isEnabled` 读同一个键，`VIBRATE` 权限也在）。真正的原因是**旧参数太弱**：
 * `tap()` 12ms/振幅 62、`select()` 8ms/振幅 42 —— 在大多数线性马达上等于没震。所以这里断言三件事：
 *  1. **开关关着 → 一次都不许震**（四个公开入口 + 内部出口逐个验证，注入一个记录器来数次数）；
 *  2. **按 SDK 选 API**：29+ 用系统预置效果、26..28 用一段定长震动（无振幅控制时用系统默认振幅）、
 *     24..25 用已废弃的重载；并且这些分支的数值在 Robolectric 里跟真实的
 *     `VibrationEffect.EFFECT_*` / `Build.VERSION_CODES.*` 比过一遍（我们自己的常量不是瞎写的）；
 *  3. **新参数严格强于旧参数**（逐项断言：22ms > 12ms、150 > 62 ……），并且四个动作互不相同。
 *
 * 另外用**源码扫描**钉住「全仓库只有 BeansHaptics 一个地方碰马达」—— 这是「没有任何路径绕过
 * 开关」这条要求的另一半：只要没有人绕过去，开关就管得住所有震动。
 *
 * ## 测不到的（**没有模拟器 / 真机，不做任何假装**）
 *
 * 真机上「这一下到底震得多明显」—— `Vibrator` 的强弱是硬件 + 厂商调校的事，JVM 里读不到。
 * 这里只钉住「交给系统的效果是什么」，不假装验过手感。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class BeansHapticsTest {

    /** 记录每一次「交给马达」的效果；生产路径上这个出口是包着系统 `Vibrator` 的实现。 */
    private class RecordingEngine(
        override val sdkInt: Int,
        override val hasAmplitudeControl: Boolean = true,
    ) : BeansHapticEngine {
        val effects = mutableListOf<BeansHapticEffect>()

        override fun vibrate(effect: BeansHapticEffect) {
            effects += effect
        }
    }

    /** 保存原始的开关读取器：测试结束必须换回去，否则会影响同一个沙箱里后面的用例。 */
    private lateinit var originalReader: () -> Boolean

    @Before
    fun installFakeToggle() {
        originalReader = BeansHaptics.enabledReader
        BeansHaptics.resetTestEngine()
    }

    @After
    fun restoreProductionWiring() {
        BeansHaptics.enabledReader = originalReader
        BeansHaptics.resetTestEngine()
    }

    // ------------------------------------------------------------------
    // 1. 开关：关着一次都不震，开着恰好一次
    // ------------------------------------------------------------------

    @Test
    fun turningTheToggleOffSuppressesEveryHapticCallPath() {
        val engine = RecordingEngine(sdkInt = 34)
        BeansHaptics.testEngine = engine
        BeansHaptics.enabledReader = { false }

        // 四个公开入口逐个来一遍。
        BeansHaptics.tap()
        BeansHaptics.select()
        BeansHaptics.medium()
        BeansHaptics.success()
        // 再把所有动作直接从出口走一遍（将来新增动作时这条也会覆盖到）。
        BeansHapticAction.entries.forEach { BeansHaptics.emit(it) }

        assertTrue(
            "关掉「触感反馈」之后一次都不许震，实际震了 ${engine.effects.size} 次：${engine.effects}",
            engine.effects.isEmpty(),
        )
        assertFalse("开关关着时 isEnabled 必须为假", BeansHaptics.isEnabled)
    }

    @Test
    fun turningTheToggleOnProducesExactlyOneEffectPerCall() {
        val engine = RecordingEngine(sdkInt = 34)
        BeansHaptics.testEngine = engine
        BeansHaptics.enabledReader = { true }

        BeansHaptics.tap()
        BeansHaptics.select()
        BeansHaptics.medium()
        BeansHaptics.success()

        assertEquals(
            "四次调用必须恰好产生四次震动（不多不少），实际 ${engine.effects}",
            listOf(
                BeansHapticEffect.Predefined(BeansHapticPredefined.CLICK),
                BeansHapticEffect.Predefined(BeansHapticPredefined.TICK),
                BeansHapticEffect.Predefined(BeansHapticPredefined.HEAVY_CLICK),
                BeansHapticEffect.Predefined(BeansHapticPredefined.DOUBLE_CLICK),
            ),
            engine.effects,
        )
        assertTrue(BeansHaptics.isEnabled)
    }

    @Test
    fun theToggleIsReadFromTheSettingsStoreByDefault() {
        // 默认实现读 `SettingsStore.hapticsEnabled`（默认 true，与 iOS 一致）——
        // 这条断言证明「开关关着就不震」里的那个开关确实是设置里的那一个。
        BeansHaptics.resetTestEngine()
        BeansHaptics.enabledReader = originalReader
        assertTrue("默认（没改过设置）必须是开启的", BeansHaptics.isEnabled)
    }

    // ------------------------------------------------------------------
    // 2. 按 SDK 选 API
    // ------------------------------------------------------------------

    @Test
    fun api29AndAboveUsesTheSystemPredefinedEffects() {
        listOf(29, 30, 33, 34, 35).forEach { sdk ->
            BeansHapticAction.entries.forEach { action ->
                BeansHapticEffect.Predefined(beansHapticPredefinedFor(action)).let { expected ->
                    assertEquals(
                        "SDK $sdk 的 $action 必须走系统预置效果",
                        expected,
                        beansHapticEffect(action, sdkInt = sdk, hasAmplitudeControl = true),
                    )
                    // 预置效果与振幅控制无关：无振幅控制的设备也用同一支。
                    assertEquals(
                        "SDK $sdk 的 $action 在无振幅控制的设备上同样是预置效果",
                        expected,
                        beansHapticEffect(action, sdkInt = sdk, hasAmplitudeControl = false),
                    )
                }
            }
        }
    }

    @Test
    fun api26To28FallsBackToOneShotWithOrWithoutAmplitudeControl() {
        assertEquals(
            "有振幅控制 → 用自定振幅",
            BeansHapticEffect.OneShot(22L, 150),
            beansHapticEffect(BeansHapticAction.TAP, sdkInt = 28, hasAmplitudeControl = true),
        )
        assertEquals(
            "无振幅控制 → 必须用系统默认振幅（给具体数字会被忽略甚至抛异常）",
            BeansHapticEffect.OneShot(22L, BeansHapticDefaultAmplitude),
            beansHapticEffect(BeansHapticAction.TAP, sdkInt = 28, hasAmplitudeControl = false),
        )
        assertEquals(
            BeansHapticEffect.OneShot(14L, 100),
            beansHapticEffect(BeansHapticAction.SELECT, sdkInt = 26, hasAmplitudeControl = true),
        )
        assertEquals(
            BeansHapticEffect.OneShot(34L, 220),
            beansHapticEffect(BeansHapticAction.MEDIUM, sdkInt = 26, hasAmplitudeControl = true),
        )
        assertEquals(
            "成功是双响波形；两段之间的 0 段必须保持 0（否则静默期间也会震）",
            BeansHapticEffect.Waveform(listOf(0L, 22L, 62L, 34L), listOf(0, 150, 0, 220)),
            beansHapticEffect(BeansHapticAction.SUCCESS, sdkInt = 28, hasAmplitudeControl = true),
        )
        assertEquals(
            "无振幅控制时非零段换成系统默认振幅，0 段仍然是 0",
            BeansHapticEffect.Waveform(
                listOf(0L, 22L, 62L, 34L),
                listOf(0, BeansHapticDefaultAmplitude, 0, BeansHapticDefaultAmplitude),
            ),
            beansHapticEffect(BeansHapticAction.SUCCESS, sdkInt = 28, hasAmplitudeControl = false),
        )
    }

    @Test
    fun api24And25UseTheLegacyOverloads() {
        BeansHapticAction.entries.forEach { action ->
            val effect = beansHapticEffect(action, sdkInt = 24, hasAmplitudeControl = true)
            assertTrue(
                "API 24/25 没有 VibrationEffect，只能用已废弃的重载，实际 $effect",
                effect is BeansHapticEffect.LegacyOneShot || effect is BeansHapticEffect.LegacyWaveform,
            )
        }
        assertEquals(
            BeansHapticEffect.LegacyOneShot(22L),
            beansHapticEffect(BeansHapticAction.TAP, sdkInt = 24, hasAmplitudeControl = false),
        )
        assertEquals(
            BeansHapticEffect.LegacyWaveform(listOf(0L, 22L, 62L, 34L)),
            beansHapticEffect(BeansHapticAction.SUCCESS, sdkInt = 25, hasAmplitudeControl = false),
        )
    }

    @Test
    fun theSdkBoundariesAreTheRealPlatformConstants() {
        // 我们自己的常量必须是字面量（这一层要零 Android 依赖），所以在这里跟真实常量对一次。
        assertEquals("预置效果的边界 = Build.VERSION_CODES.Q", Build.VERSION_CODES.Q, BeansHapticSdkPredefinedEffect)
        assertEquals("一段震动的边界 = Build.VERSION_CODES.O", Build.VERSION_CODES.O, BeansHapticSdkOneShotEffect)

        assertTrue(
            "边界两侧必须给出不同的 API：28 → 一段震动，29 → 预置效果",
            beansHapticEffect(BeansHapticAction.TAP, 28, true) !is BeansHapticEffect.Predefined &&
                beansHapticEffect(BeansHapticAction.TAP, 29, true) is BeansHapticEffect.Predefined,
        )
    }

    @Test
    fun thePredefinedIdsMatchThePlatformConstants() {
        assertEquals(VibrationEffect.EFFECT_CLICK, androidPredefinedId(BeansHapticPredefined.CLICK))
        assertEquals(VibrationEffect.EFFECT_DOUBLE_CLICK, androidPredefinedId(BeansHapticPredefined.DOUBLE_CLICK))
        assertEquals(VibrationEffect.EFFECT_TICK, androidPredefinedId(BeansHapticPredefined.TICK))
        assertEquals(VibrationEffect.EFFECT_HEAVY_CLICK, androidPredefinedId(BeansHapticPredefined.HEAVY_CLICK))
        assertEquals(
            "枚举里的数值必须与平台常量一致（否则预置效果会串味）",
            VibrationEffect.EFFECT_CLICK,
            BeansHapticPredefined.CLICK.systemId,
        )
        assertEquals(
            "系统默认振幅的哨兵值必须与平台常量一致",
            VibrationEffect.DEFAULT_AMPLITUDE,
            BeansHapticDefaultAmplitude,
        )
    }

    // ------------------------------------------------------------------
    // 3. 参数终于能被感觉到，而且四个动作互不相同
    // ------------------------------------------------------------------

    @Test
    fun theNewParametersAreStrictlyStrongerThanTheUnperceivableOldOnes() {
        val tap = beansHapticEffect(BeansHapticAction.TAP, 28, true) as BeansHapticEffect.OneShot
        val select = beansHapticEffect(BeansHapticAction.SELECT, 28, true) as BeansHapticEffect.OneShot
        val medium = beansHapticEffect(BeansHapticAction.MEDIUM, 28, true) as BeansHapticEffect.OneShot

        // 改造前：tap 12ms/62、select 8ms/42、medium 24ms/128。
        assertTrue("tap 时长必须比改造前的 12ms 长：${tap.durationMillis}", tap.durationMillis > 12L)
        assertTrue("tap 振幅必须比改造前的 62 强：${tap.amplitude}", tap.amplitude > 62)
        assertTrue("select 时长必须比改造前的 8ms 长：${select.durationMillis}", select.durationMillis > 8L)
        assertTrue("select 振幅必须比改造前的 42 强：${select.amplitude}", select.amplitude > 42)
        assertTrue("medium 时长必须比改造前的 24ms 长：${medium.durationMillis}", medium.durationMillis > 24L)
        assertTrue("medium 振幅必须比改造前的 128 强：${medium.amplitude}", medium.amplitude > 128)

        // 不刺耳：没有任何一段超过 40ms（长震才是「obnoxious」）。
        listOf(tap, select, medium).forEach { effect ->
            assertTrue("单次震动必须仍然很短：${effect.durationMillis}ms", effect.durationMillis <= 40L)
        }
    }

    @Test
    fun theFourActionsAreDistinctFromEachOther() {
        val predefined = BeansHapticAction.entries.map { beansHapticPredefinedFor(it) }
        assertEquals("四个动作必须是四种不同的预置效果", predefined.size, predefined.toSet().size)

        val oneShots = BeansHapticAction.entries
            .filter { it != BeansHapticAction.SUCCESS }
            .map { beansHapticEffect(it, 28, true) }
        assertEquals("轻点 / 选择 / 中等三种回退参数必须互不相同", oneShots.size, oneShots.toSet().size)

        assertNotEquals(
            "轻点与选择不能一样（用户要能分辨「点了一下」和「选了东西」）",
            beansHapticEffect(BeansHapticAction.TAP, 28, true),
            beansHapticEffect(BeansHapticAction.SELECT, 28, true),
        )
    }

    // ------------------------------------------------------------------
    // 4. 没有任何路径绕过 BeansHaptics
    // ------------------------------------------------------------------

    /**
     * 源码扫描：全仓库**只有** `ui/components/BeansHaptics.kt` 碰 `Vibrator` / `vibrate(`。
     *
     * 这是「关掉开关之后一次都不许震」的另一半：开关管得住的前提是**所有**震动都从这一个出口走。
     * 任何人在别处写一句 `vibrator.vibrate(...)`，这条用例立刻红 —— 那种震动会绕过开关。
     */
    @Test
    fun onlyBeansHapticsTouchesTheVibrator() {
        val offenders = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "BeansHaptics.kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("Vibrator") || text.contains(".vibrate(")
            }
            .map { it.name }
            .toList()

        assertTrue(
            "这些文件绕过了 BeansHaptics 直接碰马达（会绕过触感开关）：$offenders",
            offenders.isEmpty(),
        )
    }

    /** `BeansHaptics.kt` 本身仍然把开关作为唯一闸门（防止有人把 `isEnabled` 判断删掉）。 */
    @Test
    fun theGateAndTheSingleExitAreStillInPlace() {
        val source = File(
            mainSourceRoot(),
            "com/lulu/music/ui/components/BeansHaptics.kt",
        ).readText()

        assertTrue("开关必须仍然读 SettingsStore.hapticsEnabled", source.contains("SettingsStore.hapticsEnabled"))
        assertEquals(
            "四个公开入口都必须走同一个出口 emit（少一个就等于少一道闸门）",
            4,
            Regex("=\\s*emit\\(BeansHapticAction\\.").findAll(source).count(),
        )
        assertTrue(
            "出口里必须先判开关再碰马达（`if (!isEnabled) return`）",
            source.contains("if (!isEnabled) return"),
        )
    }

    /**
     * 找 `src/main/java`：Gradle 单测的工作目录通常是 `android/app`，但为了在别的调用方式下也能
     * 跑，这里从工作目录往上找几层。
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
}

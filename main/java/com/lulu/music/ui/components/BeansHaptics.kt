package com.lulu.music.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.lulu.music.BeansApplication
import com.lulu.music.data.prefs.SettingsStore

/**
 * 触感反馈（port of iOS `BeansHaptics`）。
 *
 * iOS 用 `UIImpactFeedbackGenerator` / `UISelectionFeedbackGenerator`；
 * Android 没有等价物，这里用系统 [Vibrator] 近似：
 *
 * | iOS | Android |
 * | --- | --- |
 * | `tap()`（`.light` 冲击） | 12ms / 低振幅单次震动 |
 * | `medium()`（`.medium` 冲击） | 24ms / 中振幅单次震动 |
 * | `select()`（`UISelectionFeedbackGenerator`） | 8ms / 极低振幅单次震动 |
 * | `success()`（`.success` 通知） | 两段波形（短-长），对应 iOS 的上行双响 |
 *
 * 与 iOS 一致，所有方法都受「设置 → 触感反馈」开关（[SettingsStore.hapticsEnabled]）控制；
 * 开关关闭、设备无马达、系统拒绝震动时一律静默返回，绝不抛异常。
 *
 * `VibrationEffect` 需要 API 26，因此 API 24/25 回退到已废弃的 `Vibrator.vibrate(millis)` /
 * `vibrate(pattern, repeat)`；[Vibrator.hasAmplitudeControl] 为 false 的设备同样回退到默认振幅。
 */
object BeansHaptics {

    /** 与 iOS `BeansHaptics.enabledKey` 完全相同的持久化键。 */
    const val ENABLED_KEY = "beans.haptics.enabled"

    private var vibrator: Vibrator? = null
    private var attempted = false

    /**
     * 注入震动服务。`BeansApplication.onCreate` 或 [BeansHapticsEffect] 调用；
     * 未调用时会在首次触发时惰性回退到 [BeansApplication.instance]。
     */
    fun init(context: Context) {
        attempted = true
        vibrator = runCatching { resolveVibrator(context.applicationContext) }.getOrNull()
    }

    private fun resolveVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** 惰性获取马达；任何一步失败都返回 null（= 静默降级）。 */
    private fun activeVibrator(): Vibrator? {
        if (!attempted) {
            val app = runCatching { BeansApplication.instance }.getOrNull()
            if (app != null) init(app)
        }
        val device = vibrator ?: return null
        return runCatching { if (device.hasVibrator()) device else null }.getOrNull()
    }

    /**
     * 是否开启触感反馈。iOS 默认 `true`（`object(forKey:) as? Bool ?? true`），保持一致；
     * 读取失败（例如偏好层尚未初始化）时同样按「开启」处理，绝不因为读设置而崩溃。
     */
    val isEnabled: Boolean
        get() = runCatching { SettingsStore.hapticsEnabled.value }.getOrDefault(true)

    /** iOS 侧用于预热 Taptic Engine；Android 的 Vibrator 是系统服务，无需预热，保留空实现对齐 API。 */
    fun prepare() = Unit

    /** 轻冲击（对应 iOS `.light`）。 */
    fun tap() = oneShot(durationMillis = 12L, amplitude = 62)

    /** 中等冲击（对应 iOS `.medium`）。 */
    fun medium() = oneShot(durationMillis = 24L, amplitude = 128)

    /** 成功通知（对应 iOS `notificationOccurred(.success)`）：短-长两段波形。 */
    fun success() = waveform(
        timings = longArrayOf(0L, 18L, 62L, 26L),
        amplitudes = intArrayOf(0, 96, 0, 150),
    )

    /** 选择变化（对应 iOS `UISelectionFeedbackGenerator`）。 */
    fun select() = oneShot(durationMillis = 8L, amplitude = 42)

    @Suppress("DEPRECATION")
    private fun oneShot(durationMillis: Long, amplitude: Int) {
        if (!isEnabled) return
        val device = activeVibrator() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = if (device.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
                device.vibrate(VibrationEffect.createOneShot(durationMillis, amp))
            } else {
                device.vibrate(durationMillis)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun waveform(timings: LongArray, amplitudes: IntArray) {
        if (!isEnabled) return
        val device = activeVibrator() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amps =
                    if (device.hasAmplitudeControl()) amplitudes
                    else IntArray(amplitudes.size) { index ->
                        if (index % 2 == 0) 0 else VibrationEffect.DEFAULT_AMPLITUDE
                    }
                device.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            } else {
                device.vibrate(timings, -1)
            }
        }
    }
}

/**
 * 在 Compose 树中注入震动服务：放在 `setContent` 根部即可（等价于 iOS 在 App 启动时创建一次生成器）。
 */
@Composable
fun BeansHapticsEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        BeansHaptics.init(context)
        onDispose { }
    }
}

package com.lulu.music

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.playback.beansCanDrawOverlays
import com.lulu.music.playback.openOverlayPermissionSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * 悬浮窗权限这条**失败路径**里唯一能在 Robolectric 里测的部分。
 *
 * 能测的：
 *  - 权限查询永远给出一个布尔值（绝不因为查权限把 App 弄崩）；
 *  - 「去授权」真的发出 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`，并且带上本 App 的包名
 *    （落到本应用那一页，而不是一长串应用列表）。
 *
 * 测不到的（**没有模拟器 / 真机，不做任何假装**）：
 *  - 系统授权弹窗本身、用户在系统页面上的选择；
 *  - `WindowManager.addView` 是否成功、真实触摸是否送达悬浮条（单击 / 长按 / 拖动）；
 *  - 悬浮条在真实屏幕上的拖动与落点。
 *  这些只能靠 [DesktopLyricsTest] 里的纯逻辑保证「决定行为的那套规则」是对的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class DesktopLyricsPermissionTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun restoreOverlayPermission() {
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun overlayPermissionQueryReportsBothStatesAndNeverThrows() {
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse("系统默认不授予悬浮窗权限", beansCanDrawOverlays(context))

        ShadowSettings.setCanDrawOverlays(true)
        assertTrue("授权之后必须读到 true（设置页据此决定要不要显示「去授权」）", beansCanDrawOverlays(context))
    }

    @Test
    fun overlayPermissionSettingsIntentTargetsThisAppOnTheSystemOverlayPage() {
        ShadowSettings.setCanDrawOverlays(false)

        assertTrue("必须真的把用户送到系统页面", openOverlayPermissionSettings(context))

        val started = shadowOf(context).nextStartedActivity
        assertNotNull("必须真的发出一个 Intent", started)
        assertEquals(
            "必须是系统的「显示在其他应用上层」页面",
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            started.action,
        )
        assertEquals(
            "必须带本 App 的包名（否则用户会落到一长串应用列表里）",
            "package:${context.packageName}",
            started.data?.toString(),
        )
    }
}

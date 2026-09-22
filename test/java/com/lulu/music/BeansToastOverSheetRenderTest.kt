package com.lulu.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansSheetLayer
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.BeansToastHost
import com.lulu.music.ui.theme.BeansTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「面板打开时提示条也能被看到」的渲染测试。
 *
 * 这里能证明的三件事：
 *  1. `BeansBottomSheet` 真的登记了面板（[BeansSheetLayer] 计数 = 1）—— 落点切换的依据；
 *  2. 面板打开时提示条**只画一次**（没有出现「主窗口一份 + 面板窗口一份」的重影）；
 *  3. 提示条的实测位置在屏幕上方那一小块里，而不是贴在面板占据的下半屏。
 *
 * **证明不了**的：`Popup` 窗口与面板对话框窗口的真实 z 序 —— Robolectric 没有真实的
 * WindowManager 排序，只能在真机上肉眼确认。选 `Popup`（子窗口，且按需创建）的理由见
 * `BeansToast.kt` 的 [BeansToastHost] 注释。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class BeansToastOverSheetRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun resetSingletons() {
        ApplicationProvider.getApplicationContext<BeansApplication>()
        BeansToastCenter.dismiss()
        BeansSheetLayer.reset()
    }

    @After
    fun clearSingletons() {
        BeansToastCenter.dismiss()
        BeansSheetLayer.reset()
    }

    @Test(timeout = 60_000L)
    fun toastShownWhileTheSheetIsOpenIsRenderedOnceUpTop() {
        composeRule.setContent {
            BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") {
                Box(modifier = Modifier.fillMaxSize()) {
                    BeansBottomSheet(onDismissRequest = {}) {
                        Text("面板内容")
                    }
                    BeansToastHost(bottomPadding = 150.dp)
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(
            "面板宿主必须把「有面板」登记进去，否则提示条会一直贴在面板下面",
            1,
            BeansSheetLayer.openCount.value,
        )

        // 60 秒时长：让自动消失不参与这次断言（否则测试时钟一走，提示条就淡出了）。
        composeRule.runOnIdle { BeansToastCenter.show("已加入下一首", durationMillis = 60_000L) }
        composeRule.waitForIdle()

        val toastNodes = composeRule.onAllNodesWithText("已加入下一首").fetchSemanticsNodes()
        assertEquals("面板开着时提示条必须恰好画一次", 1, toastNodes.size)

        val top = toastNodes.first().boundsInRoot.top
        val upperLimit = with(composeRule.density) { 300.dp.toPx() }
        assertTrue(
            "提示条必须落在屏幕上方（实测 top=$top，上限 $upperLimit），" +
                "否则就是落在面板占掉的下半屏里",
            top < upperLimit,
        )
    }
}

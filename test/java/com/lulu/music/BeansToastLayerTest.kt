package com.lulu.music

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.lulu.music.ui.components.BeansSheetLayer
import com.lulu.music.ui.components.BeansToastDefaultBottomPadding
import com.lulu.music.ui.components.BeansToastDefaultTopPadding
import com.lulu.music.ui.components.beansToastPlacement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「提示条不被底部面板盖住」里**可测的那部分**：落点计算 + 面板计数。
 *
 * 层级本身（`Popup` 自己的窗口叠在面板的对话框窗口之上）没法在 JVM 里断言 —— 那是
 * WindowManager 的行为，只能在真机上肉眼确认。但这两件事可以钉住：
 *
 *  1. 面板打开时提示条的落点必须**换到顶部**（贴底 150dp 会正好压在面板的行上）；
 *  2. 面板进出的计数必须配对（嵌套也不会漏减、不会变成负数），否则落点会永远停在顶部。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class BeansToastLayerTest {

    @Before
    fun resetLayer() {
        BeansSheetLayer.reset()
    }

    @After
    fun clearLayer() {
        BeansSheetLayer.reset()
    }

    @Test
    fun toastStaysAtTheBottomWhileNoSheetIsOpen() {
        val placement = beansToastPlacement(sheetOpen = false)

        assertEquals(Alignment.BottomCenter, placement.alignment)
        assertEquals(BeansToastDefaultBottomPadding, placement.verticalOffset * -1)
        assertTrue("贴底时位移必须朝上（负 y）", placement.verticalOffset < 0.dp)
    }

    @Test
    fun toastKeepsTheCallSiteBottomPadding() {
        // BeansApp 传的是 150dp：没有面板时观感必须与改造前一致。
        val placement = beansToastPlacement(sheetOpen = false, bottomPadding = 150.dp)
        assertEquals(Alignment.BottomCenter, placement.alignment)
        assertEquals((-150).dp, placement.verticalOffset)
    }

    @Test
    fun toastMovesToTheTopWhileASheetIsOpen() {
        val open = beansToastPlacement(sheetOpen = true)
        val closed = beansToastPlacement(sheetOpen = false)

        assertEquals(Alignment.TopCenter, open.alignment)
        assertTrue("有面板时位移必须朝下（正 y）", open.verticalOffset > 0.dp)
        assertEquals(BeansToastDefaultTopPadding, open.verticalOffset)
        // 顶部留白必须小到还在屏幕上方那一小块里，否则「挪到顶部」等于挪到屏幕中间。
        assertTrue("顶部留白不该超过屏幕四分之一量级", open.verticalOffset <= 200.dp)
        assertNotEquals("有 / 没有面板的落点必须真的不同", closed.alignment, open.alignment)
    }

    @Test
    fun sheetCounterPairsOpenAndCloseAndNeverGoesNegative() {
        assertEquals(0, BeansSheetLayer.openCount.value)

        BeansSheetLayer.sheetOpened()
        assertEquals(1, BeansSheetLayer.openCount.value)
        assertTrue("面板一打开就必须判成「有面板」", BeansSheetLayer.openCount.value > 0)

        // 嵌套面板（面板里再开面板）：只需要「> 0」，但不能算错。
        BeansSheetLayer.sheetOpened()
        assertEquals(2, BeansSheetLayer.openCount.value)

        BeansSheetLayer.sheetClosed()
        assertEquals(1, BeansSheetLayer.openCount.value)

        BeansSheetLayer.sheetClosed()
        assertEquals(0, BeansSheetLayer.openCount.value)

        BeansSheetLayer.sheetClosed()
        assertEquals("多减一次不能变成负数（否则落点会永远停在顶部）", 0, BeansSheetLayer.openCount.value)
    }
}

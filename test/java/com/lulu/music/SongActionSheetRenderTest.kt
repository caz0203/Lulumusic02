package com.lulu.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Lang
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.AppLanguage
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.data.store.LocalPlaylistStore
import com.lulu.music.ui.components.LocalPlaylistPickerContent
import com.lulu.music.ui.components.playlistPickerRows
import com.lulu.music.ui.theme.BeansTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「添加到本地歌单」选择器的**渲染测试**：证明它能把 store 里的歌单真的画出来，
 * 并且「已在该歌单中」这一行是按真实内容标出来的。
 *
 * ## 为什么只组合 [LocalPlaylistPickerContent] 而不用 `BeansBottomSheet`
 *
 * `BeansBottomSheet` 是 `ModalBottomSheet`，内容由独立窗口承载；本文件要断言的是
 * **内容本身**（列表 / 勾选态 / 新建入口），直接组合内容既确定又不受浮层窗口影响。
 * 宿主（行菜单里的就地换页、播放页 `···` 的独立浮层）由 `SongActionSheet` 负责。
 *
 * ## 为什么开头要把歌单清空
 *
 * `LocalPlaylistStore` 是 JVM 单例，同一 JVM 里其它测试类可能已经往里塞过歌单，
 * 于是「列表里到底有几行」不可控（`LazyColumn` 只组合可见项）。
 * `BackupManagerTest` 也用同样的手法清场，这里保持一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class SongActionSheetRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun seedStore() {
        // `ErrorMessagesTest` 的英文用例会把进程级的 `Lang.current` 留在 ENGLISH，且不还原。
        Lang.current.value = AppLanguage.CHINESE
        ApplicationProvider.getApplicationContext<BeansApplication>()
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
        // 从「一张歌单都没有」这个确定状态开始。
        assertTrue(LocalPlaylistStore.importJson("[]"))
    }

    private fun song(id: Long) = Song(
        id = id,
        name = "选择器测试歌曲-$id",
        artists = "选择器测试歌手",
        album = "选择器测试专辑",
        source = SongSource.KUGOU,
        kugouHash = "PICKER-$id",
    )

    @Test(timeout = 60_000L)
    fun pickerListsPlaylistsWithCountsAndShowsTheAlreadyInStateHonestly() {
        val song = song(760_001L)
        val containing = requireNotNull(
            LocalPlaylistStore.create("包含这首歌-${System.nanoTime()}", listOf(song)),
        )
        val empty = requireNotNull(LocalPlaylistStore.create("空歌单-${System.nanoTime()}"))

        composeRule.setContent {
            Host {
                LocalPlaylistPickerContent(song = song, onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("添加到本地歌单").assertIsDisplayed()
        composeRule.onNodeWithText(song.name).assertIsDisplayed()
        // 新建入口必须在
        composeRule.onNodeWithText("新建歌单").assertIsDisplayed()
        // 两张歌单都要画出来
        composeRule.onNodeWithText(containing.name).assertIsDisplayed()
        composeRule.onNodeWithText(empty.name).assertIsDisplayed()
        // 已经在歌单里的那一行：如实标「已在歌单中」；空歌单显示「0 首」
        composeRule.onNodeWithText("已在歌单中").assertIsDisplayed()
        composeRule.onNodeWithText("0 首").assertIsDisplayed()
        assertTrue(
            "只有真的包含这首歌的那一张歌单才该被标成「已在歌单中」",
            composeRule.hasExactlyOne("已在歌单中"),
        )
    }

    @Test(timeout = 60_000L)
    fun pickerDoesNotClaimAlreadyInForASongNoPlaylistContains() {
        val song = song(760_002L)
        requireNotNull(LocalPlaylistStore.create("不相干的歌单-${System.nanoTime()}"))

        composeRule.setContent {
            Host {
                LocalPlaylistPickerContent(song = song, onDismiss = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("添加到本地歌单").assertIsDisplayed()
        composeRule.onAllNodesWithText("已在歌单中").assertCountEquals(0)

        val created = requireNotNull(
            LocalPlaylistStore.create("后加的歌单-${System.nanoTime()}", listOf(song)),
        )
        // 纯投影同步反映 store：这张新歌单必须被标成已包含。
        assertTrue(
            playlistPickerRows(LocalPlaylistStore.playlists.value, song)
                .first { it.id == created.id }
                .alreadyContains,
        )
    }

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        BeansTheme(themeMode = BeansThemeMode.DARK, accentKey = "amber") { content() }
    }
}

/** 某段文案在当前语义树里出现了几次（[androidx.compose.ui.test.assertCountEquals] 的可读封装）。 */
private fun ComposeContentTestRule.hasExactlyOne(text: String): Boolean =
    onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1

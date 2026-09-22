package com.lulu.music

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Lang
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.AppLanguage
import com.lulu.music.data.store.CoverImageProcessor
import com.lulu.music.data.store.CoverStore
import com.lulu.music.data.store.LocalPlaylist
import com.lulu.music.data.store.LocalPlaylistStore
import com.lulu.music.ui.components.CoverPickResult
import com.lulu.music.ui.components.PlaylistAddOutcome
import com.lulu.music.ui.components.PlaylistPickerRow
import com.lulu.music.ui.components.SongActionKind
import com.lulu.music.ui.components.addSongToLocalPlaylist
import com.lulu.music.ui.components.applyPlaylistPick
import com.lulu.music.ui.components.coverPickMessage
import com.lulu.music.ui.components.coverPickResult
import com.lulu.music.ui.components.createLocalPlaylistWithSong
import com.lulu.music.ui.components.createdPlaylistMessage
import com.lulu.music.ui.components.downloadActionTrailing
import com.lulu.music.ui.components.favoriteActionTrailing
import com.lulu.music.ui.components.favoriteToggleMessage
import com.lulu.music.ui.components.playlistAddMessage
import com.lulu.music.ui.components.playlistPickerRows
import com.lulu.music.ui.components.songActionKinds
import com.lulu.music.ui.components.songCoverCoilModel
import com.lulu.music.ui.components.songCoverModel
import com.lulu.music.ui.components.songCustomCoverModel
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 新 UI 接线里**可测**的那部分逻辑。
 *
 * 覆盖三类东西：
 *  1. 纯规则：`⋮` 菜单的行与顺序（含「有封面才出现移除行」）、状态文案、收藏提示文案；
 *  2. 「哪张封面赢」：本机自定义封面 > 远程 `coverURL`，以及给 Coil 的模型必须是 `File`
 *     （缓存键要带 lastModified，否则换封面会命中旧图）；
 *  3. 选择器写回 `LocalPlaylistStore` 的真实路径：新增 / 已在该歌单中 / 歌单不存在三种结果
 *     必须被如实区分（`addSongs` 的返回值分不清后两者）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongActionSheetLogicTest {

    private var seq = 0

    private val identityProcessor = CoverImageProcessor { bytes -> bytes }

    @Before
    fun bootApplication() {
        // `Lang.current` 是进程级单例：`ErrorMessagesTest` 的英文用例会把它置成 ENGLISH 且不还原，
        // 所以这里必须显式钉回中文，否则同一 JVM 里的文案断言会随执行顺序飘。
        Lang.current.value = AppLanguage.CHINESE
        ApplicationProvider.getApplicationContext<BeansApplication>()
        LocalPlaylistStore.resetForTests()
        CoverStore.init(ApplicationProvider.getApplicationContext())
        CoverStore.resetForTests(identityProcessor)
    }

    @After
    fun restoreCoverProcessor() {
        // 单例是全进程共享的：别把假处理器留给别的测试类。
        CoverStore.resetForTests(null)
    }

    private fun unique(): String = "${System.nanoTime()}-${seq++}"

    private fun kugouSong(id: Long) = Song(
        id = id,
        name = "song-$id",
        artists = "歌手",
        album = "专辑",
        coverURL = "https://cdn.example.com/$id.jpg",
        duration = 200.0,
        source = SongSource.KUGOU,
        kugouHash = "HASH-$id",
    )

    // ------------------------------------------------------------------
    // 1. 菜单行与顺序
    // ------------------------------------------------------------------

    @Test
    fun theMoreMenuKeepsTheDesignedOrderAndOnlyShowsRemoveCoverWhenThereIsOne() {
        assertEquals(
            "没有自定义封面时：不能出现「移除自定义封面」（点了没反应的菜单项是不诚实的）",
            listOf(
                SongActionKind.PLAY_NEXT,
                SongActionKind.ADD_TO_PLAYLIST,
                SongActionKind.REPLACE_COVER,
                SongActionKind.FAVORITE,
                SongActionKind.DOWNLOAD,
            ),
            songActionKinds(hasCustomCover = false),
        )

        val withCover = songActionKinds(hasCustomCover = true)
        assertEquals(
            listOf(
                SongActionKind.PLAY_NEXT,
                SongActionKind.ADD_TO_PLAYLIST,
                SongActionKind.REPLACE_COVER,
                SongActionKind.REMOVE_COVER,
                SongActionKind.FAVORITE,
                SongActionKind.DOWNLOAD,
            ),
            withCover,
        )
        assertEquals(
            "「移除自定义封面」必须紧跟在「更换自定义封面」之后",
            withCover.indexOf(SongActionKind.REMOVE_COVER),
            withCover.indexOf(SongActionKind.REPLACE_COVER) + 1,
        )
        assertEquals(
            "有没有封面只影响那一行，核心行必须一模一样",
            songActionKinds(hasCustomCover = false),
            withCover.filterNot { it == SongActionKind.REMOVE_COVER },
        )
    }

    @Test
    fun trailingLabelsTellTheTruthAboutFavoriteAndDownloadState() {
        assertEquals("已收藏", favoriteActionTrailing(true))
        assertEquals("未收藏", favoriteActionTrailing(false))
        assertEquals("已下载", downloadActionTrailing(true))
        assertEquals("未下载", downloadActionTrailing(false))
    }

    @Test
    fun aFailedFavoriteToggleIsReportedAsAFailureNeverAsSuccess() {
        assertEquals("收藏失败", favoriteToggleMessage(ok = false, isFavorite = false))
        assertEquals("收藏失败", favoriteToggleMessage(ok = false, isFavorite = true))
        assertEquals("已收藏", favoriteToggleMessage(ok = true, isFavorite = true))
        assertEquals("已取消收藏", favoriteToggleMessage(ok = true, isFavorite = false))
    }

    // ------------------------------------------------------------------
    // 2. 「哪张封面赢」
    // ------------------------------------------------------------------

    @Test
    fun theLocalCustomCoverWinsOverTheRemoteUrl() {
        val song = kugouSong(700_001L)
        assertEquals("没有自定义封面时必须用远程地址", song.coverURL, songCoverModel(song, emptyMap()))
        assertEquals(
            "有自定义封面时必须用本机文件",
            "/data/user/0/com.lulu.music/files/covers/a.jpg",
            songCoverModel(song, mapOf(song.identityKey to "/data/user/0/com.lulu.music/files/covers/a.jpg")),
        )
        assertEquals(
            "空白路径不算自定义封面",
            song.coverURL,
            songCoverModel(song, mapOf(song.identityKey to "   ")),
        )
        assertNull("没有歌曲时没有封面", songCoverModel(null, mapOf("kugou-1" to "/x.jpg")))
    }

    @Test
    fun theCustomCoverOnlyCountsForItsOwnSong() {
        val song = kugouSong(700_002L)
        val other = kugouSong(700_003L)
        val covers = mapOf(other.identityKey to "/data/covers/other.jpg")

        assertNull(songCustomCoverModel(song, covers))
        assertEquals("/data/covers/other.jpg", songCustomCoverModel(other, covers))
        assertEquals(song.coverURL, songCoverModel(song, covers))
    }

    @Test
    fun theCoilModelForALocalCoverIsAFileSoTheCacheKeyFollowsLastModified() {
        val song = kugouSong(700_004L)
        val path = "/data/user/0/com.lulu.music/files/covers/b.jpg"
        val model = songCoverCoilModel(song, mapOf(song.identityKey to path))

        assertTrue("本机封面必须交给 Coil 一个 File，实际是 ${model?.javaClass?.name}", model is File)
        // 用 File 相等而不是字符串相等：Windows 上 File.path 会把 `/` 规范化成 `\`。
        assertEquals("File 必须指向封面文件本身", File(path), model)
        assertEquals(
            "没有本机封面时仍然用远程地址字符串",
            song.coverURL,
            songCoverCoilModel(song, emptyMap()),
        )
    }

    @Test
    fun replacingACoverForTheSameSongStillBumpsTheRevision() {
        val song = kugouSong(700_005L)
        val before = CoverStore.revision.value

        assertTrue(CoverStore.setCoverFromBytes(song, byteArrayOf(1, 2, 3)))
        val afterFirst = CoverStore.revision.value
        assertTrue("第一次设置封面必须让版本号自增", afterFirst > before)

        // 换一张：文件名（identityKey 的 sha1）不变 → `covers` 这个 Map 的内容完全一样，
        // 只有版本号能告诉 UI「内容变了」。
        val pathBefore = CoverStore.coverPath(song)
        assertTrue(CoverStore.setCoverFromBytes(song, byteArrayOf(9, 9, 9, 9)))
        assertEquals("封面文件路径必须是固定的（换封面 = 覆盖同一个文件）", pathBefore, CoverStore.coverPath(song))
        assertTrue(
            "换封面必须让版本号再次自增，否则 StateFlow 不发新值、UI 不会重组",
            CoverStore.revision.value > afterFirst,
        )
    }

    // ------------------------------------------------------------------
    // 3. 相册选图的结果判定
    // ------------------------------------------------------------------

    @Test
    fun cancellingTheImagePickerNeverTouchesTheStoreAndSaysNothing() {
        var applied = 0
        val uri = Uri.parse("content://media/picker/1")

        assertEquals(
            CoverPickResult.CANCELLED,
            coverPickResult(null) {
                applied++
                true
            },
        )
        assertEquals("用户取消时绝不能去写盘", 0, applied)
        assertNull("取消不该弹提示", coverPickMessage(CoverPickResult.CANCELLED))

        assertEquals(CoverPickResult.APPLIED, coverPickResult(uri) { true })
        assertEquals("成功必须给一句明确的提示", "已更新自定义封面", coverPickMessage(CoverPickResult.APPLIED))

        assertEquals(CoverPickResult.FAILED, coverPickResult(uri) { false })
        assertNotNull(coverPickMessage(CoverPickResult.FAILED))
        assertEquals(
            "落盘抛异常也必须被当成失败，而不是把异常抛给调用方",
            CoverPickResult.FAILED,
            coverPickResult(uri) { throw IllegalStateException("boom") },
        )
    }

    // ------------------------------------------------------------------
    // 4. 选择器：纯投影 + 写回 store
    // ------------------------------------------------------------------

    @Test
    fun thePickerProjectsWhoAlreadyContainsTheSong() {
        val song = kugouSong(710_001L)
        val playlists = listOf(
            LocalPlaylist(id = "a", name = "已包含", songs = listOf(song, kugouSong(710_002L))),
            LocalPlaylist(id = "b", name = "还没有", songs = emptyList()),
        )

        val rows = playlistPickerRows(playlists, song)
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals(listOf(2, 0), rows.map { it.songCount })
        assertEquals(listOf(true, false), rows.map { it.alreadyContains })
    }

    @Test
    fun addingToAPlaylistDistinguishesAddedFromAlreadyThereAndMissing() {
        val song = kugouSong(720_001L)
        val playlist = requireNotNull(LocalPlaylistStore.create("选择器-${unique()}"))

        assertEquals(PlaylistAddOutcome.ADDED, addSongToLocalPlaylist(playlist.id, song))
        assertEquals(listOf(song), LocalPlaylistStore.songs(playlist.id))

        assertEquals(
            "同一首歌再加一次必须如实报「已在该歌单中」",
            PlaylistAddOutcome.ALREADY_PRESENT,
            addSongToLocalPlaylist(playlist.id, song),
        )
        assertEquals("重复添加不得改变内容", listOf(song), LocalPlaylistStore.songs(playlist.id))

        assertEquals(
            "歌单不存在必须与「已在该歌单中」区分开",
            PlaylistAddOutcome.FAILED,
            addSongToLocalPlaylist("no-such-playlist", song),
        )
    }

    @Test
    fun pickerMessagesMatchTheOutcome() {
        assertEquals("已添加到「我的歌单」", playlistAddMessage("我的歌单", PlaylistAddOutcome.ADDED))
        assertEquals("已在该歌单中", playlistAddMessage("我的歌单", PlaylistAddOutcome.ALREADY_PRESENT))
        assertTrue(playlistAddMessage("我的歌单", PlaylistAddOutcome.FAILED).contains("失败"))
    }

    @Test
    fun pickingARowOnlyClosesThePickerWhenSomethingWasActuallyAdded() {
        val song = kugouSong(730_001L)
        val playlist = requireNotNull(LocalPlaylistStore.create("关闭语义-${unique()}"))
        val row = PlaylistPickerRow(
            id = playlist.id,
            name = playlist.name,
            songCount = 0,
            alreadyContains = false,
        )

        val first = applyPlaylistPick(row, song)
        assertEquals(PlaylistAddOutcome.ADDED, first.outcome)
        assertTrue("加入成功之后才收起选择器", first.dismiss)

        val second = applyPlaylistPick(row, song)
        assertEquals(PlaylistAddOutcome.ALREADY_PRESENT, second.outcome)
        assertFalse("命中「已在该歌单中」时必须留在选择器里，让用户看见那一行本来就打着勾", second.dismiss)
    }

    @Test
    fun creatingAPlaylistFromThePickerGoesThroughCreateWithThisSong() {
        val song = kugouSong(740_001L)
        assertNull("空白名称不能建出歌单", createLocalPlaylistWithSong("   ", song))

        val name = "新建歌单-${unique()}"
        val created = requireNotNull(createLocalPlaylistWithSong("  $name  ", song))
        assertEquals("名称必须去空白后落库", name, created.name)
        assertEquals(listOf(song), created.songs)
        assertEquals("必须真的落进 store", listOf(song), LocalPlaylistStore.songs(created.id))
        assertTrue("提示里必须带上歌单名", createdPlaylistMessage(created).contains(name))
    }
}

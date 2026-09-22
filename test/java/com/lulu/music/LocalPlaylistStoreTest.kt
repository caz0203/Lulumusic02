package com.lulu.music

import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.store.LocalPlaylistStore
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
 * [LocalPlaylistStore] 的行为测试 —— 「本地歌单是真的落盘，不再随页面 / 进程消失」的证明。
 *
 * ## 为什么断言都写成「相对当前状态」
 *
 * [LocalPlaylistStore] 是 JVM 单例、数据放在**进程级** `SharedPreferences`（[com.lulu.music.data.store.Prefs]）
 * 里，而 Robolectric 给每个测试方法一个全新的 `filesDir`：内存状态跨方法保留、磁盘目录换新。
 * 因此：
 *  - 每个歌单名 / id 都带一个唯一后缀，不会和别的用例撞；
 *  - 「重载 / 重启」用 [LocalPlaylistStore.resetForTests] 模拟（丢掉内存状态后重新读盘）；
 *  - 断言只关心本用例自己造出来的那些歌单。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalPlaylistStoreTest {

    private var seq = 0

    @Before
    fun bootApplication() {
        // 触发 Application.onCreate（LocalPlaylistStore.load() 在这里被调用一次），
        // 然后主动重载一次：让每个测试方法都从「当前磁盘状态」开始。
        ApplicationProvider.getApplicationContext<BeansApplication>()
        LocalPlaylistStore.resetForTests()
    }

    private fun unique(): String = "${System.nanoTime()}-${seq++}"

    private fun kugouSong(id: Long, name: String = "song-$id") = Song(
        id = id,
        name = name,
        artists = "歌手",
        album = "专辑",
        source = SongSource.KUGOU,
        kugouHash = "HASH-$id",
    )

    private fun newPlaylist(songs: List<Song> = emptyList()): com.lulu.music.data.store.LocalPlaylist {
        val name = "歌单-${unique()}"
        return requireNotNull(LocalPlaylistStore.create(name, songs)) { "create(合法名称) 不能返回 null" }
    }

    // ------------------------------------------------------------------
    // 持久化：这就是「进程重启后歌单还在」的证明
    // ------------------------------------------------------------------

    @Test
    fun aCreatedPlaylistSurvivesAStoreReload() {
        val song = kugouSong(810_001L)
        val created = newPlaylist(listOf(song))
        assertNotNull("前置条件：创建后立刻可查", LocalPlaylistStore.playlist(created.id))

        // 丢掉内存状态、重新读盘 —— 等价于「杀进程后再启动」。
        LocalPlaylistStore.resetForTests()

        val restored = LocalPlaylistStore.playlist(created.id)
        assertNotNull("重载之后歌单必须还在（否则又是页面内存状态）", restored)
        requireNotNull(restored)
        assertEquals("名称必须落盘", created.name, restored.name)
        assertEquals("歌曲必须落盘", listOf(song), restored.songs)
        assertEquals("id 必须保持不变", created.id, restored.id)
    }

    @Test
    fun aRenameSurvivesAStoreReload() {
        val playlist = newPlaylist()
        val renamed = "改名后的歌单-${unique()}"
        assertTrue(LocalPlaylistStore.rename(playlist.id, renamed))

        LocalPlaylistStore.resetForTests()

        assertEquals(renamed, LocalPlaylistStore.playlist(playlist.id)?.name)
    }

    @Test
    fun aDeletedPlaylistDoesNotComeBackAfterAReload() {
        val playlist = newPlaylist()
        assertTrue("删除存在歌单必须返回 true", LocalPlaylistStore.delete(playlist.id))
        assertNull("删除后立刻不可查", LocalPlaylistStore.playlist(playlist.id))

        LocalPlaylistStore.resetForTests()

        assertNull("删除必须落盘：重载之后不能复活", LocalPlaylistStore.playlist(playlist.id))
        assertFalse("重复删除必须返回 false", LocalPlaylistStore.delete(playlist.id))
    }

    // ------------------------------------------------------------------
    // 增删改：去重 / 重命名 / 删除 / 排序
    // ------------------------------------------------------------------

    @Test
    fun addSongsDeduplicatesByldentityKey() {
        val a = kugouSong(820_001L)
        val b = kugouSong(820_002L)
        val c = kugouSong(820_003L)
        val playlist = newPlaylist()

        assertEquals("第一次加两首：两首都进", 2, LocalPlaylistStore.addSongs(playlist.id, listOf(a, b)))
        assertEquals("再加重叠的一首 + 新的一首：只新增 1 首", 1, LocalPlaylistStore.addSongs(playlist.id, listOf(a, c)))
        assertEquals("顺序必须是先来的在前", listOf(a, b, c), LocalPlaylistStore.songs(playlist.id))
        assertEquals("全是重复时返回 0", 0, LocalPlaylistStore.addSongs(playlist.id, listOf(a, b, c)))
        assertEquals("重复添加不能改变内容", listOf(a, b, c), LocalPlaylistStore.songs(playlist.id))

        // 同一首歌的「另一份元数据」也不能重复加入（身份判据是 identityKey，不是对象相等）。
        assertEquals(
            "同 identityKey 不同元数据必须被判为重复",
            0,
            LocalPlaylistStore.addSongs(playlist.id, listOf(a.copy(name = "改过名字", album = "改过专辑"))),
        )
        assertEquals(listOf(a, b, c), LocalPlaylistStore.songs(playlist.id))
    }

    @Test
    fun addSongsToAMissingPlaylistIsANoOpThatReturnsZero() {
        assertEquals(0, LocalPlaylistStore.addSongs("no-such-playlist", listOf(kugouSong(821_001L))))
        assertEquals(0, LocalPlaylistStore.addSongs("no-such-playlist", emptyList()))
    }

    @Test
    fun createRejectsABlankName() {
        assertNull("全空白名称必须被拒绝", LocalPlaylistStore.create("   "))
        assertNull(LocalPlaylistStore.create(""))
        // 合法名称必须创建成功，并去掉首尾空白。
        val raw = "  带空白的名字-${unique()}  "
        val created = requireNotNull(LocalPlaylistStore.create(raw))
        assertEquals("创建时必须把首尾空白去掉", raw.trim(), created.name)
        assertEquals("落盘之后名字也要是去空白的那份", raw.trim(), LocalPlaylistStore.playlist(created.id)?.name)
    }

    @Test
    fun renameRejectsBlankNamesAndUnknownIds() {
        val playlist = newPlaylist()
        val originalName = playlist.name

        assertTrue(LocalPlaylistStore.rename(playlist.id, "  ${originalName}2  "))
        assertEquals("重命名必须去空白后落库", "${originalName}2", LocalPlaylistStore.playlist(playlist.id)?.name)

        assertFalse("空名称必须被拒绝", LocalPlaylistStore.rename(playlist.id, "   "))
        assertEquals("被拒绝的重命名不得改动名称", "${originalName}2", LocalPlaylistStore.playlist(playlist.id)?.name)

        assertFalse("不存在的 id 必须返回 false", LocalPlaylistStore.rename("no-such-id", "x"))
    }

    @Test
    fun removeSongDropsTheSongByldentityKey() {
        val a = kugouSong(830_001L)
        val b = kugouSong(830_002L)
        val playlist = newPlaylist(listOf(a, b))

        assertTrue(LocalPlaylistStore.removeSong(playlist.id, a))

        assertEquals(listOf(b), LocalPlaylistStore.songs(playlist.id))
        assertFalse("再移除同一首必须返回 false", LocalPlaylistStore.removeSong(playlist.id, a))
        assertFalse("移除不在歌单里的歌必须返回 false", LocalPlaylistStore.removeSong(playlist.id, kugouSong(830_003L)))
        assertFalse("移除不存在的歌单必须返回 false", LocalPlaylistStore.removeSong("no-such-id", b))
        assertEquals("失败的移除不得改动歌单", listOf(b), LocalPlaylistStore.songs(playlist.id))
    }

    @Test
    fun movePlaylistReordersWithTheSameSemanticsAsTheOrderDialog() {
        val first = newPlaylist()
        val second = newPlaylist()

        val before = LocalPlaylistStore.playlists.value.map { it.id }
        val from = before.indexOf(first.id)
        val to = before.indexOf(second.id)
        assertTrue("前置条件：两个歌单都存在", from >= 0 && to >= 0)
        assertEquals("前置条件：连续创建的两个歌单相邻", from + 1, to)

        assertTrue(LocalPlaylistStore.movePlaylist(from, to))

        val after = LocalPlaylistStore.playlists.value.map { it.id }
        assertEquals("排序不得增加 / 删除条目", before.size, after.size)
        assertEquals("下移一位：原来的第二个落到 from", second.id, after[from])
        assertEquals("下移一位：原来的第一个落到 to", first.id, after[to])

        // 原地 / 越界都是 no-op。
        val snapshot = LocalPlaylistStore.playlists.value.map { it.id }
        assertFalse(LocalPlaylistStore.movePlaylist(from, from))
        assertFalse(LocalPlaylistStore.movePlaylist(-1, 1))
        assertFalse(LocalPlaylistStore.movePlaylist(0, 999))
        assertEquals("no-op 之后顺序必须完全不变", snapshot, LocalPlaylistStore.playlists.value.map { it.id })
    }

    // ------------------------------------------------------------------
    // 备份：JSON 导出 / 导入
    // ------------------------------------------------------------------

    @Test
    fun exportImportRoundTripsEveryField() {
        val a = kugouSong(840_001L)
        val b = kugouSong(840_002L)
        val playlist = newPlaylist(listOf(a, b))

        val json = LocalPlaylistStore.exportJson()
        assertTrue("导出不能是空串", json.isNotBlank())
        assertTrue("导出的 JSON 必须带上歌单 id", json.contains(playlist.id))
        // identityKey 是 Song 的**计算属性**（不参与序列化），所以这里断言落盘的是它的两个来源字段。
        assertTrue("导出的 JSON 必须带上歌曲 id", json.contains("\"id\":${a.id}"))
        assertTrue(
            "导出的 JSON 必须带上歌曲来源（kotlinx.serialization 枚举按名字落盘）",
            json.contains("\"source\":\"KUGOU\""),
        )

        // 破坏现场：整体替换成空。
        assertTrue(LocalPlaylistStore.importJson("[]"))
        assertNull(LocalPlaylistStore.playlist(playlist.id))
        assertTrue("空导入必须真的清空", LocalPlaylistStore.playlists.value.isEmpty())

        // 导回去：字段必须完全一致。
        assertTrue("导入自己导出的 JSON 必须成功", LocalPlaylistStore.importJson(json))
        val restored = LocalPlaylistStore.playlist(playlist.id)
        assertNotNull(restored)
        requireNotNull(restored)
        assertEquals(playlist.name, restored.name)
        assertEquals(listOf(a, b), restored.songs)
        assertEquals(playlist.createdAt, restored.createdAt)
    }

    @Test
    fun importJsonRejectsGarbageWithoutTouchingCurrentData() {
        val playlist = newPlaylist(listOf(kugouSong(850_001L)))

        for (bad in listOf("", "not json at all", "{", "{}", """{"id":"x"}""", "[1,2,3]")) {
            assertFalse("「${bad.take(20)}」必须被拒绝", LocalPlaylistStore.importJson(bad))
        }

        assertNotNull("失败的导入不得清掉现有歌单", LocalPlaylistStore.playlist(playlist.id))
        assertEquals(1, LocalPlaylistStore.songs(playlist.id).size)
    }

    @Test
    fun importJsonCleansUpDuplicateIdsAndDuplicateSongs() {
        val song = kugouSong(860_001L)
        val document = """
            [
              {"id":"dup-1","name":"第一","songs":[${songJson(song)},${songJson(song)}]},
              {"id":"dup-1","name":"重复 id 的第二份","songs":[]},
              {"id":"","name":"缺 id 的一份","songs":[]}
            ]
        """.trimIndent()

        assertTrue(LocalPlaylistStore.importJson(document))

        val playlists = LocalPlaylistStore.playlists.value
        assertEquals("重复 id 只保留第一份；缺 id 的会被补上新 id", 2, playlists.size)
        assertEquals("同一首歌不能在一个歌单里出现两次", 1, playlists.first().songs.size)
        assertEquals("song identityKey 必须原样保留", song.identityKey, playlists.first().songs.first().identityKey)
        assertTrue("补出来的 id 不能为空", playlists[1].id.isNotBlank())
    }

    private fun songJson(song: Song): String =
        com.lulu.music.data.store.Prefs.json.encodeToString(Song.serializer(), song)
}

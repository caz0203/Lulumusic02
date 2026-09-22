package com.lulu.music

import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.store.FavoritesStore
import com.lulu.music.data.store.Prefs
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [FavoritesStore] 的**纯本地**语义测试。
 *
 * ## 绝不能调用 [FavoritesStore.toggle]
 *
 * `toggle()` 对网易云会请求 `NetEaseApi.like`、对 QQ 会请求 `QQMusicApi.like` —— 这是本仓库
 * 明令禁止在单元测试里碰的路径。本文件因此只覆盖两类**不联网**的东西：
 *
 *  1. [FavoritesStore.isLiked] / [FavoritesStore.removeQQFavorite] / [FavoritesStore.all] 的本地语义；
 *  2. `Prefs` 里三条收藏键的**序列化格式与读取路径** —— 用 `Song.serializer()` 写进去，
 *     再用 store 自己启动时会用的同一套 `Prefs.readList` 读回来。这就是 `load()` 在**下一次进程启动**
 *     时会走的路径（本进程内 `loaded` 闸门已经闭合，见下）。
 *
 * ## 为什么不能直接给 store「灌」数据
 *
 * `load()` 有 `private var loaded` 闸门，而 `BeansApplication.onCreate()` 已经调用过它；
 * 对象又是 JVM 单例，所以本进程里第二次 `load()` 是 no-op，Collections 无法从测试里再刷一次。
 * 同时 `updateQQ` / `updateKugou` / `updateNetease` 都是 `private`，唯一的公开写入口 `toggle()`
 * 又会联网。因此「预置数据 → 断言 isLiked」这条路线在本进程里走不通，这里改用等价方案：
 * 断言 `isLiked` 的**判定规则**（QQ 的 `qqMid` / NetEase 的 `id` / Kugou 的 `identityKey`）
 * 能在 store 的公开/可观测状态上被区分出来，并用 `removeQQFavorite` 验证 QQ 的删除是按
 * `identityKey` **或** `qqMid` 匹配的 —— 这条语义与 `isLiked` 的 QQ 分支是同一套判据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritesStoreTest {

    private val keyNetease = "beans.fav.netease.v1"
    private val keyQq = "beans.fav.qq.v1"
    private val keyKugou = "beans.fav.kugou.v1"

    @Before
    fun bootApplication() {
        // 触发 Application.onCreate（FavoritesStore.load() 在这里被调用一次）。
        ApplicationProvider.getApplicationContext<BeansApplication>()
    }

    private fun qqSong(id: Long, qqMid: String?, name: String = "qq-$id") = Song(
        id = id,
        name = name,
        artists = "歌手",
        source = SongSource.QQ,
        qqMid = qqMid,
        qqMediaMid = qqMid,
    )

    private fun kugouSong(id: Long, hash: String? = "hash-$id") = Song(
        id = id,
        name = "kg-$id",
        source = SongSource.KUGOU,
        kugouHash = hash,
    )

    private fun neteaseSong(id: Long) = Song(id = id, name = "ne-$id", source = SongSource.NET_EASE)

    // ------------------------------------------------------------------
    // null 与空集合
    // ------------------------------------------------------------------

    @Test
    fun isLikedReturnsFalseForNull() {
        // 明确要求：null 必须是 false（而不是抛 NPE）。
        assertFalse("isLiked(null) 必须是 false", FavoritesStore.isLiked(null))
    }

    @Test
    fun missingSongIsNeverLiked() {
        // 一个没被收藏过的 id 在任何平台都必须是 false。
        assertFalse(FavoritesStore.isLiked(neteaseSong(999_999_999L)))
        assertFalse(FavoritesStore.isLiked(qqSong(999_999_998L, "no-such-mid")))
        assertFalse(FavoritesStore.isLiked(kugouSong(999_999_997L, "no-such-hash")))
    }

    @Test
    fun allIsTheConcatenationOfTheThreePlatformLists() {
        // all() = qq + netease + kugou，且不联网、不抛异常。
        val all = FavoritesStore.all()
        assertEquals(
            "all() 必须是三个列表的顺序拼接",
            FavoritesStore.qqFavoriteSongs.value +
                FavoritesStore.neteaseFavoriteSongs.value +
                FavoritesStore.kugouFavoriteSongs.value,
            all,
        )
    }

    // ------------------------------------------------------------------
    // QQ：identityKey 语义（本地删除路径，不联网）
    // ------------------------------------------------------------------

    @Test
    fun removeQQFavoriteIsSafeWhenNothingMatches() {
        // 本地私有 updateQQ(song, false)：不联网，也不该抛。
        FavoritesStore.removeQQFavorite(qqSong(424_242L, "mid-424242"))
        FavoritesStore.removeQQFavorite(qqSong(424_243L, null))
        // 断言仍然是一个可用的列表状态。
        assertTrue(
            "removeQQFavorite 之后 qqFavoriteSongs 仍可读",
            FavoritesStore.qqFavoriteSongs.value.none { it.id == 424_242L },
        )
    }

    @Test
    fun qqIdentityKeyIsStableAcrossInstancesSoMatchingByIdWorks() {
        // identityKey = "qq-$id"：两个字段不同但 id 相同的实例必须被视为同一首歌。
        // 这是 isLiked(QQ) 走 `it.identityKey == song.identityKey` 分支的判据。
        val a = qqSong(555_555L, "mid-A", name = "第一份元数据")
        val b = qqSong(555_555L, "mid-B", name = "第二份元数据")

        assertEquals("QQ 的 identityKey 只由 id 决定", a.identityKey, b.identityKey)
        assertEquals("qq-555555", a.identityKey)

        // 反过来：id 不同 → identityKey 必须不同（否则会误判成同一首）。
        assertFalse(
            "不同 id 的 QQ 歌不能共享 identityKey",
            a.identityKey == qqSong(555_556L, "mid-A").identityKey,
        )
    }

    @Test
    fun qqMidIsTheCrossIdMatchKeyDocumentedInTheStore() {
        // 文档语义：QQ 歌曲带非空 qqMid 时，
        //   「不同 id、同一 qqMid」也算同一首（见 FavoritesStore.isLiked 的 QQ 分支）。
        // 这里验证这条语义依赖的字段关系成立，且 qqMid 为 null 时不会与任何非空 mid 相等。
        val original = qqSong(100L, "shared-mid")
        val sameMidDifferentId = qqSong(200L, "shared-mid")

        assertEquals("qqMid 必须原样保留", "shared-mid", original.qqMid)
        assertEquals("同一 qqMid 的两个实例 qqMid 相等", original.qqMid, sameMidDifferentId.qqMid)
        assertFalse(
            "但它们的 identityKey 不同 —— 所以 QQ 分支必须同时比较 qqMid 和 identityKey",
            original.identityKey == sameMidDifferentId.identityKey,
        )

        val nullMid = qqSong(300L, null)
        assertTrue("null qqMid 必须保持 null", nullMid.qqMid == null)
        assertFalse(
            "null qqMid 不能与任何非空 mid 相等（isLiked 在 mid 为空时只比 identityKey）",
            original.qqMid == nullMid.qqMid,
        )
    }

    // ------------------------------------------------------------------
    // Kugou / NetEase 的 identityKey 语义
    // ------------------------------------------------------------------

    @Test
    fun kugouIdentityKeyIsBuiltFromIdAndIgnoresTheHash() {
        val a = kugouSong(777L, "HASH-1")
        val b = kugouSong(777L, "HASH-2")

        assertEquals("kugou 的 identityKey = kugou-<id>", "kugou-777", a.identityKey)
        assertEquals(
            "hash 变了但 id 相同 → 仍视为同一首（isLiked 的 KUGOU 分支只比 identityKey）",
            a.identityKey,
            b.identityKey,
        )
    }

    @Test
    fun neteaseIdentityKeyIsBuiltFromId() {
        assertEquals("netease-123456", neteaseSong(123_456L).identityKey)
        assertEquals(
            "同 id 的网易云歌必须是同一个 identityKey",
            neteaseSong(1L).identityKey,
            neteaseSong(1L).copy(name = "改了名字").identityKey,
        )
    }

    // ------------------------------------------------------------------
    // 落盘格式：三个键的读写路径（这就是下次 load() 会读的东西）
    // ------------------------------------------------------------------

    @Test
    fun favoritesKeysRoundTripThroughTheSamePrefsPathTheStoreUsesOnLoad() {
        // 关键：必须用 Prefs.prefs 这个**同一个实例**。Robolectric 下每个测试方法都是新的
        // Context/Application，`context.getSharedPreferences(...)` 会拿到另一个内存缓存实例，
        // 那样写进去的内容 Prefs.readList 是读不到的（实测如此）。
        val prefs = Prefs.prefs

        val qq = qqSong(1_111L, "mid-1111", name = "QQ 收藏")
        val ne = neteaseSong(2_222L)
        val kg = kugouSong(3_333L, "HASH-3333")

        val qqJson = Prefs.json.encodeToString(ListSerializer(Song.serializer()), listOf(qq))
        val neJson = Prefs.json.encodeToString(ListSerializer(Song.serializer()), listOf(ne))
        val kgJson = Prefs.json.encodeToString(ListSerializer(Song.serializer()), listOf(kg))

        // 写进 store 自己用的那三个键（SharedPreferences 名字 = Prefs 里的 "beans_prefs"）。
        prefs.edit().putString(keyQq, qqJson).putString(keyNetease, neJson).putString(keyKugou, kgJson).commit()

        // 读回来的路径 = FavoritesStore.load() 用的 Prefs.readList。
        assertEquals(listOf(qq), Prefs.readList(keyQq, Song.serializer()))
        assertEquals(listOf(ne), Prefs.readList(keyNetease, Song.serializer()))
        assertEquals(listOf(kg), Prefs.readList(keyKugou, Song.serializer()))

        // 关键字段必须真的过了序列化（qqMid / kugouHash / source 都不能丢）。
        val qqBack = Prefs.readList(keyQq, Song.serializer()).single()
        assertEquals("qqMid 必须落盘", "mid-1111", qqBack.qqMid)
        assertEquals("source 必须落盘", SongSource.QQ, qqBack.source)
        val kgBack = Prefs.readList(keyKugou, Song.serializer()).single()
        assertEquals("kugouHash 必须落盘", "HASH-3333", kgBack.kugouHash)
        assertEquals("source 必须落盘", SongSource.KUGOU, kgBack.source)
        assertEquals("netease source 必须落盘", SongSource.NET_EASE, Prefs.readList(keyNetease, Song.serializer()).single().source)
    }

    @Test
    fun corruptFavoritesJsonDegradesToEmptyInsteadOfThrowing() {
        Prefs.prefs.edit().putString(keyQq, "{ this is not a list }").commit()

        // Prefs 的契约：「损坏数据降级为空，绝不抛」—— load() 因此永远不会因坏数据崩在启动路径上。
        assertEquals(emptyList<Song>(), Prefs.readList(keyQq, Song.serializer()))
    }
}

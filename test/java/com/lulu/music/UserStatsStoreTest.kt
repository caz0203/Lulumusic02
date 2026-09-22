package com.lulu.music

import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.stats.UserStatsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [UserStatsStore] 的运行时行为测试 —— 「卸载重装后用 6 位 ID 找回统计」这条用户路径的底座。
 *
 * 为什么这个文件价值最高：[UserStatsStore] 是 JVM 单例 + SharedPreferences，
 * `load()` 只在进程内第一次被调用时读盘（`private var loaded`）。所以这里的每个用例都**不依赖执行顺序**：
 *
 *  - 计数类断言全部写成「相对当前值 +1 / +N」，而不是绝对值；
 *  - 用户 ID 类断言先记下 [before] 再比较；
 *  - [clearAndRoundTripThroughExportImport] 自己把状态建成确定状态（clear → recordPlay → …）。
 *
 * 每个测试方法拿到的是**全新的 filesDir**（Robolectric 沙箱），而单例的内存状态跨方法保留 ——
 * 这两条合起来就是「不能假设磁盘干净」的原因，也是上面那套写法的由来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserStatsStoreTest {

    /** 6 位数字 ID 的格式（对齐 `isValidUserId`）。 */
    private val sixDigits = Regex("^\\d{6}$")

    private lateinit var before: String

    @Before
    fun seedDeterministicState() {
        // 触发 Application.onCreate（各 store 初始化），并把统计清成确定状态。
        ApplicationProvider.getApplicationContext<BeansApplication>()
        before = UserStatsStore.userId
    }

    private fun kugouSong(id: Long, name: String = "song-$id") = Song(
        id = id,
        name = name,
        artists = "artist",
        source = SongSource.KUGOU,
        kugouHash = "hash-$id",
    )

    private fun neteaseSong(id: Long) = Song(
        id = id,
        name = "ne-$id",
        artists = "artist",
        source = SongSource.NET_EASE,
    )

    // ------------------------------------------------------------------
    // 用户 ID：格式 + 稳定性
    // ------------------------------------------------------------------

    @Test
    fun userIdIsAlwaysSixDigitsAndStableAcrossReadsAndLoad() {
        val first = UserStatsStore.userId
        val second = UserStatsStore.userId

        assertTrue("userId 必须是 6 位数字，实际 \"$first\"", sixDigits.matches(first))
        assertEquals("同一进程内连续读取必须完全一致", first, second)

        // load() 是幂等的：重复调用**不得**重新生成 / 重新读盘把 ID 换掉。
        UserStatsStore.load()
        UserStatsStore.load()
        assertEquals("重复 load() 之后 userId 必须原样保留", first, UserStatsStore.userId)

        // stats 属性自带 load()，读到的必须还是同一个 ID。
        assertEquals("stats.value.userId 与 userId 必须一致", first, UserStatsStore.stats.value.userId)
    }

    @Test
    fun idIsGeneratedFromTheNoLeadingZeroRange() {
        // generateUserId() = 100000..999999：刻意避开前导 0（用户手抄时不会丢 0）。
        // 这是文档写明的约定，只有「本机自己生成」的 ID 才受此约束。
        val stats = UserStatsStore.stats.value
        val id = stats.userId
        assertTrue("6 位数字：$id", sixDigits.matches(id))
        assertTrue("随机生成的 ID 不应小于 100000（当前 $id）", id.toInt() >= 100_000)
        assertTrue("随机生成的 ID 不应大于 999999（当前 $id）", id.toInt() <= 999_999)
    }

    // ------------------------------------------------------------------
    // 计数
    // ------------------------------------------------------------------

    @Test
    fun recordPlayIncrementsPlayCountAndPerSongCountByExactlyOne() {
        val song = kugouSong(7_001L)
        val startCount = UserStatsStore.stats.value.playCount
        val startSong = UserStatsStore.stats.value.songPlayCounts[song.identityKey] ?: 0

        UserStatsStore.recordPlay(song)

        val after = UserStatsStore.stats.value
        assertEquals("playCount 必须 +1", startCount + 1, after.playCount)
        assertEquals(
            "songPlayCounts[identityKey] 必须 +1",
            startSong + 1,
            after.songPlayCounts[song.identityKey],
        )
        assertTrue("createdAt 必须被填成一个正的时间戳", after.createdAt > 0L)
        assertTrue("updatedAt 必须被填成一个正的时间戳", after.updatedAt > 0L)
    }

    @Test
    fun recordingTheSameSongTwiceGivesTwo() {
        val song = kugouSong(7_002L)
        val base = UserStatsStore.stats.value.songPlayCounts[song.identityKey] ?: 0

        UserStatsStore.recordPlay(song)
        assertEquals(base + 1, UserStatsStore.stats.value.songPlayCounts[song.identityKey])

        UserStatsStore.recordPlay(song)
        assertEquals(
            "同一首歌播两次，该歌的计数必须是 2（相对基线 +2）",
            base + 2,
            UserStatsStore.stats.value.songPlayCounts[song.identityKey],
        )
    }

    @Test
    fun differentSongsAreCountedUnderDifferentKeys() {
        val a = kugouSong(7_003L)
        val b = neteaseSong(7_004L)
        val baseA = UserStatsStore.stats.value.songPlayCounts[a.identityKey] ?: 0
        val baseB = UserStatsStore.stats.value.songPlayCounts[b.identityKey] ?: 0
        assertNotEquals("两个 identityKey 必须不同", a.identityKey, b.identityKey)

        UserStatsStore.recordPlay(a)
        UserStatsStore.recordPlay(a)
        UserStatsStore.recordPlay(b)

        val counts = UserStatsStore.stats.value.songPlayCounts
        assertEquals(baseA + 2, counts[a.identityKey])
        assertEquals(baseB + 1, counts[b.identityKey])
    }

    @Test
    fun addListeningSecondsAccumulatesAndIgnoresNonPositive() {
        val start = UserStatsStore.stats.value.listeningSeconds

        UserStatsStore.addListeningSeconds(3L)
        assertEquals(start + 3L, UserStatsStore.stats.value.listeningSeconds)

        UserStatsStore.addListeningSeconds(11L)
        assertEquals("累加而不是覆盖", start + 14L, UserStatsStore.stats.value.listeningSeconds)

        UserStatsStore.addListeningSeconds(0L)
        UserStatsStore.addListeningSeconds(-9L)
        assertEquals("非正数必须完全忽略", start + 14L, UserStatsStore.stats.value.listeningSeconds)
    }

    // ------------------------------------------------------------------
    // 备份 / 恢复：exportJson → clear → importJson
    // ------------------------------------------------------------------

    @Test
    fun clearAndRoundTripThroughExportImport() {
        // 1) 先把状态做成确定的：清空 → 播两首 → 加时长。
        UserStatsStore.clear()
        val cleared = UserStatsStore.stats.value
        assertTrue("clear() 之后 ID 仍必须是合法 6 位数字", sixDigits.matches(cleared.userId))
        assertEquals("clear() 必须把 playCount 归零", 0, cleared.playCount)
        assertEquals("clear() 必须把 listeningSeconds 归零", 0L, cleared.listeningSeconds)
        assertTrue("clear() 必须清空 songPlayCounts", cleared.songPlayCounts.isEmpty())
        assertTrue(
            "clear() 必须打上「已清空」标记（本地状态需要区分「从未记录」和「主动清空」）",
            UserStatsStore.isResetPending,
        )

        val songA = kugouSong(8_001L)
        val songB = neteaseSong(8_002L)
        UserStatsStore.recordPlay(songA)
        UserStatsStore.recordPlay(songA)
        UserStatsStore.recordPlay(songB)
        UserStatsStore.addListeningSeconds(42L)

        val exportedStats = UserStatsStore.stats.value
        assertEquals(3, exportedStats.playCount)
        assertEquals(42L, exportedStats.listeningSeconds)
        assertEquals(2, exportedStats.songPlayCounts[songA.identityKey])
        assertEquals(1, exportedStats.songPlayCounts[songB.identityKey])

        val json = UserStatsStore.exportJson()
        assertTrue("exportJson() 不能返回空串", json.isNotBlank())
        assertTrue("导出的 JSON 里必须带上 songA 的 identityKey", json.contains(songA.identityKey))

        // 2) 破坏现场：清空。
        UserStatsStore.clear()
        assertEquals("clear() 之后 playCount 必须是 0", 0, UserStatsStore.stats.value.playCount)
        assertEquals("clear() 之后 listeningSeconds 必须是 0", 0L, UserStatsStore.stats.value.listeningSeconds)
        assertTrue("clear() 之后 songPlayCounts 必须为空", UserStatsStore.stats.value.songPlayCounts.isEmpty())

        // 3) 导入刚才那份备份：数值必须**完全**回到导出时的样子。
        val ok = UserStatsStore.importJson(json)
        assertTrue("importJson(合法备份) 必须返回 true", ok)

        val restored = UserStatsStore.stats.value
        assertEquals("恢复后 userId", exportedStats.userId, restored.userId)
        assertEquals("恢复后 playCount", exportedStats.playCount, restored.playCount)
        assertEquals("恢复后 listeningSeconds", exportedStats.listeningSeconds, restored.listeningSeconds)
        assertEquals(
            "恢复后 songA 的单曲计数",
            exportedStats.songPlayCounts[songA.identityKey],
            restored.songPlayCounts[songA.identityKey],
        )
        assertEquals(
            "恢复后 songB 的单曲计数",
            exportedStats.songPlayCounts[songB.identityKey],
            restored.songPlayCounts[songB.identityKey],
        )
        assertEquals(
            "恢复后 songPlayCounts 整体必须一致",
            exportedStats.songPlayCounts,
            restored.songPlayCounts,
        )
    }

    @Test
    fun importJsonAcceptsALegalUserIdFromTheBackup() {
        val json = UserStatsStore.stats.value.let { current ->
            """{"userId":"424242","listeningSeconds":${current.listeningSeconds},""" +
                """"playCount":${current.playCount},"songPlayCounts":{},"createdAt":1,"updatedAt":1}"""
        }

        assertTrue("合法 6 位 ID 的备份必须能导入", UserStatsStore.importJson(json))
        // 之后再恢复回原来的 ID，避免影响同进程里的其它用例。
        UserStatsStore.adoptUserId(before)
        assertEquals(before, UserStatsStore.userId)
    }

    @Test
    fun importJsonOfGarbageReturnsFalseAndDoesNotThrow() {
        val playCountBefore = UserStatsStore.stats.value.playCount
        val idBefore = UserStatsStore.userId

        assertFalse("非 JSON 必须返回 false", UserStatsStore.importJson("not json"))
        assertFalse("空串必须返回 false", UserStatsStore.importJson(""))
        assertFalse("合法 JSON 但不是 UserStats 必须返回 false", UserStatsStore.importJson("""{"a":1}"""))
        assertFalse("数组也不是 UserStats", UserStatsStore.importJson("[]"))
        // 缺 userId 的 UserStats 形状 → 反序列化就失败。
        assertFalse(
            "缺 userId 字段必须返回 false",
            UserStatsStore.importJson("""{"listeningSeconds":1,"playCount":1}"""),
        )

        assertEquals("失败导入不得改动 userId", idBefore, UserStatsStore.userId)
        assertEquals("失败导入不得改动 playCount", playCountBefore, UserStatsStore.stats.value.playCount)
    }

    // ------------------------------------------------------------------
    // adoptUserId：重装后输入已有 ID
    // ------------------------------------------------------------------

    @Test
    fun adoptUserIdAcceptsOnlySixDigitValues() {
        // 先确保当前 ID 不是 123456，这样「换成 123456」才是真的发生了变化。
        if (UserStatsStore.userId == "123456") UserStatsStore.adoptUserId("654321")
        val beforeAdopt = UserStatsStore.userId
        assertNotEquals("123456", beforeAdopt)

        assertTrue("合法 6 位数字必须被接受", UserStatsStore.adoptUserId("123456"))
        assertEquals("adoptUserId 必须真的把 ID 换过去", "123456", UserStatsStore.userId)

        // 顺带验证内部标记：用户输入的 ID 记为「已被用户确认」，而不是「本机随机生成」。
        assertFalse("用户输入的 ID 不该被标记为「本机生成」", UserStatsStore.isIdGenerated)
        assertTrue("用户输入的 ID 必须被标记为已确认", UserStatsStore.isIdVerified)

        // 再换一个合法值，确认不是只能换一次。
        assertTrue(UserStatsStore.adoptUserId("999999"))
        assertEquals("999999", UserStatsStore.userId)
    }

    @Test
    fun adoptUserIdRejectsMalformedValuesAndLeavesIdUnchanged() {
        val beforeAdopt = UserStatsStore.userId

        for (bad in listOf("abc", "12345", "1234567", "", "12 456", "12345a", "１２３４５６")) {
            assertFalse("「$bad」不是合法 6 位数字，必须返回 false", UserStatsStore.adoptUserId(bad))
            assertEquals("拒绝「$bad」之后 userId 必须原样不变", beforeAdopt, UserStatsStore.userId)
        }
    }

    @Test
    fun adoptingTheCurrentIdIsANoOpThatStillReturnsTrue() {
        val current = UserStatsStore.userId
        val playCountBefore = UserStatsStore.stats.value.playCount

        assertTrue("输入的 ID 与当前相同时文档说明直接返回 true", UserStatsStore.adoptUserId(current))
        assertEquals("ID 不变", current, UserStatsStore.userId)
        assertEquals(
            "同号 adopt 不该动计数（不能把本地统计清掉）",
            playCountBefore,
            UserStatsStore.stats.value.playCount,
        )
    }

    @Test
    fun adoptUserIdTrimsSurroundingWhitespace() {
        assertTrue("带空白的合法 ID 应被 trim 后接受", UserStatsStore.adoptUserId("  321321  "))
        assertEquals("321321", UserStatsStore.userId)
    }

    /**
     * 换 ID 不得清空本机统计。
     *
     * 云端取消之前 `adoptUserId` 会重建一份全新的 `UserStats`（计数归零），因为那时统计
     * 还能从云端重新下载。现在没有云端，归零就等于纯粹的数据丢失——用户只是填了一个 ID，
     * 不该因此丢掉听歌时长。
     */
    @Test
    fun adoptingADifferentIdPreservesLocalCounters() {
        val current = UserStatsStore.userId
        val other = if (current == "135790") "135791" else "135790"
        UserStatsStore.adoptUserId(other)
        UserStatsStore.recordPlay(kugouSong(9_002L))
        UserStatsStore.addListeningSeconds(7L)

        val before = UserStatsStore.stats.value
        assertTrue("前置条件：计数必须已经累计", before.playCount > 0 && before.listeningSeconds > 0)

        assertTrue("换 ID 必须成功", UserStatsStore.adoptUserId(current))

        val after = UserStatsStore.stats.value
        assertEquals("换 ID 之后 ID 必须变过来", current, after.userId)
        assertEquals("换 ID 不得清空播放次数", before.playCount, after.playCount)
        assertEquals("换 ID 不得清空听歌时长", before.listeningSeconds, after.listeningSeconds)
    }

    // ------------------------------------------------------------------
    // clear() 的文档化语义
    // ------------------------------------------------------------------
    @Test
    fun clearKeepsAValidUserIdWhileZeroingCounters() {
        UserStatsStore.adoptUserId("246810")
        UserStatsStore.recordPlay(kugouSong(9_001L))
        UserStatsStore.addListeningSeconds(5L)
        assertTrue("前置条件：playCount > 0", UserStatsStore.stats.value.playCount > 0)

        UserStatsStore.clear()

        val cleared = UserStatsStore.stats.value
        assertEquals("clear() 必须保留用户抄下来的 6 位 ID", "246810", cleared.userId)
        assertTrue("保留的 ID 仍必须是合法 6 位数字", sixDigits.matches(cleared.userId))
        assertEquals(0, cleared.playCount)
        assertEquals(0L, cleared.listeningSeconds)
        assertTrue(cleared.songPlayCounts.isEmpty())
    }
}

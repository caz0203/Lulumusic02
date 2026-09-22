package com.lulu.music

import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.donors.Donor
import com.lulu.music.data.donors.DonorsStore
import com.lulu.music.data.store.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DonorsStore] 的「同步 + 缓存」测试。
 *
 * ## 为什么每个用例都要自己摆好缓存
 *
 * `Prefs.prefs` 是**进程级单例**（第一次 `getSharedPreferences` 就被缓存），跨 Robolectric 的
 * 每个测试方法都不会重绑。所以这里不假设「新用例 = 空缓存」，而是每个用例显式清缓存再
 * [DonorsStore.reloadFromCache] —— 这正是「下次进程启动会读到的内容」那条路径。
 *
 * ## 断网是确定性的
 *
 * `TestBeansApplication` 会调 `Http.markOffline()`，而 [DonorsStore] 的拉取走
 * `Http.guardNetwork()`：所以下面的「拉取失败」用例不是在赌没有网络，而是**一定**失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestBeansApplication::class)
class DonorsStoreTest {

    private val goodManifest = """
        {
          "donors": [
            { "name": "甲", "amount": 10 },
            { "name": "丙", "amount": 50, "message": "加油", "date": "2025-01-12" },
            { "name": "乙", "amount": 20 }
          ]
        }
    """.trimIndent()

    private fun cached(): List<Donor> = Prefs.readList(DonorsStore.CACHE_KEY, Donor.serializer())

    @Before
    fun bootFromAnEmptyCache() {
        ApplicationProvider.getApplicationContext<BeansApplication>()
        Prefs.remove(DonorsStore.CACHE_KEY)
        DonorsStore.reloadFromCache()
        assertTrue("前置条件：缓存清空后榜单必须为空", DonorsStore.donors.value.isEmpty())
        assertTrue("前置条件：缓存清空后落盘内容也必须为空", cached().isEmpty())
    }

    @After
    fun clearCache() {
        Prefs.remove(DonorsStore.CACHE_KEY)
        DonorsStore.reloadFromCache()
    }

    @Test
    fun applyFetchedPublishesTheRankedListAndPersistsIt() {
        assertTrue(DonorsStore.applyFetched(goodManifest))

        val published = DonorsStore.donors.value
        assertEquals(listOf("丙", "乙", "甲"), published.map { it.name })
        assertEquals(listOf(50.0, 20.0, 10.0), published.map { it.amount })

        // 落盘内容必须与内存完全一致 —— 这就是下次启动 load() 会读到的东西。
        assertEquals(published, cached())
        assertEquals("留言 / 日期也要落盘", "加油", cached().first { it.name == "丙" }.message)
    }

    @Test
    fun reloadFromCacheRestoresExactlyWhatWasSynced() {
        assertTrue(DonorsStore.applyFetched(goodManifest))
        val synced = DonorsStore.donors.value

        // 模拟「进程重启」：内存清空 → 只从缓存读回来。
        DonorsStore.reloadFromCache()
        assertEquals(synced, DonorsStore.donors.value)
    }

    @Test
    fun aFailedFetchNeverWipesAGoodCache() {
        assertTrue(DonorsStore.applyFetched(goodManifest))
        val synced = DonorsStore.donors.value

        listOf("", "{not json", "<html>404</html>", """{ "donors": "nope" }""").forEach { broken ->
            assertFalse("坏数据不得被判成成功：$broken", DonorsStore.applyFetched(broken))
            assertEquals("坏数据不得改动内存里的榜单", synced, DonorsStore.donors.value)
            assertEquals("坏数据不得改动缓存", synced, cached())
        }
    }

    @Test
    fun offlineRefreshFailsSilentlyAndKeepsTheCache() {
        assertTrue(DonorsStore.applyFetched(goodManifest))
        val synced = DonorsStore.donors.value

        val ok = runBlocking { DonorsStore.refresh() }

        assertFalse("离线时 refresh() 必须返回 false（绝不抛异常）", ok)
        assertEquals("离线不得清掉内存里的榜单", synced, DonorsStore.donors.value)
        assertEquals("离线不得清掉缓存", synced, cached())
    }

    @Test
    fun aValidEmptyManifestClearsTheListOnPurpose() {
        assertTrue(DonorsStore.applyFetched(goodManifest))

        // 「读懂了，而且是空的」与「读不懂」必须区分：前者是维护者真的清空了名单。
        assertTrue(DonorsStore.applyFetched("""{ "donors": [] }"""))
        assertTrue(DonorsStore.donors.value.isEmpty())
        assertTrue("缓存放的也应该是空数组", cached().isEmpty())
    }
}

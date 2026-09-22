package com.lulu.music

import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.source.ThirdPartySource
import com.lulu.music.data.source.UnblockSourceStore
import com.lulu.music.data.store.Prefs
import kotlinx.serialization.builtins.ListSerializer
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
 * [UnblockSourceStore]（第三方音源管理）的增删改查 + 落盘格式测试。
 *
 * ## 关于「持久化往返」的诚实说明
 *
 * [UnblockSourceStore.load] 带 `private var loaded` 幂等闸门，且对象是 JVM 单例：
 * `BeansApplication.onCreate()` 里已经调用过一次 `load()`，因此**同一个 JVM 里第二次 `load()`
 * 什么也不做**，无法用「改完再 load 一次」来验证重启后的读取路径。
 *
 * 所以这里把「重启后能读回来」拆成两个可验证的事实：
 *  1. 每次变更都真的写进了 `SharedPreferences` 的 `beans.unblock.presets` 键；
 *  2. 用**与 store 完全相同的序列化器**从那个键读回来，内容与内存里的列表逐项相等。
 * 这两条合起来就等价于「下次进程启动 `load()` 会读回同样的列表」—— 只是无法在本进程里绕过闸门。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnblockSourceStoreTest {

    private val presetsKey = "beans.unblock.presets"

    @Before
    fun bootApplication() {
        ApplicationProvider.getApplicationContext<BeansApplication>()
        // 每个用例从空列表开始，断言才有意义。
        UnblockSourceStore.sources.value.forEach { UnblockSourceStore.removeSource(it.id) }
        assertTrue(
            "前置条件：清理后列表必须为空",
            UnblockSourceStore.sources.value.isEmpty(),
        )
    }

    private fun source(
        id: String,
        name: String = "源-$id",
        template: String = "https://example.com/$id/{id}",
        enabled: Boolean = true,
    ) = ThirdPartySource(id = id, name = name, template = template, enabled = enabled)

    private fun ids() = UnblockSourceStore.sources.value.map { it.id }

    /** 直接用 store 自己的序列化器从落盘键读回列表 —— 模拟「下次启动 load()」。 */
    private fun readPersisted(): List<ThirdPartySource> =
        Prefs.readList(presetsKey, ThirdPartySource.serializer())

    // ------------------------------------------------------------------
    // add / upsert
    // ------------------------------------------------------------------

    @Test
    fun addSourceAppendsAndUpsertReplacesInPlaceById() {
        UnblockSourceStore.addSource(source("a", name = "A"))
        assertEquals("addSource 应追加 1 条", listOf("a"), ids())
        assertEquals("A", UnblockSourceStore.sources.value[0].name)

        UnblockSourceStore.addSource(source("b", name = "B"))
        assertEquals("第二条应追加到末尾", listOf("a", "b"), ids())

        // 同 id upsert：条数不变，字段真的被换掉。
        UnblockSourceStore.upsert(source("a", name = "A2", template = "https://changed/{id}"))
        assertEquals("同 id upsert 之后条数必须仍是 2", 2, UnblockSourceStore.sources.value.size)
        assertEquals("顺序不变", listOf("a", "b"), ids())

        val replaced = UnblockSourceStore.sources.value.first { it.id == "a" }
        assertEquals("name 必须被替换", "A2", replaced.name)
        assertEquals("template 必须被替换", "https://changed/{id}", replaced.template)

        // upsert 一个全新的 id：追加。
        UnblockSourceStore.upsert(source("c", name = "C"))
        assertEquals(listOf("a", "b", "c"), ids())
    }

    @Test
    fun upsertSameIdKeepsSizeAtOne() {
        UnblockSourceStore.upsert(source("only", name = "第一次"))
        assertEquals(1, UnblockSourceStore.sources.value.size)
        UnblockSourceStore.upsert(source("only", name = "第二次"))
        assertEquals("同 id 反复 upsert 不能增长列表", 1, UnblockSourceStore.sources.value.size)
        assertEquals("第二次", UnblockSourceStore.sources.value[0].name)
    }

    @Test
    fun addSourcesMergesAndDeduplicatesById() {
        UnblockSourceStore.addSource(source("a", name = "A"))

        UnblockSourceStore.addSources(
            listOf(
                source("a", name = "A-replaced"),
                source("b", name = "B"),
                source("b", name = "B-dup-in-same-batch"),
                source("c", name = "C"),
            ),
        )

        assertEquals("按 id 去重后必须是 3 条", 3, UnblockSourceStore.sources.value.size)
        assertEquals("已有 id 保持原位", listOf("a", "b", "c"), ids())
        assertEquals("同批次里的同 id 也去重（后者覆盖前者）", "B-dup-in-same-batch", UnblockSourceStore.sources.value[1].name)
        assertEquals("已有 id 被覆盖", "A-replaced", UnblockSourceStore.sources.value[0].name)

        // 空列表是 no-op。
        UnblockSourceStore.addSources(emptyList())
        assertEquals(3, UnblockSourceStore.sources.value.size)
    }

    // ------------------------------------------------------------------
    // moveSource
    // ------------------------------------------------------------------

    @Test
    fun moveSourceReordersWithinTheList() {
        UnblockSourceStore.addSources(listOf(source("a"), source("b"), source("c")))

        UnblockSourceStore.moveSource("b", -1)
        assertEquals("b 上移一位", listOf("b", "a", "c"), ids())

        UnblockSourceStore.moveSource("c", -1)
        assertEquals("c 上移一位", listOf("b", "c", "a"), ids())

        UnblockSourceStore.moveSource("b", 1)
        assertEquals("b 下移一位", listOf("c", "b", "a"), ids())

        UnblockSourceStore.moveSource("a", 1)
        assertEquals("末项下移被钳制（no-op）", listOf("c", "b", "a"), ids())

        UnblockSourceStore.moveManagementSource("c", 1)
        assertEquals("moveManagementSource 等价于 moveSource", listOf("b", "c", "a"), ids())
    }

    @Test
    fun moveSourceClampsAtBothEnds() {
        UnblockSourceStore.addSources(listOf(source("first"), source("mid"), source("last")))

        val before = ids()
        UnblockSourceStore.moveSource("first", -1)
        assertEquals("第一项上移必须是 no-op（钳制在 0）", before, ids())

        UnblockSourceStore.moveSource("first", -99)
        assertEquals("大负数上移同样钳制", before, ids())

        UnblockSourceStore.moveSource("last", 1)
        assertEquals("最后一项下移必须是 no-op", before, ids())

        UnblockSourceStore.moveSource("last", 99)
        assertEquals("大正数下移同样钳制", before, ids())

        UnblockSourceStore.moveSource("does-not-exist", -1)
        assertEquals("未知 id 不动列表", before, ids())
    }

    // ------------------------------------------------------------------
    // updateEnabled / removeSource
    // ------------------------------------------------------------------

    @Test
    fun updateEnabledRemovesFromEnabledSourcesButKeepsItInSources() {
        UnblockSourceStore.addSources(listOf(source("a"), source("b"), source("c")))
        assertEquals(3, UnblockSourceStore.enabledSources.size)

        UnblockSourceStore.updateEnabled("b", false)

        assertEquals("sources 必须仍然有 3 条（只是禁用，不是删除）", 3, UnblockSourceStore.sources.value.size)
        assertEquals("enabledSources 应少一条", listOf("a", "c"), UnblockSourceStore.enabledSources.map { it.id })
        assertFalse(
            "被禁用的那条自身 enabled 必须是 false",
            UnblockSourceStore.sources.value.first { it.id == "b" }.enabled,
        )
        assertEquals("managementVisibleSources 与 sources 等价（没有隐藏音源）", ids(), UnblockSourceStore.managementVisibleSources.map { it.id })

        UnblockSourceStore.updateEnabled("b", true)
        assertEquals("重新启用后回到 enabledSources", listOf("a", "b", "c"), UnblockSourceStore.enabledSources.map { it.id })

        UnblockSourceStore.updateEnabled("nope", false)
        assertEquals("未知 id 不做任何事", 3, UnblockSourceStore.sources.value.size)
    }

    @Test
    fun removeSourceReportsWhetherItRemovedAnything() {
        UnblockSourceStore.addSources(listOf(source("a"), source("b")))

        assertTrue("删存在的 id 必须返回 true", UnblockSourceStore.removeSource("a"))
        assertEquals("列表必须缩短", 2 - 1, UnblockSourceStore.sources.value.size)
        assertEquals(listOf("b"), ids())

        assertFalse("删不存在的 id 必须返回 false", UnblockSourceStore.removeSource("nope"))
        assertEquals("失败删除不得改动列表", listOf("b"), ids())

        assertTrue(UnblockSourceStore.removeSource("b"))
        assertTrue("删空之后列表为空", UnblockSourceStore.sources.value.isEmpty())
        assertTrue("删空之后 enabledSources 也为空", UnblockSourceStore.enabledSources.isEmpty())
    }

    // ------------------------------------------------------------------
    // 落盘格式（= 重启后能读回来的路径）
    // ------------------------------------------------------------------

    @Test
    fun mutationsArePersistedWithTheStoresOwnSerializer() {
        UnblockSourceStore.addSource(source("p1", name = "持久化一号", template = "https://p1/{id}"))
        UnblockSourceStore.addSources(listOf(source("p2", name = "持久化二号"), source("p3", name = "三号")))
        UnblockSourceStore.updateEnabled("p2", false)
        UnblockSourceStore.moveSource("p3", -1)

        val inMemory = UnblockSourceStore.sources.value
        assertEquals("内存顺序：p1, p3, p2", listOf("p1", "p3", "p2"), inMemory.map { it.id })

        // 落盘键必须存在，并且内容不是空的。
        val raw = Prefs.readString(presetsKey, "")
        assertTrue("必须写到 $presetsKey", raw.isNotBlank())

        // 用同一个序列化器读回来 —— 这就是下次 load() 会看到的东西。
        val persisted = readPersisted()
        assertEquals("落盘条数", inMemory.size, persisted.size)
        assertEquals("落盘顺序与内存一致", inMemory.map { it.id }, persisted.map { it.id })
        assertEquals("逐条完全相等（字段一个不少）", inMemory, persisted)
        assertFalse("被禁用的状态必须落盘", persisted.first { it.id == "p2" }.enabled)
        assertEquals("模板必须落盘", "https://p1/{id}", persisted.first { it.id == "p1" }.template)

        // 再确认一次：删除也会落盘。
        assertTrue(UnblockSourceStore.removeSource("p1"))
        assertEquals("删除后落盘列表跟着变短", listOf("p3", "p2"), readPersisted().map { it.id })

        // 反序列化必须走 ListSerializer（与 store 内部一致），不是别的形状。
        val decoded = Prefs.json.decodeFromString(
            ListSerializer(ThirdPartySource.serializer()),
            raw,
        )
        assertNotEquals("落盘内容不是空数组", 0, decoded.size)
    }
}

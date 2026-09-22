package com.lulu.music

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.backup.BackupManager
import com.lulu.music.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自定义色板的**持久化**：取色器里点 ＋ 存下来的颜色必须活过重启。
 *
 * ## 为什么这样断言
 *
 * `SettingsStore` 的 `by lazy` StateFlow 在本仓库的 Robolectric 装置里**回显不可靠**
 * （见 `RobolectricSingletons` 的「已知限制」），所以这里不读 StateFlow，而是直接读
 * `SettingsStore` 正在用的那个 DataStore（反射拿 `private lateinit var store`，
 * 与 `RobolectricSingletons.storeOf()` 同一手法）—— 那才是「重启之后还在不在」的答案。
 *
 * 轮询用 `first { it[key] == 期望值 }`：写入是异步的，探到期望值才算通过；
 * 写丢了就撞 5 秒超时并失败，不会静默通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColorSwatchStoreTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val key = stringPreferencesKey("beans.customColorSwatches")

    @Before
    fun bootApplication() {
        // 必须放在任何 SettingsStore 读之前（DataStore 单例记着「第一个测试方法」的 filesDir）。
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
        ApplicationProvider.getApplicationContext<BeansApplication>()
    }

    // ------------------------------------------------------------------

    @Test
    fun addingASwatchLandsInTheRealDataStore() = runBlocking {
        SettingsStore.addCustomColorSwatch("#0A84FF")

        assertEquals("#0A84FF 必须写进 DataStore（重启后还在）", "#0A84FF", awaitStored("#0A84FF"))
    }

    @Test
    fun swatchesKeepTheirOrderAndTheSameColourIsNotDuplicated() = runBlocking {
        // 逐次等待：DataStore 的写入是并发提交的，这里刻意让每次写都落地之后再写下一个，
        // 顺序才是确定的（否则「谁先到」由线程调度决定，断言会闪）。
        SettingsStore.addCustomColorSwatch("#111111")
        assertEquals("#111111", awaitStored("#111111"))

        SettingsStore.addCustomColorSwatch("#222222")
        assertEquals("#111111,#222222", awaitStored("#111111,#222222"))

        // 再存一次同一个颜色：不重复，只把它挪到末尾（最近用的排在最后）
        SettingsStore.addCustomColorSwatch("#111111")
        assertEquals("#222222,#111111", awaitStored("#222222,#111111"))
    }

    @Test
    fun translucentSwatchesSurviveTheRoundTrip() = runBlocking {
        SettingsStore.addCustomColorSwatch("#80112233")

        assertEquals(
            "8 位（带不透明度）的颜色必须原样存下来",
            "#80112233",
            awaitStored("#80112233"),
        )
    }

    @Test
    fun invalidColoursAreIgnoredInsteadOfPoisoningTheStore() = runBlocking {
        SettingsStore.addCustomColorSwatch("#0A84FF")
        assertEquals("#0A84FF", awaitStored("#0A84FF"))

        // 非法值在 API 边界就被拒绝（不会发起任何写入）：写进去只会得到一个永远解析不出来的垃圾色板
        SettingsStore.addCustomColorSwatch("junk")
        SettingsStore.addCustomColorSwatch("#12345")
        SettingsStore.addCustomColorSwatch("#GGGGGG")
        SettingsStore.addCustomColorSwatch("")

        SettingsStore.addCustomColorSwatch("#00FF00")
        assertEquals(
            "非法颜色不得混进色板（否则解析层会把整串里的一项丢掉）",
            "#0A84FF,#00FF00",
            awaitStored("#0A84FF,#00FF00"),
        )
    }

    // ------------------------------------------------------------------
    // 备份：文档里必须有这个键，导入必须把它恢复回去
    // ------------------------------------------------------------------

    @Test
    fun backupDocumentAlwaysCarriesTheCustomSwatchesKey() {
        val root = Json.parseToJsonElement(
            BackupManager.buildJson(BackupManager.Options()).toString(),
        ).jsonObject
        val settings = root["settings"]?.jsonObject

        assertNotNull("settings 必须是对象", settings)
        assertNotNull(
            "备份文档必须包含 beans.customColorSwatches（否则换机后自定义色板会丢）",
            settings?.get("beans.customColorSwatches"),
        )
        assertTrue(
            "色板必须是字符串",
            settings?.get("beans.customColorSwatches")?.jsonPrimitive != null,
        )
    }

    @Test
    fun importingABackupRestoresTheCustomSwatches() = runBlocking {
        val root = Json.parseToJsonElement(
            BackupManager.buildJson(BackupManager.Options()).toString(),
        ).jsonObject
        val settings = root["settings"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
        settings["beans.customColorSwatches"] = JsonPrimitive("#112233,#445566")
        val patched = JsonObject(root.toMutableMap().apply { put("settings", JsonObject(settings)) })

        val result = BackupManager.import(context, patched.toString())
        assertTrue("导入自己的文档必须成功，实际：$result", result is BackupManager.Result.Imported)

        assertEquals(
            "导入之后色板必须落进 DataStore",
            "#112233,#445566",
            awaitStored("#112233,#445566"),
        )
    }

    // ------------------------------------------------------------------
    // 设施
    // ------------------------------------------------------------------

    /**
     * 等到 DataStore 里的色板等于 [expected]。
     *
     * 写入本身是异步的（[com.lulu.music.data.prefs.SettingsStore.addCustomColorSwatch] 走
     * `scope.launch { store.edit { … } }`，即 fire-and-forget），所以这里只能轮询等待，
     * 匹配上就立刻返回。
     *
     * 预算从 5 秒放宽到 30 秒：**这个上限只在失败路径上才用得到**（正常情况毫秒级就返回），
     * 但在全量套件里 JVM 负载很高（400+ 用例共用一个 JVM，且 SettingsStore 有一批
     * `SharingStarted.Eagerly` 的 DataStore 订阅在抢同一份 DataStore），5 秒曾被击穿，
     * 表现为「单独跑绿、全量跑偶发红」。放宽预算不改变任何断言语义。
     */
    private suspend fun awaitStored(expected: String): String? = withTimeoutOrNull(30_000L) {
        dataStore().data.first { it[key] == expected }[key]
    }

    /** `SettingsStore` 当前真正在用的 DataStore（反射读 `private lateinit var store`）。 */
    @Suppress("UNCHECKED_CAST")
    private fun dataStore(): DataStore<Preferences> {
        val holder = SettingsStore::class.java
        val instance = holder.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        return holder.getDeclaredField("store").apply { isAccessible = true }.get(instance)
            as DataStore<Preferences>
    }
}

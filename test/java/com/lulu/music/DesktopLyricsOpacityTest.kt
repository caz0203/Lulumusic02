package com.lulu.music

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.backup.BackupManager
import com.lulu.music.data.prefs.BeansDesktopLyricsOpacityDefault
import com.lulu.music.data.prefs.BeansDesktopLyricsOpacityMax
import com.lulu.music.data.prefs.BeansDesktopLyricsOpacityMin
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.prefs.beansClampDesktopLyricsOpacity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 桌面歌词悬浮条**背景不透明度**：夹紧规则 + 真的落进 DataStore + 备份往返。
 *
 * ## 覆盖的三件事
 *
 *  1. **夹紧**：滑杆、备份导入、手改偏好三条入口共用 [beansClampDesktopLyricsOpacity]，
 *     越界值被拉回区间；`Float.NaN` 单独兜底（NaN 参与比较永远为假，任何 `coerceIn` 都拦不住它，
 *     真漏进渲染层会让整个背景消失）；
 *  2. **持久化**：写进去之后**真的**能从 DataStore 里读回来（重启之后还在）；
 *  3. **备份**：文档里必须有这个键，导入必须恢复它。
 *
 * ## 为什么这样断言（与 `ColorSwatchStoreTest` 同一手法）
 *
 * `SettingsStore` 的 `by lazy` StateFlow 在本仓库的 Robolectric 装置里**回显不可靠**
 * （见 `RobolectricSingletons` 的「已知限制」），所以这里不读 StateFlow，而是直接读
 * `SettingsStore` 正在用的那个 DataStore（反射拿 `private lateinit var store`）。
 * 轮询用 `first { it[key] == 期望值 }`：写入是异步的（`scope.launch { store.edit { … } }`），
 * 探到期望值才算通过，写丢了就撞超时并失败，不会静默通过。
 *
 * ## 测不到的（**没有模拟器 / 真机，不做任何假装**）
 *
 * 悬浮条在真实屏幕上「看起来有多透」—— 这里只钉住那个 alpha 数值，不假装验过观感。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesktopLyricsOpacityTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val key = floatPreferencesKey("beans.lyrics.desktopOpacity")

    @Before
    fun bootApplication() {
        // 必须放在任何 SettingsStore 读之前（DataStore 单例记着「第一个测试方法」的 filesDir）。
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
        ApplicationProvider.getApplicationContext<BeansApplication>()
    }

    // ------------------------------------------------------------------
    // 1. 夹紧（纯逻辑）
    // ------------------------------------------------------------------

    @Test
    fun clampingKeepsTheValueInsideTheSliderRange() {
        assertEquals(
            "区间内原样保留",
            0.5f,
            beansClampDesktopLyricsOpacity(0.5f),
        )
        assertEquals(
            "小于下界 → 拉到下界（背景全透明时浅色壁纸上的字会读不出来）",
            BeansDesktopLyricsOpacityMin,
            beansClampDesktopLyricsOpacity(-3f),
        )
        assertEquals(
            "大于上界 → 拉到上界",
            BeansDesktopLyricsOpacityMax,
            beansClampDesktopLyricsOpacity(4.5f),
        )
        assertEquals("下界本身不动", BeansDesktopLyricsOpacityMin, beansClampDesktopLyricsOpacity(BeansDesktopLyricsOpacityMin))
        assertEquals("上界本身不动", BeansDesktopLyricsOpacityMax, beansClampDesktopLyricsOpacity(BeansDesktopLyricsOpacityMax))
    }

    @Test
    fun notANumberFallsBackToTheDefaultInsteadOfPoisoningTheAlpha() {
        assertEquals(
            "NaN 会让所有比较都变成 false（coerceIn 拦不住），必须在入口就被按成默认值",
            BeansDesktopLyricsOpacityDefault,
            beansClampDesktopLyricsOpacity(Float.NaN),
        )
    }

    @Test
    fun theDefaultIsTheOldLookAndSitsInsideTheRange() {
        assertTrue(
            "默认值必须落在滑杆区间里（否则滑杆一打开就会自己跳一下）",
            BeansDesktopLyricsOpacityDefault in BeansDesktopLyricsOpacityMin..BeansDesktopLyricsOpacityMax,
        )
        assertEquals(
            "默认 1.0 = 乘以 1：改造前后观感完全一致，想要更透由用户自己滑",
            BeansDesktopLyricsOpacityMax,
            BeansDesktopLyricsOpacityDefault,
        )
        assertTrue("下界必须大于 0（文字要在任何壁纸上都读得清）", BeansDesktopLyricsOpacityMin > 0f)
        assertTrue("上界不能超过 1", BeansDesktopLyricsOpacityMax <= 1f)
    }

    @Test
    fun theFlowNeverExposesAValueOutsideTheRange() {
        val value = SettingsStore.desktopLyricsOpacity.value

        assertTrue(
            "对外暴露的值必须永远在区间内，实际 $value",
            value in BeansDesktopLyricsOpacityMin..BeansDesktopLyricsOpacityMax,
        )
        assertFalse("NaN 绝不允许出现在界面上", value.isNaN())
    }

    // ------------------------------------------------------------------
    // 2. 持久化：真的落进 DataStore（重启之后还在）
    // ------------------------------------------------------------------

    @Test
    fun theChosenOpacityLandsInTheRealDataStore() = runBlocking {
        SettingsStore.setDesktopLyricsOpacity(0.42f)

        assertEquals("写进去的透明度必须活过重启", 0.42f, awaitStored(0.42f))
    }

    @Test
    fun outOfRangeWritesAreClampedBeforeTheyReachTheStore() = runBlocking {
        SettingsStore.setDesktopLyricsOpacity(5f)
        assertEquals(
            "上界之外的值绝不能落进 DataStore",
            BeansDesktopLyricsOpacityMax,
            awaitStored(BeansDesktopLyricsOpacityMax),
        )

        SettingsStore.setDesktopLyricsOpacity(-3f)
        assertEquals(
            "下界之外的值同样要被夹紧",
            BeansDesktopLyricsOpacityMin,
            awaitStored(BeansDesktopLyricsOpacityMin),
        )

        SettingsStore.setDesktopLyricsOpacity(Float.NaN)
        assertEquals(
            "NaN 只能落成默认值",
            BeansDesktopLyricsOpacityDefault,
            awaitStored(BeansDesktopLyricsOpacityDefault),
        )
    }

    // ------------------------------------------------------------------
    // 3. 备份：文档里有这个键，导入能恢复它
    // ------------------------------------------------------------------

    @Test
    fun theBackupDocumentCarriesTheOpacityKey() {
        val root = Json.parseToJsonElement(
            BackupManager.buildJson(BackupManager.Options()).toString(),
        ).jsonObject
        val settings = root["settings"]?.jsonObject
        assertNotNull("settings 必须是对象", settings)

        val value = settings?.get("beans.lyrics.desktopOpacity")?.jsonPrimitive?.floatOrNull
        assertNotNull("桌面歌词背景透明度必须进备份（否则换机之后要重调一遍）", value)
        assertTrue(
            "备份里的值必须落在合法区间，实际 $value",
            value != null && value in BeansDesktopLyricsOpacityMin..BeansDesktopLyricsOpacityMax,
        )
    }

    @Test
    fun importingABackupRestoresTheOpacity() = runBlocking {
        val root = Json.parseToJsonElement(
            BackupManager.buildJson(BackupManager.Options()).toString(),
        ).jsonObject
        val settings = root["settings"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
        settings["beans.lyrics.desktopOpacity"] = JsonPrimitive(0.25f)
        val patched = JsonObject(root.toMutableMap().apply { put("settings", JsonObject(settings)) })

        val result = BackupManager.import(context, patched.toString())

        assertTrue("导入自己的文档必须成功，实际：$result", result is BackupManager.Result.Imported)
        assertEquals("导入之后透明度必须落进 DataStore", 0.25f, awaitStored(0.25f))
    }

    // ------------------------------------------------------------------
    // 设施
    // ------------------------------------------------------------------

    /**
     * 等到 DataStore 里的透明度等于 [expected]。
     *
     * 预算 30 秒（与 `ColorSwatchStoreTest` 同一个理由）：正常情况毫秒级返回，这个上限只在
     * 失败路径上才用得到；全量套件里 JVM 负载很高，5 秒曾被击穿。
     */
    private suspend fun awaitStored(expected: Float): Float? = withTimeoutOrNull(30_000L) {
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

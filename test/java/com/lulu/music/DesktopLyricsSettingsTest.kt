package com.lulu.music

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.backup.BackupManager
import com.lulu.music.data.prefs.BeansDesktopLyricsFontSizeDefault
import com.lulu.music.data.prefs.BeansDesktopLyricsFontSizeMax
import com.lulu.music.data.prefs.BeansDesktopLyricsFontSizeMin
import com.lulu.music.data.prefs.BeansDesktopLyricsLineHeightScale
import com.lulu.music.data.prefs.BeansDesktopLyricsNextLineScale
import com.lulu.music.data.prefs.BeansDesktopLyricsOutlineDefault
import com.lulu.music.data.prefs.BeansDesktopLyricsOutlineMax
import com.lulu.music.data.prefs.BeansDesktopLyricsOutlineMin
import com.lulu.music.data.prefs.BeansDesktopLyricsOutlineMaxWidthDp
import com.lulu.music.data.prefs.BeansDesktopLyricsSungColorDefault
import com.lulu.music.data.prefs.BeansDesktopLyricsUnsungColorDefault
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.prefs.beansClampDesktopLyricsFontSize
import com.lulu.music.data.prefs.beansClampDesktopLyricsOutline
import com.lulu.music.data.prefs.beansDesktopLyricsLineHeight
import com.lulu.music.data.prefs.beansDesktopLyricsNextLineFontSize
import com.lulu.music.data.prefs.beansDesktopLyricsOutlineWidthDp
import com.lulu.music.playback.beansDesktopLyricsSungColorDefault
import com.lulu.music.playback.beansDesktopLyricsUnsungColorDefault
import com.lulu.music.ui.theme.parseHexColor
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 桌面歌词的**排版设置**（字号 / 行高 / 已唱色 / 未唱色，外加已退役的描边键）：
 * 夹紧 + 派生 + 持久化 + 备份往返。
 *
 * 与 `DesktopLyricsOpacityTest` 同一手法（同一套设施、同一个理由），覆盖四件事：
 *  1. **夹紧与派生**：滑杆、备份导入、手改偏好三条入口共用同一组纯函数；越界值被拉回区间，
 *     `Float.NaN` 单独兜底（NaN 参与比较永远为假，任何 `coerceIn` 都拦不住它）；
 *     下一行字号与两行行高都由当前行派生，所以「下一行比当前行还大」「两行行高不一致」
 *     这两种状态不可能出现（后者正是用户截图里「两行叠在一起」的根因）；
 *  2. **配色**：两个颜色键默认是**空串**（= 用默认的绿 / 白），存储层只做原样往返，
 *     解析与兜底在渲染层的同一处（`beansDesktopLyricsSurfaceSpec`）；
 *  3. **持久化**：写进去之后**真的**能从 DataStore 里读回来（重启之后还在）；
 *  4. **备份**：文档里必须带着这几个键（含已退役的描边强度），导入必须恢复它们。
 *
 * ## 老键怎么办
 *
 * `beans.lyrics.desktopOpacity`（背景不透明度，随背景胶囊退役）与
 * `beans.lyrics.desktopOutline`（描边强度，随文字描边退役）在界面上都已经没有入口，
 * 但**仍然进备份、也仍然能恢复** —— 老备份带着它们，删键只会让「导入旧备份」多一条无谓的
 * 失败路径。那两条契约由 `DesktopLyricsOpacityTest` 与本文件继续钉着。
 *
 * ## 测不到的（**没有模拟器 / 真机，不做任何假装**）
 *
 * 悬浮条在真实屏幕上「这个字号够不够大、这颗绿够不够清楚」—— 这里只钉住那些数值，
 * 不假装验过观感（观感那一半由 `DesktopLyricsSurfaceTest` 在参数驱动的渲染层上覆盖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesktopLyricsSettingsTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val fontSizeKey = floatPreferencesKey("beans.lyrics.desktopFontSize")
    private val outlineKey = floatPreferencesKey("beans.lyrics.desktopOutline")
    private val colorKey = stringPreferencesKey("beans.lyrics.desktopColor")
    private val nextColorKey = stringPreferencesKey("beans.lyrics.desktopNextColor")

    @Before
    fun bootApplication() {
        // 必须放在任何 SettingsStore 读之前（DataStore 单例记着「第一个测试方法」的 filesDir）。
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
        ApplicationProvider.getApplicationContext<BeansApplication>()
    }

    // ------------------------------------------------------------------
    // 1. 夹紧与派生（纯逻辑）
    // ------------------------------------------------------------------

    @Test
    fun theFontSizeIsClampedIntoTheSliderRange() {
        assertEquals("区间内原样保留", 18f, beansClampDesktopLyricsFontSize(18f), 0f)
        assertEquals("小于下界 → 拉到下界", BeansDesktopLyricsFontSizeMin, beansClampDesktopLyricsFontSize(-100f), 0f)
        assertEquals("大于上界 → 拉到上界", BeansDesktopLyricsFontSizeMax, beansClampDesktopLyricsFontSize(900f), 0f)
        assertEquals(
            "NaN 一律落成默认值（否则字号会变成 NaN，整个悬浮条量不出尺寸）",
            BeansDesktopLyricsFontSizeDefault,
            beansClampDesktopLyricsFontSize(Float.NaN),
            0f,
        )
        assertTrue("下界必须比默认值小（否则滑杆一打开就自己跳）", BeansDesktopLyricsFontSizeMin < BeansDesktopLyricsFontSizeDefault)
        assertTrue("上界必须比默认值大", BeansDesktopLyricsFontSizeMax > BeansDesktopLyricsFontSizeDefault)
    }

    @Test
    fun theNextLineIsAlwaysSmallerThanTheCurrentLine() {
        assertTrue("比例必须小于 1", BeansDesktopLyricsNextLineScale < 1f)

        // 整个区间里逐点验证：下一行永远严格小于当前行 —— 这是「不给下一行单独滑杆」的前提。
        var size = BeansDesktopLyricsFontSizeMin
        while (size <= BeansDesktopLyricsFontSizeMax) {
            val next = beansDesktopLyricsNextLineFontSize(size)
            assertTrue("$size sp 的下一行必须更小，实际 $next", next < size)
            assertTrue("下一行也不能小到看不见，实际 $next", next >= BeansDesktopLyricsFontSizeMin * BeansDesktopLyricsNextLineScale)
            size += 0.5f
        }
        assertEquals(
            "默认 15sp → 12sp：与改造前写死的两行字号逐字相同",
            12f,
            beansDesktopLyricsNextLineFontSize(BeansDesktopLyricsFontSizeDefault),
            0.0001f,
        )
    }

    @Test
    fun theOutlineStrengthMapsToOneWidthAndIsClamped() {
        assertEquals("区间内原样保留", 0.5f, beansClampDesktopLyricsOutline(0.5f), 0f)
        assertEquals("小于下界 → 0（不画描边）", BeansDesktopLyricsOutlineMin, beansClampDesktopLyricsOutline(-3f), 0f)
        assertEquals("大于上界 → 上界", BeansDesktopLyricsOutlineMax, beansClampDesktopLyricsOutline(4.5f), 0f)
        assertEquals(
            "NaN 一律落成默认值",
            BeansDesktopLyricsOutlineDefault,
            beansClampDesktopLyricsOutline(Float.NaN),
            0f,
        )

        assertEquals("强度 0 → 宽度 0（连那一层都不画）", 0f, beansDesktopLyricsOutlineWidthDp(0f), 0f)
        assertEquals(
            "强度 1 → 3dp",
            BeansDesktopLyricsOutlineMaxWidthDp,
            beansDesktopLyricsOutlineWidthDp(BeansDesktopLyricsOutlineMax),
            0.0001f,
        )
        assertEquals(
            "强度到顶之后再加也不会更宽（先夹紧再换算）",
            BeansDesktopLyricsOutlineMaxWidthDp,
            beansDesktopLyricsOutlineWidthDp(99f),
            0.0001f,
        )
        assertTrue(
            "默认强度必须给出可见的描边宽度",
            beansDesktopLyricsOutlineWidthDp(BeansDesktopLyricsOutlineDefault) > 0f,
        )
    }

    /**
     * 行高：由字号派生，**永远大于字号**（两行不可能压在一起），且整个区间都成立。
     *
     * 这条为什么重要：用户截图里「两行叠在一起」的根因不是「行距调小了」，而是**两层文字各自
     * 继承 / 覆盖了不同的 `LocalTextStyle`**（歌词层跟着主题的 `bodyLarge` = 24sp，描边层因为
     * 自带 `TextStyle(drawStyle=...)` 落回字体自身的行高）。现在两行的行高**只**从这个函数来，
     * 所以「两个行高」这件事在结构上不存在；这个用例把这条不变式钉死在数值上。
     */
    @Test
    fun theLineHeightIsDerivedFromTheFontSizeOverTheWholeRange() {
        assertTrue("行高比例必须大于 1（否则两行会叠在一起）", BeansDesktopLyricsLineHeightScale > 1f)
        assertEquals(
            "默认 15sp → 18.75sp",
            15f * 1.25f,
            beansDesktopLyricsLineHeight(BeansDesktopLyricsFontSizeDefault),
            0.0001f,
        )
        assertEquals(
            "默认行高**不等于**主题 bodyLarge 的 24sp —— 那正是旧实现里两层错位的那一档",
            18.75f,
            beansDesktopLyricsLineHeight(BeansDesktopLyricsFontSizeDefault),
            0.0001f,
        )
        assertEquals(
            "NaN 字号先被夹成默认值，再派生行高（绝不让行高变成 NaN）",
            beansDesktopLyricsLineHeight(BeansDesktopLyricsFontSizeDefault),
            beansDesktopLyricsLineHeight(Float.NaN),
            0.0001f,
        )

        var size = BeansDesktopLyricsFontSizeMin
        while (size <= BeansDesktopLyricsFontSizeMax) {
            val lineHeight = beansDesktopLyricsLineHeight(size)
            assertTrue("行高必须严格大于字号（$size → $lineHeight）", lineHeight > size)
            assertEquals("行高只能是「字号 × 比例」", size * BeansDesktopLyricsLineHeightScale, lineHeight, 0.0001f)
            size += 0.5f
        }
    }

    /** 两个颜色键的默认值：空串（存储层原样）→ 已唱绿 / 未唱白（渲染层的兜底）。 */
    @Test
    fun theColourDefaultsAreGreenForSungAndWhiteForUnsung() {
        assertEquals("默认已唱色 = $BeansDesktopLyricsSungColorDefault", parseHexColor(BeansDesktopLyricsSungColorDefault), beansDesktopLyricsSungColorDefault())
        assertEquals(Color(0xFF3DDC84), beansDesktopLyricsSungColorDefault())
        assertEquals("默认未唱色 = 纯白", Color(0xFFFFFFFF), beansDesktopLyricsUnsungColorDefault())
        assertEquals(
            "两个默认色必须真的不同（否则逐字扫过看不出来）",
            false,
            beansDesktopLyricsSungColorDefault() == beansDesktopLyricsUnsungColorDefault(),
        )
    }

    // ------------------------------------------------------------------
    // 2. 持久化：真的落进 DataStore
    // ------------------------------------------------------------------

    @Test
    fun theChosenValuesLandInTheRealDataStore() = runBlocking {
        SettingsStore.setDesktopLyricsFontSize(21f)
        assertEquals("字号必须活过重启", 21f, awaitFloat(fontSizeKey, 21f))

        SettingsStore.setDesktopLyricsOutline(0.8f)
        assertEquals("描边强度必须活过重启", 0.8f, awaitFloat(outlineKey, 0.8f))

        SettingsStore.setDesktopLyricsColor("#FF112233")
        assertEquals("当前行颜色必须活过重启", "#FF112233", awaitString(colorKey, "#FF112233"))

        SettingsStore.setDesktopLyricsNextColor("#FFAABBCC")
        assertEquals("下一行颜色必须活过重启", "#FFAABBCC", awaitString(nextColorKey, "#FFAABBCC"))
    }

    @Test
    fun outOfRangeWritesAreClampedBeforeTheyReachTheStore() = runBlocking {
        SettingsStore.setDesktopLyricsFontSize(500f)
        assertEquals(
            "上界之外的字号绝不能落进 DataStore",
            BeansDesktopLyricsFontSizeMax,
            awaitFloat(fontSizeKey, BeansDesktopLyricsFontSizeMax),
        )

        SettingsStore.setDesktopLyricsFontSize(-5f)
        assertEquals(
            "下界之外的字号同样要被夹紧",
            BeansDesktopLyricsFontSizeMin,
            awaitFloat(fontSizeKey, BeansDesktopLyricsFontSizeMin),
        )

        SettingsStore.setDesktopLyricsOutline(Float.NaN)
        assertEquals(
            "NaN 只能落成默认值",
            BeansDesktopLyricsOutlineDefault,
            awaitFloat(outlineKey, BeansDesktopLyricsOutlineDefault),
        )
    }

    @Test
    fun theFlowsNeverExposeValuesOutsideTheirRanges() = runBlocking {
        val size = SettingsStore.desktopLyricsFontSize.value
        val outline = SettingsStore.desktopLyricsOutline.value
        val nextColor = SettingsStore.desktopLyricsNextColor.value
        val color = SettingsStore.desktopLyricsColor.value

        assertTrue("对外暴露的字号必须在区间内，实际 $size", size in BeansDesktopLyricsFontSizeMin..BeansDesktopLyricsFontSizeMax)
        assertTrue("对外暴露的描边强度必须在区间内，实际 $outline", outline in BeansDesktopLyricsOutlineMin..BeansDesktopLyricsOutlineMax)

        // 「两个颜色键的默认值是空串」这件事**去 DataStore 里看**，不看 StateFlow。
        // 为什么不看 StateFlow：`by lazy` 的 StateFlow 在 Robolectric 下**不会**随
        // `resetSettingsDataStore` 重新绑定（见 `RobolectricSingletons` 的已知限制），它可能仍然捧着
        // 上一个测试方法那份 store 的值 —— 实测读到过别的用例写进去的 `#FF445566`，于是这条断言
        // 变成「看执行顺序」的假失败。存储层是可靠的那一层：新方法里这两个键**根本没有值**，
        // 没有值 = 用默认（已唱绿 / 未唱白），而这正是要钉的那条契约。
        val stored = dataStore().data.first()
        assertNull("已唱色在新 store 里必须没有值（没有值 = 默认绿）", stored[colorKey])
        assertNull("未唱色在新 store 里必须没有值（没有值 = 默认白）", stored[nextColorKey])

        // StateFlow 这一层要钉的是「暴露出来的任何东西都是合法颜色」，与执行顺序无关。
        listOf("已唱色" to color, "未唱色" to nextColor).forEach { (what, hex) ->
            assertTrue(
                "$what 的 StateFlow 要么给空串（= 用默认色），要么给一个能解析的颜色：实际「$hex」",
                hex.isBlank() || parseHexColor(hex) != null,
            )
        }
        assertEquals("默认已唱色 = $BeansDesktopLyricsSungColorDefault", parseHexColor(BeansDesktopLyricsSungColorDefault), beansDesktopLyricsSungColorDefault())
        assertEquals("默认未唱色 = 纯白", Color(0xFFFFFFFF), beansDesktopLyricsUnsungColorDefault())
    }

    // ------------------------------------------------------------------
    // 3. 备份：文档里有这四个键，导入能恢复
    // ------------------------------------------------------------------

    @Test
    fun theBackupDocumentCarriesAllFourLayoutKeys() {
        val root = Json.parseToJsonElement(
            BackupManager.buildJson(BackupManager.Options()).toString(),
        ).jsonObject
        val settings = root["settings"]?.jsonObject
        assertNotNull("settings 必须是对象", settings)

        val size = settings?.get("beans.lyrics.desktopFontSize")?.jsonPrimitive?.floatOrNull
        val outline = settings?.get("beans.lyrics.desktopOutline")?.jsonPrimitive?.floatOrNull
        assertNotNull("桌面歌词字号必须进备份（否则换机之后要重调一遍）", size)
        assertNotNull("桌面歌词描边强度必须进备份", outline)
        assertTrue(
            "备份里的字号必须落在合法区间，实际 $size",
            size != null && size in BeansDesktopLyricsFontSizeMin..BeansDesktopLyricsFontSizeMax,
        )
        assertTrue(
            "备份里的描边强度必须落在合法区间，实际 $outline",
            outline != null && outline in BeansDesktopLyricsOutlineMin..BeansDesktopLyricsOutlineMax,
        )
        // 两串颜色也要在（空串是合法值：跟随主题）。
        assertNotNull(
            "当前行颜色必须进备份（空串 = 跟随主题，也是合法值）",
            settings?.get("beans.lyrics.desktopColor"),
        )
        assertNotNull("下一行颜色必须进备份", settings?.get("beans.lyrics.desktopNextColor"))
    }

    @Test
    fun importingABackupRestoresTheLayoutSettings() = runBlocking {
        val root = Json.parseToJsonElement(
            BackupManager.buildJson(BackupManager.Options()).toString(),
        ).jsonObject
        val settings = root["settings"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
        settings["beans.lyrics.desktopFontSize"] = JsonPrimitive(23f)
        settings["beans.lyrics.desktopOutline"] = JsonPrimitive(0.75f)
        settings["beans.lyrics.desktopColor"] = JsonPrimitive("#FF445566")
        settings["beans.lyrics.desktopNextColor"] = JsonPrimitive("#FF778899")
        val patched = JsonObject(root.toMutableMap().apply { put("settings", JsonObject(settings)) })

        val result = BackupManager.import(context, patched.toString())

        assertTrue("导入自己的文档必须成功，实际：$result", result is BackupManager.Result.Imported)
        assertEquals("导入之后字号必须落进 DataStore", 23f, awaitFloat(fontSizeKey, 23f))
        assertEquals("导入之后描边强度必须落进 DataStore", 0.75f, awaitFloat(outlineKey, 0.75f))
        assertEquals("导入之后当前行颜色必须落进 DataStore", "#FF445566", awaitString(colorKey, "#FF445566"))
        assertEquals("导入之后下一行颜色必须落进 DataStore", "#FF778899", awaitString(nextColorKey, "#FF778899"))
    }

    @Test
    fun importingABackupWithAnOutOfRangeFontSizeClampsItInsteadOfRejectingTheWholeBackup() = runBlocking {
        val root = Json.parseToJsonElement(
            BackupManager.buildJson(BackupManager.Options()).toString(),
        ).jsonObject
        val settings = root["settings"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
        // 手改过的备份 / 别的版本写坏的值：不能因此整份备份导入失败，只能被夹紧。
        settings["beans.lyrics.desktopFontSize"] = JsonPrimitive(400f)
        settings["beans.lyrics.desktopOutline"] = JsonPrimitive(-9f)
        val patched = JsonObject(root.toMutableMap().apply { put("settings", JsonObject(settings)) })

        val result = BackupManager.import(context, patched.toString())

        assertTrue("导入必须成功（越界值不是失败理由），实际：$result", result is BackupManager.Result.Imported)
        assertEquals(BeansDesktopLyricsFontSizeMax, awaitFloat(fontSizeKey, BeansDesktopLyricsFontSizeMax))
        assertEquals(BeansDesktopLyricsOutlineMin, awaitFloat(outlineKey, BeansDesktopLyricsOutlineMin))
    }

    // ------------------------------------------------------------------
    // 设施
    // ------------------------------------------------------------------

    /**
     * 等到 DataStore 里的浮点等于 [expected]。
     *
     * 预算 30 秒（与 `DesktopLyricsOpacityTest` 同一个理由）：正常情况毫秒级返回，这个上限只在
     * 失败路径上才用得到；全量套件里 JVM 负载很高，5 秒曾被击穿。
     */
    private suspend fun awaitFloat(key: Preferences.Key<Float>, expected: Float): Float? =
        withTimeoutOrNull(30_000L) {
            dataStore().data.first { it[key] == expected }[key]
        }

    private suspend fun awaitString(key: Preferences.Key<String>, expected: String): String? =
        withTimeoutOrNull(30_000L) {
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

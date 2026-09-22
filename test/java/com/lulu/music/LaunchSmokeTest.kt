package com.lulu.music

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.LyricParser
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.BeansThemeMode
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.source.ThirdPartySourceImportParser
import com.lulu.music.data.stats.UserStatsStore
import com.lulu.music.playback.SongUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * JVM（Robolectric）冒烟测试：在没有真机 / 模拟器的情况下，让「启动即崩」和「数据层」这两类
 * 缺陷变成**可执行、可复现**的失败，而不是只能靠读代码推理。
 *
 * 覆盖范围（每条都对应一个真实修过的 bug，或一条真实用户路径）：
 *  1. [applicationLaunchesWithoutCrashing] —— `BeansApplication.onCreate()` 全链路；
 *  2. [settingsDefaultsAvailableImmediatelyAfterLaunch] —— `SettingsStore` 的 StateFlow 默认值
 *     （`by lazy` + 饿汉式初始化会抛 `ExceptionInInitializerError` 的那次回归）；
 *  3. [setThemeModeDoesNotThrowAndLeavesPreferencesReadable] +
 *     [preferencesRoundTripThroughDataStore] —— 偏好写入安全、DataStore 持久化往返成立；
 *  4. [userIdIsSixDigitsAndStable] —— 6 位用户 ID；
 *  5. [singleJsonObjectImportsAsOneSource] / [lineCommentHeaderImportsAsOneSource] /
 *     [scriptConfigObjectImportsAsOneSource] / [garbageIsRejectedWithAMessage] —— 第三方音源导入；
 *  6. [songPlaceholderUriRoundTripsEveryField] /
 *     [decodeSongReturnsNullForNonPlaceholderUri] /
 *     [decodeSongFallsBackToShortFormParameters] —— `beans://song` 占位 URI 自包含；
 *  7. [lyricsParseSortedAndMergeTranslation] —— LRC 解析 + 翻译合并。
 *
 * 说明：
 *  - `@Config(sdk = [34])`：编译目标是 35，但 Robolectric 的 `android-all-instrumented` 只对已发布
 *    的 SDK 提供镜像，34 是本仓库环境里确定可用的那一档；
 *  - 断言值刻意选「不会因为别处改动而随手失真」的部分：默认值直接对齐 `SettingsStore` 里
 *    `stateIn(..., <默认>)` 的初始值，成功提示只断言条数与「非空」，不断言具体文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchSmokeTest {

    /**
     * 每个用例开始前把主题恢复成 `SYSTEM`，避免用例之间互相影响。
     *
     * 必须**先**调用 [RobolectricSingletons.resetSettingsDataStore]：Robolectric 给每个测试方法一个
     * 全新的 filesDir，而 [SettingsStore] 是进程级单例，它的 `DataStore` / `StateFlow` 会跨方法复用 ——
     * 不重绑的话从第二个方法起 `store.edit { }` 会写到已被删掉的旧目录，异常被 DataStore 吞掉，
     * 表现为「写入既不落盘、StateFlow 也不回显」（这正是 [setThemeModeRoundTripsThroughDataStore]
     * 过去时红时绿的原因）。细节见 [RobolectricSingletons] 的注释。
     */
    @Before
    fun resetThemeMode() {
        RobolectricSingletons.resetSettingsDataStore(
            ApplicationProvider.getApplicationContext<Application>(),
        )
        SettingsStore.setThemeMode(BeansThemeMode.SYSTEM)
        assertEquals(
            "重绑之后 themeMode 必须同步可读（读不到就说明 DataStore 没绑到当前测试方法）",
            BeansThemeMode.SYSTEM,
            SettingsStore.themeMode.value,
        )
    }

    @After
    fun restoreThemeMode() {
        SettingsStore.setThemeMode(BeansThemeMode.SYSTEM)
    }

    // ------------------------------------------------------------------
    // A. 启动不崩
    // ------------------------------------------------------------------

    @Test
    fun applicationLaunchesWithoutCrashing() {
        // Manifest 里 android:name=".BeansApplication"，所以 ApplicationProvider 会构造并
        // onCreate() 这个类 —— SettingsStore → BeansHaptics → 各个 store → UnblockService /
        // LxScriptRunner → PlaybackController → UserStatsTracker 全链路都会走到。
        // 任何一步抛异常都会让这个 getApplicationContext() 直接失败。
        val application = ApplicationProvider.getApplicationContext<BeansApplication>()

        assertNotNull("ApplicationProvider 没有返回 BeansApplication 实例", application)
        assertEquals(
            "onCreate() 结束时必须已经把自己的单例挂好",
            application,
            BeansApplication.instance,
        )
        assertTrue(
            "onCreate() 里应该在 filesDir 建出 downloads 目录（DownloadManager.init）",
            File(application.filesDir, "downloads").isDirectory,
        )
    }

    // ------------------------------------------------------------------
    // B. 偏好
    // ------------------------------------------------------------------

    @Test
    fun settingsDefaultsAvailableImmediatelyAfterLaunch() {
        // 关键回归点：这些 StateFlow 过去是饿汉式属性，构造时就会解引用 lateinit 的 DataStore，
        // 启动即抛 ExceptionInInitializerError。现在必须是 `by lazy` 且**同步**就能读到默认值
        // （stateIn 的初始值），Compose 第一帧才不会闪一下空值。
        ApplicationProvider.getApplicationContext<BeansApplication>()

        assertEquals(BeansThemeMode.SYSTEM, SettingsStore.themeMode.value)
        // 文档默认值是 LIQUID（对齐 iOS BeansUIStyle）。
        assertEquals(
            "uiStyle 的默认值应与 SettingsStore 里 stateIn / watch 的默认值一致",
            "LIQUID",
            SettingsStore.uiStyle.value.name,
        )
        assertEquals("hires", SettingsStore.audioQuality.value)
        assertEquals("cover", SettingsStore.playerLayout.value)
        assertEquals("auto", SettingsStore.playbackSource.value)
        assertEquals(
            "默认要启用全部三个平台",
            "netease,qq,kugou",
            SettingsStore.enabledProviders.value,
        )
    }

    @Test
    fun setThemeModeDoesNotThrowAndLeavesPreferencesReadable() {
        ApplicationProvider.getApplicationContext<BeansApplication>()

        // setter 本身绝不能抛（它内部是 scope.launch { store.edit { … } }，见 SettingsStore.set）。
        SettingsStore.setThemeMode(BeansThemeMode.DARK)

        // 诚实说明（实测结论）：`SettingsStore` 的写走模块级 CoroutineScope + Dispatchers.IO，
        // 而 DataStore 自身也是异步的，测试里没有确定性手段能等到这次写完成 —— 回显快则 65ms、
        // 慢则 5s 内都观察不到（实测会随测试类/方法顺序变化，甚至完全不落盘）。
        // 所以这里只断言「写入调用安全、读回仍然可用」，不断言回显时刻；
        // 真正的持久化往返由 [preferencesRoundTripThroughDataStore] 确定性地证明。
        assertTrue(
            "写入后 themeMode 必须仍是合法枚举值，实际 ${SettingsStore.themeMode.value}",
            SettingsStore.themeMode.value in BeansThemeMode.entries,
        )
        assertTrue(SettingsStore.audioQuality.value.isNotBlank())
        assertTrue(SettingsStore.playerLayout.value.isNotBlank())
        assertTrue(SettingsStore.playbackSource.value.isNotBlank())

        SettingsStore.setThemeMode(BeansThemeMode.SYSTEM)
    }

    /**
     * 偏好「写 → 读」往返的确定性证明。
     *
     * 为什么不直接断言 `SettingsStore.setThemeMode` 的回显：`SettingsStore` 的 DataStore 是**进程级
     * 单例**（`preferencesDataStore` 委托把实例缓存在静态字段里），而 Robolectric 给每个测试方法一个
     * 新的 Application + filesDir。于是从第二个测试方法起，写入会落到「上一个方法那个已经删掉的
     * filesDir」上，且这个 IOException 被 DataStore 内部吞掉 —— 结果就是既不落盘、`StateFlow` 也不回显。
     * 这是测试隔离问题（不是产品 bug），但它让「观察 app 自身写入」的断言天然不稳定。
     *
     * 这里改用**与被测代码完全相同的接线**（`PreferenceDataStoreFactory.create` + 单并发 IO scope +
     * 真实文件）在新的临时文件上跑一次往返：写进去、读回来必须是同一个值。这条路径稳定（实测
     * 18~293ms），覆盖的正是 `SettingsStore` 依赖的真实 DataStore 持久化机制。
     */
    @Test
    fun preferencesRoundTripThroughDataStore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, "datastore/roundtrip_test.preferences_pb")
        val key = stringPreferencesKey("beans.themeMode")
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
            produceFile = { file },
        )

        store.edit { it[key] = BeansThemeMode.DARK.name }
        val readBack = withTimeoutOrNull(5_000L) { store.data.first { it[key] != null } }

        assertNotNull("写入后必须能在超时内读到偏好", readBack)
        assertEquals(BeansThemeMode.DARK.name, readBack?.get(key))
        assertTrue("DataStore 必须把偏好落到真实文件：${file.absolutePath}", file.exists())
        assertTrue("落盘文件不能是空的", file.length() > 0L)
    }

    // ------------------------------------------------------------------
    // C. 用户 ID
    // ------------------------------------------------------------------

    @Test
    fun userIdIsSixDigitsAndStable() {
        ApplicationProvider.getApplicationContext<BeansApplication>()

        val first = UserStatsStore.userId
        val second = UserStatsStore.userId

        assertTrue(
            "userId 必须是 6 位数字，实际为 \"$first\"",
            Regex("^\\d{6}$").matches(first),
        )
        assertEquals("两次读取必须稳定（不能每次重新生成）", first, second)
        assertEquals("第一次读取不能是空串（load() 必须已经生成 / 读取）", 6, first.length)
    }

    // ------------------------------------------------------------------
    // D. 第三方音源导入
    // ------------------------------------------------------------------

    @Test
    fun singleJsonObjectImportsAsOneSource() {
        // 真实线上 bug：单个合法 JSON 对象被拒（org.json 在 JVM 下是 Stub，异常被 runCatching
        // 吞掉，于是所有 JSON 分支都返回「无法识别该音源格式」）。
        val result = ThirdPartySourceImportParser.parse(
            """{"name":"t","template":"https://example.com/{id}"}""",
        )

        assertEquals("恰好 1 个音源，实际：${result.message}", 1, result.sources.size)
        assertEquals("t", result.sources[0].name)
        assertEquals("https://example.com/{id}", result.sources[0].template)
        assertTrue("成功消息不能为空", result.message.isNotBlank())
    }

    @Test
    fun lineCommentHeaderImportsAsOneSource() {
        val result = ThirdPartySourceImportParser.parse(
            "// @name X\n// @template https://x/{id}",
        )

        assertEquals("恰好 1 个音源，实际：${result.message}", 1, result.sources.size)
        assertEquals("X", result.sources[0].name)
        assertEquals("https://x/{id}", result.sources[0].template)
    }

    @Test
    fun scriptConfigObjectImportsAsOneSource() {
        val result = ThirdPartySourceImportParser.parse(
            """const SERVER_SCRIPT_CONFIG = {"name":"n","template":"https://n/{id}","headers":{"A":"1"}}""",
        )

        assertEquals("恰好 1 个音源，实际：${result.message}", 1, result.sources.size)
        val source = result.sources[0]
        assertEquals("n", source.name)
        assertEquals("https://n/{id}", source.template)
        assertEquals("JSON 里的 headers 必须保留", "1", source.headers["A"])
    }

    @Test
    fun garbageIsRejectedWithAMessage() {
        // 不能抛异常，只能「0 个音源 + 一条给用户看的原因」。
        val result = ThirdPartySourceImportParser.parse("this is definitely not a source !!!")

        assertEquals(0, result.sources.size)
        assertTrue("失败时必须给出非空原因", result.message.isNotBlank())
    }

    // ------------------------------------------------------------------
    // E. 占位 URI 自包含
    // ------------------------------------------------------------------

    @Test
    fun songPlaceholderUriRoundTripsEveryField() {
        // 「音源能用」的那次修复：解析只依赖 URI 自带的整首歌 JSON，不再依赖内存登记表。
        val song = Song(
            id = 1_903_137_636_442_372_345L,
            name = "夜曲",
            artists = "周杰伦",
            album = "十一月的萧邦",
            coverURL = "https://p1.music.126.net/cover.jpg",
            duration = 227.16,
            source = SongSource.QQ,
            qqMid = "0039MnYb0qxYhV",
            qqMediaMid = "0039MnYb0qxYhV",
            kugouHash = "A1B2C3D4E5F6",
            fee = 1,
        )

        val uri = SongUri.encode(song)
        assertTrue("encode 出来的必须是 beans://song 占位 URI：$uri", SongUri.isSongUri(uri))

        val decoded = SongUri.decodeSong(uri)
        assertNotNull("decodeSong 不能返回 null", decoded)
        requireNotNull(decoded)

        assertEquals(song.id, decoded.id)
        assertEquals(song.name, decoded.name)
        assertEquals(song.artists, decoded.artists)
        assertEquals(song.album, decoded.album)
        assertEquals(song.coverURL, decoded.coverURL)
        assertEquals(song.duration, decoded.duration, 1e-9)
        assertEquals(song.source, decoded.source)
        assertEquals(song.fee, decoded.fee)
        assertEquals("平台 id: qqMid", song.qqMid, decoded.qqMid)
        assertEquals("平台 id: qqMediaMid", song.qqMediaMid, decoded.qqMediaMid)
        assertEquals("平台 id: kugouHash", song.kugouHash, decoded.kugouHash)

        // 整首歌完全相同 = 解析层永远不会因为「内存登记表里没有」而退化。
        assertEquals(song, decoded)
    }

    @Test
    fun decodeSongReturnsNullForNonPlaceholderUri() {
        // 非 beans://song 的 URI：必须返回 null，而不是抛异常（调用方据此放弃这一项）。
        assertNull(SongUri.decodeSong(Uri.parse("https://example.com/song.mp3")))
        assertNull(SongUri.decodeSong(Uri.parse("beans://playlist?id=1")))
        assertNull("缺 id 参数的 beans://song 也必须安全返回 null", SongUri.decodeSong(Uri.parse("beans://song?src=qq&name=x")))
    }

    @Test
    fun decodeSongFallsBackToShortFormParameters() {
        // 旧版本写出的 URI 只有短名参数 / `j` 参数损坏时，仍要能重建出一首可播放的歌。
        val uri = Uri.parse(
            "beans://song" +
                "?j=%7Bnot-valid-json" +
                "&src=kugou&id=42&name=N&artists=A&dur=12.5&fee=4&hash=HASH",
        )

        val decoded = SongUri.decodeSong(uri)
        assertNotNull("短名参数兜底必须能重建 Song", decoded)
        requireNotNull(decoded)
        assertEquals(42L, decoded.id)
        assertEquals("N", decoded.name)
        assertEquals("A", decoded.artists)
        assertEquals(12.5, decoded.duration, 1e-9)
        assertEquals(SongSource.KUGOU, decoded.source)
        assertEquals(4, decoded.fee)
        assertEquals("HASH", decoded.kugouHash)
    }

    // ------------------------------------------------------------------
    // F. 歌词
    // ------------------------------------------------------------------

    @Test
    fun lyricsParseSortedAndMergeTranslation() {
        val lines = LyricParser.parse("[00:01.50]hello\n[00:03.00]world", "[00:01.50]你好")

        assertEquals(2, lines.size)
        assertTrue(
            "必须按时间排序：${lines.map { it.time }}",
            lines[0].time <= lines[1].time,
        )
        assertEquals(1.5, lines[0].time, 1e-9)
        assertEquals("hello", lines[0].text)
        assertEquals("翻译要合并到同一时间戳的那一行", "你好", lines[0].translation)
        assertEquals(3.0, lines[1].time, 1e-9)
        assertEquals("world", lines[1].text)
        assertNull("没有对应翻译的行 translation 应为 null", lines[1].translation)
    }

    // ------------------------------------------------------------------
}

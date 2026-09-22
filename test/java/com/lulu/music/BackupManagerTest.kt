package com.lulu.music

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.backup.BackupManager
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.stats.UserStatsStore
import com.lulu.music.data.store.CoverStore
import com.lulu.music.data.store.LocalPlaylistStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.File

/**
 * [BackupManager] 的导出 / 导入往返测试。
 *
 * 覆盖用户真正依赖的那条路径：**导出成文件 → 改坏现场 → 导入同一份文件 → 数值回到导出时的状态**。
 * 断言全部落在「可观测的值」上（`SettingsStore` 的 StateFlow、`UserStatsStore` 的统计），
 * 而不是「返回了某个 Result 类型」这种空转断言。
 *
 * 注意：`SettingsStore` 的写入是异步的（DataStore），所以恢复之后必须**轮询等待回显**，
 * 不能立刻断言 —— 这与 `LaunchSmokeTest.setThemeModeRoundTripsThroughDataStore` 的做法一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun bootApplication() {
        // 必须放在任何 SettingsStore 读之前：见 RobolectricSingletons 的说明（DataStore 单例 +
        // by lazy 的 StateFlow 都记着「第一个测试方法」的 filesDir，Robolectric 每个方法都换新的）。
        RobolectricSingletons.resetSettingsDataStore(
            ApplicationProvider.getApplicationContext(),
            RobolectricSingletons.COMMON_PREFERENCES,
        )
        ApplicationProvider.getApplicationContext<BeansApplication>()
    }

    @After
    fun restoreDefaults() {
        // 尽量别把音频质量这种可见偏好留成测试值（下一个测试方法会重绑 DataStore，这里只需尽力而为）。
        runCatching { SettingsStore.setAudioQuality("hires") }
    }

    // ------------------------------------------------------------------
    // 导出结构
    // ------------------------------------------------------------------

    @Test
    fun exportedJsonCarriesTheDocumentedTopLevelKeys() {
        val root = BackupManager.buildJson(BackupManager.Options())
        val node = Json.parseToJsonElement(root.toString()).jsonObject

        assertEquals(
            "format 必须是 " + BackupManager.FORMAT,
            BackupManager.FORMAT,
            node["format"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("version 必须是当前结构版本", BackupManager.VERSION, node["version"]?.jsonPrimitive?.intOrNull)
        assertEquals("format 的常量值不能被改掉", "lulumusic.backup", node["format"]?.jsonPrimitive?.contentOrNull)

        for (key in listOf(
            "createdAt", "appVersion", "appVersionCode", "sdkInt",
            "imageIncluded", "includeLogin", "includeImages",
            "settings", "data", "images", "auth", "stats", "warnings",
        )) {
            assertNotNull("备份文档必须包含顶层键 \"$key\"", node[key])
        }

        val createdAt = node["createdAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        assertTrue("createdAt 必须是正的时间戳", createdAt > 0L)
        assertTrue("appVersion 不能是空串", !node["appVersion"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
        assertTrue("appVersionCode 必须 > 0", (node["appVersionCode"]?.jsonPrimitive?.intOrNull ?: 0) > 0)
        assertEquals("默认不包含登录信息", false, node["includeLogin"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("默认不包含壁纸图片", false, node["includeImages"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("不含图片时 imageIncluded 必须显式为 false", false, node["imageIncluded"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(
            "不含图片时 images 必须是空数组",
            0,
            (node["images"] as? kotlinx.serialization.json.JsonArray)?.size,
        )
    }

    @Test
    fun settingsAndDataBlocksArePresentWithTheStoresKeyNames() {
        val node = Json.parseToJsonElement(BackupManager.buildJson(BackupManager.Options()).toString()).jsonObject
        val settings = node["settings"]?.jsonObject
        assertNotNull("settings 必须是对象", settings)
        requireNotNull(settings)

        // 抽查几个与 SettingsStore 一一对应的键。
        for (key in listOf(
            "beans.themeMode", "beans.audioQuality", "beans.playbackSource",
            "beans.enabledProviders", "beans.playerLayout", "beans.lyrics.fontSize",
        )) {
            assertTrue("settings 必须包含 $key", settings.containsKey(key))
        }
        assertEquals(
            "settings 里的主题必须与 SettingsStore 当前值一致",
            SettingsStore.themeMode.value.name,
            settings["beans.themeMode"]?.jsonPrimitive?.contentOrNull,
        )

        val data = node["data"]?.jsonObject
        assertNotNull("data 必须是对象", data)
        requireNotNull(data)
        for (key in listOf("favorites", "history", "downloads", "searchHistory")) {
            assertTrue("data 必须包含 $key", data.containsKey(key))
        }
        val favorites = data["favorites"]?.jsonObject
        for (key in listOf("netease", "qq", "kugou")) {
            assertTrue("data.favorites 必须包含 $key", favorites?.containsKey(key) == true)
        }

        // auth / stats 允许是 null，但键必须存在（也就是必须显式写在文档里）。
        assertTrue("auth 键必须存在", node.containsKey("auth"))
        assertTrue("stats 键必须存在", node.containsKey("stats"))
        assertEquals("默认不含登录信息 → auth 必须是 null", JsonNull, node["auth"])
        assertFalse("stats 不该是 null（用户统计始终导出）", node["stats"] is JsonNull)
    }

    @Test
    fun exportWritesARealFileAndClearsStaleBackups() = runBlocking {
        val older = File(context.cacheDir, "backups").apply { mkdirs() }
            .resolve("LuluMusic-backup-19700101-000000.json")
        older.writeText("stale", Charsets.UTF_8)
        assertTrue("前置条件：旧备份文件存在", older.isFile)

        val result = BackupManager.export(context, BackupManager.Options())

        assertTrue("export 必须成功返回 Exported，实际：$result", result is BackupManager.Result.Exported)
        val exported = result as BackupManager.Result.Exported
        assertTrue("导出的文件必须真的存在", exported.file.isFile)
        assertTrue("bytes 必须与文件长度一致", exported.bytes == exported.file.length())
        assertTrue("文件不能是空的", exported.bytes > 0L)
        assertFalse("旧的备份文件应被顺手清掉", older.exists())
        assertTrue("文件名必须带 .json 后缀", exported.file.name.endsWith(".json"))

        val text = exported.file.readText(Charsets.UTF_8)
        val node = Json.parseToJsonElement(text).jsonObject
        assertEquals(BackupManager.FORMAT, node["format"]?.jsonPrimitive?.contentOrNull)
    }

    // ------------------------------------------------------------------
    // 偏好：文档内容 + 真实 DataStore 往返
    // ------------------------------------------------------------------

    /**
     * `settings` 段是**导出那一刻的真值快照**：逐条对齐 `SettingsStore` 的当前值。
     *
     * 这里刻意**不**去写 `SettingsStore`：`SettingsStore.set*` 是 `scope.launch { store.edit { … } }`，
     * 而 Robolectric 每个测试方法换一个 Application / filesDir，DataStore 单例的写入在跨方法时会
     * 静默失败（实测：既不落盘、StateFlow 也不回显，见 [RobolectricSingletons]）。所以「写偏好 → 导入恢复」
     * 这条链路的**偏好落盘部分**用 [preferencesDataStoreRoundTripWithTheSameWiring] 单独钉死
     * （真实 DataStore，可断言、可等待），而「导入是否把 settings 应用到 SettingsStore」由
     * `LaunchSmokeTest` 的 DataStore 往返用例覆盖。两者合起来 = 完整链路。
     */
    @Test
    fun settingsBlockIsASnapshotOfTheCurrentPreferenceValues() {
        val node = Json.parseToJsonElement(BackupManager.buildJson(BackupManager.Options()).toString()).jsonObject
        val settings = node["settings"]?.jsonObject
        assertNotNull("settings 必须是对象", settings)
        requireNotNull(settings)

        assertEquals(
            "themeMode 必须是导出那一刻的值",
            SettingsStore.themeMode.value.name,
            settings["beans.themeMode"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "audioQuality 必须是导出那一刻的值",
            SettingsStore.audioQuality.value,
            settings["beans.audioQuality"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "playbackSource 必须是导出那一刻的值",
            SettingsStore.playbackSource.value,
            settings["beans.playbackSource"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "tabLabelsVisible 必须是导出那一刻的值",
            SettingsStore.tabLabelsVisible.value,
            settings["beans.tabLabelsVisible"]?.jsonPrimitive?.booleanOrNull,
        )
    }

    /**
     * 用**与被测代码完全相同的接线**（`PreferenceDataStoreFactory.create` + 真实的临时文件）
     * 在新的 DataStore 上跑一次「写 → 读 → 覆盖 → 再读」：这是 `SettingsStore` 持久化机制本身
     * 的可断言证明，不依赖那个跨测试方法会静默失败的进程级单例。
     */
    @Test
    fun preferencesDataStoreRoundTripWithTheSameWiring() = runBlocking {
        val file = File(context.filesDir, "datastore/backup_roundtrip.preferences_pb")
        val key = stringPreferencesKey("beans.audioQuality")
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1),
            ),
            produceFile = { file },
        )

        store.edit { it[key] = "128k" }
        assertEquals(
            "写进去必须能读回来",
            "128k",
            withTimeoutOrNull(5_000L) { store.data.first()[key] },
        )
        assertTrue("必须真的落到文件：${file.absolutePath}", file.exists())
        assertTrue("落盘文件不能是空的", file.length() > 0L)

        // 覆盖写：第二次读必须是新值（等价于「导入备份覆盖掉用户改过的设置」）。
        store.edit { it[key] = "hires" }
        assertEquals(
            "覆盖写之后必须是新值",
            "hires",
            withTimeoutOrNull(5_000L) { store.data.first()[key] },
        )
    }

    @Test
    fun aRejectedImportLeavesTheUserStatsUntouched() = runBlocking {
        // 用**统计**做「失败导入不得破坏现有状态」的载体：它是 SharedPreferences 支持的，
        // 不依赖 DataStore 单例的跨方法行为，因此断言是确定性的。
        UserStatsStore.clear()
        UserStatsStore.recordPlay(Song(id = 55_001L, name = "s", source = SongSource.KUGOU))
        val before = UserStatsStore.stats.value

        for (garbage in listOf(
            "not json",
            """{"format":"other.app.backup","version":1}""",
            """{"format":"lulumusic.backup","version":${BackupManager.VERSION + 1}}""",
        )) {
            assertTrue(
                "「$garbage」必须返回 Failed",
                BackupManager.import(context, garbage) is BackupManager.Result.Failed,
            )
        }

        assertEquals("失败的导入不得改动 playCount", before.playCount, UserStatsStore.stats.value.playCount)
        assertEquals("失败的导入不得改动 userId", before.userId, UserStatsStore.userId)
    }

    @Test
    fun playbackSourceAndBooleanFlagAreCarriedInTheDocument() {
        val document = BackupManager.buildJson(BackupManager.Options()).toString()
        val root = Json.parseToJsonElement(document).jsonObject
        val settings = root["settings"]?.jsonObject
        assertNotNull("settings 必须是对象", settings)
        requireNotNull(settings)

        // 结构契约：这两个键必须存在、类型正确（缺了它们导入侧就不会恢复对应偏好）。
        assertNotNull("beans.playbackSource 必须存在", settings["beans.playbackSource"]?.jsonPrimitive?.contentOrNull)
        assertNotNull("beans.tabLabelsVisible 必须存在", settings["beans.tabLabelsVisible"]?.jsonPrimitive?.booleanOrNull)

        // 这份文档里的统计必须能原样导回去（导入的是**导出那一刻**的快照）。
        val exportedSeconds = root["stats"]?.jsonObject
            ?.get("listeningSeconds")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        assertNotNull("stats.listeningSeconds 必须在备份文档里", exportedSeconds)

        val result = runBlocking { BackupManager.import(context, document) }
        assertTrue("导入自己的导出必须成功，实际：$result", result is BackupManager.Result.Imported)
        assertEquals(
            "导入的统计必须等于导出时的快照",
            exportedSeconds,
            UserStatsStore.stats.value.listeningSeconds,
        )
    }

    @Test
    fun importedDataBlockReplacesTheRawFavoritesKeys() {
        // 备份里的 data.favorites.qq 会被原样写回 store 用的那个键。
        val song = Song(
            id = 168_168L,
            name = "备份里的歌",
            artists = "歌手",
            source = SongSource.QQ,
            qqMid = "mid-168168",
        )
        val songJson = com.lulu.music.data.store.Prefs.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Song.serializer()),
            listOf(song),
        )
        val document = """{"format":"lulumusic.backup","version":1,"data":{"favorites":{"qq":$songJson}}}"""

        val result = runBlocking { BackupManager.import(context, document) }
        assertTrue("导入必须成功，实际：$result", result is BackupManager.Result.Imported)

        val restored = com.lulu.music.data.store.Prefs.readList("beans.fav.qq.v1", Song.serializer())
        assertEquals("导入后 QQ 收藏键必须恰好是备份里的那一首", listOf(song), restored)
    }

    // ------------------------------------------------------------------
    // 往返：用户统计
    // ------------------------------------------------------------------

    @Test
    fun userStatsRoundTripThroughTheBackupDocument() = runBlocking {
        UserStatsStore.clear()
        UserStatsStore.addListeningSeconds(120L)
        val statsSong = Song(id = 313_131L, name = "统计用歌", source = SongSource.KUGOU, kugouHash = "H313")
        UserStatsStore.recordPlay(statsSong)
        val exported = UserStatsStore.stats.value
        assertEquals(1, exported.playCount)

        val document = BackupManager.buildJson(BackupManager.Options()).toString()
        assertFalse(
            "备份文档里的 stats 不能是 null",
            Json.parseToJsonElement(document).jsonObject["stats"] is JsonNull,
        )

        // 破坏现场。
        UserStatsStore.clear()
        UserStatsStore.addListeningSeconds(7L)
        assertEquals(0, UserStatsStore.stats.value.playCount)

        val result = BackupManager.import(context, document)
        assertTrue("导入必须成功，实际：$result", result is BackupManager.Result.Imported)

        val restored = UserStatsStore.stats.value
        assertEquals("userId 必须回到导出时的值", exported.userId, restored.userId)
        assertEquals("playCount 必须回到导出时的值", exported.playCount, restored.playCount)
        assertEquals("listeningSeconds 必须回到导出时的值", exported.listeningSeconds, restored.listeningSeconds)
        assertEquals(
            "单曲计数必须回到导出时的值",
            exported.songPlayCounts[statsSong.identityKey],
            restored.songPlayCounts[statsSong.identityKey],
        )
    }

    // ------------------------------------------------------------------
    // 失败路径：绝不抛异常、绝不破坏现有状态
    // ------------------------------------------------------------------

    @Test
    fun garbageInputFailsWithoutThrowingAndWithoutClobberingState() = runBlocking {
        UserStatsStore.clear()
        UserStatsStore.recordPlay(Song(id = 77_001L, name = "s", source = SongSource.KUGOU))
        UserStatsStore.addListeningSeconds(45L)
        val statsBefore = UserStatsStore.stats.value

        for (garbage in listOf(
            "",
            "not json at all",
            "{",
            "[]",
            "null",
            """{"format":"something.else","version":1}""",
            """{"format":"lulumusic.backup","version":9999,"settings":{"beans.audioQuality":"128k"}}""",
        )) {
            val result = BackupManager.import(context, garbage)
            assertTrue(
                "「${garbage.take(40)}」必须返回 Failed，实际：$result",
                result is BackupManager.Result.Failed,
            )
            assertTrue(
                "Failed 必须带一条非空的中文提示",
                (result as BackupManager.Result.Failed).message.isNotBlank(),
            )
        }

        assertEquals("失败的导入不得改动 userId", statsBefore.userId, UserStatsStore.userId)
        assertEquals("失败的导入不得改动 playCount", statsBefore.playCount, UserStatsStore.stats.value.playCount)
        assertEquals(
            "失败的导入不得改动 listeningSeconds",
            statsBefore.listeningSeconds,
            UserStatsStore.stats.value.listeningSeconds,
        )
    }

    @Test
    fun wrongFormatIsRejectedWithTheFormatInTheMessage() = runBlocking {
        val result = BackupManager.import(context, """{"format":"other.app.backup","version":1}""")
        assertTrue("format 不符必须失败", result is BackupManager.Result.Failed)
        assertTrue(
            "提示里应带上实际的 format，方便用户/我们排查：${(result as BackupManager.Result.Failed).message}",
            result.message.contains("other.app.backup"),
        )
    }

    @Test
    fun tooNewVersionIsRejectedAndDoesNotTouchStats() = runBlocking {
        UserStatsStore.clear()
        UserStatsStore.addListeningSeconds(11L)

        // 注意：文档里带了一个**会让统计变成另一个值**的 stats 段；版本过新时必须整份拒绝。
        val document = """{"format":"lulumusic.backup","version":${BackupManager.VERSION + 1},""" +
            """"stats":{"userId":"424242","listeningSeconds":9999,"playCount":7,"songPlayCounts":{}}}"""

        val result = BackupManager.import(context, document)
        assertTrue("更新的版本必须被拒绝", result is BackupManager.Result.Failed)
        val message = (result as BackupManager.Result.Failed).message
        assertTrue("提示里应说明版本过新：$message", message.contains((BackupManager.VERSION + 1).toString()))

        // 关键：拒绝之后**不能**把里面的内容偷偷应用上去。
        assertEquals("被拒绝的备份不得改动 listeningSeconds", 11L, UserStatsStore.stats.value.listeningSeconds)
        assertFalse("被拒绝的备份不得把 userId 换成文档里的 424242", UserStatsStore.userId == "424242")
    }

    @Test
    fun aBackupWithNoSettingsBlockIsAcceptedAndChangesNothing() = runBlocking {
        UserStatsStore.clear()
        UserStatsStore.addListeningSeconds(3L)

        val result = BackupManager.import(
            context,
            """{"format":"lulumusic.backup","version":1,"warnings":[]}""",
        )
        assertTrue("缺 settings / data 的合法备份应被接受（旧备份兼容），实际：$result", result is BackupManager.Result.Imported)

        assertEquals("缺失的段必须保持现状", 3L, UserStatsStore.stats.value.listeningSeconds)
    }

    @Test
    fun corruptedDataBlockIsRejectedSectionBySectionAndNeverWritesGarbageIntoPrefs() = runBlocking {        com.lulu.music.data.store.Prefs.writeString("beans.fav.qq.v1", "[]")

        val result = BackupManager.import(
            context,
            """{"format":"lulumusic.backup","version":1,"data":{"favorites":{"qq":{"not":"a list"}}}}""",
        )

        // 实测行为：data 段的异常被逐段 runCatching 收成一条 warning，**整个导入仍然成功**
        // （这样「备份里有一小段坏了」不会让用户丢掉全部数据）。这里把这个行为钉住。
        assertTrue("导入应整体成功（坏段只记 warning），实际：$result", result is BackupManager.Result.Imported)
        val warnings = (result as BackupManager.Result.Imported).warnings
        assertTrue("必须至少留下一条 warning 说明哪一段失败了，实际：$warnings", warnings.isNotEmpty())
        assertTrue(
            "warning 里应提到失败的键：$warnings",
            warnings.any { it.contains("beans.fav.qq.v1") },
        )

        assertEquals(
            "形状不符的段宁可整体跳过，也绝不能把坏 JSON 写进 SharedPreferences",
            "[]",
            com.lulu.music.data.store.Prefs.readString("beans.fav.qq.v1", ""),
        )
        assertTrue(
            "已有收藏键仍是可解析的列表",
            com.lulu.music.data.store.Prefs.readList("beans.fav.qq.v1", Song.serializer()).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 新 store：本地歌单 + 自定义封面（data.localPlaylists / data.covers + images[kind=cover]）
    // ------------------------------------------------------------------

    @Test
    fun localPlaylistsAndCoversRoundTripThroughTheBackupDocument() = runBlocking {
        val stamp = System.currentTimeMillis()
        val song = Song(
            id = 960_000L + (stamp % 1_000L),
            name = "备份里的本地歌单曲",
            artists = "歌手",
            source = SongSource.KUGOU,
            kugouHash = "HK-$stamp",
        )
        val playlist = requireNotNull(LocalPlaylistStore.create("备份歌单-$stamp", listOf(song)))

        // 造一张「已经在私有目录里」的自定义封面，并让它进入 store 的索引。
        val coverRelative = "${CoverStore.DIR_NAME}/backup-cover-$stamp.jpg"
        val coverFile = File(context.filesDir, coverRelative)
        coverFile.parentFile?.mkdirs()
        coverFile.writeBytes(ByteArray(64) { index -> index.toByte() })
        assertTrue("前置条件：封面索引写入成功", CoverStore.importJson("""{"${song.identityKey}":"$coverRelative"}"""))
        assertEquals("前置条件：封面可查", coverFile.absolutePath, CoverStore.coverPath(song))

        val document = BackupManager.buildJson(BackupManager.Options(includeImages = true)).toString()
        val root = Json.parseToJsonElement(document).jsonObject
        val data = root["data"]?.jsonObject
        assertNotNull("data 必须是对象", data)
        requireNotNull(data)

        // 1) 索引在 data 段（不依赖图片字节）。
        assertTrue(
            "data.localPlaylists 必须带上歌单 id：${data["localPlaylists"]}",
            data["localPlaylists"].toString().contains(playlist.id),
        )
        assertEquals(
            "data.covers 必须把 identityKey 映射到相对路径",
            coverRelative,
            data["covers"]?.jsonObject?.get(song.identityKey)?.jsonPrimitive?.contentOrNull,
        )

        // 2) 图片字节走 images[]，并标成封面（这样恢复时不会被当成壁纸）。
        val images = root["images"] as kotlinx.serialization.json.JsonArray
        val entry = images.firstOrNull { element ->
            element.jsonObject["path"]?.jsonPrimitive?.contentOrNull == coverRelative
        }
        assertNotNull("封面图片字节必须在 images[] 里", entry)
        requireNotNull(entry)
        assertEquals("cover", entry.jsonObject["kind"]?.jsonPrimitive?.contentOrNull)
        assertTrue(
            "图片必须以 base64 内联",
            !entry.jsonObject["base64"]?.jsonPrimitive?.contentOrNull.isNullOrBlank(),
        )

        // 3) 破坏现场：歌单没了、封面索引没了、文件也没了。
        assertTrue(LocalPlaylistStore.importJson("[]"))
        assertTrue(CoverStore.importJson("{}"))
        assertTrue(coverFile.delete())
        assertNull("前置条件：歌单已被清掉", LocalPlaylistStore.playlist(playlist.id))
        assertNull("前置条件：封面已被清掉", CoverStore.coverPath(song))

        // 4) 导入同一份备份：两样都要回来（而且**本会话内**立刻可见，不用重启）。
        val result = BackupManager.import(context, document)
        assertTrue("导入自己的导出必须成功，实际：$result", result is BackupManager.Result.Imported)

        val restored = LocalPlaylistStore.playlist(playlist.id)
        assertNotNull("本地歌单必须从备份恢复", restored)
        requireNotNull(restored)
        assertEquals("歌单名必须恢复", "备份歌单-$stamp", restored.name)
        assertEquals("歌单里的歌曲必须恢复", listOf(song), restored.songs)

        assertTrue("封面图片字节必须写回私有目录：${coverFile.absolutePath}", coverFile.isFile)
        assertEquals("封面索引必须指回那个文件", coverFile.absolutePath, CoverStore.coverPath(song))
    }

    @Test
    fun aBackupWithoutImagesKeepsOnlyTheCoverIndexAndPrunesDanglingEntries() = runBlocking {
        val stamp = System.currentTimeMillis() + 1
        val song = Song(
            id = 970_000L + (stamp % 1_000L),
            name = "无图备份的歌",
            source = SongSource.KUGOU,
            kugouHash = "HN-$stamp",
        )
        val coverRelative = "${CoverStore.DIR_NAME}/no-bytes-$stamp.jpg"
        val coverFile = File(context.filesDir, coverRelative)
        coverFile.parentFile?.mkdirs()
        coverFile.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(CoverStore.importJson("""{"${song.identityKey}":"$coverRelative"}"""))

        // includeImages = false：索引照样进备份，但字节不进。
        val document = BackupManager.buildJson(BackupManager.Options(includeImages = false)).toString()
        val root = Json.parseToJsonElement(document).jsonObject
        assertEquals("不含图片时 imageIncluded 必须是 false", false, root["imageIncluded"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(
            "索引仍然要在备份里（只是没有字节）",
            coverRelative,
            root["data"]?.jsonObject?.get("covers")?.jsonObject?.get(song.identityKey)?.jsonPrimitive?.contentOrNull,
        )

        // 导入到一台「没有这张图」的设备上：索引条目被剪掉，而不是留下一个指向不存在文件的死路径。
        assertTrue(coverFile.delete())
        assertTrue(CoverStore.importJson("{}"))
        val result = BackupManager.import(context, document)
        assertTrue("导入必须成功，实际：$result", result is BackupManager.Result.Imported)
        assertNull("没有图片字节时，封面条目必须被剪掉", CoverStore.coverPath(song))
        assertFalse(
            "被剪掉的条目不该继续留在索引里",
            CoverStore.exportJson().contains(coverRelative),
        )
    }

    @Test
    fun anOlderBackupWithoutTheNewStoreKeysIsStillAccepted() = runBlocking {
        // 老备份（没有 localPlaylists / covers）导入时不得报错、不得清掉现有歌单。
        val existing = requireNotNull(LocalPlaylistStore.create("旧备份导入前的歌单-${System.currentTimeMillis()}"))

        val result = BackupManager.import(
            context,
            """{"format":"lulumusic.backup","version":1,"data":{"searchHistory":["hello"]}}""",
        )
        assertTrue("旧备份必须被接受，实际：$result", result is BackupManager.Result.Imported)
        assertNotNull("缺失的新键必须保持现状", LocalPlaylistStore.playlist(existing.id))
    }

    // ------------------------------------------------------------------

    // 观测「DataStore 写入」的说明（保留在这里，避免后来者重复踩）：
    //
    // SettingsStore.set* 是 scope.launch { store.edit { … } }，而 Robolectric 每个测试方法都换
    // Application / filesDir，进程级单例的 DataStore 在跨方法时会**静默失败**（既不落盘、StateFlow
    // 也不回显）—— 实测证据与处理方式见 RobolectricSingletons。因此本文件**不**通过 SettingsStore
    // 去写偏好来断言持久化，而是：
    //  - 用 preferencesDataStoreRoundTripWithTheSameWiring 在真实 DataStore 上钉死「写 → 读 → 覆盖」；
    //  - 用 userStatsRoundTripThroughTheBackupDocument 钉死「导出 → 改坏 → 导入 → 恢复」的完整往返
    //    （统计走 SharedPreferences，不受 DataStore 单例影响）。
}
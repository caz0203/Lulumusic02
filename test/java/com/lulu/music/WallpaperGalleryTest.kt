package com.lulu.music

import android.app.Application
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.backup.BackupManager
import com.lulu.music.data.prefs.BeansWallpaperGallery
import com.lulu.music.data.prefs.BeansWallpaperLimit
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.prefs.beansAddWallpaper
import com.lulu.music.data.prefs.beansParseWallpaperPaths
import com.lulu.music.data.prefs.beansRemoveWallpaper
import com.lulu.music.data.prefs.beansSelectWallpaper
import com.lulu.music.data.prefs.beansSerializeWallpaperPaths
import com.lulu.music.data.prefs.beansWallpaperGallery
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

/**
 * 壁纸画廊（最多 5 张）的**逻辑 + 持久化 + 备份往返**测试。
 *
 * 覆盖用户真正依赖的四条：
 *  1. 老用户迁移 —— 只有 `beans.backgroundImagePath` 时那张壁纸必须成为画廊第一项，绝不丢；
 *  2. 追加 / 选中 / 删除 / 第 6 张被拒；
 *  3. 不变量：`beans.backgroundImagePath` 要么是空串（默认背景），要么**一定还在画廊里**
 *     （删掉当前那张时顺位回落，绝不指向刚被删掉的文件）；
 *  4. 含图备份带着**全部**画廊图片，导入后画廊重建、当前壁纸恢复。
 *
 * ## 为什么断言写在 DataStore 上而不是 StateFlow 上
 *
 * `SettingsStore` 的 `by lazy` StateFlow 在本仓库的 Robolectric 装置里**回显不可靠**
 * （见 `RobolectricSingletons` 的「已知限制」：每个测试方法换 filesDir，而 lazy 只绑第一个）。
 * 所以这里用 [dataStore] 反射读 `SettingsStore` **当前真正在用**的 DataStore —— 那才是
 * 「重启之后还在不在」的答案 —— 写入则一律走 `SettingsStore` 的 suspend 接口（写完了才返回）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WallpaperGalleryTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val pathsKey = stringPreferencesKey("beans.wallpaperPaths")
    private val currentKey = stringPreferencesKey("beans.backgroundImagePath")

    @Before
    fun bootApplication() {
        // 必须在任何 SettingsStore 读之前（DataStore 单例记着「第一个测试方法」的 filesDir）。
        RobolectricSingletons.resetSettingsDataStore(ApplicationProvider.getApplicationContext())
        ApplicationProvider.getApplicationContext<BeansApplication>()
        // 每个方法都从「画廊为空、没有当前壁纸」开始，方法之间互不影响。
        runBlocking { clearWallpaperPreferences() }
    }

    @After
    fun leaveNoWallpapersBehind() {
        // 这条清理不只是卫生问题：构建备份快照的 flow 是进程级 `by lazy`，如果它恰好绑定在
        // 本方法的内存 DataStore 上，留下一个非空画廊会让后面「设置页空态」的渲染测试变红。
        runBlocking { runCatching { clearWallpaperPreferences() } }
    }

    // ------------------------------------------------------------------
    // 1. 纯逻辑：序列化 / 迁移 / 追加 / 选中 / 删除 / 上限 / 不变量
    // ------------------------------------------------------------------

    @Test
    fun gallerySerialisationSurvivesCommasInPaths() {
        val paths = listOf("/data/x/wallpaper_a.jpg", "/data/x/wall,paper_b.jpg")

        val raw = beansSerializeWallpaperPaths(paths)

        assertEquals(paths, beansParseWallpaperPaths(raw))
        assertEquals(
            "路径里可能有逗号：分隔符必须是换行，不能用逗号（否则一张图会碎成两张）",
            2,
            beansParseWallpaperPaths(raw).size,
        )
        assertEquals("空画廊序列化成空串", "", beansSerializeWallpaperPaths(emptyList()))
        assertEquals(
            "空行 / 重复项都要被吃掉",
            listOf("/a.jpg"),
            beansParseWallpaperPaths("/a.jpg\n\n   \n/a.jpg"),
        )
    }

    @Test
    fun anExistingSingleWallpaperMigratesIntoTheFirstGallerySlot() {
        val migrated = beansWallpaperGallery(raw = "", currentPath = "/data/wallpapers/old.jpg")

        assertEquals("老用户那张壁纸必须成为画廊第一项（绝不丢）", listOf("/data/wallpapers/old.jpg"), migrated.paths)
        assertEquals("/data/wallpapers/old.jpg", migrated.current)
        assertTrue("迁移之后「当前」就在画廊里", migrated.currentInGallery)

        assertTrue("没有任何壁纸时画廊是空的", beansWallpaperGallery(raw = "", currentPath = "").paths.isEmpty())

        // 幂等：画廊里已经有这一张时不会重复插入。
        val raw = beansSerializeWallpaperPaths(migrated.paths)
        assertEquals(
            listOf("/data/wallpapers/old.jpg"),
            beansWallpaperGallery(raw, "/data/wallpapers/old.jpg").paths,
        )
    }

    @Test
    fun aCurrentWallpaperOutsideTheGalleryIsPulledBackIn() {
        val raw = beansSerializeWallpaperPaths(listOf("/a.jpg", "/b.jpg"))

        val gallery = beansWallpaperGallery(raw, "/c.jpg")

        assertEquals(listOf("/a.jpg", "/b.jpg", "/c.jpg"), gallery.paths)
        assertEquals("/c.jpg", gallery.current)
        assertTrue(gallery.currentInGallery)
    }

    @Test
    fun theSixthWallpaperIsRefusedByThePureLogicToo() {
        val six = (1..6).map { "/w/$it.jpg" }
        var gallery = BeansWallpaperGallery()

        six.forEach { gallery = beansAddWallpaper(gallery, it) }

        assertEquals(BeansWallpaperLimit, gallery.paths.size)
        assertFalse("第 6 张必须被拒绝", gallery.paths.contains("/w/6.jpg"))
        assertTrue(gallery.isFull)
        assertEquals("被拒绝时当前壁纸停在最后一张已接受的", "/w/5.jpg", gallery.current)
        assertEquals(
            "序列化也要强制上限（手工改偏好塞进 6 条时不会画出来）",
            BeansWallpaperLimit,
            beansParseWallpaperPaths(six.joinToString("\n")).size,
        )
    }

    @Test
    fun addMovesAnExistingEntryToTheEndWithoutGrowingTheGallery() {
        var gallery = BeansWallpaperGallery()
        listOf("/a.jpg", "/b.jpg", "/c.jpg").forEach { gallery = beansAddWallpaper(gallery, it) }

        gallery = beansAddWallpaper(gallery, "/a.jpg")

        assertEquals("重复追加 = 挪到末尾，不是新增一项", listOf("/b.jpg", "/c.jpg", "/a.jpg"), gallery.paths)
        assertEquals("/a.jpg", gallery.current)
    }

    @Test
    fun selectSwitchesTheCurrentAndKeepsEveryEntry() {
        val gallery = BeansWallpaperGallery(listOf("/a.jpg", "/b.jpg"), "/b.jpg")

        assertEquals("/a.jpg", beansSelectWallpaper(gallery, "/a.jpg").current)
        assertEquals(
            "选中空串 = 回到默认背景，画廊一张都不删",
            BeansWallpaperGallery(listOf("/a.jpg", "/b.jpg"), ""),
            beansSelectWallpaper(gallery, ""),
        )
        assertEquals(listOf("/a.jpg", "/b.jpg"), beansSelectWallpaper(gallery, "").paths)
    }

    @Test
    fun deletingTheCurrentWallpaperFallsToTheNextThenThePreviousThenTheDefault() {
        val full = BeansWallpaperGallery(listOf("/a.jpg", "/b.jpg", "/c.jpg"), "/b.jpg")

        val deletedMiddle = beansRemoveWallpaper(full, "/b.jpg")
        assertEquals(listOf("/a.jpg", "/c.jpg"), deletedMiddle.paths)
        assertEquals("删中间那张 → 顺位到原来的下一张", "/c.jpg", deletedMiddle.current)

        val deletedLast = beansRemoveWallpaper(full, "/c.jpg")
        assertEquals(listOf("/a.jpg", "/b.jpg"), deletedLast.paths)
        assertEquals("删最后那张 → 顺位到上一张", "/b.jpg", deletedLast.current)

        val deletedOnly = beansRemoveWallpaper(BeansWallpaperGallery(listOf("/a.jpg"), "/a.jpg"), "/a.jpg")
        assertTrue(deletedOnly.paths.isEmpty())
        assertEquals("一张都不剩 → 回到默认背景", "", deletedOnly.current)

        val notCurrent = beansRemoveWallpaper(full, "/a.jpg")
        assertEquals("删的不是当前那张 → 当前值不动", "/b.jpg", notCurrent.current)
    }

    @Test
    fun currentNeverDanglesAcrossAnyOperationSequence() {
        var gallery = BeansWallpaperGallery()
        var step = 0

        fun check() {
            assertTrue(
                "第 $step 步：画廊不得超过 $BeansWallpaperLimit 张，实际 ${gallery.paths}",
                gallery.paths.size <= BeansWallpaperLimit,
            )
            assertEquals("第 $step 步：画廊不得有重复项", gallery.paths.size, gallery.paths.distinct().size)
            assertTrue(
                "第 $step 步：当前值要么是默认背景（空串），要么必须还在画廊里：$gallery",
                gallery.current.isEmpty() || gallery.current in gallery.paths,
            )
        }

        check()
        listOf<(BeansWallpaperGallery) -> BeansWallpaperGallery>(
            { beansAddWallpaper(it, "/a.jpg") },
            { beansAddWallpaper(it, "/b.jpg") },
            { beansSelectWallpaper(it, "/a.jpg") },
            { beansRemoveWallpaper(it, "/a.jpg") },
            { beansAddWallpaper(it, "/c.jpg") },
            { beansSelectWallpaper(it, "/c.jpg") },
            { beansRemoveWallpaper(it, "/c.jpg") },
            { beansRemoveWallpaper(it, "/b.jpg") },
        ).forEach { operation ->
            step += 1
            gallery = operation(gallery)
            check()
        }
        assertEquals("最后一张也删掉之后必须完全回到默认背景", BeansWallpaperGallery(), gallery)
    }

    // ------------------------------------------------------------------
    // 2. 持久化：真的落进 DataStore（重启之后还在）
    // ------------------------------------------------------------------

    @Test
    fun addingWallpapersPersistsTheGalleryAndTheCurrentPath() = runBlocking {
        val first = wallpaperFile("wallpaper_a.jpg")
        val second = wallpaperFile("wallpaper_b.jpg")

        assertTrue(SettingsStore.addWallpaper(first.absolutePath))
        assertTrue(SettingsStore.addWallpaper(second.absolutePath))

        assertEquals(
            "画廊必须是换行分隔的绝对路径（顺序 = 上传顺序）",
            listOf(first.absolutePath, second.absolutePath).joinToString("\n"),
            storedPaths(),
        )
        assertEquals("刚上传的那张就是当前壁纸", second.absolutePath, storedCurrent())
    }

    @Test
    fun selectingAThumbnailOnlyRewritesTheCurrentPath() = runBlocking {
        val first = wallpaperFile("wallpaper_a.jpg")
        val second = wallpaperFile("wallpaper_b.jpg")
        assertTrue(SettingsStore.addWallpaper(first.absolutePath))
        assertTrue(SettingsStore.addWallpaper(second.absolutePath))

        assertTrue(SettingsStore.selectWallpaper(first.absolutePath))
        assertEquals(first.absolutePath, storedCurrent())
        assertEquals(
            "切换「当前」不得改动画廊",
            listOf(first.absolutePath, second.absolutePath).joinToString("\n"),
            storedPaths(),
        )

        assertTrue(SettingsStore.selectWallpaper(""))
        assertEquals("清除当前背景 → 回到默认背景", "", storedCurrent())
        assertEquals(
            "清除当前背景不得删掉画廊里的任何一张",
            listOf(first.absolutePath, second.absolutePath).joinToString("\n"),
            storedPaths(),
        )
    }

    @Test
    fun theSixthWallpaperIsRefusedAndTheStoreIsLeftUntouched() = runBlocking {
        val files = (1..5).map { wallpaperFile("wallpaper_$it.jpg") }
        files.forEach { assertTrue(SettingsStore.addWallpaper(it.absolutePath)) }
        val sixth = wallpaperFile("wallpaper_6.jpg")
        val beforePaths = storedPaths()
        val beforeCurrent = storedCurrent()

        assertFalse("第 6 张必须被拒绝（返回 false，界面据此弹说明）", SettingsStore.addWallpaper(sixth.absolutePath))

        assertEquals("被拒绝时画廊必须原样停在 5 张", beforePaths, storedPaths())
        assertEquals("被拒绝时当前壁纸不得改变", beforeCurrent, storedCurrent())
        assertEquals(files.map { it.absolutePath }.joinToString("\n"), storedPaths())
    }

    @Test
    fun deletingTheCurrentWallpaperDeletesItsFileAndNeverLeavesADanglingPath() = runBlocking {
        val first = wallpaperFile("wallpaper_a.jpg")
        val second = wallpaperFile("wallpaper_b.jpg")
        assertTrue(SettingsStore.addWallpaper(first.absolutePath))
        assertTrue(SettingsStore.addWallpaper(second.absolutePath))

        assertTrue(SettingsStore.removeWallpaper(second.absolutePath))

        assertEquals("画廊里只剩第一张", first.absolutePath, storedPaths())
        assertEquals("当前壁纸必须顺位到还活着的那张", first.absolutePath, storedCurrent())
        assertFalse("被删壁纸的副本必须从磁盘上消失", second.exists())
        assertTrue("没被删的那张必须原封不动", first.exists())
        assertFalse("当前值绝不能指向刚被删掉的文件", storedCurrent() == second.absolutePath)
    }

    @Test
    fun deletingTheLastWallpaperFallsBackToTheDefaultBackground() = runBlocking {
        val only = wallpaperFile("wallpaper_only.jpg")
        assertTrue(SettingsStore.addWallpaper(only.absolutePath))

        assertTrue(SettingsStore.removeWallpaper(only.absolutePath))

        assertEquals("画廊清空", "", storedPaths())
        assertEquals("一张都不剩时必须回到默认背景", "", storedCurrent())
        assertFalse("文件也要删掉", only.exists())
    }

    @Test
    fun deletingAWallpaperOutsideTheWallpaperDirectoryNeverTouchesTheOriginalFile() = runBlocking {
        // 老用户的 beans.backgroundImagePath 历史上可能指向别处（用户自己的图）。
        val outside = File(context.filesDir, "outside-original.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        assertTrue(SettingsStore.selectWallpaper(outside.absolutePath))
        assertEquals("不在画廊里的路径被补进画廊并设为当前", outside.absolutePath, storedCurrent())

        assertTrue(SettingsStore.removeWallpaper(outside.absolutePath))

        assertTrue("filesDir/wallpapers 之外的文件一个字节都不许动", outside.exists())
        assertEquals("", storedPaths())
        assertEquals("", storedCurrent())
    }

    @Test
    fun aLegacySingleWallpaperMigratesOnTheFirstStoreMutation() = runBlocking {
        val legacy = wallpaperFile("wallpaper_legacy.jpg")
        // 老用户的现场：只有 beans.backgroundImagePath，从来没有 beans.wallpaperPaths。
        //
        // 这里**直接写 DataStore**，而不是走 `SettingsStore.setBackgroundImagePath` 那条
        // `scope.launch { store.edit { … } }` 的异步路径：本仓库的 Robolectric 装置里模块级 scope 的
        // fire-and-forget 写在跨测试方法时不可观测（见 RobolectricSingletons 的「已知限制」），
        // 而这条用例要钉的是「画廊事务里的迁移」，不是那个 setter（它覆盖的写入路径由
        // `addingWallpapersPersistTheGalleryAndTheCurrentPath` 等 await 版本用例钉住）。
        dataStore().edit { it[currentKey] = legacy.absolutePath }
        assertEquals("前置条件：老键必须真的写进 DataStore", legacy.absolutePath, storedCurrent())
        assertNull("前置条件：画廊键还不存在", storedPaths())

        val added = wallpaperFile("wallpaper_new.jpg")
        assertTrue(SettingsStore.addWallpaper(added.absolutePath))

        assertEquals(
            "迁移必须发生：老壁纸成为画廊第一项，新壁纸排在后面（老壁纸绝不丢）",
            listOf(legacy.absolutePath, added.absolutePath).joinToString("\n"),
            storedPaths(),
        )
        assertEquals(added.absolutePath, storedCurrent())
    }

    // ------------------------------------------------------------------
    // 3. 备份：全部画廊图片都进备份
    // ------------------------------------------------------------------

    @Test
    fun aBackupWithImagesCarriesEveryGalleryWallpaperNotJustTheCurrentOne() = runBlocking {
        val files = (1..3).map { wallpaperFile("wallpaper_$it.jpg") }
        files.forEach { assertTrue(SettingsStore.addWallpaper(it.absolutePath)) }

        val root = Json.parseToJsonElement(
            BackupManager.buildJson(BackupManager.Options(includeImages = true)).toString(),
        ).jsonObject

        assertEquals(
            "含图备份必须显式写 imageIncluded=true",
            true,
            root["imageIncluded"]?.jsonPrimitive?.booleanOrNull,
        )
        assertTrue(
            "settings 里必须带上画廊键（结构契约：列表走 settings，字节走 images）",
            root["settings"]?.jsonObject?.containsKey("beans.wallpaperPaths") == true,
        )

        val wallpapers = (root["images"] as JsonArray)
            .map { it.jsonObject }
            .filter { it["kind"]?.jsonPrimitive?.contentOrNull == "wallpaper" }
        assertEquals("3 张画廊壁纸都必须进 images[]（不是只带当前那张）", 3, wallpapers.size)
        files.forEach { file ->
            val entry = wallpapers.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == file.name }
            assertNotNull("${file.name} 必须在 images[] 里", entry)
            requireNotNull(entry)
            assertTrue(
                "${file.name} 必须以 base64 内联",
                !entry["base64"]?.jsonPrimitive?.contentOrNull.isNullOrBlank(),
            )
            assertEquals(
                "图片按相对 filesDir 的路径记录（导入端据此写回）",
                "wallpapers/${file.name}",
                entry["path"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    @Test
    fun importingABackupRebuildsTheWholeGalleryAndRestoresTheActiveWallpaper() = runBlocking {
        val names = (1..3).map { "wallpaper_$it.jpg" }
        val files = names.map { wallpaperFile(it) }
        val bytes = files.associate { it.name to it.readBytes() }
        files.forEach { assertTrue("前置条件：文件被删掉", it.delete()) }
        clearWallpaperPreferences()
        assertNull("前置条件：本机没有壁纸记录", storedPaths())

        val document = backupDocument(
            galleryPaths = files.map { it.absolutePath },
            activePath = files.last().absolutePath,
            images = bytes,
        )
        val result = BackupManager.import(context, document)

        assertTrue("导入必须成功，实际：$result", result is BackupManager.Result.Imported)
        files.forEach { assertTrue("${it.name} 的字节必须写回磁盘", it.isFile) }
        assertEquals(
            "画廊必须按原顺序重建出 3 张",
            files.map { it.absolutePath },
            beansParseWallpaperPaths(storedPaths().orEmpty()),
        )
        assertEquals("当前壁纸必须恢复成导出时的那一张", files.last().absolutePath, storedCurrent())
    }

    @Test
    fun aBackupFromAnotherDeviceRebuildsTheGalleryByFileName() = runBlocking {
        val names = listOf("wallpaper_1.jpg", "wallpaper_2.jpg")
        // 换机后 filesDir 前缀会变：备份里记的是导出那台设备的绝对路径。
        val foreignDir = "/data/user/0/com.lulu.music/files/wallpapers"

        val result = BackupManager.import(
            context,
            backupDocument(
                galleryPaths = names.map { "$foreignDir/$it" },
                activePath = "$foreignDir/${names.last()}",
                images = names.associateWith { name -> ByteArray(16) { index -> (index + name.length).toByte() } },
            ),
        )

        assertTrue("导入必须成功，实际：$result", result is BackupManager.Result.Imported)
        val local = names.map { File(context.filesDir, "wallpapers/$it").absolutePath }
        local.forEach { assertTrue("图片必须写回本机私有目录：$it", File(it).isFile) }
        assertEquals(
            "换机后必须按文件名把画廊指回本机真实路径（否则全成了死路径）",
            local,
            beansParseWallpaperPaths(storedPaths().orEmpty()),
        )
        assertEquals("当前壁纸也要指回本机的那一份", local.last(), storedCurrent())
    }

    @Test
    fun galleryEntriesWithoutAFileAreDroppedInsteadOfLeavingDanglingPaths() = runBlocking {
        val present = "wallpaper_present.jpg"
        val missing = "wallpaper_missing.jpg"
        val foreignDir = "/data/user/0/com.lulu.music/files/wallpapers"

        val result = BackupManager.import(
            context,
            backupDocument(
                galleryPaths = listOf("$foreignDir/$missing", "$foreignDir/$present"),
                // 当前壁纸正好是那张**不存在**的：必须回落到唯一存在的那张，而不是留一条死路径。
                activePath = "$foreignDir/$missing",
                images = mapOf(present to ByteArray(8) { 3 }),
            ),
        )

        assertTrue("导入必须成功，实际：$result", result is BackupManager.Result.Imported)
        val local = File(context.filesDir, "wallpapers/$present").absolutePath
        assertEquals(
            "没有字节的条目必须被丢掉，绝不留下指向不存在文件的死路径",
            listOf(local),
            beansParseWallpaperPaths(storedPaths().orEmpty()),
        )
        assertEquals("当前值必须回落到真正存在的那张", local, storedCurrent())
    }

    @Test
    fun aBackupWithoutImageBytesNeverWritesDanglingWallpaperPaths() = runBlocking {
        val file = wallpaperFile("wallpaper_a.jpg")
        assertTrue(SettingsStore.addWallpaper(file.absolutePath))

        // includeImages = false：画廊列表照样在 settings 里，但一个图片字节都不带。
        val root = Json.parseToJsonElement(
            BackupManager.buildJson(BackupManager.Options(includeImages = false)).toString(),
        ).jsonObject
        assertEquals("不含图时 images 必须是空数组", 0, (root["images"] as JsonArray).size)
        assertTrue(
            "画廊键仍然要在文档里（只是没有字节）",
            root["settings"]?.jsonObject?.containsKey("beans.wallpaperPaths") == true,
        )

        // 换一台「没有这张图」的设备：绝不能把路径写进去。
        assertTrue(file.delete())
        clearWallpaperPreferences()
        val result = BackupManager.import(context, root.toString())

        assertTrue("导入必须成功，实际：$result", result is BackupManager.Result.Imported)
        assertNull("没有图片字节时不得写画廊", storedPaths())
        assertNull("没有图片字节时不得写当前壁纸", storedCurrent())
    }

    // ------------------------------------------------------------------
    // 设施
    // ------------------------------------------------------------------

    /** 造一张「已经在私有壁纸目录里」的图片（内容不重要，长度固定）。 */
    private fun wallpaperFile(name: String): File {
        val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(ByteArray(32) { index -> index.toByte() }) }
    }

    /**
     * 手工拼一份**与导出格式完全一致**的备份文档。
     *
     * 为什么不用 `buildJson` 直接导出：导出读的是 `SettingsStore` 的 `by lazy` StateFlow，
     * 它在 Robolectric 下可能绑在别的测试方法的 DataStore 上（见 `RobolectricSingletons`），
     * 于是文档里的 `beans.wallpaperPaths` 会不稳定。导出侧的形状由
     * [aBackupWithImagesCarriesEveryGalleryWallpaperNotJustTheCurrentOne] 单独钉住，
     * 这里专心验证**导入侧**：列表走 settings、字节走 images、按文件名找回本机路径。
     */
    private fun backupDocument(
        galleryPaths: List<String>,
        activePath: String,
        images: Map<String, ByteArray>,
    ): String = buildJsonObject {
        put("format", JsonPrimitive(BackupManager.FORMAT))
        put("version", JsonPrimitive(BackupManager.VERSION))
        put("imageIncluded", JsonPrimitive(images.isNotEmpty()))
        put("settings", buildJsonObject {
            put("beans.wallpaperPaths", JsonPrimitive(galleryPaths.joinToString("\n")))
            put("beans.backgroundImagePath", JsonPrimitive(activePath))
        })
        put(
            "images",
            JsonArray(
                images.map { (name, bytes) ->
                    buildJsonObject {
                        put("kind", JsonPrimitive("wallpaper"))
                        put("name", JsonPrimitive(name))
                        put("path", JsonPrimitive("wallpapers/$name"))
                        put("bytes", JsonPrimitive(bytes.size))
                        put("base64", JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                    }
                },
            ),
        )
    }.toString()

    private suspend fun storedPaths(): String? = dataStore().data.first()[pathsKey]

    private suspend fun storedCurrent(): String? = dataStore().data.first()[currentKey]

    private suspend fun clearWallpaperPreferences() {
        dataStore().edit { preferences ->
            preferences.remove(pathsKey)
            preferences.remove(currentKey)
        }
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

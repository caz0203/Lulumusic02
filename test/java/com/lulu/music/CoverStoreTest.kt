package com.lulu.music

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource
import com.lulu.music.data.store.AndroidCoverImageProcessor
import com.lulu.music.data.store.CoverImageProcessor
import com.lulu.music.data.store.CoverStore
import com.lulu.music.data.store.coverInSampleSize
import com.lulu.music.data.store.coverScaledSize
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * [CoverStore] 的行为测试。
 *
 * ## 为什么注入一个「不做解码」的图片处理器
 *
 * 真机上 `setCover` 走 `BitmapFactory` 解码 + 缩放 + JPEG 压缩；但 JVM（Robolectric）里
 * `BitmapFactory` 是影子实现，解码结果不确定。所以这里用 [CoverStore.resetForTests] 注入一个
 * 恒等处理器，专门验证**与解码无关**的那部分契约：字节有没有真的写进私有目录、索引对不对、
 * 旧文件有没有被删掉、清除有没有连文件一起清、导出能不能导回来。
 * 「12MP 不会被原样存下来」这条节能结论由 [coverInSampleSize] / [coverScaledSize] 两个纯函数钉死
 * （生产处理器就是拿它们算的目标尺寸）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val identityProcessor = CoverImageProcessor { bytes -> bytes }

    private var seq = 0

    @Before
    fun bootApplication() {
        ApplicationProvider.getApplicationContext<BeansApplication>()
        // 顺序：先 init（绑定当前测试方法的 filesDir），再 reset（用新的上下文重读索引）。
        CoverStore.init(context)
        CoverStore.resetForTests(identityProcessor)
    }

    @After
    fun restoreProcessor() {
        // 单例是全进程共享的：别把假处理器留给别的测试类。
        CoverStore.resetForTests(null)
    }

    private fun unique(): Long = System.nanoTime() + seq++

    private fun song(id: Long = unique()) = Song(
        id = id,
        name = "cover-$id",
        artists = "歌手",
        source = SongSource.KUGOU,
        kugouHash = "HASH-$id",
    )

    private fun coversDir(): File = File(context.filesDir, CoverStore.DIR_NAME)

    // ------------------------------------------------------------------
    // 设置 / 查询 / 清除
    // ------------------------------------------------------------------

    @Test
    fun setCoverWritesAPrivateFileAndLookupFindsIt() {
        val target = song()
        assertNull("前置条件：还没有封面", CoverStore.coverPath(target))

        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue("设置封面必须成功", CoverStore.setCoverFromBytes(target, bytes))

        val path = CoverStore.coverPath(target)
        assertNotNull("设置之后必须能查到封面", path)
        val file = File(requireNotNull(path))
        assertTrue("封面文件必须真的存在：${file.absolutePath}", file.isFile)
        assertTrue(
            "封面必须写在私有 filesDir 下的 ${CoverStore.DIR_NAME}/ 里，实际 ${file.absolutePath}",
            file.absolutePath.startsWith(coversDir().absolutePath),
        )
        assertArrayEquals("写进去的必须就是处理后的字节", bytes, file.readBytes())
        assertEquals(
            "StateFlow 必须暴露同一路径（Compose 靠它重组）",
            path,
            CoverStore.covers.value[target.identityKey],
        )
    }

    @Test
    fun setCoverFromAContentUriCopiesTheBytesInsteadOfKeepingTheUri() {
        val target = song()
        val uri = Uri.parse("content://com.lulu.music.test/cover/${target.id}")
        val payload = "raw-image-bytes".toByteArray()
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(payload))

        assertTrue("从 content:// 设置封面必须成功", CoverStore.setCover(target, uri))

        val path = requireNotNull(CoverStore.coverPath(target))
        assertTrue("content:// 的字节必须被复制到私有目录", path.startsWith(coversDir().absolutePath))
        assertFalse("绝不能把 content:// 本身当成封面路径", path.startsWith("content://"))
        assertArrayEquals(payload, File(path).readBytes())
    }

    @Test
    fun setCoverFailsSafelyForAnUnreadableUri() {
        val target = song()
        // 没有注册过输入流 → openInputStream 返回 null；必须返回 false，绝不抛。
        assertFalse(CoverStore.setCover(target, Uri.parse("content://com.lulu.music.test/missing")))
        assertNull(CoverStore.coverPath(target))
    }

    @Test
    fun clearDeletesTheFileAndTheIndexEntry() {
        val target = song()
        assertTrue(CoverStore.setCoverFromBytes(target, byteArrayOf(7, 7, 7)))
        val path = requireNotNull(CoverStore.coverPath(target))

        assertTrue("清除必须返回 true", CoverStore.clear(target))

        assertNull("清除后不能再查到封面", CoverStore.coverPath(target))
        assertFalse("清除必须把文件也删掉：$path", File(path).exists())
        assertFalse("StateFlow 里也不能再有这个条目", CoverStore.covers.value.containsKey(target.identityKey))
        assertFalse("重复清除必须返回 false", CoverStore.clear(target))
    }

    @Test
    fun replacingACoverDeletesThePreviousFile() {
        val target = song()
        // 造一个「旧命名规则留下的文件 + 指向它的索引」，模拟历史数据 / 从备份导入的另一个路径。
        val relative = "${CoverStore.DIR_NAME}/legacy-${target.id}.jpg"
        val legacy = File(context.filesDir, relative)
        legacy.parentFile?.mkdirs()
        legacy.writeBytes(byteArrayOf(3, 3, 3))
        assertTrue(CoverStore.importJson("""{"${target.identityKey}":"$relative"}"""))
        assertNotNull("前置条件：旧封面可查", CoverStore.coverPath(target))

        assertTrue(CoverStore.setCoverFromBytes(target, byteArrayOf(1, 2)))

        assertFalse("被替换掉的旧文件必须删掉", legacy.exists())
        assertNotNull("新封面必须可用", CoverStore.coverPath(target))
    }

    // ------------------------------------------------------------------
    // 重启 / 备份往返
    // ------------------------------------------------------------------

    @Test
    fun coversSurviveAReloadAndRoundTripThroughJson() {
        val target = song()
        assertTrue(CoverStore.setCoverFromBytes(target, byteArrayOf(5, 6, 7)))
        val path = requireNotNull(CoverStore.coverPath(target))
        val json = CoverStore.exportJson()
        assertTrue("导出的 JSON 必须带上 identityKey", json.contains(target.identityKey))

        CoverStore.reload()
        assertEquals("重载（= 进程重启）之后封面必须还在", path, CoverStore.coverPath(target))

        assertTrue("导入自己导出的索引必须成功", CoverStore.importJson(json))
        assertEquals("导入后必须指回同一个文件", path, CoverStore.coverPath(target))
    }

    @Test
    fun aDanglingEntryIsPrunedOnLoad() {
        val target = song()
        val relative = "${CoverStore.DIR_NAME}/dangling-${target.id}.jpg"
        val file = File(context.filesDir, relative)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1))
        assertTrue(CoverStore.importJson("""{"${target.identityKey}":"$relative"}"""))
        assertEquals("前置条件：条目生效", file.absolutePath, CoverStore.coverPath(target))

        // 用户用文件管理器删掉了文件（或恢复了一份不带图片字节的备份）。
        assertTrue(file.delete())
        CoverStore.reload()

        assertNull("指向不存在文件的条目必须被剪掉", CoverStore.coverPath(target))
        assertFalse("剪掉的条目不能再被导出", CoverStore.exportJson().contains(relative))
    }

    @Test
    fun importJsonDropsPathsOutsideTheCoverDirectory() {
        assertTrue(
            CoverStore.importJson(
                """{"kugou-1":"/data/data/com.lulu.music/files/evil.jpg",""" +
                    """"kugou-2":"../evil.jpg",""" +
                    """"kugou-3":"wallpapers/not-a-cover.jpg",""" +
                    """"kugou-4":"/sdcard/evil.jpg"}""",
            ),
        )

        val exported = CoverStore.exportJson()
        assertFalse("绝对路径必须被丢弃：$exported", exported.contains("evil"))
        assertFalse("壁纸目录不是封面目录，必须被丢弃：$exported", exported.contains("wallpapers"))
        assertTrue("被丢弃的条目不该出现在导出里", exported.trim() == "{}")
    }

    @Test
    fun importJsonRejectsGarbageWithoutLosingExistingCovers() {
        val target = song()
        assertTrue(CoverStore.setCoverFromBytes(target, byteArrayOf(2, 4, 6)))
        val path = CoverStore.coverPath(target)

        for (bad in listOf("", "not json", "[", "[1,2,3]", "\"text\"")) {
            assertFalse("「${bad.take(20)}」必须被拒绝", CoverStore.importJson(bad))
        }

        assertEquals("失败的导入不得改动现有封面", path, CoverStore.coverPath(target))
    }

    // ------------------------------------------------------------------
    // 体积：12MP 不会被原样存下来（纯函数部分，可确定性验证）
    // ------------------------------------------------------------------

    @Test
    fun a12MegapixelPhotoIsSampledAndScaledDownToAtMost1024px() {
        // 4000x3000（12MP）→ 采样 2 → 解码 2000x1500（3MP）→ 缩放到 1024x768（≈0.79MP）。
        assertEquals(2, coverInSampleSize(4000, 3000, 1024))
        assertEquals(1024 to 768, coverScaledSize(2000, 1500, 1024))

        // 已经够小的图不采样、不缩放。
        assertEquals(1, coverInSampleSize(800, 600, 1024))
        assertEquals(600 to 400, coverScaledSize(600, 400, 1024))

        // 极端长条：短边不满足采样条件，由长边兜底，最终仍然不超过 1024。
        assertEquals(2, coverInSampleSize(4000, 200, 1024))
        assertEquals(1024 to 51, coverScaledSize(2000, 100, 1024))

        // 不合法输入不炸。
        assertEquals(1, coverInSampleSize(0, 0, 1024))
        assertEquals(1 to 1, coverScaledSize(-1, 5, 1024))
    }

    @Test
    fun theProductionProcessorNeverThrowsOnGarbage() {
        // 真机上这里会解码 JPEG / PNG；JVM 里 BitmapFactory 是影子实现，但契约是**绝不抛**。
        val result = runCatching { AndroidCoverImageProcessor.prepare(byteArrayOf(1, 2, 3)) }
        assertTrue("真实图片处理器遇到坏字节不得抛异常：$result", result.isSuccess)

        val empty = runCatching { AndroidCoverImageProcessor.prepare(ByteArray(0)) }
        assertTrue("空字节也不得抛异常：$empty", empty.isSuccess)
        assertNull("空字节必须判定为「不是图片」", empty.getOrNull())
    }
}

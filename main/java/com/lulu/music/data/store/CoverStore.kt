package com.lulu.music.data.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.lulu.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * 把任意图片字节转成「适合长期保存」的小图（JPEG 字节）；返回 null 表示这不是能处理的图片。
 *
 * 抽成接口只有一个原因：Android 的 `BitmapFactory` 在 JVM 单测（Robolectric）里不做真实解码，
 * 想验证「字节真的被写进私有目录 / 旧文件真的被删掉」就必须能注入一个确定性的实现。
 */
internal fun interface CoverImageProcessor {
    fun prepare(bytes: ByteArray): ByteArray?
}

/**
 * 每首歌的自定义封面仓库（重活是「把用户选的图复制进应用私有目录」）。
 *
 * ## 为什么必须复制字节
 *
 * 相册返回的是 `content://` URI，它的读权限只在本次授权（很多机型是「本次运行」）内有效。
 * 一旦把 URI 存下来，重启后封面就变成空白甚至抛 `SecurityException`。所以这里只接受
 * 「读 URI → 处理 → 写进 `filesDir/covers/`」这一条路径，落盘后**只记录相对路径**。
 *
 * ## 数据存在哪、能活过什么
 *
 *  - 图片：`filesDir/covers/<identityKey 的 sha1>.jpg`（应用私有目录，其他应用读不到）。
 *  - 索引：`SharedPreferences("beans_prefs")` 的 `beans.covers.v1` 键，JSON 对象
 *    `{ "<Song.identityKey>": "covers/<文件>" }`（相对 `filesDir` 的路径，换机恢复时可以直接放回去）。
 *  - 进程重启 / 覆盖安装：**保留**；卸载：**丢失**（整个应用数据目录被删）。
 *
 * ## 体积
 *
 * 12MP 的原图（约 3–4 MB 甚至更大）不会被原样存下来：先按 [MAX_DIMENSION] 采样解码
 * （[coverInSampleSize]），再缩放到最长边 1024，最后压成 JPEG；若仍超过 [MAX_BYTES]（600 KB）
 * 就逐档降质量、必要时再缩一半。读入的原始字节也有 [MAX_INPUT_BYTES] 上限，防止一次 OOM。
 */
object CoverStore {

    private const val TAG = "CoverStore"

    /** 封面目录名（相对 `filesDir`）；`BackupManager` 备份图片时按它收集。 */
    const val DIR_NAME = "covers"

    private const val KEY = "beans.covers.v1"

    /** 存下来的封面最长边（px）。 */
    internal const val MAX_DIMENSION = 1024

    /** 单张封面的目标体积上限（字节）。 */
    internal const val MAX_BYTES = 600 * 1024

    /** 从 `content://` 读入的原始字节上限（防止用户选了一张超大图把内存吃光）。 */
    internal const val MAX_INPUT_BYTES = 32 * 1024 * 1024

    private var appContext: Context? = null
    private var loaded = false

    /** 落盘用的形式：identityKey -> 相对 `filesDir` 的路径（`covers/xxx.jpg`）。 */
    private var stored: Map<String, String> = emptyMap()

    private val _covers = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 当前生效的封面：identityKey -> **绝对路径**（只包含文件真实存在的条目）。
     *
     * 绝对路径是给 UI 直接喂 Coil / `File` 用的；条目变化会推动 Compose 重组，
     * 所以「设置 / 清除封面」不需要调用方自己去刷新列表。
     */
    val covers: StateFlow<Map<String, String>> = _covers.asStateFlow()

    private val _revision = MutableStateFlow(0L)

    /**
     * 封面的「内容版本号」：每次设置 / 清除 / 导入封面都会自增。
     *
     * 为什么需要它（对 UI 是必须的）：同一首歌的封面文件名是**固定的**（`identityKey` 的 sha1），
     * 「换封面」就是覆盖同一个文件，所以 [covers] 这个 Map 的内容可能一个字都没变 ——
     * StateFlow 不会发出新值，UI 也就不会重组，用户会看到「提示说已更新，图上还是旧封面」。
     * 把版本号一起当作渲染 key 就能立刻刷新（Coil 侧由 `FileKeyer` 用 lastModified 区分）。
     *
     * 这是纯增量：不改变任何现有 API / 行为，只是多暴露一个计数器。
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** 测试钩子：替换图片处理实现（传 null 恢复生产实现）。 */
    @VisibleForTesting
    internal var imageProcessor: CoverImageProcessor = AndroidCoverImageProcessor

    /** 幂等；绑定应用上下文并读回封面索引。`BeansApplication.onCreate` 调用。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    /**
     * 读回封面索引。幂等，且**在 [init] 之前调用是安全的**（什么都不做，也不闭合闸门）。
     *
     * 顺带把「指向不存在的文件」的条目剪掉并落盘：换机恢复了一份不带图片字节的备份、
     * 或者用户用文件管理器删了目录时，索引不会永远留着一堆死路径。
     */
    fun load() {
        if (loaded) return
        val context = appContext ?: return
        loaded = true
        val raw = runCatching { Prefs.readStringMap(KEY) }
            .onFailure { Log.w(TAG, "load failed: ${it.message}") }
            .getOrDefault(emptyMap())
        val kept = raw.filter { (_, relative) -> isSafeRelativePath(relative) && fileOf(context, relative).isFile }
        stored = kept
        if (kept.size != raw.size) persist(kept)
        publish()
    }

    /**
     * 丢掉内存状态并重新读盘。备份导入把索引直接写进 SharedPreferences 之后调用，
     * 让内存里的 [covers] 跟上（导入的图片字节这时已经写回磁盘）。
     */
    fun reload() {
        loaded = false
        stored = emptyMap()
        _covers.value = emptyMap()
        load()
    }

    // ---------------------------------------------------------------------------------------
    // 读
    // ---------------------------------------------------------------------------------------

    /** 这首歌的自定义封面文件；没有（或文件已消失）时返回 null。 */
    fun coverFile(song: Song?): File? = coverFile(song?.identityKey)

    fun coverFile(identityKey: String?): File? {
        val key = identityKey?.takeIf { it.isNotBlank() } ?: return null
        load()
        val context = appContext ?: return null
        val relative = stored[key] ?: return null
        if (!isSafeRelativePath(relative)) return null
        val file = fileOf(context, relative)
        return file.takeIf { it.isFile }
    }

    /** 这首歌的自定义封面绝对路径；没有时返回 null。 */
    fun coverPath(song: Song?): String? = coverFile(song)?.absolutePath

    fun coverPath(identityKey: String?): String? = coverFile(identityKey)?.absolutePath

    // ---------------------------------------------------------------------------------------
    // 写
    // ---------------------------------------------------------------------------------------

    /**
     * 用用户刚挑的图片设置封面（复制字节，绝不保存 `content://`）。
     *
     * @return 是否真的设置成功（读取失败 / 不是图片 / 没有上下文都会返回 false，绝不抛）。
     */
    fun setCover(song: Song, uri: Uri): Boolean {
        val context = appContext ?: return false
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_INPUT_BYTES) }
        }.onFailure { Log.w(TAG, "openInputStream failed: ${it.message}") }.getOrNull()
        if (raw == null || raw.isEmpty()) return false
        return setCoverFromBytes(song, raw)
    }

    /**
     * 用已经在内存里的图片字节设置封面（导入 / 测试用）。
     *
     * @return 是否写盘成功。
     */
    fun setCoverFromBytes(song: Song, bytes: ByteArray): Boolean {
        val context = appContext ?: return false
        val key = song.identityKey
        val processed = runCatching { imageProcessor.prepare(bytes) }
            .onFailure { Log.w(TAG, "prepare failed: ${it.message}") }
            .getOrNull()
        if (processed == null || processed.isEmpty()) return false

        val relative = relativePathFor(key)
        val target = fileOf(context, relative)
        val previous = stored[key]
        val ok = runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(processed)
            true
        }.onFailure { Log.w(TAG, "write failed: ${it.message}") }.getOrDefault(false)
        if (!ok) return false

        // 同一个 key 的文件名是确定的（sha1），正常路径下是覆盖写；但如果旧条目指向别的文件
        // （换过命名规则 / 从备份导入过另一个路径），旧文件必须删掉，不能留成孤儿。
        if (previous != null && previous != relative) deleteQuietly(context, previous)

        persist(stored + (key to relative))
        return true
    }

    /** 清除封面：删掉私有目录里的文件并移除索引条目。@return 是否本来就有封面。 */
    fun clear(song: Song): Boolean {
        val context = appContext ?: return false
        val key = song.identityKey
        load()
        val relative = stored[key] ?: return false
        deleteQuietly(context, relative)
        persist(stored - key)
        return true
    }

    // ---------------------------------------------------------------------------------------
    // 备份：JSON 导出 / 导入
    // ---------------------------------------------------------------------------------------

    /**
     * 导出索引（identityKey -> 相对路径）为 JSON 对象字符串。
     *
     * **图片字节不在这里**：备份文件要不要带图片由 [com.lulu.music.data.backup.BackupManager.Options.includeImages]
     * 决定，字节走的是和壁纸完全相同的 `images[].base64` 通道（见 `BackupManager`）。
     */
    fun exportJson(): String = runCatching {
        Prefs.json.encodeToString(Prefs.stringMapSerializer, stored)
    }.onFailure { Log.w(TAG, "exportJson failed: ${it.message}") }.getOrDefault("")

    /**
     * 从 JSON 对象导入索引（**整体替换**）；格式不合法返回 false 且不动现有数据。
     *
     * 只接受形如 `covers/xxx.jpg` 的安全相对路径：绝对路径与任何带 `..` 的路径会被丢弃，
     * 免得一份被改过的备份让应用去读（或删）私有目录之外的文件。
     */
    fun importJson(json: String): Boolean = runCatching {
        val decoded = Prefs.json.decodeFromString(Prefs.stringMapSerializer, json)
        persist(decoded.filter { (_, relative) -> isSafeRelativePath(relative) })
        true
    }.onFailure { Log.w(TAG, "importJson failed: ${it.message}") }.getOrDefault(false)

    /** 测试钩子：丢掉内存状态并重新读盘。生产代码从不调用。 */
    @VisibleForTesting
    internal fun resetForTests(processor: CoverImageProcessor? = null) {
        imageProcessor = processor ?: AndroidCoverImageProcessor
        reload()
    }

    // ---------------------------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------------------------

    private fun publish() {
        val context = appContext
        _covers.value = if (context == null) {
            emptyMap()
        } else {
            stored.mapNotNull { (key, relative) ->
                val file = fileOf(context, relative)
                if (file.isFile) key to file.absolutePath else null
            }.toMap()
        }
    }

    private fun persist(map: Map<String, String>) {
        stored = map
        runCatching { Prefs.writeStringMap(KEY, map) }
            .onFailure { Log.w(TAG, "persist failed: ${it.message}") }
        // 内容版本号自增：换封面时 Map 可能与旧值完全相等（文件名固定），
        // 只有这个计数器能让消费方知道「内容变了」。
        _revision.value = _revision.value + 1
        publish()
    }

    private fun fileOf(context: Context, relative: String): File = File(context.filesDir, relative)

    private fun deleteQuietly(context: Context, relative: String) {
        if (!isSafeRelativePath(relative)) return
        runCatching { fileOf(context, relative).takeIf { it.isFile }?.delete() }
    }

    /** 只允许 `covers/...` 这样的相对路径；绝对路径与 `..` 一律拒绝。 */
    private fun isSafeRelativePath(relative: String): Boolean {
        if (relative.isBlank()) return false
        if (relative.startsWith("/") || relative.startsWith("\\")) return false
        if (relative.contains("..")) return false
        if (relative.contains(':')) return false
        val normalized = relative.replace('\\', '/')
        return normalized == DIR_NAME || normalized.startsWith("$DIR_NAME/")
    }

    /** 文件名取 identityKey 的 sha1：确定性（重复设置覆盖同一个文件）+ 不依赖 key 的字符集。 */
    private fun relativePathFor(identityKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(identityKey.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { String.format(Locale.US, "%02x", it) }
        return "$DIR_NAME/$hex.jpg"
    }

    /** 最多读 [limit] 字节；超过上限时截断（`decodeByteArray` 会因此失败 → 返回 false）。 */
    private fun readBounded(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            val allowed = minOf(read, limit - total)
            if (allowed <= 0) break
            out.write(buffer, 0, allowed)
            total += allowed
            if (total >= limit) break
        }
        return out.toByteArray()
    }
}

/**
 * 采样率：把 [width] x [height] 压到最长边不超过 [maxDimension] 所需的 2 的幂。
 *
 * 抽成纯函数是为了能在单测里钉死「12MP → 约 1MP」这条节能结论（JVM 里跑不了真实解码）。
 */
internal fun coverInSampleSize(width: Int, height: Int, maxDimension: Int = CoverStore.MAX_DIMENSION): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= maxDimension && height / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    // 长边仍然超限时继续加倍（例如 4000x200：短边不满足上面的条件，但长边必须降下来）。
    while (maxOf(width, height) / sample > maxDimension * 2) sample *= 2
    return sample
}

/** 采样之后如果要精确落到 [maxDimension] 以内，目标尺寸是多少（等比缩放，至少 1px）。 */
internal fun coverScaledSize(
    width: Int,
    height: Int,
    maxDimension: Int = CoverStore.MAX_DIMENSION,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1 to 1
    val longest = maxOf(width, height)
    if (longest <= maxDimension) return width to height
    val ratio = maxDimension.toDouble() / longest.toDouble()
    return maxOf(1, (width * ratio).toInt()) to maxOf(1, (height * ratio).toInt())
}

/**
 * 生产实现：`BitmapFactory` 采样解码 + 缩放 + JPEG 压缩。
 *
 * 任何一步失败都返回 null（调用方当作「这不是一张能用的图」处理），绝不抛。
 */
internal object AndroidCoverImageProcessor : CoverImageProcessor {

    override fun prepare(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = coverInSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
            .getOrNull() ?: return null

        var working = decoded
        var scaled: Bitmap? = null
        return try {
            val (targetWidth, targetHeight) = coverScaledSize(working.width, working.height)
            if (targetWidth != working.width || targetHeight != working.height) {
                scaled = Bitmap.createScaledBitmap(working, targetWidth, targetHeight, true)
                working = scaled
            }
            // 先按 88 压；仍然超上限就逐档降质量，最后再缩一半。
            var quality = 88
            var out = compress(working, quality)
            while (out != null && out.size > CoverStore.MAX_BYTES && quality > 45) {
                quality -= 15
                out = compress(working, quality)
            }
            if (out != null && out.size > CoverStore.MAX_BYTES) {
                val (halfWidth, halfHeight) = coverScaledSize(
                    working.width,
                    working.height,
                    CoverStore.MAX_DIMENSION / 2,
                )
                val half = Bitmap.createScaledBitmap(working, halfWidth, halfHeight, true)
                val halved = compress(half, 75)
                if (!half.isRecycled) half.recycle()
                out = halved ?: out
            }
            out
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { scaled?.takeIf { !it.isRecycled && it !== decoded }?.recycle() }
            runCatching { if (!decoded.isRecycled) decoded.recycle() }
        }
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray? = runCatching {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return@runCatching null
        out.toByteArray()
    }.getOrNull()
}

package com.lulu.music.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.store.CoverStore
import java.io.File

// ---------------------------------------------------------------------------------------------
// MARK: - 「哪张封面赢」的唯一规则
// ---------------------------------------------------------------------------------------------

/**
 * 这首歌现在应该显示哪张封面。
 *
 * 规则只有一条，全仓库共用：**本机自定义封面优先于远程 `coverURL`**。
 * 自定义封面是用户明确选过的，远程地址只是兜底；没有自定义封面时才回退到它。
 *
 * 做成纯函数（`covers` 显式传入）是为了能被单测直接钉住 —— 不需要 Context、不需要 Compose。
 *
 * @param covers [CoverStore.covers] 的快照：`identityKey` → 本机封面文件绝对路径
 */
fun songCoverModel(song: Song?, covers: Map<String, String>): String? {
    val key = song?.identityKey ?: return null
    val custom = covers[key]?.takeIf { it.isNotBlank() }
    return custom ?: song.coverURL
}

/** 只有自定义封面（本机文件绝对路径）；没有时返回 null。 */
fun songCustomCoverModel(song: Song?, covers: Map<String, String>): String? {
    val key = song?.identityKey ?: return null
    return covers[key]?.takeIf { it.isNotBlank() }
}

/**
 * 歌曲封面的 Coil 模型 + [CoverStore.revision]。
 *
 * 本机封面用 [File]（Coil 的 `FileKeyer` 会把 `lastModified` 算进缓存键），
 * 没有本机封面时是远程 `coverURL` 字符串。
 */
data class SongCoverModel(val model: Any?, val revision: Long, val isCustom: Boolean)

/** [songCoverModel] 给「直接拿 Coil 模型」的调用方用的版本。[coverPath] 为 null 时用远程地址。 */
fun songCoverCoilModel(song: Song?, covers: Map<String, String>): Any? {
    val key = song?.identityKey ?: return null
    val custom = covers[key]?.takeIf { it.isNotBlank() }
    return if (custom != null) File(custom) else song.coverURL
}

/**
 * [songCoverModel] 的 Compose 版本：订阅 [CoverStore.covers] 与 [CoverStore.revision]，
 * 所以「设置 / 清除 / **替换**自定义封面」都会立刻让消费方重组
 * （替换时 Map 内容不变，只有版本号会变）。
 */
@Composable
fun rememberSongCoverModel(song: Song?): SongCoverModel {
    val covers by CoverStore.covers.collectAsState()
    val revision by CoverStore.revision.collectAsState()
    return SongCoverModel(
        model = songCoverCoilModel(song, covers),
        revision = revision,
        isCustom = songCustomCoverModel(song, covers) != null,
    )
}

/**
 * 歌曲封面（歌曲行 / 播放页 / 迷你播放器统一入口）。
 *
 * 与直接写 `BeansCoverImage(url = song.coverURL)` 的区别：
 *  - 有自定义封面时用本机文件（优先，且以 [File] 作为 Coil 模型 —— 换封面之后缓存键会跟着变），
 *    并保留远程地址作为**回退**：本机文件损坏 / 被系统清掉时不会留一块空白；
 *  - 订阅了 `CoverStore.covers` + `revision`，换封面之后不需要调用方自己刷新。
 */
@Composable
fun BeansSongCoverImage(
    song: Song?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    emptyHint: String? = null,
) {
    val cover = rememberSongCoverModel(song)
    key(cover.revision) {
        val model = cover.model
        if (model is File) {
            BeansCoverImage(
                file = model,
                size = size,
                modifier = modifier,
                cornerRadius = cornerRadius,
                emptyHint = emptyHint,
                fallbackUrl = song?.coverURL,
            )
        } else {
            BeansCoverImage(
                url = song?.coverURL,
                size = size,
                modifier = modifier,
                cornerRadius = cornerRadius,
                emptyHint = emptyHint,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 相册选图（ActivityResultContracts.PickVisualMedia）
// ---------------------------------------------------------------------------------------------

/** 一次「选封面」的结果。 */
enum class CoverPickResult {
    /** 用户在系统相册里点了取消（回调 uri 为 null）：什么都没发生。 */
    CANCELLED,

    /** 字节已经复制进应用私有目录。 */
    APPLIED,

    /** 选了图，但读取 / 解码 / 落盘失败。 */
    FAILED,
}

/**
 * 相册回调 + 一次落盘尝试 → 结果。纯函数，落盘动作由 [apply] 注入，因此三种结果都能单测。
 *
 * 用户取消时**绝不**调用 [apply]：不然会拿一个空 uri 去写盘，还可能把好封面覆盖掉。
 */
fun coverPickResult(uri: Uri?, apply: (Uri) -> Boolean): CoverPickResult {
    if (uri == null) return CoverPickResult.CANCELLED
    return if (runCatching { apply(uri) }.getOrDefault(false)) {
        CoverPickResult.APPLIED
    } else {
        CoverPickResult.FAILED
    }
}

/**
 * 结果的提示文案；用户主动取消时返回 null —— 取消不该被弹一句提示念叨。
 */
fun coverPickMessage(result: CoverPickResult): String? = when (result) {
    CoverPickResult.CANCELLED -> null
    CoverPickResult.APPLIED -> beansLocalized("已更新自定义封面", "Custom cover updated")
    CoverPickResult.FAILED -> beansLocalized(
        "封面保存失败，请换一张图片重试",
        "Could not save the cover — try another image",
    )
}

/**
 * 返回一个「打开系统图片选择器」的启动器；选完的图片会被 [CoverStore] 复制进应用私有目录。
 *
 * 用 `PickVisualMedia`（Android 13+ 是系统相册选择器，更低版本由 AndroidX 回退到
 * `ACTION_OPEN_DOCUMENT`），因此**不需要**任何存储权限。
 *
 * 三种结果都会如实反馈：取消 → 不提示、成功 → 提示已更新、失败 → 明确报错。
 *
 * @param onResult 结果回调（调用方一般用来刷新自己的状态）
 */
@Composable
fun rememberSongCoverPicker(
    song: Song?,
    onResult: (CoverPickResult) -> Unit = {},
): () -> Unit {
    val currentSong by rememberUpdatedState(song)
    val currentOnResult by rememberUpdatedState(onResult)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = currentSong
        val result = if (target == null || uri == null) {
            // 没有目标歌曲：按失败处理（有 uri 时）或取消处理，绝不谎报成功。
            if (uri == null) CoverPickResult.CANCELLED else CoverPickResult.FAILED
        } else {
            coverPickResult(uri) { picked -> CoverStore.setCover(target, picked) }
        }
        coverPickMessage(result)?.let { BeansToastCenter.show(it) }
        currentOnResult(result)
    }

    return remember(launcher) {
        {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

package com.lulu.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lulu.music.BuildConfig
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.beansGlass
import com.lulu.music.ui.components.beansScrollIndicatorsHidden
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 更新日志（Android）。
 *
 * **渲染方式**：没有在 `ui/BeansApp.kt` 里注册新路由（那个文件不归本 agent 所有），
 * 而是从设置页用一个全屏 [Dialog]（`usePlatformDefaultWidth = false`）盖在整个界面上。
 * 这样它天然带着自己的返回栈语义（点返回键或右上角关闭都会 `onDismiss`），
 * 也不会影响 `BeansRootScaffold` 的底部标签栏。
 *
 * **内容来源**：`app/src/main/assets/changelog.md`（构建时从仓库根目录的 `CHANGELOG.md` 复制过来，
 * 并在最前面补了一条当前版本的记录）。这里用一个极简的行级 markdown 渲染器：
 * `##` 版本标题、`###` 小节标题、`-` 列表项、`>` 引用、`**粗体**`——没有引入任何 markdown 依赖。
 */
@Composable
fun ChangelogScreen(onDismiss: () -> Unit) {
    val colors = BeansTheme.colors
    val context = LocalContext.current
    val uiStyle = BeansUIStyle.LIQUID

    // 读盘只在进屏幕时做一次；失败降级为空文本，页面显示「暂无更新日志」。
    val markdown by produceState(initialValue = null as String?, Unit) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("changelog.md").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }

    val entries = remember(markdown) { parseChangelog(markdown.orEmpty()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                ChangelogTopBar(onClose = onDismiss, style = uiStyle)

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 860.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .beansScrollIndicatorsHidden()
                            .padding(horizontal = 16.dp)
                            .padding(top = 8.dp, bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = beansLocalized(
                                "当前版本 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                                "Current version v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            ),
                            color = colors.comment,
                            fontSize = 12.sp,
                        )

                        if (entries.isEmpty()) {
                            ChangelogCard {
                                Text(
                                    text = beansLocalized("暂无更新日志", "No changelog available"),
                                    color = colors.comment,
                                    fontSize = 13.sp,
                                )
                            }
                        } else {
                            entries.forEach { entry -> ChangelogEntryCard(entry = entry) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 顶部栏
// ---------------------------------------------------------------------------------------------

@Composable
private fun ChangelogTopBar(onClose: () -> Unit, style: BeansUIStyle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BeansGlassIconButton(
            systemName = "chevron.left",
            onClick = {
                BeansHaptics.tap()
                onClose()
            },
            size = 40.dp,
            forceLiquid = true,
            style = style,
        )
        Text(
            text = beansLocalized("更新日志", "Changelog"),
            color = BeansTheme.colors.label,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 卡片
// ---------------------------------------------------------------------------------------------

@Composable
private fun ChangelogCard(content: @Composable () -> Unit) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .beansGlass(shape = shape, style = BeansUIStyle.LIQUID)
            .clip(shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun ChangelogEntryCard(entry: ChangelogEntry) {
    val colors = BeansTheme.colors
    ChangelogCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "v${entry.version}",
                color = colors.accent,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            if (entry.date.isNotBlank()) {
                Text(
                    text = entry.date,
                    color = colors.comment,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (entry.version == BuildConfig.VERSION_NAME) {
            Text(
                text = beansLocalized("当前版本", "Current"),
                color = colors.comment,
                fontSize = 11.sp,
            )
        }

        entry.blocks.forEach { block -> ChangelogBlockView(block) }
    }
}

@Composable
private fun ChangelogBlockView(block: ChangelogBlock) {
    val colors = BeansTheme.colors
    when (block) {
        is ChangelogBlock.Section -> Text(
            text = inlineMarkdown(block.text, colors.accent.copy(alpha = 0.9f)),
            color = colors.label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )

        is ChangelogBlock.Bullet -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "•", color = colors.accent, fontSize = 13.sp)
            Text(
                text = inlineMarkdown(block.text, colors.accent),
                color = colors.label,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.weight(1f),
            )
        }

        is ChangelogBlock.Quote -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .background(colors.accent.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
            )
            Text(
                text = inlineMarkdown(block.text, colors.accent),
                color = colors.comment,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
            )
        }

        is ChangelogBlock.Paragraph -> Text(
            text = inlineMarkdown(block.text, colors.accent),
            color = colors.comment,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 极简 markdown
// ---------------------------------------------------------------------------------------------

/** 一条版本记录：[version] 取自 `## x.y.z`，[date] 是标题里 `·` 之后的部分（没有则为空）。 */
internal data class ChangelogEntry(
    val version: String,
    val date: String,
    val blocks: List<ChangelogBlock>,
)

internal sealed interface ChangelogBlock {
    data class Section(val text: String) : ChangelogBlock
    data class Bullet(val text: String) : ChangelogBlock
    data class Quote(val text: String) : ChangelogBlock
    data class Paragraph(val text: String) : ChangelogBlock
}

/** HTML 标签（仓库的 CHANGELOG 里有 `<span style=...>`）在纯文本渲染里直接丢掉。 */
private val htmlTag = Regex("""<[^>]*>""")

/** 行内粗体标记（两个星号包住一段文字）：只处理成对出现的片段。 */
private val boldPattern = Regex("""\*\*(.+?)\*\*""")

/**
 * 按行解析 markdown：
 *  - `# ` / `## ` / `### ` 开新记录或小节；只有 `## ` 是版本记录（`#` 是文档大标题，`###` 是小节）。
 *  - `- ` / `* ` 列表项，`> ` 引用，其余归为段落。
 *  - 连续的空行会被折叠，条目之间不会出现大段空白。
 */
internal fun parseChangelog(markdown: String): List<ChangelogEntry> {
    val entries = mutableListOf<ChangelogEntry>()
    var version: String? = null
    var date = ""
    var blocks = mutableListOf<ChangelogBlock>()

    fun flush() {
        val current = version ?: return
        entries += ChangelogEntry(version = current, date = date, blocks = blocks.toList())
        blocks = mutableListOf()
    }

    markdown.lineSequence().forEach { rawLine ->
        val line = rawLine.trim().removeSuffix("\r")
        when {
            line.startsWith("## ") -> {
                flush()
                val heading = clean(line.removePrefix("## ").trim())
                // 标题形如「1.8.1」或「1.8.1 · 2026-01-18」
                val parts = heading.split("·", limit = 2).map { it.trim() }
                version = parts.getOrNull(0).orEmpty().ifBlank { null }
                date = parts.getOrNull(1).orEmpty()
            }

            line.startsWith("### ") -> blocks += ChangelogBlock.Section(clean(line.removePrefix("### ").trim()))

            line.startsWith("# ") -> Unit // 文档大标题，不进记录

            line.startsWith("- ") || line.startsWith("* ") ->
                blocks += ChangelogBlock.Bullet(clean(line.drop(2).trim()))

            line.startsWith("> ") ->
                blocks += ChangelogBlock.Quote(clean(line.removePrefix("> ").trim()))

            line.isBlank() -> Unit

            else -> blocks += ChangelogBlock.Paragraph(clean(line))
        }
    }
    flush()

    // 最新在前：文件本身是「新 → 旧」，但真遇到乱序时按版本号兜底排一次。
    return entries.sortedWith(
        compareByDescending<ChangelogEntry> { versionRank(it.version) }
            .thenByDescending { it.version },
    )
}

private fun clean(text: String): String = htmlTag.replace(text, "").trim()

/** 把 `1.8.1` 变成可比较的数字；解析不了时排到最后。 */
private fun versionRank(version: String): Int {
    val parts = version.split('.').mapNotNull { it.trim().toIntOrNull() }
    if (parts.isEmpty()) return -1
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 1_000_000 + minor * 1_000 + patch
}

/** 行内粗体标记 → [AnnotatedString]；[boldColor] 用于加粗片段。 */
@Composable
private fun inlineMarkdown(text: String, boldColor: Color): AnnotatedString {
    return remember(text, boldColor) {
        buildAnnotatedString {
            var cursor = 0
            boldPattern.findAll(text).forEach { match ->
                if (match.range.first > cursor) {
                    append(text.substring(cursor, match.range.first))
                }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = boldColor)) {
                    append(match.groupValues[1])
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
}

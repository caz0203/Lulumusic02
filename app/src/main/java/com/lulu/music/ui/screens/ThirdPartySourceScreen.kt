package com.lulu.music.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lulu.music.data.model.ThirdPartyAudioQuality
import com.lulu.music.data.model.beansLocalized
import com.lulu.music.data.net.Http
import com.lulu.music.data.prefs.BeansUIStyle
import com.lulu.music.data.prefs.SettingsStore
import com.lulu.music.data.source.ThirdPartySource
import com.lulu.music.data.source.UnblockSourceStore
import com.lulu.music.data.source.ThirdPartySourceImportParser
import com.lulu.music.data.store.CrashLog
import com.lulu.music.ui.components.BeansBottomSheet
import com.lulu.music.ui.components.BeansCapsule
import com.lulu.music.ui.components.BeansDetent
import com.lulu.music.ui.components.BeansEmptyState
import com.lulu.music.ui.components.BeansGlass
import com.lulu.music.ui.components.BeansGlassButton
import com.lulu.music.ui.components.BeansGlassIconButton
import com.lulu.music.ui.components.BeansHaptics
import com.lulu.music.ui.components.BeansIcons
import com.lulu.music.ui.components.BeansToastCenter
import com.lulu.music.ui.components.beansGlass
import com.lulu.music.ui.components.beansPressClickable
import com.lulu.music.ui.components.beansScrollIndicatorsHidden
import com.lulu.music.ui.theme.BeansTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 第三方音源（自定义音源）管理页 — port of the iOS `ThirdPartySourceManagerSheet`.
 *
 * 结构完全对齐 iOS 的三张玻璃卡片：
 *  1. 「导入与管理」— 说明文案 + 「+」新建入口（也显示最近一次导入结果）；
 *  2. 「导入音源」— 文本 / 链接 / 文件 三段式分段控件 + 输入区 + 「识别并导入」 + 格式说明；
 *  3. 「音源列表」— 计数 + 每条音源（名称 / `类型 · 音质 · url` 副标题 / 编辑 / 启用 / 上移下移 / 删除）。
 *
 * Android 侧差异（均为平台能力差异，非逻辑简化）：
 *  - iOS 的 `UIDocumentPickerViewController`（`SourceDocumentPicker`）换成
 *    `ActivityResultContracts.OpenDocument`，用 `ContentResolver` 以 UTF-8 读出文件文本；
 *  - iOS 的 `.alert` 换成 Material3 的 [AlertDialog]；
 *  - iOS 的 `SourceEditorSheet`（`.sheet`）换成 [BeansBottomSheet]（全屏 detent），
 *    字段与 iOS 的 `ThirdPartySourceDraft` 一致：名称 / 类型 / 适用平台 / 音质 / URL 模板 /
 *    字段路径 / 脚本内容 / 启用；
 *  - 「识别并导入」按钮在三种模式下都保留（iOS 只在文本与文件模式渲染，且文件模式那颗会去解析
 *    空文本框）；这里文件模式会优先重新解析已选中的文件，没有选中文件时直接唤起选择器，不会走进死路。
 *  - 卡片右上角的圆形「+」用主题强调色实底 + 白色加号（iOS 是纯黑圆底），避免深色模式下黑底不可见。
 */
@Composable
fun ThirdPartySourceScreen(onBack: () -> Unit) {
    val colors = BeansTheme.colors
    val uiStyle by SettingsStore.uiStyle.collectAsState()
    val sources by UnblockSourceStore.sources.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modeIndex by rememberSaveable { mutableIntStateOf(0) }
    val mode = ImportMode.entries[modeIndex.coerceIn(0, ImportMode.entries.size - 1)]

    // 输入框 / 状态文案只放在 remember 里（iOS 那边同样是 @State）。
    // 原因：粘贴进来的 LX 脚本动辄几百 KB，之前用 rememberSaveable 会把它写进 Activity 的
    // saved-instance-state Bundle，而 Bundle 走 Binder 有约 1MB 的事务上限——超限时系统抛
    // TransactionTooLargeException（在 onSaveInstanceState 里，不在我们的 try/catch 范围内）
    // 直接把进程打掉。这里只保留可以随时重建的 UI 状态，大文本不进 saved state。
    var importText by remember { mutableStateOf("") }
    var importUrl by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var pickedFileName by remember { mutableStateOf<String?>(null) }

    // 只记住文件 Uri（不缓存文件正文）：真正读取放在 IO 线程按需进行，避免把大脚本留在组合状态里。
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    var editorTarget by remember { mutableStateOf<ThirdPartySource?>(null) }
    var pendingDelete by remember { mutableStateOf<ThirdPartySource?>(null) }

    // 已安装的音源决定可选音质集合（对应 iOS `store.supportedQualities`）。
    val availableQualities = remember(sources) {
        runCatching { UnblockSourceStore.availableThirdPartyQualities() }
            .getOrDefault(ThirdPartyAudioQuality.entries.toList())
    }

    LaunchedEffect(Unit) { runCatching { UnblockSourceStore.load() } }

    fun openEditor(source: ThirdPartySource) {
        BeansHaptics.tap()
        editorTarget = source
    }

    fun newDraft() = ThirdPartySource(
        name = "",
        kind = ThirdPartySource.KIND_KEYWORD,
        template = "",
        urlPath = "url",
        headers = emptyMap(),
        quality = "320k",
        script = null,
        enabled = true,
    )

    fun fail(message: String) {
        importing = false
        statusText = message
        // 提示 / 震动属于「锦上添花」，任何 Throwable（含 Error）都不该把导入本身带崩。
        runCatching { BeansToastCenter.show(message) }
            .onFailure { CrashLog.write(it) }
        runCatching { BeansHaptics.tap() }
    }

    fun applyOutcome(outcome: ImportOutcome) {
        importing = false
        statusText = outcome.message
        runCatching { BeansToastCenter.show(outcome.message) }
            .onFailure { CrashLog.write(it) }
        if (outcome.imported) {
            runCatching { BeansHaptics.success() }
            importText = ""
            importUrl = ""
            pickedUri = null
            pickedFileName = null
        } else {
            runCatching { BeansHaptics.tap() }
        }
    }

    /**
     * 所有导入入口共用的一段：解析 + 落盘丢到 IO 线程，状态更新回到主线程。
     *
     * 关键点：
     *  - `catch (Throwable)`（不是 `Exception`）：`ExceptionInInitializerError` /
     *    `NoClassDefFoundError` / `OutOfMemoryError` / `StackOverflowError` 都是 `Error`，
     *    `catch (Exception)` 会漏掉，漏掉的 `Error` 会直接冒泡出线程把进程干掉——
     *    这正是「文本 / 链接 / 文件三种方式都闪退且拿不到堆栈」的形态；
     *  - 被收敛掉的 Throwable 仍然写进 [CrashLog]，下次可以直接看 crash.log；
     *  - 大脚本（LX 脚本常见几百 KB）的解析 + kotlinx.serialization 编码 + SharedPreferences
     *    写入不再占用主线程，主线程只做状态赋值（不在 IO 线程碰 Compose 状态）。
     */
    fun startImport(
        failurePrefix: String,
        block: suspend () -> ImportOutcome,
    ) {
        if (importing) return
        importing = true
        scope.launch {
            val outcome = try {
                block()
            } catch (error: Throwable) {
                CrashLog.write(error)
                ImportOutcome(false, "$failurePrefix：${errorDetail(error)}")
            }
            try {
                applyOutcome(outcome)
            } catch (error: Throwable) {
                // 连「收尾」都炸了也不能带走进程。
                CrashLog.write(error)
                importing = false
                statusText = "$failurePrefix：${errorDetail(error)}"
            }
        }
    }

    fun importFromText() {
        val trimmed = importText.trim()
        if (trimmed.isEmpty()) {
            fail(beansLocalized("请先粘贴 JSON / 配置 / 脚本内容", "Paste JSON / config / script text first"))
            return
        }
        startImport(beansLocalized("识别失败", "Could not recognise the input")) {
            withContext(Dispatchers.IO) { parseAndStore(trimmed, null) }
        }
    }

    fun importFromUrl() {
        val url = importUrl.trim()
        if (url.isEmpty()) {
            fail(beansLocalized("请输入远程音源地址", "Enter a remote source URL first"))
            return
        }
        startImport(beansLocalized("下载失败", "Download failed")) {
            withContext(Dispatchers.IO) { parseAndStore(Http.getText(url), url) }
        }
    }

    /** 选中文件后的读取 + 解析 + 落盘，全部在 IO 线程完成。 */
    fun importPickedFile(uri: Uri, name: String) {
        startImport(beansLocalized("读取文件失败", "Could not read the file")) {
            val text = withContext(Dispatchers.IO) { readPickedText(context, uri) }
            if (text.isBlank()) {
                ImportOutcome(
                    imported = false,
                    message = beansLocalized("文件内容为空：$name", "The selected file is empty: $name"),
                )
            } else {
                withContext(Dispatchers.IO) { parseAndStore(text, name) }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayNameOf(uri)
        pickedUri = uri
        pickedFileName = name
        importPickedFile(uri, name)
    }

    fun pickFile() {
        runCatching { filePicker.launch(arrayOf("*/*")) }
            .onFailure { fail(beansLocalized("无法打开文件选择器：${errorDetail(it)}", "Could not open the file picker: ${errorDetail(it)}")) }
    }

    fun runPrimaryImport() {
        when (mode) {
            ImportMode.TEXT -> importFromText()
            ImportMode.LINK -> importFromUrl()
            ImportMode.FILE -> {
                val uri = pickedUri
                if (uri == null) {
                    pickFile()
                } else {
                    importPickedFile(uri, pickedFileName.orEmpty().ifBlank { "import" })
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // ---------------------------------------------------------------- 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BeansGlassIconButton(
                systemName = "plus",
                onClick = { openEditor(newDraft()) },
                size = 40.dp,
                forceLiquid = true,
                style = uiStyle,
            )
            Text(
                text = beansLocalized("自定义音源", "Custom Sources"),
                color = colors.label,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            BeansGlassButton(
                title = beansLocalized("完成", "Done"),
                onClick = {
                    BeansHaptics.tap()
                    onBack()
                },
                prominent = true,
                style = uiStyle,
            )
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .beansScrollIndicatorsHidden()
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ImportManageCard(
                    statusText = statusText,
                    onAdd = { openEditor(newDraft()) },
                    style = uiStyle,
                )

                ImportCard(
                    mode = mode,
                    onModeChange = { modeIndex = it.ordinal },
                    importText = importText,
                    onImportTextChange = { importText = it },
                    importUrl = importUrl,
                    onImportUrlChange = { importUrl = it },
                    pickedFileName = pickedFileName,
                    importing = importing,
                    onPickFile = { pickFile() },
                    onImport = { runPrimaryImport() },
                    style = uiStyle,
                )

                SourceListCard(
                    sources = sources,
                    onEdit = { openEditor(it) },
                    onMove = { source, by -> runCatching { UnblockSourceStore.moveSource(source.id, by) } },
                    onToggle = { source, enabled ->
                        runCatching { UnblockSourceStore.updateEnabled(source.id, enabled) }
                    },
                    onDelete = { pendingDelete = it },
                    style = uiStyle,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 编辑弹层
    val target = editorTarget
    if (target != null) {
        SourceEditorSheet(
            source = target,
            qualities = availableQualities,
            style = uiStyle,
            onDismiss = { editorTarget = null },
            onSave = { updated ->
                runCatching { UnblockSourceStore.upsert(updated) }
                BeansHaptics.success()
                statusText = beansLocalized("已保存音源：${updated.name}", "Saved source: ${updated.name}")
                BeansToastCenter.show(statusText.orEmpty())
                editorTarget = null
            },
        )
    }

    // ---------------------------------------------------------------- 删除确认
    val doomed = pendingDelete
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = colors.card,
            title = { Text(text = beansLocalized("删除音源", "Delete source"), color = colors.label) },
            text = {
                Text(
                    text = beansLocalized(
                        "确定要删除「${doomed.name}」吗？",
                        "Delete \"${doomed.name}\"?",
                    ),
                    color = colors.comment,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val removed = runCatching { UnblockSourceStore.removeSource(doomed.id) }
                            .getOrDefault(false)
                        pendingDelete = null
                        val message = if (removed) {
                            beansLocalized("已删除音源：${doomed.name}", "Deleted source: ${doomed.name}")
                        } else {
                            beansLocalized("未找到该音源", "Source not found")
                        }
                        statusText = message
                        BeansToastCenter.show(message)
                        BeansHaptics.medium()
                    },
                ) {
                    Text(text = beansLocalized("删除", "Delete"), color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = beansLocalized("取消", "Cancel"), color = colors.comment)
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 卡片 1：导入与管理
// ---------------------------------------------------------------------------------------------

@Composable
private fun ImportManageCard(
    statusText: String?,
    onAdd: () -> Unit,
    style: BeansUIStyle,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }

    BeansGlass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        style = style,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = beansLocalized("导入与管理", "Import & Manage"),
                        color = colors.label,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = beansLocalized(
                            "支持本地文件、远程 URL 和粘贴文本。",
                            "Supports local files, remote URLs, and pasted text.",
                        ),
                        color = colors.comment,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = beansLocalized(
                            "也支持 LX Music / BakaMusic 风格的 JS 音源脚本。",
                            "Also supports LX Music / BakaMusic style JS source scripts.",
                        ),
                        color = colors.comment,
                        fontSize = 12.sp,
                    )
                    Text(
                        text = beansLocalized(
                            "支持 LX User API 的 JSON、JS、本地文件与在线 URL；在线导入后会保存本地副本。",
                            "Supports LX User API JSON, JS, local files and online URLs; remote imports are cached locally.",
                        ),
                        color = colors.comment,
                        fontSize = 12.sp,
                    )
                }

                // iOS 是 34pt 纯黑圆底 + 白色加号；深色模式下黑色圆底不可见，改用主题强调色实底。
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.92f))
                        .beansPressClickable(
                            interactionSource = interaction,
                            scale = 0.95f,
                            onClick = onAdd,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = beansLocalized("新建音源", "New source"),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (!statusText.isNullOrBlank()) {
                Text(
                    text = statusText,
                    color = colors.accent,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 卡片 2：导入音源
// ---------------------------------------------------------------------------------------------

@Composable
private fun ImportCard(
    mode: ImportMode,
    onModeChange: (ImportMode) -> Unit,
    importText: String,
    onImportTextChange: (String) -> Unit,
    importUrl: String,
    onImportUrlChange: (String) -> Unit,
    pickedFileName: String?,
    importing: Boolean,
    onPickFile: () -> Unit,
    onImport: () -> Unit,
    style: BeansUIStyle,
) {
    val colors = BeansTheme.colors

    BeansGlass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        style = style,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = beansLocalized("导入音源", "Import Sources"),
                    color = colors.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                SourceSegmented(
                    options = ImportMode.entries,
                    selected = mode,
                    label = { it.title },
                    onSelect = {
                        BeansHaptics.select()
                        onModeChange(it)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            when (mode) {
                ImportMode.TEXT -> {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = onImportTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        placeholder = {
                            Text(
                                text = beansLocalized(
                                    "粘贴 JSON / 配置 / 脚本内容",
                                    "Paste JSON / config / script text",
                                ),
                                fontSize = 13.sp,
                            )
                        },
                        textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    )
                }

                ImportMode.LINK -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = onImportUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            placeholder = {
                                Text(
                                    text = beansLocalized(
                                        "输入远程音源地址",
                                        "Enter remote source URL",
                                    ),
                                    fontSize = 13.sp,
                                )
                            },
                            textStyle = TextStyle(fontSize = 14.sp),
                        )
                        Text(
                            text = beansLocalized(
                                "在线导入会抓取远端内容并保存本地副本。",
                                "Online imports fetch the remote body and keep a local copy.",
                            ),
                            color = colors.comment,
                            fontSize = 11.sp,
                        )
                    }
                }

                ImportMode.FILE -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BeansGlassButton(
                            title = beansLocalized("选择本地文件", "Choose Local File"),
                            systemName = "folder",
                            onClick = onPickFile,
                            modifier = Modifier.fillMaxWidth(),
                            style = style,
                        )
                        if (!pickedFileName.isNullOrBlank()) {
                            Text(
                                text = beansLocalized(
                                    "已选择：$pickedFileName",
                                    "Selected: $pickedFileName",
                                ),
                                color = colors.accent,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = beansLocalized(
                                "支持 JSON、JS、TXT 等文本文件。",
                                "Supports JSON, JS, TXT and other text files.",
                            ),
                            color = colors.comment,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            BeansGlassButton(
                title = if (importing) {
                    beansLocalized("正在导入…", "Importing…")
                } else {
                    beansLocalized("识别并导入", "Recognize & Import")
                },
                systemName = "sparkles",
                onClick = { if (!importing) onImport() },
                modifier = Modifier.fillMaxWidth(),
                prominent = true,
                style = style,
            )

            Text(
                text = beansLocalized(
                    "支持 JSON 数组、LX User API 导出 JSON、JS 脚本、`SERVER_SCRIPT_CONFIG` 片段，" +
                        "以及 `@name` / `@template` 头部格式。",
                    "Supports JSON arrays, LX User API export JSON, JS scripts, " +
                        "`SERVER_SCRIPT_CONFIG` fragments, and `@name` / `@template` header-style blocks.",
                ),
                color = colors.comment,
                fontSize = 11.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// MARK: - 卡片 3：音源列表
// ---------------------------------------------------------------------------------------------

@Composable
private fun SourceListCard(
    sources: List<ThirdPartySource>,
    onEdit: (ThirdPartySource) -> Unit,
    onMove: (ThirdPartySource, Int) -> Unit,
    onToggle: (ThirdPartySource, Boolean) -> Unit,
    onDelete: (ThirdPartySource) -> Unit,
    style: BeansUIStyle,
) {
    val colors = BeansTheme.colors

    BeansGlass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        style = style,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = beansLocalized("音源列表", "Source List"),
                    color = colors.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${sources.size}",
                    color = colors.comment,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            if (sources.isEmpty()) {
                // iOS 用 SF Symbol `shippingbox.fill`；BeansIcons 未收录，回退为「堆叠」图标。
                BeansEmptyState(
                    icon = BeansIcons.of("shippingbox.fill", Icons.Rounded.Layers),
                    text = beansLocalized(
                        "还没有音源，先导入一个吧。",
                        "No sources yet. Import one first.",
                    ),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    sources.forEachIndexed { index, source ->
                        SourceRow(
                            source = source,
                            canMoveUp = index > 0,
                            canMoveDown = index < sources.size - 1,
                            style = style,
                            onEdit = { onEdit(source) },
                            onMoveUp = { onMove(source, -1) },
                            onMoveDown = { onMove(source, 1) },
                            onToggle = { onToggle(source, it) },
                            onDelete = { onDelete(source) },
                        )
                    }
                }
            }
        }
    }
}

/** Port of iOS `SourceRow`. */
@Composable
private fun SourceRow(
    source: ThirdPartySource,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    style: BeansUIStyle,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BeansTheme.colors
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .beansGlass(shape = shape, style = style)
            .clip(shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = source.name,
                    color = colors.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = sourceSubtitle(source),
                    color = colors.comment,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = { value ->
                    BeansHaptics.select()
                    onToggle(value)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SourceEditButton(onClick = onEdit, style = style)
            SourceMiniButton(
                systemName = "chevron.up",
                onClick = onMoveUp,
                enabled = canMoveUp,
                tint = colors.accent,
                style = style,
            )
            SourceMiniButton(
                systemName = "chevron.down",
                onClick = onMoveDown,
                enabled = canMoveDown,
                tint = colors.accent,
                style = style,
            )
            Spacer(Modifier.weight(1f))
            SourceMiniButton(
                systemName = "trash",
                onClick = onDelete,
                tint = DeleteRed,
                style = style,
            )
        }
    }
}

/** 「编辑」胶囊（对应 iOS `Label(..., systemImage: "slider.horizontal.3")`）。 */
@Composable
private fun SourceEditButton(onClick: () -> Unit, style: BeansUIStyle) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }

    BeansCapsule(
        modifier = Modifier.beansPressClickable(
            interactionSource = interaction,
            scale = 0.96f,
            onClick = onClick,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        style = style,
    ) {
        Icon(
            imageVector = BeansIcons.of("slider.horizontal.3", Icons.Rounded.Edit),
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = beansLocalized("编辑", "Edit"),
            color = colors.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 小号圆形图标按钮（上移 / 下移 / 删除）。`enabled = false` 时变淡且不响应点击。 */
@Composable
private fun SourceMiniButton(
    systemName: String,
    onClick: () -> Unit,
    tint: Color,
    style: BeansUIStyle,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(32.dp)
            .beansGlass(shape = CircleShape, style = style)
            .clip(CircleShape)
            .beansPressClickable(
                interactionSource = interaction,
                enabled = enabled,
                scale = 0.9f,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = BeansIcons.of(systemName, Icons.Rounded.Description),
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Port of iOS `SourceRow.subtitle`：
 * `类型 · 音质 · 平台 · 字段路径`，脚本音源用「脚本」并且不显示 `urlPath`。
 */
private fun sourceSubtitle(source: ThirdPartySource): String {
    val scriptLike = source.isScript
    val platform = source.headers["source"]
        ?.let { SourcePlatform.fromCode(it) }
        ?.takeIf { it != SourcePlatform.ALL }
        ?.title
        .orEmpty()
    val kind = if (scriptLike) source.kindLabel else source.kind.uppercase()
    val path = if (scriptLike) "" else source.urlPath
    return listOf(kind, source.quality, platform, path)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

// ---------------------------------------------------------------------------------------------
// MARK: - 编辑弹层
// ---------------------------------------------------------------------------------------------

/** Port of iOS `SourceEditorSheet`（字段与 `ThirdPartySourceDraft` 对齐）。 */
@Composable
private fun SourceEditorSheet(
    source: ThirdPartySource,
    qualities: List<ThirdPartyAudioQuality>,
    style: BeansUIStyle,
    onDismiss: () -> Unit,
    onSave: (ThirdPartySource) -> Unit,
) {
    val colors = BeansTheme.colors

    var name by remember(source.id) { mutableStateOf(source.name) }
    var kind by remember(source.id) { mutableStateOf(source.kind) }
    var template by remember(source.id) { mutableStateOf(source.template) }
    var urlPath by remember(source.id) { mutableStateOf(source.urlPath) }
    var quality by remember(source.id) { mutableStateOf(source.quality.ifBlank { "320k" }) }
    var scriptText by remember(source.id) { mutableStateOf(source.script ?: "") }
    var enabled by remember(source.id) { mutableStateOf(source.enabled) }

    val kindOptions = listOf(
        ThirdPartySource.KIND_KEYWORD,
        ThirdPartySource.KIND_URL,
        ThirdPartySource.KIND_SCRIPT,
    )
    val qualityOptions = remember(qualities, quality) {
        val raws = qualities.map { it.raw }
        if (raws.contains(quality)) raws else raws + quality
    }
    val scriptLike = kind.equals(ThirdPartySource.KIND_SCRIPT, ignoreCase = true) ||
        scriptText.isNotBlank()

    fun commit() {
        val normalizedQuality = quality.trim().ifBlank { "320k" }
        val updated = source.copy(
            name = name.trim().ifBlank { beansLocalized("未命名音源", "Untitled source") },
            kind = kind.trim().ifBlank { ThirdPartySource.KIND_KEYWORD },
            template = template.trim(),
            urlPath = urlPath.trim().ifBlank { "url" },
            headers = source.headers + mapOf("quality" to normalizedQuality),
            quality = normalizedQuality,
            script = scriptText.trim().ifBlank { null },
            enabled = enabled,
        )
        onSave(updated)
    }

    BeansBottomSheet(
        onDismissRequest = onDismiss,
        detents = listOf(BeansDetent.Large),
        style = style,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = beansLocalized("编辑音源", "Edit Source"),
                color = colors.label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(beansLocalized("音源名称", "Source Name"), fontSize = 13.sp) },
                textStyle = TextStyle(fontSize = 14.sp),
            )

            Text(
                text = beansLocalized("类型", "Type"),
                color = colors.comment,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            SourceSegmented(
                options = kindOptions,
                selected = kindOptions.firstOrNull { it.equals(kind, ignoreCase = true) }
                    ?: ThirdPartySource.KIND_KEYWORD,
                label = {
                    if (it == ThirdPartySource.KIND_SCRIPT) {
                        beansLocalized("脚本", "Script")
                    } else {
                        it.uppercase()
                    }
                },
                onSelect = {
                    BeansHaptics.select()
                    kind = it
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = beansLocalized("音质", "Quality"),
                color = colors.comment,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                qualityOptions.forEach { raw ->
                    val selected = raw == quality
                    QualityChip(
                        title = qualityLabel(raw),
                        selected = selected,
                        onClick = {
                            BeansHaptics.select()
                            quality = raw
                        },
                    )
                }
            }

            if (!scriptLike) {
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(beansLocalized("URL 模板", "URL Template"), fontSize = 13.sp) },
                    textStyle = TextStyle(fontSize = 14.sp),
                )
            }

            if (scriptLike) {
                OutlinedTextField(
                    value = scriptText,
                    onValueChange = { scriptText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    placeholder = {
                        Text(
                            text = beansLocalized(
                                "粘贴 LX / Baka 风格 JS 音源脚本",
                                "Paste LX / Baka style JS source script",
                            ),
                            fontSize = 12.sp,
                        )
                    },
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                )
            }

            if (!scriptLike) {
                OutlinedTextField(
                    value = urlPath,
                    onValueChange = { urlPath = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(beansLocalized("字段路径", "Value Path"), fontSize = 13.sp) },
                    textStyle = TextStyle(fontSize = 14.sp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = beansLocalized("启用", "Enabled"),
                    color = colors.label,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        BeansHaptics.select()
                        enabled = value
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BeansGlassButton(
                    title = beansLocalized("取消", "Cancel"),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    style = style,
                )
                BeansGlassButton(
                    title = beansLocalized("保存", "Save"),
                    onClick = { commit() },
                    modifier = Modifier.weight(1f),
                    prominent = true,
                    style = style,
                )
            }
        }
    }
}

/** 音质胶囊（无勾选图标，避免横向滚动时被挤压）。 */
@Composable
private fun QualityChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BeansTheme.colors
    val interaction = remember { MutableInteractionSource() }

    BeansCapsule(
        fill = if (selected) colors.accent.copy(alpha = 0.14f) else colors.label.copy(alpha = 0.055f),
        borderColor = if (selected) colors.accent.copy(alpha = 0.42f) else colors.label.copy(alpha = 0.08f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
        modifier = Modifier.beansPressClickable(
            interactionSource = interaction,
            scale = 0.95f,
            onClick = onClick,
        ),
    ) {
        Text(
            text = title,
            color = if (selected) colors.accent else colors.label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private fun qualityLabel(raw: String): String =
    ThirdPartyAudioQuality.fromSourceValue(raw)?.displayName ?: raw

// ---------------------------------------------------------------------------------------------
// MARK: - 通用零件
// ---------------------------------------------------------------------------------------------

/** 三段式分段控件（对应 iOS 的 `.pickerStyle(.segmented)`）。 */
@Composable
private fun <T> SourceSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BeansTheme.colors

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.label.copy(alpha = if (colors.isDark) 0.08f else 0.05f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) colors.card else Color.Transparent)
                    .beansPressClickable(
                        interactionSource = interaction,
                        scale = 0.97f,
                        onClick = { onSelect(option) },
                    )
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    color = if (isSelected) colors.accent else colors.label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 导入模式（对应 iOS `ThirdPartySourceImportMode`）。 */
private enum class ImportMode(val zh: String, val en: String) {
    TEXT("文本", "Text"),
    LINK("链接", "URL"),
    FILE("文件", "File");

    val title: String get() = beansLocalized(zh, en)
}

/** 音源适用平台（对应 iOS `ThirdPartySourcePlatform`）。 */
private enum class SourcePlatform(val titleZh: String, val titleEn: String) {
    ALL("全部平台", "All Platforms"),
    NETEASE("网易云音乐", "NetEase Cloud Music"),
    QQ("QQ音乐", "QQ Music"),
    KUGOU("酷狗音乐", "Kugou Music");

    val title: String get() = beansLocalized(titleZh, titleEn)

    companion object {
        fun fromCode(raw: String): SourcePlatform = when (raw.trim().lowercase()) {
            "wy", "netease", "cloud" -> NETEASE
            "tx", "qq", "qqmusic" -> QQ
            "kg", "kugou" -> KUGOU
            else -> ALL
        }
    }
}

/** 解析结果（避免直接构造并行开发中的 `ThirdPartySourceImportParser.Result`）。 */
private data class ImportOutcome(val imported: Boolean, val message: String)

/**
 * 解析 [text] 并把识别到的音源写入 [UnblockSourceStore]。
 *
 * 任何解析异常都被收敛成一条错误文案，绝不向上抛（对应需求「Never crash on malformed input」）。
 *
 * **这里必须 catch `Throwable` 而不是 `Exception`**：`ExceptionInInitializerError`、
 * `NoClassDefFoundError`、`OutOfMemoryError`、`StackOverflowError` 都是 `Error`，不属于
 * `Exception`；一旦在这里漏出去，`parseAndStore` 的三个调用点（文本 / 链接 / 文件）会分别
 * 从点击回调、`scope.launch` 协程、以及 ActivityResult 回调里把它抛到线程外，进程直接结束
 * 且没有可读堆栈——正是「三种导入方式都闪退」的形态。
 */
private fun parseAndStore(text: String, filename: String?): ImportOutcome = try {
    val result = ThirdPartySourceImportParser.parse(text, filename)
    val parsed = result.sources
    if (parsed.isEmpty()) {
        ImportOutcome(
            imported = false,
            message = result.message.ifBlank {
                beansLocalized("未识别到可用音源", "No usable source recognised")
            },
        )
    } else {
        UnblockSourceStore.addSources(parsed)
        ImportOutcome(
            imported = true,
            message = result.message.ifBlank {
                beansLocalized("已导入 ${parsed.size} 个音源", "Imported ${parsed.size} sources")
            },
        )
    }
} catch (error: Throwable) {
    ImportOutcome(
        imported = false,
        message = beansLocalized(
            "识别失败：${errorDetail(error)}",
            "Could not recognise the input: ${errorDetail(error)}",
        ),
    )
}

/**
 * 失败细节：`<异常类名>: <message>`。
 *
 * 只显示 `message` 是不够的：`NoClassDefFoundError` / `ExceptionInInitializerError` 这类 `Error`
 * 的 message 往往就是一句类名或者干脆为空，用户看到的「识别失败：NoClassDefFoundError」完全
 * 无法判断是加载失败、解析失败还是文件读取失败。带上异常类型后，`识别失败：IllegalStateException:
 * xxx` 才能一眼看出问题层级。
 *
 * 同时把换行 / 连续空白压成单个空格并截断，保证在卡片的两行文案（`maxLines = 2`）和 Toast 里
 * 都能读完，不会被 UI 从中间截掉关键信息。
 */
private fun errorDetail(error: Throwable): String {
    val type = error::class.java.simpleName.ifBlank { "Throwable" }
    val message = error.message?.replace(WHITESPACE_RUN, " ")?.trim()
    if (message.isNullOrEmpty()) return type
    val detail = "$type: $message"
    return if (detail.length <= ERROR_DETAIL_LIMIT) detail else detail.take(ERROR_DETAIL_LIMIT - 1) + "…"
}

/** 状态文案最多两行，超长就截断。 */
private const val ERROR_DETAIL_LIMIT = 160

private val WHITESPACE_RUN = Regex("[\\s\\u0000-\\u001f]+")

/** `ContentResolver` 给的显示名；取不到时退回 `import`。 */
private fun displayNameOf(uri: Uri): String =
    uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "import"

/** 以 UTF-8 读出选中文件的文本；流打不开时按「空文件」处理（失败由调用方统一收敛）。 */
private fun readPickedText(context: Context, uri: Uri): String {
    val stream = context.contentResolver.openInputStream(uri) ?: return ""
    return stream.use { it.bufferedReader(Charsets.UTF_8).readText() }
}

/** 删除按钮的红色（对应 iOS `Color.red.opacity(0.9)`）。 */
private val DeleteRed = Color(red = 0.93f, green = 0.25f, blue = 0.22f)

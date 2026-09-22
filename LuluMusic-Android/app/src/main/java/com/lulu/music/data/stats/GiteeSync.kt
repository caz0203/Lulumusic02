package com.lulu.music.data.stats

import android.util.Log
import com.lulu.music.data.net.BeansApiException
import com.lulu.music.data.net.Http
import com.lulu.music.data.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/**
 * Gitee（码云）仓库配置。
 *
 * 为什么是 Gitee：国内直连可用，Firebase / Supabase 在大陆网络下不可达。
 * 用户在 Gitee 建一个**私有仓库**，生成一个最小权限的私人令牌（只勾 `projects`），填到下面即可。
 * 三项留空时整个功能处于「离线」状态：`isConfigured == false`，不会发出任何网络请求。
 */
object GiteeConfig {
    const val OWNER: String = "Caz0203"           // Gitee 用户名或组织名
    const val REPO: String = "lulumusic"          // 仓库名
    const val TOKEN: String = "bf867025c9a7c8aacfba09926864e4d0" // 私人令牌（需勾 projects）
    const val PATH_PREFIX: String = "lulu-stats"
    const val BRANCH: String = "master"   // 只是首次写入的猜测值，真实分支由 [GiteeSync] 自动发现

    /** OWNER/REPO/TOKEN 均已填写才为 true。 */
    val isConfigured: Boolean
        get() = OWNER.isNotBlank() && REPO.isNotBlank() && TOKEN.isNotBlank()
}

sealed interface SyncResult {
    data object Success : SyncResult
    data object NotConfigured : SyncResult
    data class Failed(val reason: String) : SyncResult
}

/**
 * 把统计同步到 Gitee 私有仓库的 `v5` contents 接口（形状与 GitHub/Gitea 一致）。
 *
 * 实际用到的请求（均已按 x-www-form-urlencoded 提交，base64 里的 `+ / =` 会被正确转义）：
 * - 仓库：`GET {base}/{owner}/{repo}?access_token=…` → `default_branch`（空仓库时为 null）。
 * - 读：`GET  {base}/{owner}/{repo}/contents/{path}?access_token=…&ref={branch}`
 *         → `200 {"content":"<base64，带换行>","sha":"…"}`；文件不存在时 `404`。
 * - 建：`POST {base}/{owner}/{repo}/contents/{path}`
 *         `access_token, content(base64), message, branch` → `201`
 * - 改：`PUT  {base}/{owner}/{repo}/contents/{path}`
 *         `access_token, content(base64), sha(刚 GET 到的), message, branch`
 *         （`Http` 没有 PUT，这里直接用公开的 `Http.client` 发请求）
 *
 * 分支不是写死的：[GiteeConfig.BRANCH] 只作第一次写入的猜测值，一旦写入因「分支不存在」失败，
 * 就回读仓库的 `default_branch` 纠偏一次；空仓库（`default_branch` 为 null）不会盲目重试。
 *
 * 所有公共方法都不抛异常：失败一律降级成 [SyncResult.Failed] / `null`。
 */
object GiteeSync {

    private const val TAG = "GiteeSync"
    private const val API_BASE = "https://gitee.com/api/v5/repos"

    // 会显示在「我的」页同步状态行和崩溃日志里的文案，必须短且可照着做。
    private const val MSG_TOKEN_INVALID = "令牌无效或已过期"
    private const val MSG_TOKEN_SCOPE = "令牌权限不足（需勾选 projects 权限）"
    private const val MSG_REPO_EMPTY = "仓库还没有任何提交，请先在 Gitee 网页端初始化"
    private const val MSG_REPO_MISSING = "仓库不存在或令牌无权访问"

    private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    /** 日志/文案里出现的令牌一律抹掉；OkHttp 的异常 message 可能带上完整 URL。 */
    private val TOKEN_QUERY = Regex("access_token=[^&\\s\"']+")

    /** 自动发现出来的分支名；null 表示还没发现，此时先用 [GiteeConfig.BRANCH] 试探。 */
    @Volatile
    private var cachedBranch: String? = null

    /** 云端探测结果；[download] 把 Error/Missing 都收敛成 `null`，内部同步要区分二者。 */
    internal sealed interface Probe {
        data class Found(val stats: UserStats) : Probe
        data object Missing : Probe
        data class Error(val reason: String) : Probe
    }

    /** 仓库 `default_branch` 的探测结果。 */
    private sealed interface Branch {
        data class Resolved(val name: String) : Branch
        data object Empty : Branch
        data class Error(val reason: String) : Branch
    }

    /** 写入结果：普通失败和「分支不存在」要分开，后者才有一次自动纠偏的机会。 */
    private sealed interface Write {
        data object Done : Write
        data class Fail(val reason: String, val branchMissing: Boolean = false) : Write
    }

    /** 把统计写入仓库的 `<PATH_PREFIX>/<userId>.json`。 */
    suspend fun upload(stats: UserStats): SyncResult {
        if (!GiteeConfig.isConfigured) return SyncResult.NotConfigured
        if (!isValidUserId(stats.userId)) {
            return SyncResult.Failed("用户 ID 非法：${stats.userId}")
        }
        return try {
            withContext(Dispatchers.IO) {
                val url = contentsUrl(filePath(stats.userId))
                val content = Base64.getEncoder().encodeToString(
                    Prefs.json.encodeToString(UserStats.serializer(), stats).toByteArray(Charsets.UTF_8),
                )
                val used = branchOf()
                when (val first = write(url, content, stats.userId, used)) {
                    is Write.Done -> SyncResult.Success
                    is Write.Fail ->
                        if (!first.branchMissing) {
                            SyncResult.Failed(first.reason)
                        } else {
                            // 只在这种情况下多花一次请求：回读仓库默认分支，最多重试一次。
                            when (val discovered = resolveBranch()) {
                                is Branch.Resolved ->
                                    if (discovered.name == used) {
                                        SyncResult.Failed(first.reason)
                                    } else {
                                        retryWrite(url, content, stats.userId, discovered.name)
                                    }
                                Branch.Empty -> SyncResult.Failed(MSG_REPO_EMPTY)
                                is Branch.Error -> SyncResult.Failed(discovered.reason)
                            }
                        }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "upload failed: " + redact(t.message ?: ""))
            SyncResult.Failed(reasonOf(t))
        }
    }

    /** 读取指定用户 ID 的统计；不存在或失败返回 null。 */
    suspend fun download(userId: String): UserStats? = when (val result = probe(userId)) {
        is Probe.Found -> result.stats
        Probe.Missing, is Probe.Error -> null
    }

    // ---- 内部实现 ----------------------------------------------------------

    internal suspend fun probe(userId: String): Probe {
        if (!GiteeConfig.isConfigured) return Probe.Error("未配置 Gitee 仓库")
        if (!isValidUserId(userId)) return Probe.Error("用户 ID 非法：$userId")
        return try {
            withContext(Dispatchers.IO) {
                val file = fetchFile(contentsUrl(filePath(userId)), branchOf())
                if (file == null) {
                    Probe.Missing
                } else {
                    val parsed = runCatching {
                        Prefs.json.decodeFromString(UserStats.serializer(), file.second)
                    }.getOrNull()
                    if (parsed == null || parsed.userId != userId) {
                        // 云端文件损坏 / 内容对不上：当作「空记录」，让后续上传把它修好，
                        // 而不是永远卡在「解析失败」上。
                        Log.w(TAG, "云端数据无法解析（userId=$userId），将按空记录处理")
                        Probe.Found(UserStats(userId = userId))
                    } else {
                        Probe.Found(parsed)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed: " + redact(t.message ?: ""))
            Probe.Error(reasonOf(t))
        }
    }

    /** 当前读写用的分支：已发现的分支优先，否则用配置里的猜测值。 */
    private fun branchOf(): String = cachedBranch?.takeIf { it.isNotBlank() } ?: GiteeConfig.BRANCH

    /**
     * 读仓库元信息里的 `default_branch`，成功即写入内存缓存（之后不再重复请求）。
     *
     * 空仓库会返回 null / 空白，这时**不做任何重试**，让调用方去提示用户先初始化仓库。
     */
    private suspend fun resolveBranch(): Branch = try {
        withContext(Dispatchers.IO) {
            val raw = Http.getText(repoUrl())
            val obj = try {
                JSONObject(raw.trim())
            } catch (e: Exception) {
                return@withContext Branch.Error("响应无法解析")
            }
            // JSON null 在部分 org.json 实现里会被 optString 转成字面量 "null"，先判 isNull。
            val name = if (obj.isNull("default_branch")) "" else obj.optString("default_branch", "").trim()
            if (name.isEmpty()) {
                cachedBranch = null
                Branch.Empty
            } else {
                cachedBranch = name
                Branch.Resolved(name)
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读取仓库默认分支失败：" + reasonOf(t))
        Branch.Error(reasonOf(t))
    }

    /** 用自动发现的分支再写一次；成败都直接折算成 [SyncResult]。 */
    private suspend fun retryWrite(url: String, content: String, userId: String, branch: String): SyncResult =
        when (val retry = write(url, content, userId, branch)) {
            is Write.Done -> SyncResult.Success
            is Write.Fail -> SyncResult.Failed(retry.reason)
        }

    /** GET 拿 sha：拿到就 PUT（更新），404 就 POST（新建）。 */
    private suspend fun write(url: String, content: String, userId: String, branch: String): Write {
        val existingSha = runCatching { fetchFile(url, branch) }
            .onFailure { Log.w(TAG, "读取现有文件失败，将按新建处理：" + redact(it.message ?: "")) }
            .getOrNull()?.first
        return if (existingSha != null) {
            putFile(url, content, existingSha, userId, branch)
        } else {
            createFile(url, content, userId, branch)
        }
    }

    /** `GET contents/{path}?access_token=&ref=`；返回 `sha to 文件文本`，404 返回 null。 */
    private suspend fun fetchFile(url: String, branch: String): Pair<String, String>? {
        val text = try {
            Http.getText(readUrl(url, branch))
        } catch (e: BeansApiException.HttpStatus) {
            if (e.code == 404) return null
            throw e
        }
        val obj = try {
            JSONObject(text.trim())
        } catch (e: Exception) {
            throw BeansApiException.Decoding(text.take(120))
        }
        val sha = obj.optString("sha", "")
        if (sha.isBlank()) return null
        // Gitee 返回的 base64 带换行，必须用 MIME 解码器。
        val decoded = runCatching {
            String(Base64.getMimeDecoder().decode(obj.optString("content", "")), Charsets.UTF_8)
        }.getOrThrow()
        return sha to decoded
    }

    private suspend fun createFile(
        url: String,
        content: String,
        userId: String,
        branch: String,
    ): Write = try {
        Http.postFormText(url, Http.buildForm(fields(content, userId, branch)))
        Write.Done
    } catch (e: BeansApiException.HttpStatus) {
        if (missesBranch(e)) {
            // 分支不存在（空仓库 / 猜错分支）→ 交给上层回读 default_branch 后重试一次。
            Write.Fail(reasonOf(e), branchMissing = true)
        } else if (e.code == 400 || e.code == 409 || e.code == 422) {
            // 文件已存在（上一次同步留下的，或并发写入）→ 取最新 sha 改成 PUT。
            val sha = runCatching { fetchFile(url, branch) }.getOrNull()?.first
            if (sha == null) {
                Write.Fail("文件已存在但无法读取 sha（HTTP ${e.code}）")
            } else {
                putFile(url, content, sha, userId, branch)
            }
        } else {
            Write.Fail(reasonOf(e))
        }
    } catch (t: Throwable) {
        Write.Fail(reasonOf(t))
    }

    private suspend fun putFile(
        url: String,
        content: String,
        sha: String,
        userId: String,
        branch: String,
    ): Write = try {
        putFormText(url, Http.buildForm(fields(content, userId, branch) + ("sha" to sha)))
        Write.Done
    } catch (e: BeansApiException.HttpStatus) {
        Write.Fail(reasonOf(e), branchMissing = missesBranch(e))
    } catch (t: Throwable) {
        Write.Fail(reasonOf(t))
    }

    /**
     * 这个失败是否更像「分支不存在」：Gitee 对空仓库/写错分支常回 404，
     * 有时回 400 且响应里带 branch 字样。只有这类失败才值得再读一次默认分支。
     */
    private fun missesBranch(e: BeansApiException.HttpStatus): Boolean {
        if (e.code == 404) return true
        val snippet = e.snippet.lowercase()
        return snippet.contains("branch") || snippet.contains("分支")
    }

    private fun fields(content: String, userId: String, branch: String): Map<String, String> = linkedMapOf(
        "access_token" to GiteeConfig.TOKEN,
        "content" to content,
        "message" to "lulu-stats: sync $userId",
        "branch" to branch,
    )

    /** `Http` 没有 PUT：用公开的 `Http.client` 直接发。 */
    private suspend fun putFormText(url: String, body: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).put(body.toRequestBody(FORM)).build()
        try {
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw BeansApiException.HttpStatus(response.code, text.take(120))
                }
                text
            }
        } catch (e: BeansApiException) {
            throw e
        } catch (e: IOException) {
            throw BeansApiException.Network(e.message ?: "network error")
        }
    }

    private fun contentsUrl(path: String): String =
        "$API_BASE/${Http.formEncode(GiteeConfig.OWNER)}/${Http.formEncode(GiteeConfig.REPO)}/contents/$path"

    /** 仓库元信息地址，只用来读 `default_branch`。 */
    private fun repoUrl(): String =
        "$API_BASE/${Http.formEncode(GiteeConfig.OWNER)}/${Http.formEncode(GiteeConfig.REPO)}" +
            "?access_token=${Http.formEncode(GiteeConfig.TOKEN)}"

    /** 路径里的 `/` 保持字面量（Gitee 与 GitHub 一致），逐段转义。 */
    private fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Http.formEncode(it) }

    private fun filePath(userId: String): String {
        val prefix = GiteeConfig.PATH_PREFIX.trim().trim('/')
        val name = "$userId.json"
        return if (prefix.isEmpty()) encodePath(name) else "${encodePath(prefix)}/${encodePath(name)}"
    }

    private fun readUrl(url: String, branch: String): String =
        "$url?access_token=${Http.formEncode(GiteeConfig.TOKEN)}&ref=${Http.formEncode(branch)}"

    /** 令牌绝不进日志/文案：先抹掉字面量令牌，再兜底抹掉 `access_token=…`。 */
    private fun redact(text: String): String {
        var out = text
        val token = GiteeConfig.TOKEN
        if (token.isNotBlank()) out = out.replace(token, "***")
        return TOKEN_QUERY.replace(out, "access_token=***")
    }

    private fun reasonOf(t: Throwable): String = when (t) {
        is BeansApiException.HttpStatus -> httpReason(t)
        is BeansApiException.Network -> "网络不可用：" + redact(t.message ?: "连接失败")
        is BeansApiException.Decoding -> "响应无法解析"
        else -> redact(t.message ?: t::class.java.simpleName)
    }

    /**
     * 401 / 403 / 404 换成用户能照着做的中文提示，其余保留「HTTP 码：响应片段」。
     *
     * 注意：读文件时的 404 已经在 [fetchFile] 里被收敛成「文件不存在」，
     * 所以能走到这里的 404 一定是仓库（或分支）这一层的问题。
     */
    private fun httpReason(e: BeansApiException.HttpStatus): String = when (e.code) {
        401 -> MSG_TOKEN_INVALID
        403 -> MSG_TOKEN_SCOPE
        404 -> MSG_REPO_MISSING
        else -> "HTTP ${e.code}：" + redact(e.snippet).ifBlank { "请求被拒绝" }
    }
}

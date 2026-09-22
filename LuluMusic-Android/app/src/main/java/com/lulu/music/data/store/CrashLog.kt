package com.lulu.music.data.store

import android.app.Application
import android.util.Log
import com.lulu.music.BeansApplication
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 极简崩溃日志：把未捕获异常的堆栈追加到 `filesDir/crash.log`。
 *
 * 背景：自定义音源导入在真机上闪退，但用户设备上拿不到 logcat，于是「没有堆栈可查」。
 * 这里在 [BeansApplication.onCreate] 里挂一个全局 handler，先落盘、再交回上一个 handler
 * （保持系统默认行为：进程照样结束，只是崩溃现场被留下来了）。
 *
 * 设计约束：
 * - **零依赖**：只用 JDK + `android.util.Log`；
 * - 只保留最近 [MAX_ENTRIES] 条，并且总长度不超过 [MAX_BYTES]，不会无限增长；
 * - 所有写盘都包在 `runCatching` 里，日志本身绝不抛异常，也不会递归；
 * - 只提供 API（[read] / [write] / [clear]），不做任何 UI。
 *
 * 用法（诊断线上崩溃）：
 * ```
 * adb shell run-as com.lulu.music cat files/crash.log
 * // 或应用内：CrashLog.read()
 * ```
 */
object CrashLog {

    /** 文件名（位于 `filesDir` 下）。 */
    const val FILE_NAME = "crash.log"

    private const val TAG = "LuluCrash"

    /** 最多保留的条目数（最新的在前）。 */
    private const val MAX_ENTRIES = 8

    /** 日志体积上限，超出后只保留最新的那段。 */
    private const val MAX_BYTES = 128 * 1024

    /** 每条崩溃记录的起始标记（裁剪条目 / 阅读日志都靠它）。 */
    private const val ENTRY_MARKER = "===== beans crash "

    private val lock = Any()

    @Volatile
    private var directory: File? = null

    @Volatile
    private var installed = false

    @Volatile
    private var writing = false

    /**
     * 绑定日志目录并安装全局未捕获异常处理器。幂等：重复调用只安装一次。
     *
     * 必须在 `Application.onCreate` 里尽早调用，这样连启动阶段的崩溃也能留下记录。
     */
    fun install(application: Application) {
        directory = application.filesDir
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 1) 留下现场（内部已吞掉自身所有异常）
            append(thread, throwable)
            // 2) 再交回上一个 handler，保持系统原本的崩溃处理行为
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
                exitProcess(10)
            }
        }
    }

    /** 手动记录一个异常（用于「已在 UI 边界收敛、但仍想留现场」的 Throwable）。 */
    fun write(throwable: Throwable?, thread: Thread? = Thread.currentThread()) {
        append(thread, throwable)
    }

    /** 读取整份崩溃日志；没有文件或读取失败时返回空串。只提供 API，不做 UI。 */
    fun read(): String = runCatching {
        val file = File(resolveDirectory(), FILE_NAME)
        if (file.exists()) file.readText() else ""
    }.getOrDefault("")

    /** 清空崩溃日志。 */
    fun clear() {
        runCatching {
            val file = File(resolveDirectory(), FILE_NAME)
            if (file.exists()) file.delete()
        }
    }

    private fun resolveDirectory(): File {
        directory?.let { return it }
        val resolved = runCatching { BeansApplication.instance.filesDir }.getOrNull()
        if (resolved != null) directory = resolved
        return resolved ?: File(".")
    }

    private fun append(thread: Thread?, throwable: Throwable?) {
        if (writing) return
        writing = true
        try {
            if (throwable != null) {
                runCatching { Log.e(TAG, "uncaught: ${throwable::class.java.name}", throwable) }
            }
            val entry = format(thread, throwable)
            synchronized(lock) {
                val file = File(resolveDirectory(), FILE_NAME)
                val previous = runCatching { if (file.exists()) file.readText() else "" }
                    .getOrDefault("")
                runCatching { file.writeText(trim(entry + previous)) }
            }
        } catch (_: Throwable) {
            // 日志本身绝不能成为新的崩溃源。
        } finally {
            writing = false
        }
    }

    private fun format(thread: Thread?, throwable: Throwable?): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val builder = StringBuilder()
        builder.append(ENTRY_MARKER).append(time)
            .append(" thread=").append(thread?.name ?: "unknown")
            .append(" priority=").append(thread?.priority ?: -1)
            .append('\n')
        if (throwable == null) {
            builder.append("(no throwable)\n")
        } else {
            val writer = StringWriter()
            runCatching { throwable.printStackTrace(PrintWriter(writer)) }
            builder.append(writer.toString())
        }
        builder.append('\n')
        return builder.toString()
    }

    /** 只保留最近 [MAX_ENTRIES] 条，并裁到 [MAX_BYTES] 以内。 */
    private fun trim(text: String): String {
        var result = text
        var seen = 0
        var index = result.indexOf(ENTRY_MARKER)
        while (index >= 0) {
            seen++
            if (seen > MAX_ENTRIES) {
                result = result.substring(0, index)
                break
            }
            index = result.indexOf(ENTRY_MARKER, index + ENTRY_MARKER.length)
        }
        if (result.length > MAX_BYTES) {
            val cut = result.indexOf('\n', result.length - MAX_BYTES)
            result = if (cut in 0 until result.length - 1) {
                result.substring(cut + 1)
            } else {
                result.takeLast(MAX_BYTES)
            }
        }
        return result
    }
}

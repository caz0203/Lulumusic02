package com.lulu.music.playback

import android.content.Context
import com.lulu.music.data.model.BeansAudioQuality
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.ThirdPartyAudioQuality
import com.lulu.music.data.net.Http
import com.lulu.music.data.net.userFacingReason
import com.lulu.music.data.store.DownloadRecord
import com.lulu.music.data.store.DownloadStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline downloads.
 *
 * Port of the iOS `DownloadManager`. Audio is written to the app's private
 * `filesDir/downloads` directory; the index lives in [DownloadStore]. Downloads are resolved with
 * the same URL logic the player uses ([MediaResolver.resolveUrl]), so the file you get offline is
 * the same stream you would have played online at the selected quality.
 */
object DownloadManager {

    enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    data class Task(
        val song: Song,
        val progress: Float = 0f,
        val state: State = State.QUEUED,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    val tasks: StateFlow<Map<String, Task>> = _tasks.asStateFlow()

    private var dir: File? = null

    fun init(context: Context) {
        val d = File(context.applicationContext.filesDir, "downloads")
        if (!d.exists()) d.mkdirs()
        dir = d
        DownloadStore.load()
        pruneMissing()
    }

    /** Absolute file for a downloaded song, or null when it is not downloaded / the file is gone. */
    fun localFileFor(identityKey: String): File? {
        val record = DownloadStore.records.value.firstOrNull { it.song.identityKey == identityKey }
            ?: return null
        val base = dir ?: return null
        val f = File(base, record.fileName)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun localFileFor(song: Song?): File? = song?.let { localFileFor(it.identityKey) }

    fun isDownloaded(song: Song?): Boolean = localFileFor(song) != null

    fun taskFor(song: Song?): Task? = song?.let { _tasks.value[it.identityKey] }

    /**
     * Start (or restart) a download. Quality defaults to the app's configured audio quality.
     * Safe to call repeatedly: an in-flight download for the same song is not duplicated.
     */
    fun download(song: Song, quality: BeansAudioQuality? = null) {
        val key = song.identityKey
        if (jobs[key]?.isActive == true) return
        val resolvedQuality = quality
            ?: BeansAudioQuality.fromRaw(com.lulu.music.data.prefs.SettingsStore.audioQuality.value)

        updateTask(Task(song = song, state = State.QUEUED))
        jobs[key] = scope.launch {
            try {
                updateTask(Task(song = song, state = State.RUNNING, progress = 0f))

                val url = MediaResolver.resolveUrl(song, resolvedQuality)
                    ?: throw IllegalStateException("no playable url")

                val base = dir ?: throw IllegalStateException("download dir unavailable")
                val extension = extensionFor(url)
                val fileName = "${song.source.raw}-${sanitize(song.id.toString())}.$extension"
                val target = File(base, fileName)
                val temp = File(base, "$fileName.part")

                // 与播放共用同一套平台请求头：QQ / 酷狗 CDN 缺 Referer 会 403，
                // 第三方音源直链也可能要求自己的 UA / Referer / Cookie。
                val requestBuilder = Request.Builder().url(url)
                runCatching {
                    MediaResolver.streamRequestHeaders(android.net.Uri.parse(url))
                        .forEach { (key, value) -> requestBuilder.header(key, value) }
                }
                val request = requestBuilder.build()
                Http.streamClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("empty body")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                if (jobs[key]?.isCancelled == true) throw CancellationSignal()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                val progress = if (total > 0) {
                                    (written.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                updateTask(Task(song = song, state = State.RUNNING, progress = progress))
                            }
                            output.flush()
                        }
                    }
                }

                if (temp.length() <= 0) throw IllegalStateException("empty file")
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }

                DownloadStore.upsert(
                    DownloadRecord(
                        song = song,
                        fileName = fileName,
                        quality = ThirdPartyAudioQuality.fromSourceValue(
                            com.lulu.music.data.prefs.SettingsStore.audioQuality.value,
                        )?.raw ?: resolvedQuality.level,
                        bytes = target.length(),
                        downloadedAt = System.currentTimeMillis(),
                    ),
                )
                updateTask(Task(song = song, state = State.DONE, progress = 1f))
            } catch (t: CancellationSignal) {
                updateTask(Task(song = song, state = State.CANCELLED))
            } catch (t: Throwable) {
                updateTask(Task(song = song, state = State.FAILED, error = userFacingReason(t)))
            } finally {
                jobs.remove(key)
            }
        }
    }

    fun cancel(song: Song) {
        jobs[song.identityKey]?.cancel()
        jobs.remove(song.identityKey)
        updateTask(Task(song = song, state = State.CANCELLED))
    }

    /** Remove the file and the index entry. */
    fun delete(song: Song) {
        cancel(song)
        val record = DownloadStore.recordFor(song)
        if (record != null) {
            dir?.let { File(it, record.fileName).delete() }
        }
        DownloadStore.remove(song)
        _tasks.value = _tasks.value - song.identityKey
    }

    fun deleteAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        dir?.listFiles()?.forEach { it.delete() }
        DownloadStore.clear()
        _tasks.value = emptyMap()
    }

    /** Drop index entries whose file disappeared (e.g. cleared by the system). */
    private fun pruneMissing() {
        val base = dir ?: return
        val alive = DownloadStore.records.value.filter { File(base, it.fileName).exists() }
        if (alive.size != DownloadStore.records.value.size) {
            DownloadStore.clear()
            alive.reversed().forEach { DownloadStore.upsert(it) }
        }
    }

    private fun updateTask(task: Task) {
        _tasks.value = _tasks.value + (task.song.identityKey to task)
    }

    /** Pick a sensible file extension from the CDN URL, defaulting to mp3. */
    private fun extensionFor(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3", "flac", "m4a", "aac", "ogg", "wav", "ape" -> ext
            else -> "mp3"
        }
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    private class CancellationSignal : RuntimeException("cancelled")
}

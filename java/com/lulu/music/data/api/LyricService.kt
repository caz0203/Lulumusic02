package com.lulu.music.data.api

import com.lulu.music.data.model.LyricHighlight
import com.lulu.music.data.model.LyricLine
import com.lulu.music.data.model.Song
import com.lulu.music.data.model.SongSource

/**
 * 歌词的**唯一入口**：按曲目来源取「最好的那版歌词」。
 *
 * 返回值永远是 `List<LyricLine>`，契约是用户定的那一句：
 *
 * > 有真数据就用真的，没有就退回整行高亮
 *
 * 落到类型上就是：逐字歌词可用时，[LyricLine.words] 有真时间轴（[LyricHighlight.hasWordTiming]
 * 为 true）；不可用时 `words` 为空列表，渲染端按整行高亮走。**整行歌词成功时绝不返回空列表**。
 *
 * 三个平台各自的分支（也都在本包里，可单独调用）：
 * - 网易云 [NetEaseApi.lyricWithWords] —— YRC（`/api/song/lyric` + `yv/ytv/yrv`）
 * - QQ 音乐 [QQMusicApi.lyricWithWords] —— musicu QRC（3DES + zlib）
 * - 酷狗 [KugouMusicApi.lyricWithWords] —— KRC（定长异或 + zlib）
 *
 * 异常语义与原来的 `PlayerScreen.loadLyrics` 一致：网络/解析失败会往上抛，由调用方
 * `runCatching` 决定怎么显示（本层不吞异常，免得把「真的失败了」和「本来就没歌词」混在一起）。
 */
object LyricService {

    /** 按曲目来源取歌词（逐字优先，逐字缺席时退回整行）。 */
    suspend fun lyricsFor(song: Song): List<LyricLine> = when (song.source) {
        SongSource.NET_EASE -> NetEaseApi.lyricWithWords(song.id)

        SongSource.QQ -> {
            val mid = song.qqMid
            if (mid.isNullOrBlank()) emptyList() else QQMusicApi.lyricWithWords(mid)
        }

        SongSource.KUGOU -> {
            val hash = song.kugouHash
            if (hash.isNullOrBlank()) emptyList() else KugouMusicApi.lyricWithWords(hash, song.duration)
        }
    }
}

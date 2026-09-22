package com.lulu.music.data.net

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 酷狗 KRC 歌词的解码。
 *
 * ## 数据流
 *
 * `base64 → 4 字节 magic "krc1" → 余下全部字节与固定的 16 字节密钥循环异或 → zlib 解压 → UTF-8 文本`
 *
 * 两个容易踩错的点（本机对着真实响应验证过）：
 *  1. 只去掉 **4 字节** 的 `krc1`，不是 8 字节。第 5~8 字节看起来像「每份文件都不同的密钥」
 *     （实测同一首歌多次下载这 4 字节会变），但它**不是**密钥，当成密钥去异或必然解压失败。
 *  2. 异或密钥是**写死的 16 字节常量**，与文件内容无关。
 *
 * 明文结构见 [com.lulu.music.data.model.KrcParser]。
 */
object KrcCodec {

    private const val MAGIC = "krc1"

    /** 固定异或密钥；来源见类注释，已用真实响应反向确认。 */
    private val XOR_KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    /** `base64 文本 → KRC 明文`；任何一步失败都返回 null。 */
    fun decodeBase64(content: String?): String? {
        if (content.isNullOrEmpty()) return null
        val cleaned = content.filterNot { it.isWhitespace() }
        val bytes = try {
            Base64.getDecoder().decode(cleaned)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return decode(bytes)
    }

    /** `KRC 二进制 → 明文`；magic 不是 `krc1`、解压失败都返回 null。 */
    fun decode(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size <= MAGIC.length) return null
        for (i in MAGIC.indices) {
            if (bytes[i].toInt() != MAGIC[i].code) return null
        }
        val body = ByteArray(bytes.size - MAGIC.length)
        for (i in body.indices) {
            body[i] = (bytes[i + MAGIC.length].toInt() xor XOR_KEY[i % XOR_KEY.size]).toByte()
        }
        return inflate(body)
    }

    /** 编码方向：`UTF-8 → deflate → 异或 → 前置 magic`。只用于单测的往返验证。 */
    internal fun encode(text: String): ByteArray {
        val compressed = deflate(text.toByteArray(Charsets.UTF_8))
        val out = ByteArray(MAGIC.length + compressed.size)
        for (i in MAGIC.indices) out[i] = MAGIC[i].code.toByte()
        for (i in compressed.indices) {
            out[MAGIC.length + i] = (compressed[i].toInt() xor XOR_KEY[i % XOR_KEY.size]).toByte()
        }
        return out
    }

    private fun inflate(data: ByteArray): String? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                    continue
                }
                break
            }
            if (out.size() == 0) null else String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size * 2 + 64)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}

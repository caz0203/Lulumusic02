package com.lulu.music.data.net

import java.math.BigDecimal
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object NetEaseCrypto {
    private val fixedKey = "0CoJUm6Qyw8W8jud".toByteArray(Charsets.UTF_8)
    private val fixedIV = "0102030405060708".toByteArray(Charsets.UTF_8)
    private val eapiKey = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)
    private const val rsaModulusHex =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    // RSA 公钥指数固定 65537，密文按定长 128 字节输出（对应 BigUInt.data(count: 128)）
    private const val rsaExponent = 65_537L
    private const val rsaKeyLength = 128

    private const val randomAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val secureRandom = SecureRandom()

    // MARK: - AES-128-CBC (weapi 第一层)

    private fun aesCBCEncrypt(input: ByteArray, key: ByteArray): ByteArray? =
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(fixedIV))
            cipher.doFinal(input)
        } catch (e: GeneralSecurityException) {
            null
        }

    // MARK: - AES-128-ECB (eapi)

    private fun aesECBEncrypt(input: ByteArray, key: ByteArray): ByteArray? =
        try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(input)
        } catch (e: GeneralSecurityException) {
            null
        }

    // MARK: - weapi（密钥反转 + RAW RSA 无填充）

    fun weapi(payload: Map<String, Any?>): Map<String, String> {
        val text = jsonString(payload)
        val first = aesCBCEncrypt(text.toByteArray(Charsets.UTF_8), fixedKey) ?: return emptyMap()
        val secretKey = random16()
        val second = aesCBCEncrypt(
            base64Encode(first).toByteArray(Charsets.UTF_8),
            secretKey.toByteArray(Charsets.UTF_8),
        ) ?: return emptyMap()
        val reversed = secretKey.reversed()
        val encSecKey = rawRSAEncrypt(reversed.toByteArray(Charsets.UTF_8)) ?: return emptyMap()
        return mapOf(
            "params" to base64Encode(second),
            "encSecKey" to hexString(encSecKey),
        )
    }

    // MARK: - eapi（params 单字段）

    fun eapi(payload: Map<String, Any?>, path: String): Map<String, String> {
        val text = jsonString(payload)
        val message = "nobody${path}use${text}md5forencrypt"
        val digest = md5Hex(message.toByteArray(Charsets.UTF_8))
        val data = "$path-36cd479b6b5-$text-36cd479b6b5-$digest"
        val params = aesECBEncrypt(data.toByteArray(Charsets.UTF_8), eapiKey) ?: return emptyMap()
        return mapOf("params" to hexString(params))
    }

    // MARK: - RAW RSA（m^e mod n，无填充）

    fun rawRSAEncrypt(input: ByteArray): ByteArray? {
        val modulus = BigInteger(rsaModulusHex, 16)
        val message = if (input.isEmpty()) BigInteger.ZERO else BigInteger(1, input)
        val result = message.modPow(BigInteger.valueOf(rsaExponent), modulus)
        // 与 BigUInt.data(count:) 相同：取低 128 字节，不足时左侧补 0，超长时丢弃高位
        val bytes = result.toByteArray()
        val out = ByteArray(rsaKeyLength)
        val copy = minOf(rsaKeyLength, bytes.size)
        bytes.copyInto(out, rsaKeyLength - copy, bytes.size - copy, bytes.size)
        return out
    }

    private fun hexString(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (byte in data) {
            sb.append("%02x".format(byte.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun random16(): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(randomAlphabet[secureRandom.nextInt(randomAlphabet.length)]) }
        return sb.toString()
    }

    private fun md5Hex(data: ByteArray): String =
        hexString(MessageDigest.getInstance("MD5").digest(data))

    private fun base64Encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    private fun jsonString(payload: Map<String, Any?>): String =
        try {
            jsonObject(payload)
        } catch (e: IllegalArgumentException) {
            "{}"
        }

    private fun jsonObject(map: Map<*, *>): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((key, value) in map) {
            if (key !is String) throw IllegalArgumentException("JSON 键必须是字符串")
            if (!first) sb.append(',')
            first = false
            sb.append(jsonStringLiteral(key)).append(':').append(jsonValue(value))
        }
        sb.append('}')
        return sb.toString()
    }

    private fun jsonArray(values: Iterable<*>): String {
        val sb = StringBuilder()
        sb.append('[')
        var first = true
        for (value in values) {
            if (!first) sb.append(',')
            first = false
            sb.append(jsonValue(value))
        }
        sb.append(']')
        return sb.toString()
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> jsonStringLiteral(value)
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is Float, is Double -> jsonNumber((value as Number).toDouble())
        is BigInteger -> value.toString()
        is BigDecimal -> value.toPlainString()
        is Map<*, *> -> jsonObject(value)
        is Iterable<*> -> jsonArray(value)
        is Array<*> -> jsonArray(value.asList())
        is IntArray -> jsonArray(value.toList())
        is LongArray -> jsonArray(value.toList())
        is ShortArray -> jsonArray(value.toList())
        is DoubleArray -> jsonArray(value.toList())
        is FloatArray -> jsonArray(value.toList())
        is BooleanArray -> jsonArray(value.toList())
        else -> throw IllegalArgumentException("无法序列化为 JSON：${value::class.java.name}")
    }

    private fun jsonNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) {
            throw IllegalArgumentException("JSON 不支持 NaN / Infinity")
        }
        if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        return value.toString()
    }

    // Apple JSONSerialization 的默认行为：斜杠输出为 \/
    private fun jsonStringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u").append("%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

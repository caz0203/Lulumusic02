package com.lulu.music

import com.lulu.music.data.model.Lang
import com.lulu.music.data.net.BeansApiException
import com.lulu.music.data.net.userFacingMessage
import com.lulu.music.data.net.userFacingReason
import com.lulu.music.data.prefs.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [userFacingMessage] 的回归测试。
 *
 * 这条规则保护的是一个**真实出现过的用户可见缺陷**：歌单详情页以前直接渲染
 * `Throwable.message`，于是请求失败时用户看到的是 OkHttp 的英文原文
 * （`Failed to connect to music.163.com:443`）甚至框架内部描述。
 *
 * 因此这里除了逐类断言文案，还额外锁一条不变量：
 * **中文模式下，任何异常翻译出来的文案都不允许含拉丁字母** ——
 * 一旦有人哪天又把原始 message 拼回去，这条断言会立刻失败。
 */
class ErrorMessagesTest {

    @After
    fun restoreChinese() {
        // Lang 是 JVM 单例，测试之间会互相影响，必须还原。
        Lang.current.value = AppLanguage.CHINESE
    }

    private val connectFailure = BeansApiException.Network("Failed to connect to music.163.com:443")

    // ---------------------------------------------------------------- 中文文案

    @Test
    fun networkFailureBecomesChineseNetworkMessage() {
        assertEquals("网络连接失败，请检查网络", userFacingMessage(connectFailure))
    }

    @Test
    fun httpStatusCodesMapToDistinctMessages() {
        assertEquals("没有访问权限，请稍后重试", userFacingMessage(BeansApiException.HttpStatus(401, "x")))
        assertEquals("没有访问权限，请稍后重试", userFacingMessage(BeansApiException.HttpStatus(403, "x")))
        assertEquals("内容不存在或已下架", userFacingMessage(BeansApiException.HttpStatus(404, "x")))
        assertEquals(
            "服务器暂时不可用，请稍后重试",
            userFacingMessage(BeansApiException.HttpStatus(503, "x")),
        )
        assertEquals("请求失败，请稍后重试", userFacingMessage(BeansApiException.HttpStatus(418, "x")))
    }

    @Test
    fun decodingAndUnknownAreUserFacing() {
        assertEquals("数据解析失败，请稍后重试", userFacingMessage(BeansApiException.Decoding("<html>")))
        assertEquals("出错了，请稍后重试", userFacingMessage(BeansApiException.Unknown("boom")))
    }

    @Test
    fun bareJavaNetExceptionsAreHandledToo() {
        // 少数调用点直接用 OkHttp，没有包成 BeansApiException。
        assertEquals("连接超时，请重试", userFacingMessage(SocketTimeoutException("timeout")))
        assertEquals("网络连接失败，请检查网络", userFacingMessage(UnknownHostException("music.163.com")))
        assertEquals("网络连接失败，请检查网络", userFacingMessage(IOException("unexpected end of stream")))
        assertEquals("出错了，请稍后重试", userFacingMessage(RuntimeException("IllegalStateException: x")))
    }

    /**
     * 不变量：中文模式下界面文案里不允许出现英文原文。
     *
     * 这正是这次修复要根除的症状（英文原文泄漏到 UI），所以把它写成断言而不是靠人工检查。
     */
    @Test
    fun chineseMessagesNeverLeakLatinLetters() {
        val samples = listOf(
            connectFailure,
            BeansApiException.HttpStatus(403, "forbidden"),
            BeansApiException.HttpStatus(404, "not found"),
            BeansApiException.HttpStatus(500, "server error"),
            BeansApiException.HttpStatus(418, "teapot"),
            BeansApiException.Decoding("<html>"),
            BeansApiException.Unknown("boom"),
            SocketTimeoutException("timeout"),
            UnknownHostException("music.163.com"),
            IOException("unexpected end of stream"),
            RuntimeException("IllegalStateException"),
        )
        for (sample in samples) {
            val message = userFacingMessage(sample)
            assertTrue("文案不能为空：$sample", message.isNotBlank())
            assertFalse(
                "中文文案里不允许出现拉丁字母，实际为「$message」（来自 $sample）",
                Regex("[A-Za-z]").containsMatchIn(message),
            )
        }
    }

    // ---------------------------------------------------------------- 英文模式

    @Test
    fun englishModeProducesEnglishMessages() {
        Lang.current.value = AppLanguage.ENGLISH
        assertEquals(
            "Network unavailable, please check your connection",
            userFacingMessage(connectFailure),
        )
        assertEquals("Content not found", userFacingMessage(BeansApiException.HttpStatus(404, "x")))
        assertFalse(Regex("[\\u4e00-\\u9fff]").containsMatchIn(userFacingMessage(connectFailure)))
    }

    // ---------------------------------------------------------------- 拼接用的原因片段

    /**
     * [userFacingReason] 用于「某某失败：<原因>」的拼接。
     *
     * 关键点：**我们自己写的中文原因必须原样保留** —— 它比通用文案有用得多，
     * 例如备份导入时抛出的「备份里的 xxx 不是有效数据」；
     * 而底层框架的英文原文必须被换掉。
     * 这条规则一旦被改成「一律替换」，用户会从「看得懂的具体原因」退化成「出错了」。
     */
    @Test
    fun chineseDomainReasonIsPreservedVerbatim() {
        val domain = IllegalArgumentException("备份里的 favorites 不是有效数据")
        assertEquals("备份里的 favorites 不是有效数据", userFacingReason(domain))
        assertEquals("无法读取所选文件", userFacingReason(IllegalStateException("无法读取所选文件")))
        assertEquals("不是 LuluMusic 的备份文件（format=x）", userFacingReason(IllegalStateException("不是 LuluMusic 的备份文件（format=x）")))
    }

    @Test
    fun englishReasonIsReplacedWithChinese() {
        assertEquals("网络连接失败，请检查网络", userFacingReason(IOException("unexpected end of stream")))
        assertEquals("没有访问权限，请稍后重试", userFacingReason(BeansApiException.HttpStatus(403, "forbidden")))
        assertEquals("数据解析失败，请稍后重试", userFacingReason(BeansApiException.Decoding("<html>")))
        // 没有 message 时不能退化成 "null" 或类名。
        assertEquals("出错了，请稍后重试", userFacingReason(RuntimeException()))
        assertEquals("出错了，请稍后重试", userFacingReason(null))
    }

    /** 不变量：拼进界面的原因片段在中文模式下必须含汉字，绝不能是英文原文。 */
    @Test
    fun reasonAlwaysContainsHanInChineseMode() {
        val samples = listOf<Throwable?>(
            null,
            RuntimeException(),
            IOException("connection reset"),
            BeansApiException.Network("Failed to connect to music.163.com:443"),
            BeansApiException.HttpStatus(500, "internal server error"),
            IllegalArgumentException("备份里的 favorites 不是有效数据"),
        )
        for (sample in samples) {
            val reason = userFacingReason(sample)
            assertTrue("原因不能为空：$sample", reason.isNotBlank())
            assertTrue(
                "原因里必须含汉字，实际为「$reason」（来自 $sample）",
                Regex("[\\u4e00-\\u9fff]").containsMatchIn(reason),
            )
        }
    }
}

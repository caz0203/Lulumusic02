package com.lulu.music

import com.lulu.music.data.feedback.FEEDBACK_EMAIL
import com.lulu.music.data.feedback.EmailFeedback
import com.lulu.music.data.model.Lang
import com.lulu.music.data.prefs.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [EmailFeedback] 的纯文本拼装测试（不需要 Android 运行时：只测主题与正文）。
 *
 * 保护两件用户可见的事：
 *  - 主题永远是 `[LuluMusic 反馈] <正文第一行>`，且第一行超长时被截断（邮件主题栏放不下）；
 *  - 正文里既有用户原文，也有自动附带的环境信息，中间有明确的分隔线。
 */
class EmailFeedbackTest {

    private val tag = "[LuluMusic 反馈] "

    @Before
    fun forceChinese() {
        // Lang 是 JVM 单例：其它测试类可能把它切成英文，这里固定成中文再断言文案。
        Lang.current.value = AppLanguage.CHINESE
    }

    @After
    fun restoreChinese() {
        Lang.current.value = AppLanguage.CHINESE
    }

    @Test
    fun recipientIsTheProjectMailbox() {
        assertEquals("57844458@qq.com", FEEDBACK_EMAIL)
    }

    @Test
    fun subjectUsesTheFirstNonBlankLineOnly() {
        val subject = EmailFeedback.buildSubject("\n\n  歌曲播放不了  \n第二行不该出现")
        assertTrue("主题必须以固定前缀开头，实际 $subject", subject.startsWith(tag))
        assertTrue("主题要带上正文第一行，实际 $subject", subject.contains("歌曲播放不了"))
        assertFalse("主题只取第一行，实际 $subject", subject.contains("第二行不该出现"))
    }

    @Test
    fun subjectTruncatesAnOverlongFirstLine() {
        val long = "a".repeat(200)
        val subject = EmailFeedback.buildSubject(long)
        assertEquals("第一行必须截断，主题不能无限长", tag + "a".repeat(60), subject)
    }

    @Test
    fun bodyCarriesTheTextASeparatorAndTheEnvironment() {
        val environment = "设备：Xiaomi 14\n系统：Android 14 (API 34)"
        val body = EmailFeedback.buildBody("  闪退，复现步骤如下  ", environment)

        assertTrue("正文必须包含用户原文（trim 后）", body.contains("闪退，复现步骤如下"))
        assertFalse("正文里的原文也必须是 trim 过的", body.contains("  闪退，复现步骤如下  "))
        assertTrue("正文必须有分隔线", body.contains("----------"))
        assertTrue("正文必须带运行环境", body.contains("设备：Xiaomi 14") && body.contains("系统：Android 14 (API 34)"))
        assertTrue("环境信息要写成列表项", body.contains("- 设备：Xiaomi 14"))
    }

    @Test
    fun bodySurvivesAMissingEnvironment() {
        val body = EmailFeedback.buildBody("没有环境信息", "")
        assertTrue("正文仍要包含用户原文", body.contains("没有环境信息"))
        assertTrue("没有环境信息时不能留空白", body.contains("未采集到环境信息"))
    }

    @Test
    fun clipboardTextMatchesTheMailBody() {
        val environment = "设备：Xiaomi 14"
        assertEquals(
            "复制到剪贴板的内容必须与邮件正文一致",
            EmailFeedback.buildBody("反馈内容", environment),
            EmailFeedback.clipboardText("反馈内容", environment),
        )
    }
}

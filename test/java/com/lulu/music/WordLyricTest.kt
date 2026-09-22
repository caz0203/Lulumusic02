package com.lulu.music

import com.lulu.music.data.model.KrcParser
import com.lulu.music.data.model.LyricFallback
import com.lulu.music.data.model.LyricHighlight
import com.lulu.music.data.model.LyricLine
import com.lulu.music.data.model.LyricParser
import com.lulu.music.data.model.LyricWord
import com.lulu.music.data.model.QrcParser
import com.lulu.music.data.model.YrcParser
import com.lulu.music.data.net.KrcCodec
import com.lulu.music.data.net.QrcCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字（卡拉 OK）歌词的数据层：三个平台的解析/解密、高亮纯函数、以及「有真数据就用真的，
 * 没有就退回整行」的兜底契约。
 *
 * ## 关于测试数据
 *
 * 这里**没有一个字节来自三个平台的真实响应**：所有明文都是本文件里手写的合成串，
 * QRC 的密文和 KRC 的二进制都是拿这些合成串在**另一套独立的实现**（Node 里照着
 * `L-1124/QQMusicApi` 的 Python 与 `WXRIW/QQMusicDecoder` 的 C# 重写的探针）现算出来的，
 * 再把结果当已知答案钉进来。这样做能验到单靠 Kotlin 自产自销验不到的东西：
 * **SBOX / 位序抄错**（自产自销的往返测试对这种事完全不敏感）。
 *
 * ## 不覆盖什么
 *
 * 三个 `*Api.lyricWithWords` 的**网络**路径一行都没测：Robolectric 里网络被
 * `Http.guardNetwork()` 挡着，而这里连 Application 都不需要。端点/字段/格式的结论见
 * 各自的 KDoc 与本次任务的报告，属于「实测过一次」而不是「每次构建都回归」。
 */
class WordLyricTest {

    // ------------------------------------------------------------------
    // 合成数据（全部手写，不含真实歌词）
    // ------------------------------------------------------------------

    private val yrcSynthetic = listOf(
        "[ti:synthetic]",
        "[0,0](0,0,0) meta line",
        "[1000,2000](1000,200,0)He(1200,300,0)llo(1500,100,0) (1600,400,0)world",
        "[3000,1000](3000,500,0)tail",
    ).joinToString("\n")

    /** QRC 解密后的明文（XML 外壳 + LyricContent 属性）。 */
    private val qrcSyntheticPlain = listOf(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
        "<QrcInfos>",
        "<QrcHeadInfo SaveTime=\"1\" Version=\"100\"/>",
        "<LyricInfo LyricCount=\"1\">",
        "<Lyric_1 LyricType=\"1\" LyricContent=\"[ti:Test Song]",
        "[ar:Tester]",
        "[0,2000]He(0,200)llo(200,300) (500,100)world(600,400)",
        "[2000,1500]Sec(2000,500)ond(2500,300) (2800,200)line(3000,500)",
        "[3500,1000]plain line without words",
        "\"/>",
        "</LyricInfo>",
        "</QrcInfos>",
    ).joinToString("\n")

    /** KRC 解密解压后的明文（CRLF 行尾，与真实响应一致）。 */
    private val krcSyntheticPlain = "[offset:0]\r\n" +
        "[0,2000]<0,200,0>He<200,300,0>llo<500,100,0> <600,400,0>world\r\n" +
        "[2000,1500]<0,500,0>Sec<500,300,0>ond<800,200,0> <1000,500,0>line\r\n"

    /** 独立实现算出的 QRC 密文（3DES + zlib）。 */
    private val qrcSyntheticHex =
        "AB2F323B4929BC1468DEA448CDB419D2FCCC9D07C536C38D634DFCB994D2ED34A215463949156FF110145150" +
            "3A8BD88DCF7CD136341D1C4D1A497A888E102FAF65333EFB35799711ECD5D13488B39D8107A46E649C37FC" +
            "4DF3B832ECABD4E2EB49A5A0830C3851A50D9EDC6021706CBF555C32305C85C7447FCE2107309246F47610" +
            "688281B11DC4CD96950F9384DA04BFCCF8F5D215F63FDB228F6FFBE6A901F7133D67CB151255629060E699" +
            "CF3A316C3564B6EA70B6AA5ACD83EF668CA3B71978747E2BA096014DF4EBE117F388B25B7879DBFB8B448" +
            "528BCB9B64B7D6C05AF0B8021AC61DADA96E1F944BA8A02FE25CF319F4A620EDD"

    /** 独立实现算出的 KRC base64（`krc1` + 定长异或 + zlib）。 */
    private val krcSyntheticBase64 =
        "a3JjMTjbVPtvOPR3QTLKbTAybceY73DmneiqdQlfM2+GkmvWb/4BAuXeuf9N9Nyyx0k4Z5RFuS9wUyp7xuZ4pP9h" +
            "a6EILBl39qWIoXooa5TMQd+8ICI7inBiIaMlxrnyEGJHKT3LBoUET/j4cHM="

    private val delta = 1e-6

    // ------------------------------------------------------------------
    // 1. YRC（网易云）
    // ------------------------------------------------------------------

    @Test
    fun yrcParsesLineAndWordTimesInSeconds() {
        val lines = YrcParser.parse(yrcSynthetic)
        assertEquals("合成 yrc 应该有 3 行（[ti:] 元信息行必须被跳过）", 3, lines.size)

        val first = lines[0]
        assertEquals(0.0, first.time, delta)
        assertEquals("meta line", first.text)
        assertEquals("只有一个词的元信息行不能算逐字", 1, first.words.size)
        assertEquals(0.0, first.words[0].time, delta)

        val second = lines[1]
        assertEquals(1.0, second.time, delta)
        assertEquals("去掉时间标记后必须还原成肉眼看到的整行文本", "Hello world", second.text)
        assertEquals(4, second.words.size)
        assertEquals(listOf("He", "llo", " ", "world"), second.words.map { it.text })
        assertEquals(listOf(1.0, 1.2, 1.5, 1.6), second.words.map { it.time },)
        assertEquals(listOf(0.2, 0.3, 0.1, 0.4), second.words.map { it.duration })
        assertEquals(3.0, lines[2].time, delta)
    }

    @Test
    fun yrcLineWithoutWordMarkersIsLineLevelOnly() {
        val lines = YrcParser.parse("[500,1000]just text")
        assertEquals(1, lines.size)
        assertEquals(0.5, lines[0].time, delta)
        assertEquals("just text", lines[0].text)
        assertTrue("没有词标记就必须是空 words（退回整行高亮）", lines[0].words.isEmpty())
        assertTrue("整篇都不带逐字时间", !LyricHighlight.hasWordTiming(lines))
    }

    // ------------------------------------------------------------------
    // 2. QRC（QQ 音乐）
    // ------------------------------------------------------------------

    @Test
    fun qrcHexDecryptsToTheIndependentlyProducedPlaintext() {
        val plain = QrcCipher.decryptHexToText(qrcSyntheticHex)
        assertNotNull("已知答案解密不能失败", plain)
        assertEquals(qrcSyntheticPlain, plain)
    }

    @Test
    fun qrcParsesXmlWrappedWordLyrics() {
        val lines = QrcParser.parse(qrcSyntheticPlain)
        assertEquals("XML 头 + [ti:] + [ar:] 都要被吃掉，只剩 3 行歌词", 3, lines.size)

        assertEquals(0.0, lines[0].time, delta)
        assertEquals("Hello world", lines[0].text)
        assertEquals(listOf("He", "llo", " ", "world"), lines[0].words.map { it.text })
        assertEquals(listOf(0.0, 0.2, 0.5, 0.6), lines[0].words.map { it.time })

        assertEquals(2.0, lines[1].time, delta)
        assertEquals("Second line", lines[1].text)
        assertEquals(listOf(2.0, 2.5, 2.8, 3.0), lines[1].words.map { it.time })

        assertEquals(3.5, lines[2].time, delta)
        assertEquals("plain line without words", lines[2].text)
        assertTrue("这一行没有词标记，words 必须为空", lines[2].words.isEmpty())
    }

    @Test
    fun qrcXmlEntitiesAndEscapesAreDecoded() {
        val xml = "<Lyric_1 LyricType=\"1\" LyricContent=\"[0,1000]A &amp; B &lt;x&gt; &quot;q&quot; &#65;&#x42;\"/>"
        val lines = QrcParser.parse(xml)
        assertEquals(1, lines.size)
        assertEquals("A & B <x> \"q\" AB", lines[0].text)
    }

    @Test
    fun qrcRoundTripEncodeDecryptParse() {
        val original = "<Lyric_1 LyricType=\"1\" LyricContent=\"[0,1000]Ro(0,500)und(500,500)\"/>"
        val hex = QrcCipher.encryptTextToHex(original)
        assertTrue("加密结果必须是非空十六进制", hex.isNotEmpty())
        assertEquals("encode → decode → 必须逐字回到原文", original, QrcCipher.decryptHexToText(hex))

        val lines = QrcParser.parse(QrcCipher.decryptHexToText(hex))
        assertEquals(1, lines.size)
        assertEquals("Round", lines[0].text)
        assertEquals(listOf(0.0, 0.5), lines[0].words.map { it.time })
    }

    // ------------------------------------------------------------------
    // 3. KRC（酷狗）
    // ------------------------------------------------------------------

    @Test
    fun krcBase64DecodesToTheIndependentlyProducedPlaintext() {
        val plain = KrcCodec.decodeBase64(krcSyntheticBase64)
        assertNotNull("已知答案解码不能失败", plain)
        assertEquals(krcSyntheticPlain, plain)
    }

    @Test
    fun krcParsesRelativeWordOffsetsAsAbsoluteTimes() {
        val lines = KrcParser.parse(krcSyntheticPlain)
        assertEquals("KRC 的词偏移是相对行起点，必须换算成绝对秒", 2, lines.size)

        assertEquals(0.0, lines[0].time, delta)
        assertEquals("Hello world", lines[0].text)
        assertEquals(listOf(0.0, 0.2, 0.5, 0.6), lines[0].words.map { it.time })

        assertEquals(2.0, lines[1].time, delta)
        assertEquals("Second line", lines[1].text)
        assertEquals(
            "第二行的相对偏移必须叠在 2000ms 上",
            listOf(2.0, 2.5, 2.8, 3.0),
            lines[1].words.map { it.time },
        )
    }

    @Test
    fun krcRoundTripEncodeDecodeParse() {
        val original = "[0,1000]<0,400,0>Ro<400,600,0>und\r\n"
        val encoded = KrcCodec.encode(original)
        assertEquals("krc1", String(encoded.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("encode → decode → 必须逐字回到原文", original, KrcCodec.decode(encoded))

        val lines = KrcParser.parse(KrcCodec.decode(encoded))
        assertEquals(1, lines.size)
        assertEquals("Round", lines[0].text)
        assertEquals(listOf(0.0, 0.4), lines[0].words.map { it.time })
    }

    // ------------------------------------------------------------------
    // 4. 畸形输入：一律不抛，且降级成「没有逐字数据」
    // ------------------------------------------------------------------

    private val nastyStrings = listOf(
        "",
        " ",
        "\n\n\n",
        "not a lyric at all",
        "[00:01.00]plain lrc, not word level",
        "[0,",
        "[0,1000]",
        "[0,1000](",
        "[0,1000](1,2",
        "[abc,def]x(1,2)",
        "[0,1000](0,0,0)",
        "[99999999999999999999,1](1,2,3)x",
        "[]()",
        "\uFEFF[0,1000](0,100)only-bom",
        "<Lyric_1 LyricContent=",
        "<Lyric_1 LyricContent=\"unterminated",
        "<QrcInfos><LyricInfo><Lyric_1 LyricType=\"1\" LyricContent=\"\"/></LyricInfo></QrcInfos>",
    )

    @Test
    fun everyParserSurvivesMalformedInput() {
        for (raw in nastyStrings) {
            // 任何一处抛异常都会直接让这个用例失败 —— 这里不做 try/catch。
            val yrc = YrcParser.parse(raw)
            val qrc = QrcParser.parse(raw)
            val krc = KrcParser.parse(raw)
            for (lines in listOf(yrc, qrc, krc)) {
                for (line in lines) {
                    assertTrue("时间不能是 NaN/Inf", line.time.isFinite())
                    for (word in line.words) {
                        assertTrue("词时间不能是 NaN/Inf", word.time.isFinite())
                        assertTrue("词时长不能是 NaN/Inf", word.duration.isFinite())
                        assertTrue("词时长不能为负", word.duration >= 0.0)
                    }
                }
            }
        }
        assertTrue(YrcParser.parse(null).isEmpty())
        assertTrue(QrcParser.parse(null).isEmpty())
        assertTrue(KrcParser.parse(null).isEmpty())
    }

    @Test
    fun qrcCipherRejectsMalformedCiphertext() {
        assertNull(QrcCipher.decryptHexToText(null))
        assertNull(QrcCipher.decryptHexToText(""))
        assertNull("奇数长度不是合法十六进制", QrcCipher.decryptHexToText("ABC"))
        assertNull("非十六进制字符必须被拒绝", QrcCipher.decryptHexToText("ZZZZZZZZZZZZZZZZ"))
        assertNull("长度不是 8 的倍数", QrcCipher.decryptHexToText("AABBCC"))
        assertNull("能解 3DES 但解不出 zlib", QrcCipher.decryptHexToText("0000000000000000"))
        assertNull(
            "真实长度但内容随机：不能崩，只能返回 null",
            QrcCipher.decryptHexToText("01".repeat(64)),
        )
    }

    @Test
    fun krcCodecRejectsMalformedPayloads() {
        assertNull(KrcCodec.decode(null))
        assertNull(KrcCodec.decode(ByteArray(0)))
        assertNull(KrcCodec.decode("krc1".toByteArray(Charsets.US_ASCII)))
        assertNull("没有 krc1 magic", KrcCodec.decode("krc2AAAAAAAA".toByteArray(Charsets.US_ASCII)))
        assertNull(
            "magic 对但整体不是 zlib",
            KrcCodec.decode("krc1".toByteArray(Charsets.US_ASCII) + ByteArray(16)),
        )
        assertNull(KrcCodec.decodeBase64(null))
        assertNull(KrcCodec.decodeBase64(""))
        assertNull("非 base64 字符", KrcCodec.decodeBase64("!!!not base64!!!"))
        assertNull("合法的 base64 但不是 KRC", KrcCodec.decodeBase64("aGVsbG8gd29ybGQ="))
    }

    // ------------------------------------------------------------------
    // 5. 兜底契约
    // ------------------------------------------------------------------

    private val lineLevelSample: List<LyricLine> = LyricParser.parse(
        "[00:01.00]Hello\n[00:03.00]world",
        "[00:01.00]你好",
    )

    @Test
    fun fallbackKeepsLineLevelWhenWordLevelIsMissingOrJunk() {
        assertEquals(2, lineLevelSample.size)

        val empty = LyricFallback.best(lineLevelSample, emptyList())
        assertSame("没有逐字数据时必须原样返回整行结果", lineLevelSample, empty)

        for (junk in nastyStrings) {
            val wordLevel = listOf(
                YrcParser.parse(junk),
                QrcParser.parse(junk),
                KrcParser.parse(junk),
            ).flatten()
            val best = LyricFallback.best(lineLevelSample, wordLevel)
            assertEquals(
                "逐字版本是垃圾时，整行歌词一个字都不能少（junk=$junk）",
                lineLevelSample.map { it.text },
                best.map { it.text },
            )
            assertTrue(
                "退回整行时 words 必须全空（junk=$junk）",
                best.all { it.words.isEmpty() },
            )
        }
        assertEquals("第 1 行的翻译不能被兜底弄丢", "你好", empty[0].translation)
    }

    @Test
    fun fallbackUsesWordLevelAndAttachesTranslations() {
        val wordLevel = YrcParser.parse("[1000,2000](1000,200,0)He(1200,300,0)llo")
        assertEquals(1, wordLevel.size)

        val best = LyricFallback.best(lineLevelSample, wordLevel)
        assertEquals("有真逐字数据就必须用逐字的", 1, best.size)
        assertEquals(listOf("He", "llo"), best[0].words.map { it.text })
        assertEquals("整行歌词里的翻译必须按时间贴到逐字行上", "你好", best[0].translation)
        assertTrue(LyricFallback.hasWordTiming(best))
    }

    @Test
    fun fallbackTranslationsUseAToleranceButNeverAttachTheWrongLine() {
        val wordLevel = YrcParser.parse("[1000,2000](1000,200,0)He(1200,300,0)llo")

        // 差 50ms：同一行，允许贴上。
        val near = LyricFallback.best(LyricParser.parse("[00:01.05]Hello", "[00:01.05]你好"), wordLevel)
        assertEquals("你好", near[0].translation)

        // 差 1s：宁可没有翻译，也不能贴错行。
        val far = LyricFallback.best(LyricParser.parse("[00:02.00]Hello", "[00:02.00]你好"), wordLevel)
        assertNull("超出容差就不能贴", far[0].translation)
    }

    @Test
    fun fallbackIsLineLevelWhenOnlyOneWordIsTimed() {
        // 单个时间点不是逐字时间轴：解析出来只有 1 个词，必须当成整行。
        val single = QrcParser.parse("[1000,2000]whole line(1000,2000)")
        assertEquals(1, single.size)
        assertEquals(1, single[0].words.size)
        assertTrue("一个词的「逐字」没有意义", !LyricFallback.hasWordTiming(single))

        val best = LyricFallback.best(lineLevelSample, single)
        assertSame(lineLevelSample, best)
    }

    // ------------------------------------------------------------------
    // 6. 高亮纯函数
    // ------------------------------------------------------------------

    private val wordedLine = LyricLine(
        time = 1.0,
        text = "Hello world",
        words = listOf(
            LyricWord(1.0, 0.2, "He"),
            LyricWord(1.2, 0.3, "llo"),
            LyricWord(1.5, 0.1, " "),
            LyricWord(1.6, 0.4, "world"),
        ),
    )

    /**
     * 比较 0..1 的进度值。这里显式 [Float.toDouble]，是为了让 Kotlin 选中 JUnit 的
     * `assertEquals(double, double, double)` 重载 —— 否则会落到 `assertEquals(Object, Object, Object)`，
     * 变成「Double 和 Float 永不相等」的假失败。
     */
    private fun assertProgress(expected: Double, actual: Float, message: String = "progress") {
        assertEquals(message, expected, actual.toDouble(), 1e-6)
    }

    @Test
    fun activeWordIndexFollowsTheTimeline() {
        assertEquals("还没唱到第一个词", -1, LyricHighlight.activeWordIndex(wordedLine, 0.5))
        assertEquals(0, LyricHighlight.activeWordIndex(wordedLine, 1.0))
        assertEquals(0, LyricHighlight.activeWordIndex(wordedLine, 1.19))
        assertEquals(1, LyricHighlight.activeWordIndex(wordedLine, 1.2))
        assertEquals(2, LyricHighlight.activeWordIndex(wordedLine, 1.5))
        assertEquals(3, LyricHighlight.activeWordIndex(wordedLine, 1.7))
        assertEquals("唱完之后停在最后一个词", 3, LyricHighlight.activeWordIndex(wordedLine, 99.0))
        assertEquals("world", LyricHighlight.activeWord(wordedLine, 1.9)?.text)
        assertNull(LyricHighlight.activeWord(wordedLine, 0.1))
    }

    @Test
    fun sweepProgressIsCharacterWeightedWithinAWord() {
        assertProgress(0.0, LyricHighlight.sweepProgress(wordedLine, 3.0, 0.0))
        assertProgress(0.0, LyricHighlight.sweepProgress(wordedLine, 3.0, 1.0))
        // "He" 唱了一半 → 覆盖 1 个字符 / 总共 11 个字符。
        assertProgress(1.0 / 11.0, LyricHighlight.sweepProgress(wordedLine, 3.0, 1.1))
        // 进入 "llo" 的起点 → 已经完整覆盖了 "He"。
        assertProgress(2.0 / 11.0, LyricHighlight.sweepProgress(wordedLine, 3.0, 1.2))
        // 进入 "world" 的起点 → "He" + "llo" + " " 全覆盖。
        assertProgress(6.0 / 11.0, LyricHighlight.sweepProgress(wordedLine, 3.0, 1.6))
        // 最后一个词唱完 → 整行 1.0。
        assertProgress(1.0, LyricHighlight.sweepProgress(wordedLine, 3.0, 2.0))
        assertProgress(1.0, LyricHighlight.sweepProgress(wordedLine, 3.0, 10.0))
        // "world" 从 1.6s 唱到 2.0s，1.7s 正好唱了四分之一。
        assertProgress(0.25, LyricHighlight.wordProgress(wordedLine, 1.7))
    }

    @Test
    fun sweepProgressFallsBackToLineTimingWithoutWords() {
        val lineOnly = LyricLine(time = 1.0, text = "hello")
        assertProgress(0.0, LyricHighlight.sweepProgress(lineOnly, 3.0, 0.9))
        assertProgress(0.0, LyricHighlight.sweepProgress(lineOnly, 3.0, 1.0))
        assertProgress(0.5, LyricHighlight.sweepProgress(lineOnly, 3.0, 2.0))
        assertProgress(1.0, LyricHighlight.sweepProgress(lineOnly, 3.0, 3.0))
        assertProgress(1.0, LyricHighlight.sweepProgress(lineOnly, 3.0, 5.0))
        // 最后一行没有下一行时间：过了起点就算铺满，不能除零。
        assertProgress(0.0, LyricHighlight.sweepProgress(lineOnly, null, 0.5))
        assertProgress(1.0, LyricHighlight.sweepProgress(lineOnly, null, 1.0))
        assertProgress(1.0, LyricHighlight.sweepProgress(lineOnly, 0.5, 2.0))
    }

    @Test
    fun sweepProgressHandlesZeroDurationWordsWithoutDividingByZero() {
        val zeroDuration = LyricLine(
            time = 0.0,
            text = "ab",
            words = listOf(LyricWord(0.0, 0.0, "a"), LyricWord(0.5, 0.0, "b")),
        )
        assertProgress(0.0, LyricHighlight.sweepProgress(zeroDuration, null, 0.0))
        // 零时长的词一「开始」就等于唱完（用下一个词的起点兜底，不会除零）。
        assertProgress(1.0, LyricHighlight.sweepProgress(zeroDuration, null, 0.5))
        assertProgress(1.0, LyricHighlight.sweepProgress(zeroDuration, null, 1.0))
        // 空文本的词不能把覆盖率算成 NaN。
        val blankWords = LyricLine(
            time = 0.0,
            text = "",
            words = listOf(LyricWord(0.0, 1.0, ""), LyricWord(1.0, 1.0, "")),
        )
        assertProgress(0.0, LyricHighlight.sweepProgress(blankWords, 2.0, 0.0))
        assertProgress(0.5, LyricHighlight.sweepProgress(blankWords, 2.0, 1.0))
        assertProgress(1.0, LyricHighlight.sweepProgress(blankWords, 2.0, 2.0))
    }

    @Test
    fun sweepProgressByListAutoPicksTheNextLine() {
        val lines = listOf(
            LyricLine(time = 0.0, text = "a"),
            LyricLine(time = 2.0, text = "b"),
            LyricLine(time = 4.0, text = "c"),
        )
        assertProgress(0.0, LyricHighlight.sweepProgress(lines, -1, 1.0))
        assertProgress(0.5, LyricHighlight.sweepProgress(lines, 0, 1.0))
        assertProgress(0.75, LyricHighlight.sweepProgress(lines, 1, 3.5))
        assertProgress(0.0, LyricHighlight.sweepProgress(lines, 9, 3.5))
    }

    // ------------------------------------------------------------------
    // 7. 既有整行行为不能变
    // ------------------------------------------------------------------

    @Test
    fun lineLevelParserIsUnchangedAndProducesEmptyWords() {
        // 与 LaunchSmokeTest 里那条断言同源，这里额外钉住「新增字段的默认值是空列表」。
        val lines = LyricParser.parse("[00:01.50]hello\n[00:03.00]world", "[00:01.50]你好")
        assertEquals(2, lines.size)
        assertEquals("hello", lines[0].text)
        assertEquals("你好", lines[0].translation)
        assertTrue(lines.all { it.words.isEmpty() })

        // 位置 / 具名构造与相等语义都不受新字段影响（id 有随机默认值，比较时显式对齐）。
        assertEquals(
            LyricLine(id = "same", time = 1.5, text = "hello"),
            LyricLine(id = "same", time = 1.5, text = "hello"),
        )
        assertEquals(
            LyricLine(id = "same", time = 1.5, text = "hello"),
            LyricLine(id = "same", time = 1.5, text = "hello", translation = null, words = emptyList()),
        )
        assertEquals(emptyList<LyricWord>(), LyricLine(time = 0.0, text = "x").words)
        assertNotNull(LyricLine(time = 0.0, text = "x").id)
    }
}

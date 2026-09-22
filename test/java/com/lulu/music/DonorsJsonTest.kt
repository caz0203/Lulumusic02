package com.lulu.music

import com.lulu.music.data.donors.Donor
import com.lulu.music.data.donors.DonorsManifest
import com.lulu.music.data.donors.MAX_DONORS
import com.lulu.music.data.donors.parseDonorsManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `donors.json` 的解析测试（纯 JVM，不需要 Robolectric：解析只用 kotlinx.serialization）。
 *
 * 这里钉住的是「宽容但不撒谎」的取舍：
 *  - 读不懂的 JSON 一律 [DonorsManifest.Invalid]（让 store 保留旧缓存）；
 *  - 空数组合法（维护者真的清空了名单）；
 *  - 名字空白 / 金额不是数字 / 金额为负 → 整条丢弃（榜单是按金额排名的，编一个 0 元是撒谎）；
 *  - 同名合并（金额相加）、金额降序、并列按名字升序（顺序必须确定）。
 */
class DonorsJsonTest {

    private fun parsed(raw: String): List<Donor> {
        val manifest = parseDonorsManifest(raw)
        assertTrue("这段清单应该被识别：$raw", manifest is DonorsManifest.Parsed)
        return (manifest as DonorsManifest.Parsed).donors
    }

    // ------------------------------------------------------------------
    // 形状与排序
    // ------------------------------------------------------------------

    @Test
    fun parsesTheDocumentedShapeAndSortsByAmountDescending() {
        val donors = parsed(
            """
            {
              "donors": [
                { "name": "甲", "amount": 10 },
                { "name": "丙", "amount": 50 },
                { "name": "乙", "amount": 20 }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("丙", "乙", "甲"), donors.map { it.name })
        assertEquals(listOf(50.0, 20.0, 10.0), donors.map { it.amount })
    }

    @Test
    fun acceptsABareArrayAsWell() {
        val donors = parsed("""[ { "name": "甲", "amount": 10 } ]""")
        assertEquals(1, donors.size)
        assertEquals("甲", donors[0].name)
        assertEquals(10.0, donors[0].amount, 0.0)
    }

    @Test
    fun ignoresUnknownTopLevelKeys() {
        val donors = parsed("""{ "updatedAt": "2025-01-01", "donors": [ { "name": "甲", "amount": 1 } ] }""")
        assertEquals(listOf("甲"), donors.map { it.name })
    }

    @Test
    fun keepsOptionalMessageAndDateAndDefaultsThemToEmptyStrings() {
        val donors = parsed(
            """
            [
              { "name": "有留言", "amount": 30, "message": "加油", "date": "2025-01-12" },
              { "name": "没留言", "amount": 20 }
            ]
            """.trimIndent(),
        )

        assertEquals("加油", donors[0].message)
        assertEquals("2025-01-12", donors[0].date)
        assertEquals("", donors[1].message)
        assertEquals("", donors[1].date)
    }

    @Test
    fun equalAmountsAreOrderedByNameSoTheRankingIsDeterministic() {
        val donors = parsed(
            """
            [
              { "name": "丙", "amount": 20 },
              { "name": "甲", "amount": 20 },
              { "name": "乙", "amount": 20 }
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("丙", "乙", "甲"), donors.map { it.name }.sorted())
        assertEquals(donors.map { it.name }, parsed(
            """
            [
              { "name": "乙", "amount": 20 },
              { "name": "丙", "amount": 20 },
              { "name": "甲", "amount": 20 }
            ]
            """.trimIndent(),
        ).map { it.name })
    }

    // ------------------------------------------------------------------
    // 金额：数字 / 货币字符串 / 坏数据
    // ------------------------------------------------------------------

    @Test
    fun parsesAmountsWrittenAsCurrencyStrings() {
        val donors = parsed(
            """
            [
              { "name": "带符号", "amount": "￥1,000 元" },
              { "name": "小数", "amount": "20.5" },
              { "name": "数字", "amount": 7 }
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("带符号", "小数", "数字"), donors.map { it.name })
        assertEquals(1000.0, donors[0].amount, 0.0001)
        assertEquals(20.5, donors[1].amount, 0.0001)
        assertEquals(7.0, donors[2].amount, 0.0001)
    }

    @Test
    fun dropsEntriesWithBlankName() {
        val donors = parsed(
            """
            [
              { "name": "", "amount": 10 },
              { "name": "   ", "amount": 20 },
              { "amount": 30 },
              { "name": "有效", "amount": 1 }
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("有效"), donors.map { it.name })
    }

    @Test
    fun dropsEntriesWhoseAmountIsMissingOrNotANumber() {
        val donors = parsed(
            """
            [
              { "name": "没金额" },
              { "name": "空金额", "amount": "" },
              { "name": "文字金额", "amount": "待定" },
              { "name": "布尔金额", "amount": true },
              { "name": "null 金额", "amount": null },
              { "name": "负金额", "amount": -50 },
              { "name": "有效", "amount": 1 }
            ]
            """.trimIndent(),
        )
        assertEquals("只有金额能读成非负数的条目才该留下", listOf("有效"), donors.map { it.name })
        assertEquals(1, donors.size)
    }

    // ------------------------------------------------------------------
    // 重名
    // ------------------------------------------------------------------

    @Test
    fun mergesDuplicateNamesBySummingAmounts() {
        val donors = parsed(
            """
            [
              { "name": "甲", "amount": 10, "message": "第一次", "date": "2025-01-01" },
              { "name": "  甲  ", "amount": 5 },
              { "name": "乙", "amount": 12 }
            ]
            """.trimIndent(),
        )

        assertEquals("重名必须合并成一条", 2, donors.size)
        val first = donors.first { it.name == "甲" }
        assertEquals("金额必须相加", 15.0, first.amount, 0.0001)
        assertEquals("留言取第一条非空的", "第一次", first.message)
        assertEquals("日期取第一条非空的", "2025-01-01", first.date)
        // 合并后 15 排在 12 前面 —— 排名用的是合并后的金额。
        assertEquals(listOf("甲", "乙"), donors.map { it.name })
    }

    // ------------------------------------------------------------------
    // 坏数据 / 边界
    // ------------------------------------------------------------------

    @Test
    fun malformedJsonIsInvalidAndNeverThrows() {
        listOf(
            "",
            "   ",
            "{not json",
            "[{",
            "此文件还没写好",
            "null",
            "42",
            "\"donors\"",
            """{ "list": [] }""",
            """{ "donors": 3 }""",
        ).forEach { raw ->
            assertTrue("这段文本必须判为 Invalid：$raw", parseDonorsManifest(raw) is DonorsManifest.Invalid)
        }
    }

    @Test
    fun anEmptyButValidListIsParsedAsEmptyRatherThanInvalid() {
        val manifest = parseDonorsManifest("""{ "donors": [] }""")
        assertEquals(DonorsManifest.Parsed(emptyList()), manifest)
        assertEquals(DonorsManifest.Parsed(emptyList()), parseDonorsManifest("[]"))
    }

    @Test
    fun toleratesAByteOrderMarkAndSurroundingWhitespace() {
        val donors = parsed("\uFEFF\n  [ { \"name\": \"甲\", \"amount\": 3 } ]\n")
        assertEquals(listOf("甲"), donors.map { it.name })
    }

    @Test
    fun entriesThatAreNotObjectsAreSkipped() {
        val donors = parsed("""[ 1, "甲", null, { "name": "乙", "amount": 9 } ]""")
        assertEquals(listOf("乙"), donors.map { it.name })
    }

    @Test
    fun keepsAtMostMaxDonorsAndKeepsTheHighestOnes() {
        val entries = (1..(MAX_DONORS + 20)).joinToString(",") { index ->
            """{ "name": "第${index}名", "amount": $index }"""
        }
        val donors = parsed("[ $entries ]")

        assertEquals(MAX_DONORS, donors.size)
        assertEquals("留下的必须是金额最高的那批", (MAX_DONORS + 20).toDouble(), donors[0].amount, 0.0)
        assertEquals("被裁掉的必须是最低的那些", 21.0, donors.last().amount, 0.0)
    }
}

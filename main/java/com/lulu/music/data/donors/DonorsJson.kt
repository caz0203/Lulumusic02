package com.lulu.music.data.donors

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * 榜单里的一个人。
 *
 * [amount] 用 [Double]（元），只有 [name] 与 [amount] 是必需的；[message] / [date] 缺失时是空串，
 * 界面据此决定要不要多画一行。落盘走 kotlinx.serialization（与 `data/store/Prefs.kt` 同一套）。
 */
@Serializable
data class Donor(
    val name: String,
    val amount: Double,
    val message: String = "",
    val date: String = "",
)

/** 榜单最多保留这么多条（按金额取前 N 名），防止一份写坏的清单把界面撑爆。 */
const val MAX_DONORS: Int = 100

/**
 * `donors.json` 的解析结果。
 *
 * 区分「解析成功但榜单是空的」与「根本没读懂」非常重要：[DonorsStore] 只在
 * [Parsed] 时更新缓存 —— 断网 / 半截 JSON 绝不能把已经拿到的好名单清掉。
 */
sealed interface DonorsManifest {

    /** JSON 结构可识别（空数组也算，代表「维护者确实把名单清空了」）。 */
    data class Parsed(val donors: List<Donor>) : DonorsManifest

    /** 结构不可识别：语法错误、空文本，或顶层既不是数组也不是含 `donors` 数组的对象。 */
    data object Invalid : DonorsManifest
}

private val donorsJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

/**
 * 解析远程 / 缓存的榜单 JSON。**绝不抛异常**。
 *
 * 接受的两种形状（都是这一段实现，没有第二份解析）：
 * ```json
 * { "donors": [ { "name": "热心网友小A", "amount": 50, "message": "加油", "date": "2025-01-01" } ] }
 * [ { "name": "热心网友小A", "amount": 50 } ]
 * ```
 *
 * 宽容规则（每条都有单测）：
 *  - 金额可以是数字，也可以是 `"50"` / `"￥50"` / `"1,000 元"` 这类字符串（只取数字、小数点与符号）；
 *  - 名字为空白、或金额缺失 / 不是数字的条目**整条丢弃** —— 榜单是按金额排名的，没有金额的条目
 *    既排不了名，也不该被显示成「¥ 0.00」；
 *  - 金额为负视为写错，同样丢弃；
 *  - 同名的重复条目**合并为一条，金额相加**（同一个人赞助多次就是两次之和）；
 *  - 结果按金额**降序**排列；金额相同时按名字升序，保证顺序确定；
 *  - 最多保留 [MAX_DONORS] 条。
 */
fun parseDonorsManifest(raw: String?): DonorsManifest {
    val text = raw?.trim()?.removePrefix("\uFEFF").orEmpty()
    if (text.isEmpty()) return DonorsManifest.Invalid

    val root = runCatching { donorsJson.parseToJsonElement(text) }.getOrNull()
        ?: return DonorsManifest.Invalid

    val array: JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> root["donors"] as? JsonArray ?: return DonorsManifest.Invalid
        else -> return DonorsManifest.Invalid
    }

    // 同名合并：金额相加；留言 / 日期取第一个非空的（同一个人重复出现时不该丢掉说明文字）。
    val merged = LinkedHashMap<String, Donor>()
    array.forEach { element ->
        val donor = donorOf(element as? JsonObject ?: return@forEach) ?: return@forEach
        val existing = merged[donor.name]
        merged[donor.name] = if (existing == null) {
            donor
        } else {
            Donor(
                name = donor.name,
                amount = existing.amount + donor.amount,
                message = existing.message.ifBlank { donor.message },
                date = existing.date.ifBlank { donor.date },
            )
        }
    }

    val ranked = merged.values
        .sortedWith(compareByDescending<Donor> { it.amount }.thenBy { it.name })
        .take(MAX_DONORS)
    return DonorsManifest.Parsed(ranked)
}

/** 一个 JSON 元素 → [Donor]；任何一项不合法都返回 null（调用方直接跳过这一条）。 */
private fun donorOf(obj: JsonObject): Donor? {
    val name = obj.stringField("name").trim()
    if (name.isEmpty()) return null
    val amount = obj.amountField() ?: return null
    if (amount < 0.0) return null
    return Donor(
        name = name,
        amount = amount,
        message = obj.stringField("message").trim(),
        date = obj.stringField("date").trim(),
    )
}

/** 读一个字符串字段；缺失、`null`、或不是标量时返回空串（绝不抛）。 */
private fun JsonObject.stringField(key: String): String {
    val primitive = this[key] as? JsonPrimitive ?: return ""
    if (primitive is JsonNull) return ""
    return primitive.content
}

/**
 * 读金额：先按数字试，再按「去掉货币符号与千分位」的字符串试。
 * 例：`50` / `"50"` / `"￥50.00"` / `"1,000 元"` 都能读成 50 / 50 / 50.0 / 1000.0；
 * `"待定"` / `true` / 缺失 → null。
 */
private fun JsonObject.amountField(): Double? {
    val primitive = this["amount"] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    primitive.doubleOrNull?.let { return it }
    val cleaned = primitive.content.trim()
        .filter { it.isDigit() || it == '.' || it == '-' || it == '+' }
    return cleaned.toDoubleOrNull()
}

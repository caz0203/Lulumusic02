package com.lulu.music.data.prefs

/**
 * LuluMusic 的运营信息配置。
 *
 * 「我的」页面里的**交流群**与**自愿赞助**两个模块的全部展示内容都集中在这里。
 * 后续补充或修改信息时，只需要改这一个文件（或把图片放进 `res/drawable/` 再填资源名），
 * 不需要改任何界面代码。
 */
object LuluLinks {

    // ---------------------------------------------------------------------
    // 交流群
    // ---------------------------------------------------------------------

    /** 群名称，显示在面板标题处。 */
    const val COMMUNITY_NAME: String = "LuluMusic"

    /** 群链接（QQ 群 / Telegram / 微信群邀请链接均可）。 */
    const val COMMUNITY_URL: String = "https://qm.qq.com/q/uAvQyjOG3Y"

    /**
     * 交流群二维码图片。
     *
     * 目前留空：因为已经填了 [COMMUNITY_URL]，界面会用 ZXing **本地生成**二维码，
     * 所以这里不需要图片。
     *
     * 如果你更想用自己的二维码图片：把图片放到 `app/src/main/res/drawable/`
     * （例如 `community_qr.png`），然后这里填**不带扩展名**的资源名 → `"community_qr"`。
     * 填写后优先使用图片。
     */
    const val COMMUNITY_QR: String = ""

    /** 二维码下方的说明文字。留空使用默认文案。 */
    const val COMMUNITY_NOTE: String = ""

    // ---------------------------------------------------------------------
    // 自愿赞助
    // ---------------------------------------------------------------------

    /**
     * 赞助收款二维码图片（微信 / 支付宝均可）。
     * 对应 `res/drawable/donation_qr.png`。
     */
    const val DONATION_QR: String = "donation_qr"

    /**
     * 微信支付跳转链接（可选，形如 `wxp://...`）。
     * 留空时面板里不显示「打开微信」按钮 —— 扫码即可，无需跳转。
     */
    const val WECHAT_PAY_URL: String = ""

    /** 赞助说明文字。留空使用默认文案。 */
    const val DONATION_NOTE: String = ""

    /**
     * 赞助人员榜单。可留空（默认就是空的）。
     * 有数据时按金额从高到低展示。
     */
    val DONORS: List<Donor> = emptyList()

    data class Donor(val name: String, val amount: Double)

    // ---------------------------------------------------------------------
    // 派生状态（界面据此决定显示什么）
    // ---------------------------------------------------------------------

    /** 交流群是否有可展示的信息（链接或二维码图片）。 */
    val hasCommunityInfo: Boolean
        get() = COMMUNITY_URL.isNotBlank() || COMMUNITY_QR.isNotBlank()

    /** 赞助是否有可展示的信息。 */
    val hasDonationInfo: Boolean
        get() = DONATION_QR.isNotBlank() ||
            WECHAT_PAY_URL.isNotBlank() ||
            DONORS.isNotEmpty()

    /** 榜单按金额降序。 */
    val donorsRanked: List<Donor>
        get() = DONORS.sortedByDescending { it.amount }
}

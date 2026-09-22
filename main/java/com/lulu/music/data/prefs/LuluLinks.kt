package com.lulu.music.data.prefs

/**
 * LuluMusic 的运营信息配置。
 *
 * 「我的」页面里的**交流群**与**自愿赞助**两个模块的展示内容集中在这里
 * （收款码 / 群链接 / 说明文字）。
 *
 * 唯一例外是**赞助人员榜单**：它是异步同步来的数据，不在这个文件里，见
 * `data/donors/DonorsStore.kt`（远程 `donors.json` + 本地缓存）。
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

    // ---------------------------------------------------------------------
    // 赞助人员榜单
    // ---------------------------------------------------------------------
    //
    // 榜单**不在这个文件里**：它来自远程 `donors.json`，由 `data/donors/DonorsStore.kt`
    // 拉取 + 落缓存，界面直接订阅 `DonorsStore.donors`。
    //
    // 这里以前有一个 `val DONORS: List<Donor> = emptyList()`（以及 data class Donor 和
    // donorsRanked）。它一直是**空的**，作为「离线兜底」没有任何价值，反而和远程名单构成
    // 两个真相来源（界面看它、用户改仓库文件）—— 所以整块删掉，缓存才是唯一的离线兜底。

    // ---------------------------------------------------------------------
    // 派生状态（界面据此决定显示什么）
    // ---------------------------------------------------------------------

    /** 交流群是否有可展示的信息（链接或二维码图片）。 */
    val hasCommunityInfo: Boolean
        get() = COMMUNITY_URL.isNotBlank() || COMMUNITY_QR.isNotBlank()

    /**
     * 赞助是否有可展示的信息（收款码 / 微信跳转）。
     *
     * 不含赞助人员榜单：名单是异步同步来的，用它决定「这个模块要不要显示」会让卡片
     * 在同步完成前后忽隐忽现。
     */
    val hasDonationInfo: Boolean
        get() = DONATION_QR.isNotBlank() || WECHAT_PAY_URL.isNotBlank()
}

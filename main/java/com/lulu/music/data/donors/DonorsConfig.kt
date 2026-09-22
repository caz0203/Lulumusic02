package com.lulu.music.data.donors

/**
 * 赞助名单（`donors.json`）的**唯一**配置入口 —— 与检查更新的 [com.lulu.music.data.update.UpdateConfig]
 * 同一套做法：只要把 [MANIFEST_URL] 指向线上清单，应用启动后就会自动把名单同步到本地缓存。
 *
 * 与检查更新的区别：清单地址写死在代码里（不需要用户配置），而且**拉取失败只保留旧缓存**，
 * 不会给用户任何错误提示 —— 赞助名单是「有就显示」的信息，不该因为它没网就打扰用户。
 */
object DonorsConfig {

    /**
     * 赞助名单地址。留空 = 关闭自动同步（启动时不做任何网络请求）。
     * 当前指向本仓库根目录的 `donors.json`（raw 地址），**更新名单只需改仓库里的
     * `donors.json`**，不需要重新编译 App。
     */
    const val MANIFEST_URL: String =
        "https://raw.githubusercontent.com/caz0203/Lulumusic02/main/donors.json"

    /** 网络超时（毫秒）；启动时的后台同步不应该拖慢启动。 */
    const val TIMEOUT_MS: Long = 6000
}

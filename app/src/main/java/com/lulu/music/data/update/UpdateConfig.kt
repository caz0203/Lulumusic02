package com.lulu.music.data.update

/**
 * 检查更新（Android）的**唯一**配置入口。
 *
 * 这是维护者需要改动的唯一一个文件：只要把 [MANIFEST_URL] 填成线上清单地址，
 * 应用启动后就会自动比对版本并在有新版本时弹窗提示。
 *
 * 与 iOS 版的区别：iOS 的「检查更新」会直接下载 IPA；Android 这里只负责
 * 把用户引导到新 APK 的下载地址（浏览器 / 下载器接管）。
 */
object UpdateConfig {

    /**
     * 版本清单地址。留空 = 关闭更新检查（启动时不做任何网络请求）。
     * 可以放在任意静态托管上（GitHub raw / Gist / 自己的服务器）。
     * 期望返回 JSON：
     * {
     *   "versionCode": 40,
     *   "versionName": "1.6.7",
     *   "notes": "更新说明，可多行",
     *   "url": "https://.../LuluMusic-1.6.7.apk",
     *   "force": false
     * }
     */
    const val MANIFEST_URL: String = "https://raw.githubusercontent.com/caz0203/Lulumusic02/main/version.json"

    /** 网络超时（毫秒）；检查更新不应该拖慢启动。 */
    const val TIMEOUT_MS: Long = 6000
}

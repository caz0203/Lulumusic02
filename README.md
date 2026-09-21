# LuluMusic — Android

An Android (Kotlin + Jetpack Compose) music client, ported from the Beans Music iOS app and
rebranded as **LuluMusic**.

- **应用名 / App label:** `LuluMusic`
- **包名 / Package:** `com.lulu.music`
- **版本 / Version:** 1.6.7 (versionCode 40)
- **签名 / Signing:** self-signed, `keystore/lulu.jks`

## Features

- **网易云音乐 / QQ 音乐 / 酷狗音乐** browsing and search
- **账号登录** with synced playlists and favourites
- **播放** with queue, shuffle, repeat, playback speed, and a sleep timer
- **后台播放 + 锁屏控制** via a Media3 `MediaSessionService`
- **歌词**（含翻译）with a scrolling, seek-by-tap lyric sheet
- **均衡器**（均衡器 / 重低音 / 虚拟环绕）
- **主题与壁纸**（9 套配色、深浅色、液态玻璃 / 简洁两种风格）
- **下载与离线播放**；**本机音频**（MediaStore 扫描，可直接播放）
- **第三方音源**：导入 / 管理 / 启用第三方解锁音源（JSON、LX User API、JS 脚本）
- **播放来源**：自动 / 官方 / 第三方 三档切换
- **应用内更新检查**：启动时联网比对版本并弹窗提示
- 中英双语

### 「我的」页面模块

账号卡片 → 已下载 → **交流群** → **自愿赞助** → 版本号页脚。

> 交流群与自愿赞助的展示内容集中在 `data/prefs/LuluLinks.kt`。

## Architecture

```
app/src/main/java/com/lulu/music/
  BeansApplication.kt        Application entry; CrashLog + settings + stores + playback
  MainActivity.kt            Single-activity host
  data/
    model/                   Song / Playlist / LyricLine + LRC parser
    net/                     OkHttp facade (Http) + NetEase request crypto
    api/                     NetEaseApi, QQMusicApi, KugouMusicApi
    auth/                    NetEase / QQ / Kugou account stores
    prefs/                   SettingsStore (DataStore) + LuluLinks (community/donation config)
    store/                   Favourites, history, downloads, CrashLog
    source/                  第三方音源: model, store, import parser, resolver, LX JS runner
    update/                  UpdateConfig + UpdateChecker (in-app update check)
  playback/
    BeansPlayerService.kt    Media3 MediaSessionService (foreground playback, lock screen)
    PlaybackController.kt    Queue/transport facade exposed to Compose
    MediaResolution.kt       Lazy `beans://song` → real stream URL (honours 播放来源)
    DownloadManager.kt       Offline downloads
    EqualizerController.kt   AudioEffect equalizer / bass boost / virtualizer
  ui/
    BeansApp.kt              NavHost, tab shell, navigator, update dialog
    MiniPlayerBar.kt         Docked mini player
    theme/ components/       Colour system + shared glass design system
    screens/                 Discover / Search / Library / Profile / Player / Settings /
                             Login / Downloads / PlaylistDetail / ThirdPartySource / ProfileExtras
```

### Notable design decisions

- **Lazy stream resolution.** Songs are queued as `beans://song?src=…&id=…` placeholder URIs; the
  real CDN URL is resolved by a Media3 `ResolvingDataSource` only when playback is about to start.
  A downloaded file wins; otherwise the 「播放来源」 setting decides official vs third-party.
- **64-bit ids.** NetEase ids exceed `Int` range for some tracks, so every platform id is `Long`.
- **`SettingsStore` flows are `by lazy`.** The DataStore instance only exists after
  `Application.onCreate`; eager `stateIn` initialisers would throw `ExceptionInInitializerError`.
- **JS 音源** runs in a lazily-created headless `WebView` (V8) with a synchronous
  `@JavascriptInterface` bridge — the closest Android equivalent of iOS's JavaScriptCore, and no
  extra dependency. The WebView is only built when a script source is actually resolved.
- **Large text never enters saved-instance state.** The 音源 import fields use `remember`, not
  `rememberSaveable`: an LX script is routinely hundreds of KB and would blow the Binder limit
  (`TransactionTooLargeException`). Parsing + persistence also run on `Dispatchers.IO`.
- **Icons** are generated from a single square source image by `tools/IconGen.java` (JDK `ImageIO`).

## Building

Requirements: JDK 17, Android SDK (platform 35, build-tools 35.0.0).

```powershell
. D:\xsbofang\tools\env.ps1
& D:\xsbofang\tools\gradle\bin\gradle.bat -p D:\xsbofang\android assembleRelease
```

Outputs land in `app/build/outputs/apk/{debug,release}/`. APKs are self-signed, so the release
build installs directly on any device. **Replace `keystore/lulu.jks` before distributing.**

CI: `.github/workflows/build-android-apk.yml` builds the release APK on `ubuntu-latest`.

## 应用内更新检查

实现在 `data/update/`。首次启动时（仅在联网且已配置时）拉取一份版本清单，若远端
`versionCode` 高于本机，就弹出更新对话框（`立即更新` 打开下载链接 / `以后再说` 忽略该版本；
`force: true` 时只保留「立即更新」）。

**启用步骤**：把下面这份 JSON 放到任意静态托管上（GitHub Release 附件、Gist raw、
自己的服务器都可以），然后把它填进 `data/update/UpdateConfig.kt`：

```kotlin
object UpdateConfig {
    const val MANIFEST_URL: String = "https://你的地址/version.json"
    const val TIMEOUT_MS: Long = 6000
}
```

清单格式（除 `versionCode` 和 `url` 外均可省略）：

```json
{
  "versionCode": 41,
  "versionName": "1.6.8",
  "notes": "更新说明，可以多行。",
  "url": "https://你的地址/LuluMusic-1.6.8.apk",
  "force": false
}
```

留空 `MANIFEST_URL` = 完全关闭更新检查（默认值，不会发起任何网络请求）。
「以后再说」忽略的版本号会持久化，同一版本不再重复弹窗。

## 崩溃日志

`data/store/CrashLog.kt` 在 `Application.onCreate` 第一行安装全局未捕获异常处理器，把堆栈
按时间追加到 `filesDir/crash.log`（保留最近 8 条 / 128 KB），并委托给原有处理器。

查看方式：**设置 → 平台显示 分组末尾 → 崩溃日志**，可直接查看 / 复制 / 清空。
也可以用 adb：`adb shell run-as com.lulu.music cat files/crash.log`。

## 来源与声明 / Attribution & disclaimer

本项目是 [Beans Music](https://github.com/XIaodou0416/Beans-Music)（iOS / SwiftUI，MIT 许可）
的 **Android 移植版**，已重命名为 LuluMusic。**原项目版权归 XIaodou0416 所有**，本项目遵循
MIT 许可（见 `LICENSE`，其中保留了原始版权声明）。

- 本仓库**不包含**任何音乐内容、账号凭据、Cookie 或 API 密钥。
- 各音乐平台的接口为**逆向所得**，其版权与商标归各自所有者；请遵守各平台的服务条款。
- 第三方音源由**用户自行导入**，本仓库不分发、不内置任何音源脚本或解锁服务。
- 仅供学习交流使用，请勿用于商业用途。

## Known gaps

- The iOS player exposes a very large set of lyric *appearance* options (glow, tilt, blur,
  gradient, scale, anchor, per-preset styling). This port implements the core lyric behaviour:
  parsing, translation, current-line highlight, auto-centre scrolling and tap-to-seek, plus font
  size / alignment / offset.
- Backdrop blur (`.ultraThinMaterial`) has no Compose equivalent; glass surfaces are approximated
  with a translucent fill, sheen and hairline border.
- 第三方音源编辑弹层未包含 iOS 的「请求头」多行编辑与「适用平台」分段（已有 `headers` 原样保留）。
- 音质选择器没有 `192k`（`ThirdPartyAudioQuality` 无该成员）。
- Internal class names still use the `Beans` prefix (`BeansTheme`, `BeansApplication`, …) and
  preference keys still use `beans.*`; the LX JS bridge globals use `__beans*`. These are
  implementation details and are not user-visible.

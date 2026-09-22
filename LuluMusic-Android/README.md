# LuluMusic

> 希望大家都可以免费听三个平台的歌，不用专门为了听一个人的歌跑去专门下一个软件、开一个会员。

本仓库包含 **LuluMusic 的 Android 客户端**（Kotlin + Jetpack Compose），以及它的来源
**Beans Music iOS 客户端**（SwiftUI）。

- **Android 应用名 / label：** `LuluMusic`
- **包名 / package：** `com.lulu.music`
- **当前版本 / version：** 1.6.9（versionCode 42）
- **许可 / license：** MIT

---

## 这是什么

一个三平台（网易云音乐 / QQ 音乐 / 酷狗音乐）第三方音乐客户端，支持搜索与浏览、账号登录与歌单同步、
后台播放与锁屏控制、歌词（含翻译）、均衡器、主题壁纸、下载与离线播放，以及**第三方音源导入**。

### 功能一览（Android）

| 模块 | 说明 |
|---|---|
| 三平台 | 网易云 / QQ 音乐 / 酷狗 的搜索、歌单、排行榜、歌手、专辑 |
| 账号 | 三平台登录（扫码 / Cookie / 网页），歌单同步与收藏 |
| 播放 | 队列、随机、循环、倍速、定时关闭、后台播放、锁屏控制 |
| 歌词 | LRC 解析、翻译、当前行高亮、自动居中滚动、点击跳转 |
| 音效 | 均衡器、重低音、虚拟环绕 |
| 外观 | 9 套配色、深浅色、液态玻璃 / 简洁两种风格、自定义壁纸 |
| 第三方音源 | 导入 / 管理 / 启用解锁音源（JSON、LX User API、JS 脚本） |
| 播放来源 | 自动 / 官方 / 第三方 三档切换 |
| 离线 | 下载与离线播放；本机音频扫描播放 |
| 其他 | 应用内更新检查、崩溃日志查看、中英双语 |

### 「我的」页面

账号卡片 → 已下载 → 交流群 → 自愿赞助 → 版本号页脚。

> 交流群与自愿赞助的展示内容集中在
> `app/src/main/java/com/lulu/music/data/prefs/LuluLinks.kt`。

---

## 目录结构

当前仓库把两端源码都放在根目录：

```
Beans/                   iOS 客户端源码（SwiftUI）
project.yml              iOS 工程定义（XcodeGen）
docs/  ci-logs/          iOS 文档与构建日志
CHANGELOG.md  FEATURES.md  说明.md      iOS 文档

app/                     Android 模块
build.gradle.kts         Android 根构建脚本
settings.gradle.kts
gradle.properties
keystore/                Android 签名密钥（个人侧载用）
version.json             ★ 应用内更新清单（见下文）
LICENSE
README.md                本文件
```

> **建议（可选）**：更清爽的做法是拆成 `android/` 与 `ios/` 两个子目录。
> 这需要删除并重新上传约 200 个文件，所以仓库目前保持扁平结构——
> Gradle 只依赖根目录的 `app/`、`build.gradle.kts`、`settings.gradle.kts`，扁平结构可以正常构建。

---

## 构建 Android

需要 JDK 17 + Android SDK（platform 35、build-tools 35.0.0）。

```bash
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 已签名发布包
```

产物在 `app/build/outputs/apk/{debug,release}/`。

APK 是自签名的，**可直接安装到任何设备**（Android 没有 Apple 那种签名限制）。
发布前请替换 `keystore/lulu.jks`（当前密码写在 `app/build.gradle.kts` 里，仅方便个人使用）。

CI：`.github/workflows/build-android-apk.yml` 会在 `ubuntu-latest` 上构建发布包并上传为产物。

---

## 应用内更新检查

App 启动时会（仅在已配置且联网时）拉取一份版本清单，若远端 `versionCode` 高于本机就弹窗提示更新。

清单地址写在
`app/src/main/java/com/lulu/music/data/update/UpdateConfig.kt` 的 `MANIFEST_URL`，
当前指向本仓库的 `version.json`：

```
https://raw.githubusercontent.com/caz0203/Lulumusic02/main/version.json
```

**发布新版本时只需要改 `version.json` 这一个文件，不用重新编译 App：**

```json
{
  "versionCode": 43,
  "versionName": "1.7.0",
  "notes": "更新说明，可多行。",
  "url": "https://github.com/caz0203/Lulumusic02/releases/download/v1.7.0/LuluMusic-1.7.0.apk",
  "force": false
}
```

- `versionCode` 必须**大于**已发布版本才会提示。
- `url` 指向新 APK；用 GitHub Release 附件时，文件名要和地址最后一段一致。
- `force: true` 时弹窗只保留「立即更新」，用户无法跳过。
- 用户点「以后再说」会记住该版本，不再重复打扰。

> ⚠️ **1.6.8 及更早的版本不会弹更新窗**——它们的 `MANIFEST_URL` 是空的，代码里没有联网检查。
> **1.6.9 是第一个支持自我更新的版本。**

---

## 崩溃日志

`data/store/CrashLog.kt` 在 `Application.onCreate` 第一行安装全局未捕获异常处理器，
把堆栈按时间追加到应用私有目录的 `crash.log`（保留最近若干条）。

查看方式：**设置 → 平台显示 → 崩溃日志**（可查看 / 复制 / 清空）。
也可以用 adb：`adb shell run-as com.lulu.music cat files/crash.log`

---

## 来源与声明

本项目是 [Beans Music](https://github.com/XIaodou0416/Beans-Music)（iOS / SwiftUI，MIT 许可）
的 **Android 移植版**。**原项目版权归 XIaodou0416 所有**，本项目遵循 MIT 许可
（见 `LICENSE`，其中保留了原始版权声明）。

- 本仓库**不包含**任何音乐内容、账号凭据、Cookie 或 API 密钥。
- 各音乐平台的接口为**逆向所得**，其版权与商标归各自所有者；请遵守各平台的服务条款。
- 第三方音源由**用户自行导入**，本仓库不分发、不内置任何音源脚本或解锁服务。
- 仅供学习交流使用，请勿用于商业用途。

---

## 已知差距

- iOS 播放器有大量歌词**外观**选项（辉光、倾斜、模糊、渐变、缩放、锚点、预设）；
  Android 版只实现了核心歌词行为：解析、翻译、当前行高亮、自动居中、点击跳转，
  以及字号 / 对齐 / 偏移。
- iOS 的 `.ultraThinMaterial` 背景模糊在 Compose 里没有对应能力，玻璃质感用半透明填充 + 高光近似。
- 音源编辑弹层未包含 iOS 的「请求头」多行编辑与「适用平台」分段（已有 `headers` 原样保留）。
- 音质选择器没有 `192k`。
- 内部类名仍保留 `Beans` 前缀（`BeansTheme`、`BeansApplication` 等），
  偏好键仍用 `beans.*`，LX JS 桥接全局名用 `__beans*`——这些对用户不可见。

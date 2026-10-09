<h1 align="center">Monica 本地密码库</h1>

<div align="center">

**中文** | [English](README_EN.md) | [日本語](README_JA.md) | [Tiếng Việt](README_VI.md) | [Русский](README_RU.md) | [黑羽川](README_Nya.md)

<img src="image/themepng.png" alt="Monica App Icon" width="500" />

<p><strong>聚合 Bitwarden 与 KeePass 的本地优先密码库</strong></p>
<p>Android · Local Vault · TOTP · WebDAV Backup</p>


[![Release](https://img.shields.io/github/v/release/Monica-Pass/Monica-for-Android?style=flat-square)](https://github.com/Monica-Pass/Monica-for-Android/releases)
[![Downloads](https://img.shields.io/github/downloads/Monica-Pass/Monica-for-Android/total?style=flat-square)](https://github.com/Monica-Pass/Monica-for-Android/releases)
[![Last Commit](https://img.shields.io/github/last-commit/Monica-Pass/Monica-for-Android?style=flat-square)](https://github.com/Monica-Pass/Monica-for-Android/commits)
[![QQ群](https://img.shields.io/badge/QQ群-1087865010-12B7F5?style=flat-square&logo=tencentqq&logoColor=white)](https://qm.qq.com/q/2vTdTkHV3u)
[![Telegram](https://img.shields.io/badge/Telegram-Monica%20Community-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/+IZUDLL-vWOA1Y2U1)

[![爱发电](https://img.shields.io/badge/爱发电-JoyinJoester-ea4aaa?style=flat-square)](https://afdian.com/a/JoyinJoester)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-joyinjoester-29ABE0?style=flat-square&logo=kofi&logoColor=white)](https://ko-fi.com/joyinjoester)
[![PayPal](https://img.shields.io/badge/PayPal-Support%20Monica-FFD140?style=flat-square&logo=paypal&logoColor=00457C)](https://www.paypal.com/ncp/payment/BHSYWK73CA8FW)
[![Liberapay · EUR](https://img.shields.io/badge/Liberapay-EUR-F6C915?style=flat-square&logo=liberapay&logoColor=black)](https://liberapay.com/JoyinJoester)
<br>
<a href="https://trendshift.io/repositories/27059" target="_blank"><img src="https://trendshift.io/api/badge/repositories/27059" alt="JoyinJoester%2FMonica | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

</div>

Monica 是一个聚合 **Bitwarden** 与 **KeePass** 的本地密码库（Local Vault）。
它以本地存储优先为核心，帮助你在 Android 设备上统一管理账号密码、2FA、私密笔记与敏感附件。

官网入口: https://monica-pass.github.io/MonicaDocs/

---

## 用户先看

### Monica 适合谁
- 需要本地优先密码管理，不希望账号数据托管到第三方云。
- 既使用 Bitwarden，也维护 KeePass (`.kdbx`) 数据。
- 需要在 Android 应用和手机浏览器中使用自动填充。

### 你能得到什么
- 本地加密保险箱: 登录信息、银行卡、身份信息、私密笔记、附件。
- 双生态聚合: Android 端包含 Bitwarden API/同步能力与 KeePass (`.kdbx`) 读写能力。
- 可选同步与备份: 通过自有 WebDAV 基础设施实现跨设备数据流转。
- 内置 TOTP: 在同一应用内完成密码与二次验证码管理。

### MDBX 本地数据库格式

跨端开发与 AI 适配请先遵守 [Monica MDBX 存储兼容契约](docs/storage/MDBX-CROSS-CLIENT-CONTRACT.zh-CN.md)，其中区分了核心格式、业务类型、未知字段保留和同步验收要求。
MDBX 是 Monica 正在推进的本地优先加密 vault 格式。它不是简单的密码表，而是围绕嵌套文件夹、附件、提交历史、冲突检测、tombstone 删除链路、快照恢复和 Tiga 安全模式设计的数据库格式。

如果你要在其他客户端接入 MDBX，请先读 [MDBX workspace 说明](mdbx/README.md) 和 [MDBX 客户端接入指南](mdbx/CLIENT_INTEGRATION_GUIDE.zh-CN.md)。完整格式规范在 [mdbx/docs](mdbx/docs/README.zh-CN.md)。

### 快速安装

1. 从 [Releases](https://github.com/Monica-Pass/Monica-for-Android/releases) 下载最新 APK。
2. 在 Android 8.0+ 设备安装并初始化主密码。

### 已知限制
- 由于系统兼容性原因，Monica for Android 目前在部分小米 HyperOS 设备上无法创建通行密钥（Passkey）。可以尝试酷 U 提供的解决模块：[HyperMonica](https://github.com/Wuming155/HyperMonica)。

---

## Android 版本重点

### 核心功能
- 本地 Vault: 所有核心凭据本地加密存储。
- 聚合导入: 支持 KeePass 数据迁移与 Bitwarden 兼容接入。
- 智能检索: 按标题、域名、标签快速定位凭据。
- 生物识别解锁: 使用系统级生物识别能力提升安全与可用性。
- TOTP 管理: 统一存储并生成动态验证码。

### 实现说明（专业版）
- UI 层: Jetpack Compose + Material 3 + Navigation Compose。
- 数据层: Room（`PasswordDatabase`）+ DAO + Repository。
- 并发模型: Kotlin Coroutines + Flow。
- 依赖注入: Koin（应用启动于 `MonicaApplication`）。
- 安全能力: Android Keystore、EncryptedSharedPreferences、BiometricPrompt。
- 同步任务: WorkManager（`AutoBackupWorker`）用于自动 WebDAV 备份。
- 协议与集成: Retrofit + OkHttp（Bitwarden API）、kotpass（KeePass）、sardine-android（WebDAV）。

### 安全模型
- 加密算法: AES-256-GCM（认证加密）。
- 密钥派生: PBKDF2-HMAC-SHA256（高迭代参数）。
- 本地保护: 主密码哈希与安全配置由本地安全组件管理。
- 网络边界: 应用声明网络权限，主要用于 Bitwarden 联动与 WebDAV 备份/同步等在线能力。

---

## 赞助支持

如果 Monica 对你有帮助，欢迎支持持续开发与安全投入。

<div align="center">
<img src="image/support_author.jpg" alt="Support Monica" width="320"/>
<br/>
<sub>微信 / 支付宝扫码支持</sub>
</div>

<br/>

<p align="center">
  <a href="https://www.paypal.com/ncp/payment/BHSYWK73CA8FW">
    <img src="https://img.shields.io/badge/PayPal-Support%20Monica-FFD140?style=for-the-badge&amp;logo=paypal&amp;logoColor=003087" alt="通过 PayPal 支持 Monica" />
  </a>
  <a href="https://liberapay.com/JoyinJoester">
    <img src="https://img.shields.io/badge/Liberapay-EUR-F6C915?style=for-the-badge&amp;logo=liberapay&amp;logoColor=black" alt="Liberapay · EUR (€)" />
  </a>
</p>

你的支持将优先用于:
- 安全审计与加密方案强化。
- Android 体验优化与稳定性改进。
- 跨端功能统一与文档维护。

<!-- afdian-sponsors:start -->
### 爱发电鸣谢

感谢每一位支持 Monica 的朋友！

**本月打赏金额：¥10.00**

<table>
<tbody>
<tr><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="1503384107"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-blue.png" alt="1503384107" width="56" height="56" /></a><br /><sub>1503384107<br />¥180.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="半夏微凉"><img src="https://pic1.afdiancdn.com/user/0eb2c0400a4511edac4a52540025c377/avatar/d97d62fbb2aa4fc296e8dabeae4be5de_w600_h600_s58.jpeg" alt="半夏微凉" width="56" height="56" /></a><br /><sub>半夏微凉<br />¥50.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="名字太长会有傻子跟着念吗"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-orange.png" alt="名字太长会有傻子跟着念吗" width="56" height="56" /></a><br /><sub>名字太长会有傻子跟着念吗<br />¥40.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="一生"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-blue.png" alt="一生" width="56" height="56" /></a><br /><sub>一生<br />¥30.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="LINGYU"><img src="https://pic1.afdiancdn.com/user/9b2ca6aaeb7411ee86205254001e7c00/avatar/87235f32a3abe937aefcc697a2a1eca2_w1050_h992_s239.jpg.gif" alt="LINGYU" width="56" height="56" /></a><br /><sub>LINGYU<br />¥20.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="洛初"><img src="https://pic1.afdiancdn.com/user/user_upload_osl/a3a8b7f7e037e52e0ce6a9251964a78c_w132_h132_s7.jpeg" alt="洛初" width="56" height="56" /></a><br /><sub>洛初<br />¥15.00</sub></td></tr>
<tr><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="Memory15"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png" alt="Memory15" width="56" height="56" /></a><br /><sub>Memory15<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_70c34"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png?imageView2/1/" alt="爱发电用户_70c34" width="56" height="56" /></a><br /><sub>爱发电用户_70c34<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="NovaX"><img src="https://pic1.afdiancdn.com/user/3a5c1cfc25b611f09aa55254001e7c00/avatar/6a9c4406d861193c64fbff10c741fddf_w2400_h2400_s2296.jpeg" alt="NovaX" width="56" height="56" /></a><br /><sub>NovaX<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_rywF"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-blue.png" alt="爱发电用户_rywF" width="56" height="56" /></a><br /><sub>爱发电用户_rywF<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="御坂07833号"><img src="https://pic1.afdiancdn.com/user/e40f486c7bb911ee8ca25254001e7c00/avatar/f4c8da76d80fd5a7cd276f6b5d7a6a54_w300_h300_s61.jpeg" alt="御坂07833号" width="56" height="56" /></a><br /><sub>御坂07833号<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="PVPWHGAME"><img src="https://pic1.afdiancdn.com/user/f110cee48d8611ee87e25254001e7c00/avatar/cfce45089c0692e2484d603e035a17be_w1720_h3728_s783.jpeg" alt="PVPWHGAME" width="56" height="56" /></a><br /><sub>PVPWHGAME<br />¥10.00</sub></td></tr>
<tr><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_ff493"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png?imageView2/1/" alt="爱发电用户_ff493" width="56" height="56" /></a><br /><sub>爱发电用户_ff493<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_a991e"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png?imageView2/1/" alt="爱发电用户_a991e" width="56" height="56" /></a><br /><sub>爱发电用户_a991e<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_U9rq"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-blue.png" alt="爱发电用户_U9rq" width="56" height="56" /></a><br /><sub>爱发电用户_U9rq<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="不二"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png" alt="不二" width="56" height="56" /></a><br /><sub>不二<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_edaa4"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png?imageView2/1/" alt="爱发电用户_edaa4" width="56" height="56" /></a><br /><sub>爱发电用户_edaa4<br />¥10.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_e928c"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png?imageView2/1/" alt="爱发电用户_e928c" width="56" height="56" /></a><br /><sub>爱发电用户_e928c<br />¥6.00</sub></td></tr>
<tr><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="哇哦"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-orange.png" alt="哇哦" width="56" height="56" /></a><br /><sub>哇哦<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_xj7t"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-blue.png" alt="爱发电用户_xj7t" width="56" height="56" /></a><br /><sub>爱发电用户_xj7t<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="Kiana Kaslana"><img src="https://pic1.afdiancdn.com/user/2a8bae681b3211eeb37b5254001e7c00/avatar/94a9bb340fed621cd44360a8b1fdb7d6_w1198_h981_s797.png" alt="Kiana Kaslana" width="56" height="56" /></a><br /><sub>Kiana Kaslana<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="海之南"><img src="https://pic1.afdiancdn.com/user/8a041df8860411edb23752540025c377/avatar/dad5974d3fca49d1aca5835d6818ea98_w2064_h2016_s3434.png" alt="海之南" width="56" height="56" /></a><br /><sub>海之南<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="重逢海风"><img src="https://pic1.afdiancdn.com/user/886243ac044b11edad3752540025c377/avatar/f50842fe3af48d89f4dfe2b95e2a4c66_w690_h647_s46.jpg" alt="重逢海风" width="56" height="56" /></a><br /><sub>重逢海风<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="yagaojun"><img src="https://pic1.afdiancdn.com/user/b3c6a738350e11ed8f3752540025c377/avatar/2322f1ee3d40356f3d97a4e93d0b4bb2_w1080_h1080_s91.jpeg" alt="yagaojun" width="56" height="56" /></a><br /><sub>yagaojun<br />¥5.00</sub></td></tr>
<tr><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="可恶"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-orange.png" alt="可恶" width="56" height="56" /></a><br /><sub>可恶<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_97324"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png?imageView2/1/" alt="爱发电用户_97324" width="56" height="56" /></a><br /><sub>爱发电用户_97324<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="Jursin"><img src="https://pic1.afdiancdn.com/user/user_upload_osl/34d612b1c80939392ab74bae307ae41a_w132_h132_s6.jpeg" alt="Jursin" width="56" height="56" /></a><br /><sub>Jursin<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="Memories white"><img src="https://pic1.afdiancdn.com/user/152f9a2c797011eba47252540025c377/avatar/505d7046fd33ebae8ff1af167177ef20_w800_h800_s409.jpeg" alt="Memories white" width="56" height="56" /></a><br /><sub>Memories white<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_hAKP"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png" alt="爱发电用户_hAKP" width="56" height="56" /></a><br /><sub>爱发电用户_hAKP<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="森王"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-orange.png" alt="森王" width="56" height="56" /></a><br /><sub>森王<br />¥5.00</sub></td></tr>
<tr><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_43357"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png?imageView2/1/" alt="爱发电用户_43357" width="56" height="56" /></a><br /><sub>爱发电用户_43357<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="坏名字qwq"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-yellow.png" alt="坏名字qwq" width="56" height="56" /></a><br /><sub>坏名字qwq<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="Draking"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-yellow.png" alt="Draking" width="56" height="56" /></a><br /><sub>Draking<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="希莉卡"><img src="https://pic1.afdiancdn.com/user/fd7a69c6e64c11f0b3cb52540025c377/avatar/5a619e16a1e05c140e7b838b556e0ca1_w1024_h1280_s179.jpeg" alt="希莉卡" width="56" height="56" /></a><br /><sub>希莉卡<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="airem"><img src="https://pic1.afdiancdn.com/user/26696d60ee9111ee832a5254001e7c00/avatar/1267d3f1a84cc1d6b6ea9e7ae4bac522_w791_h791_s327.jpeg" alt="airem" width="56" height="56" /></a><br /><sub>airem<br />¥5.00</sub></td><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="Janson"><img src="https://pic1.afdiancdn.com/user/ad16e57c584511ef8a9152540025c377/avatar/d318e7eb2a040a0e53696a38eb982e52_w160_h160_s4.png" alt="Janson" width="56" height="56" /></a><br /><sub>Janson<br />¥5.00</sub></td></tr>
<tr><td align="center" width="120"><a href="https://afdian.com/a/JoyinJoester" title="爱发电用户_0e739"><img src="https://pic1.afdiancdn.com/default/avatar/avatar-purple.png?imageView2/1/" alt="爱发电用户_0e739" width="56" height="56" /></a><br /><sub>爱发电用户_0e739<br />¥5.00</sub></td></tr>
</tbody>
</table>
<!-- afdian-sponsors:end -->

---

## 开发者信息

### 项目分层（代码现状）
- `takagi/ru/monica/ui`: Compose 页面与组件。
- `takagi/ru/monica/data`: Room 实体、DAO、数据库迁移。
- `takagi/ru/monica/repository`: 数据访问封装。
- `takagi/ru/monica/security`: 加密、密钥与鉴权相关实现。
- `takagi/ru/monica/bitwarden`: API、加密、映射、同步与视图模型。
- `takagi/ru/monica/autofill`: 自动填充服务与流程。
- `takagi/ru/monica/passkey`: Android 14+ Credential Provider 相关实现。
- `takagi/ru/monica/workers`: 后台任务（如自动 WebDAV 备份）。
- `mdbx`: Monica MDBX 本地数据库格式的 Rust workspace 与客户端接入文档。

### 当前已使用的成熟组件（仓库可验证）
- Android UI: Jetpack Compose, Material 3, Navigation Compose。
- 数据与状态: Room, DataStore Preferences, ViewModel。
- 安全: Android Keystore, EncryptedSharedPreferences, BiometricPrompt。
- 网络与协议: Retrofit, OkHttp, Kotlinx Serialization。
- 同步与生态: sardine-android(WebDAV), kotpass(KeePass), Bitwarden API 对接。
- 异步与任务: Coroutines, Flow, WorkManager。
- 其他能力: CameraX + ML Kit（二维码扫描）, Credentials API（Passkey）。

### 构建与贡献
- Android Studio: 最新稳定版。
- JDK: 17+。
- Android 配置: `compileSdk 35`，`targetSdk 34`，`minSdk 26`（见 `Monica for Android/app/build.gradle`）。
- Android 构建基线: AGP `8.6.0`，Kotlin `2.0.21`，Compose BOM `2026.03.00`（Material3 跟随 BOM）。
- 版本信息以 `Monica for Android/gradle/libs.versions.toml` 与 `Monica for Android/app/build.gradle` 为准。
- 欢迎通过 Issue / PR 参与功能和安全改进。

---

## 致谢

Monica 的设计、兼容性适配与部分功能方向，受到了以下优秀开源项目和软件的启发与帮助：

- [Keyguard](https://github.com/AChep/keyguard-app) - Android 端密码管理器的交互设计与体验参考。
- [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer) - Android 端密码库滚动条样式参考。
- [Bitwarden](https://bitwarden.com/) - 开源密码管理生态、Vault 模型与同步能力的重要参考。
- [KeePass](https://keepass.info/) - 本地密码库理念与 `.kdbx` 生态兼容的重要基础。
- [Stratum Auth](https://github.com/stratumauth/app) - 身份验证器体验、图标资源与相关兼容支持参考。
- [Steam Desktop Authenticator](https://github.com/Jessecar96/SteamDesktopAuthenticator) - Steam maFile 格式、Steam Guard 与交易确认兼容性的参考。
- [steamguard-cli](https://github.com/dyc3/steamguard-cli) - Steam Guard 登录、令牌迁移与确认协议实现的参考。
- [AnotherVaporAuth](https://github.com/freefrank/AnotherVaporAuth) - Steam 移动验证器、登录批准与确认流程体验的参考。

---

## 友情链接

- [Linux.do](https://linux.do)
- [TinadecOffice](https://github.com/Tinadec/TinadecOffice)

---

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=Monica-Pass/Monica-for-Android&type=Date)](https://star-history.com/#Monica-Pass/Monica-for-Android&Date)

---

## 贡献者

![贡献者](.github/assets/contributor-flag.svg)

---

## 社区与支持

- Telegram 群组：[加入 Monica 社区](https://t.me/+IZUDLL-vWOA1Y2U1)

---

## 许可证

Copyright (c) 2025 JoyinJoester

Monica 基于 [GNU General Public License v3.0](LICENSE) 开源发布。

## 第三方图标标注

- 本项目本地打包了来自 [Stratum Auth app](https://github.com/stratumauth/app) 的图标资源（版本 [v1.4.0](https://github.com/stratumauth/app/releases/tag/v1.4.0)，目录 [icons](https://github.com/stratumauth/app/tree/v1.4.0/icons) / [extraicons](https://github.com/stratumauth/app/tree/v1.4.0/extraicons)，GPL-3.0）。
- 银行卡/支付卡图标来源：本地目录 [SVG Credit Card & Payment Icons](svg-credit-card-payment-icons-main)（Apache-2.0）。
- 品牌名称与 Logo 的商标权归各自权利人所有。

## F-Droid contact

For F-Droid metadata or packaging issues, contact: joyin8888@foxmail.com


### Shizuku default password manager / 默认密码管理器

The Android 14+ helper in developer and autofill settings adapts the secure-settings transaction logic from [cr-zhichen/password-manager-switch](https://github.com/cr-zhichen/password-manager-switch) (MIT, copyright 2026 cr-zhichen). It targets the installed Monica edition for the current Android user, verifies writes, and supports restoring the previous configuration. The original MIT notice is included in `app/src/main/assets/licenses/password-manager-switch.txt`.

开发者设置与自动填充设置中的 Shizuku 入口参考上述原项目（MIT），用于 HyperOS 隐藏第三方密码管理器设置入口的情况。仅设置当前 Monica 版本，不导入原项目的自更新等功能；系统设置读回成功不代表所有 HyperOS 通行密钥流程均已验证。

On multi-user devices, Shizuku may also require Monica to be installed for the owner user before starting the helper for a secondary user. Only the calling user settings are changed.
多用户设备上，Shizuku 可能需要主用户也安装 Monica 才能启动副用户的辅助服务；实际更改仍限定于调用用户。

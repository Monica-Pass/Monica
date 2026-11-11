# 🔐 Monica Password Manager

**中文** | [English](README.md)

<div align="center">

![Monica Logo](https://img.shields.io/badge/Monica-Password%20Manager-blue?style=for-the-badge)
[![Android](https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple?style=for-the-badge&logo=kotlin)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=for-the-badge)](LICENSE)

**一个用于管理密码、验证码、卡片与笔记的 Android 应用**

当前源码为 **1.0.327 未发布开发版本**；功能与依赖更新不代表已正式发布。

[功能特性](#-功能特性) • [安装说明](#-安装说明) • [使用指南](#-使用指南) • [技术栈](#-技术栈) • [截图展示](#-截图展示) • [贡献指南](#-贡献指南)

</div>

---

## ✨ 功能特性

### 🔒 核心功能
- **密码管理** - 安全存储和管理您的所有账号密码
- **TOTP 两步验证** - 支持生成和管理 TOTP 验证码
- **文档存储** - 安全保存重要文档和笔记
- **银行卡管理** - 加密存储银行卡信息

### 🛡️ 安全特性
- **主密码保护** - 支持字母、数字、符号与中文主密码
- **本地字段加密** - 本地敏感字段使用 AES-GCM；Bitwarden、KDBX、MDBX 沿用各自格式
- **恢复保护** - 恢复取决于原密码与可用恢复材料，不应把安全问题视为通用的密码库解密方式
- **屏幕截图保护** - 可选防止截图功能
- **自动锁定** - 按已配置的后台、超时等策略锁定

### 📱 实用功能
- **密码生成器** - 快速生成强密码
- **密码强度检测** - 实时显示密码安全等级
- **数据导入导出** - 支持 CSV 格式导入导出
  - ✅ Chrome 密码 CSV 兼容
  - ✅ 批量导入导出
- **QR 码扫描** - 快速扫描 TOTP 二维码
- **搜索功能** - 快速查找您的密码和信息
- **分组管理** - 按类型分类管理不同数据
- **密码库与连接** - 支持 KeePass/KDBX、MDBX 和 Bitwarden/Vaultwarden，具体操作遵循各来源能力
- **通行密钥** - 提供 Android 14+ 凭据提供程序入口，兼容范围取决于设备、浏览器与服务

### 🎨 界面设计
- **Material Design 3** - 现代化的 UI 设计
- **深色模式** - 支持浅色/深色主题
- **多语言支持** - 包含简繁中文、英语、日语、越南语、俄语及巴西/欧洲葡语等，完整选项见应用语言设置
- **响应式布局** - 适配各种屏幕尺寸

---

## 📥 安装说明

### 系统要求
- Android 8.0 (API 26) 及以上版本
- 安装及备份所需空间以实际 APK、附件和密码库大小为准

### 下载安装

1. 前往 [Monica Releases](https://github.com/Monica-Pass/Monica/releases) 获取已发布 APK。
2. 选择与设备架构相符的包并安装。
3. 首次启动后设置主密码。开发分支版本不代表已经发布。

### 从源码编译

准备 JDK 17、Android SDK Platform 37 / platform-tools / build-tools、side-by-side NDK，以及通过 rustup 安装的 Rust。JNI 随当前源码构建，因此 `cargo` 和 `cargo-ndk` 必须可供 Gradle 使用。SDK 通过 `local.properties` 或 `ANDROID_HOME` 配置，指定 NDK 时可设置 `ANDROID_NDK_HOME`。原生桥目标为 API 26，入口见 [JNI 构建脚本](gradle/monica-rust-jni.gradle)与[工具查找脚本](gradle/rust-toolchain.gradle)。

```bash
git clone https://github.com/Monica-Pass/Monica.git
cd "Monica/Monica for Android"

rustup toolchain install stable --profile minimal
rustup default stable
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --version 4.1.2 --locked

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest

# x86_64 模拟器的主程序与测试包
rustup target add x86_64-linux-android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86TestAbi
```

默认 APK 输出为 ARM。Windows PowerShell 使用 `./gradlew.bat`；IDE 应打开 `Monica for Android` 子目录。打包成功不等于设备或 Release 验证通过。

---

## 📖 使用指南

### 首次使用

1. **设置主密码**
   - 首次打开应用,设置您的主密码
   - 输入两次确认密码
   - 妥善保管主密码与独立加密备份；恢复依赖现有密钥和恢复材料

2. **检查备份与锁定设置**
   - 根据需要配置加密备份和自动锁定
   - 验证备份可恢复后再依赖它；安全问题不能保证恢复所有加密数据

### 密码管理

#### 添加密码
1. 点击主页的 ➕ 按钮
2. 填写以下信息:
   - 网站名称/应用名称
   - 用户名
   - 密码 (可使用密码生成器)
   - 网站 URL (可选)
   - 备注 (可选)
3. 点击保存

#### 查看和编辑
- 点击密码条目查看详情
- 点击编辑按钮修改信息
- 长按密码可快速复制

#### 删除密码
- 在密码详情页点击菜单
- 选择"删除"选项

### TOTP 两步验证

#### 添加 TOTP
1. 切换到 TOTP 标签页
2. 点击 ➕ 按钮
3. 扫描 QR 码或手动输入密钥
4. 填写账号名称和发行方
5. 保存

#### 使用 TOTP
- TOTP 码会自动刷新
- 点击验证码可复制
- 倒计时显示剩余时间

### 证件管理

1. 切换到"证件"标签页
2. 添加证件:
   - 标题
   - 内容
   - 标签 (可选)
   - 证件图片 (可上传)
3. 根据证件类型编辑对应字段与备注
4. 图片及附件操作以当前编辑页提供的选项为准

### 银行卡管理

1. 切换到"银行卡"标签页
2. 添加银行卡信息:
   - 银行名称
   - 卡号
   - 持卡人姓名
   - 有效期
   - CVV (可选)
3. 敏感字段按所选密码库的存储规则保护

### 数据导入导出

#### 导出数据
1. 进入设置 → 数据导出
2. 输入主密码确认
3. 选择保存位置
4. 按所选格式导出；CSV 是明文凭据文件，需要加密保护时选择支持加密的备份格式

#### 导入数据
1. 进入设置 → 数据导入
2. 选择 CSV 文件
3. 支持以下格式:
   - Monica 导出的 CSV
   - Chrome 密码 CSV
4. 确认导入

### 设置选项

#### 安全设置
- **更改主密码** - 修改登录密码
- **恢复与备份** - 查看当前可用恢复入口并保留独立加密备份
- **屏幕截图保护** - 防止应用内截图

#### 外观设置
- **主题** - 浅色/深色/跟随系统
- **语言** - 在设置中选择当前支持的语言和地区

#### 数据管理
- **导出数据** - 备份所有数据
- **导入数据** - 恢复或导入数据
- **清除所有数据** - 删除所有数据(需确认)

---

## 🛠️ 技术栈

| 组件 | 当前配置 |
| --- | --- |
| 构建运行时 | JDK 17；Java/Kotlin 字节码目标 11 |
| Gradle Wrapper / Android Gradle Plugin | 9.7.0 / 9.3.1 |
| Kotlin / KSP | 2.4.21 / 2.3.13 |
| Compose BOM | 2026.09.00 |
| Material 3 Expressive | 1.5.0-rc01，独立固定版本，不跟随 BOM |
| Android SDK | compileSdk 37、targetSdk 34、minSdk 26（Android 8.0） |

界面使用 Jetpack Compose、ViewModel、Coroutines/Flow；本地索引和记录使用 Room（SQLite）。扫码使用 CameraX 与 ZXing，KDBX 使用本地 `kotpass` 模块，WebDAV 使用 `sardine-android`，Markdown 使用 CommonMark，部分原生操作由 Rust/JNI 模块提供。

依赖与构建的准确信息见 [版本目录](gradle/libs.versions.toml)、[应用配置](app/build.gradle)和 [Gradle Wrapper 配置](gradle/wrapper/gradle-wrapper.properties)。

---

## 📱 截图展示

<div align="center">

### 主要功能展示
| 密码管理 | 添加密码 | TOTP 两步验证 |
|---------|---------|----------|
| ![密码列表](image/password.png) | ![添加密码](image/addpass.png) | ![TOTP验证](image/2fa.png) |

### 其他功能
| 银行卡管理 | 证件管理 | 删除功能 |
|---------|---------|----------|
| ![银行卡](image/bank.png) | ![证件](image/doc.png) | ![删除](image/del.png) |

### 设置
| 设置页面 |
|---------|
| ![设置](image/set.png) |

</div>

---

## 🔐 安全说明

- 本地敏感字段使用 AES-GCM。
- 本地主密码校验及 MDK 密码包装密钥：PBKDF2-HMAC-SHA256，1.0.327 新写入使用 600,000 次迭代。历史 100,000 次 PBKDF2 和 Argon2id 数据保留兼容，成功验证原密码后迁移，保留原 MDK 与记录密文。
- 便携加密备份：兼容 V1 使用 PBKDF2-HMAC-SHA256／100,000 次，增强 V2 使用 600,000 次；备份格式与本地主密码保护分别处理。
- 主密码找回：安全问题答案加本次系统锁屏凭据确认（Android 11+），一次性授权在取消、后台或锁定时撤销。新答案采用随机盐与 600,000 次 PBKDF2-HMAC-SHA256，旧 SHA-256 答案和备份保留兼容；尝试次数跨页面和进程重启保留。
- Bitwarden、KDBX 与 MDBX 保留各自的 KDF 和文件格式。
- Android Keystore 用于保护相应的本地密钥材料；是否由硬件支持取决于设备，不保证受损系统或 root 环境下的绝对安全。
- 本地使用无需云账号。网络权限用于所配置的服务、传输与更新检查，不能描述为完全不联网；自动检查更新可关闭。
- 截图保护与自动锁定按应用设置及系统能力生效。请保留独立加密备份；忘记密码或丢失密钥时，是否能恢复取决于实际可用材料。
- CSV 导出是明文，不能与加密备份等同。

---

## 🤝 贡献指南

欢迎贡献代码、报告问题或提出建议!

### 如何贡献

1. **Fork 本仓库**
2. **创建特性分支** (`git checkout -b feature/AmazingFeature`)
3. **提交更改** (`git commit -m 'Add some AmazingFeature'`)
4. **推送到分支** (`git push origin feature/AmazingFeature`)
5. **创建 Pull Request**

### 报告问题

如果您发现 Bug 或有功能建议:
1. 前往 [Issues](https://github.com/Monica-Pass/Monica/issues) 页面
2. 搜索是否已有相关问题
3. 创建新 Issue 并详细描述

### 代码规范
- 遵循 Kotlin 官方代码风格
- 添加必要的注释
- 编写单元测试

## 🧩 第三方图标标注

- 本项目已本地打包来自 [Stratum Auth app](https://github.com/stratumauth/app) 的图标资源（版本 [v1.4.0](https://github.com/stratumauth/app/releases/tag/v1.4.0)，目录 [icons](https://github.com/stratumauth/app/tree/v1.4.0/icons) / [extraicons](https://github.com/stratumauth/app/tree/v1.4.0/extraicons)，GPL-3.0）。
- 品牌名称与 Logo 的商标权归各自权利人所有。

## 📄 许可证

本项目采用 GPL-3.0 许可证 - 详见 [LICENSE](LICENSE) 文件

```
GNU GENERAL PUBLIC LICENSE
Version 3, 29 June 2007

Copyright (C) 2025 JoyinJoester

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

---

## 👨‍💻 作者

**JoyinJoester**
- Email: lichaoran8@gmail.com
- GitHub: [@JoyinJoester](https://github.com/JoyinJoester)

---

## 🙏 致谢

感谢所有为 Monica 做出贡献的开发者!

特别感谢:
- [Keyguard](https://github.com/AChep/keyguard-app) - 在 Android 端密码管理器设计与体验上提供了很多启发
- [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer) - 密码库滚动条样式参考
- [Bitwarden](https://bitwarden.com/) - 开源密码管理生态与 Vault/同步能力参考
- [KeePass](https://keepass.info/) - 本地密码库与跨平台密码管理理念的重要来源
- [Stratum](https://github.com/stratumauthapp/stratum) - 身份验证器与相关导入/兼容能力的参考项目
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - 现代化 UI 框架
- [Material Design](https://m3.material.io/) - 设计规范
- [ZXing](https://github.com/zxing/zxing) - QR 码库

---

## 🤝 友情链接

- [Linux.do](https://linux.do)
- [TinadecOffice](https://github.com/Tinadec/TinadecOffice)

---

## 📞 支持

如果您觉得这个项目有帮助,请给一个 ⭐️ Star!

有问题或建议?
- 📧 Email: lichaoran8@gmail.com
- 💬 Issues: [GitHub Issues](https://github.com/Monica-Pass/Monica/issues)

---

## 💖 支持作者

也可以通过 [Liberapay](https://liberapay.com/JoyinJoester) 使用欧元（EUR）支持 Monica。

如果这个项目对您有帮助,欢迎通过以下方式支持作者的持续开发:

<div align="center">

![支持作者](image/support_author.jpg)

**扫码支持 • 感谢您的慷慨 ❤️**

</div>

您的支持是作者持续更新和改进的最大动力!

---

<div align="center">

**用 ❤️ 和 Kotlin 打造**

[回到顶部](#-monica-password-manager)

</div>


### Shizuku default password manager / 默认密码管理器

The Android 14+ helper in developer and autofill settings adapts the secure-settings transaction logic from [cr-zhichen/password-manager-switch](https://github.com/cr-zhichen/password-manager-switch) (MIT, copyright 2026 cr-zhichen). It targets the installed Monica edition for the current Android user, verifies writes, and supports restoring the previous configuration. The original MIT notice is included in `app/src/main/assets/licenses/password-manager-switch.txt`.

开发者设置与自动填充设置中的 Shizuku 入口参考上述原项目（MIT），用于 HyperOS 隐藏第三方密码管理器设置入口的情况。仅设置当前 Monica 版本，不导入原项目的自更新等功能；系统设置读回成功不代表所有 HyperOS 通行密钥流程均已验证。

On multi-user devices, Shizuku may also require Monica to be installed for the owner user before starting the helper for a secondary user. Only the calling user settings are changed.
多用户设备上，Shizuku 可能需要主用户也安装 Monica 才能启动副用户的辅助服务；实际更改仍限定于调用用户。

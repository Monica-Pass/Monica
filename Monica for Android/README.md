# Monica for Android 🔐

[中文](README_ZH.md) | **English**

<div align="center">

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![Material Design 3](https://img.shields.io/badge/Material%20Design%203-757575?style=for-the-badge&logo=material-design&logoColor=white)
![License](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)

**Next-Generation Password Manager tailored for Android**
*Secure. Offline-First. Feature-Packed.*

</div>

---

## 📖 Overview

**Monica for Android** manages passwords, authenticators, cards and notes, with local storage and optional connections to Bitwarden/Vaultwarden, KeePass and cloud backup providers. Sensitive local fields use AES-GCM; connected vaults retain their own encryption and storage formats.

The current checkout is **1.0.327, unreleased**. Development changes and dependency versions do not indicate that a release has been published.

With a stunning **Material You** interface, seamless **Autofill** integration, and advanced features like **KeePass support** and **TOTP generation**, Monica brings desktop-class power to your mobile device.

---

## ✨ Key Features

### 🔐 Advanced Credential Management
*   **Local protection**: Sensitive local fields use AES-GCM, with master-password and Android Keystore protection for local key material. Provider-specific formats remain separate.
*   **Rich Entries**: Store more than just passwords. Add **websites**, **notes**, **emails**, **phone numbers**, and custom tags.
*   **Password Generator**: Generate random passwords with customizable rules (length, charset, entropy).
*   **History & Recycle Bin**: Accidentally deleted something? Recover it from the recycle bin. view previous password versions in history.

### 🛡️ TOTP Authenticator
*   **Integrated 2FA**: No need for a separate authenticator app.
*   **Smart Scan**: Add accounts instantly by scanning QR codes.
*   **Steam Guard**: Native support for Steam's proprietary 2FA protocol.
*   **Post-Fill Popup**: Automatically prompts the relevant TOTP code after autofilling a password—seamless login flow.

### 🔄 Sync & Backup
*   **Optional connections**: Configure WebDAV/OneDrive backups or connect a Bitwarden/Vaultwarden account. Server capabilities and vault formats determine the available operations.
*   **Backups**: ZIP export offers an encryption option. Retain the backup password and verify a restore before relying on a backup.
*   **Cross-platform formats**: Use supported KeePass/MDBX formats or import/export workflows; compatibility depends on the other client version and fields involved.

### 🧩 Interoperability & Migration
*   **KeePass Integration**: Directly **read and write** `.kdbx` files. Use Monica as a modern UI for your existing KeePass databases.
*   **Chrome Import**: Seamlessly migrate credentials from Google Chrome CSV exports.
*   **Data Export**: Choose a supported export format and scope. CSV contains plaintext credentials; use an encrypted backup when needed.
*   **Passkeys**: Android 14+ credential-provider integration is available; supported flows depend on the device, browser and service.

### 🤖 Intelligent Autofill System
*   **Refactored Engine**: Completely redesigned autofill service for higher compatibility with apps and browsers.
*   **Overlay Mode**: Non-intrusive overlay for quick access without leaving your current app.

### 📄 Secure Vault for Documents & Cards
*   **Bank Cards**: Encrypted storage for credit/debit cards with privacy masking.
*   **ID Documents**: Securely store digital copies of your passport, driver's license, and other IDs.
*   **Encrypted Notes**: A safe place for keys, recovery codes, and private memos.

---

## 🎨 Design & Experience

Monica embraces **Material Design 3** (Material You) to offer a visually stunning and responsive experience.

*   **Dynamic Theming**: UI colors adapt to your wallpaper (on Android 12+).
*   **Dark Mode**: Hand-crafted dark theme for OLED efficiency and eye comfort.
*   **Smooth Animations**: Fluid transitions powered by Jetpack Compose.
*   **Icon Support**: Visual icons for password cards for easier recognition.

The vault scrollbar style is inspired by [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer).

---

## 🔒 Security Architecture

- Sensitive fields in Monica's local Room-backed storage use AES-GCM.
- Local master-password verification and derivation of the password-based MDK wrapping key use PBKDF2-HMAC-SHA256 with 100,000 iterations. Bitwarden, KDBX and MDBX keep their own KDFs and formats.
- Android Keystore protects local key material where used. Hardware-backed protection depends on the device and does not guarantee safety on a compromised operating system.
- Screenshot protection and lock behavior follow the application's settings and platform capabilities. Keep an independent encrypted backup; recovery depends on the original password and available recovery material.
- Network permission supports configured providers, transfers and update checks. Local use does not require a cloud account; optional automatic update checks can be disabled.

---

## 🛠️ Tech Stack

| Component | Current configuration |
| --- | --- |
| Build runtime | JDK 17; Java/Kotlin bytecode target 11 |
| Gradle Wrapper / Android Gradle Plugin | 9.7.0 / 9.3.1 |
| Kotlin / KSP | 2.4.21 / 2.3.13 |
| Compose BOM | 2026.09.00 |
| Material 3 Expressive | 1.5.0-rc01, explicitly pinned outside the BOM |
| Android SDK | compileSdk 37, targetSdk 34, minSdk 26 (Android 8.0) |

The app uses ViewModel, Coroutines/Flow and Room (SQLite), CameraX with ZXing for scanning, the local `kotpass` module for KDBX, `sardine-android` for WebDAV, CommonMark for Markdown, and Rust/JNI modules for supported native operations.

Version sources: [version catalog](gradle/libs.versions.toml), [app configuration](app/build.gradle) and [wrapper configuration](gradle/wrapper/gradle-wrapper.properties).

---

## 📸 Screenshots

<div align="center">

| Vault Home | TOTP Authenticator | Settings |
|:---:|:---:|:---:|
| ![Password List](image/password.png) | ![TOTP](image/2fa.png) | ![Settings](image/set.png) |

</div>

---

## 🚀 Getting Started

### Installation
1.  Download the latest `.apk` from the [**Releases**](https://github.com/Monica-Pass/Monica/releases) page.
2.  Install on your Android device (Android 8 or higher required).
3.  Launch and follow the setup wizard to create your Master Password.

### Build from Source

Install JDK 17, Android SDK Platform 37 / platform-tools / build-tools, a side-by-side Android NDK, and Rust via rustup. The JNI bridge is built from this checkout, so `cargo` and `cargo-ndk` must be available to Gradle. Configure the SDK through `local.properties` or `ANDROID_HOME`; set `ANDROID_NDK_HOME` when selecting an installed NDK explicitly. The native bridge targets Android API 26. See the [JNI build script](gradle/monica-rust-jni.gradle) and [tool discovery](gradle/rust-toolchain.gradle).

```bash
git clone https://github.com/Monica-Pass/Monica.git
cd "Monica/Monica for Android"

rustup toolchain install stable --profile minimal
rustup default stable
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --version 4.1.2 --locked

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest

# x86_64 emulator: build both the application and instrumentation APKs
rustup target add x86_64-linux-android
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86TestAbi
```

Default APK outputs target ARM. Windows PowerShell users should invoke `./gradlew.bat`. Open this Android subdirectory in the IDE; a successful build alone does not establish device or release validation.

---

## 🤝 Support the Development

You can also support Monica in euros (EUR) through [Liberapay](https://liberapay.com/JoyinJoester).

Monica is an open-source labor of love. If this app helps you secure your digital life, please consider supporting its development!

<div align="center">
<img src="image/support_author.jpg" alt="Support Author" width="300" style="border-radius: 10px"/>
<br/>
<sub>Scan using WeChat or Alipay</sub>
</div>

**Your support enables:**
*   Security maintenance and regression testing
*   Feature development and compatibility testing
*   Server costs for update distribution

---

## 🤝 Friend Links

- [Linux.do](https://linux.do)
- [TinadecOffice](https://github.com/Tinadec/TinadecOffice)

---

## Contributors

![Contributors](../.github/assets/contributor-flag.svg)

---

## ⚖️ License

Copyright © 2025 JoyinJoester.
Licensed under the **GNU General Public License v3.0**.


### Shizuku default password manager / 默认密码管理器

The Android 14+ helper in developer and autofill settings adapts the secure-settings transaction logic from [cr-zhichen/password-manager-switch](https://github.com/cr-zhichen/password-manager-switch) (MIT, copyright 2026 cr-zhichen). It targets the installed Monica edition for the current Android user, verifies writes, and supports restoring the previous configuration. The original MIT notice is included in `app/src/main/assets/licenses/password-manager-switch.txt`.

开发者设置与自动填充设置中的 Shizuku 入口参考上述原项目（MIT），用于 HyperOS 隐藏第三方密码管理器设置入口的情况。仅设置当前 Monica 版本，不导入原项目的自更新等功能；系统设置读回成功不代表所有 HyperOS 通行密钥流程均已验证。

On multi-user devices, Shizuku may also require Monica to be installed for the owner user before starting the helper for a secondary user. Only the calling user settings are changed.
多用户设备上，Shizuku 可能需要主用户也安装 Monica 才能启动副用户的辅助服务；实际更改仍限定于调用用户。

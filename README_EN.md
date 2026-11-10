# Monica Local Password Vault

<div align="center">

[中文](README.md) | **English** | [日本語](README_JA.md) | [Tiếng Việt](README_VI.md) | [Русский](README_RU.md) | [黑羽川](彩蛋语言备份/README_Nya.md)

<img src="image/themepng.png" alt="Monica App Icon" width="500" />

<p><strong>A local-first password vault that bridges Bitwarden and KeePass</strong></p>
<p>Android · Local Vault · TOTP · WebDAV Backup</p>

[![Release](https://img.shields.io/github/v/release/Monica-Pass/Monica?style=flat-square)](https://github.com/Monica-Pass/Monica/releases)
[![Downloads](https://img.shields.io/github/downloads/Monica-Pass/Monica/total?style=flat-square)](https://github.com/Monica-Pass/Monica/releases)
[![Last Commit](https://img.shields.io/github/last-commit/Monica-Pass/Monica?style=flat-square)](https://github.com/Monica-Pass/Monica/commits)
[![QQ Group](https://img.shields.io/badge/QQ%20Group-1087865010-12B7F5?style=flat-square&logo=tencentqq&logoColor=white)](https://qm.qq.com/q/2vTdTkHV3u)
[![Telegram](https://img.shields.io/badge/Telegram-Monica%20Community-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/+IZUDLL-vWOA1Y2U1)

[![Afdian](https://img.shields.io/badge/Afdian-JoyinJoester-ea4aaa?style=flat-square)](https://afdian.com/a/JoyinJoester)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-joyinjoester-29ABE0?style=flat-square&logo=kofi&logoColor=white)](https://ko-fi.com/joyinjoester)
[![PayPal](https://img.shields.io/badge/PayPal-Support%20Monica-FFD140?style=flat-square&logo=paypal&logoColor=00457C)](https://www.paypal.com/ncp/payment/BHSYWK73CA8FW)
[![Liberapay · EUR](https://img.shields.io/badge/Liberapay-EUR-F6C915?style=flat-square&logo=liberapay&logoColor=black)](https://liberapay.com/JoyinJoester)
<br>
<a href="https://trendshift.io/repositories/27059" target="_blank"><img src="https://trendshift.io/api/badge/repositories/27059" alt="JoyinJoester%2FMonica | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>

</div>

Monica is a local password vault that aggregates **Bitwarden** and **KeePass**.
It is built around local-first storage and helps you manage passwords, 2FA, secure notes, and sensitive attachments on Android devices.

Website: https://monica-pass.github.io/MonicaDocs/

---

## User First

### Who Monica is for

- Users who want local-first password management instead of fully hosted cloud storage.
- Users who work with both Bitwarden data and KeePass (`.kdbx`) files.
- Users who need autofill in Android apps and mobile browsers.

### What you get

- Encrypted local vault for logins, cards, identity data, notes, and attachments.
- Dual ecosystem integration: Android includes Bitwarden API/sync capability and KeePass (`.kdbx`) read/write support.
- Optional WebDAV backup and remote vault sync, OneDrive backup and file access, plus paired local-network transfers.
- Built-in TOTP management in the same app.

### MDBX local database format
MDBX is Monica's local-first encrypted vault format in progress. It is not just a password table; it is designed around nested folders, attachments, commit history, conflict detection, tombstone-based deletion, snapshot recovery, and Tiga security modes.

If you want to integrate MDBX in another client, start with the [MDBX workspace README](mdbx/README.en.md) and the [MDBX client integration guide](mdbx/CLIENT_INTEGRATION_GUIDE.md). The full format specification lives in [mdbx/docs](mdbx/docs/README.md).

### Quick install

1. Download the latest APK from [Releases](https://github.com/Monica-Pass/Monica/releases).
2. Install on Android 8.0+ and initialize your master password.

### Known limitation

- Due to system compatibility constraints, Monica for Android currently cannot create passkeys on some Xiaomi HyperOS devices. You can try [HyperMonica](https://github.com/Wuming155/HyperMonica), a workaround module contributed by a Coolapk community member.

---

## Android Focus

### Core capabilities

- Local vault for credential storage.
- Aggregated import/integration with KeePass and Bitwarden.
- Fast search by title, domain, and tags.
- Biometric unlock with Android system capability.
- Unified TOTP storage and generation.

### Implementation details

- UI: Jetpack Compose + Material 3 Expressive + Navigation Compose.
- Data: Room (`PasswordDatabase`) + DAO + Repository, with native KDBX and MDBX storage integrations.
- Concurrency: Kotlin Coroutines + Flow.
- Dependency wiring: explicit constructors, factories, and application entry points.
- Security: Android Keystore, EncryptedSharedPreferences, BiometricPrompt.
- Background tasks: WorkManager (`AutoBackupWorker`) for automatic WebDAV backup.
- Protocols and integrations: Retrofit + OkHttp (Bitwarden API), kotpass (KeePass), sardine-android (WebDAV).

### Security model

- Local sensitive fields: AES-256-GCM authenticated encryption in `SecurityManager`.
- Local master password verification and derivation of the password-based MDK wrapping key: PBKDF2-HMAC-SHA256, currently 100,000 iterations.
- Bitwarden, KeePass, and MDBX use the encryption and key derivation rules of their own formats and configurations.
- Local protection: master password hash and security settings are managed locally.
- Network boundary: the app declares network permissions for features such as Bitwarden, WebDAV, OneDrive, local-network transfers, and update checks.

---

## Support

If Monica helps your workflow, consider supporting development and security improvements.

<div align="center">
<img src="image/support_author.jpg" alt="Support Monica" width="320"/>
<br/>
<sub>Scan with WeChat or Alipay</sub>
</div>

<br/>

<p align="center">
  <a href="https://www.paypal.com/ncp/payment/BHSYWK73CA8FW">
    <img src="https://img.shields.io/badge/PayPal-Support%20Monica-FFD140?style=for-the-badge&amp;logo=paypal&amp;logoColor=003087" alt="Support Monica with PayPal" />
  </a>
  <a href="https://liberapay.com/JoyinJoester">
    <img src="https://img.shields.io/badge/Liberapay-EUR-F6C915?style=for-the-badge&amp;logo=liberapay&amp;logoColor=black" alt="Liberapay · EUR (€)" />
  </a>
</p>

Your support mainly funds:
- Security hardening and audits.
- Android UX and stability improvements.
- Cross-platform consistency and documentation maintenance.

---

## Developer Notes

### Project layers (current code layout)

- `takagi/ru/monica/ui`: Compose screens and components.
- `takagi/ru/monica/data`: Room entities, DAO, database migrations.
- `takagi/ru/monica/repository`: data access wrappers.
- `takagi/ru/monica/security`: encryption, key handling, authentication-related logic.
- `takagi/ru/monica/bitwarden`: API, crypto, mapper, sync, and viewmodel logic.
- `takagi/ru/monica/autofill_ng`: Autofill services and flows.
- `takagi/ru/monica/passkey`: Android 14+ credential provider implementation.
- `takagi/ru/monica/workers`: background tasks such as automatic WebDAV backup.
- `mdbx`: Rust workspace and client integration documents for Monica's MDBX local database format.

### Main components (verifiable in source)

- Android UI: Jetpack Compose, Material 3 Expressive, Navigation Compose.
- Data and state: Room, DataStore Preferences, ViewModel.
- Security: Android Keystore, EncryptedSharedPreferences, BiometricPrompt.
- Network and protocol: Retrofit, OkHttp, Kotlinx Serialization.
- Sync and ecosystem: sardine-android (WebDAV), kotpass (KeePass), Bitwarden API integration.
- Async and jobs: Coroutines, Flow, WorkManager.
- Additional capabilities: CameraX + ZXing (QR scan), Credentials API (Passkey).

### Build and contribution

The table describes the current development checkout. It does not imply that these versions are in the latest published APK.

| Component | Current source configuration |
| --- | --- |
| Build JDK / Java and Kotlin bytecode | JDK 17 / target 11 |
| Android SDK | compile 37 · target 34 · minimum 26 (Android 8.0) |
| Gradle Wrapper / Android Gradle Plugin | 9.7.0 / 9.3.1 |
| Kotlin / KSP | 2.4.21 / 2.3.13 |
| Compose BOM | 2026.09.00 |
| Material 3 Expressive | 1.5.0-rc01, pinned separately from the BOM |
| Room / Navigation Compose | 2.8.5 / 2.10.2 |
| Lifecycle Runtime and ViewModel | 2.11.0 |
| DataStore / WorkManager / CameraX | 1.3.0 / 2.12.0 / 1.6.2 |

Use an Android Studio version compatible with the configured AGP, or the Gradle Wrapper from the command line. Install SDK Platform 37, an Android NDK, Rust, and `cargo-ndk`; JNI libraries are built from source.

Version sources: [version catalog](Monica%20for%20Android/gradle/libs.versions.toml), [Gradle Wrapper](Monica%20for%20Android/gradle/wrapper/gradle-wrapper.properties), and [app build configuration](Monica%20for%20Android/app/build.gradle). Setup details: [Android build guide](Monica%20for%20Android/README.md).

```bash
cd "Monica for Android"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
# Prepare the application and instrumentation APKs for an x86_64 emulator:
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86TestAbi
```

Run these commands from the Android project directory after entering it from the repository root. In Windows PowerShell, use `./gradlew.bat`. Default APK builds target ARM; the last command adds x86_64 and prepares test APKs without running device tests.

Contributions via Issues and PRs are welcome.

---

## Acknowledgments

Monica's design, compatibility work, and several feature directions have been inspired and supported by the following excellent open-source projects and software:

- [Keyguard](https://github.com/AChep/keyguard-app) - reference for Android password manager interaction design and UX.
- [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer) - inspiration for the Android vault scrollbar style.
- [Bitwarden](https://bitwarden.com/) - an important reference for the open-source password management ecosystem, vault model, and sync capabilities.
- [KeePass](https://keepass.info/) - a foundational influence for the local vault philosophy and `.kdbx` ecosystem compatibility.
- [Stratum Auth](https://github.com/stratumauth/app) - reference for authenticator experience, icon resources, and related compatibility support.
- [Steam Desktop Authenticator](https://github.com/Jessecar96/SteamDesktopAuthenticator) - reference for Steam maFile format, Steam Guard, and trade confirmation compatibility.
- [steamguard-cli](https://github.com/dyc3/steamguard-cli) - reference for Steam Guard login, authenticator transfer, and confirmation protocol implementation.
- [AnotherVaporAuth](https://github.com/freefrank/AnotherVaporAuth) - reference for Steam mobile authenticator, login approval, and confirmation UX.

---

## Friend Links

- [Linux.do](https://linux.do)
- [TinadecOffice](https://github.com/Tinadec/TinadecOffice)

---

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=Monica-Pass/Monica&type=Date)](https://star-history.com/#Monica-Pass/Monica&Date)

---

## Contributors

![Contributors](.github/assets/contributor-flag.svg)

---

## Community and support

- Telegram group: [Join the Monica community](https://t.me/+IZUDLL-vWOA1Y2U1)

---

## License

Copyright (c) 2025 JoyinJoester

Monica is released under [GNU General Public License v3.0](LICENSE).

## Third-Party Icon Attribution

- This project locally bundles icon assets from the [Stratum Auth app](https://github.com/stratumauth/app) (version [v1.4.0](https://github.com/stratumauth/app/releases/tag/v1.4.0), directories [icons](https://github.com/stratumauth/app/tree/v1.4.0/icons) / [extraicons](https://github.com/stratumauth/app/tree/v1.4.0/extraicons), GPL-3.0).
- Bank card/payment icon source: [SVG Credit Card & Payment Icons](https://github.com/aaronfagan/svg-credit-card-payment-icons) (Apache-2.0).
- Brand names and logos remain the property of their respective owners.

## F-Droid contact

For F-Droid metadata or packaging issues, contact: joyin8888@foxmail.com



### Shizuku default password manager / 默认密码管理器

The Android 14+ helper in developer and autofill settings adapts the secure-settings transaction logic from [cr-zhichen/password-manager-switch](https://github.com/cr-zhichen/password-manager-switch) (MIT, copyright 2026 cr-zhichen). It targets the installed Monica edition for the current Android user, verifies writes, and supports restoring the previous configuration. The original MIT notice is included in [MIT notice](Monica%20for%20Android/app/src/main/assets/licenses/password-manager-switch.txt).

开发者设置与自动填充设置中的 Shizuku 入口参考上述原项目（MIT），用于 HyperOS 隐藏第三方密码管理器设置入口的情况。仅设置当前 Monica 版本，不导入原项目的自更新等功能；系统设置读回成功不代表所有 HyperOS 通行密钥流程均已验证。

On multi-user devices, Shizuku may also require Monica to be installed for the owner user before starting the helper for a secondary user. Only the calling user settings are changed.
多用户设备上，Shizuku 可能需要主用户也安装 Monica 才能启动副用户的辅助服务；实际更改仍限定于调用用户。

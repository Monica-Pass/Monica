# Monica Локальное хранилище паролей

<div align="center">

[中文](README.md) | [English](README_EN.md) | [日本語](README_JA.md) | [Tiếng Việt](README_VI.md) | **Русский** | [黑羽川](彩蛋语言备份/README_Nya.md)

<img src="image/themepng.png" alt="Monica App Icon" width="500" />

<p><strong>Локально‑ориентированное хранилище паролей, объединяющее Bitwarden и KeePass</strong></p>
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

Monica — это локальное хранилище паролей, которое объединяет **Bitwarden** и **KeePass**.
Проект построен вокруг принципа local-first и помогает управлять паролями, 2FA, защищенными заметками и конфиденциальными вложениями на устройствах Android.

Сайт: https://monica-pass.github.io/MonicaDocs/

---

## Пользователю прежде всего

### Для кого Monica
- Для тех, кто хочет локальное управление паролями вместо полностью облачного хранения.
- Для пользователей, которые работают и с Bitwarden, и с KeePass (`.kdbx`).
- Для тех, кому нужно автозаполнение в приложениях Android и мобильных браузерах.

### Что вы получаете
- Локальный зашифрованный vault для логинов, карт, персональных данных, заметок и вложений.
- Двойную экосистему: на Android доступна интеграция Bitwarden API/синхронизации и чтение/запись KeePass (`.kdbx`).
- Опциональную синхронизацию и резервное копирование через собственную инфраструктуру WebDAV.
- Встроенное управление TOTP в одном приложении.

### Локальный формат базы данных MDBX
MDBX — это разрабатываемый Monica локально-ориентированный зашифрованный формат vault. Это не просто таблица паролей: формат проектируется вокруг вложенных папок, вложений, истории commit, обнаружения конфликтов, удаления через tombstone, восстановления из snapshot и режимов безопасности Tiga.

Если вы хотите подключить MDBX в другом клиенте, начните с [MDBX workspace README](mdbx/README.en.md) и [MDBX client integration guide](mdbx/CLIENT_INTEGRATION_GUIDE.md). Полная спецификация формата находится в [mdbx/docs](mdbx/docs/README.md).

### Быстрая установка

1. Скачайте последний APK из [Releases](https://github.com/Monica-Pass/Monica/releases).
2. Установите на Android 8.0+ и задайте мастер‑пароль.

### Известное ограничение
- Из-за ограничений совместимости Monica for Android сейчас не может создавать passkeys на некоторых устройствах Xiaomi HyperOS. Можно попробовать модуль [HyperMonica](https://github.com/Wuming155/HyperMonica) от 酷 U.

---

## Акцент на Android

### Ключевые возможности
- Локальный vault для хранения учетных данных.
- Сводный импорт/интеграция с KeePass и Bitwarden.
- Быстрый поиск по заголовку, домену и тегам.
- Разблокировка с помощью системной биометрии Android.
- Единое хранение и генерация TOTP.

### Детали реализации
- UI: Jetpack Compose + Material 3 + Navigation Compose.
- Data: Room (`PasswordDatabase`) + DAO + Repository.
- Concurrency: Kotlin Coroutines + Flow.
- Зависимости: Repository и Factory собираются вручную, необходимые объекты передаются явно.
- Security: Android Keystore, EncryptedSharedPreferences, BiometricPrompt.
- Фоновые задачи: WorkManager (`AutoBackupWorker`) для автоматического WebDAV‑бэкапа.
- Протоколы и интеграции: Retrofit + OkHttp (Bitwarden API), kotpass (KeePass), sardine-android (WebDAV).

### Модель безопасности
- Локальное шифрование полей, которыми управляет `SecurityManager`, использует AES-256-GCM.
- В локальных путях PBKDF2-HMAC-SHA256 используется для проверки мастер-пароля и выработки ключа обёртывания MDK; само обёртывание MDK выполняется AES-GCM.
- Bitwarden, KeePass/KDBX и MDBX следуют собственным форматам и правилам шифрования/KDF. Это описание не означает единый алгоритм шифрования всей базы или один KDF для всех хранилищ.
- Локальная защита: хэш мастер‑пароля и настройки безопасности управляются локально.
- Сетевой периметр: приложение запрашивает сетевые разрешения в основном для интеграции Bitwarden и WebDAV синхронизации/бэкапа.

---

## Поддержка

Если Monica помогает в работе, рассмотрите поддержку разработки и улучшений безопасности.

<div align="center">
<img src="image/support_author.jpg" alt="Support Monica" width="320"/>
<br/>
<sub>Сканируйте WeChat или Alipay</sub>
</div>

<br/>

<p align="center">
  <a href="https://www.paypal.com/ncp/payment/BHSYWK73CA8FW">
    <img src="https://img.shields.io/badge/PayPal-Support%20Monica-FFD140?style=for-the-badge&amp;logo=paypal&amp;logoColor=003087" alt="Поддержать Monica через PayPal" />
  </a>
  <a href="https://liberapay.com/JoyinJoester">
    <img src="https://img.shields.io/badge/Liberapay-EUR-F6C915?style=for-the-badge&amp;logo=liberapay&amp;logoColor=black" alt="Liberapay · EUR (€)" />
  </a>
</p>

Поддержка в первую очередь идет на:
- Усиление безопасности и аудиты.
- Улучшение UX и стабильности Android.
- Кроссплатформенную согласованность и поддержку документации.

---

## Заметки для разработчиков

### Слои проекта (текущая структура кода)

Пути пакетов Kotlin/Java ниже указаны относительно `Monica for Android/app/src/main/java/`.

- `takagi/ru/monica/ui`: экраны и компоненты Compose.
- `takagi/ru/monica/data`: сущности Room, DAO, миграции БД.
- `takagi/ru/monica/repository`: обертки доступа к данным.
- `takagi/ru/monica/security`: шифрование, ключи и логика аутентификации.
- `takagi/ru/monica/bitwarden`: API, криптография, маппинг, синхронизация и логика ViewModel.
- `takagi/ru/monica/autofill_ng`: сервисы и потоки автозаполнения.
- `takagi/ru/monica/passkey`: реализация Credential Provider для Android 14+.
- `takagi/ru/monica/workers`: фоновые задачи, например WebDAV‑бэкап.
- `mdbx`: Rust workspace и документы интеграции клиентов для локального формата базы данных Monica MDBX.

### Используемые зрелые компоненты (проверяемо в репозитории)
- Android UI: Jetpack Compose, Material 3 Expressive, Navigation Compose `2.10.2`.
- Данные и состояние: Room `2.8.5`, DataStore Preferences `1.3.0`, Lifecycle ViewModel `2.11.0`.
- Security: Android Keystore, EncryptedSharedPreferences, BiometricPrompt.
- Network and protocol: Retrofit, OkHttp, Kotlinx Serialization.
- Sync and ecosystem: sardine-android (WebDAV), kotpass (KeePass), Bitwarden API integration.
- Асинхронность и задачи: Coroutines, Flow, WorkManager `2.12.0`.
- Дополнительные возможности: CameraX `1.6.2` + ZXing (сканирование QR), Credentials API (Passkey).

### Сборка и вклад

Ниже приведена конфигурация разработки текущего checkout. Она не обозначает уже выпущенную версию или завершенную проверку на всех устройствах.

| Компонент | Текущая конфигурация |
| --- | --- |
| Gradle Wrapper / Android Gradle Plugin | `9.7.0` / `9.3.1` |
| Kotlin / KSP | `2.4.21` / `2.3.13` |
| Compose BOM | `2026.09.00` |
| Material 3 Expressive | Отдельно закреплена версия `1.5.0-rc01`, независимо от BOM |
| Android SDK | `compileSdk 37`, `targetSdk 34`, `minSdk 26` (Android 8.0+) |
| JDK для сборки / целевой байткод Java и Kotlin | `17` / `11` |

- Используйте Android Studio с поддержкой этих версий AGP и SDK.
- Источники конфигурации: [version catalog](Monica%20for%20Android/gradle/libs.versions.toml), [app/build.gradle](Monica%20for%20Android/app/build.gradle), [Gradle Wrapper](Monica%20for%20Android/gradle/wrapper/gradle-wrapper.properties).
- Проект Android находится в `Monica for Android/`. В Windows PowerShell замените `./gradlew` на `./gradlew.bat`.

```bash
cd "Monica for Android"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

- Требования к SDK, NDK и Rust определяются текущими настройками Gradle и CI.
- Вклад через Issues и PR приветствуется.

---

## Благодарности

Дизайн Monica, адаптация совместимости и ряд функциональных направлений вдохновлены и поддержаны следующими открытыми проектами и программами:

- [Keyguard](https://github.com/AChep/keyguard-app) — референс по взаимодействию и UX Android‑менеджера паролей.
- [PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer) — образец оформления полосы прокрутки хранилища паролей в Android‑версии.
- [Bitwarden](https://bitwarden.com/) — важный ориентир по open-source экосистеме управления паролями, модели vault и синхронизации.
- [KeePass](https://keepass.info/) — основа философии локального хранилища и совместимости с `.kdbx`.
- [Stratum Auth](https://github.com/stratumauth/app) — референс по опыту аутентификатора, иконкам и связанным аспектам совместимости.
- [Steam Desktop Authenticator](https://github.com/Jessecar96/SteamDesktopAuthenticator) — референс по формату Steam maFile, Steam Guard и совместимости подтверждений обмена.
- [steamguard-cli](https://github.com/dyc3/steamguard-cli) — референс по Steam Guard login, переносу аутентификатора и реализации протокола подтверждений.
- [AnotherVaporAuth](https://github.com/freefrank/AnotherVaporAuth) — референс по мобильному Steam-аутентификатору, подтверждению входа и UX подтверждений.

---

## Дружественные ссылки

- [Linux.do](https://linux.do)
- [TinadecOffice](https://github.com/Tinadec/TinadecOffice)

---

## История звезд

[![Star History Chart](https://api.star-history.com/svg?repos=Monica-Pass/Monica&type=Date)](https://star-history.com/#Monica-Pass/Monica&Date)

---

## Участники

![Участники](.github/assets/contributor-flag.svg)

---

## Сообщество и поддержка

- Группа Telegram: [войти в сообщество Monica](https://t.me/+IZUDLL-vWOA1Y2U1)

---

## Лицензия

Copyright (c) 2025 JoyinJoester

Monica распространяется по лицензии [GNU General Public License v3.0](LICENSE).

## Указание сторонних иконок

- Проект локально включает ресурсы иконок из [Stratum Auth app](https://github.com/stratumauth/app) (версия [v1.4.0](https://github.com/stratumauth/app/releases/tag/v1.4.0), каталоги [icons](https://github.com/stratumauth/app/tree/v1.4.0/icons) / [extraicons](https://github.com/stratumauth/app/tree/v1.4.0/extraicons), GPL-3.0).
- Источник иконок банковских/платежных карт: [SVG Credit Card & Payment Icons](https://github.com/aaronfagan/svg-credit-card-payment-icons) (Apache-2.0).
- Названия брендов и логотипы принадлежат соответствующим правообладателям.

## F-Droid contact

For F-Droid metadata or packaging issues, contact: joyin8888@foxmail.com


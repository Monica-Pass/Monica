# Arabic, RTL and technical identifiers — Android 1.0.331

2026-11-26. Ordinary Android and canonical F-Droid; unreleased. Version codes remain 12 and 38.

## Changes

- Added العربية with 7,376 resource keys and 847 formatted messages per edition, including all six Arabic plural categories. Appended the language enum; existing persisted identifiers and order are retained.
- Mirrored navigation arrows, scrollbar geometry and Arabic UI containers. Language selection, narrow navigation, mixed credentials, setup, settings and the system keyboard are covered. Latin QWERTY/PIN layouts and password/OTP/URI content preserve their natural order.
- Fixed locale-dependent OTP decimal rendering. The old implementation produced `٧٥٥٢٢٤` for RFC HOTP counter 0 under Arabic; output is now the required ASCII `755224`. Padding changed, not the OTP algorithm or stored secrets.
- Preserved the page density and layout direction in the recovery confirmation dialog slots. Confirmation, cancellation, authentication and recovery operations are unchanged.
- Reviewed 21 locale directories per edition for altered protocol prefixes, extensions, algorithms, brands and Auto-Type templates. Corrected 35 values per edition across Arabic, French, Indonesian, Japanese, Korean, Russian, Turkish and Vietnamese. Examples include `maFichier`/`maDosyası` → `maFile`, translated Steam/Proton Pass labels, and Argon2id. No additional corrupted protocol prefixes remained after the Arabic `otpauth://totp/...` correction. Normal localized prose such as “fichier CSV” was retained.
- Added source-level protocol/name guards and compiled-resource checks for 20 sensitive identifiers across 22 runtime locale tags. Updated Indonesian/Turkish resource contracts only for corrected public strings.

## Verification

| Check | Ordinary Android | F-Droid |
| --- | --- | --- |
| Final build, seconds | 81.692 | 78.158 |
| JVM methods | 19 passed, 0 skipped | 19 passed, 0 skipped |
| MuMu methods | 33 passed | 33 passed |
| Instrumentation runner, seconds | 32.835 | 31.795 |
| Frozen build inputs | 6667 | 6668 |

The final driver assembles the x86_64 Debug app/test packages and runs `LocaleResourceCoverageTest`, `OtpLocaleInvariantTest`, `VaultV2SortingTest`, `VaultV2ScrollLookupTest` and `StartupLanguageCacheTest`. The last iteration changes only the IME test interaction; production build tasks are up to date from the preceding successful 88.124-second ordinary and 149.675-second F-Droid builds.

Device coverage includes all compiled Arabic resources and format arguments; regional Arabic selection; persistence/cache reads; all six plurals; language selection and quick setup; settings save/back; 320/360dp navigation and up to 1.6 font scale; credential editing; RTL slider direction; recovery warning readability/cancel; packaged locale fallbacks; all protected technical identifiers; and the full Indonesian/Turkish resource contracts.

Date checks exercise five nonalphabetical sorting modes in Arabic, French and English with synthetic records, the real sort sheet, saved settings, production sorting/section code and real scrollbar. The existing locale-independent section key remains intact. This is component integration, not an end-to-end populated production MainActivity vault.

The actual separate-process system keyboard is enabled only in the synthetic test user. Arabic → English → Arabic changes are checked while open, including toolbar direction, retained QWERTY order and an injected touchscreen tap whose result must be exactly `q` in the host EditText. The original input method is restored afterward.

Source hashes match the tested build inputs. 225 changed shared app paths are byte-identical. Remaining differences are reviewed edition-specific build settings, screens, provider/import resources and resource contracts, including existing QR-resource placement. F-Droid metadata and all six release-checker script tests pass.

## Failures and corrections retained

- The original OTP implementation fails the Arabic RFC-vector test as expected; the baseline XML is retained.
- The first ordinary device run passed 21/26. It exposed dialog density loss and incorrect test premises for slider start position, localized plural digits, Arabic packaging and existing Classical Chinese strings. The first screenshots also contained a MuMu store advertisement, so they are not visual/input acceptance evidence. The store was disabled only for the disposable test user.
- Later ordinary runs passed 26/27 and 32/33. The first IME assertion referenced a removed toolbar label. Subsequent language-switch failures were traced to cached UiAutomation accessibility nodes: temporary receiver/composition probes and actual screenshots showed English resources, English “Space” and LTR geometry while the test still read Arabic. Refreshing nodes resolves the stale observation; temporary probes and an unnecessary resource override were removed.
- An added accessibility `ACTION_CLICK` returned false. The final test uses real touchscreen down/up at the observed key bounds and verifies the host editor text. Accessibility action support is not claimed by this run.
- Two new JVM-checker iterations initially failed because the test assumed English plural categories and treated legal Android resource enclosing quotes as displayed text. The corrected source checker handles localized plural categories and optional enclosing quotes; exact compiled-string assertions remain.
- The second temporary diagnostic build completed but was not installed once screenshots had already identified the observation-cache problem. All earlier failures remain in local task evidence; final passing counts do not imply every earlier batch passed.

## Limits and retention

Only synthetic data on MuMu Android 15/API35 x86_64 Debug was used. No native-speaker certification, all-screen visual audit, physical-device/OEM, R8 Release or old-Release upgrade validation is claimed. This change does not modify storage, KDF, authentication or migration algorithms.

Representative unobstructed native pages and Arabic/English system-keyboard screenshots were inspected. The secure recovery dialog deliberately renders black in screenshots due to FLAG_SECURE; its density, text bounds and cancel behavior were checked through Compose semantics, not visual inspection. Screenshots and the editable local Canvas remain in ignored design directories.

Only the task's isolated packages and synthetic user were removed. Original users, formal Monica package metadata, density, configuration and data disks were preserved; the original user was restored. MuMu remains running and was not stopped/restarted by this task.

The prior verified changes were checkpointed before this work: ordinary `dc0c100966141d20d4d9fd740438304e1cadde95`, F-Droid `730ad10aefa23d64f7a43dc7ace3cab176853cd7`, with exact remote SHAs verified. Current Arabic/RTL/technical-name changes remain local for review. No tag, publication or APK delivery was performed.

## 中文记录

新增阿拉伯语及 RTL 适配，修复阿拉伯语环境下 OTP 输出本地数字的问题，并补上恢复确认弹窗的大字号继承。两版各修正 35 处技术名称误译；`otpauth://` 等协议、Auto-Type 标记和格式名称已增加源码与实际安装包检查。普通版和 F-Droid 最终各通过 19 项 JVM、33 项 MuMu 设备测试，包含法语／阿拉伯语日期排序与真实输入法切换和触摸输入。前序失败、测试观察缓存问题及未验证范围如上保留，不把模拟器结果等同于真机或母语审校。模拟器继续运行，原用户及应用数据保留。

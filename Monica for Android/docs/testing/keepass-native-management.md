# KeePass native compatibility and management

Scope: native KDBX data visibility, lossless entry management and failure handling. The reporter database was not supplied. All fixtures contain synthetic data; the large fixture reproduces five authenticators among 406 native entries. This is not a claim of exhaustive KeePassDX feature equivalence.

| Capability | Verification |
| --- | --- |
| KeePass native TimeOtp/HmacOtp, URI, KeeOtp, Tray TOTP and Steam | Codec tests with RFC 4226 / 6238 expected codes; actual encrypted large-database projection |
| Unknown Monica/passkey metadata, custom-only entries, empty fields | Native compatibility device tests; fallback remains accessible |
| Exact custom-field names, protection and raw references | Field patch, reference resolver and KDBX 3/4 save/reopen tests; independent PyKeePass reads |
| XML whitespace, CDATA, empty plugin values and tags | Encrypted KDBX 3/4 round trips; whitespace-only and CRLF preservation; DTD/entity rejection |
| Login fields preserved during OTP edits; UUID and vault isolation | Actual service writes plus projection/display identity regressions |
| Native live OTP and persisted HOTP next code | Production manager UI, busy/failure/read-only cases; original encoding preserved |
| Tags and expiration with date/time selection | Production editor UI and encrypted save/reopen; atomic field/property history and conflict tests |
| Fields, icon, properties and attachments saved together | Existing/new entry rollback/retry, stale revision and read-only device tests |
| Group moves, recycle bin, attachment rename/removal and history restoration | Device management workflow plus existing native mutation tests |
| Master password and key file change | Actual rekey/reopen, rejecting old or incomplete credentials |
| Native search, sort, custom icons, auto-type, database settings and merge | Existing focused JVM suites; management page navigation device suite |
| Ordinary login edits remain visible as passwords | Production manager edit/save/reload asserts PASSWORD, no template marker and a surviving password projection |

Final main-app verification completed on 2026-09-26:

| Run | Result |
| --- | --- |
| Debug app and AndroidTest packaging | Passed; final installed build `1.0.314-26092612-04` |
| Focused JVM suite | **310 passed**, 71 suites, zero failures/errors/skips |
| `KeePassNativeCompatibilityInstrumentedTest` | **12 passed** |
| `KeePassManagementUiTest` | **14 passed** |
| `KeePassConflictMergeInstrumentedTest` | **10 passed** |
| Combined device run | **36 passed**, 48.107 seconds, shared `Monica_Issue136_API_32` AVD |
| Independent PyKeePass 4.1.1.post1 | Both final Android-written KDBX 3 and KDBX 4 files passed |
| Native rendering review | Detail, editor properties, HOTP write failure and 1.8x font-scale detail screenshots inspected |

The encrypted 406-entry fixture reproduced the reported five-to-two authenticator omission before the fixes and returns all five afterward. The independent reader checks exact field names and values (including spaces, empty values and CRLF), protected passwords, raw references, tags, expiration, custom-icon references, attachment bytes and the original history. Device tests also exercise group/recycle-bin operations, attachment updates, credential/key-file changes, failed saves, read-only handling and stale edits. MockWebServer-backed WebDAV tests cover conditional writes, conflicting local/remote edits, recovery copies and pending-queue settlement.

The final JVM selection was:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest `
  --tests 'takagi.ru.monica.keepass.*' `
  --tests 'takagi.ru.monica.util.KeePass*' `
  --tests 'takagi.ru.monica.ui.screens.KeePass*' `
  --tests 'takagi.ru.monica.viewmodel.*Totp*' `
  --tests 'takagi.ru.monica.ui.*Totp*'
```

The device run selected these three classes through `takagi.ru.monica.test/androidx.test.runner.AndroidJUnitRunner`. Local reproduction options, final logs and synthetic encrypted artifacts are retained under the main repository's `.codex-tasks/20260925-kdbx-native-compatibility/raw/`:

- `verify_main_kdbx.ps1` and `management-final-build-3.log`.
- `final-jvm-results.json` and `management-device-final-2.log`.
- `check_roundtrip_pykeepass.py`, `pykeepass-roundtrip-results.json` and `keepass-native-roundtrip-v{3,4}.kdbx`.

An additional broader run included `MultiPasswordSaveRegressionGuardTest`: 355/366 passed, with 11 failing source-text assertions. Running the same compiled guard against the pre-task source baseline reproduced all 11 failures (plus the newly added helper-wiring assertion, absent from that baseline). These are recorded in `management-build-4.log` and `preexisting-source-guards-baseline.log`; the whole repository suite is not claimed to be green. The old remote-write assertion expects a previous call shape, while the production conditional-upload path is exercised by the passing device conflict tests.

The reporter's original database and live cloud-provider accounts were unavailable. Testing uses synthetic encrypted fixtures and MockWebServer, not a claim of every third-party plugin or KeePassDX feature being covered. F-Droid receives the same task source changes, dependency declaration, release notes and design/test records; its build and functional validation remain explicitly deferred by the user. Earlier unrelated variant edits are preserved.

Design: [editable M3E Canvas and rendered draft](../design/keepass-native-management-m3e.md).
References: [KeePass placeholders](https://keepass.info/help/base/placeholders.html), [field references](https://keepass.info/help/base/fieldrefs.html), and KeePassDX OtpEntryFields / OtpElement source. Local custom fields use `{S:Name}`; `REF` target `O` is unsupported by KeePass itself and remains search-only.

# AI API Key verification — 2026-09-26

Implemented the password editor's **API Key** type for AI credentials: provider name, optional website, concealed key, optional API request URL and notes. The existing native MDBX API Token format remains unchanged.

Main build: **1.0.314-26092612-09**. `assembleDebug` and `assembleDebugAndroidTest` succeeded with the local offline plugin mapping. ARMv7, ARM64 and the opt-in x86_64 test APK were built; only x86_64 was exercised on the shared `Monica_Issue136_API_32` AVD. No APK was copied to a delivery directory.

## Checks

- **32 JVM tests passed:** API Key representation and URL validation (7), login autofill matching (10), existing API Token payload/metadata (12), native-token password-list integration (3).
- **8 API Key device tests passed:** password-page creation menu; create/read/copy/edit all five fields; hidden secrets and encrypted local storage; required/invalid input with dark mode and 1.5× text; clearing the draft on lock; real KeePass write/edit/close/reopen preserving protected third-party fields; MDBX2 metadata persistence and reopening; encrypted Bitwarden upload/download and clearing optional URLs; failed-target retry without duplicate copies. Multiple assertions share each test.
- **Existing native API Token editor regression passed:** database/category selection and no secret draft in saved instance state.
- Native rendering was inspected after the IME closed, including the creation menu, complete form, real password detail page, and dark mode at 1.5× font scale. The editor places Save above the keyboard and the body scrolls.

The database tests use synthetic temporary vaults. Bitwarden requests go to the test's local MockWebServer and exercise the production cipher upload/download and encryption code; no live account or AI endpoint was contacted. The Android 32 clipboard test checks the copied value. The existing Android 13+ sensitive-preview flag remains provided by `ClipboardUtils`.

## Problems reproduced and fixed

1. MDBX2 password saves wrote native metadata before replacing Room custom fields. The save path now commits the final fields before reporting success, including removals.
2. Bitwarden's old non-empty fallback restored an API Key's cleared website. API Key synchronization now accepts empty URLs and recognizes the type when updating older password projections.
3. A failed second destination could leave a successful first copy. The API Key editor retains its draft and stable replica identity across retries and checks the number of completed destinations.

Initial and final test logs are retained under `.codex-tasks/20260926-ai-api-key/raw/` at the main repository root. A first test-source compile error and two test-harness assumptions (locale and Android 13 clipboard extras) were corrected before the final successful run. This is focused feature validation, not a claim that the entire repository test suite passed.

## Design and synchronization

See [editable M3E Canvas design and native screenshots](../design/ai-api-key-m3e.md). English, Simplified Chinese and Traditional Chinese strings are included; other locales use the default strings.

The task's source/test/resource changes and documentation are synchronized to F-Droid while preserving its existing differences. Both release notes and F-Droid Fastlane `21.txt` are updated (English 485 characters, Chinese 410). Historical `20.txt` is unchanged. **F-Droid was not built or functionally tested**, as requested.

The final baseline comparison confirmed all 30 source/test/resource changes were synchronized, with the existing F-Droid differences preserved and no added trailing whitespace or merge markers. Historical Fastlane `20.txt` files remain byte-for-byte unchanged.

The shared AVD was initially stopped and launched for this task, then stopped after validation. Synthetic test data and device screenshots were cleaned up, and the task-owned browser and Gradle/Kotlin daemons were closed. The AVD's existing installations, configuration and data disk are retained.

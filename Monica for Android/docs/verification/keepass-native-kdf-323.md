# KeePass native KDF and save latency

Date: 2026-10-30. Ordinary and canonical F-Droid development version 1.0.323, unreleased.

## Trigger and implementation

The developer reports that clicking Save in Monica's ordinary KDBX editor still spins before returning to the list. The supplied recording shows about three seconds between the click and return. The developer is unsure whether the file is local or cloud connected. A preceding isolated 300-entry fixture reproduced a 2,249 ms direct save; its raw results are retained in `keepass-native-kdf-323-baseline.txt`.

The KDBX parser/serializer remains Kotpass 0.10.0, vendored from commit `4b6455872fb882b74787c9ba18dd7024a9dde69d`. All 104 upstream source files and their notices are retained. Only `cryptography/KeyTransform.kt` changes; `KdfTransform.kt` adds optional providers scoped to a synchronous codec call on the current worker. All original service encode/decode entry points use the shared wrapper, covering local files and cloud copies.

Argon2d and Argon2id preserve the variant, v1.0/v1.3, salt, memory, iterations and lane count. AES-KDF preserves its seed and unsigned round count for KDBX3 and KDBX4. Unsupported versions, secret/associated data, out-of-range parameters and unavailable native code use the original implementation. No transformed keys are cached. Borrowed JVM inputs, JNI copies and Argon2 memory are cleared after use. No format, credential policy, KDF cost, file verification, source revision check, backup/commit or remote conflict behavior changes. The editor continues waiting for a successful commit before reporting success.

## Current reference implementations

Fresh separate checkouts were inspected at KeePassDX `25493ea30139adab14d937602f28d5243d3f9ecc` (release/4.5.6 merge) and KeePass2Android `8b60a72fc8eb23e8580df053a49ca813a686c838`. Both route Argon2 and AES-KDF through native transforms. KeePassDX retains temporary-file write protection in its save flow. These references informed the approach; their code was not copied into Monica. Kotpass's MIT license and embedded Bouncy Castle notices remain in the local module, and Rust dependencies build from the checked-in lockfile.

## Validation

Both ordinary and canonical F-Droid main Debug APKs and test APKs built successfully. Their local Kotpass modules each passed 7 JVM tests; the Rust crypto crate passed 6 tests. The module compiles with JDK 17 to Java/Kotlin 11 bytecode. The ordinary tested main APK is `1.0.323-26103012-20`; F-Droid remains `1.0.323`, code 30. The three Android JNI libraries were built from source in Rust release mode; both KeePass JNI exports are present for arm64-v8a, armeabi-v7a and x86_64. The new facade has an R8 keep rule in both flavors. Android minified Release APKs were not rebuilt or tested in this round.

The final API32 suites each passed all 72 tests across 11 KeePass classes: ordinary 350.512 s, F-Droid 371.911 s. Coverage includes the standard Monica editor, a 300-entry delete, original-vault UUID ownership, locked/conflicting sources, attachments, custom icons, unknown/protected fields, REF text, history and native compatibility. Native KDF checks cover 24 Argon2d/id variant/version/lane/uneven-memory combinations, AES-KDF for KDBX3/4, original/native byte-identical files, mutual decoding, damaged-file rejection, unsupported parameters and fallback.

The following measurements use the same public AVD. Paired encoding uses the same database, costs and random stream, alternates original/native order, and compares exact output bytes in every pair. Values are medians of three pairs. The paired Argon2d fixture uses v1.3, 32 MiB, 8 iterations and 2 lanes; AES-KDF uses 100,000 rounds. The 300-entry fixture retains that same Argon2d cost.

| Boundary (ms) | Ordinary | F-Droid |
| --- | ---: | ---: |
| Argon2d full encoding, original → native | 925 → 217 | 931 → 240 |
| AES full encoding, original → native | 531 → 9 | 617 → 3 |
| Native original-vault save and list | 489 | 603 |
| Direct 300-entry KDBX save | 405 | 522 |
| Cold reopen after save | 320 | 281 |
| List edit until published | 721 | 847 |

The preceding direct-save baseline was 2,249 ms. That earlier run and the final runs are not controlled paired whole-save measurements, so they do not establish a real-device improvement ratio. The paired codec measurements do establish lower native computation time at unchanged KDF costs. The legacy Room-based three-password benchmark still performs three commits (1,750 / 1,604 ms); it is distinct from the original-vault standard editor and is not evidence that every editor path writes once.

## Editor timing boundaries

The first final editor measurements were 2,953 / 4,258 ms. Those timers surrounded semantics lookup, enabled checks, `performClick` and Compose idle waits. They must not be described as the actual spinner duration. The final timing fixture invokes the same semantics action as `performClick` on the UI thread and records the monotonic timestamp immediately before the handler and inside `onNavigateBack`. It also records the synthetic provider's target-write-open time, asserts exactly one KDBX mutation commit, and retains saved-content, custom-icon, no-Room-index and lock-revocation checks. It does not return before a successful save or remove any source refresh/check.

The rebuilt timing fixtures passed on both packages during takeover: ordinary **34.449 s / 1 test**, F-Droid **40.047 s / 1 test**. These rerun an existing case and are not additional unique cases beyond the 72-test suites. No production source changed after those suites; the 14-file source/test manifest and both complete 109-file Kotpass modules were rechecked and match.

| Exact editor boundary (ms) | Ordinary | F-Droid |
| --- | ---: | ---: |
| Save handler to navigation callback | 4841 | 3591 |
| Handler to target write open | 2642 | 1863 |
| Target write open to navigation | 2199 | 1728 |
| Handler to test idle observation | 4914 | 3943 |

The preceding precise ordinary run was 4,552 ms; an extra ordinary run during takeover was 6,334 ms. That extra run installed F-Droid correctly but accidentally targeted the ordinary instrumentation package, so it is explicitly recorded as `ordinary-extra`, never as F-Droid evidence. The corrected F-Droid result above uses `takagi.ru.monica.fdroid.test`. The observed wait remains real and varies substantially on the shared emulator. Native codec acceleration is verified, but eliminating the reported spinner, instant save/return, parity with other clients, and real-device/cloud improvement are **not established**.

A separate ordinary ART sampling run (2 ms interval) passed the editor case in 50.342 s and measured handler-to-navigation at 2,591 ms. The sampled worker spent about 887 ms in encoding (329 ms sampled thread CPU, including 200 ms in the native KDF), 541 ms in the external-write safety path, and about 211 ms between mutation return and completion of the adapter refresh. These are approximate sampled boundaries, not an unprofiled benchmark or a controlled comparison. The observed save used the native provider on an IO worker; no repeated KDF invocation or multi-second main-thread computation was identified in that sampled save interval. File backup/revision checks and verified writes still take time. This evidence does not identify all remaining wall-clock delay and does not justify removing safety checks or reporting success before commit.

The sampling script's empty-process-ID handling and trace retrieval were corrected locally; the completed trace was retrieved after the remote file was flushed. The trace and analyzer stay in the workspace debug directory, outside the repository. No diagnostic hooks were added to production. Passing exact editor logs are `keepass-native-kdf-323-ordinary-editor-api32.txt` and `keepass-native-kdf-323-fdroid-editor-api32.txt`; the clearly labeled extra ordinary log is also retained. Both repositories passed `git diff --check`. Existing main/test build results, 72-test suites, Rust 6-test result and both Kotpass 7-test results were inspected rather than rerun without a code change.

## Fixture corrections and evidence

The first build exposed the local module's Java 17 bytecode versus the Android app's Java 11 target. The module now uses JDK 17 with Java/Kotlin bytecode target 11. The next test compilation exposed reversed positional AES constructor arguments; the fixture now uses named arguments. These corrections do not change production crypto behavior.

The initial encoded-content assertion compared in-memory millisecond timestamps with KDBX's whole-second timestamps. It now compares original-decoded content with native-decoded content while retaining exact-byte assertions. The first expanded ordinary suite passed 71/72; only the old delete UI fixture failed because it searched for a Room-ID row tag although the selected original vault uses UUID adapter IDs. The fixture now uses the actual `vault_item_password:` tags and does not create an artificial Room projection. Its separate rerun passed (76.015 s), followed by the complete ordinary 72-test rerun. The delete still selects all 300 records, requires exactly one mutation commit, checks all 300 original entries in the recycle bin and requires an empty Room password index. No production conflict or ownership rule was relaxed.

Passing device logs are `keepass-native-kdf-323-ordinary-api32.txt` and `keepass-native-kdf-323-fdroid-api32.txt`. `keepass-native-kdf-323-shared.txt` records matching shared-source SHA-256 values. All 104 upstream Kotpass source files were compared with the vendored module: only `KeyTransform.kt` differs. Both complete 109-file local modules match. The final builds preserve the original flavor differences and source-build policies.

All device fixtures are synthetic and isolated. Existing packages receive update installations; no application data is cleared. The already-running public `Monica_Issue136_API_32` AVD is reused. No physical device or live WebDAV/OneDrive account is used, so timings cannot promise instant saves, a real-device speedup ratio or cloud upload latency. No commit, push, tag, release or APK delivery is part of this work.

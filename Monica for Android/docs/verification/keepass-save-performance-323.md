# KeePass save and list performance — issue 172

Date: 2026-10-29. Development version 1.0.323, unreleased.

Latest follow-up: [incremental password projection and controlled editor comparison](keepass-incremental-save-323.md), including repeated Monica local/KDBX and official KeePassDX measurements. Historical measurements below remain unchanged.

The [report](https://github.com/Monica-Pass/Monica/issues/172#issuecomment-6460993665) says synchronization after editing takes a long time. The developer also identifies waiting after saving/returning to the list as the main symptom. No personal KDBX or live cloud-provider credentials were used for this investigation.

## Current implementation: Monica UI with original KDBX storage

The developer clarified that selecting a KDBX must retain Monica's ordinary password/library lists, detail screens and editing forms. The previous implementation switched these pages to `KeePassNativeManagerScreen`; that was a real UI substitution and has been removed. The separate KeePass management tool remains available through its existing route.

`KeePassMonicaPasswordStore` adapts the UUID-based KDBX model to Monica's screen models in memory. Its IDs are scoped to the database and UUID and must never be inserted into Room or written as compatibility ownership IDs. Selected-library password, secure-item and Passkey rows use this adapter. The all-databases overview retains the compatibility index. Saving one form, including multiple password values, uses one original-file mutation and returns without waiting for that overview projection.

Original UUIDs, protected fields, unknown custom fields, reference source text, attachments and history are preserved by the existing checked file mutations. Drafts retain their original revision; a list refresh cannot authorize a stale overwrite. Application/session locks remove native rows and revoke drafts. Group UUIDs take precedence over paths, with missing or ambiguous groups rejected. Monica's existing attachment controls receive in-memory original-vault metadata rather than requiring Room password/secure-item parents. Attachment changes made by the current form advance only that form's verified baseline. Moves into local/MDBX/Bitwarden storage create and verify target passwords and copy attachments before attempting source deletion; source-deletion failure retains the targets. Cross-storage copies encrypt native plaintext before inserting local/index rows, including literal secrets beginning with a Monica ciphertext prefix. Original-vault SSO/note links without destination identities cause the cross-storage copy/move to fail before any target write, keeping the source intact. Entries with original-vault history cannot be moved into those stores, because their complete KDBX history is not representable there: the operation rejects before target creation or source deletion. Copying remains available, and raw KDBX-to-KDBX management uses the existing UUID transfer path that preserves the original record.

The KDBX codec remains Kotpass 0.10.0. The 2026-10-29 UI work below did not accelerate its KDF. The subsequent 2026-10-30 work adds a scoped Rust/JNI Argon2/AES-KDF provider; see [native KDF verification](keepass-native-kdf-323.md). KDBX remains an encrypted file; encoding and remote upload/read-back verification still cost time. Existing KDF parameters, ownership protection, locks, backup/commit checks and remote conflict protection remain in place. Local synthetic tests do not establish a real-device or cloud-provider speedup ratio.

## Historical baseline measurements

The shared `Monica_Issue136_API_32` x86_64 AVD was reused. The local external-document fixture has 300 synthetic entries, Argon2d, 32 MiB, eight iterations and two lanes. The production service, main-list ViewModel and real encrypted file path were exercised.

| Stage | First baseline run |
| --- | ---: |
| Cold KDBX workspace load | 902 ms |
| Warm workspace read, average of three | 49 ms |
| First Room projection | 1,631 ms |
| Unchanged Room projection | 3,012 ms |
| Single main-list edit until its row is published | 919 ms |
| Direct KDBX save | 789 ms |
| Cold reopen after save | 864 ms |

The second baseline observed an unchanged projection of 3,363 ms. The grouped-password probe completed one three-password save in 5,249 ms with exactly three document commits; all other entries survived unchanged. These timings characterize this synthetic AVD scenario, not the reporter's phone or KeePassDX/KeePass2Android.

A separate direct KDF comparison used Argon2id v19, 32 MiB, eight iterations, two lanes and identical composite-key/salt bytes. Kotpass took 905/769/856 ms; the existing Rust KDF took 290/149/208 ms. All three output byte arrays matched. The medians are 856 ms and 208 ms. This demonstrates a KDF optimization opportunity, not an already-integrated native KDBX speedup.

## Retained overview-index optimization

The index refresh previously decrypted each existing password twice and encrypted every imported password, even when its plaintext was unchanged. The change reuses the first decryption result for comparison and retains unchanged ciphertext only when its protection is already current: MDK ciphertext, or C2 ciphertext while no master password is enabled. Legacy/plaintext/V2 values still take the encryption path; C2 ciphertext still upgrades after master-password enrollment. KDF parameters, KDBX encoding, source-revision checks, file backup/commit logic and remote verification were not changed.

The regression was written before the production change. A native notes-only edit followed by projection failed on the old implementation because it replaced the unchanged password ciphertext. The companion master-password migration test passed before the change. The optimized ordinary build passed all four performance/security cases plus fifteen existing KDBX compatibility cases: `OK (19 tests)`.

| Stage | Optimized index run |
| --- | ---: |
| Cold KDBX workspace load | 823 ms |
| Warm workspace average | 54 ms |
| First Room projection | 1,645 ms |
| Unchanged Room projection | 1,307 ms |
| Single compatibility edit until row publication | 822 ms |
| Direct KDBX save | 770 ms |
| Cold reopen after save | 794 ms |
| Three-password compatibility save | 2,480 ms / 3 commits |

The KDF in this run was 675/699/707 ms (Kotpass) and 161/165/220 ms (Rust), with equal output bytes. Host load varies, so the grouped-save time difference is not attributed to a reduction in file commits: it still commits three times. Selected native editing changes one KDBX entry in one commit and avoids Room projection in its return path. The underlying KDF and remote round-trip costs remain.

## Previous native-manager verification (superseded UI route)

The ordinary final build passed, and its current main and test packages were installed with `adb install -r` on `emulator-5582`. All 25 device cases passed (`Time: 84.961`, `OK (25 tests)`): four performance/security cases, fifteen existing KDBX compatibility cases and six new native-path cases.

`KeePassPasswordNativeInstrumentedTest` verifies the external-file native list with an empty overview index, a one-commit save with protected custom fields/history and unchanged other entries, stale-revision rejection, denied-write preservation, UUID folder scopes, resolved-reference favorite filtering, matching visibility for custom-only/mixed login records, and actual browser/detail/editor/save navigation and lock disposal. The locked editor is removed and the database remains closed until the test explicitly reopens it. No personal library is used.

The final ordinary run measured native save plus native list read at **984 ms**, with exactly one file commit and no Room password rows in that path. Its other measurements were cold workspace 1,031 ms, warm workspace 69 ms, initial projection 2,156 ms, unchanged projection 1,484 ms, compatibility edit until publication 1,078 ms, direct save 910 ms, cold reopen 812 ms, and grouped compatibility save 4,315 ms with three commits. Kotpass/Rust Argon2id runs were 925/864/1,009 ms and 257/166/169 ms, with equal keys. These are observations on the shared AVD, not throughput promises for a phone or a cloud provider.

The F-Droid final main/test build succeeded. Its first 25-case run passed all fifteen existing compatibility cases and all six native-path cases, but two compatibility-save benchmarks were blocked by the ownership guard. The shared test installation had a persisted `mdbx_database` password filter; the fixture's ViewModel restored it and added MDBX ownership while updating synthetic KeePass entries. This was fixture state contamination, not a reason to remove the production guard. The fixture now sets only its ViewModel's in-memory neutral filter on the main dispatcher before asynchronous restoration; it does not overwrite application settings or delete existing data.

After rebuilding only the test package, the complete F-Droid suite passed (`Time: 69.502`, `OK (25 tests)`). Native save plus native list read took **725 ms**, with one file commit and no Room password rows. Other observations were cold workspace 998 ms, warm workspace 61 ms, initial projection 1,765 ms, unchanged projection 1,322 ms, compatibility edit 917 ms, direct save 751 ms, cold reopen 726 ms and grouped compatibility save 2,320 ms / three commits. Kotpass/Rust Argon2id observations were 727/718/838 ms and 215/161/160 ms, with equal output bytes. The initial F-Droid native measurement was 854 ms; host load varies, so none of these observations establish a phone or cloud-provider performance ratio.

The ordinary test package was then rebuilt with the identical fixture correction. Its complete suite also passed (`Time: 167.735`, `OK (25 tests)`). This final repeat measured native save plus list read at **1,791 ms**, still exactly one commit with an empty Room password index. Other observations were cold workspace 2,283 ms, warm workspace 262 ms, initial projection 3,083 ms, unchanged projection 2,538 ms, compatibility edit 1,842 ms, direct save 2,436 ms, cold reopen 1,768 ms and grouped compatibility save 5,772 ms / three commits. Kotpass/Rust Argon2id observations were 3,646/2,558/1,263 ms and 2,150/478/347 ms, again with equal keys. The broad timing variation reinforces that commit counts, removed indexing dependencies and data-preservation assertions are the verified results; these runs do not establish a comparative speedup against another KeePass client.

The 12 shared production files and all three investigation/native-path test files have matching SHA-256 hashes between the ordinary tree and `.codex-worktrees/monica-fdroid-nearby-steam`; `MainActivity` keeps each flavor's existing code and receives the same small index-refresh listener patch. Both repositories passed `git diff --check`.

Raw build and device logs are retained locally under `.codex-tmp/kdbx-save-172/`. Fixtures are isolated, synthetic and cleaned by unique file/database/preference prefixes. The security migration test does not delete the application's default Keystore alias. No release/tag, commit, push or APK delivery is part of this investigation.

## Monica UI adapter verification

The initial standard-list regression reproduced the mismatch: the ordinary Monica list had zero entries while the selected synthetic KDBX contained 300. The first adapter suite passed nine device tests, including the real `AddEditPasswordScreen` save/return/lock flow. The fixture explicitly supplies `LocalKeePassViewModel` so the production ownership checks remain active.

The ordinary adapter plus existing regression run passed 48 tests in 277.089 seconds. Three further cases cover encrypted batch copying, same-name/whitespace attachment uploads through the real executor, and rejection of a linked-entry move before either source deletion or target creation. A final case checks refusal to move original history into an unsupported destination without changing either store. The previous 25-test native-manager results above establish only the earlier implementation's behavior and are not used to claim this adapter is complete.

The ordinary main/test build succeeded (`kdbx-monica-ui-verified-build-2.log`). Its preceding device suite passed **51 tests in 281.228 seconds** (`kdbx-monica-ui-verified-device.log`). This includes 16 Monica-adapter/UI cases, four save/security cases, fifteen compatibility cases, ten conflict cases and six independent native-management cases. The standard form and grouped-password tests assert one file commit, original UUID/fields/history/attachment preservation and an empty selected-vault Room password index. The real standard editor test checks save/return and lock revocation. Current-source results below include the additional history-protection case.

The new prefix-copy regression initially failed: an original-vault literal password starting with `C2|` was encrypted correctly for its local copy, but the old display decoder treated that decrypted literal as another encryption layer and returned an unreadable state. The decoder now requires a GCM-sized payload before trying a prefixed inner layer after successful outer decryption. The same regression checks that genuine double encryption still decodes and malformed stored outer ciphertext remains unreadable and unmodified. `SecurityManager`, stored encryption formats and Keystore policy were not changed.

An earlier F-Droid main/test build succeeded (`kdbx-monica-ui-fdroid-verified-build.log`) and all 16 adapter/UI cases passed, but an old performance-test class still called the previous `loadWorkspace` default-argument ABI and failed with `NoSuchMethodError`. That obsolete instrumentation run was stopped with `am force-stop`; neither package was uninstalled and no app data was cleared. Test Kotlin sources were recompiled with incremental compilation and task/build caches disabled through a local Gradle init script, without deleting build directories. The final main/test build succeeded in 130 seconds (`kdbx-monica-ui-fdroid-complete-build.log`).

After the history-protection case was added, the ordinary final main/test build succeeded in 121 seconds (`kdbx-monica-ui-complete-build.log`), and the complete device suite passed **52 tests in 259.025 seconds** (`kdbx-monica-ui-complete-device.log`). The suite comprises 17 Monica-adapter/UI cases and the existing 35 save/security, compatibility, conflict and independent native-management cases. Both forms of history/refusal protection are covered before either target writes or source deletion. The selected-vault standard-form assertions remain one file commit and no Room password projection; the separate native-manager benchmark measured 1,295 ms and is not a timing measurement of Monica's standard form.

The current F-Droid suite also passed **52 tests in 231.674 seconds** (`kdbx-monica-ui-fdroid-complete-device.log`), including the old performance-test call sites: the stale ABI failure did not recur after test recompilation. The separate native-manager save/list benchmark measured 935 ms; this is not a claim about standard-form or real-device/cloud performance. Both final suites used the already-running public API-32 AVD with update installations and synthetic, isolated fixtures. The AVD was not started by this task and remains running.

All 29 shared production files checked and all seven suite files match between flavors, while `MainActivity` receives only its shared-store binding and retains flavor-specific behavior. Both repositories passed `git diff --check`. Logs are local under the workspace `.codex-tmp/`. No commit, push, tag, release or APK delivery was performed.

# Connected editor field groups — 1.0.327

## Scope and behavior

The ordinary and canonical F-Droid editions restore connected field groups in authenticator, bank card, document, API token, Wi-Fi, address and related editors. The shared section had forced standalone rounded fields and, in some nested components, larger spacing. Explicit field groups now preserve 24 dp outer corners, 4 dp inner corners and 2 dp adjacency, including nested groups. Each field still owns its drawing; mixed section content is not clipped as one card. Scan/generate actions, helper text and error messages remain outside the field group where appropriate. The application picker dialog resets the group context so its input does not inherit an editor row's corner shape.

This task changes 14 shared production UI files. Input callbacks, save operations, authentication, cryptography, storage formats and provider logic were not changed by this grouping fix. Existing unrelated toolchain and application changes remain outside this task's source comparison. Ordinary password editing remains a regression reference; its existing save-time trimming behavior was preserved.

Both editions remain unreleased 1.0.327: ordinary versionCode 12 and F-Droid versionCode 34. The root and next release notes in Chinese/English and the F-Droid 34 changelogs already include this fix.

## Builds and device results

Builds used the repository wrapper and JDK 17 with one Gradle worker, an 8 GiB task-local JVM limit and `-PincludeX86TestAbi`. Debug verification packages used distinct application IDs under `takagi.ru.monica.groups327` and `takagi.ru.monica.groups327fd`; these are test artifacts, not delivered production packages.

| Edition / invocation | Result | Recorded driver time |
| --- | --- | --- |
| Ordinary main + instrumentation (`green-main01`) | Passed | 517.318 s |
| Ordinary instrumentation refresh (`green-main-tests02`) | Passed | 30.705 s |
| F-Droid main + instrumentation (`green-fd01`) | Passed | 218.695 s |
| F-Droid instrumentation refresh (`green-fd-tests02`) | Passed | 32.841 s |

| Edition / device batch | Result | Runner time | Driver time |
| --- | --- | --- | --- |
| Ordinary grouping / behavior / shape / editor pages (`green-main-final03`) | 14 / 14 | 22.714 s | 25.604 s |
| Ordinary wallet editor (`green-main-wallet01`) | 5 / 5 | 29.738 s | 32.206 s |
| F-Droid combined (`green-fd-final01`) | 19 / 19 | 49.870 s | 52.446 s |

Each edition has the same 19 distinct passing methods. The ordinary wallet batch used the original `green-main01` instrumentation APK; the other 14 ordinary methods used `green-main-tests02`. The wallet test source is byte-identical between those two snapshots. Both ordinary batches used the same main APK. F-Droid used `green-fd01` main and `green-fd-tests02` instrumentation. The JSON report records APK identities/hashes and every passed method; the 14 production files and five tested source files match their corresponding build snapshots. Across editions, 18 of these 19 files are byte-identical; EntryPaymentFields.kt differs only in CRLF/LF line endings (13 of 14 production files are byte-identical).

The checks cover actual corner pixels and 2 dp boundaries for API token, Wi-Fi and nested groups; both text-field overloads, edits and selections; 320 dp / 1.5 font-scale RTL support/error text; selected outlines and chips in light/dark themes; actual editor components with synthetic save/reopen, import cancellation, back cancellation, menus, wallet content order and ordinary-password input behavior. OTP save checks verify the production editor callback. Bank-card, document and password save/reopen checks use synthetic in-memory Room fixtures, not a real user vault. Component rendering in a debug host does not establish complete production Activity navigation coverage.

## Failures retained and corrections

- Frozen pre-fix production source was built as `red-main05` after four setup failures: two KSP duplicate-source-root failures, a missing isolated Java reader, and an init-script quoting failure. All original build logs remain in local task evidence. The successful pre-fix build took 458.715 s.
- The pre-fix device run failed both API-token and Wi-Fi pixel checks (2 / 2 expected failures, runner 2.137 s), identifying oversized inner corners. These failures are retained as the negative control.
- Ordinary `green-main-device01` passed 12 / 14. One shape capture could not find a Compose hierarchy; a new ordinary-password test incorrectly expected leading/trailing spaces to remain after save. The password production path already trimmed them. The test now checks spaces before save and the established trimmed persisted value afterward.
- `green-main-shapes02` passed 3 / 4 and again lost a Compose hierarchy. Window diagnostics recorded orientation/configuration changes. Shape tests were moved to the existing debug host that reinstalls the current composition on recreation; pixel/text assertions were kept. The later 14-method batch passed after this test-host correction. These results must not be described as an initially all-green run.

No new JVM test suite or Release/R8 build was run for this UI-only task. Earlier dependency/toolchain results are separate historical evidence. Independent read-only review found no blocking issue and matched all 3,875 ordinary and 3,880 F-Droid main/debug source paths to the main-build frozen hashes; its scope is the grouping diff, not every unrelated uncommitted change.

## Evidence and scope limits

The paired JSON result contains the build outcomes, final device methods, source hashes, artifact hashes, and verified resource cleanup/visual-review facts. Text receipts retain the pre-fix failure, both initial ordinary failures, final ordinary batches and final F-Droid batch. F-Droid metadata validation and all six validation-script tests passed. Screenshots remain only in ignored local design directories; this document does not embed or publish them. The local editable M3E canvas was added during takeover closeout, after implementation, so it is not evidence that a design preview preceded the code change.

Validation uses MuMu API 35 / x86_64 and synthetic isolated Debug data. It does not cover physical devices, all OEMs, Android 8 hardware, this change under R8, real vaults/cloud accounts or the full application. No commit, push, tag, release or APK delivery was performed for this task.

## Verified closure

Both editions: 36 native screenshots retained locally, with 12-panel contact sheets inspected for each edition. Explicit groups and independent controls were readable in the selected normal/large text, RTL and dark-theme samples. The supplemental five-page editable Canvas was corrected after root found inaccurate initial bank/other group sketches; all five corrected pages were loaded and visually rechecked through the local browser, with navigation exercised. Superseded sketches are retained locally and are not accepted evidence. Canvas was added after implementation, not before. Device cleanup restored original user 0 and preserved users 0/10 and their exact installed package sets. Only owned user12 was removed: initial immediate removal failed, observed stopped state then retry succeeded. Original-task-started MuMu0 was stopped; its disk remains. No commit, push, release or APK delivery.

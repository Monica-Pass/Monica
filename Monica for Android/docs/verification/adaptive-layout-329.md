# Adaptive layouts — 1.0.329

The ordinary and canonical F-Droid editions use the current app window to choose navigation and content layout. This is an implementation and emulator verification record, not Google Play certification or physical foldable-device certification.

## Behavior

- Below 600 dp: bottom navigation. From 600 dp: navigation rail. List/detail panes require at least 840 dp width and 480 dp height; short landscape windows remain one pane.
- Password, authenticator, wallet, notes, vault, Send, Passkey and generator panes share a stable composition across resizing. A selected detail occupies the narrow window; returning reveals the retained list. Existing secure editor state stays in memory and is not newly written to saved-instance state.
- Login fields are limited to 480 dp of content width. Settings, independent pages and content editor windows use a readable maximum width. Short login windows scroll, including with the keyboard and large fonts.
- MainActivity handles geometry configuration changes without recreation. Authentication, background/lock handling, storage formats and cryptography are unchanged. Other configuration changes, process death and security locking retain their existing behavior.
- Jetpack WindowManager reports separating or fully occluding fold features. The app uses the larger safe panel to avoid placing fields over the hinge. This is a conservative single-panel posture policy; it is not a book/tabletop two-surface design.
- A selected narrow detail suppresses list floating actions. Wide settings remove the unused placeholder pane.

## Official references

Read the Google announcement dated 18 November 2026 and the linked adaptive guidance:

- https://developer.android.com/blog/posts/google-play-adaptive-quality-standards
- https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-3
- https://developer.android.com/docs/quality-guidelines/adaptive-app-quality/tier-2
- https://developer.android.com/develop/ui/compose/layouts/adaptive/window-size-classes

The announcement describes April 2027 quality/screenshot requirements and the August 2027 target-API roadmap. This task does not publish store screenshots or change targetSdk (34). compileSdk remains 37. WindowManager 1.5.0 was already a transitive runtime dependency; it is now declared explicitly for direct API use.

## Verification scope

Reuse the existing MuMu API 35 / x86_64 instance, with task-only application IDs and synthetic values. Preserve original users, existing packages and data. Geometry screenshots cover 400×640 (10:16), 900×900 (1:1), 1200×900 (4:3), 720×960, actual portrait rotation, and 960×400 with 1.5× font/light theme. Component tests additionally cover 2× font and synthetic vertical/horizontal hinges.

Native tests open MainActivity, type the real login, resize while entering/editing, verify the same Activity during geometry changes, save and decrypt the retained password, search settings across rotation, and verify sensitive controls disappear on lock. Other tests cover retained list scroll, draft identity, editor windows, login background cancellation, and settings search/navigation. Source guards are reported separately from runtime tests.

Both final Debug/test builds passed: ordinary main09 in 2m12s and F-Droid fd02 in 49s. Each edition passed 14 relevant JVM tests with no failures or skips. Each edition has 29 distinct device methods with passing receipts (28 regression methods plus the native MainActivity workflow).

- Ordinary: the initial broad regression batch passed 27/28 in 119.701s; its package-isolation rejection was corrected, and the single protected-search retest passed in 5.459s. The final native workflow passed in 16.281s. These are separate runs, not a claim that the original whole batch passed.
- F-Droid: the final broad regression batch passed 28/28 in 125.626s, and the final native workflow passed in 20.177s.
- The 16 final native screenshots per edition were inspected in contact sheets; ordinary wide detail and short-window large-text settings were additionally inspected at full resolution. Representative surfaces have no unintended stretching or narrow-detail list FAB overlay. Scrollable content intentionally continues below the viewport.
- The 25 relevant source paths were frozen and compared: 21 match after line-ending normalization. Gradle, Manifest, MainActivity and the settings-search test retain documented edition differences, including F-Droid's absence of OneDrive.
- F-Droid version metadata and all six metadata checker tests passed.

Full hashes, runner receipts and the final Release upgrade evidence are recorded in the accompanying JSON. Every unsuccessful attempt is retained locally; initial failures are not represented as passing batches.

## Actual minified Release upgrade

Both editions were tested with actual R8-minified, resource-shrunk, non-debuggable x86_64 Release builds. The old sources came from the pre-work checkpoints (ordinary b5dc49a429ffa1c0ceaed0adc10d733368f2dc1b; F-Droid ea20f59becbf18954fcb23b9a7d1709fb7cf0457). Old and new builds used the same isolated application ID and local test certificate within each edition. These are not production-signed packages. Native modules were built from source.

- Ordinary: old Release built in 14m47s, new in 8m11s.
- F-Droid: old Release built in 19m57s, new in 9m25s.
- Each old Release created a master password and an old record through the real UI, cold-started and read that record, then exported an Enhanced V2 encrypted backup through DocumentsUI.
- Each new Release was installed with replacement, without uninstalling or clearing data. Installed APK hashes and certificate identity were checked. The original password unlocked the vault; the old secret was read. A new record was saved and verified after another cold process start.
- Only the synthetic old record was deleted through the normal confirmation and password-verification flow. The old encrypted backup was restored through the real UI: one imported, zero skipped, zero failed. Another cold start accepted the original password and read both the restored old secret and the preserved new secret.
- Final APK Manifest checks confirmed explicit resizeability and orientation/screenSize/smallestScreenSize/screenLayout handling (0xd80). SHA-256 values, certificates, mapping hashes and ABI are in the result JSON. Twenty retained UI evidence snapshots across the two editions record secret-read and restore-success checkpoints; all values are synthetic.

## Cleanup and delivery state

Removed only the four task Debug/test packages, two task Release packages and task-created external encrypted backups. Final user list, user-0 package set, display overrides, font and rotation settings match their recorded baseline. Existing MuMu remains running; no AVD was used. Local private encrypted evidence is retained. Pre-work checkpoints were pushed as requested; adaptation changes remain local and unpublished. No APK delivery was made.

## Known limits

MuMu does not supply a physical folding hinge sensor. Injected hinge geometry verifies placement policy, not OEM posture callbacks. Requests to launch a freeform or legacy split window did not produce a bounded multi-window surface. Explicitly moving the task into a mode-3 root still retained full-screen task bounds after resize; those commands are not counted as successful multi-window testing. The task-owned root was removed without touching existing roots. Window resizing and rotations were verified through actual Android configuration changes.

Screenshots and the editable M3E Canvas remain local under ignored docs/design/adaptive-layout-329. They are not committed or embedded here. No physical foldable, real user vault, cloud account, hardware biometrics, NFC, or exhaustive all-screen accessibility certification is claimed.

## Failures retained

- Initial test compilation used an invalid DpRect width accessor; corrected to right minus left.
- Initial app host used BoxWithConstraints and deferred NavHost until after a lifecycle observer could navigate. The real startup test caught the missing graph crash. The host now composes navigation immediately and only measures placement later.
- Initial settings locator targeted the wrong semantics node; corrected to the observed unmerged rail entry.
- Import cleanup accidentally removed the getValue operator import in SendPane; restored before a successful build.
- Large-font extension first hit Espresso orientation waiting, then a system-English versus app-Chinese unlock locator. The test now verifies actual orientation and locates the displayed Chinese action; assertions were retained.
- The first broad search batch had 27/28 passes; one test refused the task package before entering the UI. Its isolation allowlist now admits only the two additional exact task package IDs with the corresponding synthetic password; the ordinary independent retest passed.
- Old Release export to MuMu Download failed with EACCES. The same real export flow succeeded in Documents without changing device directory permissions.

UI-driver locator mistakes (the detail action is Show, and F-Droid has no Files tab in this flow) were corrected using fresh observed controls; these helper failures did not require production changes.

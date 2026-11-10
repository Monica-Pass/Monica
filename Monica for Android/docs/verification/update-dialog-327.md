# Update dialog and automatic checks — 1.0.327

## Behavior and scope

The ordinary and canonical F-Droid editions now share an update dialog with current/latest version comparison, scrollable Markdown notes and update, skip-version, details and later actions. Automatic checks are enabled by default, can be turned off in settings, and run only while the unlocked main page is in the foreground. The normal interval is 24 hours between recorded attempts; a backward wall-clock change establishes a fresh baseline. Skipping a release suppresses its automatic prompt while manual checking can still show it. Locked/background states cancel active work and reject delayed results. No external scheduler was added.

The ordinary channel checks its published stable release. Its existing version comparison still uses only the three numeric version components and ignores letter/build suffixes; skip identity retains the exact release tag. This task does not add detection of a newer suffix on the same three-part version. The F-Droid channel uses the official package catalog's suggested version code and requires that version to be present; optional notes must match the same F-Droid release tag. Its update action opens the F-Droid package page without downloading an ordinary-edition APK. Existing ordinary APK package/signature checks and platform installer confirmation remain in the implementation, but this task did not perform a real update installation.

Notes support headings, paragraphs, emphasis, code, lists, read-only tasks, quotes, validated HTTP(S) links, GFM tables, strikethrough and dividers. HTML stays plain text; images contribute alt text without image requests. Parsing runs off the main thread and is bounded to 65,536 UTF-16 units, 2,000 AST nodes, 256 blocks, 12 levels and 16 table columns. Truncation retains a localized notice and the full release-details action. Code and tables allow horizontal scrolling. Existing user-note Markdown rendering is unchanged.

Normal-height dialogs scroll notes independently while progress and actions stay fixed. Short windows (below 520 dp, or below 700 dp at font scale 1.3 or above) scroll the whole dialog, without a second nested vertical notes scroller. The dialog propagates the caller's density, configuration, context and layout direction across its separate Android window. On Android 12 or later, supported and enabled system cross-window blur supplies a Gaussian backdrop; otherwise the backdrop is dimmed. No screenshot is captured to implement blur, and the dialog inherits the host secure flag.

Both editions remain unreleased 1.0.327: ordinary versionCode 12 and F-Droid versionCode 34. Chinese/English root and local next notes plus F-Droid 34 changelogs include this feature. The earlier connected-editor grouping fix has its own separate report. No commit, push, tag, release or APK delivery was performed for this update-dialog task.

## Builds and source association

Builds used the repository wrapper, JDK 17, one worker, an 8 GiB task-local JVM limit and `-PincludeX86TestAbi`. Isolated verification application IDs are `takagi.ru.monica.updates327` and `takagi.ru.monica.updates327.fdroid`; these packages are not distribution artifacts.

| Edition / invocation | Targets | Result | Driver time |
| --- | --- | --- | --- |
| Ordinary `main03` | Debug main, instrumentation, scoped JVM | Passed | 106.189 s |
| Ordinary `main05-tests` | Final instrumentation only | Passed | 38.068 s |
| F-Droid `fd01` | Debug main, instrumentation, scoped JVM | Passed | 262.462 s |
| F-Droid `fd02-tests` | Final instrumentation only | Passed | 39.925 s |

Final ordinary device runs use the `main03` main APK with `main05-tests` instrumentation. Final F-Droid runs use `fd01` main with `fd02-tests` instrumentation. Only AppUpdateDialogTest.kt differs between each main-build snapshot and its final instrumentation snapshot; main/debug production files are unchanged. All 5,116 ordinary and 5,109 F-Droid frozen paths match the final snapshots. The F-Droid instrumentation source had changed during `fd01` diagnostics, so its earlier instrumentation output is not used as final device evidence; `fd02-tests` provides a stable source snapshot. Four final installed APK hashes and identities are recorded in the paired result JSON.

The root's separate freeze check confirms all 3,899 ordinary and 3,904 F-Droid main/debug paths match their main-build snapshots. Both editions use the same final UI test source SHA-256, recorded in the result JSON. The scoped comparison covers 34 shared paths: 33 are byte-identical across editions, while MainActivity.kt retains edition-specific content. This comparison includes the 18 new resource files, update implementation/tests, settings integration, release checker and existing checker tests. It does not claim that the entire repositories are equivalent.

## JVM and compiled-resource results

Retained XML from `main03` and `fd01` confirms the same 45 distinct passing methods in each edition, with zero failures, errors or skips. The six suite counts are controller 9, policy 5, Markdown 8, preference persistence 2, existing checker 6 and release HTTP client 15. Summed suite time is 6.380 s ordinary and 6.340 s F-Droid; Gradle driver times are separate. These checks exercise real parser behavior, synthetic HTTP responses, policy/persistence/controller failures, cancellation and release-channel rules. They do not access a real account or publish an update.

The retained x86_64 APK resource tables were checked with `aapt2`: every new value in all 18 configurations and 11 keys matches its source XML, including Android escaping and the compiled classical-Chinese qualifier. This is 198 actual compiled values per edition, 396 total. Each resource source file matches its main-build snapshot. The resources JSON records the checked strings and APK SHA-256 values. This is not a human translation certification or all-language visual acceptance.

## Device and visual results

All device testing used an isolated synthetic user on MuMu API 35 / x86_64. The production dialog and lifecycle host were exercised through the existing debug test Activity, not full production Activity/settings navigation.

| Edition / batch | Result | Runner time | Driver time |
| --- | --- | --- | --- |
| Ordinary `main-final01` | 8 / 8 | 8.808 s | 11.662 s |
| F-Droid `fd-final01` | 8 / 8 | 8.055 s | 10.590 s |
| Ordinary disabled-blur `main-blur-fallback02` | 1 / 1 | 1.162 s | 4.037 s |

Each edition has the same eight distinct passing methods. The extra ordinary blur check repeats one method under a disabled system-blur setting and is not a ninth distinct method. Coverage includes rendered Markdown and an actual link touch callback; all four action callbacks; long notes with fixed normal-height progress/actions; disabled download actions and cancellation; 320 dp width with 1.5 font scale, RTL and dark theme; a 360 dp short-height dialog scrolled through notes, progress and cancel; F-Droid source/action text; lock/background removal and late-result rejection; and actual dialog-window blur state, radius and dim amount over a synthetic background.

The root agent inspected six final native panels per edition: normal Markdown/actions, download progress, narrow large-text RTL dark, short-height cancellation, F-Droid source and enabled system blur. These are representative English/light and forced-RTL/dark states, not a review of all translated languages. Actual blurred-background screenshots were inspected for both editions. A separate ordinary run disabled system blur, verified radius 0/dim 0.48 and produced an inspected sharp, dimmed background. The prior absent override was restored to `null`, and system blur was confirmed enabled again. This covers both branches on the tested emulator, not every OEM or Android version. Screenshots and editable Canvas previews remain in ignored local design directories and are not embedded or published; only review findings and contact-sheet hashes are recorded. Canvas previews do not establish native Markdown or Gaussian-blur behavior.

## Retained failures and corrections

- `main01` failed after 217.229 s because UpdatePreferencesTest passed a type parameter to Robolectric's non-generic getApplication API. Its JVM tests did not execute. Additional controller/route/install-guard implementation review occurred during this exploratory invocation; its main APK is superseded and is not final acceptance evidence.
- `main02` produced main/test APKs but failed the combined command after 170.498 s. Its XML contains 30 passing methods and one class-initialization error because an expression-bodied JUnit method inferred a non-void return. The 15 HTTP-client methods did not run in that invocation. The fixture return type was corrected; `main03` ran all 45 methods.
- Ordinary `main-device01` passed 5 / 6 (runner 5.205 s). The separate production Dialog window used font scale 1.0 despite the host providing 1.5. Production was corrected to propagate the caller's composition locals; later tests retained the actual 1.5 scale assertion. The corrected main APK was rebuilt in `main03`.
- Ordinary `main-device02` passed 6 / 8 (runner 5.738 s). Two assertions treated Compose didOverflowWidth as direct visible-glyph clipping. A diagnostic instrumentation build (`main04-tests`, 38.185 s) retained two failing cases and measured the actual bounds: Update now used a 399 px layout with 397.54 px glyph extent but a 620 px paragraph; 61% used 117 px layout with 116 px glyph extent but a 782 px paragraph. The overflow property reflected paragraph constraints, while the text was visible in inspected screenshots. The final tests check measured line/glyph bounds instead, retaining font scale, vertical bounds, ellipsis, max-lines and semantics clipping checks. No production change followed that diagnostic. Both final eight-method batches passed with the corrected checks.
- The first attempt to disable system blur via `wm disable-blur` raised SecurityException and did not run a test. A scoped system setting change then exercised the dimming fallback, and the original absent setting was restored. The first ADB connection attempt also failed before the emulator was ready; no device acceptance is inferred from that attempt.
- Initial local note replacement encountered CRLF and left root/next bytes unchanged; corrected delimiter handling preserved existing lines and newline styles. The first resource helper attempted to parse unrelated styled `aapt2` output as JSON; restricting it to the exact 11 requested keys enabled the successful 396-value comparison. Neither helper correction changed APK or production source.

These are not initially all-green build/device results. Public text receipts retain the original device failures and final batches; build summaries preserve original failure diagnostics and hashes. Complete original logs, frozen sources and intermediate artifacts remain in local task evidence.

## Metadata and cleanup

F-Droid metadata validation and all six release-validation script tests passed. Root and ignored next notes align. F-Droid 34 Chinese/English summaries contain 281/473 characters, within the 500-character limit. Scoped whitespace checks passed. Public machine evidence contains no workstation paths or screenshots; paired public files are identical across editions.

Restored original user 0; removed only task-owned user 11 after first immediate removal failed and stopped-state retry succeeded. Original users 0/10 and their exact installed package sets remain. System disable_window_blurs returned to its original absent (null) value, enabled blur confirmed before shutdown. Stopped task-started MuMu instance 0 (verified PID39292); original VM configurations and both instances data disks remain. Canvas design service retained. Public AVD untouched.

## Limits

This task covers synthetic API 35 / x86_64 Debug fixtures and representative native dialog states. It does not establish physical-device/OEM compatibility, other APIs, Release/R8 behavior, full-app regression, arbitrary window/font combinations, real vault/cloud operation, real APK download/signature rejection/installation, or actual F-Droid application launch. The settings persistence and foreground policy are checked through unit/controller tests and source integration, not a complete production settings-navigation test. Existing installer/security safeguards were preserved in source; the dialog callback tests are not an end-to-end installer test.

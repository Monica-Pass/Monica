# Phone landscape pane regression, unreleased 1.0.329

The previous foldable change added a 480 dp minimum height to the shared pane policy. A sufficiently wide but short phone window therefore showed a full-width list. Both password and wallet pages receive this policy through SimpleMainScreen. The previous policy test explicitly expected 1000 x 400 to be single-pane, while the native short-window test checked reachability without asserting pane geometry.

Restore a width-only pane policy, with the existing 840 dp split and 600 dp navigation thresholds. Remove the unused height input from the shared policy/call site. The layout still checks actual remaining width and reserves at least 320 dp for detail. Separating-hinge safe-region selection is unchanged. No schema, authentication or data migration changes belong to this fix; schema 85 and the 86-to-85 recovery bridge are retained.

Regression tests use actual MainActivity password and wallet routes and synthetic data in isolated application packages on the existing MuMu API 35 / x86_64 instance. They assert visible, non-overlapping left/right bounds at 960 x 432, 1000 x 400 and 840 x 400, single-pane behavior at 800 x 400 and 400 x 800, portrait Back, lock hiding and 1.5 font scale/light theme. Existing native login/edit/save/settings/rotation/lock and component fold/retained-state tests are also run.

## Final results

Both ordinary and F-Droid Debug app/test builds succeeded. Each edition passed 14 relevant JVM tests (3 policy tests and 11 source guards) and 7 distinct MuMu device methods (2 new full phone workflows, 4 component checks and 1 existing MainActivity workflow). Final device batches passed without failures; earlier failures are preserved below. Exact runner durations, APK SHA-256 and shared-source hashes are in the accompanying result JSON.

The unchanged native workflow verifies real login, edit/save with decrypted value comparison, 1200 x 900 / 900 x 900 / 720 x 960 / 400 x 640 resizing, settings rotation and lock. Phone workflows verify 960 x 432 and 1000 x 400 list/detail, the 840/800 dp width boundary, portrait detail/Back, lock hiding, and 1.5 font/light theme. Component checks preserve draft and scroll identity across short/narrow windows and synthetic separating hinges. Phone screenshots in both editions were inspected; this is not physical foldable hardware testing.

Five shared production/test files match byte for byte across editions. The only production edits in this task are the width-only policy/unused height removal and the SimpleMainScreen call. Both PasswordDatabase.kt files remain byte-identical to the task baseline, retaining schema 85 and the non-destructive 86-to-85 bridge. Current 329 notes and F-Droid changelog are updated. F-Droid metadata plus all 6 checker tests pass; Git whitespace checks pass.

Only task-created isolated application packages and their synthetic data were removed. MuMu size, density, rotation and font scale match the recorded baseline; original users/packages are unchanged and the previously running emulator is left running. No commit, push, release or APK delivery was performed.

Only synthetic MuMu Debug tests are claimed, not physical foldable/OEM or Release/R8 validation. Screenshots remain under ignored docs/design/phone-landscape-329; no screenshots or APKs are added to Git.

## Retained intermediate evidence

- red01: the new test did not compile because this Compose DpRect exposes edges rather than width/height. Corrected test geometry arithmetic; no production edit.
- red02: original app compiled; both real password/wallet tests failed with adaptive-detail not displayed at 960 x 432. Screenshots confirm the reported full-width lists.
- main01: fixed policy compiled. Password two-pane geometry passed, but repeated fixture rows entered smart stacks; wallet also could not locate the expected standalone card. Added exact-prefix cleanup within the isolated package and corrected card fixture to encrypt the whole JSON like the production writer. These are test setup changes, not production data changes.
- main02: both pages passed 960 x 432 and 1000 x 400 side-by-side geometry and retained portrait detail. Espresso.pressBack raised NoActivityResumedException (orientation update wait after wm resize). Replaced the driver action with the real Android KEYCODE_BACK through the instrumentation shell; keep all state assertions.

Each edition ran the existing MainActivity regression after resetting only its task-created isolated package, to keep that workflow fixture separate from the phone fixture. No original app data was cleared.

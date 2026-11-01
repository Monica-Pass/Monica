# KDBX history stability in 1.0.324

2026-11-01. Investigation of [issue 172 comment 6545263846](https://github.com/Monica-Pass/Monica/issues/172#issuecomment-6545263846). The screenshot shows a 22:28 version marked latest above 22:32 versions. Passwords are masked, so it does not prove their contents changed or identify the user's synchronization path. No GitHub reply was sent.

## Confirmed causes and changes

- Native edits compared encrypted in-memory objects and field order directly. Rebuilding an unchanged protected field gives it fresh random salt; this caused an unchanged save or replayed field patch to touch entry times and add a history snapshot. Edits now use the existing lossless comparison, including exact field contents and protection flags, UUID, timestamps, history, attachments, presentation, and plugin data. Actual changes still create history.
- UUID merging treated access time, usage count, and movement metadata as content conflicts. Access-only changes could append the other current entry as history. Content comparison now excludes those independently merged metadata fields; modification/creation/expiry times and actual native content remain significant. The winning move is retained, and access time and usage count use their maxima rather than increasing on repeated merge.
- The password page showed native history in file order while labeling the first item latest. Only presentation is now ordered by descending modification time, with descending original index for ties. Native indices and captured revision checks remain attached to delete actions. The original history order in the file is not changed by displaying it.
- Native history uses saved-version counts and modification-time labels, rather than the local-only ten-password description and last-use labels. Delete/clear confirmation describes native versions; the clear confirmation is localized in 14 resource configurations.

KDBX snapshots represent complete entry versions. Two versions may contain the same password but different notes, fields, attachments or metadata. Those valid versions are retained. The patch does not automatically remove existing history records, change encryption/KDF/file format, relax lock/read-only/revision checks, or rebuild keys.

## Negative controls

- Old NativeMutation implementation: 10 JVM methods, two failures for rewrapped unchanged fields and repeated unchanged protected-field patches. Protection changes remain a real edit.
- Old synchronizer: 17 JVM methods, one failure for access-only divergence. A separate rewrapped-history repeated-merge control already passed and retained distinct note versions; this rules out claiming that every merge inherently duplicates history.
- Preserved old ordinary seq07 APK with new backend tests: three device methods, all three failed for unchanged saves, access-only merge history count, and latest-first display. All use disposable synthetic files, with actual service saves/merges and disk decode/cache eviction.
- The initial access-only JVM fixture later anchored its access timestamp to the fixture epoch for deterministic maximum-time assertions; the earlier false-conflict failure remains a valid negative control.

## Validation status

Ordinary final Debug main/test build: 5m29s, with all 258 KeePass JVM methods passing (no failures/errors/skips). Three new backend methods and the actual shared history component UI method passed together in 13.967s. Existing 44 device methods passed in 90.994s, covering current-file merge, stacking/account content, native password and secure item operations, external reload, lock/read-only/revision protection. This is 48 distinct device methods. A prior fixed-core run of the same three backend methods passed in 11.876s and is not added to the distinct count.

F-Droid final Debug main/test build passed in 6m19s, with all 258 KeePass JVM methods passing (no failures/errors/skips). Existing 44 device methods passed in 75.961s; the three new backend methods and actual history component UI method passed in 13.392s, for 48 distinct methods. Both actual APKs' 14 new localized values match source. The two distributions' eight shared code/test files and 14 resource files match. F-Droid static metadata and six script regressions pass.

The existing public API32 x86_64 AVD was started, with only a new synthetic guest13 (`Monica_History172_Test`). After verifying no instrumentation remained, the guest was removed, user0 restored, and this task's started AVD stopped. Original users0/10/11/12, registration/configuration, and the data disk are retained.

A guest external-storage screenshot export initially returned permission denied; the 102-byte error was not visual evidence. Only the opt-in screenshot fixture was changed to use its own internal files. Test-only rebuilds passed (ordinary24s/F-Droid13s), and the ordinary actual history UI method passed again in 4.324s. Both final native history-component screenshots were successfully exported and inspected: saved-version count, masked passwords, newest modification time/latest marker, second older version and actions are visible without clipping. These are component captures in the default light theme, not full-activity or multi-theme acceptance. Images stay in ignored local design folders and are not embedded in repository documentation.

These are Debug/emulator and synthetic local-file checks, including simulated external writes. This round does not verify the reporter's actual device/sync provider, real KeePass clients, real cloud accounts, or an R8 release. No commit, push, tag, Release publication, or APK delivery was performed. Both distributions remain unreleased1.0.324 (ordinary code12, F-Droid code31), with 323/323b archives preserved.

# Local note cloud backup selection — 1.0.325

## Report and reproduced defect

The report concerns ordinary Monica 1.0.324: notes visible under Monica local, but OneDrive backup displays zero notes. The reporter cannot currently verify whether reopening the settings page changes the count. Their private database has not been inspected, so the precise cause on that device remains unconfirmed.

The note-page ownership resolver and the backup SQL disagreed. A stored Bitwarden vault ID without any concrete cipher/revision/folder/local-modification/sync binding is explicitly treated as MonicaLocal. Simultaneous KeePass/Bitwarden IDs without concrete bindings are also treated as MonicaLocal. These compatibility rules exist in released V1.0.324 (0089334b6573920855eafdd358f3da33a0188f8b). The old backup queries required all provider IDs to be NULL instead, so those visible local notes counted as zero and never reached ZIP creation.

The converse was also reproduced: a real cipher binding without a vault ID was counted as local by SQL, although the domain resolver and ZIP policy exclude that Bitwarden row.

Real Room/SQLite and the production repository/NoteScopeIndex reproduced both errors using synthetic records with the supported shapes. Baseline: 4 tests, 3 expected failures and the ordinary-local control passed, runner 8.17 seconds, Gradle 2m15s. This is evidence of the compatibility defect, not proof of the reporter's exact stored fields.

## Implementation

The existing ownership resolver's decision body is unchanged. Full records and a new Room metadata projection call the same field-based function. Backup counts load only IDs, item types and ownership fields; they do not load note bodies, titles or attachments, open external vaults, or decrypt content.

The cloud backup input resolves local IDs from the same metadata and reads their payloads in chunks of at most 900 within a Room transaction. Selection and payload reads therefore use one database snapshot. ID ordering is preserved. Archived local rows are included consistently in count and input; trash, PASSWORD secure items and external/conflicting ownership remain excluded from this local secure-item query. Password entries continue through their separate repository.

Source records are never rewritten. ZIP export continues to sanitize only portable copies. The general BackupContentPolicy intentionally also supports other offline export scenarios; it remains unchanged. No database migration, encryption, KDF, cloud transport or authentication change is introduced.

The shared fix is applied to ordinary Monica and canonical F-Droid. F-Droid coverage and release notes describe WebDAV only; F-Droid has no OneDrive feature.

## Validation

Ordinary main APK was built in the initial 12m51s invocation; that invocation failed later in the new device-test compilation because NoteScopeIndex returns indexes rather than entities. The test mapping was corrected. The following 5m48s invocation built both main/test APKs and ran 18 JVM tests; 17 passed, one new assertion incorrectly equated the general ZIP policy with local cloud selection. The assertion was corrected to test the actual selected input, preserving the existing policy. Final ordinary JVM run: 18/18, no failures or skips, Gradle 2m15s.

Seven new Room regressions cover legacy local rows, normal local rows, cipher-only external ownership, all concrete binding fields, conflicting/MDBX ownership, Unicode blank markers, archive/trash/type scope, 1101 rows and source preservation. Eleven existing ownership, note-index and backup-policy tests also pass.

Ordinary Android32 device run: 2/2, runner14.281s. Actual production ZIP creation/readback was exercised in plain and password-encrypted modes. Each archive contains the four selected local notes, including two legacy shapes and an archived note. IDs, titles, full note data, cached text and archive state match after parsing; wrong archive password fails; restored copies have local ownership; all eight source rows are unchanged. Deleted and external rows are absent. This does not apply the restore to a destination database or call OneDrive/WebDAV servers.

F-Droid main/test APKs and all 18 JVM tests passed in one 15m44s invocation. Its two Android32 plain/encrypted archive methods passed, runner24.088s. No tests were skipped. Five shared source/test paths match SHA-256 between editions and the frozen tested sources. APK output metadata confirms ordinary code12 and F-Droid code32; four tested APK hashes are retained locally. F-Droid metadata and all six release-script tests passed.

## Cleanup and evidence

Reused the public Monica_Issue136_API_32 at port5556 and owned guest13 Monica_LocalNoteBackup325_Test. Stopped it between ordinary tests and the F-Droid build to release memory, then reused the same disk and guest. Final cleanup restored user0, removed only guest13, stopped the owned emulator, and verified preserved users0/10/11/12, AVD registration/config/userdata/qcow2 and unrelated MuMu5554. No application data belonging to another user was cleared.

Adjacent local-note-backup-325-* files contain baseline XML, the initial assertion failure, final JVM summaries/XML, both device runners, frozen source hashes and final results. Full build output and APK hashes remain in .codex-tasks/20261105-onedrive-note-counts/. Ordinary build uses the previously compiled BUILD_TIME label through a local init script; no tasks/checks are skipped. F-Droid uses its normal empty build timestamp. Screenshots and private reporter files are not copied into these reports.

## Limits

No real Microsoft account, remote upload, reporter database, physical device, R8 build or visual settings-page acceptance was performed. A separate stale UI count after restoration/background changes has not been reproduced or fixed here. Reopening behavior remains unknown. Ordinary 1.0.325/code12 and F-Droid 1.0.325/code32 remain unreleased; the published324 archive/tag is unchanged. No commit, push, tag, release or APK delivery is authorized or performed.

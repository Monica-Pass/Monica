# Export source database record counts

Date: 2026-10-29. Development version 1.0.323, unreleased.

The export page and its source selector now show a small record count to the right of the database name. The existing Monica rows, subtitles and selection controls remain in use. Empty sources display zero; counting, locked and unavailable states are distinct. No number is inferred from a failed read. Import destinations do not request or display these totals.

Totals describe current database records, excluding trash. They do not describe only the currently enabled ZIP categories. Attachments, folders and historical versions are not extra records. Local totals include passwords (including archived records), secure items, Passkeys and local Steam accounts. Ownership filters exclude native/cloud projections. Bitwarden projections are deduplicated by cipher identity so a login with a Passkey is counted once.

KDBX totals come from the original in-memory browser records, without a Room projection or automatic unlock for counting. MDBX2 uses the native diagnostics summary entry count without disclosing entry payloads, including future entry types that have no Room projection. Reads use the existing native session/credential boundary. A missing MDBX credential reports the locked state. The selected source is counted first; other sources load as the sheet opens, and reopening the sheet refreshes totals. Reads run off the UI thread and cancellation is propagated.

The editable M3E Canvas source and local share link are saved under `docs/design/export-record-count-323/`. Both Canvas frames and the actual Android export page were visually inspected. Sketches and screenshots stay in the ignored local design directory; no images are referenced here as repository assets.

The ordinary main/test build succeeded in 474 seconds (`export-source-count-ordinary-build.log`). The first four-case device run passed the local, Bitwarden and MDBX cases but the UI test could not uniquely select a shared synthetic KDBX name left by an earlier test run. Only this task's new fixture was given a unique name; existing app data and older fixtures were retained. The corrected test package rebuilt in 59 seconds (`export-source-count-ordinary-test-build.log`). The complete ordinary suite then passed **4 tests in 25.818 seconds** (`export-source-count-ordinary-final-device.log`):

- Local multi-type records, archived records, trash exclusion and native-source isolation.
- Bitwarden login/Passkey deduplication and another vault's exclusion.
- Actual export page and source-sheet selection, a 300-entry native KDBX with no Room index, count removal on lock, and byte-for-byte source-file preservation.
- Empty MDBX2 and an unprojected future-type record, folder/tombstone exclusion, and an unchanged commit head during counting.

The canonical F-Droid main/test build succeeded in 459 seconds (`export-source-count-fdroid-build.log`), and the same complete suite passed **4 tests in 29.238 seconds** (`export-source-count-fdroid-device.log`). All five shared production files, three localized resource files and the test file match between the ordinary tree and canonical F-Droid tree. Both repositories passed `git diff --check`. The unreleased bilingual notes and F-Droid fastlane 30 were updated; fastlane text remains below 500 characters per language.

Logs are local under the workspace `.codex-tmp/`. The already-running public `Monica_Issue136_API_32` AVD was reused with update installations and remains running. No app-data clearing, release, tag, commit, push or APK delivery was performed.

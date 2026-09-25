# Native KDBX and MDBX verification — 2026-09-26

This follow-up exercises the actual Android native database implementations, including files created by the real Monica CLI. The fixtures contain public synthetic data. The reporter's original vault and cloud account were not available.

## Main-app results

| Verification | Result |
| --- | --- |
| Main debug and test packaging | Passed |
| Focused JVM suites: keyboard, KeePass, MDBX, WebDAV | 486 passed; 98 suites; no failures, errors or skips |
| Native database device selection | 85 passed in 95.462 seconds |
| KDBX compatibility, management and conflict classes | 36 of those device tests |
| MDBX, legacy migration, backup, cursor and retirement classes | 49 of those device tests |
| Independent PyKeePass 4.1.1.post1 read | Android-written KDBX 3 and KDBX 4 both passed |
| Independent Monica CLI 0.5.0 read | Android-written MDBX opened; both entry identities/types preserved |

The device uses the existing `Monica_Issue136_API_32` Android 32 x86_64 AVD. ARM packages are built, but these instrumentation results do not claim physical ARM-device coverage. See the [keyboard verification](monica-keyboard.md) for its separate 27 passing device checks.

## KDBX

Encrypted KDBX 3/4 round trips verify exact field names, empty/whitespace-only values, CRLF, protected passwords, raw references, tags, expiration, custom icons, attachment bytes and history. Independent PyKeePass opens freshly pulled Android outputs and checks those values, rather than relying solely on Monica's own decoder.

The large fixture retains all five authenticators among 406 native entries. Unknown/incomplete Monica metadata and custom-field-only records remain reachable. A group named “Trash” is not treated as a recycle bin without the database's metadata. Native TimeOtp, HmacOtp, KeeOtp, URI, Tray TOTP and Steam handling, UUID isolation and editing OTP on an ordinary login are covered.

Management checks exercise actual native save/reopen, grouped field/icon/property/attachment saves, group moves, recycle-bin operations, attachment rename/removal, history restoration and master-password/key-file changes. Failed saves and read-only operations preserve the old data. Conflict tests exercise independent edits, stale revisions, conditional WebDAV publication, recovery copies and pending-work settlement. Additional details and existing UI evidence are in [KeePass native management](keepass-native-management.md).

## MDBX

The selection covers native create/read/update/delete, nested folders, tags/search, password attachments, history/snapshot restoration, reopen, interrupted Room-mirror recovery, external-file publication, concurrent writers and conflict detection. Tampered sync bundles are rejected; unsuccessful operations roll back instead of leaving partial data. Actual provider transfers are represented by deterministic fixtures and MockWebServer.

The bundled native engine reproduces and verifies the incremental-sync dependency case: a child segment encountered before its parent cannot partially change the vault. The coordinator defers it, applies its parent from another stream, and then succeeds without downloading that segment again. A 130-operation native history also verifies synchronization across page boundaries. JVM regressions cover persisted upload resumes, backoff and reduced WebDAV directory/blob probes.

MDBX1 creation and ordinary writes are rejected. Existing records offer a verified copy-based MDBX2 upgrade while retaining the original file. Local/remote legacy upgrades and attachment-failure rollback are tested. Compatible CLI-created vaults with a missing Android root collection regain that collection atomically with the first write, preserving their existing collections and identities.

`Mdbx2CliCompatibilityInstrumentedTest` additionally uses the checked-in real CLI 0.5.0 fixture in `app/src/androidTest/assets/mdbx/`. Android reads its original API token, creates and updates another entry, preserves the complete original payload, and reopens the resulting native file. It checks the updated account, password and Chinese CRLF notes, then exports and reopens a portable native backup. The fixture README records its provenance and deliberately public credentials.

The independent CLI then opens that Android-written 561,152-byte portable backup using a new isolated configuration. Its native library contains both the original `api-token` and the new `login`, with the original CLI entry identity/category unchanged. Opening a managed copy leaves the source's SHA-256 unchanged. Android verifies the credential values; CLI verification uses its public library metadata and does not add or invoke a credential-reveal interface. No live service is contacted.

The portable export fixture uses internal app storage so `adb exec-out run-as` can retrieve the native engine's private-mode file without changing its permissions. The updated export test passed separately after the 85-test run. The CLI creation, Android edit/reopen and independent CLI read reports are retained under `raw/cli-roundtrip/` in the task directory.

## Reproduction and limits

The main repository's `.codex-tasks/20260926-ime-improvements/raw/` contains `verify.ps1`, `run_database_device.py`, `build-final-verification.log`, `database-device-final.log`, `main-jvm-final.json`, `check_roundtrip_pykeepass.py`, `pykeepass-roundtrip-results.json` and the CLI round-trip scripts/artifacts. The committed instrumentation classes provide the portable regression coverage.

Two initial fixture assertions were corrected before the successful 85-test run: the native password payload uses `password_plain`, and the backup warning must be checked in the active locale. Assertions for password content, preserved CLI payloads, backup contents and item counts remain in place.

These results cover the listed native operations and interoperability cases; they do not establish exhaustive KeePassDX feature parity, compatibility with every third-party plugin, or availability of a remote provider returning persistent HTTP 503. The earlier reporter's exact Android 16 CursorWindow exception was not reproduced on API32; the related concurrent multi-window read inconsistency has a passing native regression.

F-Droid receives the source/test/catalog changes, synthetic fixture, documentation and release notes. No new F-Droid build or functional validation was run, as requested.

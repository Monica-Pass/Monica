# ZIP configuration backup and format descriptions — 1.0.323

Date: 2026-10-31. Ordinary Android and canonical F-Droid; unreleased.

## Behavior

- File ZIP export offers the existing “Monica Configuration” opt-in, off by default. It includes the same portable page settings, common account details/templates, security-question hashes, autofill exclusions, WebDAV and Bitwarden connection configuration used by cloud backup. It is available without configuring WebDAV. Selecting it requires encryption in both the UI and exporter; failed configuration serialization aborts the file export and removes its temporary files.
- Uses the existing `includeWebDavConfig` flag. Cloud backup defaults and the selected database snapshot remain unchanged. Configuration-only archives are allowed; configuration is app-wide, while record content remains restricted to the selected source. No other database files or unrelated record collections are added. Existing encrypted connection formats, locked Bitwarden restoration and security-question policy are retained; no master-password/Keystore or Plus/device-authorization state is newly serialized.
- ZIP import confirmation has an explicit configuration checkbox, off by default. The choice reaches the background job and password retry. Skipping preserves local configuration. Restoring follows existing rules: common account fields fill only gaps, WebDAV does not replace a configured connection, and Bitwarden vaults are locked and require verification. Existing destination validation and data-write rollback remain in place. Configuration application uses the existing cloud restore path; it is not a new transaction spanning configuration and all destination writes.
- Connection decryption now treats only null/empty passwords as absent; non-empty whitespace passwords remain literal. This corrects the mismatch with ZIP encryption/decryption without trimming user passwords or changing the encryption algorithm.
- Four generic import/export descriptions now name ZIP, KDBX and other supported formats rather than claiming CSV only. CSV-specific parsers and descriptions are unchanged.

## Validation

| Edition | Final Debug main/test build | JVM passed / failed | API32 passed / failed | Device duration |
|---|---|---|---|---|
| main | 1m 13s | 20 / 0 | 14 / 0 | 53.944s |
| fdroid | 1m 49s | 20 / 0 | 14 / 0 | 42.842s |

The final 14-device-test suite includes 5 configuration tests, 3 native transfer UI tests, the existing cloud settings round trip, 4 local replacement/rollback checks, and the encrypted ZIP import test across Local/KDBX/MDBX/Bitwarden.

Verified:

- Disabled configuration produces no `monica_config` entries; enabling it without encryption fails without publishing an archive.
- Encrypted configuration-only backup restores or skips page settings, common email, autofill blacklist and security questions. Answer plaintext and master-password/Plus fields are absent from those setting payloads. Wrong archive password changes no tested settings.
- Selecting an MDBX source exports its records while excluding a separate local record. Passkeys and unrelated database files are not added when deselected.
- Bitwarden connection material is protected in the archive and restored to a locked vault, including when WebDAV has never been configured.
- WebDAV credentials round-trip with a whitespace-only password; skipping leaves it unconfigured, and explicit restore never replaces an existing configured connection. Synthetic endpoints are `example.invalid`; no live cloud account is used.
- Native export UI selection reaches the produced encrypted archive and survives background completion. Import checkbox defaults off, opt-in survives a wrong-password retry, and settings then restore to the selected destination flow. Existing Polish 1.5x-font export action remains reachable.
- 9 keys × 14 resource configurations = 126 values per edition checked in the actual x86_64 APKs. New strings cover 13 languages. Shared configuration methods/import callback sections match; pre-existing F-Droid HTTP policy and lack of Android app-to-app credential import remain distinct. Fastlane English/Chinese remain at most 500 characters.

Ordinary first device run passed 14 tests in 93.581s before the whitespace-password boundary was noticed by source review. It is not counted as final verification of that fix. The whitespace fix initially failed one source-string guard that required the old `isNullOrBlank` condition; the guard was corrected to require `isNullOrEmpty` and forbid the old condition, preserving legacy fallback coverage. The failed combined run is retained locally as `build-main-old-guard.log`; final results above are from the corrected run. A first test-script invocation used the wrong working directory and was corrected before test compilation. This AVD disallows root adbd and guest external screenshots were inaccessible to the shell, so the UI fixture now stores synthetic captures in its internal test directory. No production permission or storage behavior was changed for capture.

## UI and cleanup

Local M3E Canvas was used before native UI changes; its three editable frames and spacing were inspected. After the AVD restart, the system guest-session prompt covered the initial captures even though Compose assertions passed. Those captures were not accepted as visual verification. The existing guest session was continued (not reset), and all three UI tests were repeated with the same final APKs: main: 3/3 in 19.3s; fdroid: 3/3 in 18.722s. The resulting export configuration, import confirmation and Polish 1.5x-font captures were visually inspected for both editions, six unobstructed screenshots in total. Draft JSON, local link and native screenshots remain only in the ordinary edition’s ignored `docs/design/zip-config-backup-323/` directory. No images or temporary archives are added to Git.

The existing `Monica_Issue136_API_32` AVD and original data disk were reused, with this task’s guest user 13. It was stopped during the final rebuild to free host memory, then reused for final device tests. Final cleanup restored Owner/user 0, removed only this task’s guest and temporary UI dump, and stopped the AVD started by this task. Users 0/10/11/12 and the separate emulator-5558 were preserved; the local cleanup log records the checks.

No release build, physical-device/cloud-account end-to-end validation, commit, push, tag, release or APK delivery in this task. Debug APK hashes:

- main: `7c18afe5cde393e9486a2686aab75b60ba19421bdadc4051ed539e68c9df67bf`
- fdroid: `d6346d66a7871ce9986a4b9c61ca71be30a89d65245ebd6f6d5a0c1598980b01`

# Biometric unlock repair — 1.0.331

Status: implemented and verified within the scope below; unreleased. Ordinary versionCode 12 is retained by repository policy; canonical F-Droid advances37→38. No commit, push, tag, release or APK delivery was performed.

## Problem and resulting behavior

An unreadable biometric MDK wrapper was previously considered repaired merely because the wrapper existed. Unlocking with the correct master password populated the current process cache, hiding the fact that the next process could not read the persistent wrapper. Two old-production regressions failed as expected before this fix. Earlier System/actual-OMK mechanism probes reproduced corrupt-wrapper, missing-alias and replaced-alias cases. Changing keybox alone does not prove AES key loss.

Settings → Master Password & Locking → Repair biometric unlock now requires a fresh original-password submission and explicit confirmation. It unwraps the original password-protected MDK, creates an independent alias and immutable binding slot, reopens the alias, and compares the decrypted candidate before selecting it through the existing authenticated recovery-snapshot transaction. The original vault ciphertext, password/KDF protection, legacy aliases, shared field-encryption keys and old binding material are retained. An unreadable selected binding cannot fall back to older material. AUTH is never retried as COMPAT; unknown selected policy conservatively requires AUTH.

The final transaction checks the original credential/binding state, lock generation, password epoch and request cancellation. Uncommitted candidates are removed; a candidate whose commit has begun is retained even if IO or UI delivery becomes ambiguous. Rebind itself grants no application session. Current-process caches are not persistent-health evidence. Biometric enablement is a separate platform-authenticated action requiring persistent health. Main-screen biometric provider work is bounded off-main; key publication requires a current foreground request. Existing main, recovery and IME readers use the selected binding.

The entry uses shared Setting search definitions. Nine strings are supplied in 18 locale configurations. Normal and actual system font_scale1.5 Release dialogs were viewed in both editions, and password entry/cancel was exercised. Local editable M3E Canvas states were reviewed before implementation. Media and full Canvas URLs remain local and ignored.

## Final build and runtime evidence

| Check | Ordinary | F-Droid |
|---|---:|---:|
| Final Debug main/test build |195.104s|215.662s|
| Actual minified Release build |499.883s|470.680s|
| Core/UI/key-read/real IME methods |23/23,21.631s|23/23,23.090s|
| Password upgrade/recovery/authorization methods |56/56,159.294s|56/56,147.217s|
| Settings search |16/17,73.298s|16/16 applicable,74.482s|
| JVM selected suite |54/57,3 failures|54/57,3 failures|
| System+OMK cold phases |24/24|24/24|
| Same-signature old329→331 Release acceptance |passed|passed|

Times for device groups are Android runner times. The search failure is an existing HyperOS test's hardcoded historical package allowlist, before page assertions; the new isolated package is intentionally outside that allowlist. Its original failure remains recorded. The16 applicable search methods passed in both editions. This is95 distinct successful regular methods per edition; the separate cold test method is run through12 phases per route. Do not describe the ordinary initial96-method selection as all green.

Each cold route covers corrupt blob, missing alias and replaced alias, with seed→confirmed repair→new-process old-data read/new write→another-process new-data read. All48 final phases passed with distinct PIDs. Injector snapshots contain200 unique routing lines tied to44 of those48 PIDs, establishing System for user13 and actual Omk for user14 in both editions; log rotation means not every final PID has a retained route line. Scope inclusion alone was not used as proof.

The real :ime service read the repaired binding without password or main-process MDK cache; this is a genuine second app process but not complete keyboard UI/sensor acceptance. Core negative coverage includes wrong password with a warm cache, damaged password wrapper, cancellation after candidate creation, locking, competing binding changes, selected-key failure without fallback, retention of previous slots/aliases, legacy C2 field decryption, developer-bypass disablement and staged-key cancellation.

Release acceptance used actual existing329 minified artifacts, not330. Both new artifacts are non-debuggable x86_64 and use the same isolated package/local test signature as their old artifact. Old app UI created the master password, record and encrypted ZIP backup. Installation used-r without uninstall or application-data clearing. Wrong password was rejected; original password, old record, new save and multiple cold reads passed. The old synthetic record was deleted through confirmed UI and restored from the old encrypted ZIP via system Documents; the new record remained. A separate same-signer test instrumentation deleted only the synthetic app's legacy COMPAT alias. Actual Settings search and password-confirmed repair succeeded. A new process then ran production persistent health and biometric-key reading through the actual R8 mapping, followed by another UI process reading both records with the original password. F-Droid's no-argument method was inlined: the test-only probe was adapted to the retained overload; production source/APK was unchanged. These probes do not simulate a successful fingerprint sensor callback.

Current app/src hashes exactly match both final Debug and R8 build snapshots. Shared repair paths match between editions except Setting.kt's preserved OneDrive difference. Version/fastlane metadata and all 6 F-Droid metadata script tests passed. Existing dirty autofill/attachment/Bitwarden/editor work was not edited. The initial baseline helper mishandled Git-quoted paths for some unrelated dirty files; complete pre-task byte identity is therefore not claimed for those paths.

## Retained failures and limitations

Each JVM selection ran57 tests with0 skipped and the same3 broad source-shape failures. All3 were independently reproduced against pre-task ordinary HEAD as well as current source: BiometricUnlockRegressionGuardTest.compatDataEncryptionDoesNotUsePredictableMasterKeyString, BiometricUnlockRegressionGuardTest.pageSwitchHotPathsDoNotRunAuthOrBitwardenSyncWorkOnMainThread, and SteamMarketSensitiveActionGuardTest.protectedExecutorRequiresMasterPasswordOrStrongBiometricSuccess. Their assertions were not removed or weakened; the suites are not reported as green.

Earlier main02 device12/13 failed while a damaged-password fixture attempted a protected credential write. The fixture now uses the authenticated recovery snapshot; main03 then passed16/16. Main04 build failed native mmap allocation; stopping only the task-started MuMu and using a task-local6GiB heap allowed main05 to pass. Intermediate cold runs encountered host adb bad_alloc and a user stopped by a concurrent test-user switch; final operations were serial. All original logs are retained locally.

MuMu reboot converted synthetic secondary users into managed profiles. Their file picker could not access the expected external-storage root; a private test document provider did not resolve discovery. That route was abandoned without claiming backup success. A fresh ordinary secondary user in the same existing MuMu instance provided working system Documents and completed the accepted Release workflow. Old-version update popups and file-picker node ambiguity caused additional host-driver stops, retained in the raw evidence. No Monica app data was cleared during accepted upgrades. The nominal Compose1.5 density test produced unchanged dialog pixels, so it is not large-font evidence; actual system font1.5 was used for final visual acceptance. Initial Release screenshots were black due to screenshot protection and were excluded; the setting was changed through UI only for synthetic visual checks and restored afterward.

Scope: synthetic MuMu API35/x86_64, System and third-party OMK ba6f300 x86_64. No physical TEE, official ARM64 OMK, Android16/OEM device, real fingerprint sensor, all system-entry UI paths, real cloud accounts, or power-loss verification. Existing AUTH policy remains conservative but sensor authorization was not exercised. Permanently lost keys cannot be reconstructed for content that lacks independent recovery material; retaining old aliases is not a promise to decrypt unavailable legacy hardware-only data. Visual inspection covers English/light theme and normal/1.5 system font, not all locale/theme combinations.

## Cleanup

Synthetic app state and accepted encrypted backups were archived locally before removing only task-owned users13/14/15/16 and exact isolated helper packages. Original users0/10/11/12 and simulator disks/configurations remain. OMK original scope and both config hashes match the initial snapshot. Current Android user returned to0, adbd returned to UID2000, task-started MuMu1 and ADB15062 stopped. Preexisting MuMu0 remains running with the same PID; instance2 and public AVD were not used. Screenshot protection and original font1.0 were restored in the synthetic Release user before removal.

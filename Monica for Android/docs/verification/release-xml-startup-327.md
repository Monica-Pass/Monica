# Release startup and retained-data upgrade verification

The earlier 327 acceptance omitted the required retained-data Release-to-Release upgrade. Debug tests did not cover the R8 failure. This report records the corrective verification on 2026-11-11; it does not treat those earlier tests as Release evidence.

## Root cause and change

Sardine Android 0.8's Gradle module metadata omits the XML exclusions in its POM. Its simple-xml dependency therefore introduced Xpp3's duplicate Android XML Pull API as application code. R8 renamed `org.xmlpull.v1.XmlPullParser` to `igl` and changed `setInput(InputStream,String)` to `e()`. Android's system `KXmlParser` still implements the platform interface, causing the reported `IncompatibleClassChangeError` while reading existing secure preferences.

The frozen broken mapping exactly matches the user's map ID, `de728c0a992248816134379f440120170e41d6bfaf3d03b9080cc8f935367e17`. Retracing locates `LocalVaultRecovery.kt:241`, before its cache-write operation.

Both builds explicitly restore Sardine's upstream Xpp3/StAX exclusions. A Release build task checks resolved runtime class artifacts and rejects copies of `org.xmlpull.v1`; it runs before Release/R8 tasks. The real old dependency fails the gate; complete corrected builds pass (ordinary: 224 artifacts; F-Droid: 191). No authentication, KDF, storage, cache-refresh or recovery implementation was changed for this fix.

## Ordinary signed Release verification

The downloaded official 326 APK matches its published SHA-256 `895e0f44bdbd7417811a7635dd010f6a7f168e1b02696d070690b6c0f183644a`. Old, broken and corrected APKs all have certificate SHA-256 `ee18951f09e806c104dd85548d777176ae2bec0b202a0d44bc49b9fe3e0b33c1`. The corrected APK was built through the existing signing workflow with R8 and resource shrinking enabled, without debug source sets; build time was 5m43s. Its SHA-256 is `2dda6a9bee1869523f69401fa8ea51ad5b6de74b738d4de76bb6ecabcf7b5712`. The broken and corrected 327 APKs have the same filename/version label, so hashes identify the tested bytes.

All device actions used synthetic data in a dedicated reusable MuMu Android 15/API35 instance. Existing MuMu and public AVD application data were preserved. The official old APK is ARM64; the corrected output actually contains ARM64 and x86_64 libraries. The final installed package reports `primaryCpuAbi=arm64-v8a`, with no secondary ABI, on an x86_64 device advertising a native ARM bridge. This establishes the installed ABI selection, not a separate instruction-level trace of native translation.

Actual UI checks, with no uninstall or clear-data between updates:

- Install official 326; create a master password, login with account/password/URL, bound OTP and account note; stop the process and unlock with the original password.
- Replace with the frozen broken 327: reproduce the exact reported exception and map ID twice. Database/WAL, secure preferences and recovery envelope retain their bytes across the failure; only session/update/diagnostic state changes.
- Replace with 326, confirm the original password and record still work, and create both plain and encrypted WebDAV backups.
- Replace 326 with the corrected signed 327: unlock with the original password; read the original password through the UI, OTP and note; add a new login; cold-start again, reject an incorrect master password, then read both original and new records with the correct password.
- List, upload and download backups against a loopback WebDAV fixture using real HTTP methods and DAV 207 XML. Restore the old encrypted backup in replacement mode, retain local settings, cold-start and read its original password/OTP/note. Restore the corrected version's encrypted backup and verify both records after another cold start.
- Start the real independent `:ime` service, authenticate using the original master password, and fill the original username/password into a separate native Android test application. The external application's displayed input values match the fixture. This uses the optimized Monica APK, not a debug test service.
- Install the broken 327 over already migrated data, reproduce the same exception a third time, then directly cover it with the corrected APK. Original-password unlock and both records succeed without clearing data.

Independent authenticated offline inspection included the SQLite WAL, recovery snapshot authentication, credential fingerprints, password verifier and wrapped data key. It proves the login KDF changed from legacy PBKDF2-SHA256/100000 to explicit PBKDF2-SHA256/600000 while retaining the same 32-byte MDK. The original login ID and every stored column, original custom field and bound OTP remain identical during the in-place upgrade; only the intended new login is added. Database schema 85 and integrity checks pass. The account note lives in credential metadata, not an independent note row.

Both old and new default encrypted exports remain `MONICA_ENC_V1`, with PBKDF2-SHA256/100000 and authenticated AES-256-GCM; ZIP CRC and all record values match their source snapshots. Replacement restore correctly reassigns row IDs and encrypts imported values again; semantic content matches, while local login protection remains 600000 and the MDK is unchanged. These restore results must not be described as byte-identical database restoration.

Final DEX inspection verifies there are no bundled XML Pull platform class definitions and four actual interface calls retain `setInput(InputStream,String)V`. DEX checksums and embedded mapping IDs match the archived mapping; the broken APK provides the negative control with `igl.e()V`.

## F-Droid Release verification

F-Droid was built separately with its own dependencies and policy. The full optimized Release build passed in 13m5s, including the 191-artifact gate. The old local 326 and corrected 327 test APKs share the isolated application ID `takagi.ru.monica.bwsync326fd` and certificate SHA-256 `6e1c4c795d062b74c28c3d4fa13833a71deef19f6501e917ee6b3df60797a4b0`. Both APKs are non-debuggable, minified Release builds; the matching local test certificate is not the official F-Droid distribution certificate. Version codes are 33 and 34 respectively. The device run used the old ARM64 APK; the corrected installed APK also selects ARM64 on MuMu. This does not establish a store-signed update result.

The old APK SHA-256 is `120d34e88104b9366980286288e5f50c9bb92167b440a87c96acc2980f79fb3a`; the tested corrected ARM64 APK is `5a7fd2d196e6f516a47fe03f88bcc4f262d5d05fc5c2503c5eb8ee1beb4ad264`. The additional ARMv7 output was checked statically, not exercised on a device. Both final outputs have no bundled platform XML API definitions and retain the expected interface calls.

Real UI actions created a master password and one login in 326, verified its password after a cold start, then used an in-place package replacement without clearing data. The private data archive immediately before installation and before the new APK's first launch is byte-identical. The corrected APK accepts the original master password, displays the old password, saves a second login and reads both passwords after another process restart. Authenticated offline inspection confirms the login KDF changes from 100000 to 600000, the MDK remains identical, and every original login column and its ID remain unchanged; the new login decrypts under the same MDK. This fixture has no OTP or account note, and those features are not counted as covered by the F-Droid run.

F-Droid's actual WebDAV connection test, DAV 207 listing and backup upload pass against the loopback fixture. Its default unencrypted ZIP is recorded as such; it is not counted as another encrypted-backup test. The uploaded F-Droid backup was downloaded and restored through the real replacement flow while keeping local settings. After another cold start, the original master password opens both restored logins and their passwords display correctly. The row IDs change from 1/2 to 3/4, with exactly two logins remaining, confirming actual replacement rather than only viewing the existing rows. F-Droid screenshot protection remained enabled, so black screenshots are not treated as visual acceptance; newly generated UI trees and displayed field values provide the interaction evidence.

Independent inspection of five F-Droid snapshots and the uploaded ZIP verifies CRC, unchanged MDK, 600000-iteration login protection after restore, and matching login semantics before restore, after restore and after final cold reads. The final cold reads leave the logical database unchanged. The unused old x86_64 APK in the static signer comparison is separate from the ARM64 old APK actually installed for this device run.

## Additional checks and limitations

The ordinary supplementary JVM run passes 18 tests: local KDF vectors (9), WebDAV transfer/error handling (4), and backup-directory failures (5). These are explicitly supplementary Debug JVM tests. An earlier attempt failed before execution because this AGP configuration has no `testReleaseUnitTest` task; its failure is retained. F-Droid metadata validation and all six metadata-script tests pass.

Initial gate prototypes had task-registration, artifact-variant and Groovy helper-resolution errors; each is retained, followed by the working negative control and complete successful builds. MuMu's `uiautomator` sometimes crashes on process exit after emitting complete XML; probe failures are retained, and each observation requires a newly generated well-formed XML file. IME rendering was verified using local screenshots because that window is absent from the UIAutomator tree. Caught WorkManager scheduling warnings in the independent IME process are recorded; they are not counted as fatal startup failures or repaired by this dependency change.

Independent final source review confirms the dependency-only fix, unchanged R8/resource-shrinking configuration and matching gate/APK/mapping hashes. The application Gradle files did not have a pre-build byte-hash snapshot; modification times and the final diff support the build association but are not represented as strict before/after byte-freeze evidence. The maintenance guide now requires retained-data, same-signature Release upgrades for startup/storage/dependency/R8 changes.

Nine generic intermediate `before-fill` XML files reused helper names across editions and cannot represent a complete chronological history. Explicitly named ordinary/F-Droid stage XMLs, private archives and final assertions remain separate. The helper now prefixes future F-Droid intermediate names; no missing intermediate evidence is presented as retained.

The final crash buffer contains exactly the three deliberately reproduced ordinary broken-APK exceptions (PIDs 4726, 4892 and 9000), with the reported interface error; no F-Droid fatal appears. The original input method was verified, adbd returned to shell UID 2000, the task's WebDAV reverse mapping and verified fixture process were stopped, and the dedicated emulator was shut down with all instance data retained. The first immediate shutdown status probe still saw its process running; a subsequent independent status confirms normal shutdown. No application data was cleared or package uninstalled for cleanup.

This is synthetic Release validation on one emulator environment, not a guarantee for every user database, physical OEM/TEE, cloud provider or Android version. This run does not claim an Argon2id/V2-backup, NFC hardware, every KeePass/MDBX format, or official F-Droid-signature upgrade matrix. Private synthetic archives, complete mappings, signing material, APKs and screenshots remain local; no release or tag is created by this verification.

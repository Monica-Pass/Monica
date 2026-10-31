# Android 1.0.324 confirmed storage recovery verification

2026-11-01. Local, unpublished development work; the published 1.0.323b patch and its attachments remain unchanged. Release notes were initially left blank during verification. On 2026-11-01 the user requested that confirmed recovery, automatic password-protected recovery material and its limits be documented in the root/next notes and F-Droid fastlane 31, and authorized code submission. Version 1.0.324 remains unreleased.

## Outcome and boundaries

Unavailable Android Keystore material does not indicate intentional blocking of OMK users. If startup cannot verify encrypted settings, the existing locked recovery screen remains available. Entering a password and pressing recovery now opens a second confirmation dialog; only confirming authenticates the original password and begins replacement storage creation. Cancelling, dismissing, system back, leaving composition, and leaving the foreground discard pending consent and password state. Passwords use non-saveable Compose state; the dialog and blocked Activity keep secure-window protection.

Recovery authenticates the existing independent recovery envelope and password-wrapped MDK before creating a UUID-named encrypted preferences store and its device key. It verifies the staged values, recovery generation and key, then publishes the selector last. It does not rewrite the canonical recovery envelope while staging and does not remove the original store or aliases. Database, attachments, password verifier, MDK, and original recovery material are retained. Biometric convenience is disabled in the staged store; successful recovery still requires normal app login.

A single daemon worker bounds caller waiting to 15 seconds. Cancellation or timeout withdraws permission to publish a new selector. If a native provider call remains blocked, its slot stays occupied until it actually exits; repeated retries neither queue operations nor add threads. A failed confirmed attempt can leave an unused encrypted staging store, but cannot replace or erase the source. Activity recreation after success explicitly dispatches to the main thread.

Ordinary password authentication preserves an existing convenience wrapper and no longer silently creates a missing convenience data key. Permanent-invalidated or unreadable keys are retained. Fresh first-password initialization keeps its normal key enrollment. The patch does not change algorithms, KDF parameters, ciphertext formats, or old C2/V2 data decryption rules.

## OMK and reference provenance

- OhMyKeymint source inspected read-only: https://github.com/ITxiao6666/OhMyKeymint at `ba6f3009644054d5c239650700abcec41f46bafa`.
- Its module metadata supports Android 31+ and arm64-v8a; the shared emulator is API32 x86_64. OMK was not installed, and global KeyMint routing or module configuration was not changed.
- `docs/CONFIGURATION.md` distinguishes cryptographic secrets from injection routing. Changing secrets or routing can make old keys inaccessible. `retire_stale_keybox_bound_keys` targets dedicated old keybox-bound ATTEST_KEY keys and retains ordinary signing/passkey credentials. Rotating attestation certificates alone does not prove loss of all AES keys.
- Local Keyguard reference inspected: `KEY_ALIAS.kt`, `SecureSharedPrefsKeyValueStore.kt`, and `SecureDataStoreKeyValueStore.kt`. Some generic configuration adapters clear or recreate after errors; that policy was not transplanted into Monica. No OMK or Keyguard production source was copied.

## Validation

Distribution: main; APK version `1.0.324-26110112-04`, versionCode `12`. Final Debug main/test build succeeded in 2m 40s; 13 targeted JVM checks passed. Actual APK SHA-256: `c287df3eea9bca972bf3110185b509b0f878aaa3af8cefeed7e43f07c95a2b36`.

Shared sources/tests: 8 identical files. All 14 affected resource configurations are identical between distributions; each APK contains the 42 expected values for the three new confirmation strings.

58 distinct API32 device checks passed across the final verified sources: startup 20, local recovery 20, connection recovery 4, confirmation/UI 9, actual startup Activity 1, update preservation 3, and explicitly opted-in confirmed recovery Activity 1. Repeated UI/startup cases are counted once.

- main-324-core-device.txt: 190.904s
- main-324-final-ui-device.txt: 31.301s
- main-324-final04-ui-device.txt: 45.632s
- main-324-confirmed-activity-final04-device.txt: 19.448s

Coverage includes real AndroidKeyStore deletion and replacement of fixture-owned keys, existing/recovered generations, wrong passwords, absent or corrupt envelopes, commit failure, provider blocking, cancellation, no late selector, occupied-worker retries, old byte preservation, password rotation, protected fields, OTP, attachment keys, software Passkey material, local/KDBX/MDBX and connection recovery. The opt-in Activity case verifies actual startup failure, cancellation without mutations or new aliases, rejected wrong-password confirmation, successful confirmed recovery, Activity recreation, normal password login, and protected-field readback. It requires an explicitly named disposable host user matching the process UID and refuses a pre-existing vault.

The old ordinary implementation failed four regression checks: both confirmation/cancellation UI checks observed recovery already executing at the first button click; separate checks found silent missing-compatibility-key regeneration on password login and canonical envelope mutation when committing recovered values fails. The two UI negatives establish the same missing-confirmation cause, rather than two independently reproduced cancellation bugs. The first envelope fixture intercepted Tink keyset enrollment too early and passed; allowing keyset enrollment and rejecting the actual value commit produced the valid negative result.

Intermediate failures remain recorded locally: initial incorrect Gradle cache selection prevented offline dependency resolution; a SecureFlagPolicy enum typo and a test-only chain on Unit-returning input APIs prevented compilation. The first actual Activity case timed out after selector publication under the v1 Compose unconfined test runtime; explicit main-thread Activity dispatch passed the same chain. Initial screenshot capture selected an unrelated root and was rejected visually; `isDialog()` capture now shows both complete large-text confirmation dialogs. Bright/dark native screenshots and editable local M3E Canvas files remain in ignored local design directories and are not embedded in this document.

## Limits and cleanup

These are deterministic key-loss, replacement, corruption, and blocked-provider tests with synthetic data. They do not reproduce the user's unknown OMK version or establish compatibility with every root module. Permanently lost keys cannot be reconstructed for old fields that depend solely on those keys and have no independent recovery material. No new R8 or physical-device run was performed.

Only the pre-existing shared Monica_Issue136_API_32 AVD was started. Host identity checks restricted synthetic setup and the one ordinary fixture reset to this task's disposable guest14. Final cleanup removes that guest, restores user0, and stops the AVD started by this task, preserving users0/10/11/12, AVD configuration and the data disk. Code submission was authorized separately after this verification and cleanup. No new Release, tag, or APK delivery is included.

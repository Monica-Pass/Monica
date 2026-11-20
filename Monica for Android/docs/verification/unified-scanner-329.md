# Unified authenticator and passkey QR scanning — 1.0.329

## Behavior and implementation

The authenticator overflow menu has one scan action in both standard and tile layouts. The quick-scan destination accepts supported authenticator URI formats or a validated caBLE v2 FIDO QR envelope. Existing authenticator parsing, selected storage targets, duplicate handling, migration review and save callbacks remain in their original route. Dedicated passkey shortcuts elsewhere remain FIDO-only; editor scans keep their original result-return contract.

FIDO validation checks bounded decimal encoding, little-endian groups, CBOR structure, a valid compressed P-256 public key, a 16-byte session secret and known optional field types. Passkey request types are limited to the legacy default, `ga` and `mc`; digital-credential `dcp`/`dci` requests are not misclassified as passkeys. Unknown map extensions remain allowed. Only validated FIDO URIs reach Android ACTION_VIEW. Missing handlers show the existing failure message; launch success is not authentication success. The format was checked against Chromium `device/fido/cable/v2_handshake.cc` (`qr::Parse` and `DigitsToBytes`).

Camera and gallery reject multiple distinct supported results, while duplicate detections of the same payload count once. Unrelated codes do not hide a single supported code. Unified full-resolution camera sweeps confirm the other polarity before accepting a candidate. The warning can only cover codes successfully decoded in the current image; no optical scanner can guarantee detection of every visible or partly obscured code. Existing camera session liveness, frame release and one-shot delivery remain. Gallery requests cancel when their screen leaves or pauses and await resumed state before delivery.

The frame has four fixed teal-green corners and no rectangular border or inner fill. Translucent header and footer explicitly use the theme foreground color so dark mode keeps readable text. All 18 existing language configurations contain the three new messages (54 values), matched between editions.

Database schema 85 and the unreleased 86-to-85 bridge are byte-identical to the task baseline in both editions. No schema, KDF, authentication or stored-user-data changes were made for this task.

## Verification scope

Reused the already-running MuMu API 35 / x86_64 instance. Main and F-Droid used isolated application IDs with synthetic records; existing user packages and Android users were retained. Screenshots and editable M3E Canvas remain local in ignored design directories.

Camera tests use real CameraX streaming, frame ownership, navigation and lifecycle, with synthetic QR pixels substituted at the analyzer boundary. Gallery tests decode real generated image files; ActivityResult monitor fixtures exercise selecting/cancelling the production gallery button without claiming manual DocumentsUI selection. FIDO intents are captured by a test ContextWrapper, with a missing-handler negative path. This does not certify a physical camera, OEM firmware, Google Play Services, BLE cross-device exchange or real passkey login. No R8/release build or full MainActivity navigation suite was performed.

## Earlier failures and corrections

- Initial ordinary build (`main01`) succeeded; its first device batch passed 24/25. All scanner cases passed. The standard-list swipe-delete assertion failed in the emulator's wide landscape viewport; the existing swipe drag limit is 300 pixels while its threshold scales with card width. This unrelated wide-card gesture behavior was not changed here.
- Two attempts to change the global viewport still left MuMu in landscape and ended with a Compose idle timeout during fixture teardown. Both original logs are retained. Tests now request portrait directly from the test Activity, preserving their delete and cancellation assertions.
- Ordinary `main02` and F-Droid `fd01` each passed all 33 device methods after that fixture correction. Screenshot review nevertheless found dark-theme title/footer contrast was insufficient. Explicit foreground colors and a light-pixel regression assertion were added; final validation is recorded below.

## Final results

| Edition | Final build | JVM | MuMu methods | Device runner |
| --- | --- | --- | --- | --- |
| Ordinary | 190.481 s | 42/42 | 38/38 | 81.695 s |
| F-Droid | 101.530 s | 42/42 | 38/38 | 84.285 s |

Final batches include camera/gallery OTP and FIDO routing, malformed and unsupported content, mixed-polarity full-resolution decoding, multiple/duplicate codes, missing system handler, gallery selection/cancellation and cancelled decoding, idle recovery, background/resume, queued results after leaving, frame close/callback failures, legacy Steam and boarding-pass scanner contracts, transparent corner pixel checks, standard/tile unified menus, selection/delete cancellation, OTP editor parameters, migration persistence, saved filters and Bitwarden source cards. The production quick-scan storage callback was inspected but not driven end-to-end through MainActivity.

Viewed final ordinary light, dark 1.5x, multiple-code and unified menu screenshots, and F-Droid dark 1.5x/multiple-code screenshots. Device density 420 provided a roughly 411dp portrait width for final validation; earlier ordinary functional runs used density 280. Fixed foreground contrast was confirmed visually and by a title-pixel assertion. The camera preview in MuMu is blank; synthetic code pixels exercise the analyzer, not the displayed preview.

Both final APK pairs had their package identities, x86_64 main ABI, signatures and SHA-256 recorded. 37 shared paths match between editions; MainActivity was patched separately to preserve edition differences. Database/bridge bytes and frozen final source hashes were checked. F-Droid metadata and six script tests pass, as do working-tree whitespace checks. Root and local next release notes plus F-Droid code-36 summaries were updated; version fields were not changed.

All four isolated test packages were uninstalled. Android users and the original package set match the baseline; display size, density, rotation and font settings were restored exactly. The preexisting MuMu instance remains running. No commit, push, tag, release, APK handoff or user-data operation was performed.

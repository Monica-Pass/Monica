# Bitwarden server configuration — 1.0.325 verification

Both ordinary Android and canonical F-Droid completed this server-configuration batch.
This accepts AUTH_A18 within the boundaries below; it does not complete the broader
58-item Bitwarden audit or implement organization/SSO/device-trust features.

## Result

- Each edition: 183 JVM tests, 120 distinct API32 native methods, no failures or skips in the final runs.
- Main final native build: 228.078s; F-Droid final build: 374.874s.
- Main native driver: 347.427s; F-Droid: 358.771s.
- 53 new strings across 18 resource configurations: 954 actual APK values verified per edition.
- 136 paired source files checked; all identical except WebDavHelper's retained F-Droid HTTP adapter.
- Main JVM result comes from input-reset-green. The final build only restores the prior cursor-callback comparison in the UI wrapper; the final native suite exercises that exact APK. F-Droid JVM and native tests use its final build.
- APK versions/digests, sources and method names are in accompanying JSON. Debug builds only; no APK delivery, tag or Release.

## Implemented and exercised

US/EU presets and explicit Base/Web/API/Identity/Notifications overrides use a captured
environment across authentication, registration, password hints, challenge continuation,
refresh, profile/sync, foreground notifications, Send and attachment consumers. Proxy
headers and TLS material are protected and bound to the saved configuration. Foreign
signed-transfer destinations do not inherit account credentials. JVM transport tests
include redirects and TLS/mTLS isolation; native HTTPS authentication uses a synthetic CA.

Existing-account configuration edits require reauthentication. Replacement, lock,
cancellation and late callbacks retain the previous data and reject stale publication.
The additive Room 83-to-84 migration and explicitly encrypted Monica configuration
backup preserve old accounts/records, use fresh protected bindings for restored accounts,
and retain newer existing settings and deliberate account selection.

The native configuration editor supports service endpoints, scoped headers, certificates,
and explicit DNS TXT candidate review. Discovery tests inject bounded resolver responses;
they are not evidence of a real public-DNS deployment. Native tests cover cancel/edit/lock,
separate API/Identity routing, exact spaces in secret inputs and readable large-font controls.
The already-reviewed editable local Canvas is kept in ignored docs/design/bitwarden-server-325.
Screenshots remain in ignored docs/design/bitwarden-server-configuration-325.

## Regression discovered during acceptance

Rapid input followed by an external clear before the next frame left the custom text
field's internal TextFieldValue unchanged because LaunchedEffect(value) saw the same key.
Both filled and outlined negative controls failed while the parent value was empty.
The wrapper now renders the authoritative value and reconciles selection after composition;
final tests verify clearing, selection preservation/clamping and no callbacks for external
resets. The three original password/OTP/registration lifecycle failures also pass.
The full-screen configuration dialog now sets status/navigation icon appearance from its
surface luminance; final native captures were inspected for contrast and clipping.

## Failures retained

- `main-transport-01`: 44/48 passed, 4 failed.
- `main-auth-repro-02`: 7/11 passed, 4 failed.
- `main-full-04`: 102/118 passed, 16 failed.
- `main-input-reset-red-05`: 0/2 passed, 2 failed.
- `main-revised-fixtures-06`: 13/16 passed, 3 failed.
- `main-final-full-08`: 112/120 passed, 8 failed.

The original four auth failures were caused by a fixture-only isolated Repository while
Send used the singleton. Installing/restoring that synthetic repository made 11/11 pass.
Thirteen full-suite failures came from stale fixtures: password masking semantics, old
remembered-token scope/server normalization, main unlock without Bitwarden unlock, and
notification rows whose injected session was still marked disconnected. After correcting
these prerequisites, 13/16 passed on unchanged production code; the remaining three were
the input-reset defect above. No production lock or credential checks were weakened.
The first final-package run passed 112/120; eight backup methods refused the two
synthetic remembered-MFA accounts left by the earlier cleanup failure. Their
exact synthetic identities and loopback endpoints were verified, a private local
snapshot retained, and only Monica in owned user14 was reset before the same-APK
full rerun. Other users and packages were not cleared.
Earlier CA/build failures remain in the task's local raw evidence; this report does not
reclassify them as passing or assert an unproven provider-specific CA root cause.

## Boundaries and cleanup

Real public DNS, physical/OEM devices, deployed reverse proxies/certificates, hardware
KeyChain mTLS, lower-API devices and R8 were not tested. There is no independent Icons
setting; external browsers do not inherit app headers or mTLS. Existing US/EU defaults
remain; configuration changes are not account migration. JSON import/export capability
and the remaining audit rows are not upgraded by this batch.

Only synthetic accounts were used. Restored Android user0, removed owned guest14
Monica_BitwardenTransport325_Test, stopped the task-started public API32 instance, and
preserved users0/10/11/12, registration/configuration/data disks and unrelated emulator5554.
No existing user account was reset. The requested GPT-6.1 Sol light worker hit its provider
rate limit before execution; root performed the successful resource/metadata checks.

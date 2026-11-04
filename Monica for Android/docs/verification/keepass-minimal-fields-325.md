# KeePass necessary fields — issue 177, unreleased 1.0.325

Issue: https://github.com/Monica-Pass/Monica/issues/177

## Behavior

The ordinary original-vault password editor previously emitted thirteen UI fields even for default state. It now omits exact false/zero/default-icon/empty values for the specifically owned fields. A minimal synthetic native login creates the five standard KeePass fields instead of eighteen. Existing default metadata is removed only when that entry is explicitly saved. New original entries continue to avoid transient MonicaLocalId/MonicaSecureItemId; existing compatibility IDs are preserved because older references and transfer paths can require them.

Nondefault favorite/archive/group-cover state, sort values, categories, note/SSO links, passkey bindings, icons and account/replica identities remain. Arbitrary empty user fields, whitespace, unknown fields, plugin fields and protected values are not swept. Unchanged or unresolved REF expressions retain their raw syntax and protection. Resolving a favorite REF to true and explicitly clearing it removes that field correctly.

Credential metadata still carries the stable identities, labels and ordering required for multi-account/password stacking and actual account notes. Its optional note property is omitted when exactly empty; clearing an existing note first removes its prior JSON property, while whitespace notes and unknown future keys survive. Content-order metadata is retained because it can represent an intentionally enabled empty section or future content.

Secure items omit an empty image-path field and false favorite field. Type, payload and the empty independent-note sentinel are retained; removing the latter would confuse the public note body with separate Monica notes. Cross-format preflight treats only the two known literal defaults as equivalent to absence and continues rejecting unknown native fields. Older Room-only metadata retains its existing missing-field behavior; native original-vault readers already use missing defaults. No bulk migration, authentication, KDF or storage-format change.

## Verification

- Ordinary and canonical F-Droid full Debug main/test builds passed (235.787s / 223.160s), at 1.0.325 (ordinary sequence36/code12; F-Droid code32).
- Each edition: 356 JVM tests, zero failures/errors/skips, covering KeePass mutation/patch/replay/format/field handling and credential metadata.
- Each edition: 48 distinct API32 device methods obtained passing results. Ordinary first batch was47/48 due solely to a new assertion comparing randomized in-memory protection salts; the corrected assertion separately verifies raw text, displayed value, protection bits and native metadata, and that one method passed on the unchanged primary APK. F-Droid first batch was47/48: the actual editor test asserted immediately after the title loaded, before the separate account-metadata read populated the note. The stored note was already correct. Waiting for that note before asserting/clearing passed on the same primary APK; ordinary also passed this strengthened UI method. No skipped method is counted.
- Ordinary runner: initial batch 242.665s, corrected preservation method 4.918s, final UI 16.933s. F-Droid: initial batch 205.856s, final UI 14.45s. Raw results and package SHA256 values are preserved alongside this report.
- Real production editor: create a login, save/reopen, add the issue's synthetic account note, save/reopen, clear it and save again; verifies no default UI noise, stable UUID, unchanged password and absent empty note property. No credential/order JSON is introduced for the simple initial login; actual account/replica linkage may still be present.
- Original encrypted KDBX: simple create/edit/reopen; cleanup on explicit save; enabled states then explicit clear; raw REF and protected custom/empty/whitespace/plugin fields; attachment and untouched-entry preservation; account identities/order/unknown JSON; secure-note body/sentinel; true-to-false and unchanged-false favorite references; legacy local metadata preservation.
- Existing regression includes all stack modes and account resaves, every add-content encoding plus actual add-content UI, history stability, denied/stale writes, lock revocation, native scope/filters, passkeys and notes, and portable note/attachment transfers. Sparse/old-default/favorite notes transfer; an unknown empty custom field still blocks lossy conversion.

## Negative control and corrections

Original ordinary seq35 APK with new tests: four methods, three expected excess-field failures and one account-metadata preservation pass (runner 53.713s; driver105.313s). The simple entry contained all18 fields; explicit clearing still wrote default values.

Initial full build failed at AndroidTest compilation because the fixture tried to read payload from an MDBX browser summary; it was corrected to use the native object. Review identified and corrected overbroad legacy-state resetting and unconditional secure REF retention before the validated production build. Initial AVD preparation before boot and a Windows GBK decoding error were driver failures; preparation succeeded after boot with UTF-8. Historical failures remain in local task logs.

Only the preservation assertion and UI loading wait changed after both full builds. Supplementary test-only builds passed; all primary APK bytes match their full-build hashes and both source freezes differ only in those two AndroidTest files. The eight mirrored verification paths include the unchanged legacy viewmodel; seven paths contain this task's semantic production/test edits. Both repository diff checks pass. Existing unrelated work is preserved.

## Scope and environment

Synthetic KDBX4 fixtures only, public Monica_Issue136_API_32 emulator. Existing users0/10/11/12 and original AVD configuration/data disks are preserved; task-owned guest14 is removed and the task-started emulator is stopped at closeout. No user vault was opened or rewritten. No new physical-device/OEM, hardware token, real remote service, third-party client UI or R8 run. Standard KDBX field/attachment/history behavior is checked through actual serialized files, not claimed as blanket compatibility with every plugin.

Unreleased325 root/next bilingual notes and F-Droid32 summaries updated. No commit, push, GitHub comment, tag, release or APK delivery.

# Monica Plus payment confirmation — 1.0.323

Verified on 2026-10-30 in the ordinary and canonical F-Droid workspaces.

## Behavior

The payment page's unlock button opens a Material 3 confirmation dialog. The Simplified Chinese body is exactly `我已诚信付款，确认解锁Monica plus`; actions are `取消` and `确认解锁`. Traditional Chinese and English resources are included. Confirmation calls the existing local activation callback, then navigates back. Cancel, Back, and outside dismissal only close the dialog. The pending dialog uses saveable UI state.

This change is confined to the payment screen and resources. KDBX save and cross-database operations are unchanged by this task.

## Validation

| Check | Ordinary | F-Droid |
| --- | --- | --- |
| Debug app/test builds and selected JVM checks | Pass, 874.5 s | Pass, 830.1 s |
| Existing Plus JVM checks | 3 passed | 3 passed |
| Existing DonationQrPreviewTest device suite | 3 passed, 18.937 s | 3 passed, 16.756 s |

JVM checks: `PlusLocalActivationGuardTest` (2) and `PlusBlurRemovalTest` (1). Device checks cover payment QR preview/save, the selected custom-amount QR, and the support-page QR flow. The optional `capturePlusUnlock=true` review mode opens the real PaymentScreen confirmation dialog, captures it, and clicks Cancel with inert activation/navigation callbacks. It does not activate Plus or test persisted activation. The confirmation callback ordering and dismissal handlers were reviewed in source.

Both native screenshots were inspected: English text and both action buttons fit without clipping. The device uses English; Chinese text was checked in resources and the local editable Canvas. Canvas and native screenshots remain under the ignored ordinary `docs/design/plus-payment-confirmation-323/` directory. The design README retains the full local Canvas share URL. Images are not included in this verification record.

The running public Android 32 x86_64 AVD `Monica_Issue136_API_32` was reused. Existing app data was retained, no master password was configured, and no Plus activation was performed. Test-owned saved QR images and private screenshots were removed after capture. No AVD or Android user was created. The public AVD remains running.

Builds compiled changed sources while reusing existing BuildConfig identities. Versions remain 1.0.323 (ordinary code12, F-Droid code30). No minified Release or physical-device run was added. Root bilingual release notes and F-Droid fastlane 30 were updated. No commit, push, release, or APK delivery was performed.

Companion evidence: `plus-payment-confirmation-323-build-evidence.json` and each flavor's `plus-payment-confirmation-323-*-device.txt`.

## Localization completion — 2026-10-30

The confirmation body and button now cover all 13 languages in the app's Language enum: English, Simplified Chinese, Traditional Chinese, Classical Chinese, Vietnamese, Japanese, Russian, Korean, German, Spanish, French, Polish and Italian. There are 14 resource configurations because Simplified Chinese also has its existing mainland-China override. The requested Simplified Chinese text is unchanged.

Both Debug APK builds passed after this translation-only update (ordinary 23s, F-Droid 21s). AAPT2 inspection of the actual APKs confirmed both strings in every configuration, with exact equality to source XML and between flavors. Evidence is in `plus-payment-confirmation-323-localization.json`. The earlier build hashes, device runs and screenshots above describe the original confirmation implementation before these extra translations; no new device/JVM suite or per-language screenshot review was performed for this resource-only change.

The root AGENTS.md now requires all supported app languages for new feature text. Root release notes and F-Droid fastlane 30 were updated. No Kotlin logic, locale selection, Plus state or vault behavior changed in this follow-up.

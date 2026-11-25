# Italian language label · 1.0.331

Chinese UI configurations now display the Italian language option as `Italiano`, its native name. The five affected resource directories are `values-zh`, `values-zh-rCN`, `values-b+lzh`, `values-b+zh+Hant`, and `values-b+zh+Hant+TW`. Existing locale/UI test expectations and the Taiwan resource contract were updated. Other language packs retain their existing labels.

Normal Android and canonical F-Droid builds both passed `assembleDebug` and `assembleDebugAndroidTest`, using isolated package names with x86_64 native libraries. Final build times were 70.298 seconds and 66.846 seconds. Source fingerprints stayed unchanged during each build; installed application and test APK hashes matched the recorded artifacts.

Each edition passed these three existing instrumentation methods on MuMu Android 15 / API 35:

- `ItalianLocaleInstrumentedTest.languageNameUsesItalianAutonymInChineseContexts`: compiled Italian label resolved across the supported explicit app languages.
- `LanguageSelectionDialogTest.italianOptionUsesNativeNameAndCanBeSelected`: the real dialog displays and selects `Italiano` in Chinese, dark theme and 1.5× text.
- `TaiwanLocaleInstrumentedTest.everyTaiwanPackagedValueMatchesItsSourceAndFormatsSuccessfully`: packaged Taiwan resources still match their contract and format successfully.

Both dialog screenshots were inspected and show the selected `Italiano` row unobstructed. Images remain local in ignored design folders. Runner times were 2.503 seconds (normal) and 2.076 seconds (F-Droid); driver times were 6.204 and 4.677 seconds. Both batches passed on their first final run. Earlier intermediate builds used translated Chinese labels before the requirement was corrected and are not final acceptance evidence.

Static checks parsed all ten changed XML files and both JSON contracts. Seven shared source/test paths match after newline normalization; each Taiwan contract was compared to its own edition baseline to retain platform differences. An initial whole-contract equality check failed because those editions already differ; targeted baseline comparison passed without overwriting either contract.

Only the task's isolated packages and secondary user were removed. The original user, existing users and official Monica package metadata were preserved, and the task-started MuMu instance and private ADB server were stopped. No physical-device or Release/R8 validation was performed for this resource-only correction. No commit, push, release or APK delivery was made.

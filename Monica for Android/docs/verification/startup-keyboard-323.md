# Startup search and keyboard vibration — 1.0.323

Date: 2026-10-29. Development build, unreleased.

The startup-search switch is under Page adjustment customization and defaults to off. It waits for persisted settings and the default tab before opening search on the first main page. Closing search or changing pages consumes the request. Its value is included in page-adjustment export, import and portable settings backup; old backups default to off.

The keyboard-vibration switch is under Autofill settings → Monica keyboard and defaults to off. Settings use the existing multiprocess DataStore shared by the app and the IME service. Version-1 option files retain PIN shuffle and preview flags and migrate with vibration disabled. A physical press emits one standard 20 ms click; holding Delete repeats deletion without repeating vibration. Accessibility clicks also emit one click. PIN preview suppression and vibration are independent.

Normal edition verification:

- The normal APKs were successfully packaged for arm64-v8a, armeabi-v7a and x86_64. All current runtime source files predate that successful package; the subsequent build of the latest test APK also passed.
- 11 relevant unit tests passed: keyboard-option migration/serialization (3), PIN order and input behavior (3), and settings search (5).
- 4 device tests passed on the reused Monica_Issue136_API_32 AVD: settings loading, search focus with actual IME window visibility, navigation without repeated expansion, and all keyboard-option toggles with persisted values.
- The earlier IME-visibility failure came from the bare test activity's Compose insets observation. The corrected test reads the activity window's IME insets and passes without changing production search behavior.
- The native settings screenshot was inspected locally. No screenshots or design assets are included in this document.

F-Droid verification:

- Source build and debug/test packaging passed for arm64-v8a, armeabi-v7a and x86_64.
- The same 11 relevant unit tests passed with zero failures or errors.
- The same 4 device tests passed on the shared API-32 AVD.
- The local candidate under `Monica-main/fdroid` received the matching feature changes, version configuration and release notes. Its common startup-search and keyboard files match the verified checkout; it was not separately rebuilt.

Normal versionCode remains 12. F-Droid versionCode is 30. Both version names are 1.0.323; release notes are under Chinese and English sections. Normal 1.0.322 notes were archived with their 2026-10-27 release date, and published F-Droid archives and changelog 29 were preserved.

The emulator verifies the event path and settings; it cannot establish the physical feel of the vibration motor.

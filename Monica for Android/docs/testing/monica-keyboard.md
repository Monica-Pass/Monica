# Monica keyboard verification — 2026-09-26

The keyboard now loads a small password projection, retains its search/sort index while unlocked, and invalidates it after database changes. Search matches entry content rather than the database's label: selecting a Bitwarden source no longer makes every password match “Bitwarden”. Multiword queries search title, account, website, application name and package together. Large lists use the existing Rust batch search with a Kotlin fallback.

Autofill settings contain two independent, default-off options: shuffle number keys, and hide number popups/pressed highlights. A dedicated multiprocess DataStore lets the settings activity and the `:ime` service observe the same values. Digits stay in place while typing; turning shuffle off restores the standard order. Password inputs suppress key previews.

The native layout follows the [editable M3E design](../design/monica-keyboard-m3e.md): equal letter widths, a centered second row, symmetric Shift/Delete, rounded ordinary keys, capsule Enter/mode keys, a uniform numeric grid and bounded landscape width. Delete repeat, swipe-to-clear, undo, Shift, Enter, mode switching and the existing vault panels remain covered.

## Final checks

- Main debug app and AndroidTest packaging succeeded (`1.0.314-26092612-17`).
- **486 focused JVM tests passed** across 98 suites, including keyboard, KeePass, MDBX and WebDAV checks.
- **27 keyboard device tests passed** in 62.315 seconds on the shared `Monica_Issue136_API_32` x86_64 AVD.
- The device selection covers `ImeVaultLoadInstrumentedTest`, `ImeKeyboardSettingsInstrumentedTest`, `MonicaKeyboardLayoutTest`, `MonicaImeUiTest`, `MonicaImeSystemTest` and `MonicaImePerformanceTest`.
- The real system IME test enters `0926` into another application's numeric field, checks stable shuffled positions, disables shuffling while the keyboard is open, verifies restored positions and types `q` into a text field. It restores the original IME and both preferences afterward.
- Loader tests cover wide records, Rust/fallback Unicode search, cache reuse/invalidation, overlapping refresh requests, locking during a read and changing a query during a read. OTP filling reads the latest key at click time; a read failure or changed input connection cannot commit stale text.
- Native light/dark, 320dp narrow, 1.5× font and landscape renderings were inspected. Screenshots are retained beside the M3E design.

The final settings test waits for the visible Compose switch to update before checking persistence. The system test refreshes accessibility nodes before comparing digit positions, since Compose can reuse a node after shuffling. Both earlier test failures were reproduced and checked against the actual rendered keyboard; temporary diagnostic logs were removed.

## Measured loading and search

The same service loader and sort path were measured on the shared AVD with 3,000 synthetic Chinese records in an in-memory Room database and 24,576,000 bytes of notes. Each loading sample rebuilds the index. These measurements isolate loading/projection/index work, rather than network latency or a physical phone's complete startup time.

| Measurement | Before | Final |
| --- | ---: | ---: |
| Uncached load plus alphabetical index, median of three | 17,634 ms | 3,814 ms |
| First indexed query, median of three | — | 27 ms |
| Cached two-term query, median of 60 | — | 1.636 ms |

The uncached path improved by **78.4%** in this fixture. Final loader samples were 4,101 / 3,814 / 3,750 ms and already include index preparation; the separate sorter measurement must not be added again. Cached queries reuse normalized fields and sort keys, and never repeat a Room load or ICU transliteration.

Main task evidence is retained in `.codex-tasks/20260926-ime-improvements/raw/` at the main repository root: `build-final-verification.log`, `main-jvm-final.json`, `device-keyboard-final.log`, `performance-final.json` and `native-final/`. This is focused validation, not an assertion that every repository test was run.

Source, tests, the DataStore catalog version, release notes and design/test records are synchronized to F-Droid. The previously deferred F-Droid build and functional validation completed on 2026-09-26; see the [F-Droid follow-up report](fdroid-1.0.314-verification.md) for exact counts, the separate emulator benchmark and limits.

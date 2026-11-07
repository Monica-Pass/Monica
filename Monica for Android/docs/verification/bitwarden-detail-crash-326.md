# Bitwarden detail crash — pending fix for 1.0.325

Version designation restored to 1.0.325 (ordinary code 12 / F-Droid code 32) at the user’s request after validation. The recorded test APKs were built as 1.0.326 (code 12 / 33); they were not rebuilt or renamed for this version-only correction. The implementation and test source hashes are unchanged.

The released ordinary 1.0.325 attachment context factory was called synchronously from Compose `remember`. Its authenticated client construction reads the current transport stamp through Room. Opening a Bitwarden password detail could therefore throw `Cannot access database on the main thread`, even without requesting an attachment download. The same construction pattern existed in note, card and identity detail/editor screens.

The factory now suspends and runs transport/client preparation on IO. It retains the original account/transport checks, checks account and global session generations before returning, and clears its owned key copy if cancellation discards a prepared result. The shared Compose loader starts empty for each owner/session, cancels on replacement/disposal, clears the owned key copy, and handles unavailable contexts without terminating composition. Four ViewModel wrappers also suspend so synchronous misuse no longer compiles.

A synthetic account uses real Repository TLS login and Room with the normal main-thread query prohibition. On the unmodified production code, the new main-dispatcher regression fails with the same `getTransportState → isTransportCurrent → requestSession → getAttachmentBitwardenContext` stack. Baseline build succeeded and 12 existing session JVM tests passed; the expected failing device regression took 2.366 seconds. This establishes the bug in Debug on API 32 as well as the retraced release trace; it is not specific to R8.

## Verification

| Check | Ordinary | F-Droid |
| --- | --- | --- |
| Debug app/test build | 788.806 s | 844.54 s |
| Session JVM regressions | 12 passed | 12 passed |
| Final distinct API 32 device methods | 22 passed | 22 passed |

Ordinary authentication/request-session tests passed 7/7 (8.009 s). The first UI batch was 3/5: the synthetic gate blocked before Compose advanced a frame, and the scroll matcher selected both the vertical detail list and the horizontal attachment carousel. The test now advances frames while waiting and selects the vertical list explicitly, with the original assertions and time limits retained. The pending global-lock test also awaits the actual factory completion. Rebuilding only the test APK took 169.642 s; the production APK and generated build configuration stayed byte-identical. The final UI/lifecycle batch passed 5/5 (22.085 s), and account-management regressions passed 10/10 (13.56 s).

F-Droid passed its combined authentication/UI batch 12/12 (22.585 s) and account-management batch 10/10 (8.305 s). The device suites cover main-dispatcher context creation with real TLS login and Room, three actual detail open/back/reopen cycles, no automatic attachment download, original row/key preservation, cipher replacement, owned-copy disposal, account/global locking, cancellation/retry, token refresh and guarded account mutations.

An additional ordinary detail-only visual run passed 1/1 (8.536 s); a native display capture was inspected for the real synthetic detail, masked password and pending attachment. It is a separate repeat, not an additional distinct method. Screenshots remain in ignored local design storage. No F-Droid-specific visual styling change was made. All 15 shared source/test files match after normalizing the existing repository line-ending difference; final source inputs still match the verified builds. F-Droid metadata and six release-check script tests pass. The original expected baseline failure and both initial UI harness failures remain in the evidence.

## Scope

Tests use synthetic data on API 32/x86_64. The reported OEM/API 37 device is not available locally; there is no claim of OEM/API 37 or new R8 validation. Separate partial-sync warnings in the supplied log are outside the confirmed crash fix. Authentication, encryption/KDF, Room policy, database content, and transport revision protections are retained. Published 325 archives, tags and user-supplied packages are unchanged. No new release or APK delivery is performed.

The task-owned synthetic Android user was removed and the original users were preserved. The emulator started by this task was stopped; its registration, configuration and data disk remain available for reuse. No commit, push, tag, Release or APK delivery was performed.

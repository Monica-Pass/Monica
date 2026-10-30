# KDBX incremental password projection and save comparison

Date: 2026-10-30. Ordinary and canonical F-Droid 1.0.323, unreleased.

Status: both flavors built and passed correctness checks. Timing verification is complete, but the performance goal has not passed: cohorts disagree and long saves remain. These results evaluate this limited local-save optimization; they do not establish stable overall acceleration or physical-device/cloud performance.

## Change and safeguards

Single-entry saves retain parsed password data for unchanged immutable entries. Reuse requires the same database, identical entry objects, unchanged parent/group path and recycle-bin context, unique UUIDs, and no REF dependencies in either revision. External reloads produce new entry objects and rebuild their projections. REF-containing vaults retain a full password projection. The browser, secure-item/photo restoration and Passkey paths still refresh, so this is a limited incremental password projection, not a fully incremental vault engine.

Monica presentation rows and custom-field lists retain their identity when their final values are unchanged. SSO/note links are still resolved from the current workspace before rows are reused. The store holds the previous projection only in memory; it does not retain the database credentials or create a Room content index. Existing snapshot clearing, draft revisions and lock-version checks remain in place.

Entries without standard OTP fields or Monica OTP data skip OTP parsing. Empty Monica OTP JSON returns the standard OTP value directly rather than creating a failed JSON parse. Real OTP fields retain the existing aliases, reference resolution and precedence rules.

No file writer, KDF parameter, credential format, cross-target selection, replica deletion, move ordering or remote-upload algorithm changed. Fifteen existing mutation/transfer methods were compared with their preserved sources and are unchanged. Successful navigation still follows the existing file commit and verification. Cloud upload decoupling and streaming XML remain separate, unimplemented stages.

## Feedback loop and diagnostic evidence

The existing real Monica editor test creates a 300-entry synthetic SAF vault, primes the selected-vault list, invokes the save button's semantics action and measures its navigation callback. It asserts one commit, the persisted edit, custom icon/history retention, no native-vault Room index, and draft revocation after locking. The KDF is Argon2d v1.3, 32 MiB, eight passes, two lanes.

Three fresh pre-change runs took 935, 755 and 687 ms from handler to navigation. These are a new baseline observation, not a claimed speedup over earlier 1.3-second samples. ART sampling identified XML encoding and rebuilding the workspace, including repeated OTP/empty-JSON parsing for ordinary passwords. The 2169 ms sampled run overlapped compilation and carries profiler overhead; it is excluded from performance comparisons. The temporary sampling entrance was removed from the test.

The new regression failed on the previous APK because an unchanged row was reconstructed. The new APK passed both new cases: retaining unchanged parsed/presentation objects after a real save, and rebuilding parsed inputs after explicit process-cache invalidation/reload. The latter checks a real cache invalidation; `openNativeBrowser(id, true)` only requests password membership and does not force a reload.

## Verification

Ordinary: main/test build and 13 ownership/cross-database JVM checks passed in 13m29s; the complete existing 80-case KDBX device suite passed in 231.563s. The two new projection cases passed in 6.645s. The 80-case run overlapped F-Droid compilation, so its timing output is correctness evidence only.

Ordinary additionally passed 18 OTP JVM cases (16 codec and two extension cases), bringing this batch to 31 relevant JVM cases. F-Droid main/test build and the same 31 JVM cases passed in 15m29s; its complete 83-case device suite passed in 314.878s (original 80, projection two, disk-backed local editor one). Ordinary also passed the local-editor case during repeated comparisons, covering the same 83 distinct cases across invocations.

Final test-only rebuilds added the common target-close metric and passed in 3m 59s / 7m 6s (ordinary/F-Droid). The updated F-Droid editor case passed; ordinary exercised that final case 60 times across the old/new series. Production APKs were unchanged during these test-only rebuilds. The opt-in KeePassDX case also passed its final KDF assertions in every measured peer run.

## Comparison series and observed environment limits

Environment: the existing shared `Monica_Issue136_API_32` AVD, Android 32 x86_64, serial `emulator-5582`. Monica uses Debug APKs; KeePassDX uses the official 4.5.6 libre Release APK. This is not a physical-device or same-build-type benchmark.

The local comparison uses a disposable disk-backed Room database with 300 encrypted synthetic passwords and the same Monica editor/navigation boundary. Its first pre-change observation was 195 ms. Setup and encryption of the initial fixture are outside the save timer.

The KeePassDX comparison opens a byte-for-byte copy of the synthetic Monica editor fixture through the same test-provider implementation. It times the accessibility save action, the resumed entry activity, and the provider's target-write open/close callback. File-close callback timing is not a power-loss durability measurement. The saved file is decoded afterwards, checking the edited title, 300 entries and unchanged Argon2 algorithm/cost parameters. Credential entry and KeePassDX's user-verification challenge happen before timing. The comparator is opt-in (`compareKeePassDx=true`) and never opens a user vault.

Preliminary new Monica handler/navigation: 823 ms. Preliminary KeePassDX action/navigation: 861 ms; target-write open/close callbacks: 595/601 ms. Single runs overlap and are not the final conclusion. The initial comparator setup timed out before saving because its accessibility event configuration did not expose the controls; the test harness was corrected and that failed setup is excluded.

No compilation, profiler, or other test suite ran concurrently with the formal timing series. Three ABBA cycles used five editor runs per block, for 30 before and 30 after samples. The correct baseline APK already includes native KDF, scoped reads and completed localization. Local Room and KeePassDX were then alternated for ten samples each, reversing the order on alternate rounds.

| Save path | Samples | Navigation median (ms) | Navigation P95 (ms) | Target-close median (ms) | Navigation range (ms) |
| --- | --- | --- | --- | --- | --- |
| Monica KDBX before this batch | 30 | 894.0 | 1284 | 527.5 | 680–2099 |
| Monica KDBX after this batch | 30 | 710.5 | 1485 | 481.0 | 581–1661 |
| Monica disk-backed local Room | 10 | 197.0 | 384 | N/A | 102–384 |
| Official KeePassDX 4.5.6 libre | 10 | 841.5 | 984 | 428.0 | 682–984 |

Observed median change for this batch: 894.0 → 710.5 ms (20.5% lower). Median time from target-close callback to navigation: 359.0 → 232.5 ms. These medians are from their own samples; subtracting the median columns is not a stage measurement. P95 worsened from 1284 to 1485 ms; this series does not prove stable tail-latency improvement.

Late in this series, both variants slowed. Read-only diagnostics showed substantial guest paging and a retained KeePassDX process; this is a possible confounder, not proof that every slow sample has that cause. After the peer series, an idle snapshot showed that process using only 3.8% of one CPU, with about 195 MiB resident memory; stopping it increased available guest memory from about 635 to 753 MiB. A follow-up explicitly stops only the task-installed comparator before and after each case. The original series and all slow samples remain retained; follow-up results are recorded separately below.

Monica navigation is measured at its Compose callback; KeePassDX navigation is an accessibility activity event and includes event delivery. The shared provider close callback is a better common boundary, but is dispatched by a Handler and does not measure fsync or power-loss durability. The local Room result measures its real editor with encrypted records in a disposable on-disk database, not a KDBX rewrite. KeePassDX uses a captured copy of the Monica editor fixture; each Monica run regenerates the same 300-entry shape with fresh UUIDs/timestamps/random material, so all trials are not byte-identical vaults. The cost settings and workload size are matched. Debug Monica versus Release KeePassDX and sequential series limit cross-product conclusions.

The earlier 1.3-second samples, the fresh three-run baseline and the single preliminary new/DX runs are preserved above as history, not pooled into this comparison. The local-storage gap and complete-design target still need separate assessment; this change does not implement streaming XML or decouple cloud uploads.

## Follow-up with explicit comparator process cleanup

The same production/test APKs and fixture generator were used. KeePassDX was force-stopped before and after every case; its data/settings were retained. One ABBA cycle used five cases per block (ten before, ten after). A separate series interleaved the current Monica KDBX editor, disk-backed local editor and official KeePassDX editor for five cases each, rotating/reversing their order. These are separate cohorts and are not pooled with the original series.

| Series / variant | Samples | Navigation median (ms) | Navigation P95 (ms) | Target-close median (ms) | Navigation range (ms) |
| --- | --- | --- | --- | --- | --- |
| abba / before | 10 | 982.5 | 1734 | 581.5 | 853–1734 |
| abba / after | 10 | 1148.0 | 1950 | 786.5 | 659–1950 |
| peers / after | 5 | 812 | 3550 | 612 | 591–3550 |
| peers / local | 5 | 122 | 150 | N/A | 107–150 |
| peers / keepassdx | 5 | 872 | 2625 | 541 | 677–2625 |

In the follow-up ABBA cohort the median increased from 982.5 to 1148 ms (16.8% higher), reversing the initial series' direction. The separate five-run peer cohort had medians of 122 ms local, 812 ms current Monica KDBX and 872 ms KeePassDX, but retained slow samples of 3550 and 2625 ms for the two KDBX editors. These results do not support declaring the user's slow-save report fixed, or attributing it solely to the retained competitor process.

The follow-up checks whether the median difference persists with comparator process cleanup. It does not identify the cause of all slow saves or validate a tail-latency guarantee: ten samples have P95 equal to their maximum, and a five-sample peer comparison is exploratory. Original slow samples are retained. No environment reset, app-data clearing, KDF adjustment or production-code change was made for this follow-up.

Verified implementation: unchanged password parsing and repeated empty-OTP work are removed. Timing evidence must be read across both cohorts: the initial lower median does not establish a stable overall speedup when the follow-up or tail results differ. Unresolved: long saves and the gap to the local editor. Remaining architecture work includes measuring/optimizing serialization and deciding the separate cloud-persistence stages; this batch does not claim those stages are complete.

## Scope and artifacts

No native UI labels were added; the previously completed 13-language hardware/Plus resources remain intact. Bilingual unreleased notes and F-Droid fastlane 30 describe the incremental work without a promised speed ratio. Trace binaries, synthetic comparison vaults and screenshots remain local and are not committed.

No app data reset, new AVD, commit, push, tag, release or APK delivery. The previously running shared AVD remains available. Eight production/test files are byte-identical across flavors; the 15-method mutation/transfer audit and both Git whitespace checks passed. Only this task's device comparison fixture, trace, screenshots and manual fixture were removed. The task-installed KeePassDX package was uninstalled; the current Monica APK remains installed. All 115 measured saves passed their functional assertions, but this is not a performance pass. Raw runs, cohort summaries, APK hashes, shared-source hashes and cleanup actions accompany this report.

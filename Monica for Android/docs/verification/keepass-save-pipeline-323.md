# KDBX local save pipeline: scoped reads and coherent presentation

Date: 2026-10-30. Ordinary and canonical F-Droid 1.0.323, unreleased.

Subsequent work: [incremental password projection and controlled editor comparison](keepass-incremental-save-323.md). The implementation and measurements below describe this earlier batch; later reuse of unchanged entries supersedes its full-password-rebuild behavior.

Status: this local implementation is built and regression-tested. The complete design's latency targets and later cloud/streaming stages remain unverified or unimplemented.

## Implemented scope

This implements the first local-save portion of [the pipeline design](../architecture/keepass-save-pipeline-323.md). Monica retains its own editor. The save command queries original IDs and the replica group instead of subscribing twice to every active password row. It retains availability, archive/deletion, external Steam filtering, ordering and native-owner precedence, including suppression of stale Room projections. It uses the existing replica-group index, with no schema migration or new original-vault content index. More than 900 original IDs retain the old transactional full-read fallback to avoid bind limits and split snapshots.

The adapter obtains browser and workspace from one loaded database and reuses workspace password classification. An unchanged source-browser instance reuses the presentation only while its lock version and current adapter snapshot still match. Existing source/configuration checks and cache expiry still run. A new revision rebuilds that vault once, including REF dependents. This is not per-entry incremental projection. Bank-card and document projections retain full refresh because they restore encrypted photo files that can be evicted independently of the KDBX revision.

Cross-target selection, per-target writes, replica removal, KDBX movement, backup, file validation, conflict handling and failure ordering are unchanged. The scope audit checks 15 mutation/transfer methods against the pre-change sources. Five production and two test files match across flavors. No KDF, format, credential, recovery or cloud-upload algorithm changed in this batch.

## Builds and regression results

| Check | Ordinary | F-Droid |
| --- | --- | --- |
| Final main/test Debug build plus selected JVM tests | Pass, 989.3 s | Pass, 1063.8 s |
| Ownership, cross-database transfer and TOTP binding JVM tests | 13 passed | 13 passed |
| Complete API32 KDBX device suite | 80 passed, 213.107 s | 80 passed, 207.524 s |

Ordinary's first full build passed in 28m33s. Its final source build includes the photo-restoration guard. A subsequent test-only build (4m29s) updated fixture comparisons while preserving the main APK. BuildConfig reuse preserves the existing build identity; production sources are still compiled normally. Minified Release and physical-device performance were not tested in this batch. APK versions and hashes are recorded in `keepass-save-pipeline-323-build-evidence.json`.

The 80-case suite comprises the existing 72 KeePass cases and eight new cases: saving without global-list subscription; updating an existing local replica without touching unrelated rows; deleting unselected replicas only after success; failed native save retaining source and replica; candidate equivalence across stores and the large-ID fallback; unchanged snapshot reuse plus REF dependency refresh and lock rejection; cross-KDBX move preserving UUID, values/protection, complete persisted history and attachment bytes; and failed destination write retaining both original files. The original suite also covers Monica UI, custom icons, ownership, original-format compatibility, KDF variants, attachments, deletes and conflicts. This is fixture coverage, not a claim that every live remote provider was tested.

## Controlled observations and limits

The final eight-case ordinary run passed in 26.374 s. Its real ViewModel save took 424 ms and made zero global-list subscriptions. The same guard against the pre-change main APK failed for the intended reason: two subscriptions by the actual save coroutine (638 ms). These isolated samples prove the removed work, not a universal speed ratio. Both builds still commit once and retain the original UUID; no native-vault password content is indexed in the fixture's Room database.

The ordinary editor ran in ABBA order after heavy compilation ended, on the same public Android 32 x86_64 AVD, using the existing 300-entry synthetic SAF provider fixture and Argon2d v1.3, 32 MiB, 8 iterations, 2 lanes. Each run opens a fresh synthetic vault, primes the selected list, edits the real Monica form and measures handler to navigation. Installation, startup, JIT, caches and host scheduling can still vary. The fixture records output-stream boundaries separately; they are not pure KDF or I/O phases.

| Run | Handler to navigation |
| --- | ---: |
| before-1 | 1836 ms |
| after-1 | 1293 ms |
| after-2 | 1324 ms |
| before-2 | 1230 ms |

Old and new ranges overlap. Two samples per build do not establish a stable overall improvement, P95, the design's 40% reduction target, or a one-second guarantee. No side-by-side competitor, physical-device, large-vault or real cloud latency comparison was performed. The implementation removes unnecessary queries and projection work; it does not establish that all remaining spinner delay is fixed. The comparison script restores the new main APK in its finally block, and the full suite subsequently tests that APK.

## Fixture corrections and environment recovery

The first eight-case run passed six cases and failed two fixture assumptions: equality included randomized protected-value memory, and a refresh raced the asynchronous UI lock notification. The test now compares values, protection and metadata and waits for the UI's lock notification before checking rejected reads. A second run passed seven cases and exposed KDBX4 second-precision timestamps versus the millisecond timestamps still held in memory. The move test now reloads the persisted source before comparing history. It does not round away or discard history assertions. Production transfer and locking code was not changed for these corrections.

The earlier five-case pre-change run had one expected guard failure, but its four total subscriptions also included startup maintenance. The final guard attributes only the save coroutine, and the fresh negative control confirms two. Do not attribute all four earlier calls to the save command. Earlier editor samples of 7540 ms and 10069 ms are diagnostic observations, excluded from the ABBA comparison; the latter overlapped compilation.

Initial installation failed with package-service Broken pipe (32), and a later main-APK install hung with a package dump timeout. The crash buffer contains a system-process failure setting an Android system property; its underlying trigger is not established. The existing public AVD was restarted with its original data disk, after which installation and tests succeeded. No app data or AVD configuration was cleared, and no new AVD was created.

## Remaining stages and artifacts

Cloud-first-return behavior, durable pending-sync recovery, per-entry affected-set updates and streaming XML encoding remain later stages. Current saves still wait for the existing commit, verification and remote behavior. The Canvas cloud-status screens remain design prototypes; this batch does not change native UI appearance or claim those screens are implemented.

Raw complete-suite logs, the corrected eight-case run, negative control, ABBA logs, build/JVM evidence and scope audit accompany this report. Temporary fixtures clean up their own registrations and files. The public AVD remains running. No commit, push, tag, release or APK delivery was performed.

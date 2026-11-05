# Release R8 native-memory failure (1.0.325)

## Cause and scope

On 2026-11-05 the ordinary edition's Android Studio release build appeared to stop at `:app:minifyReleaseWithR8`. Two matching JVM crash reports establish that the Gradle JVM exited because Windows could no longer satisfy a native memory allocation:

| JVM | Failure time (Asia/Shanghai) | Failed allocation | Available system commit in crash report |
| --- | --- | --- | --- |
| 183920 | 21:59:25 | 1,069,776 bytes | 5 MB of 81,651 MB |
| 108996 | 22:05:21 | 131,088 bytes | 26 MB of 81,651 MB |

The second crash was in a C2 compiler thread compiling an R8 method, with about 4.34 GiB of Java heap used under a 6 GiB maximum and 380 Java threads recorded. It was a native allocation failure under system commit exhaustion, not proof of a deadlock or a Java heap-space exception. Android Studio reported the matching build failure at 22:07:22 after 4m33s; the process had already exited.

The generic Kotlin metadata warning is a separate confirmed compatibility issue. The detailed R8 log identifies `com.google.android.gms:play-services-identity-credentials:16.0.0-alpha12`, specifically `CredentialInformation`, `GetCredentialRequest`, and `ImportCredentialsRequest`: their metadata version is 2.3.0 while R8 8.7.18's reader supports through 2.1.0. This is not attributable solely to Monica's Kotlin plugin 2.0.21 versus pinned stdlib 2.1.20. The warning remains after this resource fix; no dependency or shrinker version was changed or warning suppressed.

## Change

Both ordinary and canonical F-Droid Gradle configurations retain the 6 GiB Gradle heap and existing Kotlin compiler heap. They limit Gradle to two workers and the Gradle JVM to four active processors, bounding R8/JIT/GC concurrency, and default project parallelism to false. Explicit command-line options can still override project defaults; Android Studio's observed `--parallel` option overrides only the parallel-project default, not the saved worker/processor limits.

The task-owned MuMu clone 1 was stopped before validation to release its approximately 6.5 GiB private allocation. Its original users and OMK scope were unchanged. The original emulator, unrelated applications, and the existing Kotlin compiler process were preserved. No system page-file setting was changed. These combined changes reduce memory pressure; the run does not isolate the contribution of each change or guarantee success if other applications exhaust system commit again.

## Verification

- Ordinary `:app:assembleRelease` succeeded in **7m12s**, 72 tasks (12 executed, 60 up-to-date), with R8 minification and resource shrinking enabled. This was a real R8 rerun following the failed outputs, not a cached minification pass.
- The run used the saved resource limits as explicit command-line overrides, `--no-parallel`, and a single-use daemon. Separate subsequent `help --no-daemon` checks without resource overrides confirmed **processors=4, workers=2, parallel=false** from both saved project configurations (ordinary 18s, F-Droid 15s).
- Produced ordinary ARM64 and ARMv7 APKs for `1.0.325-26110512-30` / code 12. Mapping header confirms R8 8.7.18. No signing credentials were supplied: this verifies unsigned release packaging, not a rerun of Android Studio's signing wizard.
- Twenty-four memory samples over a 232-second portion of the successful run recorded at most 96 process threads, at most 6.79 GiB process private allocation, and at least 12.63 GiB available system commit. These are sampled observations, not whole-run exact peaks.
- F-Droid received the same configuration change and a successful effective-settings check, but **no F-Droid release build or device test** was run for this change. No new runtime behavior or cryptographic behavior was changed.

The private host evidence is in `.codex-tasks/20261105-release-r8-memory/`: the two original JVM reports, complete `--info` build log, memory samples, effective-settings receipts, and `result.json` with artifact hashes. Raw JVM reports contain local environment details and are not copied into the repository. Existing work and released versions are preserved. No commit, push, tag, release, or APK delivery was performed. The Keyguard comparison remains deferred, without a runtime result.

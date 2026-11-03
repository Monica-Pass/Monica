# Android 1.0.324 release input verification

Date: 2026-11-03. Publication is authorized for the regular and canonical F-Droid editions.

The release source includes the previously validated permission-management baseline and LAN IP autofill changes. Before staging, all 52 shared permission files and 14 shared LAN files matched their frozen SHA256 receipts in both editions. Main and AndroidTest manifest diffs were reviewed; the synthetic photo provider remains restricted by MANAGE_DOCUMENTS. Existing build/device records and their failures describe their original runs. No new build or emulator/device test was performed for this publication.

The regular edition uses exactly the two existing user-supplied Release APKs, without rebuilding, repackaging or resigning. Both actual manifests report takagi.ru.monica, versionName 1.0.324-26110312-02, versionCode 12, minSdk 26, targetSdk 34, and are not debuggable. apksigner verifies both signatures; certificate SHA256 is ee18951f09e806c104dd85548d777176ae2bec0b202a0d44bc49b9fe3e0b33c1. Manifest removals for phone state, broad photo/storage reads and top-level autofill binding declarations were verified. Filename ABI libraries have the correct ELF machine type; each supplied APK also contains x86_64 libraries. No embedded base-commit string was found, so that heuristic is not treated as provenance proof or as a reproducible-build claim.

| Supplied file | Bytes | SHA256 |
| --- | ---: | --- |
| Monica-Android-arm64-v8a-1.0.324-26110312-02.APK | 48184888 | bf3576eea1651df5b383894ec221ccbc6f3ccbadf0410c439b28cf7249c968e0 |
| Monica-Android-armeabi-v7a-1.0.324-26110312-02.APK | 45693102 | 11ec7395c1002a64e03e2944d6826c2540916d1c5d8e16f34bfe6ebf41fd1f9e |

F-Droid is a source release at versionName 1.0.324 / versionCode 31. Official F-Droid building, signing and distribution are separate; ordinary APKs are not attached to that release. Metadata/tag checks and the six release-script tests are rerun before publishing.

Published 323/323b archives and tags are preserved. Final bilingual notes are archived for 324. Design files, screenshots, APKs, scratch scripts and crash logs are excluded from source commits. The ordinary tag-triggered build workflow is temporarily disabled during publication and its original active state is restored afterward, preventing it from generating substitute assets.

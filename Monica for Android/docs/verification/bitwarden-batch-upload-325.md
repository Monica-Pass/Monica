# Bitwarden batch upload investigation — 1.0.325 preparation

2026-11-03. This is a bounded investigation of existing 1.0.324 behavior; no production sync patch, new release, push or APK delivery was performed.

## Actual official-account workflow

The human supplied a test account and explicitly authorized testing. The official US web vault required email verification; the human completed the browser login. A subsequent one-time code completed the Monica device login. No master-password change, security-setting change or real-item modification was performed. Credentials were transported to the owned device through a transient local socket with hidden terminal input, never placed in source or shell command lines.

The human requested batch data creation. The official web JSON importer created exactly 100 synthetic items in `Monica325-Batch-20261103-A`: 40 logins, 20 notes, 20 cards, 20 identities and one folder. Existing three items were retained. Login fixtures initially had three synthetic URLs, Unicode notes and two custom fields. Test payment and identity values are fictitious.

The public `Monica_Issue136_API_32` AVD was reused in owned guest 13. The installed ordinary x86_64 Debug APK was `1.0.324-26110312-01`, SHA256 `85314c32c7ad936f13f87a72ac0415bd6df812d6f4ca7c5d89e719d3a7a978e0`; the reporter used `1.0.324-26110312-02` on a different device. A task-local instrumentation adapter invoked the installed production `BitwardenRepository` login and sync methods, the actual local DAO edit, then production repository sync. It did not replace HTTP responses or use a separate uploader.

Observed operation events: initial sync success, 103 remote items added; 40 synthetic login rows identified; their username, encrypted local password and notes changed to revision B. Upload returned 40 uploaded, zero upload failures and zero conflicts, with 40 subsequent remote updates applied.

The official webpage was reloaded, unlocked and read independently. All 40 new usernames were verified across the virtualized list, including the page boundary at row 31. Row 31's revealed synthetic password and notes matched revision B. No claim is made that every password, card or identity field was individually read back. Notes/cards/identities were created and included in the pull, but were not edited in this run.

The adapter's final waiting-for-web-readback stage ended with IOException, and its bridge later reported ConnectionAbortedError. These are retained as harness/teardown failures; the complete instrumentation run is not counted as passing. The earlier explicit production operation events and independent website values establish the upload result.

## Reporter 422 and compatibility comparison

The private reporter log contains Rocket HTML 422 responses for password-update requests. Structural inspection found nine captured exchange records and failed request bodies lacking `encryptedFor`; raw log bodies, user entries, ciphertext and identifiers were not copied into this document. Counts of repeated log summaries are not treated as independent HTTP attempts.

Vaultwarden's released 1.37.5 `CipherData` schema requires `encryptedFor`, and the local official Bitwarden reference client's request type supplies it. Source: https://github.com/dani-garcia/vaultwarden/blob/1.37.5/src/api/core/ciphers.rs . A task-local Rust serde comparison extracted that released schema, substituted string aliases for ID types and relaxed only the optional folder deserializer. The synthetic request failed with `missing field encryptedFor`; the otherwise identical request passed after that field was added. This verifies the schema incompatibility, not a complete Vaultwarden HTTP/auth/database session. Docker startup was attempted but no full server test could be run.

The evidence therefore shows working official-US modification upload and a concrete newer-Vaultwarden protocol compatibility issue. The reporter's exact server/version/proxy remains unknown, so the original failure is not claimed reproduced end to end, fixed or caused by incorrect use. Temporary domain unavailability does not explain a later server-generated 422.

## Additional finding and limits

Row 31's webpage detail did not display the original URLs after modification. This is not counted as a multi-URL round-trip pass. The production URI request model does not carry `uriChecksum`; the reference web client validates that checksum. Missing checksum/display versus deletion of remote URI bytes was not independently distinguished here. This warrants a separate targeted compatibility regression before changing production code.

Seven shared Bitwarden production files are byte-identical between ordinary and canonical F-Droid. The real-account run was ordinary only; F-Droid equivalence is static and is not represented as another executed account test. No physical HyperOS device, original self-hosted server, R8 package or new application build was tested.

## Cleanup

Only the owned guest 13 and transient forward were removed; original user 0 and users 10/11/12 were retained. The task-started public AVD received its stop command with registration, config and data disks retained. The other API36 AVD was not stopped. The requested remote batch of 100 items and user-completed browser session were intentionally retained for inspection. Screenshots remain only in the ignored local design directory and are not referenced as repository images.

## 中文结论

已按用户要求批量创建100条合成数据；普通324版实际拉取后修改40条登录记录，上传40条、失败0、冲突0。网页重新读取后确认全部40个新用户名，并抽查第31条密码和备注正确。官方服务的修改上传可用。

原日志的Rocket 422请求缺少新版Vaultwarden必填的encryptedFor；正式1.37.5解析器对照已验证缺字段失败、补字段通过。尚未取得故障用户的具体服务端版本与完整复现，不能断言是使用问题或原故障已修好。网页多URL显示异常另行记录，未计通过；本轮没有修改生产同步代码。

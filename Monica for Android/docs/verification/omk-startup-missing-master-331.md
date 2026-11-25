# OMK 启动 MISSING_MASTER_KEY 复现与恢复验证

日期：2026-11-21。性质：故障复现和既有恢复能力验证，本轮未修改生产代码、版本或加密策略，未重新构建或推送。

## 结论

在真实 OMK 路由下复现了“安全存储暂不可用，只有重试和复制诊断信息”的页面。已验证两条有条件的恢复路径：恢复原密钥路由及配套原材料；或在独立恢复材料完整时，用原主密码完成应用内确认恢复。恢复后原记录、新写入及新进程冷启动通过。

用户诊断为 `1.0.329-26112012-93 / e834a78751 / API 37 / monica_secure_prefs / MISSING_MASTER_KEY / PREFLIGHT / Attempts: 1 / Causes: none`。这表示当前 AndroidKeyStore 路由查询不到原 master alias，不足以证明密钥在所有后端均已永久丢失。该次阻断不是超时；`Causes: none` 是主动预检失败没有底层异常链，并非已查明没有外部原因。

本轮用“暂移合成库恢复文件”的方法复现无恢复按钮状态，**没有证明 OMK 会自动删除恢复文件，也未确认用户设备的恢复材料具体为何不可用**。仅覆盖升级到 331 不会补回缺失的密码封装材料。

## 代码依据

- [SecurePreferencesStore.kt](../../app/src/main/java/takagi/ru/monica/security/SecurePreferencesStore.kt)：已有加密配置的 keyset 完整而 `containsAlias` 返回 false 时，阻断为 `MISSING_MASTER_KEY / PREFLIGHT`。同文件的 `SecureStorageStartup.checkWithRetry` 通过 `LocalVaultRecovery.available()` 决定恢复入口是否可用。
- [LocalVaultRecovery.kt](../../app/src/main/java/takagi/ru/monica/security/LocalVaultRecovery.kt)：读取 `files/local-vault-recovery/recovery.json`，支持 AtomicFile 备份；缺失、不可读、结构无效、撤销或不支持版本等情况不能提供恢复入口。按钮出现仍需主密码验证，不能视为已成功解密。
- [PreparedSecureStore.kt](../../app/src/main/java/takagi/ru/monica/security/PreparedSecureStore.kt)：现代配置中的恢复 generation/revision 必须与恢复材料一致。仅找回原 alias，但配套恢复文件仍缺失时，可能继续被 `SECURE_STORAGE_CHANGED` 阻断。不能通过删除这些标记绕过保护。

已用 `git show` 对照用户精确提交 `e834a78751`：上述 master alias 预检及恢复按钮判定存在于该提交。运行时对照使用本地保存的 329-95 Release，并非用户的精确 329-93 安装包。

## 环境与安装包

复用已有 MuMu 实例 1，Android API 35、x86_64、第三方 OhMyKeymint `1.3.5 (1-ba6f300-release)`。原实例 0 已运行但未开放 root，不适合本轮真实模块测试；实例 1 由本轮启动，未创建或克隆实例，未使用公共 AVD。原用户 0/10/11/12 保留，测试仅使用新建的 full.SECONDARY 用户 13/14/15。

普通版和 canonical F-Droid 的 331 Debug 使用独立包名。普通版 Release 使用同一独立包名、同一本地测试签名，在 329 创建合成库后以 `install -r` 覆盖到实际 R8、non-debuggable 的 331，没有卸载或清数据。以下安装包均复用已有产物，本轮构建数为 0。

| 包 | SHA-256 |
| --- | --- |
| 普通 331 Debug | `0b81d5c828ed7311cf6d193e2d1093a1bfe0f028975cb5d40ac9c1418a8e4c74` |
| 普通 331 instrumentation | `e1aafba945781212feeb4e1665fa2fc82032244f95eefe36a227aaf93db040d9` |
| F-Droid 331 Debug | `8109f6e0b8129db3bada7a7cff03213a6dcbb77956ecd8b6176209ff8a05765f` |
| F-Droid 331 instrumentation | `482d10c8140ca92a3a2dc1e9f69a3f142f685c48f85473e7c6e0ef2b28e8ed1f` |
| 普通 329-26112012-95 Release | `7777430cb2d0b020204dae06d9b982157885b88c0608112b016f09b2e2ad3991` |
| 普通 331-26112112-71 Release | `a986c023d18719d6bb73b9dbc930a1f8cdfa102ead0ad6252fd4951140d8d22c` |

## 实际结果

| 场景 | 普通版 | F-Droid |
| --- | --- | --- |
| System 下创建合成库，完整恢复材料，改为真实 OMK 路由 | MISSING_MASTER_KEY；有主密码恢复按钮 | 同左 |
| 原主密码、实际恢复按钮及二次确认 | 旧记录、新写入、新进程冷读写通过 | 同左 |
| 同样切路由，同时暂移合成恢复文件 | 复现无恢复按钮页面；重试不解决；原加密配置哈希未变 | 同左 |
| 仅撤销新增 OMK 作用范围，恢复文件仍暂移 | 原 System 密钥已可读，但现代配置不一致，SECURE_STORAGE_CHANGED | 同左 |
| 原路由加原字节恢复文件 | 正常登录，旧记录、新写入及冷读写通过；未走确认恢复 | 同左 |

两版各完成 6 个有效 instrumentation 阶段，共 12 次 `OK (1 test)`：两组合成库的 seed、恢复/重新打开及冷启动。使用既有 `MuMuOmkModuleFixtureTest`，主认证和确认恢复走真实 UI，Debug 记录读写通过 DAO 核对。不能称为 12 个不同测试方法。

普通版 Release 另完成真实 UI 对照：

1. 329 下创建原记录并导出加密 ZIP。完整恢复文件时，切 OMK 后有恢复按钮。
2. 暂移合成恢复文件后，329 与覆盖升级后的 331 均只显示重试和复制诊断；升级未改变旧配置及暂存恢复文件哈希。
3. 放回原文件并恢复 System 路由，原主密码解锁、旧记录密码显示及不同 PID 冷读通过。
4. 再切 OMK；错误主密码恢复被拒绝；正确主密码及二次确认恢复成功。
5. 恢复后原密码解锁、旧记录密码显示、新建记录、再次冷启动同时读取新旧记录通过。最后一组 PID 为 41975 → 49266。

模块 injector 日志按本轮精确应用 UID 记录了 System 和 Omk 的真实调用路由，未仅凭作用范围配置判断注入生效。Release 的导出 ZIP 已归档；**本轮没有执行 ZIP 导入，不能把导出成功描述为备份恢复实测**。

## 保留的失败与限制

- 最初两次冷启动方法名错误，runner 返回 `OK (0 tests)`；明确排除，不计通过。改为实际 `verifyAfterColdRestart` 后两版各 1 项通过，原日志和失败标记保留。
- 曾预期仅撤销 OMK 范围即可登录，但缺失恢复文件时仍失败；保留该负向结果，没有删标记或放宽一致性检查。补回同一合成库的原字节恢复文件后才通过。
- 清理归档最初访问 secondary 用户的 `/storage/emulated/15` 被拒绝，尚未删除测试用户；改为已核实的 `/data/media/15` 路径，归档验证完成后再清理。
- 仅合成库、API 35/x86_64、上述第三方 OMK。本轮没有验证用户 API 37/ARM64 真机、官方 OMK、物理 TEE、所有模块版本或所有凭据类型，也没有证明更换 keybox 本身就是本次用户故障的充分原因。
- 本轮未修改启动或恢复逻辑；未将前一轮指纹重新绑定修复宣称为该启动阻断的解决方案。没有原密钥、有效独立恢复材料或独立备份时，不能保证仅凭主密码重建随机数据密钥。

## 用户处理步骤

1. 保留当前 Monica 应用数据、OMK 配置和原密钥材料；先做完整备份，不清应用数据、不卸载 Monica、不重装 OMK 或改根种子来试错。应用私有文件和模块密钥不要发到公开反馈中。
2. 若能确认“原先 Monica 不在 OMK 作用范围，故障前才加入”，先保存现有作用范围，在 OMK 中仅取消 Monica 对应用户的作用范围，然后强行停止 Monica 并重开。原密钥和配套材料仍在时，这条路径已实测可恢复。若原库本来就在 OMK 中创建，取消作用范围不一定正确，应恢复创建该库时的原密钥环境。
3. 若页面出现“使用主密码恢复”，输入原主密码，阅读并确认恢复。该路径已验证错误密码拒绝、原数据保留及恢复后冷读写。
4. 若仍只有“重试／复制诊断信息”，需要进一步检查同一安装、同一 Android 用户的 `files/local-vault-recovery/recovery.json` 及其 AtomicFile 备份是否存在、可读和匹配。仅文件存在不代表有效。不要创建空文件、借用其他库的恢复文件或删一致性标记。本轮尚未获得用户设备的该项证据。
5. 若保留了 Monica 导出的独立加密备份，先在另一安装中验证恢复，原故障安装继续保留。若原密钥及独立恢复材料都已永久丢失，升级或反复重试无法凭空恢复它们。

## 证据与清理

公开摘要见同目录 `omk-startup-missing-master-331-results.json`。私有原始记录保存在工作区 `.codex-tasks/20261121-omk-startup-recovery/`，包括各阶段 runner 输出、UI 节点、诊断日志、路由、文件哈希及清理回执；不随本报告公开原库、密钥或私有归档。

清理前已将本轮合成应用数据和导出 ZIP 归档并核验 tar 可读：9,260,544 字节，275 项。OMK 作用范围精确恢复原四项，`injector.toml` 与 `config.toml` SHA-256 均与开始一致。切回用户 0，仅删除本轮 13/14/15，包注册与初始集合一致；adbd 恢复 uid 2000，仅停止本轮启动的实例 1 和 ADB 15064，原实例 0 保持运行。所有原用户、模块、配置和数据盘保留。

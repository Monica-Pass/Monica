# MuMu 真实 OMK 切换与保留数据恢复验证 — 1.0.325

## 验证结果

2026-11-05，普通版和 canonical F-Droid 的未发布 1.0.325，在独立 MuMu 克隆机中实际安装并运行 OMK，完成原主密码恢复、旧记录读取、新记录保存和不同进程冷启动。验证使用合成数据；这次证据包含真正的模块接管，不是人为替换 AndroidKeyStore alias。

| 场景 | 普通版 | F-Droid | 验证含义 |
| --- | --- | --- | --- |
| 系统密钥后端切换到 OMK，user12 | 通过 | 通过 | 原密码与二次确认恢复，原记录密文不变，新增记录可读写；随后另起进程正常登录，无须再次恢复 |
| 原 OMK 库保留，KernelSU 卸载并重装 OMK，user11 | 通过 | 通过 | 新根种子、原应用 alias 缺失的后端下，用原密码恢复；旧记录、新记录、再次冷启动均通过 |
| 仅修改 OMK root_kek_seed，保留原 OMK 内部数据库 | 失败 | 保留数据检查通过，未执行该场景的完整恢复用例 | OMK 自身 boot-level key cache 解密失败，服务无法启动；普通版确认恢复未完成，两版原配置和恢复材料哈希保持不变 |

后两项是独立记录的处理结果：后端卸载重装后的成功，不能把单独改根种子的失败改记为通过。user12 的冷启动在根种子修改之前完成；模块重装后的完整验证使用 user11，没有声称 user12 也在重装后重新通过。

## 模块来源与实际接管证据

环境为 MuMu 专用克隆实例1 `Monica OMK 325 Test`，Android 15、API35、原生 x86_64、KernelSU kernel/ksud 32525。原实例0保留。模块来自第三方分支 `ITxiao6666/OhMyKeymint` 的 `ba6f300`，本地按源码构建完整 x86_64 模块。下载的 242、247 发布包只有 ARM64，实际 KernelSU 安装247返回 `Unsupported platform: x64`，没有把该失败算作模块启用。

本地 BoringSSL 绑定生成参数修正了空 include 项吞掉 Android target 参数的问题，并明确 NDK clang resource-dir；第一次修正仍失败，第三次绑定生成才核实成功。OMK 仓库的生成 protobuf 文件仅有排版和末尾换行变化。没有为通过测试改变 OMK 运行算法。模块四个 ELF 均为 machine62；完整 ZIP 的 SHA-256 为 `ffa31a01ba2feff28f9a1d0adadcc22b4d8191525bed4743b630f99eec753ec2`。

模块通过 KernelSU 安装、重启加载；模块状态 enabled=true，Keystore 进程映射注入库，后端检查返回 normal，日志记录测试应用 UID 的 `GetKeyEntry route=Omk`。最终切换前，user12 的两个应用 alias 存在于系统 `persistent.sqlite`，不存在于 OMK `keymaster.db`；通过 OMK scoop API 加入两个 package@12 后执行恢复。原 OMK 库在 user11 中建立。卸载重装前后核对新根种子及空的 user11 应用密钥元数据，恢复后 OMK 数据库产生各自 recovered alias。

最终模块配置只保留 users10/11 的测试范围，因为重装使用原测试配置；证据文件中的该最终配置不是 user12 首次切换时的配置。配置、密钥材料和库的私有备份只保存在本地任务目录，未复制到仓库。

## 本次发现的 Monica 问题与修正

实际 OMK 初始化期间，一个健康启动检查完成后，队列中的下一个检查偶发收到 `SECURE_OPERATION_IN_PROGRESS`。旧实现先释放 retryMutex，再发布结果及回收 running 槽；worker 也先完成 Deferred，再标记 finished。两个交接窗口会把完成中的任务误判为仍在执行。

两版 `SecurePreferencesStore.kt` 将 mutex 保持到结果发布和槽位回收完成，并在完成 Deferred 前标记 worker 已结束。既有超时、取消、锁定和迟到结果保护保留；未改变算法、KDF、存储格式或认证要求。新增 `SecureStartupHandoffInstrumentedTest` 使用两个真实调用者和受控调度顺序，旧普通版 APK 明确失败：`Queued caller should be READY; blocked reason=SECURE_OPERATION_IN_PROGRESS`；最终两版均通过。

`MuMuOmkModuleFixtureTest` 必须显式指定非0合成用户。夹具不删除或替换 alias、不改变模块。原主密码输入、恢复按钮、二次确认和再次登录通过真实 MainActivity 完成，并等待 Activity 窗口获得焦点。记录写入和读取通过实际数据库 DAO 与 SecurityManager 完成，未声称逐条操作密码编辑页面。冷启动要求 PID 与恢复时不同，开始时没有解锁缓存，且必须正常登录，不能再次恢复。原配置和恢复材料在正常认证前逐字节核对，认证后保留旧配置和原快照；原记录密文与密码包装材料保持不变。

## 构建与测试统计

| 项目 | 普通版 | F-Droid |
| --- | --- | --- |
| 最终 Debug 主程序与测试包构建 | 2分44秒 | 2分29秒 |
| 最终阶段逐项执行次数 | 10次通过 | 10次通过 |
| 去重设备方法 | 8项 | 7项 |
| runner 累计耗时 | 60.320秒 | 58.330秒 |
| 宿主驱动累计耗时 | 149.675秒 | 139.835秒 |

每版10次包含4次启动交接、超时、取消及维护保护检查，2次基线建立或完成，4次两场景恢复和冷启动。普通版额外包含既有合成库的 `resumeInterruptedSeed`，因此去重方法数比 F-Droid 多1。20次是分项累计，不能称为一批20项整套运行。旧实现失败、ADB中断、日志边界缺失、根种子修改失败以及早期成功均在30份运行记录中保留。

最终源码的3个共享文件逐字节一致，SHA-256 与构建验证时冻结值一致；两版最终主 APK 仍与运行记录哈希一致。接管时重新核对30次运行中777个文件的记录哈希，20次最终回执均确实执行1项并通过，日志包含起止标记及结束后5秒观察，未出现新的 fatal/ANR 标记。F-Droid 元数据与6项发行检查脚本测试再次通过。接管阶段没有重复构建或设备测试。

普通主 APK SHA-256：`bb63c33960500c224c4d0ebdb08730110bf7babfe288d4d317e27f4cf32ad152`。F-Droid 主 APK SHA-256：`c210d3c5957cb7f966c0efa806ac2dc1d2fa2870174240d52bf473149d323d2f`。版本仍为1.0.325，普通 code12、F-Droid code32。

## 失败与验证范围

下载包架构不符、原生绑定初次修正无效、普通构建脚本本地 `buildNatiev` 拼写错误、日志 BEGIN 标记订阅竞争、公共5037 ADB服务中断、MuMu重启将测试用户转换为工作资料均保留原始记录。构建拼写恢复为原有 `buildNative`；驱动为工作资料核验 parent0 和 RUNNING_UNLOCKED，后来改用本任务独立 ADB15038。中断的普通基线只继续核对已有单条合成记录，没有清数据重建。

单改根种子时，OMK 报 boot-level key cache 初始化失败、InvalidKeyBlob/-33，RPC连接拒绝。普通版恢复测试因25秒内未回到正常登录而失败；两版原配置和 envelope 保留。没有把 OMK 后端不可用描述成 Monica 已恢复正常。

证据仅覆盖上述模拟器、第三方源码自编译模块及合成 MDK 密码记录。没有验证官方 ARM64 发布包、真实 TEE、真实 keybox 替换、Android16真机、OEM ROM、R8、所有凭据类型或全部应用功能。永久丢失且没有独立恢复材料的硬件密钥内容仍不能保证恢复。安全窗口截图为黑屏，未计作视觉验收；本轮仅有实际窗口焦点与 Compose 交互证据。

## 环境与记录

专用克隆实例1已经停止，保留测试盘、合成用户与模块状态便于复核；原 MuMu 实例0运行，shell uid2000，仅原 Owner用户0，无 Monica 包。本任务 ADB15038服务已停止，全局5037服务保留。公共 AVD 未启动、未修改。测试的私有备份、原始日志、黑屏截图与模块构建产物仅留本地；本轮没有提交、推送、标签、发布或额外交付 APK。

本目录配套文件：`omk-mumu-325-results.json` 保留全部运行汇总，`omk-mumu-325-evidence.json` 记录模块与核验哈希，`omk-mumu-325-runners.txt` 保留包括失败在内的回执，`omk-mumu-325-excerpts.txt` 保存模块和构建的选定原文，`omk-mumu-325-cleanup.json` 保存收尾状态。文本副本只规范 CR 和尾空白；原始文件哈希与原始字节留在 `.codex-tasks/20261105-omk-module-mumu/`。

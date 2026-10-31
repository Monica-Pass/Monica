# 启动与闪退 Logcat 诊断（1.0.323，未发布）

日期：2026-10-30。

## 抓取方式

1. 更新到包含本改动的 Monica 构建，在打开 Monica 之前启动 LogFox 录制。
2. 选择 Monica 进程/应用，日志等级包含 Info、Warning 和 Error。普通版包名为 takagi.ru.monica，F-Droid 为 takagi.ru.monica.fdroid。
3. 使用 MonicaDiag 标签或关键词查找新诊断。闪退时同时保留 Monica 对应的 AndroidRuntime 崩溃报告；卡顿时保留 MonicaPerfWatchdog。能够抓取系统日志时，可另保留相关时段的 keystore2/Keystore/KeyMint 信息。
4. 复现一次；若一直等待，至少录制到超过 15 秒启动上限，再导出从启动前到故障后的相关时段。注明完整 Monica 版本、Android 版本、操作步骤及是否更改过 Keystore/keybox 模块。
5. 不需要清理 Monica 数据、卸载应用、重置密钥或提供密码、PIN、验证码、数据库文件。启动恢复页的“复制诊断”仍可使用，现在带完整构建版本、Git 提交和 Logcat 标签。

Monica 不依赖 LogFox，不增加 READ_LOGS、root 或网络上报权限。新增日志进入系统 Logcat，已有本地安全日志就绪后也进入其有界队列；应用外的日志查看工具仍需其正常日志访问授权。

## 可定位的信息

| 事件 | 内容 |
| --- | --- |
| app_start / application_ready / main_activity_created | 区分应用入口、应用初始化完成和主页面入口；包含完整构建版本、code、Git 提交、构建时间、Android API |
| secure_begin / secure_stage | 会话与单次请求号、阶段及单调时钟耗时；包括等待工作槽、活动存储、预检查、主密钥、密钥集、读取、恢复检查及重试等待 |
| secure_failure / secure_failure_detail | 尝试次数、固定存储类别、白名单原因码、异常类型链和类/方法/行号；Android 13+ 带系统公开 Keystore 数字错误码和 transient 标志 |
| secure_result / secure_result_code / secure_worker_exit | 就绪、阻止、超时或取消，以及原 worker 何时实际退出；超时后不把迟到结果当成功 |
| data_read_failure / data_read_detail | MDK、V2、C2 数据解密失败；只标记格式，每种格式最多每 5 秒记录一次 |
| fatal / fatal_detail | 未捕获异常的线程编号、是否主线程、类型与代码位置；随后原样调用既有 Android 崩溃处理器 |
| previous_exit | Android 11+ 查询系统保留的本应用最近最多 3 次历史退出：Java/native 崩溃、ANR、低内存、主动退出等类别，以及 PID、时间、状态和内存统计 |

新增行不读取 Throwable 的 message/toString，不输出账号、密码、密钥、PIN、密文、数据库内容、偏好键、文件名/路径、设备稳定标识或系统退出 description/trace。未知存储与原因只显示 OTHER/UNKNOWN；异常链最多 8 层，每层最多 8 个代码位置和 2 个 suppressed 类型，循环链会截断。普通启动阶段使用 Info；诊断调用使用 Log.println，避开正式版裁掉 Log.d/v/i 的规则，不启用全应用调试日志。

示意：secure_result 的 stage=KEYSET_OPEN、outcome=TIMEOUT 表示调用未在等待上限内返回；secure_failure_detail 的 AEADBadTagException 与公开 Keystore code=10 表示认证校验失败。它们仍不能单独判断旧密钥是否丢失、密文是否损坏或哪个模块有问题。READY 仅指安全存储可读，不表示用户已完成认证或密码库已解锁。previous_exit 是带时间戳的历史记录，不能把旧崩溃误认为本次复现。

这次不改加密格式、KDF、读写或恢复策略，不自动重建/清理数据。数据读取失败仍抛出原异常；新增诊断失败不能吞掉原崩溃。历史退出查询在独立于安全存储门禁的后台协程运行，不读取原始退出堆栈，不在主线程查询 Binder。当前进程的 native 崩溃、系统杀进程或日志被系统丢弃不保证能由应用当场记录，系统也可能未保存历史退出信息。

## 构建和回归

普通版最终 `assembleDebug`、`assembleDebugAndroidTest` 和 `assembleRelease` 成功（21m12s）。首轮测试夹具直接构造了系统隐藏的 KeyStoreException 构造器，导致测试包编译失败；改为 AndroidKeyStore 生成合成 AES-GCM 密钥、损坏认证标签来验证真实异常。最终 Debug 构建复用已生成的构建时间字段，Release 单独生成完整版本 `1.0.323-26103012-18`。现存 Kotlin/R8 metadata 警告未阻止构建，没有因此更换编译工具。

| 平台与范围 | 结果 |
| --- | --- |
| 公共 API32 AVD：新增诊断 4、启动边界 20、旧密钥读取 4、恢复 UI 2、真实启动 Activity 1、本地数据恢复 14、连接恢复 4 | OK (49 tests)，286.486s |
| 已有 API35 AVD：真实 AndroidKeyStore AES-GCM 认证失败，异常链公开 code=10，输出不含测试密钥别名 | OK (1 test)，0.094s |
| 合计 | 50 项设备回归通过 |

新增用例验证：异常 message getter 不被调用、私密消息与文件名哨兵不出现、深链/循环链有界、诊断自身抛错仍恰好一次转交原 thread/Throwable、真实 Logcat 含请求号/阶段/耗时以及 OTHER/UNKNOWN。既有 C2/V2 缺失密钥测试同时检查 Logcat 中有异常类型且无原文/密文；所有原启动、锁定、密码轮换、中断迁移和连接恢复用例通过。API35 用例只创建并清理随机前缀 diag-code-fixture 的合成 alias，不改默认应用密钥或用户数据。

普通版实际 minified Release APK 已用 APK Analyzer 核对：RuntimeDiagnostics 混淆为 te.v，MonicaDiag 字符串、结构化事件和 Log.println 调用保留，SecureStorageUnavailableException 名字保留。精简反编译证据在 startup-logcat-323-r8.txt。五个共享安全生产/测试文件逐字节一致，MainActivity/MonicaApplication 分别修改以保留版本差异，未引入额外权限或依赖。

最终设备输出保存为 startup-logcat-323-api32.txt 和 startup-logcat-323-api35.txt；安全过滤后的真实 Logcat 保存为 startup-logcat-323-logcat.txt。完整构建与原始测试证据留在外层工作区 .codex-tmp/runtime-diag-*。本轮使用合成数据，没有安装/切换第三方模块或清理应用数据；复用 API32 保留运行，本轮启动的已有 API35 AVD 已停止。两版仍为 1.0.323 未发布，未提交、推送、发布或交付 APK。真实改机用户的闪退仍未确认修复。

正式 APK 的混淆位置需要该构建的 mapping.txt，不能用另一版本的映射还原。发布时应保留对应普通版/F-Droid APK 的完整版本与 mapping；Git 提交字段只标识构建时 HEAD，工作区的未提交内容不因此变成该提交的一部分。

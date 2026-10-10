# 安全存储启动读取失败：1.0.318 验证记录

日期：2026-10-08。

用户报告普通版 `1.0.317-26100612-22`、Android API 35、`monica_secure_prefs`、`UNREADABLE_SECURE_STORAGE`。用户确认关闭并重新打开 Monica 即可恢复，没有重启手机。该信息支持临时初始化或进程内读取失败，但旧诊断没有底层异常类型，不能据此确定 Keystore 故障或数据损坏。

## 确认的代码问题

- MainActivity 原先同步执行一次启动检查，任何读取失败立即显示保护页；手动重试通过 Activity 重建重新读取，没有永久缓存失败结果。
- 启动前的原始 SharedPreferences 检查可能抛出普通异常，原先只捕获 SecureStorageUnavailableException，导致此处的异常漏出保护流程。
- 多个读取阶段都使用同一个错误码，原有诊断不足以区分失败位置及系统异常。

## 修改

- 沿用系统启动页，在 IO 线程打开安全存储；未分类的读取异常最多尝试三次，重试间隔为 200 / 600 毫秒。每次仍执行原有的防覆盖检查并重新构造 SecurityManager。
- 密钥缺失、密文认证失败、缺少 keyset 及恢复冲突不自动重建、降级或清空。持续失败仍显示保护页；读取恢复不会授予解锁权限。
- 前台启动和后台维护复用同一异步重试入口；协程取消后停止后续读取。
- 诊断新增阶段、尝试次数、最多八层异常类型，以及 API 33+ 可用的 Keystore 数字错误码和临时失败标志。排除异常原文、口令、密钥及偏好值。
- 同步普通版与 F-Droid 实现和测试，记录到 1.0.318 的普通版、F-Droid、候选中英文说明与 fastlane 25.txt；保留已发布历史和版本配置。

## 验证方法与边界

公共 AVD：Monica_Issue136_API_32（API 32，x86_64）。测试使用独立命名的偏好文件、恢复目录及测试密钥；没有清空应用数据。故障注入让真实启动读取边界的 getAll 抛出合成 ProviderException。它验证容错行为，不代表复现了用户 API 35 设备的底层异常。

修复前，新增的三个用例均按预期失败：一次读取失败未恢复、持续失败只尝试一次、启动前检查异常漏出。此时仅增加了转发到原 prepare 的测试入口，没有加入重试。

修复后，普通版和 F-Droid 的 SecureStartupInstrumentedTest（16 项）、LocalVaultRecoveryInstrumentedTest（14 项）、ConnectionRecoveryInstrumentedTest（4 项）全部通过，各 34 项，共 68 项。新增用例覆盖瞬时读取恢复、三次上限、启动前异常、取消、已知损坏不重建、诊断脱敏。普通版运行 270.1 秒，F-Droid 运行 242.226 秒。

普通版和 F-Droid 的应用与测试 APK 已完成编译打包。安全单测两版各运行 54 项，50 项通过、4 项失败。失败均来自 BiometricUnlockRegressionGuardTest 的既有源码字符串断言；测试及失败断言所检查的源文件与各自 HEAD 无差异，本次未修改或跳过这些检查：

- mdkWrapperRebuildHandlesInvalidatedAndUnrecoverableKeystoreKeys
- emptyRuntimeMdkCacheCannotMaskReadableKeystoreWrapper
- pageSwitchHotPathsDoNotRunAuthOrBitwardenSyncWorkOnMainThread
- autofillPasswordSelectionReturnPathDoesNotBlockMainThread

普通版冷启动最终进入主密码设置页（UIAutomator 20 个节点），没有崩溃日志。首轮 am start -W 等待超时，随后界面可以读取；不以这次软件渲染模拟器结果推断真机启动性能。没有在用户设备验证，也没有确认其具体系统异常。

本次构建日志、红灯和绿灯运行日志位于工作区 .codex-temp/secure-startup-318/；没有提交、推送或创建 PR。

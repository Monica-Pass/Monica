# F-Droid 验证及主版共享修复

2026-09-26，F-Droid 1.0.314（versionCode 21）的后续验证已完成。[完整报告、设备截图、M3E 草图与限制](https://github.com/Monica-Pass/Monica-Fdroid/blob/codex/android-native-vaults-and-keyboard/docs/testing/fdroid-1.0.314-verification.md)保存在 F-Droid 仓库。

验证发现并修复了 KDBX 空密码被其他受保护字段替代、MDBX 联系人/地址/银行卡字段丢失及备份/重复导入的内容保留问题。共享生产修复、回归和新功能翻译已同步到主版；没有移植暂停中的密码内容编辑器实验。

F-Droid 完整 JVM 1,942 项（1 项跳过）、设备 237 项（1 项跳过），执行项无失败；通用 R8 Release 的三个 ABI、16KB 对齐和依赖边界通过检查。实际 Release 的 KDBX/MDBX 文件通过 PyKeePass/Monica CLI 独立读取；GPG 保存重开和系统通行密钥创建/登录通过，ES256 响应独立验签通过。

主版共享修复重新打包，并通过 **28 项相关 JVM + 40 项设备测试**，无失败或跳过；这不是主版全仓库测试结果。旧 API 35 实验镜像存在未确定根因的崩溃，已用相同 APK 在 API 36 16KB 环境完成上述 Release 对照流程，详情与适用范围见完整报告。

原始记录位于主仓库 `.codex-tasks/20260926-fdroid-verification/`。主版/F-Droid 发行说明与 F-Droid Fastlane 21 已更新。

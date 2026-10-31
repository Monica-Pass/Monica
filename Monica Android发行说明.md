# Monica Android 1.0.324

> 未发布 / Unreleased

## 中文

- 新增系统密钥异常后的确认恢复：已有有效的本机恢复材料、且系统密钥服务正常时，可用原主密码恢复本机安全配置，再正常登录。创建新存储密钥前会弹窗二次确认；取消、返回或离开前台不会继续恢复。原配置、数据库和附件保留，超时或失败保持锁定；恢复后需重新开启生物识别解锁。
- 本机恢复材料由支持该机制的版本在设置或成功验证主密码时自动建立、维护，包含由主密码保护的恢复密钥与加密配置快照，无需提前手动导出。请保留应用数据；未生成或已丢失恢复材料，以及仅依赖永久丢失旧密钥的数据，无法通过此路径恢复。
- 已在 Android 模拟器验证真实系统密钥丢失、替换及确认恢复登录流程。真实 OMK 设备尚未验证，不保证所有模块或改机环境均可恢复。

## English

- Added confirmed recovery when device Keystore keys become unavailable. With valid local recovery material and a working system key service, use your original master password to restore secure settings and then sign in normally. A second dialog confirmation is required before new storage keys are created. Cancelling, going back or leaving the foreground stops recovery. Original settings, databases and attachments are retained; timeout or failure keeps the app locked. Biometric unlock must be enabled again after recovery.
- Supported versions automatically create and maintain local recovery material when you set or successfully verify your master password. It contains a password-protected recovery key and an encrypted settings snapshot; no manual export is required. Keep the app data. This route cannot recover data if the required material was never created or has been lost, or if the data depends solely on permanently lost old keys.
- Real key loss, replacement and confirmed recovery followed by normal login were tested on an Android emulator. Actual OMK devices remain unverified; recovery is not guaranteed for every module or modified system.

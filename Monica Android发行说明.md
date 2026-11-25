# Monica Android 1.0.331 · 千石抚子（SengokuNadeko）

未发布 / Unreleased

## 中文

- 修复自动填充关联应用漏推荐：登录选择页在认证和切换数据库后补齐当前来源的匹配记录，保留系统推荐顺序、网页来源限制与原有锁定校验。搜索补齐已安装应用名称、多应用关联及 androidapp:// 关联，并统一忽略搜索首尾空格；不改记录内容。

- 新增「设置 → 主密码与锁定 → 修复生物识别解锁」。旧设备密钥或解锁封装失效时，重新输入原主密码并确认，可创建独立的新绑定；回读验证后才启用，保留原密码库、密码保护和旧恢复材料。
- 修正「主密码解锁一次即可恢复」的不准确提示；当前进程的密码解锁缓存不再被当作持久绑定已修复。开启生物识别前检查持久绑定，修复成功不会自动开启生物识别。
- 修复流程支持取消、后台和锁定撤销，并拒绝过期或冲突请求；生物识别后的主界面密钥读取改为有界后台处理，避免慢安全提供器阻塞界面。既有认证要求不会因修复失败降级。
- 修复入口可搜索，文案覆盖现有 18组语言配置。环境改变导致的永久密钥丢失仍需独立恢复材料；本功能不保证恢复只由已丢失硬件密钥保护的内容。

验证范围和保留的测试失败见 [生物识别修复验证报告](Monica%20for%20Android/docs/verification/biometric-rebind-331.md)。

## English

- Fix missing bound-app autofill recommendations: refresh login matches after authentication and source changes, preserving framework order, web-origin restrictions and lock checks. Search includes installed app labels, multiple bindings and androidapp:// associations, with consistent query trimming; credential records are not rewritten.

- Added Settings → Master password and locking → Repair biometric unlock. Confirming with the original master password creates an independent binding when the old device key or wrapper is unavailable. The candidate is reopened and verified before selection; vault data, password protection and previous recovery material are retained.
- Replaced the inaccurate promise that one password unlock repairs biometrics. A warm password-unlocked cache no longer counts as a healthy persistent binding. Enabling biometrics verifies the binding; repair does not enable biometrics automatically.
- Repair rejects cancelled, backgrounded, locked, stale or conflicting requests. Main authentication reads use bounded background work and validate the request before key publication. Existing authentication requirements are never downgraded after failure.
- The repair setting is searchable, with text in all 18 existing locale configurations. Permanently lost hardware keys still require independent recovery material; this feature cannot guarantee recovery of content protected only by a lost key.

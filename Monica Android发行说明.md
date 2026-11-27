# Monica Android 1.0.332 · Hanekawa Tsubasa（羽川翼）

状态 / Status: 未发布 / Unreleased

## 中文

- 新增「同步与备份 → 文件夹备份」：通过系统文件选择器选择手机目录、SD 卡或 RSAF 等文件提供器。使用独立备份密码和现有加密 ZIP 格式，支持手动备份、定期检查、改动后备份及保留份数设置；锁定或不在前台时等待解锁后执行。
- 文件夹备份写入新文件并读回校验，仅清理本次安装记录的完整自动备份；手动备份永久保留。云盘目录额外保留最近 48 小时的文件及至少两份更早备份，上传进度由文件提供器负责。权限失效、备份不完整或写入校验失败时保留旧备份。备份范围为 Monica 本地数据，外部库和连接配置不包含在内。
- 修复 MDBX 数据库信息页在写入失败后仍显示「已同步」的问题，状态与错误统一使用当前数据库记录；继续保留文件提供器并发写锁与冲突保护。
- 修复生成器密码强度标签固定显示中文的问题，五档强度随当前语言显示，并纠正阿拉伯语、法语、印尼语、意大利语和土耳其语对「中等」强度的误译。

## English

- Added Sync and backup → Folder backup. Select device storage, an SD card or a document provider such as RSAF through the system picker. Uses a separate backup password and the existing encrypted ZIP format, with manual backups, periodic checks, backups after changes and configurable retention. Locked or background sessions defer work until Monica is unlocked and visible.
- Folder backups create new files and verify their bytes by reading them back. Cleanup only applies to complete automatic backups recorded by this installation; manual backups remain permanent. Cloud providers retain files from the last 48 hours and at least two older generations, and manage remote upload themselves. Lost permissions, incomplete data and failed write verification preserve existing backups. The scope is Monica local data; external vaults and connection settings are excluded.
- Fixed MDBX database information showing “In sync” after a write failure. Status and errors now come from the current database record, preserving provider write-lease and conflict protection.
- Fixed generator password-strength labels remaining in Chinese. All five levels now follow the selected language. Corrected the middle rating in Arabic, French, Indonesian, Italian and Turkish.

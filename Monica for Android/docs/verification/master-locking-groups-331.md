# 主密码与锁定 M3E 卡片组 · 1.0.331

2026-11-25，未发布，普通版 versionCode 12。

按用户截图，将六张独立设置卡片连接成一组，沿用设置首页的 24dp 外圆角、4dp 内圆角、2dp 行间距及 surfaceContainer。指纹开关、修复生物识别、恢复保护、自动锁定、密保问题、重设主密码保持原顺序、完整文案和原回调。禁用指纹仍保留原禁用状态与配色。

生产变更仅涉及 MasterPasswordLockingSettingsScreen.kt、SettingsScreen.kt 和 RecoveryProtectionSetting.kt：无标题的 SettingsSection 不预留空标题；恢复保护支持传入分组形状。认证、生命周期取消、恢复材料、备份、ViewModel、存储、同步和密码规则未改。未修改卡包、比较其他 KeePass 客户端或添加数据库新建功能。沿用共享 Setting 定义和原搜索定位，无新资源键。

## 实际验证

| 检查 | 结果 |
| --- | --- |
| Debug x86_64 主包与测试包 | 成功，138.843s |
| SettingsSectionShapeTest + SettingsSearchIndexTest | 7/7 JVM 通过 |
| MasterLockingCardGroupUiTest | 6/6 设备通过 |
| RecoveryProtectionUiTest + BiometricRebindUiTest | 7/7 设备通过 |
| 两版共享路径 | 三份生产文件和新增设备测试字节一致 |
| F-Droid 元数据与脚本 | versionCode38 校验、6 项脚本通过 |

使用已有 MuMu2、Android15/API35、x86_64 与本次新建合成 user10，隔离应用 ID。测试直接渲染真实 Compose 页面，检查六项顺序、密保/重设/返回回调、自动锁定设置持久化及 Back 取消；修复和恢复保护原密码确认、取消保留材料、备份回调、锁定移除输入；原有组件测试另验证成功修复后原密文读取及恢复保护验证。两版各13个不同设备方法均首次通过；本轮无失败构建或设备批次。

390dp 中文深色正常字号，以及320dp中文深色/英文浅色1.5字号截图已查看；长说明按自然高度换行，滚动可达最后一项。没有固定行高、截断或省略原生文案。模拟器不支持真实指纹，指纹开关的禁用显示保留；真实硬件指纹认证、完整 Activity 目标导航、真机/OEM和本轮 R8 升级未验。密保、重设和备份目标在测试中由回调记录，因此不声称本轮完成密码更改或实际备份导出。

## 证据和范围

设备回执和两份 JVM XML 与本报告同目录、前缀 master-locking-groups-331。原始构建日志、APK SHA256、源码冻结和任务操作记录留在本地任务目录。构建驱动额外复制了此前 kotpass XML，当前命令未调用 kotpass:test，未计入本轮测试数量。可编辑 M3E Canvas 及截图仅留本地忽略目录，图不入库。

两版主包、测试包对应本轮冻结源码。保留其他任务改动；本轮未提交、推送、发布或交付 APK。测试结束仅卸载本次隔离应用并移除新建 user10，恢复原 user0，停止本次启动的 MuMu2 与独立 ADB15070；原用户、其他实例、配置和数据盘保留。清理回执另存本地。

清理首次按默认 user0 卸载被拒绝，随后显式 user10 卸载成功；停止后立即删除 user10 失败，确认只剩 user0 运行后重试删除成功。原失败与成功回执均保留，未清原用户数据。

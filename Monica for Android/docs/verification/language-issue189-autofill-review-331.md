# 语言补缺与 #189、自动填充复核 · 1.0.331

2026-11-25，普通版 code12，未发布。本轮完成语言补缺，并复核旧会话的本地密码移入 Bitwarden、自动填充推荐不显示和应用名搜索修复；在下述源码审查与当前回归范围内，没有发现新增业务回归。本轮没有修改生产 Kotlin、安全、存储或自动填充逻辑。

## 语言收尾

17 个翻译包均覆盖当前 7,351 项资源。本版新增 6403 个语言×键、修正 38 个既有值。简中、香港繁中、文言文、越南语、日语、俄语、韩语、德语、西班牙语、法语、波兰语、意大利语补齐遗漏；印尼语修正邮箱别名、WebDAV URL、Sky/Power 模式名，土耳其语补译归属说明。台湾繁中、欧洲葡语及巴西葡语当前资源原已齐全。

主要补齐附近/设备/局域网同步、默认密码管理器、Root/Shizuku 授权、Passkey 迁移/备份/交换、安全启动/恢复、自动填充/顺序复制、图片订阅、Bitwarden 二验、卡包和生成器。另纠正文言文 14 项旧数字或技术标识。逐语言数量及首次失败见 [语言验证报告](existing-locale-completion-331.md)。

本版 16 JVM、34 个不同设备方法取得通过结果，含新增/修正文案实际编译值、12 种语言共 24 个真实组件 1.5 字号用例。两版共有的 6,430 项新增/修正文案在相同默认原文下译文一致。普通版首轮 JVM 与设备有失败，修正译文及夹具后通过；原失败保留，不能称首轮全绿。

## #189：本地密码移入 Bitwarden

[GitHub #189](https://github.com/Monica-Pass/Monica/issues/189) 本轮只读检查时仍为 OPEN。旧报告的 `Password ownership changed` 与此前复现路径一致；本次没有评论或关闭 issue。

重新审查 `BitwardenPasswordHistoryStore.persistLocalTransfer` 和 `PasswordViewModel.saveGroupedPasswordsInternal`：仅接受真正本地来源，校验相同记录 ID、准确目标会话、无原生/既有 Bitwarden 历史绑定、当前完整行和原密码快照。历史成功解密后按目标 MDK 重封装，保留历史 ID/date。记录、历史和自定义字段处于带全局/目标锁代校验的 Room 事务，异常或取消回滚；普通同归属更新继续执行原归属保护。

当前 19/19 JVM、42/42 设备方法通过。设备组包含 9 项 issue 迁移、21 项历史/权限/修订、4 项新建事务/UI、8 项 KeePass 保存回归；runner 43.184 秒，完整驱动 46.211 秒。两个生产文件与先前最终 Debug/R8 冻结字节完全一致，四份先前修前/修后 Release APK 存在且重算哈希匹配。

## 自动填充：推荐、应用名与 KeePass＋WebDAV

重新审查 7 个生产文件的旧版/当前差异及调用链：登录选择页认证后从当前数据源重新匹配，保留 WebView、scheme、URL 匹配规则；新密码、银行卡等结构化请求的显式空列表继续保持其语义。应用名称在 IO 线程解析、仅在请求内缓存，不写回密码记录。异步发布仍核验取消、当前范围、授权及原生修订，填写回调再次检查当前源。

当前 53/53 JVM、25/25 设备方法通过，runner 83.374 秒，完整驱动 86.234 秒。覆盖本地/MDBX/KeePass/Bitwarden 来源选择、锁定和迟到结果拒绝、应用名称搜索以及实际 Dataset 值。

其中 KeePass＋WebDAV 的 CACHED_MIRROR 与 WORKING_COPY 两种模式，均通过生产 WebDAV transport 从本机 MockWebServer 下载实际加密 KDBX，再验证认证后从空旧建议重新推荐、按已安装应用名搜索、返回真实填写 Dataset；核对原文件/镜像字节保留，选择和填写没有增加远端请求。此为合成服务器测试，不能视为用户真实云端或任意第三方 App 的验收。7 个生产文件与先前最终 Debug/R8 冻结字节一致，两份先前修后 Release APK 的哈希重算匹配。

## 证据与边界

本版最终构建的全部 6380 个源码/资源/构建输入与本轮测试冻结相同；安装中的主包及测试包哈希在清理前逐项核验。当前结果见 [机器可读记录](language-issue189-autofill-review-331-results.json)、[#189 设备回执](language-issue189-autofill-review-331-issue189-device.txt)、[自动填充设备回执](language-issue189-autofill-review-331-autofill-device.txt)。既有 [#189 验证](issue189-local-bitwarden-331.md)、[应用绑定验证](autofill-app-bindings-331.md)、[WebDAV 验证](autofill-webdav-bindings-331.md) 的 Release/升级结果属于历史证据，本轮只复核来源和哈希，没有重跑 R8 升级。

本轮为 MuMu API35/x86_64、隔离 Debug 包和合成次用户。未验真机/OEM、用户数据库、真实 Bitwarden/WebDAV 云服务，也未做母语人工审校或所有页面视觉验收。语言齐全及定向回归通过不代表整个应用不存在其他问题。

已恢复原用户 0、原字号和显示旋转设置；删除自有次用户 10，确认本任务四个包消失，原用户包清单与任务开始一致。首次立即移除用户失败，确认其已停止后重试成功；包的异步清理和切回原用户导致的旋转恢复分别等待核对、重新恢复，原日志保留。仅停止本任务启动的既有 MuMu 实例 2 与独立 ADB 15076，配置和数据盘保留，其他任务禁用商店的设置保留。

没有提交、推送、发布、issue 评论或 APK 交付。

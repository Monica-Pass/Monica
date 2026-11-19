# 时间线重设计前源码审查

日期：2026-11-19。范围：普通版 `481f4a1aed6dc8d25fd685f0fb840ade1df4a413` 和 F-Droid `548a396ff7089b603161a098ca6ccdb1746bd039` 的时间线设计基线。方法为静态调用链审查及合成网页原型；没有运行 Android 时间线测试，没有读取真实密码库。

## 已确认的问题

1. `TimelineViewModel.loadTimelineData()` 只订阅最近 100 条，再由 `TimelineScreen` 进行范围和搜索筛选。因此大量同步事件能占据整个候选集；这不是“只显示 100 条但仍搜索全库”。
2. `groupAndAggregateEvents()` 将连续同操作/同类型事件（至少 3 条）合并，没有操作 ID、库身份或同一次批次约束。同步和新建事件会形成缺乏内容语义的数量卡片。
3. `BitwardenViewModel.logBitwardenSyncSummary()` 使用 `Bitwarden Vault #<id>` 作为标题，每次成功同步都记录，调用处未排除零变更静默同步。已有新增/更新/上传/删除/冲突等计数，首页没有充分利用它们；日志未包含完整逐项目变更清单。
4. `OperationLogger.logDelete()` 总是传入空 changes。敏感类型标题又被 `sanitizeItemTitle()` 转成 `TYPE#id`，因此拼接在标题里的“移入回收站”等说明也不能可靠保留。
5. `StandardLogDetailSheet` 遇到空 displayedChanges 显示 `no_changes`，没有区分删除没有字段差异、旧值缺失或解析失败。删除事件详情没有连接到现有回收站的恢复动作。
6. 时间线的标题和库范围主要依靠当前 active/deleted 项目映射；没有当时的独立来源身份与加密历史名称。项目被清理后不能可靠还原历史上下文。
7. `OperationLogger` 通过独立 IO scope 异步写日志。日志与快照彼此在一个 Room 事务中，但不与先前业务修改构成同一事务；不能据此证明每次业务变更都有恢复材料。
8. 目前 `TimelineVersionSnapshot` 是字段前后值，不是完整项目序列化。PASSWORD 可恢复范围仅标题、用户名、网站、密码、备注；TOTP 仅标题/备注；部分 secure item 依赖内部 itemData/notes 快照字段。不能泛化到多账号、附件、自定义字段或完整验证器旧版本。
9. `saveOldDataAsNew()` 从当前一行复制并覆盖部分旧值，清除源库字段；未见在此处复制项目的全部账号、普通字段和附件。它也要求当前变更字段等于旧事件的 newValue；这不是任意历史完整副本机制。
10. `restoreVersion(TimelineBranch)` 与 `saveAsNewEntry(TimelineBranch)` 仍为 TODO。当前 `convertLogsToEvents()` 只生成 StandardLog，不能把分支 UI 当成已完成的恢复能力。

## 可以复用但需要分别验证的能力

- `TrashViewModel.restoreItem()` 已串联远端恢复队列、KeePass 回收站、原有本机更新与恢复日志。新的时间线应接入同一来源能力，不另写一个仅改 isDeleted 的实现。
- `TimelineViewModel.revertEncryptedSnapshot()` 会比较记录中的 expectedValue，并拒绝 KeePass 行；但这里的检查不是完整项目/全部 provider 的原生修订证明，也不是业务写入与日志翻转的一体事务。新版执行层需重新约束，不能只相信 UI 的 canRevert。
- `restoreBatchMove()` / `restoreBatchCopy()` 用 Boolean 汇总，至少一项成功即 true；不足以表达逐项冲突与部分完成。旧批量载荷是否经过脱敏和当前 UI 门控也需分别判断，不能仅凭函数存在认定全部入口可用。
- `KeePassChangeSetApplier.restoreHistory()` 使用原生历史、目标 UUID、历史指纹检查和 nativeMutation，不能替换为 Room 恢复。
- `Mdbx2Repository` 已有 `listDeltaHistory()`、`listCommitDiff()`、`revertCommit()`、`listSnapshots()`、`revertToSnapshot()`，委托原生引擎；时间线需要接入这些能力并明确提交与整库快照的不同范围。
- Bitwarden 的 `passwordHistory` 是密码历史协议，有单独 store 与同步流程；它不等于完整 cipher 历史。删除恢复由已有队列负责，排队成功不等于远端完成。
- 加密编辑快照有 30 天 cutoff，但清理在新增快照时最多每天触发；读接口没有以此证明严格实时到期。新版需在查询/预检时检查到期，而不能把“表中存在”当作仍可恢复。

## 本轮产物和边界

设计规范见 [时间线重设计](../development/timeline-redesign-329.zh-CN.md)。可编辑 M3E Canvas、交互网页、截图、完整本地链接均在被忽略的 `docs/design/timeline-redesign-329/`，不进入 Git。

本轮未改生产代码、数据库 schema、恢复执行器、版本或发行说明。设计中的完整快照、统一恢复预检、分页与同步降噪均尚未实现。没有把源码审查、网页演示或已有其他功能测试称为 Android 时间线修复验证。

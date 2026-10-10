# Passkey / Steam 文件夹移动与复制验证（2026-10-10）

## 实现

- 两个页面均使用统一数据库／文件夹选择器，可选择本地分类、MDBX 文件夹、KeePass 分组、Bitwarden 文件夹或根目录，支持当前数据库内整理。Passkey 补齐 MDBX 文件夹刷新回调；选择器按数据库标识记忆目录 Flow，避免显示信息更新或切换操作时重新加载。
- Passkey COPY 不再降级为 MOVE。新建独立 Room 记录、清除目标云端记录 ID，保留源记录与受保护私钥引用。KeePass／MDBX 按原 WebAuthn 凭据 ID 检查目标冲突；同库复制无法满足唯一性要求时先提示，不改变凭据 ID 或覆盖源。
- Passkey 操作前检查绑定条目、引用条目、私钥可用性与导出能力、目标限制；跨库移动共用 Bitwarden 登录项的条目时阻止单独删除整个登录项。限制列表提供取消或跳过并继续；全部受限时禁用继续。执行前再次检查，完成后汇总跳过和失败原因。
- Steam 使用包含数据库、folderId／groupPath／categoryId 的目标。COPY 使用新记录 ID，避免按 Steam ID 覆盖已有条目；同库 MOVE 保持原 ID。KeePass 同库移动复用原生条目移动，Bitwarden 同库移动只更新文件夹，保留附件和其他内容。跨库 MOVE 先完成目标写入，再清理源记录；写入失败保留源。
- Steam 本地数据库从 schema 3 迁移到 4，新增 nullable categoryId，保留旧记录；取消 Steam ID 的唯一索引约束以允许显式副本，复制使用 ABORT 插入。普通编辑、备注及会话更新保留分类。MDBX 后续编辑从现有 collectionId 保留文件夹，明确移动到根目录仍写入根目录。
- 转移过程中阻止 Steam 切换数据源，避免源记录映射被另一数据库替换。协程取消继续传播，并复位处理中状态。

## 验证

| 版本 | 相关单测 | 公共虚拟机回归 | Debug / AndroidTest 打包 |
| --- | --- | --- | --- |
| 普通版 | 110 通过 | 21 通过 | 通过 |
| F-Droid | 110 通过 | 21 通过 | 通过 |

新增覆盖包括：四类目标身份映射、同库移动不删源、复制不删源、目标失败保留源、真实 MDBX 嵌套文件夹移动／复制及后续编辑、本地 Steam 迁移和副本独立性、删除 Passkey 源记录后副本私钥仍可用、预检查取消与跳过、全部受限、目录加载复用和 COPY 返回准确文件夹 ID。保留并通过前一轮本地同步范围、兼容标记缓存、筛选恢复／往返、真实 AndroidKeyStore 兼容性及结果弹窗回归。

首轮源码守卫仍查找已移除的 Steam 专用选择器中的旧变量名，已更新为新选择器及目标映射。设备测试发现目录 Flow 随数据库显示对象重建重复读取，改用数据库标识后通过。

旧筛选持久化测试曾在等待 DataStore 通知时超时；增加诊断后确认 ViewModel 和新读取的持久化快照均已等于期望值。测试改为在原有 10 秒时限内检查持久化快照，仍验证全部数据库／文件夹及立即选择覆盖初始恢复，未改变产品筛选逻辑。失败日志保留，最终两版 21 项全部通过。

使用已运行的公共 AVD `Monica_Issue136_API_32`（Android 32 / x86_64，emulator-5554），未新建或启动虚拟机；测试结束恢复屏幕超时和筛选设置。存储测试使用隔离数据库及生成的测试密钥。未运行 Steam 实网用例，也未验证真实 Bitwarden 云账户或系统 Passkey 登录；这些后端保留既有锁定、Premium、上传验证和同步错误处理。

最终日志位于工作区 `.codex-tasks/transfer-folders-20261010/`：普通版 `main-verified-build.log`、`main-final-package.log`、`main-complete-device.log`；F-Droid `fdroid-build.log`、`fdroid-complete-device.log`。单测统计读取各版 Gradle XML 结果。

## 设计与渲染

可编辑 Canvas 草图和分享链接仅保存在本地 `docs/design/transfer-folders-20261010/`；设计数据和截图不随此验证记录提交。

已检查真实 Compose 预检查弹窗与文件夹选择器，按钮可达、文件夹层级及完整目标路径可见。实际设备截图见同目录 `passkey-transfer-preflight.png`、`steam-folder-picker.png`。文件夹选择器在独立窗口中，使用设备截图记录实际显示，避免只截取宿主窗口得到空白图。

## 同步及发行记录

共有变更已定点同步至普通版、F-Droid 及两个候选目录，保留候选原有其他 UI；候选未单独重复构建。候选补齐新的报告组件、资源和接口依赖，资源引用检查通过。

四份未发布说明已追加中英文内容。普通版维持 1.0.318 / code 12，F-Droid 构建维持 1.0.318 / code 25，F-Droid 新增说明写入下一版未发布记录。已发布 F-Droid 1.0.318 归档和中英文 fastlane 25.txt 与标签一致，见 `audit.json`。未切版本、提交发布或额外交付 APK。

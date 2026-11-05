# Monica Android 1.0.325

> 未发布 / Unreleased

## 中文

- 统一 Bitwarden 账号安全、服务器配置、账号管理和 Send 接收的页面过渡，补齐进入、返回与内部步骤切换的短缩放效果；关闭或锁定仍立即清理敏感内容。

- 修复 Bitwarden 新建登录在字段保存失败后仍留下记录、重试产生重复项目的问题：记录与字段一起提交或回滚，同步只读取完整记录；允许用户名和密码留空。

- 修复旧本地项目因重复账号字段而无法保存的问题：兼容内容完全一致的副本，保存时整理并保留原密码、账号标识、未知字段及保护设置；冲突内容仍阻止覆盖。兼容旧账号仅部分密码保留验证器的情况，未修改验证器时保留各条原值。

- 修复 OneDrive／WebDAV 备份遗漏部分旧本地笔记或显示 0 项的问题：统计与备份共用本地归属判断，包含归档笔记，排除外部库记录及回收站；保留原记录。

- 修复删除本地多密码账号的绑定验证器后无法再次保存的问题：按账号标识同步清除对应验证器字段，保留其他有效绑定、独立账号、不同密钥及原密码。旧残留仅在明确删除对应验证器时清理，不自动改写。

- 自动填充详情和密码验证改为有界后台读取；取消、锁定或替换请求后拒绝旧凭据结果，密码验证仅在前台请求仍有效时发布解锁密钥。编辑器的自定义字段和模板准备移到后台。

- 改善系统密钥服务异常时的启动与恢复：输入法、自动填充、凭据导出和后台调度共用受控入口，避免重复同步读取及未捕获异常；保留原密文和配置，支持已有主密码保护材料的确认恢复。锁定后拒绝迟到的密钥结果，常用账号迁移遇到不可读字段时保留原值。直接依赖已永久丢失硬件密钥的旧内容仍需要原密钥或备份。 修复并发启动检查交接时误报“操作进行中”的问题，保留超时及取消后的数据保护。

- Bitwarden 个人账号新增原生密码提示、主密码和 KDF 修改，以及服务器账号删除确认。修改主密码/KDF 保留原密码库密钥；结果不确定时暂停联网并保留本机数据，重新登录核验后恢复。确认删除服务器账号后仍保留本机副本，移除本机副本需另行操作。
- 统一 Bitwarden 登录失效处理：收到明确的 401 后协调刷新并仅重试一次；写入丢失回执或服务器错误时不自动重复提交。附件补传保存原密文及精确上传意图，重启后核对并继续，保留待处理附件，避免重复分配。
- 新增原生 Send 接收：粘贴文本/文件链接，支持服务器提供的访问密码和邮箱验证码，下载前校验内容。自托管地址需确认；应用锁定或退出页面清除接收会话，访问结果不确定时需手动重试。

- 完善 Bitwarden 自托管配置：支持独立 Web/API/身份验证/通知地址、按账号限定的请求头、自定义证书及主动 DNS TXT 发现，并可通过加密的 Monica 配置备份恢复；更改已有账号配置需重新认证，保留原数据和待同步编辑。
- 修复快速切到后台时密码、验证码及注册验证输入未及时清空的问题，保留光标和选区行为；改善服务器配置页大字号布局与状态栏图标对比。

- Bitwarden 新设备邮件验证支持手动重发、等待倒计时及限流提示；服务器未提供此接口时明确提示不支持。账号页新增指纹短语查看与复制，按官方算法生成，离线使用已验证的身份资料；账号或应用锁定、切换与离开前台后关闭显示并撤销旧操作。

- 减少 KeePass 原始库中的多余 Monica 字段：保存时省略默认状态、无关联的空字段和空账号备注属性，便笺等项目不再写入空图片路径或默认收藏值。保留账号堆叠、实际备注、关联标识、用户自定义字段、REF 引用、保护位及原文件历史；只处理明确保存的项目，不批量清理旧库。

- 重新设计 Bitwarden 设置与账号页，统一圆角分组和大字号布局；同步队列显示实际待处理条目、附件及失败明细。
- 密码编辑器支持保存、补传和删除 Bitwarden 附件；上传失败保留本地文件和待处理状态。
- 应用处于前台且密码库解锁时，可接收服务器变更通知并同步；后台或锁定后停止监听。
- Send 支持服务端允许的邮箱验证，以及加密保存、恢复和手动提交文本／文件草稿；提交结果不确定时先核对服务器，避免重复创建。退出账号会在确认后移除该账号的本机草稿。
- Send 本机缓存改为加密保存，解锁后迁移可读取的旧记录；普通缓存清理保留未同步或无法确认的内容。
- 修复 Send 草稿重新读取时修改时间变化的问题，保持列表顺序稳定，并兼容缺少时间戳的旧草稿。
- 支持 Bitwarden 普通及密码加密 JSON：导入到 Monica 本地，或从本地／已解锁的 Bitwarden 个人库导出；不能保留的字段会逐项提示并停止整批操作。JSON 不含附件文件、Send 或归档状态。

- 修复编辑页卡片、标签和输入区四角／边框被分区裁剪的问题，统一独立圆角、间距及操作区；重新设计新建二维码、密码二维码和备注界面，改善 Send 账号、日期及开关布局。
- 修复备注与二维码编辑弹窗的字号／语言继承和浅色状态栏对比，支持大字号内容滚动及底部操作避让。

- 同步失败时可按账号查看具体项目，按标题或条目 ID 搜索，并显示失败阶段与原因；账号锁定后清除本次明细。
- 支持查看、删除和清空 Bitwarden 密码历史，记录密码及隐藏字段变更；离线修改、丢失回执和锁定后到达的结果保留正确的历史操作意图。
- 支持 Bitwarden 笔记、银行卡和身份条目归档／取消归档，以及逐网址编辑原生匹配规则，保留重复网址各自的规则与未知内容。
- Send 新建与编辑页统一使用现有条目编辑组件，通过“添加内容”展开备注和访问选项。

- 修复官方 Bitwarden 序列化传统条目数据被误判而无法显示的问题；编辑时同时更新序列化数据，保留未知字段和密码历史。

- 修复同步字段读取失败后被替换为空值的问题；登录、笔记、银行卡、身份和 SSH 条目在写入前校验密文，文件夹及 Send 读取失败时保留原数据并提示同步不完整。

- 修复空自定义字段和旧版主网址被服务器规范化后造成的虚假同步冲突；保留全部网址及未知内容。

- 修复官方 Bitwarden 传统条目带包装密钥标识时被遗漏的问题；核验原密钥，部分条目读取失败时明确提示同步未完成。

- 编辑 Bitwarden 登录、SSH、笔记、银行卡和身份条目时保留未知字段、原密码历史及归档状态；更新回包不完整时保留本地待同步修改。

- 修复 Bitwarden / Vaultwarden 创建、更新与复制条目的上传兼容性，以及编辑后多网址显示异常；保留网址校验信息、端口、路径和匹配规则。
- 修复编辑登录条目时丢失服务端密码历史的问题，保留原历史内容。
- 支持以原生 SSH 类型创建和编辑 Bitwarden SSH 密钥条目。
- 支持编辑已有文本和文件 Send，调整启停、独立到期／删除时间及访问密码，保留原文件内容。
- 增加可选的记住二步验证，以及账号注册、密码提示和设备列表入口。
- 加强同步修订冲突处理及账号安全状态变化后的重新认证，保留较新的本地修改。

## English

- Align Bitwarden account security, server configuration, account management and Send receiver page transitions with Monica, including opening, returning and switching steps. Closing or locking still clears sensitive content immediately.

- Fix Bitwarden login creation leaving a record after field saving fails, causing duplicates on retry. Commit or roll back the record and fields together so sync reads complete records; empty usernames and passwords remain supported.

- Fix saving older local projects with identical duplicate account metadata. Saving consolidates matching copies while retaining passwords, account identities, unknown fields and protection settings; conflicting copies remain blocked. Legacy partial OTP bindings remain intact unless the authenticator is explicitly edited.

- Fix OneDrive/WebDAV backup counts and selection for legacy local notes. Counts and backup contents use the same local ownership rules, include archived notes, and exclude external-vault records and trash; original records are preserved.

- Fix saving local multi-password accounts after deleting a bound authenticator. Clear matching OTP fields within the explicit account together, retaining other live bindings, independent accounts, different keys and original passwords. Previously retained OTP fields are cleared only by an explicit matching deletion.

- Autofill details and password verification now use bounded background reads. Cancelled, locked or replaced requests reject stale credential results; password verification publishes the unlock key only while its foreground request remains valid. Custom-field and template preparation in the editor runs in the background.

- Improved startup and recovery when Android key services fail: bounded entry handling for the keyboard, autofill, credential export and background scheduling; existing ciphertext and settings are retained, with confirmed recovery using available password-protected material. Late key results cannot undo a lock, and unreadable common-account fields remain unchanged during migration. Legacy content tied only to permanently lost hardware keys still needs its original keys or a backup. Fixed concurrent startup checks incorrectly reporting an operation in progress during handoff, while retaining timeout and cancellation protections.

- Added native personal Bitwarden password-hint, master-password and KDF changes, plus server-account deletion confirmation. Password/KDF changes retain the existing vault key. Uncertain outcomes pause account traffic and preserve local data until verified sign-in; confirmed server deletion retains a local copy that requires a separate removal action.
- Unified expired-session handling: coordinate refresh and retry once after a verified 401. Lost write responses and server errors are not automatically replayed. Attachment recovery keeps the original ciphertext and exact upload intent across restarts, reconciles uncertain allocation, and preserves pending files.
- Added native text/file Send reception from a pasted link, with server-supported passwords and email codes and verified file downloads. Self-hosted targets require confirmation; locking or leaving clears the receiver session, and uncertain access requires an explicit retry.

- Expand Bitwarden self-hosted configuration with separate Web/API/Identity/Notifications URLs, account-scoped headers, custom certificates, explicit DNS TXT discovery and encrypted Monica configuration backup. Existing-account changes require reauthentication and preserve data and pending edits.
- Fix password, OTP and registration verification input surviving a rapid background transition while preserving cursor and selection behavior; improve large-text server configuration and status-bar icon contrast.

- Bitwarden new-device email verification now supports manual resend, cooldown and rate-limit feedback, with an explicit message when the server lacks the endpoint. Account settings adds the official fingerprint phrase with copy and offline access to verified identity material; locking, account changes and leaving the foreground close the sheet and revoke old actions.

- Reduced redundant Monica fields in original KeePass vaults: saves omit default state, empty optional links and empty credential-note properties; secure items omit empty image paths and default favorite values. Account stacking, real notes, linkage identities, user fields, REF expressions, protection flags and native history are preserved. Only explicitly saved entries are affected; existing vaults are not bulk-cleaned.

- Redesigned Bitwarden settings and account pages with consistent rounded groups and large-text layouts; the sync queue now shows actual pending items, attachments and issue details.
- Password editors can save, retry and remove Bitwarden attachments; failed uploads retain local files and pending work.
- Server change notifications can trigger sync while the app is foregrounded and the vault is unlocked; listening stops in the background or when locked.
- Added server-supported Send email verification and encrypted text/file drafts with restore and manual submission. Uncertain submissions are reconciled before another create attempt. Confirmed account logout removes that account's local drafts.
- The local Send cache is encrypted, with readable legacy rows migrated after unlocking; ordinary cache clearing preserves pending or unreadable content.
- Fixed draft timestamps changing when read again, keeping Send draft order stable and supporting older drafts without a saved timestamp.
- Added plain and password-encrypted Bitwarden JSON import to Monica local storage, and export from local storage or unlocked personal Bitwarden vaults. Unsupported fields are reported before the whole operation is stopped. JSON excludes attachment files, Sends and archive state.

- Fixed clipped card, chip and field corners in editors, with consistent individual shapes, spacing and actions; redesigned QR creation, password QR and notes, and refined Send accounts, dates and switches.
- Fixed font-scale and language inheritance in notes and QR editor windows, improved light status-bar contrast, and kept large-text content and bottom actions reachable.

- Added per-account sync issue details, searchable by title or item ID, with the failed stage and reason. Locking the account clears these details.
- Added Bitwarden password history viewing, deletion and clearing, including password and hidden-field changes. Offline edits, lost responses and results arriving after a lock retain the intended history changes.
- Added archive/unarchive for Bitwarden notes, cards and identities, plus native match-rule editing for each URL, preserving duplicate URLs and their separate rules and unknown content.
- Unified Send creation and editing with the existing item editor, using Add content for notes and access options.

- Fixed official Bitwarden serialized legacy item data being incorrectly rejected during sync; edits update the serialized data while retaining unknown fields and password history.

- Fixed unreadable encrypted fields being replaced with empty values during sync; validate login, note, card, identity and SSH fields before saving, and retain existing data with an incomplete-sync message when folders or Sends cannot be read.

- Fixed false sync conflicts after server normalization of empty custom fields and the legacy primary URL, preserving every URL and unknown content.

- Fixed omitted legacy Bitwarden items carrying wrapping key identifiers, validating the original key and reporting incomplete sync when some items cannot be read.

- Editing Bitwarden logins, SSH keys, notes, cards and identities preserves unknown fields, existing history and archive state; incomplete update responses keep local edits pending.

- Fixed Bitwarden / Vaultwarden upload compatibility and multiple URL display after editing, preserving checksums, ports, paths and match rules.
- Preserved server password history when editing login items.
- Added native creation and editing of Bitwarden SSH key items.
- Added editing for existing text and file Sends, including enablement, separate expiration/deletion dates and access passwords while preserving file contents.
- Added optional remembered two-step verification, account registration, password hints and a device list.
- Strengthened revision conflict handling and reauthentication after account security changes, preserving newer local edits.

# Monica Android 1.0.327

## 中文

- 修复普通备份还原把同卡号、同证件号或同标题但内容不同的项目误判为重复：仅在完整内容、属性及独立关系均可确认一致时跳过；有附件、笔记绑定或未知内容的项目保守独立还原。附件来源缺失或身份不唯一时在写入前拒绝；笔记只绑定本次成功还原的对应项目，取消或锁定后不再发布旧请求的绑定。重复还原复杂项目可能产生额外副本。
- “通行密钥和密码设置”在系统支持且确认 Monica 服务未启用时进入启用页，其他情况进入安全设置；入口不可用时逐级回退，避免只打开设置首页或崩溃。

- 自动填充与通行密钥设置统一分组行和 M3E 单选底部面板，来源、默认库、匹配策略及验证码时长使用一致交互；整理系统入口、填充、匹配、保存及键盘分组。修复进入设置时数据库列表尚未加载就清空默认库的问题；缺失库保留原选择并提示不可用，仅明确选项才更改范围。

- 物理密钥改为默认关闭的可选功能，可在物理密钥页或“自动填充与通行密钥”设置页切换，两处状态同步。关闭后不添加 Monica 的 USB/NFC 硬件候选并停止相关扫描，已保存的软件通行密钥不受影响；系统或其他提供方的安全密钥入口由其自身控制。

- 加固主密码找回：安全问题答案之外，还需本次系统锁屏凭据确认（Android 11 及以上）；取消、后台、锁定或请求替换后授权失效。持久限制尝试次数，新答案使用带随机盐的 PBKDF2-SHA256／600000 次；旧 SHA-256 答案与备份保留兼容，仅在提供真实答案并完成找回后迁移。
- 改进安全问题设置的取消和再次进入流程：离开页面撤销尚未授权的保存请求，再次进入使用新的操作状态；答案验证页自动刷新实际冷却状态，在限制允许后重新开放尝试。

- 修复从旧版本更新后可能在启动时崩溃的问题，保留原密码库和恢复材料。
- 减小安装包：压缩赞赏码图片、无损优化内置品牌图标并复用 Monica 图标，保留已有项目的 PNG 自定义图标；精简依赖的多余语言资源，保留全部应用语言和特殊中文配置。
- 本地主密码统一使用 PBKDF2-SHA256／600000 次派生，移除算法切换入口；兼容读取旧 PBKDF2 和 Argon2id，并在成功验证原密码后迁移，保留原数据密钥与已存内容。
- ZIP 导出前弹窗选择加密 V1、V2 或符合条件的明文备份；Passkey、附件和 Monica 配置仍须加密。WebDAV／OneDrive 使用分组选项弹窗，确认后保存，取消不更改选择。
- 后台导出任务持有本次所选文件的临时授权；选择器默认建议主存储的 Download 目录，仍由用户确认位置。缺少所需的实际文件授权时不启动导出，并提示重新选择有授权的位置。
- 拆分备份恢复的独立读取和处理分支，减小单个协程的规模，保留既有恢复和错误处理规则。
- 输入法解锁框避让软键盘，内容过高时可滚动操作，支持大字号。修复切换系统字号时重复挂载键盘视图导致的输入法崩溃。
- 旧加密 ZIP 备份仍可读取，新备份默认使用兼容 V1；可主动选择 600000 次派生的增强 V2，但恢复端也必须支持 V2，请保留旧备份。
- 旧 APK 无法识别新的本地主密码保护。请使用支持此保护的新版客户端，不要直接降级打开升级后的本地数据。

- 优化 KeePass 内部代码结构，按职责拆分目录、条目和字段处理逻辑，便于维护与后续改进；现有加密方式、文件格式和使用流程保持一致。

- 精简银行卡和证件新建页，移除顶部 NFC 按钮、说明和读取成功提示；保留自动贴卡填入，卡片类型在原有菜单中确认。
- 改进银联 NFC 兼容性：补齐应用选择与读取参数，兼容部分无需 Luhn 校验的银联卡，并补充 Track 1 卡号、姓名和有效期读取。实体银联卡仍需复测；卡片未公开的信息无法读取。
- Send 访问控制改为与银行卡类型选择一致的紧凑 M3E 菜单，选中后显示对应密码或邮箱字段。
- 内置图标库添加 Monica 本体图标，支持搜索并为条目选择（#180 / #181）。
- 整理 Send 创建页：以单张账号卡片打开账号单选底部面板，并将草稿入口移至新建页。
- 若当前表单有未保存内容，打开草稿前可取消或确认替换；已保存的草稿会保留。
- Send 搜索支持识别粘贴的 Send 链接，点击后才打开接收流程；自托管服务仍须先确认目标。
- 新增 NFC 银行卡录入，不新增依赖：支持部分非接触式 EMV 银行卡公开提供的卡号，以及卡片可选提供的持卡人姓名和有效期；居民身份证号码等受保护信息暂不支持读取。
- 在列表刷卡成功后进入已填信息的银行卡新建页；在同类银行卡新建页补填读取信息，遇到已有不同值先确认替换。当前为其他类型时，可另开银行卡新建页并保留原页内容。
- NFC 无法可靠区分信用卡、借记卡或预付卡，录入后须明确确认卡片类型，并核对信息后手动保存。
- 改进版本更新弹窗，显示版本对比和可滚动的 Markdown 更新说明，支持跳过此版本、查看详情与稍后处理。
- 新增默认开启、可在设置中关闭的每日自动更新检查；仅在解锁后的前台主页面检查，跳过的版本不再自动提示，手动检查仍可查看。
- 更新弹窗在 Android 12 及以上、系统支持并启用跨窗口模糊时使用高斯背景模糊；其他情况保留背景暗化。
- 恢复验证码、银行卡、证件、API Token、Wi-Fi 等新建/编辑页的连续字段卡片组，保持独立操作按钮与错误提示完整显示。
- 更新 Android 构建工具与相关依赖，并保留 Android 8.0 最低支持版本。
- 修复退出或重置 Bitwarden 登录、关闭 Send 接收页面时，TLS 连接回收可能触发主线程网络异常的问题。
- 库移除确认准确说明文件处理范围：KeePass 移除注册后保留原文件，MDBX 本地库确认删除时移除目标本地文件。

## English

- Ordinary backup restore no longer treats different card, document or note content as a duplicate just because the number or title matches. Skipping requires complete content/metadata equality and a standalone item; related or unknown content is restored independently. Missing or ambiguous attachment owners are rejected before writes. Note bindings target only successfully restored rows and reject cancelled or locked requests. Repeating complex restores can create additional copies.
- Passkey and password settings opens provider enablement only when supported and Monica is explicitly disabled; otherwise it opens security settings, with fallback for unavailable handlers.

- Unify Autofill & passkeys settings with grouped rows and M3E single-choice sheets for sources, default vaults, matching, and OTP duration. Organize system, fill, matching, save, and keyboard settings. Opening settings no longer clears a default vault before its list loads; unavailable selections are preserved until an explicit choice is made.

- Make physical security keys opt-in and off by default, with a shared switch on the physical-key page and the renamed Autofill & passkeys settings page. Turning it off removes Monica hardware candidates and stops related USB/NFC discovery without changing saved software passkeys; system and other provider entries remain under their own control.

- Harden master-password recovery: security answers also require fresh device-credential confirmation on Android 11+. Cancel, background, lock or request replacement revokes authorization. Persist attempt limits, store new answers with salted PBKDF2-SHA256/600000, and keep legacy SHA-256 answers and backups readable; migrate only after actual answers and authorized recovery.
- Improve cancellation and re-entry of security-question setup: leaving revokes saves that have not yet been authorized, and reopening starts a fresh operation. The answer page refreshes the actual cooldown and permits retries when the limiter allows them.

- Fix a startup crash after updating from an older version while retaining the existing vault and recovery material.
- Reduce package size with compressed donation images, lossless bundled brand icons and a shared Monica icon. Preserve existing custom PNG icons and all app languages, including special Chinese locales, while removing unused dependency translations.
- Standardize local master-password writes on PBKDF2-SHA256 with 600000 iterations and remove the algorithm selector. Read historical PBKDF2 and Argon2id credentials, migrating only after successful original-password verification while retaining the data key and stored content.
- Choose encrypted V1, V2 or eligible unencrypted ZIP in a dialog before export. Passkeys, attachments and Monica configuration still require encryption. WebDAV and OneDrive use grouped format dialogs that save only on confirmation.
- Background exports retain temporary permission for the selected file. The save dialog suggests Download in main storage and still requires the user to choose a location; missing required file access stops export and prompts selection of a location with actual access.
- Split independent backup-restoration read and processing branches into smaller functions to reduce the size of a single coroutine while preserving existing restore and error-handling rules.
- Keep the keyboard unlock dialog above the software keyboard, with scrolling for larger content and font sizes. Fix an IME crash caused by reattaching its view after a system font-size change.
- Keep reading older encrypted ZIP backups and use compatible V1 for new backups by default. Enhanced V2 with 600000 iterations is optional and requires a V2-capable reader; retain earlier backups.
- Older APKs cannot recognize the new local password protection. Use an updated client that supports it; do not directly downgrade to open upgraded local data.

- Improve KeePass code organization by separating group, entry and field handling, making maintenance and future improvements easier while preserving existing encryption, file formats and workflows.

- Simplify bank-card and document creation by removing the top NFC button, help block and success messages. Keep automatic tap-to-fill and confirm the card type in the existing menu.
- Improve UnionPay NFC compatibility with broader application selection and appropriate read parameters, limited support for UnionPay numbers without Luhn validation, and Track 1 number, name and expiry parsing. Physical UnionPay cards still need retesting; information withheld by the card remains unavailable.
- Use a compact M3E menu for Send access control, matching the bank-card type picker and showing password or email fields for the selected mode.
- Add the Monica app icon to the built-in icon library for search and entry selection (#180 / #181).
- Simplify Send creation with a single account card that opens a single-selection bottom sheet, and move drafts into the New Send page.
- When opening a draft would replace unsaved form edits, offer cancel or confirm; saved drafts are retained.
- Recognize pasted Send links in search and open the receiver only after a tap; self-hosted services still require target confirmation.
- Add NFC-assisted bank-card entry without new dependencies. Read publicly available card numbers and optional cardholder names and expiry dates from some contactless EMV bank cards; protected information such as national identity-card numbers is not supported.
- A successful scan from the list opens a prefilled new bank-card page. In a matching new bank-card form, fill the read details and ask before replacing conflicting values. From another item type, offer a separate new bank-card page while keeping the original form content.
- NFC cannot reliably distinguish credit, debit and prepaid cards. Explicitly confirm the card type, review the details and save manually.
- Redesign the update dialog with version comparison, scrollable Markdown release notes, skip-version, details and later actions.
- Add daily automatic update checks, enabled by default and optional in settings, while the unlocked main page is in the foreground. Skipped versions stay hidden from automatic prompts and remain visible through manual checks.
- Apply Gaussian backdrop blur to the update dialog on Android 12 or later when cross-window blur is supported and enabled; retain background dimming otherwise.
- Restore connected field groups in authenticator, bank card, document, API token, Wi-Fi and related editors while keeping separate actions and error messages fully visible.
- Update Android build tools and related dependencies while retaining Android 8.0 as the minimum supported version.
- Fix main-thread network exceptions during TLS cleanup when leaving or resetting Bitwarden login or closing a Send receiver.
- Clarify file handling in vault removal confirmations: removing a KeePass registration retains the source file; confirming local MDBX vault deletion removes the selected local file.

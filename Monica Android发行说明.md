# Monica Android 1.0.327

## 中文

- 修复从旧版本更新后可能在启动时崩溃的问题，保留原密码库和恢复材料。
- 减小安装包：压缩赞赏码图片、无损优化内置品牌图标并复用 Monica 图标，保留已有项目的 PNG 自定义图标；精简依赖的多余语言资源，保留全部应用语言和特殊中文配置。
- 提升本地主密码保护：旧版 PBKDF2 在首次成功验证主密码时升级至 600000 次派生，保留原数据密钥与已存内容；安全设置中可为整个应用选择 Argon2id。
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

- Fix a startup crash after updating from an older version while retaining the existing vault and recovery material.
- Reduce package size with compressed donation images, lossless bundled brand icons and a shared Monica icon. Preserve existing custom PNG icons and all app languages, including special Chinese locales, while removing unused dependency translations.
- Strengthen local master-password protection: after the first successful password verification, upgrade legacy PBKDF2 to 600000 iterations while retaining the existing data key and stored content. Security settings also offer Argon2id for the whole app.
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

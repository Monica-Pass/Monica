# Monica for Android 1.0.314

## 中文

### 简要

- 优化 Monica 键盘大库加载与搜索，新增可关闭的数字键随机和隐藏预览，并统一按键布局。

- 新增面向 AI 服务的 API Key 类型，分别保存供应商、官网、密钥、请求地址和备注。

- 新增 GPG 密钥与生成器操作菜单。
- 新增意大利语及 Liberapay 支持入口。
- 支持密码字段值二维码 / Code 128 展示，以及 Emoji、已安装应用和图标包图标。
- 通行密钥保存和登录选择器统一使用 Monica 彩色图标与名称。
- 停用 MDBX1 日常使用，保留原文件并提供 MDBX2 升级。
- 修复 MDBX2 增量同步及兼容库写入，减少 WebDAV 重复请求。
- 修复 KeePass 条目和验证码漏显，补齐原生条目的验证码、标签与有效期管理。
- 优化新建页面、验证器布局和密码库预览性能。
- 独立调整 Steam 令牌卡片的内容间距，改善背景可见性；验证器磁贴保持原布局。
- 完善二维码编辑、SSH 数据保存及数据库、卡包的显示与返回体验。

### 详细

- **键盘加载与搜索**：使用轻量数据库投影，预计算中文排序和首字母导航，复用解锁期间的数据索引；大列表搜索接入现有 Rust 批处理，并保留兼容回退。合并重复窗口加载，搜索不再匹配数据库来源标签；旧查询和锁定前的读取结果不会回写到新页面。
- **PIN 与键盘布局**：自动填充设置新增“随机排列数字键”和“隐藏数字按键预览”，均默认关闭、可独立设置，修改会同步到独立键盘进程。数字键每次打开时洗牌，输入过程中保持稳定；数字输入框自动使用数字键盘。统一字母键宽度、第二排居中缩进、Shift / 删除对称，Enter 和模式键采用胶囊圆角；数字键盘按键等高，窄屏和横屏保留操作空间。密码条目的验证码改为填充时读取并生成，避免使用过期缓存。

- **AI API Key**：密码页的新建类型菜单新增 API Key，支持官方、自建和中转服务。供应商名称作为条目标题，官网与 API 请求地址分别保存；支持本地密码库、MDBX2、KeePass 和 Bitwarden。密钥加密保存、默认隐藏，可查看、编辑和复制；锁定时清除编辑草稿，不作为网站登录密码参与自动填充。

- **GPG 密钥**：在新建页和生成器中生成或导入密钥，加密保存到密码库；支持导出公私钥、复制指纹和设置私钥口令。
- **生成器菜单**：点击随机密码、单词、短语或 PIN 的结果卡片，可复制或用于新建用户名、密码；SSH 结果可分别复制公钥、指纹和私钥，GPG 结果提供复制、导出与创建条目操作。
- **字段值条码（#141）**：密码详情的字段值展示支持在二维码与 Code 128 间切换。二维码支持 Unicode 文本；Code 128 可展示礼品卡、会员编号等最多 80 个可打印 ASCII 字符。无法编码时显示提示，屏幕过窄时提示横屏查看，避免将长条码压缩到难以扫描。
- **密码 Emoji 图标（#142）**：自定义图标增加 Emoji 入口，支持快捷选择或输入单个 Emoji（含组合、旗帜和肤色修饰符）。编辑预览、列表、详情、自动填充、安全分析及通行密钥绑定的密码图标保持一致；放大系统字体时图标尺寸保持稳定。
- **已安装图标（#142）**：可从手机已安装的应用或兼容图标包中搜索并选择密码图标。选中的图像保存到 Monica，卸载来源应用或图标包后仍可显示；旋转屏幕保留搜索和未保存的图标选择。
- **通行密钥系统标识**：保存位置和登录条目明确提供 Monica 彩色应用图标，修复通用钥匙图标在浅色背景下难以辨认的问题；服务名称统一显示为 Monica Pass，便于区分其他密码管理器。
- **MDBX1 停用与升级**：不再新建或日常使用 MDBX1；已有数据库保留在管理页并标为不可用，可升级为 MDBX2。升级校验完成后才接入新库，原文件保留；远程旧库升级设备上已保存的副本，生成新的本地 MDBX2，远程未下载的修改不包含在内，云同步需另行配置。
- **MDBX2 增量同步**：修复分片乱序时缺少父提交导致同步中断的问题；依赖到齐后继续合并，同一轮同步复用已下载分片。恢复初始副本时可取回本机先前上传的历史；分页上传保留续传状态，减少重复目录与附件检查。
- **WebDAV 同步**：复用已确认的远程目录，减少路径探测和目录列表请求；服务器返回不含 Retry-After 的 503 时也执行退避，避免连续请求加重拥堵。
- **兼容数据库写入**：CLI 等客户端创建的 MDBX2 缺少 Android 默认根集合时，在首次写入的同一事务中补齐，保留已有集合与内容。
- **大量密码读取**：列表读取使用一致快照，避免同步、删除与跨 CursorWindow 读取并发时出现数据不一致；停用的 MDBX1 缓存不再影响自动填充保存和导入去重。
- **语言与支持**：新增完整意大利语，中文界面中显示为“超级马里奥语”；Monica Plus 支付页和支持作者页新增 Liberapay 欧元（EUR）支持入口。
- **验证器与卡片间距**：列表卡片边缘间距统一为 8dp。磁贴保留 313 的紧凑外观和等高外框，无账号不留空行；当前验证码完整显示，空间紧张时缩小或隐藏下一组码，正常列表保留 Next。收紧密码分组及组内留白，保留收藏、封面按钮原有尺寸（#139）。
- **新建体验**：密码、银行卡、证件和笔记页面复用安全组件并精简重复过渡，减少打开时的停顿。生成器打开的编辑页使用独立窗口，保存按钮始终可达。
- **二维码编辑**：二维码表单不再显示“密码登录／第三方登录”，避免误切换为密码条目；扫描、内容输入、保存和顶部类型菜单保持原流程。
- **返回与显示**：修复 MDBX 详情返回列表时短暂显示空数据库的情况，以及卡包堆叠详情返回收起后位置偏移的问题。
- **SSH 数据完整性**：保留 MDBX / Rust MDBX2 导入、编辑和完整导出中的密钥材料、扩展字段及私钥换行，完善 SSH / GPG 跨端格式约定。
- **KeePass 数据可见性**：兼容 KeePass 原生 TimeOtp/HmacOtp 的文本、Hex、Base32、Base64 密钥，以及 KeeOtp、Tray TOTP 和 Steam 字段；保留未知或不完整类型、仅有自定义字段及空值的条目。修复普通登录编辑后被误标为模板而漏显的问题；数据库计数统计原生条目，普通 Trash 同名文件夹不再误判为回收站。
- **KeePass 写入完整性**：按数据库和条目 UUID 区分验证码与同名登录；编辑验证码保留账号密码及第三方字段。修复 XML 解析裁掉字段名和值的空白、分段文本被截断的问题，保留原始大小写、空值和保护状态；完整替换遇到远端新增字段时提示冲突。
- **KeePass 原生管理**：详情支持实时验证码及保存后递增的 HOTP，编辑页增加标签与有效期；字段、图标、属性和待添加附件一次保存，避免附件或图标错误造成部分保存。详情及时刷新编辑和历史还原结果，显示解析后的字段引用，保存时保留原始引用。
- **KeePass 文件夹**：修复通行密钥引起的编码文件夹名重复显示，同一路径统一名称与计数。
- **密码库预览**：密码条目就绪后先显示概览，其他类型在后台解析并补全统计与推荐；大规模概览使用 Rust 批量聚合，小规模沿用 Kotlin，减少 JNI 开销。
- **长文案排版**：KeePass WebDAV 浏览器和云备份按钮的文字换行后保持居中（#140）。
- **Steam 令牌卡片**：Steam 页面使用独立的卡片垂直内边距和区块间距，让令牌内容与个人资料背景同时可见；验证器页面继续使用现有紧凑磁贴尺寸。

## English

### Summary

- Speed up Monica Keyboard loading/search, add optional number-key shuffle and hidden previews, and refine key geometry.

- Add an AI API Key type with provider, website, secret, request URL and notes.

- Add GPG keys and generator action menus.
- Add Italian and a Liberapay support option.
- Show password field values as QR / Code 128 barcodes and choose Emoji, installed app, or icon-pack artwork for password entries.
- Use Monica's color app icon and name in passkey save and sign-in selectors.
- Retire MDBX1 ordinary use and offer MDBX2 upgrades while preserving originals.
- Fix MDBX2 incremental sync and compatible-vault writes; reduce WebDAV requests.
- Fix missing KeePass entries and OTP; add native OTP, tags and expiration management.
- Improve entry creation, authenticator layouts and vault overview performance.
- Give Steam token cards their own content spacing so profile backgrounds remain visible; keep authenticator tiles unchanged.
- Fix QR editing, SSH data preservation, and database and card-wallet navigation.

### Details

- **Keyboard loading and search:** Read a lightweight database projection, prepare Chinese sort keys and letter navigation once, and reuse the unlocked snapshot. Use the existing Rust batch search for large lists with a Kotlin fallback. Coalesce duplicate window loads and keep database-source labels out of content searches. Discard results from superseded queries or locked sessions.
- **PIN and keyboard layout:** Add independent, default-off number-key shuffle and hidden-preview options to Autofill settings, synchronized with the separate keyboard process. Shuffle when the number pad opens, keep positions stable while typing, and select the number pad for numeric fields. Use equal letter widths, a centered home row, symmetric Shift/Delete, capsule Enter/mode keys and equal-height number keys, with narrow-screen and landscape layouts. Resolve password-entry OTP codes at fill time instead of using an expired cached code.

- **AI API Keys:** Add API Key to the password creation type menu for official, self-hosted and proxy providers. Use the provider name as the entry title and store the website and API request URL separately, with support for local vaults, MDBX2, KeePass and Bitwarden. Store secrets encrypted, hide them by default, and support viewing, editing and copying. Clear editor drafts on lock and exclude these keys from login autofill.

- **GPG keys:** Generate or import keys from the entry editor and generator, store them encrypted in the vault, export public/private keys, copy fingerprints, and optionally protect private keys with a passphrase.
- **Generator menus:** Tap password, word, passphrase or PIN results to copy them or create username/password entries. SSH results offer separate public-key, fingerprint and private-key actions; GPG results support copying, exporting and creating entries.
- **Field barcodes (#141):** Switch between QR and Code 128 from the password detail field menu. QR supports Unicode text; Code 128 accepts up to 80 printable ASCII characters for gift cards and membership numbers. Unsupported content shows an error, and barcodes that need a wider screen prompt rotation instead of shrinking into unreadable bars.
- **Password Emoji icons (#142):** Add quick picks and single-Emoji input, including ZWJ sequences, flags and skin-tone modifiers. Icons display consistently in the editor, lists, details, autofill, security analysis, and linked-password previews in the passkey list. Icon dimensions remain stable with larger system fonts.
- **Installed icons (#142):** Search and choose password icons from installed applications and compatible icon packs. Monica keeps a local copy so the selected icon remains available after its source is uninstalled. Searches and unsaved icon choices survive screen rotation.
- **System passkey identity:** Explicitly provide Monica's color app icon for save destinations and sign-in entries, replacing a generic key that could disappear against light backgrounds. Use the Monica Pass provider name to distinguish it from other password managers.
- **MDBX1 retirement and upgrade:** Disable MDBX1 creation and ordinary use. Keep existing databases visible as unavailable with an MDBX2 upgrade action. Verify the new vault before registering it and preserve the original file. Remote legacy upgrades convert the copy saved on this device into a new local MDBX2 vault; remote changes not yet downloaded are excluded and synchronization must be configured separately.
- **MDBX2 incremental sync:** Resolve out-of-order segment dependencies instead of aborting on missing parent commits, and reuse downloaded segments within the same synchronization. Recover previously published same-device history after reopening a bootstrap. Retain paged upload resumes and reduce repeated directory and attachment checks.
- **WebDAV sync:** Reuse confirmed remote directories and reduce path probes and listings. Apply backoff to 503 responses even without Retry-After to avoid repeated requests during service overload.
- **Compatible vault writes:** Initialize a missing Android root collection in the same transaction as the first write to a valid CLI-created MDBX2 vault, preserving existing collections and content.
- **Large password reads:** Use consistent snapshots across CursorWindow refills during concurrent synchronization or deletion. Retired MDBX1 caches no longer interfere with autofill saves or import duplicate detection.
- **Language and support:** Add complete Italian localization, playfully named “Super Mario language” in the Chinese interface, and a Liberapay option in euros (EUR) on the Monica Plus payment and Support Author pages.
- **Authenticator and card spacing:** Use an actual 8dp gap between list cards. Tiles retain the compact 313 appearance and equal outer heights without empty account rows. Current codes remain complete; crowded tiles shrink or hide the next-code preview, while regular lists retain Next. Reduce grouped-password padding while preserving favorite and cover button sizes (#139).
- **Entry creation:** Reuse security components and remove duplicate transitions when opening password, bank-card, document and note editors. Editors opened from the generator use separate windows so Save remains accessible.
- **QR editing:** Remove password/third-party login controls from the QR form to prevent accidental conversion into a password entry. Scanning, content editing, saving and the top type menu retain their existing flow.
- **Navigation and display:** Prevent a false empty-database state when leaving MDBX details, and keep card stacks in their original position after returning from details and collapsing them.
- **SSH data preservation:** Retain key material, extension fields and private-key line endings across MDBX / Rust MDBX2 imports, edits and full exports, with documented SSH / GPG interchange formats.
- **KeePass data visibility:** Read native TimeOtp/HmacOtp UTF-8, Hex, Base32 and Base64 secrets, plus KeeOtp, Tray TOTP and Steam fields. Keep entries with unknown or incomplete type metadata, custom-only fields and empty values accessible. Prevent ordinary logins from becoming hidden templates after editing. Count native entries and recognize recycle bins by metadata rather than ordinary Trash folder names.
- **KeePass write preservation:** Distinguish authenticators and same-name logins by database and entry UUID. Preserve login credentials and third-party fields when editing OTP. Fix XML whitespace trimming and fragmented text truncation; retain exact field names, empty values and protection, and reject full replacements that would erase remotely added fields.
- **KeePass native management:** Show live OTP and persist HOTP counter advances; edit entry tags and expiration. Save fields, icons, properties and pending attachments together. Refresh details after edits and history restoration, display resolved references, and preserve their raw expressions when saving.
- **KeePass folders:** Correct duplicate encoded folder names introduced by passkeys, using consistent names and counts for the same path.
- **Vault overview:** Display password entries as soon as they are ready, then fill in other types, counts and recommendations in the background. Use Rust batch aggregation for large overviews and Kotlin for smaller ones to avoid JNI overhead.
- **Long labels:** Keep wrapped button labels centered in the KeePass WebDAV browser and cloud backup pages (#140).
- **Steam token cards:** Use independent vertical card padding and section spacing on the Steam page so token content and profile backgrounds remain visible; keep the authenticator page on its existing compact tile dimensions.

# Monica for Android 1.0.323

> 未发布 / Unreleased

## 中文

- KDBX 文件管理新增“保存数据”“合并数据”“重新加载数据”，移除单库菜单中多余的手动 Wi-Fi 同步。修复本地原库重新加载未生效、关闭回收站后旧回收条目混入普通列表，以及冷启动解锁后列表偶发空白的问题；前台检查外部文件变化并更新列表和状态，保留旧编辑冲突保护。合并按 UUID、修改/移动时间和删除记录处理，保留条目历史、附件与图标，写入前保存恢复副本。面包屑与状态栏随文字、按钮和任务状态平滑展开/收缩，下方列表连续移动。界面覆盖全部 13 种语言，保留跨库复制/移动规则。

- 物理 Passkey 管理、注册位置、PIN 操作、帮助及错误提示补齐全部 13 种语言，并修正繁体中文中混用简体的问题。

- Monica Plus 诚信付款解锁前新增确认弹窗，取消或关闭弹窗不解锁；正文与确认按钮覆盖应用全部 13 种语言。


- 安全存储启动增加 15 秒等待上限，异常或超时后保持锁定并提供重试；未退出的底层调用不会重复启动，迟到结果不会放行后台维护。读取失败保留密文、旧密钥包装与恢复材料，不自动重建丢失密钥；系统密钥永久丢失时仍需原密码及已有恢复材料。
- Passkey 管理页新增“物理密钥”FAB，密钥信息改为紧凑两列，PIN 操作分组显示；支持 USB/NFC FIDO2 信息、PIN 设置与更改、只读查看可发现凭据。Android 14 及以上注册到 Monica 时，可在保存位置选择已检测到的物理密钥；断开或取消不会自动改存软件库，也可通过系统入口注册或登录。标准 FIDO2 无法导入现有私钥，硬件 PIN 和凭据不保存到 Monica；本次通过合成协议与模拟器验证，尚未实体密钥实测。
- 支持在 Monica 密码列表、密码库、详情和编辑预览中显示已解锁 KDBX 的条目自定义图标，并显示原库分组图标；编辑保存保留图标 UUID、图片和历史。锁库时隐藏原库图标，缺失或损坏的图片回退到现有图标。
- 导出数据时，来源数据库名称右侧显示当前记录数（不含回收站）；空库显示 0 条，未解锁或暂时无法读取时显示相应状态。
- 附近设备同步适配手机热点：在系统设备发现或组播不可用时补充受限的本地在线通知、热点网关与定向广播发现，并使用实际回复地址重连；保留前台解锁、设备配对与加密验证。
- 修复 WebDAV 备份目录检查失败后仍尝试创建目录和上传的问题；连接超时、断线或服务器错误会保留原始失败原因并停止上传，只有确认目录不存在时才创建。
- 选中 KDBX 后，密码页和密码库保留 Monica 的列表、详情与编辑界面，底层直接读取和保存 KDBX 原库，保留分组、附件、自定义字段与历史；“全部数据库”继续使用总览索引。保存返回单库列表不再等待总览索引刷新，并减少未更改密码的重复加密。KDBX 编解码接入原生 Argon2/AES 密钥派生，保留原算法、参数和文件校验；不支持的参数回退原实现。保存时按原条目和关联副本查找，单条保存复用未变化条目的解析结果并跳过不存在的 OTP，减少无关读取与重复构建；存在 REF 引用或外部重新载入时重新计算，保留跨库副本、移动及失败处理规则。保存仍等待文件成功提交与校验。
- “页面调整自定义”新增“打开软件自动聚焦搜索框”开关，默认关闭。开启后，解锁进入主页面时自动展开搜索并显示键盘；切换页面或返回列表不会重复触发。
- Monica 内置键盘新增独立“按键震动”开关，位于自动填充设置，默认关闭。字母、数字、符号及功能键每按一次触发一次短震动，效果与验证器提示一致；保留原有数字键随机排序与隐藏预览设置。
- 旅行票证新增“扫描登机牌”：识别 IATA BCBP 的 PDF417、Aztec、二维码或 Data Matrix，自动填入乘客、订座记录、航班、航线、日期、座位和舱位；确认保存后，完整原码及码制随票证加密保存，详情可按原码制出示并放大。编辑行程不改原码，旧票证需重新扫描。会员码和登机码入口采用统一 M3E 大圆角操作区，相关界面支持全部 13 种语言。

## English

- KDBX file menus now offer Save data, Merge data and Reload data, replacing the redundant per-vault manual Wi-Fi sync action. Fixed local reloads, recycled entries appearing after recycling was disabled, and intermittent empty lists after cold-start unlocking. Visible vaults check external file changes and update the list/status while retaining stale-editor conflict protection. UUID-based merging reconciles modification/move times and deletion records, retains entry history, attachments and icons, and creates a recovery copy before writing. Breadcrumb and status bars smoothly expand/collapse as content changes, moving the list continuously. All 13 languages and existing cross-vault copy/move rules are retained.

- Hardware Passkey management, registration location, PIN actions, help and errors now cover all 13 app languages; corrected Simplified Chinese text in the Traditional Chinese resources.

- Monica Plus now asks for payment unlock confirmation in all 13 app languages; cancelling or dismissing the dialog leaves Plus locked.


- Secure-storage startup now limits waiting to 15 seconds and stays locked on errors or timeouts, with retry available. Outstanding provider calls are not duplicated, and late results cannot enable maintenance. Read failures preserve ciphertext, existing key wrappers and recovery material without generating replacement keys; permanently lost system keys still require the original password and existing recovery material.
- Added a physical-key FAB to Passkey management with compact two-column information and grouped PIN controls for USB/NFC FIDO2 keys and read-only credential browsing. On Android 14+, registration through Monica can select a detected physical key as the save location; disconnection or cancellation never falls back to software storage. System registration and sign-in remain available. Standard FIDO2 cannot import existing private keys; hardware PINs and credentials are not stored in Monica. Verified with synthetic peers and emulators; physical-key testing is pending.
- Monica password and vault lists, password details and editor previews now display custom entry icons from unlocked KDBX files, including group icons in vault lists. Saving preserves icon UUIDs, image data and history; locking hides original-vault images, and missing or damaged images fall back to existing icons.
- Export source database names now show their current record count, excluding trash. Empty databases show zero; locked or unreadable sources display their status.
- Nearby-device synchronization supports phone hotspots with bounded local presence, Wi-Fi gateway and directed-broadcast discovery when system discovery or multicast is unavailable. Reconnection uses reply source addresses and retains foreground unlock, pairing and encryption checks.
- WebDAV backups now stop when directory checks time out, disconnect or return a server error, preserving the original failure. A directory is created only when it is confirmed missing.
- Selecting a KDBX retains Monica’s password and library lists, detail pages and editing forms while reading and saving the original KDBX directly. Groups, attachments, custom fields and history are preserved. The all-databases overview retains its index; returning to a selected-vault list after saving no longer waits for overview indexing, and unchanged passwords avoid redundant encryption. KDBX encoding/decoding now uses native Argon2/AES key derivation with the original algorithms, parameters and file checks; unsupported parameters use the existing implementation. Saves now look up originals and linked replicas, reuse unchanged entry projections after an edit and skip absent OTP parsing. REF dependencies and externally reloaded data are rebuilt; cross-database replica, move and failure handling are retained. Saving still waits for a successful file commit and verification.
- Added “Focus search on startup” to Page adjustment customization, disabled by default. When enabled, search opens and the keyboard appears on the first main page after unlocking; changing pages or returning to a list does not trigger it again.
- Added a separate “Keypress vibration” switch for the Monica keyboard in Autofill settings, disabled by default. Letter, number, symbol and action keys produce one short vibration per press, matching authenticator feedback while preserving PIN shuffle and preview settings.
- Travel passes can scan IATA BCBP codes in PDF417, Aztec, QR or Data Matrix and fill passenger, booking, flight, route, date, seat and cabin details. Saving keeps the complete original code and format inside the encrypted pass; details can show and enlarge it in its original format. Editing the itinerary leaves the code unchanged; older passes need a new scan. Membership and boarding codes share M3E rounded actions, with all related text available in 13 languages.

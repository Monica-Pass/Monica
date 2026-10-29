# Monica for Android 1.0.323

> 未发布 / Unreleased

## 中文

- 安全存储启动增加 15 秒等待上限，异常或超时后保持锁定并提供重试；未退出的底层调用不会重复启动，迟到结果不会放行后台维护。读取失败保留密文、旧密钥包装与恢复材料，不自动重建丢失密钥；系统密钥永久丢失时仍需原密码及已有恢复材料。
- Passkey 管理页新增“物理密钥”FAB，支持 USB/NFC FIDO2 密钥信息、PIN 设置与更改、只读查看可发现凭据；Android 14 及以上可通过系统注册或登录请求在密钥中创建和使用通行密钥。标准 FIDO2 无法导入现有私钥，硬件 PIN 和凭据不保存到 Monica；本次通过合成协议与模拟器验证，尚未实体密钥实测。
- 支持在 Monica 密码列表、密码库、详情和编辑预览中显示已解锁 KDBX 的条目自定义图标，并显示原库分组图标；编辑保存保留图标 UUID、图片和历史。锁库时隐藏原库图标，缺失或损坏的图片回退到现有图标。
- 导出数据时，来源数据库名称右侧显示当前记录数（不含回收站）；空库显示 0 条，未解锁或暂时无法读取时显示相应状态。
- 附近设备同步适配手机热点：在系统设备发现或组播不可用时补充受限的本地在线通知、热点网关与定向广播发现，并使用实际回复地址重连；保留前台解锁、设备配对与加密验证。
- 修复 WebDAV 备份目录检查失败后仍尝试创建目录和上传的问题；连接超时、断线或服务器错误会保留原始失败原因并停止上传，只有确认目录不存在时才创建。
- 选中 KDBX 后，密码页和密码库保留 Monica 的列表、详情与编辑界面，底层直接读取和保存 KDBX 原库，保留分组、附件、自定义字段与历史；“全部数据库”继续使用总览索引。保存返回单库列表不再等待总览索引刷新，并减少未更改密码的重复加密。
- “页面调整自定义”新增“打开软件自动聚焦搜索框”开关，默认关闭。开启后，解锁进入主页面时自动展开搜索并显示键盘；切换页面或返回列表不会重复触发。
- Monica 内置键盘新增独立“按键震动”开关，位于自动填充设置，默认关闭。字母、数字、符号及功能键每按一次触发一次短震动，效果与验证器提示一致；保留原有数字键随机排序与隐藏预览设置。
- 旅行票证新增“扫描登机牌”：识别登机牌上符合 IATA 标准（BCBP）的 PDF417、Aztec、二维码或 Data Matrix 条码，自动填入乘客、订座记录、航班、出发地、目的地、日期、座位和舱位；条码原文不会保存。

## English

- Secure-storage startup now limits waiting to 15 seconds and stays locked on errors or timeouts, with retry available. Outstanding provider calls are not duplicated, and late results cannot enable maintenance. Read failures preserve ciphertext, existing key wrappers and recovery material without generating replacement keys; permanently lost system keys still require the original password and existing recovery material.
- Added a hardware-key FAB to Passkey management for USB/NFC FIDO2 device information, PIN setup/change and read-only discoverable-credential browsing. Android 14+ system registration and sign-in requests can create and use credentials on the key. Standard FIDO2 cannot import existing private keys; hardware PINs and credentials are not stored in Monica. Verified with synthetic protocol peers and emulators; physical-key testing is pending.
- Monica password and vault lists, password details and editor previews now display custom entry icons from unlocked KDBX files, including group icons in vault lists. Saving preserves icon UUIDs, image data and history; locking hides original-vault images, and missing or damaged images fall back to existing icons.
- Export source database names now show their current record count, excluding trash. Empty databases show zero; locked or unreadable sources display their status.
- Nearby-device synchronization supports phone hotspots with bounded local presence, Wi-Fi gateway and directed-broadcast discovery when system discovery or multicast is unavailable. Reconnection uses reply source addresses and retains foreground unlock, pairing and encryption checks.
- WebDAV backups now stop when directory checks time out, disconnect or return a server error, preserving the original failure. A directory is created only when it is confirmed missing.
- Selecting a KDBX retains Monica’s password and library lists, detail pages and editing forms while reading and saving the original KDBX directly. Groups, attachments, custom fields and history are preserved. The all-databases overview retains its index; returning to a selected-vault list after saving no longer waits for overview indexing, and unchanged passwords avoid redundant encryption.
- Added “Focus search on startup” to Page adjustment customization, disabled by default. When enabled, search opens and the keyboard appears on the first main page after unlocking; changing pages or returning to a list does not trigger it again.
- Added a separate “Keypress vibration” switch for the Monica keyboard in Autofill settings, disabled by default. Letter, number, symbol and action keys produce one short vibration per press, matching authenticator feedback while preserving PIN shuffle and preview settings.
- Travel passes can now scan a boarding pass. Monica reads IATA BCBP barcodes (PDF417, Aztec, QR or Data Matrix) and fills in the passenger, booking reference, flight, origin, destination, date, seat and cabin; the raw barcode is not stored.

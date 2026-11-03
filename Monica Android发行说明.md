# Monica Android 1.0.324

## 中文

- 修复局域网 IP 网站的自动填充地址识别，支持 IPv4、IPv6 及带端口地址；IP 按完整地址匹配，不再套用主域名或数字模糊匹配。仍只从系统报告的网站来源或浏览器地址栏获取地址。浏览器未提供表单或未触发系统请求时，仍无法自动填充。

- Android 8–9 为兼容保存图片保留旧版存储写入权限，系统会由此附带读取访问；权限页明确说明这项旧系统行为，现代 Android 选图只授予所选文件访问权。

- 精简权限并升级权限管理：移除电话状态、广泛相册读取与外部存储读取权限，照片通过系统选择器获取单个文件访问权。权限页按运行时授权、特殊访问与服务、自动授予与内部保护分组，覆盖安装包与依赖声明，展示用途、实际状态和设置入口；保留 NFC 硬件密钥与 Android 8–9 保存图片兼容，授权仍由用户按需操作。

- 概览文件夹改为只显示顶层，点击文件夹或数据库后逐层浏览；当前层的文件夹始终连续排列在条目上方，A–Z／Z–A 只调整文件夹块内的顺序。保留原列表布局偏好与返回位置；KDBX 同名分组按 UUID 和父组区分，浏览与排序不改写数据库。

- 修复导出页将已解锁 KDBX 误显示为“待解锁”、无法显示记录总数的问题；多个库切换后仍能显示已核验总数，保存后自动刷新，锁定或来源失效后撤销统计。统计不额外读取文件或解密字段。

- 新增葡萄牙语（巴西／葡萄牙），按地区使用自然的密码、文件和操作术语；Dock 与底部导航采用简短标签，技术名称、格式占位符及安全提示的完整条件保留。

- 修复 KDBX 相同受保护字段重复保存时误增历史，以及仅访问时间或使用次数不同被合并为冲突的问题；历史密码按修改时间倒序展示。保留密码相同但备注等内容不同的有效历史版本，删除仍对应原始版本。

- 修复 KDBX 多选密码堆叠、账号组重复保存及复制内容保存失败：同一文件的选择一次提交，保留原字段、保护位和附件；保存复制内容时，目标条目必须核对未变，目标被外部修改或锁定时仍拒绝保存。

- 新增系统密钥异常后的确认恢复：已有有效的本机恢复材料、且系统密钥服务正常时，可用原主密码恢复本机安全配置，再正常登录。创建新存储密钥前会弹窗二次确认；取消、返回或离开前台不会继续恢复。原配置、数据库和附件保留，超时或失败保持锁定；恢复后需重新开启生物识别解锁。
- 本机恢复材料由支持该机制的版本在设置或成功验证主密码时自动建立、维护，包含由主密码保护的恢复密钥与加密配置快照，无需提前手动导出。请保留应用数据；未生成或已丢失恢复材料，以及仅依赖永久丢失旧密钥的数据，无法通过此路径恢复。
- 已在 Android 模拟器验证真实系统密钥丢失、替换及确认恢复登录流程。真实 OMK 设备尚未验证，不保证所有模块或改机环境均可恢复。

- 升级已配对设备的局域网同步：双方均支持新协议、且都有可发送的本地快照时，按记录版本、别名和已验证附件协商增量，减少重复传输。首次配对、旧版本、仅接收场景及增量缓存达到限额时，沿原加密流程完整同步；省略未变化记录不代表删除，删除确认、冲突保留和同步方向规则继续生效。
- 附件改为分块读取、验证和加密写入，降低普通二进制附件的峰值内存；图标和图片等部分接口仍有整块读取限制。断线后可在前台尝试续传配对身份与完整密文摘要绑定的暂存，重新认证并校验前缀及最终摘要，应用成功后才确认完成。取消、锁定或离开前台清理本轮续传状态；进程重启后重新同步，并回收确认属于旧进程的同步缓存，正在使用或无法确认归属的目录保留。
- 新增临时热点与扫码接入入口，创建热点和加入网络均由用户主动操作，加入仍需系统确认；不支持或连接被系统中断时可手动联网后重新扫描。网络变化会唤醒同步检查，空闲检查间隔延长。热点邀请短期有效，锁定或后台关闭本应用创建的热点与连接请求。
- 新协议已进行原生 TCP 与 Android 模拟器回归；真实双手机热点、不同 ROM 的系统联网确认及大库性能仍需实机验证。

## English

- Fix LAN IP website address recovery for autofill, including IPv4, IPv6 and addresses with ports. IP matching uses the complete address instead of base-domain or numeric heuristics. Origins still come only from system-reported website context or the browser address bar. Autofill still requires the browser to expose the form and send a system request.

- Android 8–9 image saving retains legacy storage-write permission, from which those systems also derive read access. The permission page explains this older-system behavior; photo selection on modern Android uses selected-file grants.

- Reduced permissions and expanded permission management: removed phone-state and broad photo/storage-reading permissions; photos use selected-file access through the system picker. The page covers installed-package and dependency declarations, grouped into runtime permissions, special access/services, and automatic permissions/internal protections, with purposes, current status and settings links. NFC security keys and Android 8–9 image saving remain supported; requests stay user initiated.

- The folder overview now shows the top level. Open a folder or database to browse its children; all folders at the current level stay together above entries, with A–Z/Z–A ordering inside that block. Your saved list layout and back position are preserved. KDBX groups with identical names use their UUID and parent identity; browsing and sorting do not rewrite the database.

- Fixed missing export totals for already-unlocked KDBX files after switching databases. Verified totals remain available after decoded-cache eviction and refresh after saves; locking or source invalidation revokes them. Counting does not reread files or decrypt fields.

- Added Portuguese for Brazil and Portugal with regional terminology and concise Dock/navigation labels. Technical names, formatting placeholders and the full conditions of security warnings are retained.

- Fixed spurious KDBX history from unchanged protected-field saves and merge conflicts caused only by access time or usage count. Password history now shows newest modification times first. Valid versions with the same password but different notes or other content are retained, and deletion still targets the original version.

- Fixed KDBX stacking, account-group resaves and copied-content publication: each selected file is committed once, preserving original fields, protection flags and attachments. Copied content is published only after confirming its destination is unchanged; external changes and locking still block unsafe saves.

- Added confirmed recovery when device Keystore keys become unavailable. With valid local recovery material and a working system key service, use your original master password to restore secure settings and then sign in normally. A second dialog confirmation is required before new storage keys are created. Cancelling, going back or leaving the foreground stops recovery. Original settings, databases and attachments are retained; timeout or failure keeps the app locked. Biometric unlock must be enabled again after recovery.
- Supported versions automatically create and maintain local recovery material when you set or successfully verify your master password. It contains a password-protected recovery key and an encrypted settings snapshot; no manual export is required. Keep the app data. This route cannot recover data if the required material was never created or has been lost, or if the data depends solely on permanently lost old keys.
- Real key loss, replacement and confirmed recovery followed by normal login were tested on an Android emulator. Actual OMK devices remain unverified; recovery is not guaranteed for every module or modified system.

- Upgraded LAN sync for paired devices: when both support the new protocol and have an outgoing local snapshot, exact record versions, aliases and verified attachments determine the delta, reducing repeat transfers. Initial pairing, older versions, receive-only sessions and exhausted delta caches retain the original encrypted full sync. Omitted records are retained; deletion review, conflict preservation and direction policies remain in effect.
- Binary attachments now use chunked reads, validation and encrypted writes to reduce peak memory. Some image and icon APIs still require complete buffers. Interrupted foreground transfers can resume identity-bound encrypted partial files after fresh authentication and prefix/full-digest checks. Completion is acknowledged only after application succeeds. Cancellation, locking or leaving the foreground clears this session's resume state. Process restarts begin a new sync and reclaim recognized caches from exited processes; active or unrecognized paths are retained.
- Added temporary-hotspot and QR connection controls. Users explicitly create or join a network, with system confirmation still required. Unsupported or interrupted connections offer manual Wi-Fi setup and rescanning. Network events wake sync checks, with longer idle intervals. Invitations expire, and locking or backgrounding closes only the app's hotspot and connection request.
- Native TCP and Android emulator regressions cover the new protocol. Real two-phone hotspots, ROM-specific system confirmation and large-vault performance still need device validation.

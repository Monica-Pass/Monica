# Monica Android 1.0.331 · 千石抚子（SengokuNadeko）

发布日期 / Released: 2026-11-26

## 中文

- 安全存储故障页新增需原主密码和再次确认的独立恢复：有效恢复材料可在 Keystore 不可用时恢复原 MDK 与加密配置，原文件和 alias 保留。正常用户不自动切换后端；密码恢复模式暂不启用指纹或重置全部数据。修正带外层空白的旧密文迁移遗漏，以及恢复后附件缓存仍引用旧配置的问题。永久丢失且无独立封装的密钥内容仍无法保证恢复。

- 修复笔记编辑工具栏周围的大块黑底，改为悬浮在内容上；适配键盘与导航栏避让，末尾内容可滚动到工具栏上方。

- 修复 MDBX 自动填充保存页认证成功后仍无法保存，以及新增记录保存后无法填回的问题；保留原生记录身份和未知字段，取消、锁定或进入后台后撤销保存权限。WebDAV 减少上传成功后的重复查询，同步内共享限流重试额度，继续保留条件写入与冲突校验。

- 修复旧 ZIP／WebDAV 多账号项目只显示第一个用户名的问题；按账号保留密码与 OTP 对应关系，编辑保存沿用原记录 ID，兼容已有备份并保留未知字段；本地多账号统一事务保存，失败、取消或锁定时保留原数据。

- 多选菜单保留更明亮的主题底色与清晰图标，恢复紧凑布局和无独立背景的图标按钮；适配深浅、自定义及纯黑主题。窄屏可横滑操作区，数量与关闭按钮保持可见；笔记、通行密钥统一使用相同样式。

- 新增 KDBX「比较与合并」：从数据库内容管理的更多菜单进入，选择内部库或外部文件，复用当前有效认证，外部文件仍需解锁。M3E 面板显示位置与认证状态，密码区域平滑展开／收起。可比较条目、分组、历史和附件，逐项选择版本并另存为验证后的加密新库；保留两份原文件，结果不自动绑定同步。无法完整保留的内容会阻止输出，锁定或进入后台时撤销比较；同时完善 Auto-Type 规则和 KDBX 3.1 历史兼容。

- KeePass 打开／新建改为独立 M3E 表单页；KDBX／MDBX 磁贴支持长按拖动并保存显示顺序。解锁验证时保留禁用的空密码框，减少界面跳动。

- 开关与滑块增加轻触觉反馈。滑块采用圆润填充轨道与花形滑钮，拖动时随移动距离正反滚动并跨刻度轻震，快速拖动限制反馈频率；修复界面缩放滑钮跨整数刻度时看似不滚动的问题，角度连续过渡；DPI 继续松手后应用。界面缩放松手后平滑应用，调节面板保持稳定；共用设置卡片随内容与文字换行连续调整高度，后续内容同步移动，并遵循减少动画及系统动画设置。可在「设置 → 功能拓展 → 触觉反馈」选择 0 关闭、1 轻、2 强，默认 1；强度滑块使用与开关一致的卡片背景，调节时直接反馈。触觉反馈按应用内开关和强度独立请求，取消系统触觉开关的前置限制；修复宿主禁用反馈及部分设备丢弃滑块刻度效果的问题。截图保护卡片、整行与开关点击各反馈一次，加载、状态同步、禁用及取消按压不触发；生成器结果卡片随内容及收起状态平滑调整高度，减少动画时立即到位。

- 新增 العربية（阿拉伯语）及从右向左布局，适配返回箭头、滚动条、语言选择、输入法和窄屏导航；密码、验证码及英文字母键盘保留正确顺序。修复阿拉伯语环境下 OTP 生成本地数字而无法被网站识别的问题，并完善恢复确认弹窗的大字号显示。

- 新增「繁體中文（台灣）」、Bahasa Indonesia 和 Türkçe。台湾与香港分别显示，系统台湾地区设置自动匹配台湾语言包；印尼语兼容 Android 的 id／in 代码，导航标签适配窄屏和大字号。

- 补齐现有语言遗漏的新功能文案，修正残留英文、印尼语 MDBX 模式名和文言文技术标识，保留占位符、复数及删除／恢复／权限提示条件。中文界面的意大利语选项统一显示本国语名称 Italiano。修正多语言误译的 maFile、Steam、Proton Pass 和 Argon2id 名称，保留协议示例与 Auto-Type 标记。

- 修复自动填充关联应用漏推荐：认证或切换数据库后刷新当前来源的匹配记录，保留推荐顺序、网页来源限制及锁定校验。搜索支持已安装应用名称、多应用关联和 androidapp:// 关联，并忽略首尾空格。

- 修复 #189：编辑本地密码并改存 Bitwarden 时不再误报归属校验失败；保留记录 ID、历史与字段，保存失败或锁定时回滚，普通编辑继续防止意外变更归属。

- 点击密码详情中无法读取的密码，可查看本次异常分类和对应建议。按实际错误区分设备认证、密钥不可用、加密校验失败等情况；本地密码可提示尝试还原改动前的 OMK／Keybox 配置，不臆断原因或保证恢复。原记录保留，锁定或进入后台时关闭说明。

- 修复 MDK／C2／V2 密文外层空白导致的读取不一致，保留密码正文空白；不可读密文仍阻止导出并保留原记录。ZIP 备份错误指出出错项目（含历史记录），取消操作不再显示为失败。

- 新增「恢复保护」设置：使用当前主密码验证恢复材料，并提供加密备份入口；健康旧安装可补齐缺失材料，已有材料只读验证。密码登录后的可选后台检查失败不影响正常登录。安全存储故障页按材料状态提供恢复原密钥环境或独立备份的建议；恢复需原密码与再次确认，诊断不含敏感内容。

- 新增「设置 → 主密码与锁定 → 修复生物识别解锁」：用原主密码确认后创建并验证新绑定，保留原库及旧恢复材料，修复成功不会自动开启生物识别。修正「主密码解锁一次即可恢复」的提示，取消、后台、锁定或超时会撤销恢复／修复结果；生物识别后的主界面密钥读取改为有界后台处理，减少慢安全提供器造成的卡顿。仅由永久丢失密钥保护的内容仍需独立恢复材料，不能保证恢复。

- 「主密码与锁定」采用 M3E 连接卡片组；重做 Monica Plus 概览页，以紧凑分组说明免费、开源和自愿打赏，适配大字号与深浅主题。

## English

- Storage recovery now offers an explicitly confirmed, original-master-password fallback that restores the original MDK and encrypted configuration from valid recovery material even without Keystore. Original files and aliases are retained; normal users do not switch backends automatically. Biometrics and full data reset remain disabled in this mode. Fixed legacy migration omissions for whitespace-wrapped ciphertext and stale attachment configuration handles after recovery. Permanently lost keys without independent wrappers remain unrecoverable.

- Fixed the large background strip around the note editor toolbar. It now floats over content, avoids the keyboard and navigation bar, and lets trailing content scroll above it.

- Fixed MDBX autofill saves failing after successful authentication and newly saved records failing to fill back. Native record identities and unknown fields are retained; cancellation, locking or backgrounding revoke save access. WebDAV uploads avoid redundant post-upload queries and share a retry budget within each sync, preserving conditional writes and conflict checks.

- Fixed legacy ZIP/WebDAV projects showing only the first username. Preserve each account’s passwords and OTP, keep original record IDs on edit, and retain existing backups and unknown fields. Local account groups save in one transaction, preserving original data on failure, cancellation, or lock.

- Selection toolbars retain the lighter, theme-tinted surface without an outline, with compact spacing and unfilled icon buttons. Icon contrast adapts to light, dark, custom and OLED themes. On narrow screens, actions scroll while the count and close button stay visible; notes and passkeys use the same styling.

- Added KDBX Compare and merge in the database content manager menu. Choose internal databases or external files, reuse valid current authentication, and unlock externally selected files. The M3E picker shows locations and authentication status, with smoothly resizing password fields. Compare entries, groups, history and attachments, select versions, and save a verified encrypted database. Both originals are retained; the result is not linked to sync automatically. Incomplete content blocks output, while locking or backgrounding cancels comparison. Auto-Type rules and KDBX 3.1 history compatibility are also improved.

- KeePass open/create now use standalone M3E forms. KDBX/MDBX tiles support long-press reordering with saved display order. Keeping an empty disabled password field during unlock verification reduces layout jumps.

- Switches and sliders now give light haptic feedback. Sliders use filled rounded tracks and floral thumbs that roll with movement in either direction, with throttled ticks while dragging. Fixed the interface-scale thumb appearing stationary across integer steps by interpolating its rotation. DPI changes still apply on release. Interface scaling now transitions smoothly after release while its adjustment sheet stays stable. Shared setting cards resize continuously as content or text wrapping changes, moving following content with them and respecting reduced motion and system animation settings. Settings → Extensions → Haptic Feedback now offers 0 off, 1 light (default), 2 strong. The strength slider shares the switch card background and gives feedback as it is adjusted. Haptics use the app’s own toggle and strength without requiring system touch feedback to be enabled. Fixed disabled-host feedback and slider effects silently discarded by some devices. Screenshot protection cards, rows and switches each respond once; loading, state updates, disabled controls and cancelled presses stay silent. Generator result cards resize smoothly as their content or compact state changes, with immediate layout under reduced motion.

- Added العربية (Arabic) and right-to-left layouts for navigation, scrollbars, language selection, the input method and compact navigation labels. Passwords, OTP codes and Latin keyboard keys retain their intended order. Fixed OTP generation using localized digits in Arabic locales and improved large text in recovery confirmation dialogs.

- Added Traditional Chinese (Taiwan), Bahasa Indonesia and Türkçe. Taiwan and Hong Kong have separate choices, with automatic matching for Taiwan system locales. Indonesian supports Android’s id/in codes, and navigation labels fit narrow screens and large text.

- Completed missing feature translations and corrected residual English, Indonesian MDBX mode names and Classical Chinese technical labels. Formatting, plurals and deletion/recovery/authorization conditions are preserved. Chinese interfaces now display the Italian language option as Italiano. Corrected mistranslated maFile, Steam, Proton Pass and Argon2id names, preserving protocol examples and Auto-Type tokens.

- Fixed missing bound-app autofill recommendations: refresh matches after authentication or database changes while preserving recommendation order, web-origin restrictions and lock checks. Search supports installed app names, multiple app bindings and androidapp:// associations, and ignores surrounding spaces.

- Fixed #189: moving an edited local password to Bitwarden no longer fails the ownership check. Record IDs, history and fields are retained; failed or locked saves roll back, while ordinary edits still reject unexpected ownership changes.

- Tap an unreadable password in its details to see the observed error category and suggestions. Distinguish device authentication, unavailable keys and failed ciphertext authentication. Local entries may suggest restoring an earlier OMK/Keybox configuration without assuming a cause or guaranteeing recovery. Original records are retained, and locking or backgrounding dismisses the explanation.

- Fixed inconsistent MDK/C2/V2 reads caused by whitespace around ciphertext, preserving whitespace in password values. Unreadable ciphertext still blocks export without changing records. ZIP backup errors identify the affected item, including history; cancellation no longer appears as failure.

- Added Recovery protection settings to verify recovery material with the current master password and open encrypted backups. Healthy older installations can enroll missing material; existing material is verified without rewriting it. Optional background checks after password login do not block successful login. Storage failure guidance reflects the material’s status and suggests restoring the key environment or an independent backup. Recovery requires the original password and explicit confirmation; diagnostics exclude sensitive content.

- Added Settings → Master password and locking → Repair biometric unlock. Confirm with the original master password to create and verify a new binding while retaining the vault and previous recovery material. Repair does not enable biometrics automatically. Replaced the inaccurate promise that one password unlock repairs biometrics. Cancellation, backgrounding, locking or timeout revoke recovery/repair results; bounded background key reads after biometric authentication reduce stalls from slow security providers. Content protected only by a permanently lost key still needs independent recovery material and may be unrecoverable.

- Master Password & Locking now uses M3E connected cards. The redesigned Monica Plus overview uses compact groups to explain free use, open source and optional donations, with large-text and light/dark support.

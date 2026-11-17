# 未发布 / Unreleased

# Monica Android 1.0.328

## 中文

- 密码库概览搜索结果新增轻快的上滑淡入，首次就绪后约 220 毫秒落位；输入时不重复播放，支持减少动画和系统动画设置。关闭搜索或锁库立即隐藏结果。

- 普通密码编辑与新建现在直接提交到 MDBX 原生引擎，保留对象身份、未知字段与附件；账号组修改和删除使用同一次原生提交，继续保留历史、撤销、快照与增量同步。旧表单遇到外部修订会拒绝覆盖，保存后回读失败可安全重试；锁定或离开前台撤销编辑会话。

- 优化自动填充切换 KDBX、MDBX 密码库后的列表加载，减少逐条记录重复读取加密配置和查询系统状态；保留取消、锁定、发布前授权及填写时的原生修订校验。
- 修复密码编辑页尚未完成读取时保存可能新增同名条目的问题；完整载入原记录及关联内容后才允许保存，来源缺失或读取失败时保留原数据。
- HyperOS 兼容页新增配置诊断和修复后变化检测：区分服务声明、框架识别、三项系统配置与未知状态；仅在诊断页可见或重新打开时读取比较，不自动回写，沿用 Shizuku／root 授权、审阅和恢复流程。
- 内置默认关闭的实验性 HyperOS 模块，分别控制安全中心配置重置拦截、系统选择页、Google 凭据选择界面及跨设备兼容；需要兼容的 LSPosed API 102+，首版限 Android 14–16 的 Xiaomi／Redmi／POCO 主用户，Google 路径需有效系统组件。未识别的方法保留原行为，框架连接不代表 Hook 已生效。
- HyperOS 适配来源与致谢：[Howard20181/HyperPasskey](https://github.com/Howard20181/HyperPasskey)（GPL-3.0，部分内置 Hook 实现）、[dingwen07/CredentialProviderManager](https://github.com/dingwen07/CredentialProviderManager)（GPL-3.0，配置诊断设计参考）及 [cr-zhichen/password-manager-switch](https://github.com/cr-zhichen/password-manager-switch)（MIT，默认密码管理器配置逻辑）。
- 尚未经过 HyperOS 实体机验证。欢迎 HyperOS 用户通过 [GitHub issue](https://github.com/Monica-Pass/Monica/issues) 反馈 ROM 版本、使用的框架及复现步骤，请勿提供密码、私钥或完整密码库。
- 修复部分 Steam maFile 密钥含 `/` 时验证码显示与复制不一致的问题，完整保留密钥字符，保持原有导入和令牌权限。
- 修复导入 KDBX 后中文或含特殊字符的分组出现同名重复文件夹、数量与进入后内容不一致的问题；统一原生分组身份，保留合法同名组和原文件。
- 开发者设置新增 HyperOS 兼容入口，集中现有默认密码管理器工具、HyperOS1 保存浮窗兼容和应用内减少动画选项；沿用原有授权、确认与恢复流程。
- 自动填充选择器按所选 Bitwarden 账号或 KDBX 数据库准备凭据，避免读取无关的 KDBX 库。
- 自动填充新增直接读取 MDBX 原生登录及受支持的银行卡、证件和地址，并在填写前核验当前修订；原生记录不可读、类型未知或已归档时，不回退到本地镜像。
- MDBX 自动填充暂不支持绑定新网址；独立密码键盘尚未接入本轮原生 MDBX 读取。
- 优化自动填充选择器的本地来源加载：先恢复来源筛选，再准备所需密码和钱包内容，不再等待无关原生 KeePass 库；批量准备减少重复的有效性校验。
- 新增编辑器仅在打开对应页面时初始化，减少进入自动填充选择器时的额外准备。
- 修正选择器中新密码保存后的填充与返回衔接；切换来源、取消或锁定后，旧请求结果不再覆盖当前列表。
- 自动填充与输入法解锁在等待设置时保持界面响应；返回、后台或锁定后不再接收旧请求的迟到设置。
- Passkey 认证初次读取所选凭据时保持界面响应；取消、进入后台或锁定后丢弃迟到的读取结果。
- 物理密钥页面背景延伸至状态栏，标题和关闭按钮保留安全间距，避免被系统图标遮挡。
- 减少笔记列表搜索与界面更新时重复建立的分类和密码库信息订阅，保留内容更新与锁定行为。
- Bitwarden 自托管连接支持复用 Android 设置中安装的用户 CA；同一可信 CA 签发的有效续期证书无需重新导入，仍可使用已有的账号专用 PEM 和 mTLS 配置。
- Bitwarden 官方域名仅使用系统 CA；自托管的额外信任不传递到官方域名、外部附件下载或其他网络功能，保留域名与证书有效性校验。
- 修复“管理原始信息”中本地多层文件夹被平铺显示的问题，可逐层进入并返回上级；新建、重命名和移动正确保留子目录关系。

## English

- Vault overview search results now slide up and fade in over about 220 ms when first ready. Typing does not replay the entrance; reduced motion and system animation settings are respected, and closing search or locking removes results immediately.

- Ordinary password creation and editing now commit directly through the native MDBX engine, preserving object identity, unknown fields and attachments. Credential-group edits and removals share one native commit with history, revert, snapshots and incremental sync retained. Stale forms reject intervening revisions, failed post-commit reads can be retried safely, and locking or leaving the foreground revokes the editor session.

- Faster autofill list loading when switching KDBX or MDBX vaults by removing repeated per-record secure-settings and system checks. Cancellation, locking, authorization before publication and native revision checks when filling remain enforced.
- Fixed duplicate entries when saving a password before the editor finished loading. Saving now waits for the original record and related content; missing sources or failed reads leave existing data intact.
- HyperOS compatibility adds configuration diagnostics and post-repair change detection, distinguishing service declarations, framework recognition, three secure settings and unknown states. Checks run only while the diagnostics page is visible or reopened; they never rewrite settings automatically and retain the Shizuku/root authorization, review and restore flows.
- Added an experimental, default-off HyperOS module with separate controls for Security Center reset interception, system selection pages, the Google credential chooser and cross-device compatibility. Requires compatible LSPosed API 102+; initially limited to Android 14–16 Xiaomi/Redmi/POCO owner users, with valid system components required for Google routes. Unrecognized methods retain original behavior; framework connection does not prove hooks work.
- HyperOS references and acknowledgments: [Howard20181/HyperPasskey](https://github.com/Howard20181/HyperPasskey) (GPL-3.0, parts of the built-in hooks), [dingwen07/CredentialProviderManager](https://github.com/dingwen07/CredentialProviderManager) (GPL-3.0, configuration diagnostics design), and [cr-zhichen/password-manager-switch](https://github.com/cr-zhichen/password-manager-switch) (MIT, default password manager configuration logic).
- Not tested on physical HyperOS devices. HyperOS users are welcome to submit a [GitHub issue](https://github.com/Monica-Pass/Monica/issues) with their ROM version, framework and reproduction steps; do not include passwords, private keys or complete vaults.
- Fixed Steam code display/copy mismatches for maFile keys containing `/`; preserve the full key and existing import and token permissions.
- Fixed duplicate folders and count/navigation mismatches after importing KDBX groups with non-ASCII or special-character names. Native group identities remain distinct, preserving legitimate same-named groups and the original file.
- Developer settings now groups HyperOS compatibility options: the existing default password manager tool, HyperOS1 save overlay workaround and reduced in-app animations, preserving their authorization, review and restore flows.
- The autofill picker prepares credentials for the selected Bitwarden account or KDBX database, avoiding unrelated KDBX vault reads.
- Autofill now reads native MDBX logins and supported cards, documents and addresses directly, checking their current revision before filling. Unreadable, unknown-type or archived native records never fall back to local mirrors.
- MDBX autofill does not yet support binding new URLs; the separate password keyboard is outside this round of native MDBX integration.
- Improved Local loading in the autofill picker: restore the source filter before preparing the required passwords and wallet items, skip unrelated native KeePass vaults, and reduce repeated validity checks through batch preparation.
- Initialize each add-item editor only when its page opens, reducing work when entering the autofill picker.
- Fixed the handoff from saving a new password to autofill and returning to the list; stale requests cannot replace the current list after source changes, cancellation or locking.
- Autofill and keyboard unlock stay responsive while settings load; cancelled, backgrounded or locked requests reject late settings.
- Passkey authentication stays responsive during the initial selected-credential lookup and discards late results after cancellation, backgrounding or locking.
- The hardware security key page now draws behind the status bar while keeping its title and close button clear of system icons.
- Reduced repeated category and vault subscriptions during note searches and UI updates, while preserving content updates and locking behavior.
- Bitwarden self-hosted connections can reuse user CAs installed in Android settings. Valid certificate renewals under the same trusted CA need no reimport; existing account-specific PEM and mTLS settings remain available.
- Official Bitwarden domains use system CAs only. Extra self-hosted trust does not extend to official hosts, foreign attachment downloads or other network features; hostname and certificate validity checks remain enforced.
- Fixed flattened local folder paths in Manage original information. Browse folders level by level, return to parents, and preserve descendant paths when creating, renaming or moving local folders.

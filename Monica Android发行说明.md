# 未发布 / Unreleased

# Monica Android 1.0.326

## 中文

- 修复旧版 Vaultwarden 的对象形式数据被误判为不支持，导致整批项目同步失败；兼容官方 Bitwarden 的字符串形式及新版 Vaultwarden 的普通字段格式，保留内容一致性与上传确认校验。
- 修复新建 Send 返回时重复回退导致黑屏；返回、保存完成、锁定与后台退出统一清理，避免弹出下层页面。
- 同步问题弹窗明确跟随防截屏设置，并保留强制保护；同步诊断补充不含项目内容的格式失败类别统计。
- 修复原生 KeePass 项目在自动填充列表、搜索和文件夹筛选中缺失的问题（#179）；空文件夹也可查看，文件夹名称正确显示空格和中文。
- 修复原生账号、密码被误判为 Monica 密文的问题；系统建议、详情复制和智能复制使用原生数据，并在操作时重新核对授权、锁库和文件修订。
- 修复自动填充选择器快速切换屏幕方向后意外关闭的问题，重建时重新核验当前授权。
- 支持本地 KeePass 库“填充并绑定”，保留其他字段、保护状态、历史与附件。只读库不能绑定；远端库或含引用的绑定字段请通过原生编辑器修改。
- 原生项目暂不启用填充后的可选 OTP 通知与自动复制；表单中的 OTP 字段填充不受此限制。

## English

- Fix older Vaultwarden object data being rejected as unsupported, which could fail an entire vault sync. Support official Bitwarden serialized data and newer Vaultwarden typed responses while preserving content consistency and upload confirmation checks.
- Fix a black screen when leaving a new Send caused by a second navigation pop. Back, completed saves, locking and background exit share cleanup without removing the underlying page.
- Make the sync issues sheet explicitly follow screenshot protection settings while retaining forced protection, and add format failure counts without item content to sync diagnostics.
- Fix missing native KeePass entries in autofill lists, search and folder filters (#179). Empty folders are included, and folder names display spaces and non-ASCII characters correctly.
- Fix native usernames and passwords being mistaken for Monica ciphertext. System suggestions, detail copying and Smart Copy use native data and recheck authorization, database locks and file revisions when used.
- Fix the autofill picker closing after a quick screen rotation, and recheck current authorization when it is recreated.
- Support “Fill and bind” for local KeePass files while preserving unrelated fields, protection flags, history and attachments. Read-only files cannot be bound; edit remote or referenced binding fields in the native editor.
- Optional post-fill OTP notifications and automatic OTP copying are not enabled for native entries. Filling an OTP form field remains available.

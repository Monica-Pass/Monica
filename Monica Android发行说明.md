# 未发布 / Unreleased

# Monica Android 1.0.326

## 中文

- 修复原生 KeePass 项目在自动填充列表、搜索和文件夹筛选中缺失的问题（#179）；空文件夹也可查看，文件夹名称正确显示空格和中文。
- 修复原生账号、密码被误判为 Monica 密文的问题；系统建议、详情复制和智能复制使用原生数据，并在操作时重新核对授权、锁库和文件修订。
- 修复自动填充选择器快速切换屏幕方向后意外关闭的问题，重建时重新核验当前授权。
- 支持本地 KeePass 库“填充并绑定”，保留其他字段、保护状态、历史与附件。只读库不能绑定；远端库或含引用的绑定字段请通过原生编辑器修改。
- 原生项目暂不启用填充后的可选 OTP 通知与自动复制；表单中的 OTP 字段填充不受此限制。

## English

- Fix missing native KeePass entries in autofill lists, search and folder filters (#179). Empty folders are included, and folder names display spaces and non-ASCII characters correctly.
- Fix native usernames and passwords being mistaken for Monica ciphertext. System suggestions, detail copying and Smart Copy use native data and recheck authorization, database locks and file revisions when used.
- Fix the autofill picker closing after a quick screen rotation, and recheck current authorization when it is recreated.
- Support “Fill and bind” for local KeePass files while preserving unrelated fields, protection flags, history and attachments. Read-only files cannot be bound; edit remote or referenced binding fields in the native editor.
- Optional post-fill OTP notifications and automatic OTP copying are not enabled for native entries. Filling an OTP form field remains available.

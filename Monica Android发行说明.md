# Monica Android 1.0.325

> 未发布 / Unreleased

## 中文

- 修复官方 Bitwarden 序列化传统条目数据被误判而无法显示的问题；编辑时同时更新序列化数据，保留未知字段和密码历史。

- 修复同步字段读取失败后被替换为空值的问题；登录、笔记、银行卡、身份和 SSH 条目在写入前校验密文，文件夹及 Send 读取失败时保留原数据并提示同步不完整。

- 修复空自定义字段和旧版主网址被服务器规范化后造成的虚假同步冲突；保留全部网址及未知内容。

- 修复官方 Bitwarden 传统条目带包装密钥标识时被遗漏的问题；核验原密钥，部分条目读取失败时明确提示同步未完成。

- 编辑 Bitwarden 登录、SSH、笔记、银行卡和身份条目时保留未知字段、原密码历史及归档状态；更新回包不完整时保留本地待同步修改。

- 修复 Bitwarden / Vaultwarden 创建、更新与复制条目的上传兼容性，以及编辑后多网址显示异常；保留网址校验信息、端口、路径和匹配规则。
- 修复编辑登录条目时丢失服务端密码历史的问题，保留原历史内容。
- 支持以原生 SSH 类型创建和编辑 Bitwarden SSH 密钥条目。
- 支持编辑已有文本和文件 Send，调整启停、独立到期／删除时间及访问密码，保留原文件内容。
- 增加可选的记住二步验证，以及账号注册、密码提示和设备列表入口。
- 加强同步修订冲突处理及账号安全状态变化后的重新认证，保留较新的本地修改。

## English

- Fixed official Bitwarden serialized legacy item data being incorrectly rejected during sync; edits update the serialized data while retaining unknown fields and password history.

- Fixed unreadable encrypted fields being replaced with empty values during sync; validate login, note, card, identity and SSH fields before saving, and retain existing data with an incomplete-sync message when folders or Sends cannot be read.

- Fixed false sync conflicts after server normalization of empty custom fields and the legacy primary URL, preserving every URL and unknown content.

- Fixed omitted legacy Bitwarden items carrying wrapping key identifiers, validating the original key and reporting incomplete sync when some items cannot be read.

- Editing Bitwarden logins, SSH keys, notes, cards and identities preserves unknown fields, existing history and archive state; incomplete update responses keep local edits pending.

- Fixed Bitwarden / Vaultwarden upload compatibility and multiple URL display after editing, preserving checksums, ports, paths and match rules.
- Preserved server password history when editing login items.
- Added native creation and editing of Bitwarden SSH key items.
- Added editing for existing text and file Sends, including enablement, separate expiration/deletion dates and access passwords while preserving file contents.
- Added optional remembered two-step verification, account registration, password hints and a device list.
- Strengthened revision conflict handling and reauthentication after account security changes, preserving newer local edits.

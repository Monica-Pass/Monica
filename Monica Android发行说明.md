# Monica for Android 1.0.318

> 未发布 / Unreleased

## 中文

- Passkey 和 Steam 支持选择数据库内的文件夹进行移动或复制，也可在当前数据库中整理归属；修复 Passkey 目标选择器未加载 MDBX 文件夹的问题。
- Passkey 复制保留源记录；操作前检查绑定、引用、私钥及目标限制，遇到无法处理的条目可取消或跳过后继续，并显示逐项结果。要求凭据 ID 唯一的 KeePass／MDBX 不支持同库复制，可改选其他数据库。
- Steam 复制生成独立条目，同库移动保留原记录；新增本地分类归属并迁移旧数据，修复 MDBX 后续编辑或刷新会话时回到根目录的问题。跨库移动在目标写入成功后才清理源记录。

- 修复通行密钥本地数据库页面反复出现同步状态：按当前数据库和文件夹范围触发刷新，跳过无目标、无法读取或已更新的 KeePass 索引；本地页面不再显示其他 Bitwarden／Vaultwarden 账户的同步进度。
- 缓存通行密钥的“密钥不可用”“跨端受限”等标记，返回列表或打开详情时立即复用检查结果；密钥删除、恢复、兼容性参数或解锁会话变化后重新检查，减少重复加载与标记闪烁。

- 修复通行密钥列表进入详情后返回会重置为“全部”的问题：保留所选数据库及文件夹，重新打开后恢复上次选择，避免页面重建时用默认值覆盖筛选设置。

- 修复旧 Passkey 在刷新或合并时可能改变已使用的备份资格标志：保留既有凭据的注册属性，冲突时保留原数据；新导入仍遵循来源标志。
- 修复修改 Bitwarden／Vaultwarden Passkey 时可能覆盖同一登录项中的其他密钥、密码、网址和自定义字段；更新前读取完整条目，使用版本校验并回读确认，失败或冲突时保留本地数据；保留用户主动移动文件夹和移回根目录的操作。
- 通行密钥列表在格式旁标记“仅本地”“跨端受限”或“密钥不可用”，详情解释限制原因；支持小屏及大字体换行。

- 修复仅绑定 Bitwarden／Vaultwarden 时密码库首页和搜索可能看不到已同步条目：未保存数据库范围时默认显示“全部”，旧版省略范围的设置同步采用该默认值；明确保存的本地库或具体数据库选择继续保留，锁定库仍不参与展示与搜索。

## English

- Passkey and Steam entries can now be moved or copied into folders, including organization within the current database. Fixed MDBX folders not loading in the Passkey destination picker.
- Passkey copies preserve the source. A preflight check identifies bound entries, references, unavailable keys and destination restrictions, with options to cancel or skip blocked entries and continue, followed by per-entry results. KeePass/MDBX databases requiring unique credential IDs cannot hold a second copy in the same database; choose another database instead.
- Steam copies create independent entries, while moves within a database retain the original record. Added local category assignments with migration of existing data, and fixed MDBX edits or session refreshes moving entries back to root. Cross-database moves clean up sources only after destination writes succeed.

- Fixed repeated sync activity on local Passkey pages. Refreshes follow the selected database and folder, skipping KeePass indexing with no target, inaccessible databases or an unchanged index. Local pages no longer display another Bitwarden/Vaultwarden account's sync progress.
- Cached Passkey compatibility labels, including Key unavailable and Transfer limited, so returning to the list or opening details reuses known results immediately. Key deletion or restoration, compatibility changes and lock-session changes trigger a new check, reducing repeated loading and badge flicker.

- Fixed the Passkey list resetting to All after returning from details. The selected database and folder now remain active and are restored on reopening, without page recreation overwriting saved filters with defaults.

- Fixed refresh and merge of legacy Passkeys changing previously used backup eligibility: preserve existing credential properties and retain local data on conflicts; new imports retain source flags.
- Fixed Bitwarden/Vaultwarden Passkey updates overwriting other credentials, passwords, URLs and custom fields in the same login. Updates now read the full item, check its revision and verify the saved content; failures and conflicts retain local data, while explicit folder moves and moves back to root remain supported.
- Added Local only, Transfer limited and Key unavailable labels beside Passkey formats, with explanations in details and wrapping for narrow screens and large text.

- Fixed synced entries being absent from the vault overview and search for Bitwarden/Vaultwarden users: use All when no database scope is saved, including older settings that omitted the scope. Explicitly saved local or individual database scopes are preserved, and locked vaults remain excluded from display and search.

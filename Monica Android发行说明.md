# 未发布 / Unreleased

# Monica Android 1.0.329 · 千石抚子（SengokuNadeko）

## 中文

- 设置中的「版本与链接」沿用检查更新弹窗的背景模糊，显示内部代号，并按屏宽和字体大小排列项目链接。

- 修复 Monica 本地多密码和批量创建失败后重试会多出项目的问题。每次纯本地创建的密码与字段一起保存；同时保存到两个本地文件夹时，各副本在重开编辑后仍保留自己的项目标识。已有项目不会自动去重。
- F-Droid 版更新检测与详情统一使用 F-Droid 官方接口和应用页面，按仓库推荐的已上架版本码判断更新；移除 GitHub 发行说明请求，接口失败显示检测失败，更新说明从 F-Droid 应用页面查看。普通版保留 GitHub 更新流程。
- 修复 Bitwarden 密码条目生成的验证码在没有文件夹时误显示于 Monica 本地筛选的问题；保留来源记录标识、修订及待同步状态，验证码沿原有 Bitwarden 账号与文件夹筛选显示。

## English

- The Version & Links dialog shares the update dialog’s background blur, labels the internal codename, and adapts its links to the screen width and font size.
- Retrying a failed multi-password or batch save in Monica Local no longer creates extra projects. Passwords and fields are saved together. Copies saved to two local folders keep their own project IDs when reopened for editing. Existing projects are not automatically deduplicated.
- F-Droid update checks and details now use only the official F-Droid package API and app page, comparing the recommended published version code. Removed GitHub release-note requests; API failures remain check failures and release details are available on the F-Droid app page. The ordinary edition keeps its GitHub update flow.
- Fixed password-derived Bitwarden authenticators appearing in the Monica Local filter when no folder is assigned. Preserve the source cipher identity, revision and pending-change state so authenticators remain in their Bitwarden account and folder filters.

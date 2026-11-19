# 未发布 / Unreleased

# Monica Android 1.0.329 · 千石抚子（SengokuNadeko）

## 中文

- 安全分析移除顶部细进度条，评分位置采用更大的变形图形：分析中显示省略号，完成后显示评分；窄屏和大字号下文字自动换到下方。

- 设置搜索与界面共用设置定义，新增设置自动加入搜索；补齐 HyperOS 兼容、默认填充库、导航和预览开关等遗漏项。搜索保留原有身份验证，条件项定位到父入口，不自动修改设置，也不收录用户数据。

- 补齐密码库页面的多密码项目显示：普通列表、层级列表及概览按项目展示，搜索仍覆盖每条密码，选择操作保留全部项目成员。

- 修复多密码项目在「始终展开」和「仅本地」视图中显示为多个同名卡片的问题。同一项目的多条密码显示为一张卡片，独立项目仍分别显示，已有数据保持不变。
- 在密码列表搜索时，没有匹配项的文件夹快捷入口会平滑收起，下方结果随之上移。清空搜索后，快捷入口和列表平滑回到原位。
- 设置中的「版本与链接」沿用检查更新弹窗的背景模糊，显示内部代号，并按屏宽和字体大小排列项目链接。

- 修复 Monica 本地多密码和批量创建失败后重试会多出项目的问题。每次纯本地创建的密码与字段一起保存；同时保存到两个本地文件夹时，各副本在重开编辑后仍保留自己的项目标识。已有项目不会自动去重。
- F-Droid 版更新检测与详情统一使用 F-Droid 官方接口和应用页面，按仓库推荐的已上架版本码判断更新；移除 GitHub 发行说明请求，接口失败显示检测失败，更新说明从 F-Droid 应用页面查看。普通版保留 GitHub 更新流程。
- 修复 Bitwarden 密码条目生成的验证码在没有文件夹时误显示于 Monica 本地筛选的问题；保留来源记录标识、修订及待同步状态，验证码沿原有 Bitwarden 账号与文件夹筛选显示。

## English

- Security Analysis replaces the thin top progress bar with a larger morphing indicator in the score card. It shows an ellipsis while checking and the score when finished, with stacked text on narrow screens or at large font sizes.

- Settings search shares definitions with settings controls, so new settings are included automatically. HyperOS, default fill sources, navigation and preview options are now searchable. Existing identity checks remain; conditional settings locate their parent entry, and search neither changes settings nor indexes user data.

- Multi-password projects now also keep one entry in vault lists and overview. Search covers every password, and selection includes all project members.

- Fixed multi-password projects appearing as duplicate cards in Always expand and Local only views. Each project keeps one card for all its passwords, while independent projects remain separate and stored data stays unchanged.
- During password list search, folder shortcuts without matches collapse smoothly and the results slide up. Clearing the search brings the shortcuts and list back into place.
- The Version & Links dialog shares the update dialog’s background blur, labels the internal codename, and adapts its links to the screen width and font size.
- Retrying a failed multi-password or batch save in Monica Local no longer creates extra projects. Passwords and fields are saved together. Copies saved to two local folders keep their own project IDs when reopened for editing. Existing projects are not automatically deduplicated.
- F-Droid update checks and details now use only the official F-Droid package API and app page, comparing the recommended published version code. Removed GitHub release-note requests; API failures remain check failures and release details are available on the F-Droid app page. The ordinary edition keeps its GitHub update flow.
- Fixed password-derived Bitwarden authenticators appearing in the Monica Local filter when no folder is assigned. Preserve the source cipher identity, revision and pending-change state so authenticators remain in their Bitwarden account and folder filters.

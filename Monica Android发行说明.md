# Monica Android 1.0.327

## 中文

- 内置图标库添加 Monica 本体图标，支持搜索并为条目选择（#180 / #181）。
- 改进版本更新弹窗，显示版本对比和可滚动的 Markdown 更新说明，支持跳过此版本、查看详情与稍后处理。
- 新增默认开启、可在设置中关闭的每日自动更新检查；仅在解锁后的前台主页面检查，跳过的版本不再自动提示，手动检查仍可查看。
- 更新弹窗在 Android 12 及以上、系统支持并启用跨窗口模糊时使用高斯背景模糊；其他情况保留背景暗化。
- 恢复验证码、银行卡、证件、API Token、Wi-Fi 等新建/编辑页的连续字段卡片组，保持独立操作按钮与错误提示完整显示。
- 更新 Android 构建工具与相关依赖，并保留 Android 8.0 最低支持版本。
- 修复退出或重置 Bitwarden 登录、关闭 Send 接收页面时，TLS 连接回收可能触发主线程网络异常的问题。
- 库移除确认准确说明文件处理范围：KeePass 移除注册后保留原文件，MDBX 本地库确认删除时移除目标本地文件。

## English

- Add the Monica app icon to the built-in icon library for search and entry selection (#180 / #181).
- Redesign the update dialog with version comparison, scrollable Markdown release notes, skip-version, details and later actions.
- Add daily automatic update checks, enabled by default and optional in settings, while the unlocked main page is in the foreground. Skipped versions stay hidden from automatic prompts and remain visible through manual checks.
- Apply Gaussian backdrop blur to the update dialog on Android 12 or later when cross-window blur is supported and enabled; retain background dimming otherwise.
- Restore connected field groups in authenticator, bank card, document, API token, Wi-Fi and related editors while keeping separate actions and error messages fully visible.
- Update Android build tools and related dependencies while retaining Android 8.0 as the minimum supported version.
- Fix main-thread network exceptions during TLS cleanup when leaving or resetting Bitwarden login or closing a Send receiver.
- Clarify file handling in vault removal confirmations: removing a KeePass registration retains the source file; confirming local MDBX vault deletion removes the selected local file.

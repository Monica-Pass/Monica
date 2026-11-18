# F-Droid 更新来源修正（未发布 1.0.329）

2026-11-18。普通版 versionCode12，F-Droid versionCode36。保留本轮之前的验证码来源修复与版本修改。

## 行为

当前代码此前已用官方 F-Droid API 判定更新，但新版可用时仍向 GitHub 请求对应标签的发行说明，“查看详情”也指向 GitHub。本次移除这两处依赖。

- F-Droid 通道只查询 `https://f-droid.org/api/v1/packages/takagi.ru.monica.fdroid`，使用 `application/json`，按 `suggestedVersionCode` 与已安装版本码比较。不会因 GitHub 已发布源码或列表存在更高的非推荐版本而提示更新。
- 更新与详情均打开 `https://f-droid.org/packages/takagi.ru.monica.fdroid/`。不从 GitHub 下载或安装普通版 APK。
- 官方包接口不提供发行说明，使用既有“暂无更新说明，请查看详情”文案。接口 HTTP/JSON 错误及不完整/歧义元数据仍为检测失败，不回退 GitHub。
- 普通版 GitHub 最新稳定版、ABI 选择、请求头、取消与下载签名保护沿用原流程。前台/锁定门控、自动检测开关、每日节流与跳过版本策略没有修改。

主应用的自动和手动检测入口均经现有 `AppUpdateHost` → `UpdateChecker`，F-Droid 包名后缀选取 F-Droid 通道。共享 backend、JVM 网络测试与现有 UI 测试已分别同步，两版三文件 SHA256 完全一致。

## 验证

- 官方接口实际 HTTP200：推荐 versionCode33 / 1.0.326；同时列出31 / 1.0.324与30 / 1.0.323。未发布329不是官方更新。此观察仅对应本次查询。
- 普通版 Debug 主/测试包与47项更新 JVM测试通过，153.246秒；F-Droid 对应构建与47项JVM通过，164.954秒。没有失败、错误或跳过。包括 MockWebServer 验证单个官方请求、官方详情链接、相同版本名按版本码比较、已安装相同/更高版本不更新、错误包/缺少或重复推荐版本/未知版本拒绝、HTTP及JSON失败无回退，以及普通版与取消/节流/下载回归。
- 复用公共 API32/x86_64 AVD，以独立的 F-Droid 测试 applicationId 在 user0 验证8项 Compose方法，全部通过；runner18.273秒，驱动23.862秒。覆盖无发行说明时的商店/详情按钮回调、无APK安装文案、大字号/小窗口、滚动/取消、锁定/后台拒绝迟到结果及系统模糊或降级分支。已查看实际合成 F-Droid 弹窗，截图只留本地忽略目录。
- F-Droid 静态元数据与6项发行脚本测试通过；两仓 `git diff --check` 通过。根目录未发布中英说明及FD36中英摘要同步。
- 设备检测前 AVD用户达到上限，新增secondary用户失败；设置临时max_users失败，production系统不支持adbd root。保留这些尝试，未删除原用户。改用独立包验证后仅卸载自建主包/测试包，user0包集合和原0/10/11/12/14用户完全相同，属性仍为空、adbd为shell2000。停止本任务启动的AVD，保留原配置、注册与数据盘。
- 独立包验收后重新打包默认F-Droid主/测试包（67.672秒），恢复正常包名的构建输出；四个APK的SHA256与首次通过JVM的构建完全一致，没有修改生产源码或重跑JVM。被测来源文件在构建/设备/收尾之间全部与冻结哈希一致。

## 范围

网络逻辑采用本机合成HTTP夹具，官方接口另作实际只读查询；设备为独立applicationId的Debug组件测试。未执行真实客户端商店安装升级、完整MainActivity更新导航、F-Droid服务器构建/签名、OEM真机或R8验证。没有操作用户密码库或真实账号，没有提交、推送、发布或交付APK。原始构建/JVM/设备记录和哈希留在本地任务目录。

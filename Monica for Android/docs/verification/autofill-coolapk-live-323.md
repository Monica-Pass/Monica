# 酷安官方在线登录表单实测（1.0.323）

日期：2026-10-31。接续 `autofill-paired-fields-323.md`，按用户要求直接使用酷安表单补验。

## 页面来源与测试方法

从官方酷安 16.7.0 APK 的公开 DEX 字符串核对到登录入口 `https://account.coolapk.com/auth/login?type=coolapk`。实际 HTTPS GET 返回 200，并跳转至 `https://account.coolapk.com/auth/loginByCoolapk`。

新增测试专用 `CoolapkLiveFormActivity`，用 Android WebView 的 `loadUrl()` 直接加载上述最终在线页面，包括原 HTML、CSS 和脚本。没有用本地 HTML 重画或替换输入框。测试在运行时确认 HTTPS 来源、最终路径、`loginInput` 的 text 类型和“用户名、邮箱或手机号”提示，以及 `passwordInput` 的 password 类型和“请输入密码”提示。若在线页面改变这些条件，用例会失败，不能把加载错误页算作通过。

普通版与 F-Droid 使用相同的测试源码。只增加 AndroidTest 的网络权限、独立测试 Activity 和四个显式开启的外网用例；生产解析器、加密、验证和响应构建逻辑没有新增修改。现有模拟表单继续保持无网络。远程页面不安装 JavaScriptInterface；观察器通过只读 `evaluateJavascript()` 比较原字段当前值与合成凭据，不赋值、不改提示、不改焦点或页面事件处理器。

流程为：建立临时加密测试条目 → 加载官方原页并确认两框为空 → 点击指定账号框或密码框并确认原 DOM 焦点 → 选择 Monica 系统候选一次 → 断言两框实际值均为预期。inline 候选必须位于真实输入法窗口。测试没有点击登录；同时观察 `/auth/` 的非 GET 请求计数，结果必须为 0。

## 结果

| 原页触发方式 | 普通版最终结果 | F-Droid 最终结果 |
| --- | --- | --- |
| 账号框 → 系统下拉候选 | 两项实际值正确 | 两项实际值正确 |
| 密码框 → 系统下拉候选 | 两项实际值正确 | 两项实际值正确 |
| 账号框 → 键盘 inline 候选 | 两项实际值正确 | 两项实际值正确 |
| 密码框 → 键盘 inline 候选 | 两项实际值正确 | 两项实际值正确 |

普通版最终 4 项通过，41.183 秒；F-Droid 最终 4 项通过，22.271 秒。各项报告 `live=READY user=OK password=OK`，且 `authWrites=0`。两版共 16 张原页填充前后截图已保存在本地，并核对了两版密码框触发 inline 的实际渲染。F-Droid 首轮有一次候选超时，见下方中间问题；最终结果不能覆盖或抹去首轮失败。

普通版测试包首次构建 3 分 55 秒，返回类型修正后重建 42 秒；F-Droid 测试包构建 1 分 16 秒，均成功。后两次测试包构建复用已有 BuildConfig（跳过时间戳生成任务）；没有修改生产配置或主程序源码。实际运行沿用已验证的普通版 `1.0.323-26103112-92` 与 F-Droid `1.0.323` 主 APK；安装的测试 APK 与本地产物 SHA-256 相同。

设备使用公共 `Monica_Issue136_API_32`，Android API32，系统 WebView `com.google.android.webview 155.0.8059.43`；inline 使用 Gboard，下拉使用已有不提供 inline 的测试输入法。

## 中间问题

首次普通版测试包构建成功，但设备测试在 JUnit 初始化时失败：四个新增测试函数通过 `runBlocking` 推导出了非 void 返回类型。将公共测试助手显式声明为 `Unit` 后重建，四项实际执行通过。该次失败没有进入登录页面，不能算作自动填充失败，也没有因此修改生产逻辑。

在线页面的加载等待上限为 45 秒，选择候选和检查填入值仍使用既有 20 秒上限。其余既有测试默认等待上限保持 20 秒。

F-Droid 首轮实际原页测试执行 4 项，3 项通过，`coolapkLiveInlineFromPassword` 在等待输入法候选时超时，总计 50.978 秒。失败快照确认原页已就绪、密码框是实际焦点、两框仍空、Gboard 可见，尚未选择候选，不能算作“选中后只填一个字段”。随后使用相同主 APK、相同测试包完整重跑，并增加宿主侧只读系统状态采集，4 项全部通过。采集轮观察到 Gboard 已连接，但没有再次捕获候选缺失；原因尚未确认，不宣称已消除所有输入法候选时序问题。没有以延长候选等待或去掉 inline 窗口断言来取得通过。

## 复验与证据

在专用非主 Android 用户上运行 `AutofillFlowInstrumentedTest` 的以下四个方法，必须显式传入 `autofillIsolatedUser=true` 与 `autofillCoolapkLive=true`：

- `coolapkLiveDropdownFromUsername`
- `coolapkLiveDropdownFromPassword`
- `coolapkLiveInlineFromUsername`
- `coolapkLiveInlineFromPassword`

外网用例默认不启用，避免普通回归依赖第三方网络。每项保留原页候选出现前和填充完成后的合成截图，以及只含 `OK/EMPTY/BAD` 状态的文本结果。截图只留本地忽略目录 `docs/design/coolapk-live-form-323/`，不加入 Git；网页来源快照仅留本地 `.codex-tmp/coolapk-live-form-323/`，不随测试打包。

各版 `autofill-coolapk-live-323-api32.txt` 保存最终四项完整输出；`autofill-coolapk-live-323-checks.txt` 保存字段结果、页面来源及源码／安装包 SHA-256。`autofill-coolapk-live-323-fdroid-first.txt` 在两版目录保存相同的 F-Droid 首轮失败日志，不计为普通版失败。初始化错误和完整系统状态只留在本地临时目录。

## 清理

本轮启动原公共 AVD，使用新建专用用户 `Monica_Coolapk_Live323`（id 13）。最终保存证据后清理该专用用户、切回原用户 0，并停止本轮启动的模拟器；原有用户 0、10、11、12 和公共 AVD 配置／数据盘保留。没有清理主用户数据、提交、推送、发布或交付安装包。

## 验证范围

这次验证直接覆盖酷安官方在线原表单在 Android 系统 WebView 中的实际双字段填充。酷安 APK 本身仍因此前记录的 ARM64 加固库加载错误无法在该 x86_64 模拟器运行；本次不把独立 WebView 原页通过写成酷安 App 内或用户真机已经验证。未使用真实账号，未验证登录成功、验证码流程或酷安 App 自定义 WebView 设置。

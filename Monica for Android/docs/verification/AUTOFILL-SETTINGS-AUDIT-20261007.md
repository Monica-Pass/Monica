# 自动填充设置与重复填充审计（2026-10-07）

本轮是行为审计：未改变产品实现、版本号或发行说明。临时测试探针与原始证据位于工作区 `.codex-tasks/autofill-audit-20261007/`。历史上已完成的安全修复保持原样。

## 结论

不能把 GitHub 清空后不再显示建议直接判定为 Android 的统一限制。公共 API 32 虚拟机中，原生输入框能反复填充；同一 WebView 的结果取决于清空途径。用真实键盘事件全选、删除，建议再次出现；用 Accessibility `ACTION_SET_TEXT("")` 清空，网页已变空，系统却仍保留 `AUTOFILLED`，因而拒绝再次显示建议。重新加载同一个 WebView 页面后恢复填充。

GitHub 真站未复现：在模拟器 Chrome 中打开 `https://github.com/login` 的操作被自动审批拒绝，返回 `blocked by policy`，无更多原因。没有通过其他路径绕过拦截。上述本地网页对照不能当作 GitHub、Chrome 或其他密码管理器的实测结果。

设置页存在已经确认的实现问题，不能宣称“所有设置项都有效”。

## 已确认的问题

| 设置 | 实际调用链与结果 | 建议 |
|---|---|---|
| 自动更新重复密码 | 设置页写入 DataStore，但两个 Android 版本的生产保存流程都没有读取 `isAutoUpdateDuplicatePasswordsEnabled`；不会因该开关自动更新已有密码 | 需要明确同网站、同账户、同目标库的匹配与冲突处理，再接入；避免只按用户名覆盖 |
| 保存成功通知 | `isShowSaveNotificationEnabled` 只在设置存取处出现，无生产消费者；开关不能控制保存结果通知 | 接入真实保存成功回调，或移除无法兑现的选项 |
| 关闭提示保存密码 | 服务在 `onSaveRequest()` 才检查开关；`FillResponseBuilderNg.attachSaveInfoIfNeeded()` 已无条件附带 SaveInfo。真实系统表单提交后仍弹出 Android 保存提示 | 构建响应时就决定是否附加 SaveInfo，同时保留保存回调的再次校验 |
| 前缀匹配、正则匹配 | 对话框列出全部枚举；`resolveUriStrategyConfig()` 对 STARTS_WITH / REGEX 明确回退 BASE_DOMAIN。用户选择的规则未执行 | 不应静默扩大匹配范围；只展示支持的策略或完成相应实现 |
| 默认 KeePass 数据库 | 延迟 Room 查询后打开真实设置页：仍存在的默认数据库 ID 被清空。两个版本均复现；Bitwarden 列表存在同形代码，尚未单独设备复现 | 将加载中与已加载的空列表区分，仅在加载完成后清理失效选择 |
| 完全匹配的描述 | 枚举和文案声称 URL 完全相同，但匹配器使用规范化 host，忽略 scheme/path/query | 把“主机名完全匹配”与“完整 URL 匹配”区分；系统未必提供完整 URL |

关键生产位置（两版相同）：

- `ui/screens/AutofillSettingsV2Screen.kt`：设置读写、初始数据库列表与系统入口。
- `autofill_ng/MonicaAutofillServiceNg.kt`：287 行遵循 autofill-off，590 行默认范围，600 行匹配策略，685 行内联与密码建议，887 行策略转换，1219 行保存回调。
- `autofill_ng/builder/FillResponseBuilderNg.kt:625`：SaveInfo 构建。
- `autofill_ng/AutofillSaveTransparentActivity.kt:118`：标题、应用、网站信息的消费者；这三个选项与上述两个无消费者的选项不同。

## 设置项逐项覆盖

“有消费点”只表示代码接通，不等于所有系统、键盘、浏览器条件均完成端到端验证。

| 项目 | 静态调用链 | 本轮验证边界 |
|---|---|---|
| 选择系统自动填充服务、刷新状态 | Android REQUEST_SET_AUTOFILL_SERVICE；AutofillServiceChecker | 测试中成功注册实际服务；未把各 OEM 设置入口视作已验证 |
| 默认密码管理器 / Shizuku | DefaultPasswordManagerSheet | 入口接通；本轮没有 Shizuku 授权/HyperOS 实机验证 |
| 自动填充保护 | AutofillProtectionActivity | 入口接通；本轮未覆盖后台保活策略 |
| 系统 Passkey 设置入口 | 打开 ACTION_SETTINGS | 实际是系统设置首页，不是保证直达 Passkey 管理页 |
| 键盘管理 | ACTION_INPUT_METHOD_SETTINGS | 系统入口接通 |
| PIN 随机排列、隐藏按键预览 | imeKeyboardOptions → MonicaInputMethodService | 开关读写检查；键盘行为 JVM 测试；仅作用于 Monica 键盘 |
| 启用自动填充服务 | isAutofillEnabled → onFillRequest 早退 | UI 存取检查；有实际消费者 |
| 主动填充通知 | isActiveFillNotificationEnabled → MonicaAccessibilityService.collectLatest | 有实际消费者；要求无障碍服务可用，单开此项不会替代授权 |
| 填充前验证、保持解锁两分钟 | AppSettings → session grants / response / callback | 本轮回归默认不保留授权的连续步骤；上轮完整安全结果另见 AUTOFILL-OPTIONS-20261007.md |
| 严格匹配、子域名匹配 | matcher.Config | 匹配器 JVM 测试；子域名开关与基础域名策略共同作用 |
| 域名匹配策略 | resolveUriStrategyConfig | 基础域、域、host 完全匹配有对应配置；前缀、正则为上述缺陷 |
| 遵循 autofill-off | 解析器初次及弱字段回退都带当前设置 | 字段检测策略 JVM 测试；系统完全不发送的字段不可能仅由此开关恢复 |
| 内联建议 | 是否读取 InlineSuggestionsRequest | 真正的系统内联选择回归；依赖 Android 版本与输入法支持 |
| 强密码建议 | processor / builder 的 passwordSuggestionEnabled | 开关存取、生产消费者检查；本轮未完成每种注册页的生成密码验证 |
| 提示保存密码 | onSaveRequest 才检查 | 已复现关闭后仍出现系统保存提示 |
| 自动更新重复密码、保存成功通知 | 无生产消费者 | 已确认未接入 |
| 智能标题、保存应用信息、保存网站信息 | 保存 Activity 构造 initialDraft | 开关存取和消费者检查；未把最终写入所有类型数据库视作已测 |
| OTP 通知 | AutofillOtpActions → notification service | 系统填充后通知开启/关闭对照；只在有可用 OTP 的相应填充路径触发 |
| OTP 时长 | notification session / service | 会话计时 JVM 测试；未逐一等待所有可选时长 |
| 自动复制 OTP | AutofillOtpActions → ClipboardManager | 开关存取和消费点检查；本轮未做不同系统后台剪贴板限制的矩阵 |
| 黑名单开关 / 应用列表 | isInBlacklist → 请求与保存的拦截 | 存取消费点检查；不会撤销已发送给 Android 的所有旧响应 |
| 不提示保存列表 | isSaveBlocked → buildSaveIntent | 保存回调会检查；与全局保存开关一样要留意更早的系统提示阶段 |
| 禁止填充字段列表 | 签名检查及缓存授权结果替换 | 真实 Activity 回归缓存响应拦截 |
| 默认来源 / 指定 KeePass / Bitwarden 库 | service.applyDefaultSourceFilter + picker 读取 | 有消费者；KeePass 已复现加载竞态，Bitwarden 同形代码待单独复现 |

## 重填机制与证据解释

1. 首次请求，Monica 返回候选，系统完成填充并记录字段的 `AUTOFILLED` 状态。
2. Android 收到当前字段值变化，且新值与已填充值不同，才会解除这一标记并再次显示建议。
3. 本地 WebView 使用 Accessibility 设置为空时，DOM 的 input/change 监听器已经看到空值，但 Android 会话仍为 `AUTOFILLED|AUTOFILLED_ONCE`。重新聚焦时 ViewState 记录 `Ignoring UI`，没有新的 Monica 请求。
4. 同一网页通过键盘删除则变成 `AUTOFILLED_ONCE|CHANGED`，自动建议与第二次填充均恢复。
5. 在失败路径重新加载同一 WebView，Monica 收到新请求并正常返回响应，填充成功。这排除了本对照中“Monica 填充一次后永久停止服务”的说法。

Android 官方源码（与测试 API 32 对应）：

- [ViewState.maybeCallOnFillReady](https://android.googlesource.com/platform/frameworks/base/+/android-12.1.0_r27/services/autofill/java/com/android/server/autofill/ViewState.java#206)：已填充且非手动请求时，不再显示 UI。
- [Session.updateViewStateAndUiOnValueChangedLocked](https://android.googlesource.com/platform/frameworks/base/+/android-12.1.0_r27/services/autofill/java/com/android/server/autofill/Session.java#2947)：收到聚焦字段的变更才重置 AUTOFILLED 状态。
- [AutofillManager.notifyValueChanged(View, int, AutofillValue)](https://android.googlesource.com/platform/frameworks/base/+/android-12.1.0_r27/core/java/android/view/autofill/AutofillManager.java#1388)：网页虚拟字段需要宿主传递变化。

手动长按“自动填充”在框架中属于不同请求路径，可作为用户侧排查方式，但本轮 WebView 的空输入框没有暴露该菜单，不能把它称作已验证的通用解决方法。刷新页面的恢复行为已在本地 WebView 验证。

## 测试环境与限制

复用 `Monica_Issue136_API_32`，Android API 32 x86_64、独立测试用户 10，WebView `153.0.8010.36`；Chrome 安装版本 `154.0.8037.126`（未进入真站实测）。使用合成凭据、测试条目与本地 HTML；未提交任何真实登录。

普通版与 F-Droid 的设置页、填充服务、响应构造器、保存 Activity 逐文件比对相同。测试探针只添加到 androidTest，结束后移回审计证据目录；本轮不实施产品修复，不修改历史发布内容。

## 测试结果

普通版 JVM：24/24（匹配器 10、字段检测策略 8、键盘行为 3、OTP 通知会话 3），无失败/错误/跳过。

普通版设备：原生重填、Accessibility 清空后的 WebView 重载恢复、关闭保存提示仍弹窗三项探针成功确认；键盘清空 + 内联 + OTP 开关 + 关闭两分钟保留 + 四个禁止字段回归，9/9；延迟加载默认 KeePass 库探针成功确认。

F-Droid 设备：上述三种清空/重填路径、保存提示、默认 KeePass 加载竞态，5/5。

普通版、F-Droid 各完成 18 个开关的真实点击、false→true→false 存取与 Activity 重建验证，各 1/1 测试通过。开启 OTP 通知会进入 Android 通知设置，测试按返回后继续。此处仅确认设置存取，不把无消费者的开关算作功能可用。诊断探针的“通过”表示确认了所述现象，其中包括确认缺陷；不代表产品已经修复。

构建：普通版、F-Droid 测试 APK 构建成功；F-Droid 也完成 `assembleDebug`。本轮没有改变生产代码，不以这些构建宣称功能修复。

保留的失败尝试：第一次在构建完成前运行得到 0 tests，未计入通过；探针编译遇到非公开 ACTION_AUTOFILL 后改用可观察恢复路径；JUnit 探针返回类型修正为 Unit；WebView 空框未出现长按 Autofill 菜单；原生提交按钮文案大小写修正；使用 Room 写事务没有阻塞读取，改为延迟查询线程后才成功复现默认库清空；OTP 开关会打开系统通知设置，UI 探针需返回应用再继续检查。这些不是已经修复的产品缺陷，也未被抹去。

测试用例源码、每轮日志、APK 哈希、系统状态恢复记录均保留在 `.codex-tasks/autofill-audit-20261007/`。正式记录只截取合成测试的字段状态和请求生命周期，不输出实际用户密码。


可复核证据：

- [普通版键盘、OTP、内联和字段屏蔽回归](autofill-audit-20261007/Main-keyboard-regression-device.log)
- [F-Droid 五项行为对照](autofill-audit-20261007/Fdroid-repro-final-device.log)
- [普通版开关存取](autofill-audit-20261007/Main-switches-final-device.log)、[F-Droid 开关存取](autofill-audit-20261007/Fdroid-switches-final-device.log)
- [设置消费者映射](autofill-audit-20261007/settings-consumers.json)、[JVM 结果](autofill-audit-20261007/jvm-results.json)
- [系统与服务事件摘要](autofill-audit-20261007/final-trace.txt)

本轮合计：24 个 JVM 检查；普通版 14 个独立设备用例、F-Droid 6 个独立设备用例完成验证。计数包含确认缺陷的诊断探针，不代表 20 个功能全部正常。早期整轮失败日志中，单个已完成用例按状态码核对；没有把整轮失败计作全部通过。

建议先修正已确认的设置问题；GitHub 重填还需要用户实际浏览器/版本和清空方式的现场复现。仅凭现有本地对照，不改 Monica 的会话生命周期或新增强制唤起行为。

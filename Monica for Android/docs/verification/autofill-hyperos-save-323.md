# HyperOS 1 系统保存无响应排查（2026-10-31）

> 历史排查记录：本文的“生产未变/与322一致”指修复前状态；当前默认关闭的兼容开关、实现及验证见 [后续兼容开关记录](autofill-hyperos-save-fix-323.md)。

## 反馈与结论范围

故障用户使用 HyperOS 1、已发布普通版 1.0.322，Android API 未知。反馈为系统“保存密码到 Monica”提示出现后点击“保存”没有反应，尚不清楚提示是否消失。截图中切换 Monica 输入法后可进入；“密码键盘”通过额外 sheet 再进入编辑页可用。其他 HyperOS 用户未复现。

用户进一步明确：只要把 Monica 键盘设为当前键盘，就能稳定进入保存页。源码检查未发现输入法服务调用 `setRequestSaveDataEnabled()`、切换到另一条保存 Activity 路由或主动解锁主会话。输入法与 Autofill/保存页属于同一应用，系统绑定输入法可能影响进程存活、优先级和后台启动资格。因此现阶段优先检查进程状态与 ROM 后台显示限制，而非把问题归咎于随机漏点；这种相关性仍不足以区分 ROM 拦截与冷进程初始化/时序问题。

后续反馈进一步指明组件 `takagi.ru.monica/takagi.ru.monica.ime.MonicaInputMethodService`，称该服务在运行时几乎稳定可以跳转。核对当前及 `V1.0.322` 清单：输入法明确声明 `android:process=":ime"`，系统 Autofill Service 与保存 Activity 没有独立进程声明。它们共用应用 UID，但不共用进程，不能把该现象简单等同于“输入法让主进程常驻”。保存入口未读取当前输入法设置或调用该输入法服务。

AOSP Android 14 的 `BackgroundActivityStartController.checkBackgroundActivityStart()` 有明确的当前 IME 放行分支：注释为 `IME should always be allowed to start activity, like IME settings.`，检查当前输入法窗口及其所有者，满足条件时返回 `BAL_ALLOW_ALLOWLISTED_COMPONENT`，原因是 `Active ime`；同一检查器还会考虑 UID 下其他进程的后台启动资格。这为“当前 Monica 输入法使同应用的页面能打开”提供了具体的平台机制，增强了后台启动放行差异这一假设，但服务存在本身不等于该分支必然命中，也不能将 AOSP 源码当成故障 HyperOS 的执行日志。需用故障机的 ActivityTaskManager/Autofill 系统日志核实原键盘失败时是否已交付 IntentSender、是否被拒绝启动；没有新增输入法监听依赖或启动绕过代码。

进一步的故障机对照可以始终使用原来的其他键盘：先打开并解锁 Monica，立刻返回同一表单测试；再单独对照“后台弹出界面”权限。前者成功只说明近期前台状态或初始化可能有关，后者稳定改变结果才增加对权限拦截的支持。不要同时调整输入法、电池、自启动和权限，以免无法区分原因。新增强密码注册反馈见 `autofill-generated-password-save-323.md`，尚不能视为同一个问题。

目前没有故障机现场日志，未确认根因或修复。保留直接进入 Monica 保存界面的产品行为，本轮没有修改生产保存逻辑、PendingIntent 权限、验证或锁定策略，没有添加中转 sheet。

## 已核对的源码证据

- `MonicaAutofillServiceNg.onSaveRequest()` 在 Android 9+ 使用 `PendingIntent.getActivity()` 和 `SaveCallback.onSuccess(intentSender)`，由系统 Autofill 流程启动保存页。仅 Android 8/8.1 分支直接调用 `startActivity()`。不能把 HyperOS 问题解释为 Monica 一律从后台直接启动 Activity。
- `MonicaAutofillServiceNg.kt` 与 `AutofillSaveTransparentActivity.kt` 相对 `V1.0.322` 均无差异；保存页本身是完整的 Monica 表单，名称中的 Transparent 不代表另有 sheet。
- Android 13 AOSP `SaveCallback` 文档明确支持借此打开认证/后续处理界面；`Session.onSaveRequestSuccess()` 通过目标应用的 Autofill client 执行 IntentSender。标准系统允许这一直接路径。
- Bitwarden 参考实现也使用同一 SaveCallback API，但面向 MainActivity，使用 NEW_TASK/CLEAR_TOP 和可变 PendingIntent。Monica 使用独立 singleTask 保存 Activity 和不可变 PendingIntent。差异本身不是 HyperOS 故障的证据；没有据此扩大 PendingIntent 可变性或盲目更换任务栈。
- 本机 API32 系统日志在直接保存时记录了 `Starting intent sender on save()`、保存 Activity 启动和显示；缺少显式 NEW_TASK 时系统补充该标志并成功打开。此结果不能代表所有厂商实现。

需要区分的三个阶段：系统未向 Monica 发出保存请求或未启动返回的 IntentSender；Monica 因缺失/冲突的密码、屏蔽目标等原因跳过请求；保存 Activity 启动后因认证取消、锁定变化或异常退出。输入法切换同时可能改变进程状态与捕获数据，不能据此确认其中某一种。

## 设备验证

复用公共 `Monica_Issue136_API_32`，本轮启动后创建专用访客 `Monica_HyperOS_Save_20261031`，只使用合成凭据。普通版安装版本为 `1.0.323-26103112-92`、targetSdk 34；F-Droid 使用现有 1.0.323。没有降级共享安装到 322。323 的解析器已有前序修复，因此不能把本轮结果冒充已发布 322 APK 或 HyperOS 真机验证。

测试夹具增加可选 `autofillSaveExternalIme=true`，在表单创建前选择原外部输入法，并核对其 UID 不属于 Monica、包名不属于测试夹具。默认输入法选择行为保留。早期在表单启动后切换输入法的方案已调整，避免与首次 inline 请求竞争；分步密码页等待候选时同时支持 Gboard 的 contentDescription，与既有 `fillFromSystem()` 一致。不据此认定所有候选超时的原因相同。

普通版和 F-Droid 独立冷启动检查均已确认：无 active instrumentation，Monica 进程在打开表单前不存在，不先打开 Monica 主界面，使用 Gboard 在独立原生夹具中输入合成注册数据并提交。系统保存提示出现后，一次“保存”直接打开 `AutofillSaveTransparentActivity`；锁定状态正确显示原主密码验证，验证后进入 Add Password 编辑页。没有点击编辑页保存，不向真实网站提交数据。首轮主密码输入使用突发 ADB 按键，检查发现 26 个字符仅输入 20 个；改为逐字符输入后验证通过，仅修正外部驱动。冷启动前取消本测试用户的 Autofill 服务绑定并终止其测试应用进程，核对进程不存在，再重新启用服务；没有终止主用户应用进程。

首次普通版 4 项回归有 1 项在填充候选阶段超时，系统日志确认 Autofill 服务请求超时并取消，尚未点击系统保存；其余分步保存与 2 项锁定保护通过。早期 Gboard 2 项对照中改密保存通过，账号分步场景在填充候选阶段超时。原始失败记录保留，不能把这些失败算作通过或当作 HyperOS 点击保存故障的复现。

较早一轮普通版默认输入法夹具 4 项通过（40.239s），Gboard 对照 2 项通过（50.992s）。结果见同目录文本验证记录；当前证据只覆盖标准 Android API32。

随后将 Gboard 与锁定保护一起运行时，F-Droid 首轮 4 项有 2 项失败（77.943s）：分步密码候选等待，以及验证后等待编辑页。补齐候选 contentDescription 后，普通版 4 项只剩验证后页面等待失败（67.658s）。单独复查后者时的原生截图显示 Gboard 的“Using Google Translate”首次提示遮住了 Monica 验证页，确认坐标点击受键盘移动影响，误触了键盘翻译入口。测试改为寻找“确认”的可点击祖先并执行 `ACTION_CLICK`，保留真实密码验证、主会话仍锁定、再次锁定销毁 Activity 及数据未改的断言；没有接受翻译提示或修改生产认证逻辑。这一截图不能用于解释此前所有超时，更不能映射到故障 HyperOS 用户。

最终夹具的普通版首轮 4 项中 3 项通过、1 项分步密码候选等待超时（61.866s）；同包复测仍为相同的 3 通过/1 超时（51.342s）。日志显示对应填充响应已经成功返回、inlineRequest=true，尚未点击保存，候选展示不稳定的根因仍未确认。F-Droid 最终 4 项通过（38.207s）。不能把较早的通过记录当作已解决这种候选问题；它与本报告的故障机点击系统保存无响应尚无已证实联系。

| 最终检查 | 普通版 | F-Droid |
| --- | --- | --- |
| AndroidTest APK 构建 | 通过，1m1s | 通过，35s |
| Gboard 改密表单提交、系统保存及数据库核对 | 通过 | 通过 |
| 锁定时取消验证不写入 | 通过 | 通过 |
| 独立保存验证不解锁主会话、再次锁定销毁并保留数据 | 通过 | 通过 |
| Gboard 分步密码候选与保存 | 候选等待超时，未到保存；最终两次相同 | 通过 |
| 无 instrumentation、进程冷启动、Gboard 手动填写后的系统保存 → 验证 → 编辑页 | 通过 | 通过 |

本轮只重建测试 APK，复用前序已构建安装的主 APK，没有新增主程序或 Release 构建。两版保存 Service、保存 Activity 和最终测试源码三文件哈希一致。早期失败、最终执行与外部冷启动结果保存在同目录 `autofill-hyperos-save-323-api32.txt`，构建/哈希/清理结果保存在 `autofill-hyperos-save-323-checks.txt`。

## 故障机下一步

1. 保持原来的非 Monica 输入法和同一登录表单，只对照一次 Monica 的“后台弹出界面/在后台显示界面”权限（若系统提供）。记录切换前后结果，再恢复原设置。这是定位测试，不是已经确认的修复；自启动、电池白名单与悬浮窗不是同一个权限。
2. 复现后立即打开 Monica → 设置 → 开发者设置 → 日志调试 → 查看日志 → 分享完整日志，保留失败时间。322 已有该入口，报告带完整构建号和 Android API，可先利用现有版本取证，不必先加 sheet。
3. 现有日志中的 `Skip save request: missing or conflicting password values` 能确认解析/取值拒绝；异常日志能辅助识别执行失败。但 322 没有覆盖“请求收到—回调交付—保存 Activity 创建/结束”的完整阶段日志，报告中没有相关行不等于请求没有到达。
4. 如现有报告不足，需要故障机通过 LogFox/ADB 提供同一时间段系统 ActivityTaskManager、AutofillSession/RemoteFillService、MonicaAutofill 与 AndroidRuntime 记录，检查启动拒绝、会话失效、Activity 是否启动及异常。分享前检查是否包含个人信息，不需要提供真实密码。

## 参考

- [Android SaveCallback API](https://developer.android.com/reference/android/service/autofill/SaveCallback#onSuccess(android.content.IntentSender))
- [AOSP Android 13 SaveCallback](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/core/java/android/service/autofill/SaveCallback.java)
- [AOSP Android 13 Autofill Session](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/autofill/java/com/android/server/autofill/Session.java)
- [AOSP Android 14 BackgroundActivityStartController](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/wm/BackgroundActivityStartController.java)

用户截图留在原位置，未复制入库。系统原始日志与外部驱动只留本地 `.codex-tmp/hyperos-save-323/`，不加入版本库。不提交、推送、发布或交付 APK。

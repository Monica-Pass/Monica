# 系统保存直接跳转兼容性修复尝试（2026-10-31，1.0.323 未发布）

> 后续反馈（2026-10-31）：故障用户已确认本记录的启动参数兼容开关仍然无效，填写后切到 Monica 输入法则可用。下文是旧实现的历史验证，不代表故障机已修复。新的独立悬浮面板方案见 `autofill-hyperos-save-sheet-323.md`。

## 范围与依据

HyperOS 1 用户在普通版 1.0.322 点击系统“保存密码到 Monica”没有反应，选择 Monica 输入法后近乎稳定能打开。故障机 Android API 和现场日志仍未知。前序调查在 `autofill-hyperos-save-323.md`；其中“未改生产代码”“与 322 一致”描述修复前状态，后续以本记录为准。生成强密码后没有保存提示的另一条反馈仍单独记录，不在本次增加自动保存。

IME 位于 `:ime` 进程，与保存服务/Activity 共用 UID，但不是同一个进程。AOSP 有当前 IME 的后台 Activity 启动放行分支，支持检查系统启动资格的方向；不能由此认定故障 HyperOS 必然命中该分支，也不能认定用户误操作。本次是兼容性尝试，尚未证明修复故障机。

用户补充截图进一步指出：单独启动输入法服务无效，必须设置为当前默认输入法。截图中的临时命令在 Monica 与百度小米版 `com.baidu.input_mi/.ImeService` 间切换。该补充削弱了“仅因进程存活即可解决”的解释，更支持检查当前 IME 身份对应的系统放行条件；截图中的 shell 命令只作为反馈证据，没有执行或写入应用。Gboard 模拟器测试不能代替百度小米版与 HyperOS 的组合实测。

## 实现

用户随后要求单独提供 HyperOS 1 兼容开关。两版自动填充设置在“启用自动保存”后新增该开关，默认 false，手动选择且可随时关闭，不根据不可靠的 ROM 名称推测自动启用。当前和旧版设置入口共用 DataStore 偏好；两条文案覆盖 13 种语言/14 个资源配置。本地 M3E 草图保存在忽略的 docs/design/hyperos-save-toggle-323，已检查实际中英文原生界面，标题和说明均能完整换行显示。

- API 28+ 继续经 `SaveCallback.onSuccess(IntentSender)` 交给系统，目标仍是不可导出的 `AutofillSaveTransparentActivity`。仅开关开启时显式设置 NEW_TASK/CLEAR_TOP，不依赖来源登录 Activity 的任务或 ROM 自动补标志。
- 开关关闭不添加任务标志或 creator opt-in。开关开启且 API 34+ 时使用公开 ActivityOptions creator 后台启动 opt-in，仅附于这次保存的 PendingIntent；不是新增全局权限，也不能强制越过 ROM 自身限制。API 26/27 保留原直接启动分支。
- 令牌保持显式目标和 IMMUTABLE，新增 ONE_SHOT；使用本地产生的随机 UUID 区分并发请求，密码/账号不参与令牌身份。接收方不能改目标/凭据或重放同一令牌。
- SaveCallback 首次终止调用前即标记完成；Binder 调用抛出异常时不再尝试第二次终止回调。错误 UI 使用已有通用资源文案，不显示异常原文。
- `MonicaAutofill` 的 `[SAVE]` 阶段日志覆盖收到请求、禁用/无数据、令牌就绪、系统交接、Activity 创建/恢复/结束、验证/编辑分支、保存成功与锁定/取消。记录随机关联 ID、PID、API、是否选择 Monica 输入法的布尔值，以及交接/页面阶段实际使用的兼容模式，以及固定异常类别；不读取凭据、站点、异常 message/toString/堆栈或当前其他键盘包名。日志失败不阻断保存或锁定关闭。
- `[SAVE]` 使用 Log.println 保留 Release Logcat；继续使用现有有界内存/异步持久化日志。`monicaImeSelected` 只代表选择状态，不代表服务当前运行；`*_READY` 代表 Compose 进入对应分支，不保证像素已绘出。
- 不增加中转 sheet、不强制更换键盘，不改主密码验证、主会话解锁状态、锁定代次、熄屏关闭、数据写入目标或加密/恢复机制。

## 验证

### 最终带开关构建与 API32

| 检查 | 普通版 | canonical F-Droid |
| --- | --- | --- |
| Debug 主程序、测试包、JVM 构建 | 成功，7m33s | 成功，7m13s |
| JVM：令牌/回调/默认偏好/日志、保存上下文和值策略 | 27/27 | 27/27 |
| API32 兼容开启，6 个保存用例 | 首轮5/6；失败项单独复测1/1，9.349s | 6/6，33.396s |
| API32 兼容关闭，同6个用例 | 6/6，33.354s | 6/6，30.707s |
| API32 真实设置开关点击与持久化，中文/英文 | 各1/1，2.907s/3.809s | 各1/1，3.964s/3.921s |
| API32 无 instrumentation、初始进程不存在、Gboard 冷流程 | 系统保存→身份验证→编辑页通过 | 系统保存→身份验证→编辑页通过 |

普通版为 1.0.323-26103112-94/code12，F-Droid 为1.0.323/code30。两版主程序均包含测试用 x86_64 ABI。JVM 在 API29/34 验证兼容分支、显式不可篡改目标/凭据、单次不可重放、并发请求隔离、关闭开关不加新参数及异常回调不重复终止。API32 的六个用例覆盖改密、跨步骤账号、锁定验证/取消不写入、再次锁定关闭，以及更新主账号/额外账号时保留备注与兄弟项。真实设置用例最终恢复原偏好；截图中的开启态不代表默认开启。

两版 API32 冷流程只点系统保存并完成原主密码验证，没有点击编辑页最终保存；真实数据库更新由框架用例验证。冷流程日志同一随机 UUID 能串联系统交接到编辑页，均为 monicaImeSelected=false、compatibilityMode=true；固定 SAVE 行已检查不含合成凭据。冷流程准备方法仅设置隔离用户偏好，不计作产品回归。模拟器全部使用本轮自有访客，不清主用户数据。

11个共享生产/测试文件逐字节相同，新增2键在14个资源配置中一致，并已从两版实际APK各核对28个值。保留两版资源中既有差异。F-Droid fastlane30 中文495字符、英文485字符。

### API35 与 Release

复用既有 Pixel_Fold_API_35（Android 15/API35，x86_64，16KB page size），未新建 AVD。普通版已在无 instrumentation、冷进程、Gboard 条件下由一次系统保存点击直接到达身份验证页；同 UUID 的 LAUNCH_READY/HANDOFF_SENT/ACTIVITY_CREATED/AUTH_READY 均确认 compatibilityMode=true、monicaImeSelected=false。API34+ creator opt-in 的真实运行分支已执行。

普通版 API35 首轮两个框架用例失败：系统 SaveUi 记录提示出现后 onCancel，测试在查找系统保存按钮处超时，未交接到生产保存回调；未确证取消的全部原因，不计作通过。独立冷流程首次为 NO_SAVE_DATA，之后直接读取自有合成表单确认输入丢失字符（新密码少一个字符），修正驱动为等待焦点稳定、逐字输入并在提交前核对新密码与确认值相同。下一次成功到达验证页，但旧驱动只寻找英文标题，设备实际为中文；改为识别中英文。

API35 后续主密码输入不通过：读取自有合成输入确认，在中文 Gboard 环境下外部驱动的 ASCII 连字符/感叹号最终变成全角字符，长度相同但内容不同，验证被正确拒绝。没有改动生产密码规则，也没有把此失败记为成功认证。API35 普通版只宣称直接启动到身份验证边界通过；API32 两版完整冷流程另已通过。观察期还记录了系统 ART ProfileSaver 崩溃和一次并发 uiautomator 检查的重复注册异常，不能将所有失败合并认定为同一原因。

F-Droid API35 另通过1项锁定验证/取消不写入设备回归（8.346s）；在无 instrumentation、冷进程、Gboard 条件下完成真实系统保存→身份验证页的独立流程，提交前确认两次新密码相同，未提交身份验证或编辑页保存。日志同一请求确认 creator opt-in 分支已执行。API35 两版覆盖的是直接启动边界，完整验证和真实数据库写入结果以 API32 为准。SDK34/35/36 的 api-versions.xml 均标记 setPendingIntentCreatorBackgroundActivityStartMode 自 API34 提供，生产版本保护与之相符。

普通版另完成 R8 Release 构建（9m 33s），从本次1.0.323-26103112-94实际x86_64 APK读取混淆后DEX，确认 `[SAVE]` 条件分支及 `android.util.Log.println` 调用均保留。仅做本次 Release 构建与产物检查，未额外进行 Release 设备测试或 F-Droid R8 构建；前述设备验证使用各自本次 Debug 包。

### 清理与交付边界

API32 恢复原用户0并移除本轮自有访客13，原用户0/10/11/12保留；API35 恢复原用户0并移除本轮自有访客10，原用户0/11保留。两台均为本任务启动，完成后已停止，原 AVD 配置与数据盘保留。未清理主用户应用数据、切换用户真实键盘、安装模块、提交、推送、发布、打标签或交付 APK。草图/实际截图仅保存在本地忽略目录。

开关引入前，两版主程序/测试包构建及各 23 项 JVM 通过。普通版首轮 6 项设备测试全部失败；同期模拟器发生 System UI、Gboard 和 Google 服务等多项 ANR，截图确认系统 ANR 弹窗遮住保存审阅页。该批保留为失败证据，不算通过或视作 HyperOS 故障复现；也不推断每一项超时均已确认同因。保留原数据盘停止模拟器，带开关最终测试在恢复后进行。

首轮测试编译发现 ActivityOptions.fromBundle 不在公开 SDK，改为仅测试侧反射解包；生产只用公开 API。随后单元夹具出现 Robolectric 已启动 Activity 后进先出和 API29 缺少动态接收器测试权限的问题，只修正夹具。日志异常类别在首次正式设备验证前改为白名单固定值，避免任意类名与通用 token 脱敏的冲突。保留失败记录，不把未完成的运行计作通过。

带开关的普通版 API32 开启模式首项仍在 open() 等待测试表单实例时超时，尚未到达系统保存；其他5项和随后同包单项复测通过。不把该超时与故障 HyperOS 认定为同因。

## 故障机复测与日志判断

故障用户需在新版上保持原来的非 Monica 输入法，重复同一登录表单。LogFox 可筛选 `MonicaAutofill`，或通过 Monica 设置中的日志导出获取 `[SAVE]` 记录，并保留完整构建号/Android API 与点击时间。

- REQUEST_RECEIVED 后若为 SAVE_DISABLED/NO_SAVE_DATA，查看保存设置或字段提取，不能归为启动拦截。
- HANDOFF_SENT 说明回调已经交付，不等于系统成功显示页面。若同一请求无 ACTIVITY_CREATED，结合 ActivityTaskManager/Autofill 系统日志检查启动拒绝、会话失效或进程启动问题。
- 有 ACTIVITY_CREATED 后查看 AUTH_READY/EDITOR_READY 及 LOCK_CHANGED/SESSION_LOCKED/SCREEN_OFF/取消事件，区分页面初始化、验证与主动关闭。
- 没有日志本身不能证明回调未到达；需排除旧 APK、抓取窗口、进程启动失败或日志未保存。

没有故障 HyperOS 真机的对照结果，不能宣称已确认根因、全面兼容或保证绕过厂商后台策略。

## 文本证据

- `autofill-hyperos-save-fix-323-api32.txt`：开启/关闭、设置中英文、冷启动流程与首次失败/复测。
- `autofill-hyperos-save-fix-323-api35.txt`：高版本首次失败、直接启动边界与输入测试限制。
- `autofill-hyperos-save-fix-323-logcat.txt`：仅保存阶段白名单日志，不包含凭据。
- `autofill-hyperos-save-fix-323-checks.txt`：共享文件、JVM、APK资源/哈希、构建与清理结果。
- `autofill-hyperos-save-fix-323-r8.txt`：普通版R8实际APK日志调用检查，不作为F-Droid R8通过证据。

# 网页登录、注册后保存提示排查（1.0.323）

日期：2026-10-31。普通版与 canonical F-Droid，仍未发布。

## 结论与范围

用户报告部分网页尤其注册后不出现系统“保存密码到 Monica”，没有可确认的网站、浏览器或 Android 版本。本次不是 HyperOS 1 跳转专项。

已补齐网页 SaveInfo 的不可见触发标志，并修正保存快照中按精确 AutofillId 读取字段时跳过隐藏父节点的问题。API29/34 的修前回归 6 项中 4 项失败；修后两版各 153 项相关 JVM 通过。标准网页提交、系统保存、Monica 编辑确认及真实库中账号/密码核对已通过。

**不能据此宣称用户原反馈已修复。** 标准网页测试在旧包也通过；F-Droid 的“仅隐藏整个 WebView”诊断仍未及时出现保存提示；全部数据等于自动填充基线时，Android 仍会抑制提示。这些边界没有靠自动保存或更换输入法功能绕过。

## 实现

- `AutofillSaveInfoPolicy` 增加可选 `saveOnAllViewsInvisible`。主响应构建器对 HTTP/HTTPS 网页启用，选择器返回响应对已有 webDomain 启用，使用 Android `FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`。
- 账号分步沿用 `FLAG_DELAY_SAVE`；原生提交、关闭保存、compat 模式保持原策略；注册的新密码及确认值检查保持。
- 服务只在保存阶段查找已解析、已限制范围的精确 AutofillId，允许遍历隐藏父节点；没有改变填充时的可见性、跨来源/表单隔离、保存排除、原认证与编辑确认、Keystore 或加密策略。
- 没有新增生产 UI、网页监听器或强制自动保存。HyperOS 保存中转和 Monica 键盘行为未因本轮而修改。

## 证据与结果

| 检查 | 普通版 | canonical F-Droid |
| --- | --- | --- |
| Debug 主程序、测试包及首次 JVM 构建 | 2m20s 通过 | 2m43s 通过 |
| 最终测试包重建 | 1m44s 通过 | 58s 通过；另一次 50s 构建包含补跑的 11 项 JVM |
| 相关 JVM | 153/153 通过 | 153/153 通过，142+11 分两次执行 |
| 最终常规设备检查 | 7/7，67.365s | 混合 8 项中 7 项常规通过，另 1 项诊断失败，总计 110.505s |
| 仅隐藏 WebView 诊断 | 未另跑 | 20 秒内未出现 SaveUI，未解决 |
| 共享生产、测试与 HTML | 8 个文件 SHA-256 一致 | 同左 |

设备：公共 `Monica_Issue136_API_32`，Android API32 / x86_64，WebView `155.0.8059.43`。使用本轮隔离访客及合成凭据，网页输入使用外部 Gboard inline，HyperOS 开关关闭。没有注册真实网站账号、提交真实凭据或发送验证码。

7 项常规检查覆盖：

1. WebView 登录页正常 DOM submit 后隐藏表单，系统 SaveUI → Monica 编辑 → 用户确认 → 数据库账号及密码与输入完全一致。
2. WebView 注册页同上，包含 `new-password` 与确认密码。
3. 原生手动注册提交出现系统保存提示。
4. 账号/密码分步，最终保存携带前一步账号与新密码。
5. 修改密码后真实存储新值。
6. 注册账号建议不覆盖新密码和确认框。
7. 接受生成密码后未改自动填充值，Android 抑制保存；加密生成历史仍有该密码，已有账号密码未被改写。

HTML 测试页使用正常 input/change 事件报告合成值是否正确；通过逐字硬件输入核对实际 DOM，避免仅调用 Accessibility ACTION_SET_TEXT 时 Android 没有接到值变化。为避开候选遮住网页按钮，宿主按钮只执行 DOM `requestSubmit()`，不调用 `AutofillManager.commit()`、不伪造 Autofill 通知。编辑保存后的数据库核对及夹具删除仅涉及隔离访客中的合成新行。

## 对照、失败和未解决边界

- 普通版旧包 `before-fix.APK` 在最终可靠输入方式下，两个标准网页提交测试也通过，27.626s；修后对应两项 33.44s。**这是兼容性回归，不是证明修复效果的负向对照。** 旧包 SHA-256 为 `7c18afe5cde393e9486a2686aab75b60ba19421bdadc4051ed539e68c9df67bf`。
- 修前 JVM 负向对照的 4 项失败确实对应：未设置网页保存标志、精确 ID 位于隐藏父节点时读回 null；修后变为通过。但标志正确不等于每种浏览器都会及时触发保存。
- F-Droid 额外诊断仅让宿主隐藏 WebView，既不提交 DOM 表单、也不结束 Activity。20 秒内没有 SaveUI；进入下一测试 Activity 后才观察到框架保存检查。该场景不能称为已修复，也不能替代真实网站复现。保留 `hiddenEmbeddedWebViewSaveCompatibilityProbe`，必须额外传 `-e autofillHiddenWebViewSaveProbe true` 才执行；默认跳过，普通回归计数不包含它。最后测试包重建只改此诊断名称和显式开关，没有修改已通过的七项普通测试，因此 F-Droid 未重复跑相同七项。
- Android 12 的 `Session.showSaveLocked` 会要求字段非空、至少一值相对初始/自动填充基线改变、且不完全匹配已有 Dataset。生成密码也是自动填充值；单加 ephemeral dataset 不能消除这个基线。现有生成历史可以找回密码，但**不等于账号已经入库**。
- 早期设备夹具先后遇到 placeholder 不暴露、按钮越出 WebView、候选遮挡、ACTION_SET_TEXT 只改变 DOM 而系统认为必填值为空、shell 字面引号及候选出现后逐字输入中断。测试 IME 控制键盘用例一项通过、dropdown 接续用例失败，不能全归咎于模拟器，也不能归咎于本轮保存标志；这轮未解决该独立输入连接行为。探索性 IME 桥及强制显示键盘修改已撤销。
- 早期原生分步和生成密码接受按钮被候选遮挡，改为对实际可点击节点执行 ACTION_CLICK 并等待状态后通过；没有更改生产安全规则。首轮 Robolectric 缺测试动态广播权限，仅补夹具权限后才记录有效负向对照。

上述历史失败的原始记录保留在本机 `.codex-tmp/web-save-323/`；不能将早期只见 DOM 值而系统值为空的失败当作产品问题复现。当前缺少反馈网站、浏览器和现场日志，不声称所有注册页已覆盖。

## 参考与复测

- Android `SaveInfo` 文档说明 Activity 结束、显式 commit、不可见标志及触发控件等结束条件，并列出 required/value/dataset 限制：[SaveInfo](https://developer.android.com/reference/android/service/autofill/SaveInfo)。
- 只读核对 [AOSP Android 12 Session.java](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r34/services/autofill/java/com/android/server/autofill/Session.java)，本地副本仅留临时目录，没有引入上游实现代码。
- 复测真实网页时，应记录浏览器/版本、表单是否由系统识别、是否收到值变化、是否提交会话、系统是否实际发出 SaveRequest，再区分没有系统提示与点击后没进入 Monica。仅修改网页 DOM 的自动化不能证明系统 Autofill 收到了输入。

文本证据：本目录 `autofill-web-save-323-api32.txt`、`autofill-web-save-323-checks.txt`、`autofill-web-save-323-old-package-control.txt` 和 `autofill-web-save-323-negative-jvm.xml`。两版旧包对照及负向 JVM 文本来自同一次普通版验证，不计作各自重复实测。

没有本轮 R8、真实手机或真实网站登录验证；未提交、推送、发布、创建标签或交付 APK。清理结果另记 checks 文件，保留公共 AVD 的配置和数据盘。

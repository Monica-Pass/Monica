# Android 1.0.331：关联应用推荐与应用名称搜索验证

日期：2026-11-25。普通版 code12、canonical F-Droid code38，均未发布。

## 问题与根因

用户反馈：凭据已关联应用，但系统自动填充列表没有推荐；搜索应用名称也找不到。

修前普通版 MuMu 的两个针对性设备测试均失败（50.624 秒）：冷启动认证后未推荐绑定的原生记录；仅存包名的关联无法通过已安装应用名称搜索。旧 Release 的真实系统入口也复现了应用名称搜索结果为空。截图因安全保护为黑屏，未计入视觉验收。

1. 未认证的服务阶段不能读取原生库，但实际登录选择器收到空或不完整的推荐 ID 列表后，将其视为最终结果。认证后载入记录时，没有重新匹配。
2. 原有搜索已包含保存的 appName 和包名，但仅保存包名的关联没有补入已安装应用名称；另一个重复过滤器没有 trim，导致带首尾空格的查询被拒绝。

## 改动边界

仅修改 7 个自动填充生产文件及 5 个相关测试文件，两版 12 条共享路径字节一致。本任务开始时的其他 app/src 文件哈希不变，保留已有 M3E UI 与 issue #189 改动。

- 真实已存登录选择器请求带 refreshLoginSuggestions；认证并载入当前来源后重算推荐，保留原推荐 ID 顺序，再追加匹配记录。显式 callback、结构化请求、新密码专用 ID 的空列表语义保留。
- 原始 isWebView、webScheme 沿服务、请求和选择器参数传递，并参与请求身份。刷新加载现有 URL 规则，继续保护浏览器来源、无域名 WebView、local-file 精确绑定、来源选择与锁定。
- 请求内在 IO 线程解析已安装应用名称，覆盖多个应用绑定、Android-app URI 和旧裸包名关联；统一 trim 搜索查询，删除重复过滤。名称只用于搜索，不改写凭据或关联字段。
- 没有改动认证算法、KDF、数据库格式、云同步、凭据导出、备份恢复生产逻辑或其他 UI。

生产文件：AutofillRequestNg、AutofillProcessorNg、MonicaAutofillServiceNg、FillResponseBuilderNg、AutofillPickerActivityV2、AutofillPickerSuggestions、新增 AutofillPickerSearch。
测试文件：AutofillPickerSuggestionsTest、新增 AutofillPickerSearchTest、新增 AutofillPickerRefreshContractTest、MdbxFrameworkAutofillInstrumentedTest、NativeMultivaultPickerUiTest。

## 自动验证

两版各 53 个不同相关 JVM 方法通过，无失败、错误或跳过。涵盖推荐刷新合同、显式空列表、WebView/网页来源、URL 规则、应用名/多关联/空格搜索及请求身份。普通先运行 52 项，新增 URL 规则回归后重跑 SuggestionsTest 全部 13 项；其余 40 项未变，合计不同方法为 53。F-Droid 整批 53 项通过。普通构建还运行了既有 19 项 Bitwarden 单测并通过，单独记录，不计入 53。

MuMu API35/x86_64：两版各 23 个不同设备方法通过。普通分 5 项真实 framework（19.436 秒）和 18 项原生/来源/参数/UI（82.687 秒）；F-Droid 完整 23 项（79.541 秒）。覆盖 Local、KDBX、MDBX、合成离线 Bitwarden，多库来源、次要应用绑定、安装名称搜索、原生数据保留、实际 Dataset 值、取消、锁定/切换与迟到结果拒绝。计时为驱动实耗，非统一 runner 内计时。

F-Droid 首轮设备运行前 19 项通过，执行第 20 项期间 MuMu 退出，未完成 23 项。同期两个 R8 构建造成明显宿主内存压力，与退出相关，但未独立证明 OOM 根因。原输出保留。仅归档后重置本任务 F-Droid Debug 合成夹具数据；Release 数据未清空。恢复 MuMu 后同一 Debug APK 完整 23 项通过，不能把首轮称为全绿。

## 实际 R8 Release 与保留数据升级

两版实际 minified Release 构建成功，生产源码与已测试版本一致；mapping 证实新增搜索对象被混淆。普通 479.205 秒，F-Droid 413.781 秒。仅使用任务隔离包名与合成 Debug 签名证书，不能替代正式签名升级验收。

旧 Release 复用此前 issue #189 已构建包；审计普通 4370/F-Droid 4375 条生产文件与本任务修前基线无差异。新旧包的同包名与签名证书已核对，以 install -r 升级，没有卸载或清除 Release 数据。

两版均完成：原主密码解锁、错误密码拒绝、旧记录读取、新记录保存、不同 PID 冷启动读取；并在两个不同冷 PID 下，从外部原生登录表单进入真正系统自动填充，认证后推荐、搜索带首尾空格的安装名称、选择记录，由真实 framework Dataset 填入用户名和密码。没有通过直接启动选择器或构造假的 Dataset 代替验收。

升级前由旧 Release 的实际导出 UI 创建加密 ZIP。升级后通过实际删除/认证/导入/恢复确认/备份密码 UI 恢复旧记录，并在新 PID 下核验旧记录和新增记录，最终恢复 Immediately 自动锁定并再次登录。

备份文件选择环节有限定：MuMu 重启后将自有 user10 显示为 user0 的 managed profile；系统 DocumentsUI 的已有根目录为空，且没有展示可正常 query 的临时 DocumentsProvider。原文件及两个本机副本哈希一致。最终仅在自有测试 profile 临时禁用 DocumentsUI，使用临时只读文件夹具处理 app 发出的 OPEN_DOCUMENT，请求由 UI 点击选择精确旧备份 URI；Release 的实际解密、恢复和冷读照常执行。夹具不修改备份字节或生产代码。此结果证明备份恢复及数据保留，不能宣称 MuMu 系统文件选择器通过。profile 的 DocumentsUI 已恢复默认状态，夹具已删除。

## 失败与环境记录

保存修前预期失败、F-Droid 中断及重试记录。文件选择测试中的重复设备名称索引、键盘遮挡、认证超时、原生夹具按钮被标题栏遮挡及保存密码提示导致的驱动断言失败均保留本地操作记录，修正私有驱动/夹具后完成核验，没有修改生产恢复逻辑或放宽断言。部分 uiautomator/content probe 在产出有效 XML/行后退出 139，属于探针退出，未计为 Release 应用崩溃。

## 清理与范围

只使用已有 MuMu 实例2，在本任务起始时停止，后由本任务启动、退出后重启，最终停止。自有 user10、两版隔离应用与只读夹具、user0 Download 中精确自有暂存文件/目录已删除；原 user0 和其他实例保留，adbd 恢复 UID2000，自有 ADB15072 停止，实例配置与数据盘保留。加密旧备份的私有本机证据副本保留，不入库。

仅合成 API35/x86_64 数据、离线 Bitwarden 和原生测试表单；未知反馈用户的实际库类型，未验证其原库、真实云账号、OEM 真机、其他 Android API 或正式签名安装包。未提交、推送、评论 issue、发布或交付 APK。


| 版本 | 旧 APK SHA256 | 修后 APK SHA256 |
|---|---|---|
| main | `2dc0a7c7159d9c7efd8a9e097fbd3f552d75a48620cc86bbf3bdc74f6580b725` | `e473b0b5ee7f29555a87bbc49403c84a73461fb3c37cc2aa0a0ca00d75fceb4f` |
| fd | `35a2e394df039593742cb94acb8c4e01284e4dcc75a0de86d0abca68ed8a397a` | `c698e401805ed8388e2ef7cd1b214d8f3f0a367b19bb42bc4de20ed45814a3d7` |

当前报告目录的 XML、设备文本和 results.json 为可入库证据。完整构建、UI 树、夹具失败和私有加密备份留在本机任务目录；截图不入库。

最终核对：12 条共享源码/测试字节一致，保持已测试源码哈希；其余 app/src 文件与任务起始基线一致；两仓 git diff --check 和新增文件空白检查通过（已有 LF/CRLF 提示保留）。F-Droid 元数据及 6 个发行脚本测试通过。

后续反馈已明确为 KeePass WebDAV；本轮增加镜像/工作副本两个设备方法，生产源码不变。补充范围、结果与首轮夹具失败见 autofill-webdav-bindings-331.md。原各 23 项设备记录为此前批次，新增两项单独记录。

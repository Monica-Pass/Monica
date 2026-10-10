# Passkey compatibility fixes — 2026-10-10

## 中文

普通版和 F-Droid 已修复旧凭据备份资格合并与共享 Bitwarden Cipher 覆盖问题，并增加跨设备能力提示。

### 发现与处理

1. 已有 Android 记录的 `backupEligible = NULL` 实际使用 BE=true 签名。旧合并逻辑接受外部 BE=false，能让同一 ID 和私钥的认证标志从 `0x1D` 变成 `0x05`。现在区分“已有但未知”和“全新导入”，已有记录拒绝相互矛盾的资格属性；新导入允许保留 BE=false。BS 可变化，签名路径原来的零计数策略未改变。
2. 原有 `updatePasskey` 使用新建模板替换整个 Cipher，可能丢掉兄弟 Passkey、密码、TOTP、URI 匹配方式和自定义字段。这是此前已存在的上传缺陷。现在先 GET 原始加密 JSON，匹配唯一的凭据 ID，并校验 RP、userHandle、私钥、编辑权限及版本；仅合并目标密钥的显示属性、计数和编辑的共享备注，保留父条目、其他凭据及未知字段。使用项目密钥时保留其包装数据。
3. PUT 携带 `lastKnownRevisionDate`，同进程 Passkey 更新串行化；关闭此路径的连接重试和重定向。回读确认保存结果后才标记已同步。服务器冲突、返回内容缺失及本地同时编辑保留本地数据和待处理状态；响应丢失时只回读，不盲目重发。
4. 仅设备绑定且可用、不可导出的私钥标记“仅本地”。BE=false、非 ES256 或已知非零计数历史标记“跨端受限”；缺失私钥标记“密钥不可用”。详情解释原因。是否支持 KeePass 格式不等同于是否可跨设备登录。
5. 单独持久化明确的文件夹移动意图，区分“保留服务器文件夹”和“用户要求移回根目录”。记录先于Room更新落盘；Room失败恢复原意图，上传读取时校验当前记录状态，旧上传不能清除较新的移动意图。普通备注编辑保留父文件夹，明确移动在合并原Cipher时只更新folderId，兄弟密钥和共享数据继续保留。

### 验证

| 项目 | 普通版 | F-Droid |
| --- | --- | --- |
| 相关单元测试（14 个类） | 52 通过 | 52 通过 |
| Android 虚拟机测试 | 12 通过 | 12 通过 |
| Debug APK 与 AndroidTest APK | 构建成功 | 构建成功 |

单元测试涵盖迁移79→80、旧NULL标志、新导入、实际认证数据的标志位、KeePass编解码及合并、ID表示、Bitwarden映射、发现/请求策略、备注与导航、共享Cipher保留和每项目密钥。组织库、只读/已删除条目、缺少版本、身份不匹配、重复ID及无效密钥均拒绝写入。

设备测试使用公共 API32 AVD、真实 Room、Android Keystore、Retrofit 及本机 MockWebServer 的合成凭据，覆盖单密钥编辑、同Cipher两密钥并发、409冲突、写入成功但响应断开、本地在PUT前/期间编辑、服务器丢掉兄弟凭据的失败识别。检查设备绑定私钥、可导出明文/受保护引用与不存在的别名；280dp、1.7倍字体、深浅主题及异步重组均通过。

首轮发现异步检查随无关时间戳重建反复启动，已改成仅由密钥和兼容属性变化触发，随后设备全套10项通过。扩展测试的一条旧导航断言只接受 `Icon(imageVector = ...)` 写法，当前源码使用位置参数；已改为检查入口图标和实际点击回调，未修改导航行为。

另增加2项单元测试验证移动意图回滚和并发完成保护，2项设备测试验证移动到文件夹和移回根目录。

复现命令：使用 JDK17、仓库 Gradle，运行 `:app:testDebugUnitTest` 并选择验证JSON中的14个Passkey相关测试类；打包 `:app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86TestAbi`。设备测试类为 `credentialexchange.PasskeyCipherUpdateInstrumentedTest` 和 `passkey.PasskeyPortabilityInstrumentedTest`。

本机完整日志、测试XML和源文件摘要位于工作区 `.codex-tasks/passkey-fix-20261010/`，`verification.json` 记录测试计数、相关源码及安装包SHA-256。

### 边界

- 本轮没有对真实网站执行跨设备登录或Android14+系统Credential Manager全链路验收。能力标记来自可用密钥和已知元数据，不是所有客户端/网站的兼容性认证。
- 组织Cipher当前缺少组织密钥解析能力，因此拒绝此类Passkey更新，避免使用账户密钥覆盖组织数据。已有登录能力不因这个上传保护被重写。
- 保留服务器父条目的名称、收藏及共享字段；只有明确移动意图才修改文件夹。文件夹和共享备注的编辑作用于整个父条目。
- 对未知旧属性保持历史签名语义，不能推断在其他认证器最初注册时的缺失属性。已知非零计数提示依据当前保存的计数，未新增历史溯源字段。
- 未提交、推送或发布。普通版及候选的当前未发布说明已更新；F-Droid只更新下一版及候选未发布说明，已发布1.0.318与fastlane25保持原样。用户已有版本标题和其他未提交修改保留。

[本地可编辑设计及截图](design/passkey-portability-319/README.md)

## English

Fixed legacy NULL eligibility being reinterpreted during refresh, and replaced destructive single-Passkey Cipher replacement with a guarded merge of the original encrypted document. Existing credential identities and keys are retained. Conflicts and concurrent local edits are not marked as successful uploads. Added Local only, Transfer limited and Key unavailable labels with detailed reasons.

Both editions passed 52 related unit tests, 12 API32 instrumentation tests and debug/test APK builds. Explicit folder moves, including moves back to root, retain all other shared Cipher contents. Tests use synthetic data and a loopback server; live website and Android14+ system Credential Manager end-to-end interoperability was not exercised. Organization-key resolution remains unsupported for this update path, which rejects such writes. Published F-Droid notes were preserved.

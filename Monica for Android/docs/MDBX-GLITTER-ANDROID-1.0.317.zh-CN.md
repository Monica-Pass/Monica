# MDBX Glitter Android 验证（1.0.317）

> 2026-10-06：用户已决定暂缓 Android 客户端接入 Glitter。以下保留为先前实现与验证记录，不代表当前客户端功能；当前只提供 Sky、Multi、Power，MDBX 底层仍支持 Glitter。

状态（2026-10-06）：实现及当前环境内的双版最终验证完成。普通版和 F-Droid 各 236 项相关 JVM、30 项应用设备测试、5 项 JNI 测试通过。真实硬件认证成功及真实远程账户往返未验证，不作全场景通过承诺。

## Android 行为与代价

- 设备必须为 64 位进程，至少 3 GiB 总内存、896 MiB 可用内存，已设置安全锁屏并登记强生物识别。
- 使用 AndroidKeyStore 的独立 AES-GCM 每次认证密钥，检查 TEE/StrongBox 及硬件强制认证属性；实际 BiometricPrompt CryptoObject 完成加解密才授予访问，不以普通软件密钥或设备 PIN 回退冒充。
- Glitter 固定要求主密码（至少 16 字符）、随机密钥文件及 512 MiB Argon2id。首次选择与重开密钥文件均限制 1 MiB，读取临时缓冲和错误路径字节会擦除。
- 每次显式认证最多 60 秒，一个原生 handle 复用读写，不自动重开或续期。到期、后台、取消或文件身份变化会锁定；显示内容和未保存草稿清除。原生层自身的 2 分钟空闲/15 分钟上限不会延长 Android 的 60 秒限制。
- 内容不复制到普通 Room 聚合列表、不进入后台自动填充或普通 API token/通用明文导出路径。直接原生管理页支持登录/JSON 编辑、删除、保留未知字段与大整数及附件事务。
- 文本/图片附件只在内存验证和预览；单条最多 16 个附件，单次上传最多 16 MiB。禁止明文临时文件导出，创建/打开/编辑页面保持 FLAG_SECURE，阻止复制/剪切向剪贴板写入及 ProcessText 外传。硬件复制/剪切不删除草稿，普通删除及粘贴仍可使用。
- 本地 Glitter 只在应用私有存储使用。外部文件必须明确确认“独立副本，原文件永不更新”；不支持的外部目录新建/旧链接条目在写入前拒绝。
- 取舍是更高内存与解锁成本、严格 60 秒交互窗口、后台立即锁定、草稿不跨后台保留，以及上述自动填充/导出/本地链接限制。不能宣传任意操作都比 Sky 更快。

## 已验证与待验证

| 项目 | 当前证据 |
| --- | --- |
| 两版相关 JVM 回归 | 每版 56 suites、236 项，0 失败/错误/跳过；共 472 次执行，不是 472 个不同场景 |
| 普通版最终 Debug 与 AndroidTest 构建 | 4 分 12 秒成功，最终快捷键修复及原生库已入包 |
| F-Droid 完整源码构建 | 15 分 31 秒成功，两个 Rust JNI 模块实际执行三 ABI source build；随后主/测试 Kotlin 强制重编及单测重跑 8 分 25 秒成功 |
| 应用设备回归 | Android 32 x86_64 公共 AVD：普通版 30/30（72.441 秒），F-Droid 30/30（61.997 秒） |
| 两版各自 JNI 回归 | 普通版 5/5（18.811 秒）；F-Droid 自身源码构建的 JNI 5/5（17.121 秒），不是复用主版测试包代替 |
| F-Droid 测试渠道修订 | 只改测试为本地/WebDAV 四入口且明确无 OneDrive，测试包重建 14 秒；产品 APK 未改变 |
| 实际界面 | 两版创建/设备拒绝、锁定管理页、合成登录编辑页均从最终设备运行捕获并检查；使用 Compose captureToImage，未关闭 FLAG_SECURE |
| 实体设备硬件认证成功 | 未验证：没有可用真机 |
| 真实远程账户端到端往返 | 未验证，不以本地 JNI 或模拟上下文替代 |

失败与修复均保留证据：最早测试错误要求受保护密码提供 CopyText/CutText，已改为断言这些动作不存在。随后真实 Ctrl+X 虽未泄漏剪贴板却删除选中密码，已在生产 wrapper 提前拦截硬件复制/剪切，未削弱原文不变断言。最终两版验证八组硬件快捷键及普通 Delete/Backspace、Ctrl+V/Shift+Insert 均符合预期。F-Droid 另一个失败来自共享测试误要求显示已裁剪的 OneDrive，仅修该渠道测试契约，普通版六入口检查保留。

设备回归覆盖真实 native handle 的登录及附件创建/编辑/关闭重开/删除、大整数保真、旧版本写入拒绝；4 项严格会话后台/认证竞态/到期通知/锁后结果擦除；滑块、编辑器、复制攻击、屏幕安全引用计数、旧模式元数据配硬件 envelope 的门禁、大字体及键盘可达性。为隔离 UI/session 行为而用的 SKY fixture 与 synthetic DeviceContext 不代表真实 Glitter 硬件认证成功。

## 可复现命令

在对应 Android 项目目录运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'takagi.ru.monica.*Mdbx*' --tests 'takagi.ru.monica.*NativeApiToken*' :app:assembleDebug :app:assembleDebugAndroidTest :mdbx-engine:assembleDebugAndroidTest -PincludeX86TestAbi --console=plain --max-workers=2
```

必须保留全限定测试包前缀，Windows 会将裸 `*Mdbx*` 展开成同名目录。F-Droid 通过 `preBuild -> buildMdbxFfiFromSource` 从对应 vendored Rust 构建三 ABI，不能跳过后称其完整构建通过。

应用测试类为 `MdbxNativeCredentialInstrumentedTest`、`MdbxGlitterSessionInstrumentedTest`、`MdbxTigaSliderTest`、`MdbxNativeEditorUiTest`、`MdbxGlitterTextProtectionUiTest`、`MdbxLayoutReachabilityTest`。测试使用合成数据，不读取用户保险库。

## 证据与范围

完整原始输出在工作区 `.codex-tasks/glitter-317/`：

- 普通版：`android-main-acceptance-final.log`、`android-app-acceptance-final.log`、`android-engine-acceptance-final.log`。
- F-Droid：`android-fdroid-source-build-final.log` 保留真实 cargo 构建；`android-fdroid-acceptance-final.log` 保留 8 分 25 秒强制重编；`android-fdroid-test-contract-build.log` 是最后仅测试契约重建；`android-fdroid-app-acceptance-final.log`、`android-fdroid-engine-acceptance-final.log` 是最终设备结果。
- 失败历史：`android-app-tests-initial.log`、`android-app-acceptance-attempt1.log`、`android-fdroid-app-acceptance-attempt1.log`。没有通过覆盖失败日志掩盖问题。
- 普通版最终 x86_64 产品包 SHA-256：`ef655485b800e6a89317a7f511b2c01eb58b55ef1766bd36a2fe2fffd9444026`。
- F-Droid 最终 x86_64 产品包 SHA-256：`edcbdb692b56d7377de6007f192dbefef83b4115097c740d68d1149ba2d1b086`。

普通版 instrumentation 包为 `takagi.ru.monica.test`，F-Droid 为 `takagi.ru.monica.fdroid.test`。F-Droid 使用 NDK 28.2.13676358、API 26 和已验证的 vendored Rust；最终 Kotlin 主/测试任务明确使用 `--rerun`，避免编译期间修改源码的取输入竞态。原生引擎、基准及构建输入/包内库一致性由综合验收报告单独记录。

未提交、打标签或发布，未将安装包复制到交付目录。真机、其他 Android/API/ABI 运行时以及实际远程服务的可用性仍需各自证据。

普通版包含 WebDAV 与 OneDrive 入口；两者的增量同步均只使用当前已认证 handle，过期时保留 pending/错误状态，不绕过认证。

本地可编辑设计及实际渲染见 [Glitter 设计记录](design/glitter-317/README.md)。

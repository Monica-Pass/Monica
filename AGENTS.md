# Monica Android 维护指南

本文件供维护者和编码代理使用，只记录长期适用的约定。当前版本以构建配置为准，任务进度、提交记录和测试流水另存，不在这里持续追加。

## 项目入口

- Android 工程位于 `Monica for Android/`，使用 Kotlin、Jetpack Compose 和 Material 3 Expressive；Gradle 配置使用 Groovy。
- 业务源码位于 `Monica for Android/app/src/main/java/takagi/ru/monica/`；界面在 `ui/`，状态管理在 `viewmodel/`，存储与同步分布在 `data/`、`repository/`、`keepass/`、`bitwarden/` 等目录。先查现有实现，再增加抽象或依赖。
- JVM 测试在 `app/src/test/`，设备测试在 `app/src/androidTest/`，以上路径相对 Android 工程。原生模块和 `kotpass/` 有各自的测试。
- 涉及 MDBX 时先读 [跨端存储契约](docs/storage/MDBX-CROSS-CLIENT-CONTRACT.zh-CN.md)和 [MDBX 接入指南](mdbx/CLIENT_INTEGRATION_GUIDE.zh-CN.md)。
- F-Droid 独立维护于 [Monica-Fdroid](https://github.com/Monica-Pass/Monica-Fdroid)。同步前核对目标 checkout 的远端和分支，不把同名目录或旧副本视为发布来源。浏览器扩展有自己的仓库和维护指南。

## 开始修改前

- 查看 `git status --short`、当前分支及远端，确认本次目标和已有改动。优先用 `rg` 查找同类功能、调用方及测试，不只修改搜索命中的第一处。
- 从用户入口沿界面、ViewModel、Repository 到实际存储核对完整行为。新增业务逻辑放入对应模块，避免继续堆入 `MainActivity` 或大型 Compose 页面。
- 涉及普通版与 F-Droid 时，先列出共享文件和平台差异，再分别应用和验证；不要整目录覆盖，也不要仅凭文件同名认定实现一致。
- 遇到旧文档中的“当前版本”“已通过”“待实现”，用现有源码、构建配置及对应提交的验证证据重新核对；不要把历史描述当作本轮结论。

## 构建与验证

使用仓库 Gradle Wrapper，JDK 17；SDK、NDK 和 Rust 环境按构建配置及 `.github/workflows/` 准备。原生构建入口见 `Monica for Android/gradle/monica-rust-jni.gradle`。本机 SDK 路径和签名材料留在本地。

在 `Monica for Android/` 内执行；Windows PowerShell 将 `./gradlew` 替换为 `./gradlew.bat`：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86TestAbi
```

- 最后一条用于准备 x86_64 模拟器主包和测试包；默认应用只输出 ARM 包，不能仅改文件名冒充其他 ABI。打包成功不等于设备测试通过。
- 按改动选择相关 JVM 和设备回归；安全、存储和同步修改需覆盖失败、取消、锁定、冲突及原数据保留。修改原生模块时补跑其对应测试；涉及 R8 的问题需要 Release 验证。
- 纯文档修改检查链接、命令及 `git diff --check`，无需重新打包应用。报告区分实际通过、失败后复测、跳过和未验证，不将合成数据或模拟器结果泛化为真机验收。
- 设备测试优先复用已有合适 AVD，先检查是否已启动；只有 API 或 ABI 无法覆盖时才新建。使用隔离的合成数据；只清理本任务创建的用户、夹具和进程，保留原用户、AVD 配置及数据盘，结束时停止本任务启动的实例。
- 默认完成构建和测试即可；只有明确要求交付时，才额外复制安装包或提供下载链接。

### 按改动选择检查

| 改动 | 需要核对的行为 |
| --- | --- |
| Compose 页面、导航、文案 | 编译与相关 UI 测试；实际打开、保存、返回、取消、大字号及锁定后的敏感内容隐藏 |
| 本地存储、迁移、编辑保存 | 相关 JVM 与设备测试；旧数据读取、保存重开、失败回滚、进程重启及未知字段保留 |
| KeePass / MDBX / Bitwarden | 对应来源的读写、外部修订冲突、锁库和跨端往返；不能仅验证 Room 投影或界面显示 |
| Keystore、自动填充、Passkey、输入法 | 主应用及系统入口分别验证；冷进程、认证失败、取消、超时和迟到结果 |
| Rust / JNI / MDBX 引擎 | 对应原生测试、目标 ABI 打包及实际加载；同时核对运行时来源与哈希记录 |
| 权限、Manifest、R8 | 合并后 Manifest 或最终 APK 的实际声明；相关系统授权流程，R8 问题使用 Release 包 |

### 构建和设备常见问题

- `gradle.properties` 已有内存和并发限制；先检查错误原因，再作本任务所需的局部调整。不要为一次构建修改全局 Gradle 配置、结束所有 Java 进程或清空其他任务缓存。
- 原生 JNI 随当前源码构建，不从旧安装包拷贝 `.so` 充当新代码。MDBX 引擎的实际来源另见 `Monica for Android/mdbx-engine/MDBX3_RUNTIME_PROVENANCE.json`；修改引擎时同步其来源、补丁和产物校验。
- 多设备环境中先确认 serial、Android 用户、包名及 ABI，ADB 操作显式指定目标。普通版与 F-Droid 的包名和安装结果分别核对。
- 主包和测试包必须来自预期源码与构建参数；修改生产代码后不能只重建测试包。测试前确认前台窗口没有系统弹窗遮挡，截图中的实际页面与测试目标一致。
- 失败先保留原始日志和测试结果，再定位原因。不要通过删断言、扩大超时或反复运行直到偶然通过来宣称修复；复测仍注明初次失败及修正范围。

## 数据与安全边界

- 不为修复界面、性能或兼容问题降低加密、KDF、认证、锁定、只读或修订冲突保护。密码、Token、私钥及真实密码库不得进入源码、日志、截图或测试夹具。
- Keystore 异常、解密失败、超时或不可读数据不能作为自动清库、重建密钥或覆盖旧材料的依据。保留已有密文和恢复材料，沿现有认证与确认流程恢复。
- 锁定、后台、取消和请求替换后，异步结果必须重新核验有效会话，不能发布迟到的明文、密钥或写入权限；主线程避免阻塞式解密、开库和网络调用。
- KeePass、MDBX、Bitwarden 的原生数据语义分别保留。编辑、导入、迁移和去重需保留未知字段、账号与密码对应关系、UUID、保护位、附件、历史和引用；无法证明完整保留时，不静默覆盖或删除。
- 跨库操作应明确目标、部分成功和恢复行为；文件写入及同步继续执行来源与修订校验，不把多个文件或远端请求描述为原子事务。
- 本地 Room 写入遵守既有事务和认证边界；原生库的 Room 投影或 UI 缓存不能替代原文件、引擎或服务器的真实修订。空列表、未加载、锁定、不可读和真正的空库要区分。
- 输入法包含独立 `:ime` 进程，主应用单例和内存锁状态不能自动覆盖它。安全入口改动同时检查 Manifest 中的服务、Activity、调用方验证及跨进程行为。
- Android 权限以实际功能需要为准；系统文件选择器返回的 URI 授权不能扩大成任意路径读取。保留系统服务绑定权限、导出组件保护及自动填充的来源匹配。

## 界面与多语言

- 复用现有 Material 3 Expressive 组件、主题和导航模式。设计改动先用本地 M3E Canvas 制作或更新可编辑草图，再核对实际 Compose 页面；机器上的服务地址和草图链接另存本地。
- 新增用户文案使用字符串资源，补齐应用当前支持的全部语言，保留占位符与复数语义。检查大字号、深浅主题及关键返回、取消、锁定流程。
- 容器高度变化使用单层平滑过渡，尊重系统动画设置；敏感内容在锁定时立即隐藏。
- 共用功能同步维护普通版与 F-Droid，但保留其功能和安全策略差异。F-Droid 不提供 OneDrive，不复制相关依赖、功能或发行说明。

## 版本与发行说明

- Android 版本入口是 `Monica for Android/app/build.gradle` 中的 `baseVersionName`、`appVersionCode` 和现有构建标识生成逻辑。普通版 `versionCode` 有意冻结以支持同签名回退，常规发版不要自行递增；F-Droid 使用独立递增的 `versionCode`。
- 修复默认归入当前未发布版本；切换版本时同步已确定范围内的版本配置、根目录发行说明和对应 F-Droid fastlane changelog。开发版本变更不代表已经发布。
- 根目录 [Android 发行说明](<Monica Android发行说明.md>)按 `## 中文`、`## English` 分区，不再拆简要版与详细版。已发布说明保存在 `docs/releases/`，保留历史标签和 changelog，新改动记入未发布部分。
- 提交、推送、打标签、创建 Release 和交付 APK 按用户授权的范围执行；操作前查看对应 CI 的触发条件，避免普通维护意外触发发布。

## 提交与记录

- 开始前查看 Git 状态，保留其他任务的改动；按文件明确暂存，提交前检查实际差异，不用整仓暂存夹带无关内容。
- 本文件不写日期流水、构建耗时、测试计数、设备编号或机器绝对路径。可公开的验证结论放在 `Monica for Android/docs/verification/`，详细过程和私有原始证据留在本地任务目录。
- 设计草图和真机截图只留本地，遵循 `.gitignore`；不强制加入 `docs/design/` 或被忽略的图片，提交文档也不使用图片语法引用这些本地文件。签名材料、数据库、构建包和临时脚本不混入文档提交。
- 完成时说明改动、实际验证结果及仍未验证的范围；不要把历史任务记录当作当前源码已通过验证的证明。

# 本地 Glitter 新增与保存回归（2026-10-06）

状态：普通版与 F-Droid 的最终 Debug 构建、36 项 JVM 测试、90 项设备测试全部通过，无失败或跳过。完成时间：2026-10-06 14:53（UTC+8）。

## 最终验收结果

| 版本 | 构建 | JVM | API 32 x86_64 设备回归 | 最终日志 |
| --- | --- | ---: | ---: | --- |
| 普通版 1.0.317（12） | `assembleDebug`、`assembleDebugAndroidTest` 通过 | 18/18 | 45/45 | `main-fields-build.log`、`main-final-acceptance.log` |
| F-Droid 1.0.317（24） | `assembleDebug`、`assembleDebugAndroidTest` 通过 | 18/18 | 45/45 | `fdroid-fields-build.log`、`fdroid-acceptance.log` |

合计 126 项，两版分别运行相同覆盖；中间重跑不累计。最后的预填字段修正已包含在两版最终安装包及全部 45 项设备回归中。验收范围为上述 API 32 x86_64 AVD 上的 Debug 构建；实体手机及 Release 构建未在本轮测试。

完整构建与历次回归记录保留在工作区 `.codex-tasks/glitter-add-password/`。可随文档查看的证据：

- [最终结果与 APK 校验记录](glitter-creation-20261006/results.json)
- [普通版设备日志](glitter-creation-20261006/main-final-acceptance.log)、[F-Droid 设备日志](glitter-creation-20261006/fdroid-acceptance.log)
- [普通版耗时](glitter-creation-20261006/main-timing.log)、[F-Droid 耗时](glitter-creation-20261006/fdroid-timing.log)
- [普通版小屏大字体](glitter-creation-20261006/main-small-large-text.png)、[F-Droid 小屏大字体](glitter-creation-20261006/fdroid-small-large-text.png)
- [本地 M3E Canvas 可编辑设计及源文件](../design/glitter-317/README.md)

保存按钮在 320dp 宽、560dp 高、1.8 倍字体的布局中保持可见并成功保存。测试结束恢复 Android 用户和屏幕超时。本任务修改的源文件通过 `git diff --check`，资源 XML 可解析，两版对应修复已核对，保留各自功能差异；42 个源文件的 SHA-256 在最终构建后未变化。普通版、F-Droid、候选版的 1.0.317 未发布说明及 F-Droid changelog 已更新，未修改已发布历史。

## 复现与原因

用户日志中的 `saveCredentialsAcrossTargets` 接收到 `mdbx:22:` 后，由 `requireMdbxAppReplicaAllowed` 拒绝普通副本保存。管理数据库页的原生写入可用。公共 API 32 x86_64 AVD 上，通过公开 `AddEditPasswordScreen` 的当前筛选和显式目标两条入口，复现了相同异常。

修复前实测：首次打开 3267 ms，保存 3265 ms，随后刷新 3239 ms；连接未复用。数据均为测试生成的合成内容，未使用用户密码库。

## 修复边界

- 新建登录信息使用同一目标解析器决定普通或原生编辑器；显式 Monica 本地目标优先于当前 Glitter 筛选。
- Glitter 单目标直接进入原生编辑器，保留数据库、文件夹、预填登录字段与应用绑定。混合目标或不支持的条目类型明确阻止，缺失数据库不会回退到普通保存。
- 新建条目可指定原生文件夹；文件夹不存在或已删除时拒绝写入，不改存根目录。
- 内部 Glitter 原生写入复用当前有效的前台连接，之后刷新仍可复用。原生策略到期、应用锁定、后台、凭据或文件变化仍使连接失效。
- 外部目录写入、外部变更合并与加密快照发布复用同一工作文件连接。保留既有外部变更检测、发布锁、冲突与完整性验证；实际合并触发原生密钥重新验证，旧会话按引擎策略失效，之后重新解锁；未修改的后续保存无需重复解锁。
- 没有降低 KDF，没有写入普通 Room 密码副本，没有新增后台自动填充或云同步。

## 验证覆盖与证据

- 新增公开入口测试：当前筛选、显式目标、文件夹、外部文件、显式普通本地目标、混合目标、缺失数据库、旧凭据、锁屏草稿、页面重建、失效文件夹、小屏大字体、会话复用。
- 既有真实 Glitter 两因子、原生 CRUD、加密备份、外部文件与生命周期测试；原生登录/附件编辑与复制保护回归。
- JVM 会话缓存、普通副本隔离与 Glitter 凭据策略测试。
- 日志位于工作区 `.codex-tasks/glitter-add-password/`。`red-confirmed.log` 为三项预期失败的修复前回归；`red-diagnostics.log` 包含相同保存异常与耗时。
- 初次 `red-public-screen.log` 的失败是测试尚未适配两种保存按钮布局，不作为业务复现证据。

### 设备回归范围

| 测试组 | 每版数量 | 核验内容 |
| --- | ---: | --- |
| `MdbxGlitterPasswordCreationTest` | 15 | 公开新增入口、目标与预填字段、实际文件重开、会话复用、外部合并、发布失败、本地隔离、小屏及重建 |
| `MdbxGlitterSessionInstrumentedTest` | 10 | 真实双因素、重启、加密备份、外部目录、本地原生编辑、远端隔离及后台关闭 |
| `MdbxNativeCredentialInstrumentedTest` | 1 | 原生凭据流程 |
| `Mdbx2ExternalPublicationInstrumentedTest` | 9 | 发布完整性、独立修改、并发变化、失败与本地提交保护 |
| `MdbxNativeEditorUiTest` | 3 | 登录字段、无效 JSON、附件预览清除 |
| `MdbxGlitterTextProtectionUiTest` | 3 | 剪贴板、硬件快捷键、辅助功能与粘贴保护 |
| `MdbxLayoutReachabilityTest` 中四项 Glitter 回归 | 4 | 正常应用会话、重叠窗口保护、设置与销毁、旧凭据入口 |
| 合计 | 45 | 重跑不重复累计覆盖数量 |

JVM 每版 18 项：会话缓存 11、普通副本隔离 3、Glitter 凭据策略 4。设备安装器校验本地与已安装 APK 的 SHA-256，拒绝测试跳过或数量不符，并恢复原 Android 用户和屏幕超时。

## 性能记录

同一个 API 32 x86_64 公共 AVD，临时配置 4096 MiB 内存。数据为单次回归计时，不能视为手机性能承诺或稳定的统计基准。

| 操作 | 修复前 | 普通版验收 | F-Droid 验收 |
| --- | ---: | ---: | ---: |
| 内部库首次打开 | 3267 ms | 3356 ms | 3248 ms |
| 内部库有效会话内保存 | 3265 ms | 17 ms | 18 ms |
| 内部库保存后刷新 | 3239 ms | 10 ms | 11 ms |
| 外部文件首次发布及刷新 | 未单独测量 | 6435 ms | 6492 ms |
| 外部文件独立修改后的合并、发布及刷新 | 未单独测量 | 6471 ms | 6454 ms |
| 外部文件无独立修改时连续保存 | 未单独测量 | 60 ms | 58 ms |
| 外部文件连续保存后刷新 | 未单独测量 | 7 ms | 5 ms |

内部及外部连续保存均断言复用同一原生句柄，随后关闭会话并独立打开实际文件核对数据。外部文件第一次发布和独立修改后的合并需要打开外部副本，合并又会清除原生授权期限，因此刷新仍有完整 KDF 开销；本次优化保留这一边界。

公开新增页从点击保存到通过仓库读取确认条目可见，内部库普通版为 80–158 ms、F-Droid 为 80–155 ms；外部库首次写回分别为 7089 ms 和 7238 ms。表中的存储层耗时不等同于点击到界面渲染完成的耗时。原始记录：`main-final-timing.log`、`fdroid-timing.log`。

## 测试调整记录

- `main-regression.log`：第一轮 34 项通过，确认内部保存加速，随后增加外部连续保存及失败保留的覆盖。
- `main-external-policy-assumption.log`：45 项中 44 项通过；失败项错误地假设实际合并后授权仍有效。核查 Rust 的 `refresh_verified_keyring` → `attach_verified_keyring` → `invalidate_local_acceleration` 后，将测试改为断言合并后旧句柄关闭，重新解锁后无外部变化的连续保存复用句柄。没有修改 Rust 或放宽授权。
- 最终检查发现新增预填应用绑定需要使用既有 `app_package_name`、`app_name` 字段，已修正并将网站与这两个字段加入实际文件重开断言。

仅使用公共 `Monica_Issue136_API_32`，没有操作已连接的实体手机。首次 KDF 成本和设备性能仍影响冷打开耗时，虚拟机结果不代表所有手机速度。

公共 AVD 已在测试结束后停止，配置、注册文件及数据盘保留供后续复用。

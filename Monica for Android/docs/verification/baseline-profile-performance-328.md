# Android 1.0.328 Baseline Profile 工具与实测

本轮保留真实流程的 Profile 采集和消费工具接入，但不把本次候选规则用于默认构建。F-Droid 在交替复测中出现反向结果，现有证据不足以确认稳定提速；默认构建继续使用原有 27 条应用手写 baseline 规则与依赖自带规则，并保留原有 14 条 startup 规则源文件。保留 startup 源文件不等于已证明它作为启动布局输入被消费。候选规则、候选 R8 包、原始日志和 70 份启动 trace 保留在本地，未提交为默认规则。

本报告对应 普通版，未发布版本 1.0.328 / versionCode 12。工具、测量、默认构建、覆盖安装、数据读写、系统入口及本轮最终默认包的真实 UI 备份恢复已核对。采用决定为保留工具、暂不采用候选规则。Passkey 与笔记列表的后续修改不属于此处验证范围。

## 改动

- app 接入仓库已有 Baseline Profile 插件和 `:baselineprofile` 项目；`automaticGenerationDuringBuild=false`，普通构建不自动启动设备采集。
- 显式生成采用 `saveInSrc=true`、`mergeIntoMain=true` 和 `takagi.ru.monica.**` 过滤。采集后仍须审查并实测规则，不能因为文件已生成就视为有收益。
- `nonMinified` / `benchmark` 变体使用 `.benchmark` 后缀，普通与 F-Droid 保留各自包名前缀。采集要求显式合成夹具参数，完整流程必须真正解锁、看到合成记录、搜索并进入设置，缺任一步即失败。
- 两版各 2 项 `BaselineProfileRule` 设备方法通过。应用过滤后普通候选为 10,639 条 baseline / 6,273 条 startup；F-Droid 为 10,598 / 6,267。两版独立采集，未互相复制规则。

## 启动测量及采用决定

MuMu、Android 35、x86_64，隔离的合成密码库；测量对象为实际混淆 Release。每个表格单元来自 7 次进程冷启动，所有成功运行均断言目标包到达认证输入页。合计 10 个成功运行、70 次冷启动；统计是首次显示时间 TTID，不是解锁耗时、滚动帧率或整条用户流程耗时。

| 版本 | 第一轮旧规则 / 候选规则中位数 | 交替复测旧规则 / 候选规则中位数 | 候选包 `CompilationMode.None` |
| --- | ---: | ---: | ---: |
| 普通 | 560.46 / 468.02 ms | 442.76 / 416.49 ms | 490.86 ms |
| F-Droid | 473.10 / 433.15 ms | 416.98 / 489.28 ms | 526.95 ms |

旧规则与候选规则对照均使用 `CompilationMode.Partial(BaselineProfileMode.Require, warmupIterations=0)`；包状态回执均有 `status=speed-profile`，候选与旧包中的二进制 profile 哈希不同，证明实际打包并应用了 profile。`None` 只测候选包，是独立的编译模式观察，不能当作完整无 Profile 对照组。

普通版两轮方向一致，但轮间整体漂移明显；F-Droid 第二轮候选包更慢。没有把较好的一轮挑出来当最终收益，也没有把模拟器结果外推到真机。默认源码删除本次候选生成规则后重新完成两版 R8 构建：普通 685.429 秒，F-Droid 536.307 秒。最终 `merged_art_profile` 内的应用规则逐条等于 Git 检查点的原 27 条；依赖 Profile 仍保留。

最终审计纠正了先前 `startupProfileFiles=0` 的歧义：两版 `app/src/main/startup-prof.txt` 均是早已跟踪的同一旧文件，14 条、721 字节，Git blob `77ba9010f8fb7e7b812f58915d0685fe8a3bbb7d`、工作区 SHA-256 `8dcab18fe142d2c57b9a1df3a1ccb2d2abddb17b53208bdcbb640964d366e5ae`，与各自 Git 原内容一致。普通版来自 2026-08-06 的 1.0.299 提交，FD 来自 2026-08-26 初始 FOSS 版；不是本轮候选残留，也未删除或重写。这 14 条都是原 27 条 baseline 规则的子集。

需要分开看源文件与构建输出：当前源树为旧 startup 源文件1份／14条，新增生成候选0份；最终只读全树检查未找到 `app/build/intermediates` 中的 `startup-prof.txt`。本机 AGP9.3.1 源码显示，startup 合并仅从 `baselineProfiles` 源目录读取，默认目录是 `src/main/baselineProfiles`，R8 接收 `MERGED_STARTUP_PROFILE`；旧文件位于 `src/main/startup-prof.txt`，不是该默认目录。保留的默认及后续 Runtime R8 日志有 startup 合并任务，但没有逐次归档的合并输入快照，本轮也未重新运行 Gradle 模型。因此不声称旧14条被作为 startup 布局输入消费；这14条同时存在于已核对的27条 baseline 中。此处只修报告和审计，没有为了填齐报告迁移文件或改变构建行为。

## 默认包可用性验证

最终 普通版 测试 APK SHA-256：`a736ee55daf7f20b70c5276936a6e42b532df41616e9eef17c2d2221c6c808ca`。

使用本任务原已保留的同包名、同签名 Release 合成库，从入口优化包覆盖到候选包，再覆盖到最终默认规则包。安装回执逐次核对设备包 SHA，未卸载或清应用数据。最终 F-Droid 包与本轮入口优化阶段验收的包逐字节一致。

- 原密码可认证，原记录密文与类别 ID 不变；已有第二条记录保留。
- 调用实际 R8 生产加密代码写入第三条合成记录，随后在不同 PID 中验证三条记录均可读。写入使用本地 instrumentation 的 SQL 夹具，不能替代完整编辑器交互验收。
- 两版各 4 项实际 R8 系统入口检查通过：自动填充选择器认证 / 取消、独立 `:ime` 解锁认证 / 取消。
- 最终默认包使用真实 UI 加密 ZIP 导出、系统文件选择器及解密恢复。测试准备阶段只删除本任务第二条合成记录，原记录和第三条保留；随后两版 UI 均明确成功恢复 1 条，最终在新 PID 中重新核对原密文、类别和三条记录可读。F-Droid 页面另明确显示导出 3 条，恢复时跳过 2 条、失败 0；普通页面只展示成功恢复 1 条，不把 F-Droid 的详细统计当成普通版 UI 观察。

普通版使用本轮 `monica_backup_20261115_161840.enc.zip`，F-Droid 使用 `monica_backup_20261115_163115.enc.zip`，未选用前一阶段旧备份。普通保存对话框返回时已自动锁定；仍生成可解密的新备份，后续实际恢复和新进程读取通过，所以没有依赖一次导出成功提示判断。FD 导入页直接是文件页面，本地驱动曾按普通版路径寻找不存在的“檔案”标签，前置断言在点击前拒绝；按实际页面继续后通过。这是驱动页面前提差异，未当作应用导入失败。

所有 R8 测试包使用隔离 applicationId 和同一个测试证书；保留实际 x86_64 产物、签名回执、mapping 和 Profile 哈希，不代表生产签名发布验收。普通版对照还通过本地 init 脚本固定 `BUILD_TIME`，减少无关构建输入差异；生产版本生成策略未因此改变。

## 失败与限制

根任务最终审计因旧报告将空的合并输出误写成“无 startup 文件”而拒绝通过。已保留修正前脚本/报告与原失败，修正为分别核对旧源文件、生成候选和观察到的输出；这是证据审计夹具缺陷，不是新增应用运行失败。后续 Runtime 的 Passkey/笔记报告没有 `startup=0` 声明，不需修改其测试结论。

首次普通候选 R8 构建 `profile-main-r8-build-01` 与 F-Droid 编译并行时，主机可用内存降到约 1 GiB；主动停止本任务普通 R8 进程，保留失败日志。后续改为串行构建并成功，未修改全局内存配置。

首次普通基准 `profile-main-old-partial-01` 在 runner 加载测试类时失败，尚未进入应用测量。原因是本地 Kotlin 基准目录未被 AGP 打包；改用 Kotlin source set 注册后 runner build03 成功，再完成全部成功测量。错误 runner、首次失败和后续回执保留，未把首次运行记为通过。

普通 Release Manifest 仍非 profileable。本地基准显式抑制 `EMULATOR,NOT-PROFILEABLE` 环境检查，故结果只作模拟器探索证据；不代表标准真机性能门槛通过。未验证物理设备、OEM、真实用户库、热限制条件或全部功能。没有确认稳定收益，所以本次候选规则没有默认启用，也没有承诺“零 bug”。

结构化统计、产物与 trace 校验见 [结果记录](baseline-profile-performance-328-result.json)。私有脚本、完整设备日志、APK、数据库和截图留在本地任务目录；本报告不附安装包或图片链接。

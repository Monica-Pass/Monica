# HOTP 生成后保持排序（2026-10-26）

## 复现与原因

使用生产 TotpViewModel、SecureItemRepository 与内存 Room 数据库复现。三个验证码使用默认 sortOrder=0，初始顺序为 [1,2,3]，第三项为 HOTP；调用 incrementHotpCounter 后变为 [3,1,2]。更新后的 sortOrder 仍为 0，updatedAt 正常推进，说明跳位来自 DAO 按修改时间倒序处理同排序值的条目，而不是计数器写入重置了排序字段。

修复前五项数据库回归中三项失败：默认顺序、收藏后的普通条目顺序，以及相同创建时间下修改数据的顺序。显式手动排序和其他类型按修改时间排序的回归已通过。基线 XML 保存在 `.codex-tasks/hotp-generation-order-20261026/raw/normal-baseline.xml`。

此前紧凑磁贴的几何测试在内存状态中推进计数器，没有接入真实 Room 排序查询，因此未覆盖本次跳位；本次补上生产 ViewModel 和持久化路径。

## 修复

验证码类型的两个列表查询复用稳定规则：收藏优先、手动 sortOrder、创建时间倒序、ID 正序。默认相同创建时间也有固定顺序。其他类型继续使用原有修改时间倒序。

incrementHotpCounter 继续保存新计数器并更新 updatedAt，保留同步和数据修订识别所需的修改时间；不通过冻结时间戳或重新写入整页排序来保持位置。已有手动排序值与收藏字段保留，搜索和分类在稳定列表上继续筛选。

普通版和 F-Droid 同步此查询及回归。保留此前 142dp 紧凑磁贴和底部“生成下一个”按钮；本次不更改页面布局、数据库结构、发布版本或历史说明。

## 验证

普通版和 F-Droid 应用及测试 APK 均已构建成功。两版各 16 项针对性单测通过，零失败、错误或跳过：HotpGenerationOrderTest（6）、AuthenticatorTileLayoutTest（5）、TotpTickerPerformanceGuardTest（3）、TotpSwipeSelectionRegressionGuardTest（2）。六项生产 ViewModel/Room 单测覆盖连续生成、手动排序、收藏优先、相同创建时间、非验证码修改时间排序，以及搜索与分类。

两版各 13/13 项设备回归通过：普通版 157.565 秒，F-Droid 123.992 秒。普通版构建耗时 27 分 23 秒；F-Droid 首轮因 JVM 原生内存分配失败退出，保留失败及崩溃日志后改用 6 GiB 堆内存单独重跑，19 分 1 秒构建成功。

两项新增设备回归让原生 TotpCodeCard 的按钮调用真实 ViewModel，写入内存 Room 并重新接收列表，检查验证码刷新、数据库顺序、实际行列位置、重新进入及已保存的手动顺序。与此前十一项紧凑布局回归一起执行。

连续生成四次（其中一次在重建 ViewModel 后）仍位于原来的行列，当前码刷新且卡片保持 142dp；卡片点击继续复制当前码而不增加计数器。已保存的手动顺序和 sortOrder 保持不变。紧凑布局回归覆盖 1.0／1.3／1.5 字号、完整按钮文字、生成与复制触摸隔离、多选禁用生成、隐藏后的显示、次码及列表规则，以及双向拖动。

`verify-results.ps1` 汇总校验通过，两版 DAO、新增单测及设备测试源码一致，`git diff --check` 通过。最终汇总保存于 `.codex-tasks/hotp-generation-order-20261026/raw/final-summary.json`，各版 16 项单测 XML 已归档至同目录的 `normal-unit/`、`fdroid-unit/`。

复用公共 Monica_Issue136_API_32（Android 32、x86_64），仅在没有其他 instrumentation 测试运行时执行。合成条目保存在内存数据库，测试结束关闭；不修改用户条目或设备字号。所有日志保存于 `.codex-tasks/hotp-generation-order-20261026/raw/`。中英文说明写入两版下一版未发布文档。

公共 AVD 在本任务开始前已运行，结束后保留，系统字号仍为 1.5。本次未修改版本配置、已发布发行说明、标签或 fastlane 历史；未发布或额外交付 APK。

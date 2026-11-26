# 应用内独立触觉反馈修复（1.0.331，未发布）

2026-11-26，普通版。修正此前要求系统触觉开关开启的行为：Monica 开关、滑块及预览按应用内总开关和 1／2 强度请求反馈，采用与 ImageToolbox 相同的公开 View 覆盖标志；应用不修改系统设置。本记录取代 [上一轮触觉兼容结论](haptic-strength-331.md) 中“必须遵循系统触觉开关”及仅检查请求返回值的验收范围。

## 已确认的问题

用户最终确认开启系统触觉后 Monica 才能震动，而 ImageToolbox 无需开启。源码存在系统 HAPTIC_FEEDBACK_ENABLED 前置拦截，且普通 TOUCH 振动也受系统该设置控制。ImageToolbox 的关键差异是 FLAG_IGNORE_GLOBAL_SETTING，此前参考时未保留它。

此外，修前实际服务测试连续两次失败：轻滑块 TEXT_HANDLE_MOVE 返回 REQUESTED，但 Android 记录 ignored_unsupported；强滑块 CLOCK_TICK 映射到不支持的 TEXTURE_TICK；旧轻触接口被宿主禁用状态拦截。这不能据此声称在 MuMu 复现了用户手机上全部预览失败；MuMu 修前开关和预览路径本身可以输出。

隔离用户中将系统触觉关闭并确认服务 TOUCH = OFF 后，对照试验显示：ImageToolbox 使用的 View 覆盖调用在轻／强档均有 finished 输出；直接 TOUCH、默认预定义振动和默认短脉冲均为 ignored_for_settings。未使用通知、闹钟或媒体用途冒充触觉，也未更改原用户的系统设置。

## 最终改动

- 移除应用层系统开关拦截及对应提示。19 份语言资源一起删除失效提示，无新增翻译。
- 轻档统一 CONTEXT_CLICK，强档 LONG_PRESS；滑块不再依赖本轮已复现不支持的 TEXT_HANDLE_MOVE／CLOCK_TICK。旧 performLightClick 复用同一出口。
- 请求期间启用宿主 View 反馈并在 finally 恢复，使用公开 FLAG_IGNORE_GLOBAL_SETTING。View 明确拒绝才尝试一次短振动，避免重复提交；应用关闭、控件禁用／取消、页面脱离和外部状态加载保持静默。
- 保留默认开启／强度 1、原偏好格式、滑块节流与松手提交。输入法独立反馈入口保持原逻辑并回归验证。未修改加密、数据库、恢复或同步路径。
- Android 文档将该标志标为 deprecated，并提示 Android 13 起可能由系统限制；本轮实测 API35 有效，不据此承诺所有 OEM 或未来 Android 版本都允许独立反馈。REQUESTED 仅代表提交，不能当作物理马达回执。

## 来源与许可

复核 [T8RIN/ImageToolbox](https://github.com/T8RIN/ImageToolbox) 提交 ab763ce76effd13ae3b8c7cfea37835eb9e0f8b9 的 EnhancedHapticFeedback.kt。本轮独立实现相同公开 API 策略，没有复制其源码；此前滑块改编的作者声明和 Apache-2.0 许可证继续保留。README 及应用内许可证来源说明同步，实际 APK 中的声明和许可证与源码字节一致。另按此前紧凑菜单变更纠正 README 仍称圆形实色按钮的旧描述。

## 本轮验证

主包／测试包／JVM 联合构建成功，186.732s。5 项调度策略与 3 项滑块节流单测全过；MuMu 实例 0、API35/x86_64、自有 full secondary user15、独立 Debug applicationId 上，24/24 设备方法首批全过，runner 19.809s。实际安装的主／测试 APK 哈希与构建产物一致，VIBRATE 权限已授予。

设备覆盖三组真实服务测试，以及 21 项已有 UI／设置／输入法回归。系统触觉 ON 与 OFF 各独立验证预览 1／2、真实开关、滑块进度、选择强度、应用关闭后静默和重新开启；每组有 7 条单次完成输出。另验证 5 种真实宿主入口，包括默认禁用 View 的旧轻触。合计每版 19 条服务完成输出；系统 OFF 组明确记录 TOUCH = OFF，应用未修改该设置。UI 事件计数测试仍采用记录宿主，但新增服务测试不拦截 View 或振动器调用。

其余回归覆盖设置保存与恢复、功能拓展返回、窄屏大字、开关整行／子开关避免重复、取消与禁用、外部状态静默、滚轮方向／减少动画、DPI 松手提交与重置、输入法按键。两版 29 个本轮共享路径字节一致；最终 app/src、kotpass/src 和 app/build.gradle 与构建冻结清单完全一致。F-Droid 元数据和 6 项脚本测试、两仓 diff 空白检查通过。

初始两次设备失败和后续中间方案全部保留。中间“直接 TOUCH 输出优先”方案在系统开启时通过，但用户补充独立控制要求后被替换；不以中间方案的测试替代最终结果。系统关闭的临时 API 对照探针源码只保存在本地任务目录，未留在生产／设备测试源码中。最终两类服务回归保留。

只验证 API35/x86_64/Debug 的软件调用与 Android 服务完成状态，MuMu 无物理马达，未确认用户真机手感、全部 OEM、API26–28 分支或 Release/R8。此次没有主界面设计变化，也没有生成新设计稿或将截图入库。

## 环境与交付

结束时只移除本任务 user15 和四个独立测试包，恢复原用户 0；原用户 0/10/11/12/16、正式 Monica 包元数据和 density 保持不变。专用 ADB15092 停止，MuMu 进程 51792 保持运行，不关闭或重启模拟器。无提交、推送、发布或 APK 交付。

主包 SHA-256：`d58efe8dac8f0cbda8aae2da52f8ccce1f54359f205d5bd2fc7f608f9468cabb`

测试包 SHA-256：`c7d81791f96e0b283e44e1afe55ef30d15623624386af9f097977297a6076dd8`

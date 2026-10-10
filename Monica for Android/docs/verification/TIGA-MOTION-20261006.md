# MDBX 档位动效验证（1.0.317）

日期：2026-10-06。范围：普通版及 F-Droid 的 Tiga 档位选择器。

## 交互变化

- 星光以不同相位缓慢改变明暗和大小，仅绘制在已填充侧，并裁剪到胶囊轨道内部。
- 手指拖动直接更新滑块的连续位置；松手从最后的指针位置平滑吸附最近档位。
- 点击轨道、档位标签或通过无障碍选择时，滑块约用 280ms 过渡到目标位置。
- 按住时显示光环并轻微放大滑块。跨档调用标准点击触感；同档移动、重复选择和吸附不重复调用。
- 读取当前触感设置；减少动画时星光静止、切档立即完成，拖动仍然跟手。后台停止星光循环。
- 保留四档含义、键盘和 RTL 支持；远端创建仍只有 Sky、Multi、Power 三档。

设计使用[本地 M3E Canvas 草图](../design/tiga-motion-317/README.md)，原始布局保存在该目录的 `canvas.json`。

## 最终验证

普通版及 F-Droid 最终 Debug 构建均成功，每版通过 2 项 JVM 测试和 16 项 API 32 设备测试，共 **36 项通过、0 失败、0 跳过**。重复运行未累计。两版选择器与设备测试源码哈希一致；本次修改的源码和发行说明通过定向 `git diff --check`。

设备复用公共 AVD `Monica_Issue136_API_32`，API 32 / x86_64。运行器逐个校验安装包 SHA-256，要求测试数量匹配、无失败、无跳过，并恢复原 Android 用户和屏幕超时设置。

每版的测试组成：

| 测试 | 数量 | 覆盖内容 |
| --- | ---: | --- |
| `MdbxTigaModeAvailabilityTest` | 2 | 本地四档、远端三档及原有档位含义 |
| `MdbxTigaSliderTest` | 13 | 连续拖动、反向拖动、点击中间帧、吸附、星光像素变化和空白区域、反馈开关、减少动画、外部状态恢复、RTL、键盘、纵向滚动、浅深主题和大字体 |
| `MdbxLayoutReachabilityTest`（选定方法） | 3 | 大字体表单提交可达、远端不提供 Glitter、本地 Glitter 表单使用密码和密钥文件 |

动效测试读取实际渲染像素，断言滑块能停在两档之间、同档内继续跟随手指、反向移动以及松手时存在中间帧。星光测试分别断言已填充侧发生像素变化、未填充侧不变，且没有静态暗星残留。

首次回归中反馈开关测试失败，单独重跑复现。已将选择回调及触感设置改为读取当前状态；两版最终回归确认关闭和重新开启即时生效、外部恢复当前档位不产生额外触感。原失败日志保留，未计入最终通过数量。

## 证据与复现

本地日志目录：工作区 `.codex-tasks/tiga-motion-317/`。

可随文档保存的[最终验证清单](TIGA-MOTION-20261006.json)包含两版的安装包及源码哈希、测试时间和数量。

- `main-final-build.log` / `fdroid-final-build.log`：最终构建与 JVM 测试。
- `main-final-motion.log` / `fdroid-final-motion.log`：最终设备回归。
- 同名 `.log.json`：安装包哈希、设备、时间和测试结果。
- `main-motion.log` / `main-haptic-repro.log`：修复前的失败与单独复现。

各版构建使用 JDK 17，执行：

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*MdbxTigaModeAvailabilityTest' -PincludeX86TestAbi --console=plain
```

设备回归运行器：`.codex-tasks/tiga-motion-317/run-android.ps1`，`ExpectedTests=16`，分别传入 `Edition=main` 和 `Edition=fdroid`。测试类为 `MdbxTigaSliderTest` 及 `MdbxLayoutReachabilityTest` 的 `allSixProductionFormsKeepSubmissionVisibleWithLargeText`、`remoteGlitterIsUnavailableWhileSkyMultiPowerRemainSelectable`、`glitterCreateUsesPasswordKeyAndAllowsExternalDirectoryWithoutExtraAuthorization`。

普通版最终测试包生成的[点击过渡预览](../design/tiga-motion-317/main/click-transition.gif)和[两档之间按住滑块](../design/tiga-motion-317/main/held-between-detents.png)已检查。GIF 来自受控测试时钟的截图序列，首尾有展示停留时间，不作为实际帧率或性能基准。

F-Droid 同样保留最终包的[点击过渡预览](../design/tiga-motion-317/fdroid/click-transition.gif)及[跟手截图](../design/tiga-motion-317/fdroid/held-between-detents.png)。普通版、F-Droid 和两个候选副本的 1.0.317 未发布说明已更新，F-Droid fastlane 24 中英文说明均未超过 500 字符。

虚拟机验证了触感调用、去重和设置响应；真实手机的马达手感尚未实测。

测试结束后已停止本任务启动的公共虚拟机，保留 AVD 注册、配置和数据盘。

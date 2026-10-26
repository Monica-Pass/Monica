# 卡包多选卡叠收起与排序（2026-10-25）

## 行为

- 多选卡叠标题右侧新增独立展开／收起按钮，48dp 点击区，与复选框和管理菜单分开。
- 收起后显示完整圆角的卡叠标题和选择计数，成员卡片从懒加载列表隐藏。点击箭头展开，不改变已选集合。
- 多选时长按收起的卡叠标题拖动，可在收起的卡叠之间调整整组顺序；未入叠单卡保留原有拖动。
- 排序即时更新可见列表，并沿用 SecureItem.sortOrder 批量保存；重新打开页面保留顺序。退出多选后，下次多选恢复展开，便于逐张选择。
- 排序不写 WalletStack.memberIds 或 coverId，不修改卡片内容、存储位置或所属数据库。筛选只移动当前可见卡片，隐藏卡片保留全局排序槽位。

## 设计

本地 M3E Canvas 项目标识：wallet-selection-collapse-20261025。可编辑 JSON 与完整本地分享链接保存在本地 docs/design/wallet-selection-collapse-20261025/。
已检查单页实际预览，验证箭头能够从展开状态导航到收起状态；沿用现有动态主题、分组圆角、真实卡面以及三态复选框。设计文件和截图仅保存在本地，不加入版本库。

## 验证

普通版及 F-Droid 各 25 项针对性单测通过：WalletSelectionStackOrderTest（4）、WalletStackProjectionTest（15）、CardWalletOrderTest（3）、CardWalletSwipeActionsGuardTest（3）。覆盖双向组块移动、异步投影前即时排序、筛选隐藏项保护、部分选择、成员与封面保持，以及原有单卡和滑动操作。

普通版与 F-Droid 应用 APK 及测试 APK 均已构建。公共模拟器 `Monica_Issue136_API_32` 上两版均通过 `WalletReorderScreenTest` 与 `WalletSelectionSectionTest`，共 6/6；覆盖独立卡排序、收起卡叠双向拖动、重新进入保序、部分选择、筛选和大字体布局。首次设备测试包编译发现测试坐标 API 写法错误，已修正并重跑通过。

公共测试虚拟机 Monica_Issue136_API_32 / emulator-5554 已在本任务开始前运行，直接复用。测试使用内存数据库与保留 ID 的示例卡片，结束后清理本次创建的卡叠并恢复原筛选设置。

两版中英文说明随 1.0.322 写入各自根目录发行说明，F-Droid 摘要写入 fastlane 29.txt；已发布说明、标签和 28.txt 保持原样。不发布、不复制或交付 APK。

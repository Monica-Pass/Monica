# Monica for Android 1.0.322

> 未发布 / Unreleased

## 中文

- 修复部分笔记内容显示为 `MDK|…` 乱码的问题。附近设备同步接收的笔记，以及从数据库导出包恢复的笔记，曾以加密形式写入本地，笔记列表与详情直接显示了密文。现在接收和恢复的笔记保持笔记原有的存储格式；解锁后会自动将已受影响的笔记恢复为原文，包括已在编辑器中再次保存过的笔记，不改变修改时间和同步归属。

- 修复 HOTP 点击“生成下一个”后跳到列表最前面的问题。验证码顺序保留收藏优先级和手动排序，默认顺序不再随计数器更新而变化；计数器与同步修改时间仍正常更新。

- 修复 HOTP“生成下一个”按钮撑高磁贴的问题。所有验证码磁贴保留原有紧凑尺寸，生成按钮复用次码位置，减少内边距并适配文字大小；放大字号仍完整显示验证码和按钮，生成与复制互不误触。

- 卡包多选列表中的卡叠新增独立展开／收起按钮；收起后可长按拖动整组调整卡叠顺序，重新进入页面仍保留排序。展开与收起保留逐张选择和选择计数，排序不改变卡叠成员顺序或封面。

- 优化密码列表加载与滚动：减少滚动期间的页面重组，复用分类数据订阅，并避免相同数据快照重复生成脱敏列表；保留原有回顶、快速定位、返回位置、搜索、分组和数据处理规则。

- 新增独立 Root 授权，可替代 Shizuku 使用增强无障碍恢复和设置默认密码管理器；Root 通道无需安装或运行 Shizuku。
- 授权方式可切换，提供授权状态、重新授权与停用入口，命令失败后仍可停用 Root。只有主动确认后才请求 Root；无 Root、拒绝或超时会提示，不会自动切换授权方式。停用或切换方式后，排队请求和迟到的授权结果不会重新启用 Root。
- 无障碍恢复仍需手动启用后台保护和增强恢复，仅重连已开启的 Monica 服务；默认密码管理器保留配置备份、写入校验和恢复功能，仍需 Android 14 或更新版本。

## English

- Fixed some notes showing garbled `MDK|…` text. Notes received through nearby device sync, or restored from a database export archive, were stored locally in encrypted form, so the note list and details displayed the ciphertext. Received and restored notes now keep the regular note storage format, and affected notes, including ones saved again from the editor, are restored to their original text after unlocking without changing modification times or sync ownership.

- Fixed HOTP moving to the front of the list after Generate Next. Authenticator ordering retains favorites and manual ranks, with a stable default order across counter updates. Counter values and modification timestamps still update normally for synchronization.

- Fixed the HOTP Generate Next button increasing tile height. All authenticator tiles retain their compact size, with the action using the next-code footer, reduced padding and text that fits the available space. Codes and labels remain complete at larger font sizes, with separate generation and copy actions.

- Added separate expand/collapse controls to card stacks in wallet multiselect. Long-press a collapsed stack to reorder stacks, with the order retained when reopening the wallet. Expansion preserves individual selections and counts; dragging keeps stack membership, internal card order and covers intact.

- Optimized password-list loading and scrolling by reducing page recompositions during scrolling, sharing category subscriptions, and avoiding repeated metadata copies for unchanged snapshots. Existing back-to-top, fast-scroll, position restoration, search, grouping, and data-handling behavior are preserved.

- Added direct Root authorization as an alternative to Shizuku for enhanced accessibility recovery and setting the default password manager. The Root backend works without Shizuku installed or running.
- Choose the authorization method, view its status, authorize again, or disable Root, including after a failed command. Root is requested only after explicit confirmation; unavailable access, denial, and timeouts are reported without switching backends automatically. Disabling Root or switching backends invalidates queued requests and late authorization results.
- Recovery still requires background protection and enhanced recovery to be enabled, and only reconnects an already enabled Monica service. Default manager changes retain configuration backups, verification, and restoration, and still require Android 14 or later.

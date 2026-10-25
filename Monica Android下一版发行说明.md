# Monica Android 下一版（未发布，版本号待定）

## 中文

- 优化密码列表加载与滚动：减少滚动期间的页面重组，复用分类数据订阅，并避免相同数据快照重复生成脱敏列表；保留原有回顶、快速定位、返回位置、搜索、分组和数据处理规则。

- 新增独立 Root 授权，可替代 Shizuku 使用增强无障碍恢复和设置默认密码管理器；Root 通道无需安装或运行 Shizuku。
- 授权方式可切换，提供授权状态、重新授权与停用入口，命令失败后仍可停用 Root。只有主动确认后才请求 Root；无 Root、拒绝或超时会提示，不会自动切换授权方式。停用或切换方式后，排队请求和迟到的授权结果不会重新启用 Root。
- 无障碍恢复仍需手动启用后台保护和增强恢复，仅重连已开启的 Monica 服务；默认密码管理器保留配置备份、写入校验和恢复功能，仍需 Android 14 或更新版本。

## English

- Optimized password-list loading and scrolling by reducing page recompositions during scrolling, sharing category subscriptions, and avoiding repeated metadata copies for unchanged snapshots. Existing back-to-top, fast-scroll, position restoration, search, grouping, and data-handling behavior are preserved.

- Added direct Root authorization as an alternative to Shizuku for enhanced accessibility recovery and setting the default password manager. The Root backend works without Shizuku installed or running.
- Choose the authorization method, view its status, authorize again, or disable Root, including after a failed command. Root is requested only after explicit confirmation; unavailable access, denial, and timeouts are reported without switching backends automatically. Disabling Root or switching backends invalidates queued requests and late authorization results.
- Recovery still requires background protection and enhanced recovery to be enabled, and only reconnects an already enabled Monica service. Default manager changes retain configuration backups, verification, and restoration, and still require Android 14 or later.

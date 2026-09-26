# 系统通行密钥图标验证

2026-09-26，主版 `1.0.314-26092612-05`。

用户反馈系统保存、登录选择器里的 Monica 图标像通用钥匙，浅色背景下几乎不可见。使用已有 `Pixel_Fold_API_35`（Android 15，x86_64，运行 ARM64 主版）打开真正的 Android Credential Manager 选择器；公共 API 32 虚拟机不支持 Android 14+ Credential Provider API。

修改前，登录条目稳定显示白色通用钥匙；保存确认页的条目使用默认通行密钥图标。服务标题处的 Monica 彩色应用图标正常，排除了应用图标资源本身不可用。`CreateEntry` 未指定图标，`PublicKeyCredentialEntry` 使用依赖宿主主题的 `ic_passkey`。现在两个条目都通过 `Icon.createWithResource` 提供现有彩色自适应应用图标，服务名称和保存位置前缀使用 Monica Pass。

| 验证项 | 结果 |
| --- | --- |
| 主版 `:app:assembleDebug` | 成功，1 分 16 秒；覆盖安装最终 APK 成功 |
| 浅色登录选择器 | 条目和服务标题均显示彩色 Monica 图标，服务名为 Monica Pass |
| 浅色保存确认页 | 条目使用彩色应用图标，账号信息保留 |
| 保存位置列表 | Monica Pass 名称、彩色图标及网站名称正常 |
| 深色登录选择器 | 彩色图标清晰，保留同一条目的显示信息 |
| 深色保存确认页 | 彩色图标清晰，账号信息保留 |

使用本地临时调用程序发起真实框架请求，合成账号为 `alice@example.test`，RP 为 `example.com`。在实际 Room 数据库中加入一条合成凭据供生产服务查询，不访问任何线上账户。截图记录了系统返回的选择器；没有把自绘模拟页面作为系统验证结果。

[修改前后对比](../design/passkey-provider-branding-native-comparison.png)与[完整截图、可编辑 M3E 草图](../design/passkey-provider-branding-m3e.md)。草图在代码修改前创建并检查。原始截图、界面 XML、临时调用程序和构建日志保存在主仓库 `.codex-tasks/20260926-passkey-provider-branding/raw/`。

本次改动限于图标与名称，没有新增权限、改动凭据数据格式或认证逻辑；未重新执行此前数据库和密码学测试。验证覆盖上述 API 35 系统界面，未持有反馈用户的厂商设备。F-Droid 后续构建和实测已于 2026-09-26 完成：实际 R8 Release 在 API 36 16KB 系统创建、登录并独立验签通过，浅色保存/登录和深色登录图标已检查。[F-Droid 完整验证与旧 API 35 镜像限制](fdroid-1.0.314-verification.md)。

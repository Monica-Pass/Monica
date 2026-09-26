# GPG Release 生成失败修复 · 2026-09-20

## 根因与修改

新建页与生成器共用 GpgKeyGenerator。Debug 未压缩，RSA 正常；Release 启用 R8，Bouncy Castle 以类名加载的算法 Mappings 和 JCA 实现未受到保护，生成时抛出 `NoSuchAlgorithmException: no such algorithm: RSA for provider BC`，界面只显示生成失败。

仅新增 `org.bouncycastle.jcajce.provider.**` 和 `org.bouncycastle.jce.provider.**` 两组 keep 规则。未修改生成算法、格式、UI、数据库或全局安全提供器。

## 验证

- 生产编译后的 GpgKeyGenerator 经过 R8；删除新增 keep 规则的反向对照稳定报 RSA 缺失，恢复规则后通过。
- 使用实际依赖：bcprov/bcpg/bcutil 1.78.1、Kotlin stdlib 2.1.20、AGP builder 8.7.3 内置 R8。
- 五组压缩回归在 JVM 与公共 API 32 x86_64 虚拟机均通过：ho / 你好、RSA 3072、空邮箱、有／无口令，以及带口令 RSA 4096；每组检查私钥往返、公钥单独导入及指纹一致。
- Debug 设备密码学 3 项、UI／存储 11 项通过；包含真实签名验签、加密解密、错误口令拒绝、KDBX 写入关闭后重开。
- 完整 `assembleRelease` 通过（18 分 51 秒），启用 R8 与资源裁剪。仅临时 init 脚本使用 debug 签名以便本地安装，未改变生产签名配置。构建有 Kotlin 元数据兼容警告，无失败。
- 同一公共 AVD 临时用户 11 上实际安装 Release：新建 ho / 空邮箱 / RSA 3072 / 365 天 / 有口令，成功生成并收藏保存；强制停止后解锁重新打开，指纹一致。
- Release 生成器：Generator fixture / 空邮箱 / RSA 3072 / 365 天 / 无口令，成功生成，保存到密码库并重新打开，指纹一致。
- 后续于 2026-09-26 完成 F-Droid 通用 R8 Release 构建及 API 36 实测：带口令 RSA 3072 生成、保存、进程重开指纹一致。见 [F-Droid 验证](testing/fdroid-1.0.314-verification.md)。

## 证据

- [Release 生成器成功截图](gpg-release-regression-device/generator-result.png)
- [生成器保存后的条目](gpg-release-regression-device/saved-generator-entry.png)
- [重启读取 UI 树](gpg-release-regression-device/reopened-entry.xml)
- [压缩回归日志](gpg-release-regression-device/r8-device.log)
- [删除规则的失败对照](gpg-release-regression-device/r8-negative.log)
- [完整 Release 构建](gpg-release-regression-device/release-build.log)
- [可重复运行的脚本](../scripts/gpg-release-regression/README.md)

## 范围与尚存问题

这次修复正式包 GPG 生成失败。测试另外发现：在 320dp、1.5 倍字号下，生成器内部打开的 GPG 编辑页仍保留外层导航及刷新 FAB，保存按钮大部分被遮挡，仅小部分可点击。保存动作实际成功，但界面可达性问题尚未修复，不应宣称整个 GPG 功能已经完全验收。后续应修复该嵌套页面的导航与 FAB 所有权，并增加真实宿主页面测试。

此前 Debug 测试不足以发现此回归；以后必须保留压缩回归和完整 Release 冒烟测试。发行说明继续以新增 GPG 功能统一介绍，避免将未发布功能的内部修复反复列为用户更新项。

后续状态：上述生成器内嵌 GPG 编辑页保存按钮遮挡已在[生成结果菜单改动](generator-result-menus.md)中改为独立全屏窗口，并通过保存按钮可见和至少 48dp 高的设备断言。

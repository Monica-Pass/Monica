# Passkey 初始读取性能验证 — 1.0.328

Passkey 认证入口把初始 Room 查询及 credential ID 兼容遍历移到 IO，查询期间主线程可以继续响应。精确 recordId 选择、规范化兼容、平台请求/RP/challenge 检查、签名前重读和签名写入保持原逻辑。生产源码两版逐字节一致；从 `authenticateWithPasskey` 到文件末尾与修改前逐字一致。

本报告对应 普通版 未发布 1.0.328 / code 12。本轮采用 MuMu / Android35 / x86_64 的隔离合成包，未操作真实用户密码库。本版最终 Debug 与本轮限定范围的 R8 验收已通过。

当前实际混淆 R8 包已构建并冻结，SHA-256：`cfd465e7d085575e1280f77cf2dd084ed3a65540315cf7f07ced18fbbbca6858`。

两版共用的本轮 JVM 批次各 129 项：128 通过、0 失败、0 错误、1 项 assumption 跳过。跳过项 `DeviceNativePasskeyPreparationTest` 要求 `MONICA_MDBX_HOST_LIBRARY` 与 Android 绑定匹配，当前未配置，未计作通过。两份报告引用同一批 JVM 结果，不能相加成两轮独立测试。

本版最终 R8 构建耗时 357.723 秒，实际执行 minifyReleaseWithR8；隔离初始化脚本只改测试 applicationId 与本地 Debug 签名，保留 Release 混淆。同包同签名覆盖安装前验证旧三条记录，安装后原密码可读取旧记录，旧密文与分类ID保持；新增第四条 Runtime new record 后，写入 PID 61298、强制停止后的冷读 PID 61380 不同，四条均通过。这里通过真实生产密码加解密和 repository 浏览配合 SQL 夹具读写，不等于完整编辑页面保存验收。

真实导出、文件选择和确认恢复页面通过，页面显示成功恢复1条，随后探针验证四条记录完整可读。恢复前只移除自有 Performance new record，另三条保留。 自动填充选择器认证/取消、输入法认证/取消四个相关入口均通过：实际密码UI、选择器显示原记录、独立 :ime 进程认证结果广播及取消不暴露记录。这些从目标上下文直接启动真实 Activity，不等于完整系统自动填充 Dataset 会话或键盘实际插入字段。 两份专项报告引用这同一轮 R8 通用回归，不重复累加为独立测试。

普通版 R8 构建时 app/build.gradle SHA 为 `303ab9526168fb16c6c7a095d8624a9196516833e0693867b06a4743b55fdd5f`；之后仅追加可选 Debug 稳定构建标识，当前 SHA 为 `60be72e90695a575945c71bc30f894b69d70b1e08f55bedac960b8c0f4aa1a5d`。该补丁仅在 stableDebugBuild=true 且 Debug 变体启用，Release/profiling 不受影响；没有把当前配置哈希冒充 R8 构建输入。

## 实现边界与实测

查询结果先留在局部，只有主线程处于 RESUMED、同锁代、请求未撤销且未结束才交给认证页面；Back、Stop、Destroy 撤销旧查询，配置重建由新 Activity 重新读取。普通查询异常返回受控 credential error；安全存储异常继续使用已有阻塞/恢复页面。

15 秒是逻辑截止时间，不能硬中断忽略取消的底层 DAO；超时后即使它迟到返回也不能发布记录。本轮慢 DAO 用 latch 包住真实 Room 查询，旧普通和 FD 主线程心跳在 1000 ms 窗口内均未响应且 `query_on_main=true`；新普通观测 5 ms / 9 ms 且 `query_on_main=false`。这是确定性阻塞对照，不是承诺用户登录提速倍数。

最终普通 `passkey-main-green-02` 12/12 通过，runner 288.967 秒；FD `passkey-fd-green-01` 12/12 通过，runner 334.771 秒。分别使用普通 `runtime-main-apk-03`、FD `runtime-fd-apk-02` 的测试包，原始运行回执记录主包 SHA，测试包对应由冻结回执和实际安装顺序核对；没有拿后续笔记测试包代替本轮 Passkey 产物。完整 app/test/source 哈希在 [结果记录](passkey-loading-performance-328-result.json)。最终通过不覆盖先前失败记录，也不称首次整批全绿。

12 项覆盖查询响应、Back/Home、锁代、重建、规范化精确选择、删除/重复项拒绝、平台 challenge 不匹配、DAO 失败、逻辑超时，以及显示后删除在签名前重读被拒绝。最后一项反射调用只做负向拒绝验证，不代表完成真实密码/生物认证或成功签名。

## 最终 R8 真实签名与取消

另用本版最终 R8 包执行公开框架 GetCredentialRequest、目标 UID PendingIntent 与真实 PasskeyAuthActivity，主密码页面认证成功后获得平台结果。随机 P-256 凭据由生产 protectForStorage 保护，探针独立验证 ES256 签名、credential/rawId/userHandle、challenge/type、原生调用包与签名 origin、RP hash、UP/UV 及 wire signCount=0。平台结果仅一次，usage 仅加1。成功场景驱动耗时 1.834 秒。

取消场景也实际进入密码页面并填写后取消，无 assertion、usage 保持0、lastUsed 不变，驱动耗时 1.413 秒；日志 genuinePasswordUi=false/independentEs256=false 按取消阶段定义，不表示取消场景成功验签。两阶段之后再次反射调用签名只检查迟到完成无效，并非实际重复生物回调。清理仅移除自有随机凭据与保护材料，前后旧 password/passkey 行哈希保持。完整 probe/APK/mapping/source 绑定和原始结果在结果 JSON。

这是真实生产密码页面与签名流程的原生合成调用，不是浏览器真实网站、DAL、真实生物认证或 OEM 验证。

## 首次失败与诊断

普通首次联合 15 项通过 12、失败 3：Passkey Home 用例的 Activity 已结束，但测试调用方 Bootstrap 在后台未收到结果；随后显式恢复测试调用方到前台，保留取消结果断言。可见性用例最初要求横屏中被裁剪的 username；诊断确认原页面显示账号标题、RP 和认证按钮，夹具改为唯一且可见的账号显示名，并保留精确 row、decoy 排除和无写入断言。本轮未修改生产布局，也不声称修复了既有横屏裁剪。

第三项属于笔记锁定测试的内部树断言，见同轮笔记报告。普通 diagnostic02 仍有 2 项失败，diagnostic03 的 Passkey 可见断言通过而笔记内部树断言仍失败；全部原始失败保留。

第一次普通构建 `runtime-main-build-01` 在测试编译失败，原夹具使用工程未依赖的 UiAutomator；改为已有平台 UiAutomation 后重新构建通过，没有因此放宽生产认证或密钥规则。真实 R8 成功签名/取消与覆盖升级已通过，备份和相关入口以本报告所列阶段状态为准。未验证真机/OEM/用户库，不承诺零缺陷。

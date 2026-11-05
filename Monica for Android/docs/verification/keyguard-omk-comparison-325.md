# Keyguard 与 Monica 的真实 OMK 对比（2026-11-06）

本次从系统 Keystore 切入真实 OMK 后，Keyguard 没有观察到应用闪退，但自动重置了部分安全存储并回到“Create a vault”，原应用密码的解锁入口不再可用。Monica 通过原密码与二次确认恢复，原记录可读，恢复后和再次冷启动均能保存新记录。此结果不能概括为所有 OMK 环境或 Keyguard 永远不会闪退，也不能说 Keyguard 删除了所有数据库或数据永久不可恢复。

## 环境与基线

复用已有专用 MuMu 克隆1、Android15/API35/x86_64，KernelSU与第三方 ITxiao6666/OhMyKeymint 1.3.5、ba6f300 本地x86_64构建；TEESimulator保持禁用，没有更换keybox、root_kek_seed或重装模块。本轮新建测试用户13，保留原0/10/11/12及实例0。官方 Keyguard r20261103/3.7.0 APK，SHA256 `11e7578a2f5fc12d28d8b9897dd555c01a4d47e92e956e89f5c53f2f3893f628`；发布tag提交 `ebb23ce047cb7e897a561bd46456f860887917d8`，八个已审阅安全/认证文件与此前固定HEAD一致。

Monica 使用实例中已有的普通版 `1.0.325-26110512-28` 主/测试包，仅为新用户install-existing，没有替换原用户安装版本；主包SHA256 `1ecaae702471c98905d824f5b4e177bcf151965adac9c506129ac8d14f9bc18a`。本轮没有修改Monica加密生产代码，也没有将动画修复的新安装包冒充此次OMK测试包。

在独立localhost Vaultwarden1.37.5、新数据目录创建一份合成账号/RSA密钥和一条加密登录记录。Keyguard真实设置本地应用密码、连接自托管账号、同步并点眼睛读取密码。移除唯一adb reverse18407后，停止进程、冷启动、输入原应用密码，再次点眼睛得到同一个合成密码；确认本地离线基线成立。Monica现有夹具建立一条MDK加密合成记录并通过真实主界面登录。

只通过模块API给原完整scope追加 `com.artemchep.keyguard@13` 和 `takagi.ru.monica@13`。两应用实际UID分别1310062/1310058；切换前后GetKeyEntry日志分别证明System与Omk，不能仅凭scope配置判定接管成功。

## 实际结果

| 检查 | Keyguard 3.7.0 | Monica 325 |
|---|---|---|
| 切换前离线原记录 | 原应用密码解锁并显示原密码 | 合成原库实际主密码登录及原记录验证通过 |
| 切入OMK | 两次冷进程显示创建库页，没有原Unlock入口 | 真实恢复按钮/二次确认/原密码，随后正常登录 |
| 原安全材料 | fingerprint/nav_stack文件删除，共享keyset、master_key及settings改变 | 原monica_secure_prefs和原恢复快照SHA不变，另建恢复存储 |
| 原数据库 | database_v2为352256字节，SHA不变；database_exposed亦不变 | 原行密文与密码断言一致，恢复后新行与冷启动新行通过 |
| 再次冷启动 | 第三个进程无UI探针观察10秒稳定、无fatal/ANR | 独立PID正常登录并验证三条合成行 |

Keyguard首进程26147日志报告 `stage=artifact_inventory undecryptable`，库存含master_key/nav_stack/settings/fingerprint，`masterKeyPresent=false`，之后`state=ready`。这与缺失旧Keystore主密钥后清理安全存储再初始化的源码路径一致。没有点击“Create a vault”、删除应用数据或重新登录服务器来掩盖原离线解锁入口丢失；没有验证后续重新登录/重建/恢复能否找回所有内容。

Monica三次夹具方法seedOriginalVault、reopenOriginalVault、verifyAfterColdRestart均通过；私有marker明确usedConfirmedRecovery=true、恢复PID28381→冷PID32405、原行1/恢复新行2/冷启动新行3。登录与恢复是实际UI，记录校验/写入使用已有DAO夹具，不冒充全部记录类型或所有编辑页面的端到端验证。附三次完整runner回执和用时。

## 工具失败与限制

MuMu的uiautomator命令写出完整的新XML后，部分进程在Shutdown thread发生SIGSEGV/退出139。首切换的26165由main entry与tombstone明确识别为uiautomator，不是Keyguard26147。最初全窗口“无fatal”断言因此失败，保留原记录；没有简单忽略它，另做完全不启动UI探针的冷启动：PID31900持续10秒、标记窗口无fatal/ANR、启动状态COLD且Status ok。两次创建库页面由新XML及焦点核对；黑屏截图仅作工具限制记录，不算视觉验收。输入驱动曾因多余Back退回登录前页面，后按实际页面重新输入成功，不计为Keyguard登录故障。

仅合成数据、第三方OMK本地x86_64与MuMuAPI35；不是官方ARM64包、真实物理TEE/keybox更换、Android16/OEM真机或所有Keystore损坏方式。先前仅源码审阅不能证明运行行为，本报告补充了指定版本的实际证据。

## 可参考的部分与收尾

可以参考Keyguard的异常分类与共享初始化协调；不能直接把它的自动清除安全配置策略作为Monica保留本地数据的恢复方案。“应用能重新打开”和“原记录仍能离线解锁”在本次测试中不同。Monica继续使用原密码、独立恢复材料及用户确认的恢复路径。

私有前后应用tar及合成服务数据留本地，原冷克隆备份保留。恢复原完整OMK scope、原用户0和adbd2000，只移除已核对名称的自有用户13；首次立即删除未成功，确认已停止后重试成功。停自启克隆1、自有ADB15040及核对PID/数据目录的Vaultwarden服务；实例0保持原停止状态，0/10/11/12、克隆数据盘与公共AVD均保留。未操作真实账号，未提交、推送、发布或交付APK。

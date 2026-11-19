# HyperOS 解锁复查（2026-11-20）

结论：仍未复现报告者的 HyperOS 故障，不能认定是 NFC、指纹硬件或密码库损坏。此次不改认证、KDF、密钥或系统安全设置。

## 新增核对

1. 对照真实 V1.0.326、V1.0.327 标签和当前源码，327 不只增加 NFC，也修改了主密码验证、旧密码保护升级与本机恢复协议。8 个相关文件的原文、缺失项及 SHA 留在 source-receipt.json / sources。标签差异不能单独作为因果证据。
2. NFC 控制器在 Activity 创建时同步取得 adapter；主界面 host 仅在认证成功且离开认证路由后授权 reader。此前“密码验证期间不读 NFC”不能排除认证成功后的界面切换被服务阻塞。
3. 当前 updateReader 在调用线程同步读取 isEnabled、调用 enableReaderMode / disableReaderMode。isEnabled 位于 runCatching 之外；enable 抛异常之前就写 enabled，后续可能不重试。这些是代码确认的风险，不是该用户 ROM 已发生的事实。
4. 关闭 NFC 时仍会读 isEnabled，随后可能调用 disableReaderMode。因此关闭开关可作对照，但失败不能单独排除系统服务阻塞。
5. 生物识别成功回调在主线程；随后 canProceedAfterBiometricAuth 直接执行 unlockVaultWithBiometric 和访问状态检查，冷缓存时可能到 Keystore。该调用链与密码 worker 的有界等待不同。未执行真实指纹、OEM Keystore 或 HyperOS 服务注入。

## 实际反馈回路

service-probe.py 从当前工作树原样抽取 reader / 提交按钮函数，并编译原 BoundedSecureDataRead；平台、界面状态及 SecurityManager 为明确标记的合成替身。11 个检查全部通过，6.882 秒：原 8 个行为检查加 3 个 latch 探针，分别在 NFC 状态、启用、已关闭 NFC 后的停用调用阻塞，断言调用线程在释放前不能返回、释放后可继续。它证明同步调用行为，不是 Android 主线程 ANR 或 HyperOS 复现。原 327 探针结果保留，未覆盖或改写。

MuMu API35/x86_64 使用本任务隔离 Debug 包运行真实 Android 密码协议、旧凭据升级、确认弹窗及有界读取测试。普通版 34/34，runner 92.190 秒；包括旧/新 KDF、原密文和 MDK 保留、错误密码、未知配置、取消/锁定/过期写入、慢验证 UI 响应及迟到结果拒绝。MasterPasswordFallbackUiTest 是密码确认组件测试，不是真实指纹提示，也不是 HyperOS 完整主入口复现。F-Droid 同一组 34/34，runner 104.617 秒。

## 仍缺的现场证据

已向用户询问报告者机型、完整构建号与关闭 NFC 后的结果，尚未收到。原会话的日志崩溃进程是 ards.nfc_reader，没有 Monica 认证栈；不能据此定位本应用故障。

需要一次故障时间对应的 Monica 主/系统/crash 日志；若界面完全不响应，还需主线程/ANR 栈。应区分“验证未完成”“验证成功后导航未完成”“重新输入是否恢复按钮”。现有 SDK/模拟器不提供该 HyperOS ROM，未以普通模拟器的通过结果冒充其修复验收。

未新增生产调试日志、未操作用户数据库、未提交/推送/发布。时间线 Release 升级验证与本调查分别记录。

附加 NFC UI 生命周期用例首次运行 5/5 在 @Before 被保护条件拒绝：旧夹具要求非 Owner 的专用 Android 用户及 legacyVaultwardenUser 参数；本轮使用 Owner 下隔离包，不满足该夹具。该首轮未运行用例主体，不计作功能通过，原日志保留。随后创建本任务专用合成用户13并传入原夹具要求的参数，普通和F-Droid各5/5通过，runner15.249秒/14.176秒；未改断言。测试覆盖锁定拒绝旧结果、后台撤销、锁定标签意图去敏、温启动等编辑器恢复与host卸载。使用合成Tag和卡数据，没有实体NFC或HyperOS服务。恢复Owner后首次移除用户失败，重新确认停止并移除成功，原0/10/11/12用户逐字核对一致。

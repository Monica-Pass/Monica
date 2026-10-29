# 物理 FIDO2 安全密钥验证（1.0.323，未发布）

日期：2026-10-30。

## 实现和数据边界

现有 Passkey 管理页右下角新增“物理密钥”FAB，没有新增底栏项。Monica Compose 管理界面通过 USB HID 或 NFC ISO-DEP 显示 GetInfo 返回的协议、AAGUID、固件、PIN 和可发现凭据能力，提供设置/更改 PIN 和只读浏览凭据。硬件选项通过现有 CredentialProvider 服务参与 Android 14+ 的注册和登录；管理页面可在 Android 26+ 使用。

创建和登录读取系统附加的最终 Provider 请求，不以页面 extras 中的 RP 或请求 JSON 作为授权。委托浏览器须通过已有签名白名单；HTTPS 来源须与可注册 RP 域匹配，不能是公共后缀。原生应用须通过 HTTPS Digital Asset Links 的包名、签名及关联关系验证，拒绝重定向。可信浏览器提供的 clientDataHash 原样用于 CTAP，不伪造 clientDataJSON。不支持的扩展和 enterprise attestation 在发送 makeCredential 前拒绝。

标准 FIDO2 在设备内生成新凭据，不能导入 Monica 已有软件私钥，也不提供离线输入 RP/用户的写入表单。硬件私钥、PIN、PIN token 和浏览到的凭据不写入 Room、KDBX/MDBX 或日志；PIN 草稿不保存状态，离开页面清空，token/共享密钥字节在使用后清零。管理活动不可外部直接启动，禁止屏幕捕获。取消、断开与生命周期停止关闭连接，旧操作不得回写新连接的状态。没有设备重置或凭据删除功能。

USB 仅选择 FIDO usage page 的 HID 接口，不抢占 OTP/键盘接口；使用 UsbRequest 传输、CTAPHID 初始化 nonce/能力校验、分片、序列及尺寸校验。NFC 使用短 APDU 分包、ISO GET RESPONSE 和 CTAP keepalive 响应。I/O 采用有界超时；异常/取消关闭连接。PIN 协议 1 与 2 各自采用规范的 ECDH、密钥派生、CBC 和认证参数长度；不对错误 PIN 自动重试，不自动降级 token 权限。

连接建立同样检查操作轮次，并只关闭本次打开的连接；断开后取消待处理 USB 授权。USB/NFC 工厂处理切回主线程时的协程取消，避免已经打开但返回值被取消丢弃的资源泄漏。快速断开并重连时，旧任务不得替换或关闭新连接。

## 验证范围

最终测试按以下范围执行：HardwareProtocolTest 15 项、HardwareTransportTest 7 项、HardwareKeyUiTest 5 项（公共 API32）；HardwareProviderTest 3 项（现有 Pixel_Fold_API_35）；原有 PasskeyScanMenuTest 单独执行 1 项，避免混合 Activity 生命周期干扰。软件回归为 PasskeyGetRequestPolicyTest 6 项、PasskeyCredentialDiscoveryPolicyTest 6 项、PasskeyUserVerificationGuardTest 1 项。

| 版本 | 最终主程序／测试包构建 | API32：27 项 | API35：3 项 | 原有扫码菜单：1 项 | 软件单元：13 项 |
| --- | --- | --- | --- | --- | --- |
| 普通版 | 通过，3m 9s | 通过，34.457s | 通过，4.232s | 通过，15.624s | 通过 |
| F-Droid | 通过，8m 32s（含单元回归） | 通过，28.307s | 通过，5.349s | 通过，15.211s | 通过 |

两版最终各 30 项硬件相关设备测试、13 项软件单元回归和 1 项原有扫码菜单回归通过。各版最终设备日志保存在本文旁的 hardware-passkey-323-api32.txt、hardware-passkey-323-api35.txt 和 hardware-passkey-323-scan-menu.txt。

合成 CTAP peer 使用独立设备密钥生成真实 ECDSA attestation/assertion，并由测试验证签名和返回 SPKI；PIN 协议 2 的 peer 用 BouncyCastle HKDF 独立派生密钥。覆盖 PIN 设置/更改、错误 PIN 不重试、旧 CTAP2.0 token、不支持 UV/RK 时不发写命令、只读凭据枚举、none attestation 隐私处理、可信浏览器 hash 原样传递、非法 CBOR/来源/断包拒绝及取消。连接回归用阻塞的合成 open 模拟不能立即返回的底层调用，真实执行 Activity 取消与重连代码。USB/NFC 测试实际执行生产分包代码，但没有物理 USB/NFC 传输。

系统测试构造 Android 框架最终注册请求，验证可信 Chrome 调用签名及 clientDataHash，普通 extras 不能覆盖它；伪造委托来源与 Monica 签名组合拒绝；响应和取消类型经 PendingIntentHandler 往返。首轮暴露公共后缀表的 IDNA2003 转换无法表示某些新域名，现改用 Android ICU UTS46，并拒绝当前 ICU 无法表示的域名；无关 RP 不再因为单条规则失败而无法注册。修复后这 3 项完整通过。

首轮 23 项、后续 26 项普通版设备测试均曾完整通过。临时在原有扫码菜单测试增加的 FAB 合并语义断言不稳定，真实截图已显示 FAB；临时断言与诊断已撤销，原测试保持原样并独立通过。新连接夹具首轮因 CBOR DSL 内误用 Kotlin Pair 写法编译失败，仅修正夹具后最终构建通过。

构建中曾发生 Gradle native 内存分配失败，随后采用单 worker、4GB Gradle heap，构建时停止本任务的高版本 AVD。高版本测试先尝试现有 API36 AVD，但磁盘不足无法安装；API36.1 AVD 的 instrumentation 被系统崩溃中断，日志存在系统图形进程异常，未认定每次中断同因。最终使用现有 API35：最新普通版一次 instrumentation 在测试开始前报告 System has crashed，crash buffer 可见系统死亡及其他系统应用异常；系统恢复后同一安装包的 3 项重跑通过，未因此修改生产代码或清理应用数据。F-Droid 最后一轮将此临时 AVD 的启动内存从 1536MB 调整为 2048MB，3 项完整通过，不能据此认定内存是之前系统中断的根因。原始失败日志留在本地 .codex-tmp/。

没有实体安全密钥，本次不能证明真实 USB OTG、NFC 射频、所有固件、PIN 锁定和生物识别兼容性，也不是 FIDO 认证测试。系统请求测试在高版本 Android 模拟系统附加的真实框架请求并通过 PendingIntentHandler 解析，不能等同于实体设备的网站端到端注册。

## 设计和交付

先在本地 M3E Canvas 制作并检查三页可编辑草图，再检查 Android Compose 实际渲染。草图 JSON、完整本地分享链接及截图保留在普通版忽略目录 docs/design/hardware-passkey-323/。不入库图片或 APK。

33 个共享生产、测试、资源、许可文件核对一致，另核对原扫码菜单测试一致、两版硬件 Activity 未导出且 USB/NFC 功能为可选。两版 Git diff-check 和新增 Kotlin 文件空白检查通过。F-Droid fastlane 30 保留前序改动，中英文分别 460／268 字符，均未超过 500 字符。

保留现有软件 Passkey、数据库和安全存储策略。未清理应用数据、提交、推送、发布、打标签或交付 APK。公共 API32 AVD 由前序任务启动，保留运行；为覆盖 API34+ 系统请求复用现有高版本 AVD，最终采用 Pixel_Fold_API_35，本任务启动的高版本虚拟机已停止。

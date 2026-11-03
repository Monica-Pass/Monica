# 1.0.324 权限精简与权限管理验证

日期：2026-11-02。版本：1.0.324-26110212-04（code 12），未发布。

## 修改及边界

移除显式 READ_EXTERNAL_STORAGE、READ_MEDIA_IMAGES 与应用顶层 BIND_AUTOFILL_SERVICE；自动填充 Service 的绑定保护仍保留。普通版还删除无业务调用的 READ_PHONE_STATE；保留实际凭据导出使用的 HIDE_OVERLAY_WINDOWS。

NFC 仍用于硬件 FIDO／Passkey 的 IsoDep 连接及前台 reader mode；指纹、相机、实际网络和服务权限保留。Android 8–9 保存图片仍保留 WRITE_EXTERNAL_STORAGE maxSdk28，这些平台会推导读取权限。该行为在资源和发行说明中明确解释；现代 Android 无显式广泛相册／存储读取声明。

旧相册入口改为 ACTION_OPEN_DOCUMENT、image/*、CATEGORY_OPENABLE 和仅读 URI 授权；缺少处理器时才回退 ACTION_GET_CONTENT。回调流复制所选文件原字节。没有改变认证、加密、KDF、数据库和应用启动授权策略。

权限页读取实际安装包 GET_PERMISSIONS、GET_SERVICES、GET_DISABLED_COMPONENTS，覆盖依赖声明与服务绑定保护，分为运行时授权、特殊访问与服务、自动授予与内部保护。点击行只看详情，详情按钮才发起请求或设置跳转；动作前重读声明，仅明确白名单可请求。未知声明用完整名称生成唯一 ID，伪 CAMERA／同后缀权限不误识别。精确位置同时请求粗略位置，并接受用户仅授予大致位置；旧系统通知状态取实际通知开关；生物识别展示硬件与录入可用性。

54 个新增字符串覆盖两版各18资源配置，保留全部内置语言。已检查 XML、占位符与共享字节；实际 APK 中972个编译值逐项等于源码。52份共享生产／资源／测试文件的最终 SHA 一致，变更前冻结另存在本地。

## 最终验证

- 完整 Debug 主程序、测试包和相关 JVM 构建：9m 37s，未跳过生产编译检查。使用单 worker、8GiB in-process、no-daemon，构建期间模拟器停止。
- JVM：41项通过，0失败／错误／忽略。Repository 的8方法覆盖 API26／28／32／34；另含动作路由3项、通知6项。
- 公共 API32 模拟器：13项不同设备方法全部通过；驱动用时 59.764 秒。覆盖实际安装清单、依赖和服务保护、详情打开不授权、真实系统相机拒绝、大致位置选择、设置往返、320dp英文双倍字号／巴西葡语、刷新状态及既有照片编辑两项。
- 真实 DocumentsUI 从受 MANAGE_DOCUMENTS 保护的合成 provider 选择一张图，应用回调原字节与红色像素正确，另一张图依旧拒读，前后均无广泛读取权限。点击依据实际系统 accessibility 节点的 bounds，通过 shell input tap；没有 pm grant 或程序直接赋 URI 权限。照片编辑两项使用已有合成 ActivityResultRegistry，不冒充真实相机验证。
- 实际 APK 声明 29 项；原自动填充、辅助服务、输入法与凭据提供方绑定保护核对保留。APK 与设备回执主程序 SHA 相同。

两版英文双倍字号、巴西葡语及 F-Droid NFC 原生代表截图已查看，均保留在本地忽略的 docs/design/permissions-324 中，图片及 Canvas 不入库。帮助弹窗的 native 截图处于入场动画、文字呈半透明，未用于最终视觉验收；帮助和返回行为的设备断言通过。324 中英文根／本地 next 与 F-Droid31说明同步，保留已发布323／323b。F-Droid元数据检查与6脚本测试通过。

## 失败及适用范围

初次测试编译存在 Array 投影、nullable Service 与缺失 UIAutomator 依赖错误，修正夹具后继续。首次 API32 批次9/13，补全真实系统窗口访问 flags 后12/13；选择器点击与 MotionEvent 注入未回调，实际节点坐标 shell input 方式修正后最终完整13/13。所有原始失败与诊断日志保留在本地任务目录，未把先前失败批次计为通过。

最终重启的第一次普通版批次7/13、6失败，130.157秒；截图查到 SystemUI 的“Welcome back, guest”遮挡外部窗口。待 runner 结束后选择可见的 Yes, continue 保留访客；同 APK、同测试断言完整重测13/13。驱动还曾在访客切换完成前被用户检查拦住，以及因 API32 不支持 clear-permission-flags 而在 instrumentation 前失败；随后仅清理已核实的自有用户13合成应用夹具。没有清主用户或其他应用数据。

默认 Gradle／Kotlin 两大 daemon 与模拟器并行曾 mmap 失败；4GiB in-process 在既有大方法 ASM 分析时 OOM。跳过缓存生产编译的实验又遇 Javac／KSP 依赖错误，已废弃。停止模拟器，单 worker、8GiB完整串行构建后通过；未修改用户全局配置或终止他人 Java 进程。

API35 只尝试复用既有 AVD，系统服务及宿主内存不稳，未执行 instrumentation，不能计为通过；自有用户已清理。没有本轮 R8、真机／OEM、实际 NFC 安全钥匙或 Android8–9设备验证，API26／28兼容证据来自 JVM。仅使用合成数据。

最终恢复公共AVD用户0，只移除本轮自有用户13，保留0／10／11／12及原配置、userdata／qcow2，停止本轮启动实例。未提交、推送、打标签、发布或交付 APK。

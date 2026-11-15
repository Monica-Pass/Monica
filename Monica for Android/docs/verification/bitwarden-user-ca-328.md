# Bitwarden Android 用户 CA 支持 — 1.0.328

2026-11-14 完成。普通版与 canonical F-Droid 保持未发布 1.0.328，versionCode 分别为 12 / 35。完成既有实现的剩余验证、证据核对、发行说明检查及测试环境清理。未提交、推送、打标签、发布或交付 APK。

## 行为

自托管 Bitwarden 的已配置 HTTPS 主机可复用 Android 设置中由用户安装的 CA。同一可信 CA 签发、域名与有效期均正确的新服务器证书可以继续连接，无需重新填写账号 PEM。现有账号专用 PEM 和 PKCS#12/mTLS 配置保留。

额外信任仅用于账号 base、API、identity、notifications 的 HTTPS 主机；官方 bitwarden.com、bitwarden.eu、bitwarden.pw、bitwarden-gov.com 及其子域仅使用系统信任。没有修改应用全局网络安全配置，外部附件下载及其他网络客户端不继承该信任。账号请求的 scheme/host/port/path、会话检查及禁用重定向继续生效。

生产实现通过 AndroidCAStore 的 user: 根证书建立信任，并在实际验证新证书链时重新读取。按主机名清理证书链的 Android 反射方法使用 @Keep。账号客户端委托原域名校验器，但使用独立实例，阻止当前 OkHttp 4.12 的跨主机 HTTP/2 连接复用绕过重新握手。

本功能不自动下载或信任未知服务器证书。删除 CA 的验证范围是新的完整 TLS 握手，不承诺关闭已经建立的连接或撤销既有 TLS 会话恢复；账号独立 PEM 明确信任同一 CA 时仍可通过自身配置连接。

## 实现与本次接管范围

既有生产改动为 BitwardenApiFactory、BitwardenHostTrustManager；新增用户 CA 设备测试，调整配置页迟到回调测试，并更新全部 18 个语言配置中的证书说明。接管时 22 个共享源码/测试/资源文件与原最终冻结 SHA-256 完全一致，两版也字节一致。

本次接管未新增生产代码或 R8 配置改动。仅完善本地平台测试探针与驱动：保留此前已加的明确域名异常断言，增加同一管理器跨系统 CA 移除的真实握手检查，并为新管理器的移除后检查补真实 HTTPS。重新打包两版 Release 测试包；原 Debug/Release 主包及 Debug 测试包保持接管时原 SHA-256。

## 构建与 JVM

| 阶段 | 普通版 | F-Droid |
| --- | ---: | ---: |
| 已有 Debug 主包/测试/JVM 成功构建 | 278.931 秒 | 525.613 秒 |
| 配置页夹具最终主包/测试构建 | 53.122 秒 | 81.549 秒 |
| 已有平台 Release 主包/测试成功构建 | 175.635 秒 | 441.089 秒 |
| 本次 Release 主包保持不变、测试包更新 | 41.545 秒 | 26.333 秒 |
| 已有相关 JVM | 36/36，无失败、错误或跳过 | 36/36，无失败、错误或跳过 |

JVM 为环境解析 14、TLS 配置 10、实际 JVM TLS transport 7、环境存储 5。沿用各自原构建与 JSON 回执，本次未重复运行 JVM。raw 中同名 TEST XML 仅保留一套，不能表述为两版各自完整独立 XML 副本。

主包此前实际经过 R8；本次两版主包 minifyReleaseWithR8 和 packageRelease 均 UP-TO-DATE，未把未改变的主包称为再次执行混淆。两版 8 个最终主/测试包签名均校验通过，主包实际包含 x86_64；两版各自 Debug、Release、测试包签名一致。本地测试使用独立 application ID 和 debug 签名，不是正式发行包验收。

## Android 设备结果

同一公共 API32/x86_64 AVD；合成 CA、合成服务器和本机回环 HTTPS，使用原 user 0 下的独立任务包，未创建新 Android 用户。

| 每版范围 | 普通版结果 | F-Droid 结果 |
| --- | --- | --- |
| Debug 用户 CA 完整客户端测试 | 11/11，runner 10.619 秒 | 11/11，runner 55.290 秒 |
| 最终认证及配置页回归 | 21/21，runner 190.087 秒 | 21/21，runner 71.984 秒 |
| 本次 Release 平台检查 | 8/8，驱动 5.820 秒 | 8/8，驱动 5.049 秒 |
| 同一 Release 管理器移除 CA 后的新握手 | 1/1，驱动 79.286 秒，含人工系统操作等待 | 1/1，驱动 79.278 秒，含同一等待 |
| 新 Release 管理器移除后真实 HTTPS | 1/1，驱动 1.655 秒 | 1/1，驱动 1.658 秒 |
| Debug 客户端工厂移除后真实请求 | 1/1，runner 0.863 秒 | 1/1，runner 0.763 秒 |

每版共 33 个不同 Debug 设备方法取得通过结果；另有 10 项最终 Release 平台检查。不要将平台检查称为额外 10 个 JUnit 方法。合成夹具 prepare 的通过不计产品测试。所有最终回执均核对 OK、完成码 -1、无失败标记；Debug 逐方法开始/结束记录已核对。

Debug 的 11 项涵盖实际系统用户 CA、同根 CA 换叶证书/服务器密钥、官方域名拒绝用户及账号 CA、域名相似后缀、真实 HTTP/2 跨主机复用拒绝、域名不符、过期、未知 CA、外部传输隔离、普通客户端隔离、会话撤销。

Release 使用独立 Java Instrumentation，对实际混淆主包内的生产管理器按 residual constructor 签名反射构造，调用 Android X509TrustManagerExtensions 和平台 HTTPS。两版实际 mapping 保留所需类名和 String-host 方法，本地 init 没有额外 keep。错误域名明确要求 SSLPeerUnverifiedException。

移除实验先在两个运行中的生产管理器上成功连接同一合成服务器证书，再通过系统设置按完整 SHA-256 指纹精确卸载本次 CA。两个进程保留原管理器，移除后通过新 SSLContext 和新端口发起完整握手，均得到 SSLHandshakeException，服务端未收到 HTTP；随后两个新进程/新管理器及两个 Debug 工厂请求也拒绝。

**Release 平台检查没有复刻完整 OkHttp 工厂、HTTP/2 或 mTLS 流程。** 完整客户端的相应证据来自 Debug/既有 JVM；不能扩大 Release 的结论。未验证真实用户账号、远程服务、其他 API/OEM/实体设备或其他 TLS provider。

## 保留的失败和修正

- 普通 main-debug-01 联合构建 391.642 秒失败：新增 Dns lambda 不符合接口类型，改为显式对象；主包已生成但测试编译失败。
- 普通早期独立包安装有旧测试 provider authority 冲突，本地测试 Manifest 隔离后成功；仓库 Manifest 未修改。此初始失败没有独立 raw 回执，依据原会话输出及后续修正，不宣称所有初次失败均有独立日志。
- main-release-01 / 02 分别 1086.586 / 505.944 秒失败：既有 AndroidTest 带入 Debug 专用页面；第一次仅修改 Java 源目录仍带入 Kotlin 测试，后来修正双源目录。主包生成不等于联合构建成功。
- main-user-ca-release-01 因 AndroidJUnitRunner 缺失被裁剪的 androidx.tracing.Trace 而在测试入口崩溃；未进入证书方法，ADB exit 0 不计通过。后续平台探针有不同且明确受限的验证范围。
- fd-regression-debug-01 为 20/21。旧测试通过已卸载 Compose 节点的点击包装层读取 CompositionLocal，进入业务回调前抛错。改为捕获同一业务回调，保留不能重定向服务器的全部断言；最终两版 21/21。不能把 FD 初批改写成全绿。
- 公共 AVD 首次实例消失原因未确定；新用户/上限/root 尝试失败后使用独立包，原用户保留。
- 本次接管新执行的四个移除后测试批次、两个持有管理器实验、两个正常 Release 平台批次和两次补测试构建均通过；旧失败记录未删除。

## 界面、说明与清理

两版最终配置页各 12 张截图仅保存在忽略的 docs/design 下。本次查看两版各四张证书页联系表：中文、英文、巴葡、英文 RTL，1.5 倍组件字号；说明可见，完成按钮保留，长表单可滚动。RTL 为英文布局测试，不是新增 RTL 语言的母语审校。没有全主题、全语言或实体设备视觉验收声明。

普通/F-Droid 根目录中英文 328 未发布说明、各自本地 next 文件及 FD fastlane 35 摘要核对。F-Droid 元数据检查及 6 项脚本测试通过；版本号与 327 历史说明保留。

系统 USER 证书页已恢复为空；只卸载按指纹确认的测试 CA，没有使用 Clear credentials。只卸载本任务的四个独立测试包，删除校验 SHA 后的公有测试 CA 文件和 UI 转储。接管前后其他 user 0 包清单一致，原用户 0/10/11/12/14 保留；接管时系统字号 1.5 未改动。没有清理用户密码库。

停止原任务启动的公共 AVD，确认 emulator-5556 不再在线；原配置/注册 SHA 不变，userdata 与 qcow2 数据盘保留，其他设备未操作。合成 TLS 私有夹具只保存在本地任务私有目录；公开结果不含密钥、用户库、截图或安装包。

结果与最终产物标识见 [结果 JSON](bitwarden-user-ca-328-result.json)。原始回执、日志、失败及本地探针保留于本地任务 20261114-bitwarden-user-ca。独立只读复核未发现当前范围内需要额外生产修正的阻塞缺陷，结论仅限上述路径与证据。

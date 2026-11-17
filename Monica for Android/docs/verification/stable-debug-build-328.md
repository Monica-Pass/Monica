# 1.0.328 可选稳定 Debug 构建验证

普通 Android 新增 `-PstableDebugBuild=true`，仅为本地应用 Debug 构建省去会反复变化的时间与每日序号输入。一次完整构建后的第二次无修改构建成功复用了编译和打包结果。默认 Debug、Release 及性能采集变体仍保留原有构建标识。

## 改动范围

仅修改 [app/build.gradle](../../app/build.gradle)，使用 AGP 9.3.1 公共 Variant API，并以 `stableDebugBuild && variant.buildType == 'debug'` 限定作用范围。

| 字段 | 显式启用时的值 |
| --- | --- |
| 输出版本名、`BuildConfig.VERSION_NAME`、`FULL_VERSION_NAME` | `1.0.328-local-stable` |
| `BUILD_DETAIL_TAG` | `local-stable` |
| `BUILD_TIME` | `local-stable (timestamp omitted)` |
| APK 文件名 | `Monica-Android-<ABI>-1.0.328-local-stable.APK` |

包名 `takagi.ru.monica`、版本号 12、基础版本 `1.0.328`、原 Git SHA、签名与 ABI 配置保留。实际生成的 Git SHA 为 `ae3f33e66a`。该标记明确省略构建时间；Git SHA 标识已提交版本，不能代替未提交源码内容的完整哈希。未增加跳过编译、测试或原生构建的配置。

canonical F-Droid 已有静态标识，本项未修改其配置。Linux Rust CI、工作流和原生 Gradle 脚本也未修改。

## 实际验证

Windows、JDK 17、项目 Gradle Wrapper，串行执行两次完整 `:app:assembleDebug`，包含 `-PincludeX86TestAbi -PstableDebugBuild=true`。同次调用还运行本地只读元数据记录任务。两次构建均未传固定时间的测试 init，也未传 `dailySeq` 或排除编译任务。

| 完整调用 | 耗时 | 结果 |
| --- | ---: | --- |
| 首次切换为稳定 Debug 标识 | 178.716 秒 | 构建成功，实际重新生成标识并编译打包 |
| 源码未变的第二次调用 | 2.522 秒 | 构建成功，63 个任务中 62 个 up-to-date，仅本地元数据记录任务执行 |

第二次 `generateDebugBuildConfig`、KSP、Kotlin、Java、Manifest、Dex、`packageDebug` 和 `assembleDebug` 均为 `UP-TO-DATE`。两次生成的 BuildConfig 和三个 ABI 的 APK 字节完全一致。

首次调用是切换标识后的构建，不能作为严格的旧默认模式对照。因此这些结果证明本机该次无修改构建能复用已有结果，不表示所有构建获得相同提速倍数，也不代表应用运行速度提升。

另以默认和启用选项分别记录 `debug`、`release`、`benchmarkRelease`、`nonMinifiedRelease` 四个真实变体。为固定比较条件，两次配置记录均使用同日的 `dailySeq=02`。只有 Debug 使用 `local-stable`；其余三个变体的包名、版本、Git SHA、ABI 和输出文件名一致，正常时间字段随真实配置时间由 18:39:30 前进到 18:41:08 CST。

## 产物独立复核

独立只读复核再次执行元数据与复用验收脚本，并对当前三个 APK 逐个运行 Android Build Tools 36.0.0 的 `apksigner verify --verbose --print-certs` 和 `aapt2 dump badging`。所有签名验证通过，均为包名 `takagi.ru.monica`、code 12、版本 `1.0.328-local-stable`，每份只声明对应 ABI。每份 9 个打包原生库的 ELF 头均匹配对应架构，共检查 27 个库。

三个 APK 的签名证书均与本任务留存的既有普通 Debug 参考包一致，证书 SHA-256：`6e1c4c795d062b74c28c3d4fa13833a71deef19f6501e917ee6b3df60797a4b0`。这是证书比对，不是覆盖安装测试。

| ABI | 两次构建及复核一致的 APK SHA-256 |
| --- | --- |
| armeabi-v7a | `81494b0b2fdd0d33506e1d0c32f6ea852165d807ff1176817aefa3c38c9605f8` |
| x86_64 | `f52d74248b129a4e0569376bef23d18b24b1305796935ac4e2c108791f0e5323` |
| arm64-v8a | `9fcf0b124f31528452c7613bdcb0be05c2a22b6acae27d3fa678762925c9e404` |

构建脚本 SHA-256：`60be72e90695a575945c71bc30f894b69d70b1e08f55bedac960b8c0f4aa1a5d`。生成 BuildConfig SHA-256：`370054c2d5e368bef6f515a1e35203ff169d358ba25d3c5aece5bcdfb9c7dcd3`。结构化结果见 [stable-debug-build-328-result.json](stable-debug-build-328-result.json)，完整命令与原始日志保留在本地任务证据中。

## 验证边界

本项只涉及应用 Debug 构建标识，没有改运行时代码。Release 的本项验收是配置比较，未为此项重新打 Release 包或执行安装、设备测试。AndroidTest 组件仍继承其原有动态输入，因此不承诺 `assembleDebugAndroidTest` 的重复编译与打包复用。Linux CI 未执行，也不将本机结果泛化到其他平台或全新环境。APK 只供构建校验，未交付、发布或由本子任务提交推送。

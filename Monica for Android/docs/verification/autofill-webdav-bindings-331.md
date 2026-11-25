# Android 331：KeePass WebDAV 关联应用推荐补充验证

2026-11-25。普通版 code12 / canonical F-Droid code38，均未发布。

## 补充场景

用户后续截图明确：反馈的凭据存于 WebDAV 上的 KeePass 库，可以手动选择并自动填充，但没有推荐。前一轮本地/KDBX/MDBX/Bitwarden 回归中的 KDBX 使用本地文档 URI；本轮补测 WebDAV 的 CACHED_MIRROR 与 WORKING_COPY 两种模式。

源码路径核对：WebDAV 库从已下载的本机镜像/工作文件加载，认证与原生投影后进入同一推荐匹配链路。原先“解锁后载入的记录没有重算推荐”的修复作用于此路径。

## 本轮修改

仅在两版 NativeMultivaultPickerUiTest 添加两个 WebDAV 设备方法与自有远程夹具清理。文件 SHA256 为 08255eb186eae4edafc58375ceba5040cb01ad1309918b8ef4c2045aa8f086e7，两版字节一致。全部生产源码与本轮起始基线一致，包括此前 7 个自动填充修复文件；没有进一步修改生产、UI、认证、数据库或 WebDAV 同步逻辑。

- webDavCachedMirrorRecommendsBoundLoginAfterAuthenticationAndSearchesAppName
- webDavWorkingCopyRecommendsBoundLoginAfterAuthenticationAndSearchesAppName

## 检查内容

每项通过现有 WebDavKeePassSupport/生产 WebDAV 客户端从独立 MockWebServer 下载精确合成加密 KDBX，配置 REMOTE_WEBDAV 来源、本机镜像和工作副本及对应 openMode，清空原生进程缓存后进入实际 V2 选择器并认证。

使用包名关联、空的初始推荐数组与 refreshLoginSuggestions，验证：关联记录出现在推荐组；本地来源行不泄漏；具体 KeePass 来源正确；无关查询排除记录，带首尾空格的已安装应用名称找回记录；清空搜索恢复推荐；实际选择记录并核对 Android Dataset 的用户名与密码。镜像、缓存和初始文档字节均保持原值；选择器阶段没有新增 WebDAV 请求或远程写入。

这是实际 Activity、认证和 Android Dataset 补测，启动参数由测试提供。本轮没有用远程 WebDAV 表单重新跑完整系统 AutofillService 入口；此前完整系统入口及 Release 升级证据保持独立，不把此处 Activity 测试描述为新的完整系统链路验收。

## 结果与失败记录

MuMu 实例2 / API35 / x86_64 / 自有 secondary user10：普通 2/2（驱动 12.532 秒，runner 9.840 秒），F-Droid 2/2（驱动 11.860 秒，runner 9.402 秒）。两版 Debug 主/测试包构建通过，输入源码未在构建期间改变。普通最终构建 81.111 秒，F-Droid 94.334 秒。

首次普通夹具只实现 HEAD/GET，未支持客户端用于路径检查的 WebDAV PROPFIND，两个方法均在下载前置阶段失败（驱动 7.046 秒），尚未进入自动填充。补齐合法 207 XML 响应后两项通过；未改生产逻辑。首次输出、APK 与精确冻结哈希匹配的夹具源码保留，本轮最终源码和失败不混淆。没有声称首次全绿。

与此前每版 23 个不同设备方法合计，相关不同方法为每版 25 项；这是跨批次的不同方法合计，并非新整批 25 项测试。此前每版 53 项 JVM 和 R8 Release 系统填充/保留数据升级结果未重跑；生产源码字节一致，不将旧包称作本轮新 R8 构建。

## 范围与清理

仅合成本机 WebDAV、KDBX4、API35/x86_64、Debug；未访问用户原库或真实服务器，未验证用户 OEM 真机。截图用于确认库类型，未写入仓库或报告图片。

复用已存在且本轮起始停止的 MuMu2，任务结束停止实例并保留配置/盘；删除自有 user10 与四个隔离主/测试包，恢复原 user0，adbd 保持 UID2000，自有 ADB15073 停止。其他实例保持原状态。首次立即移除用户失败，确认停止后再次移除成功，原失败记录保留。两版源码与空白检查通过。未提交、推送、发布或交付 APK。

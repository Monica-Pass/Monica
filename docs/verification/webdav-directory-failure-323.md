# WebDAV 备份目录检查故障（1.0.323，2026-10-29）

## 中文

用户报告 WebDAV “找不到文件”，但没有设备日志或具体操作，因此尚不能确认该反馈的根因。检查备份上传发现一个独立缺陷：`WebDavHelper.webDavPathExists` 会将超时、断线和未知服务器异常转换成 `false`，使 `uploadBackup` 在没有确认目录状态时继续发送 MKCOL 和 PUT，并可能掩盖原始错误。

修复仅保留确认 404 时的不存在结果；其他异常原样上抛，由上传入口停止操作并呈现原始失败类别。未配置的客户端也不会被视为目录不存在。没有修改本地库、密钥、证书校验、备份密文格式或服务端既有文件。

通过实际 `uploadBackup` 入口、真实 OkHttp/Sardine 与 MockWebServer 测试。只替换 Robolectric 无法提供的系统密钥存储，不替换上传或目录检查逻辑；目录请求使用短测试超时，生产超时保持原样。

普通版结果：

- 修复前 5 项场景中 3 项失败：HTTP 500、元数据超时和断线仍会继续创建目录及上传；已有目录及真实 404 场景通过。
- 修复后新增的 5 项回归全部通过；故障场景只有 PROPFIND，没有 MKCOL 或 PUT。
- 2 项既有 MDBX 目录检查测试和 3 项超时分类测试通过，共 10 项，无跳过。
- Debug 打包通过，使用隔离 Gradle 缓存、单 worker 和项目既有 JVM 配置。没有交付或发布安装包。

构建期间曾遇到本机原生内存不足及降低堆上限后的频繁回收；恢复既有 6 GiB 配置后构建成功。该现象与产品运行时故障无关。原始构建记录保留在 `.codex-tmp/cloud-errors-323/android-webdav-before.log` 和 `android-webdav-after-6g.log`。

## English

The reported “file not found” could not be tied to a specific operation without device logs. An independent backup-upload defect was reproduced: directory-check timeouts, disconnects and unknown server errors became `false`, allowing speculative MKCOL and PUT requests that could hide the original error.

Only a confirmed 404 now means the directory is missing. Other exceptions stop upload with their original classification. Local data, keys, certificate verification and encrypted backup formats are unchanged.

Tests exercise the real upload entry point and OkHttp/Sardine transport against MockWebServer, substituting only the unavailable Robolectric OS key store. Three of five scenarios failed before the fix; all five passed afterward, along with five existing directory and timeout tests. The normal Android Debug package built successfully. No APK was delivered or published.

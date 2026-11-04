# Bitwarden / Vaultwarden 请求兼容修复 — 1.0.325

2026-11-03。当前仓库最终 Debug/x86_64 APK `1.0.325-26110312-04`，versionCode `12`。主程序与相关JVM构建同批成功，耗时 3m 17s。保持未发布325，未提交、推送、发布或交付APK，已发布324和此前IP无障碍改动保留。

## 确认的缺陷与修复

旧创建/更新DTO没有 `encryptedFor`，URI DTO没有 `uriChecksum`。三个旧生产回归均出现预期断言失败：创建账号标识、更新账号标识，以及URI校验值在实际序列化往返中丢失。前序用户日志的Rocket422与发布版Vaultwarden1.37.5要求账号标识一致；这不是使用错误的证据。

本次在七个实际HTTP调用边界补齐协议：登录创建/更新、SecureItem创建/更新、通用创建、原始Passkey文档更新、同库文件夹原数据复制。账号标识使用该Vault已认证profile的UUID；旧实体无有效缓存时，使用同一API和token获取profile，并核对邮箱与UUID。认证失败、邮箱不符或缺少有效标识不会构造替代账号，也不会发出后续写请求。

每个类型化登录URI按完整解密原文的UTF-8计算SHA-256，再使用标准Base64字符串，并沿用已经解析出的有效条目密钥加密校验值。保留URI密文字节、顺序、重复项及显式匹配规则；已有正确校验值原样保留，缺失/错误校验值重新生成。URL校验不做额外归一化，支持HTTP/HTTPS、IP、Unicode、端口、路径、查询与片段以及androidapp。类型化更新按完全相同的原URI恢复旧匹配规则，不恢复用户已移除的网址；矛盾旧规则拒绝上传。

本地官方参考客户端的Login.decrypt在存在cipher.key时验证URI校验值并排除无效URI；旧条目无cipher.key时为兼容会绕过该检查。因此本轮修复针对确实缺少的协议字段及这条客户端验证路径，不把它等同所有多URL异常的唯一原因。参考源码为bitwarden/clients的libs/common/src/vault/models/domain/login.ts、login-uri.ts及services/cipher.service.ts；生成算法为加密完整URI原文的SHA256标准Base64。

原组织、collection、包裹的条目密钥、Passkey同条目内容及原修订保护保留。错误密钥或无法认证解密的URI拒绝上传。原始Passkey和文件夹复制文档仅补账号标识，保留所有未知字段及既有URI/校验密文；读回内容验证排除请求独有的账号字段。它们不会批量重写既有URL校验值。

## 实际执行的验证

| 验证 | 当前仓库结果 | 范围 |
| --- | --- | --- |
| JVM | 40个不同用例通过，0失败/跳过 | 16个新协议用例；24个既有组织密钥、Passkey、文件夹复制、自定义字段回归 |
| 实际Retrofit请求 | POST创建、PUT更新各1份抓包 | 每份3个合成URI，账号UUID、加密校验值与匹配规则正确；不含URL明文 |
| 独立加密核对 | Python cryptography43.0.3 + hashlib/hmac 通过 | 两份抓包共6个URI及6个校验值，12次HMAC验证、AES-CBC解密和独立SHA256/Base64比较 |
| 发布版Vaultwarden解析 | 两份实际抓包接受；各自删去encryptedFor后均拒绝 | 提取1.37.5的CipherData serde结构；保留前序ID字符串别名与folder可选反序列化适配，不是完整服务端 |
| 包与来源 | 实际325主APK含新增协议实现/字段，9共同源码/测试SHA相等 | 保留前序IP地址Reader；普通code12、F-Droid code32 |

新协议用例覆盖：创建/更新字段保留、HTTP IPv4/HTTPS IPv6和Unicode的多URI请求、各条目类型账号元数据、正确校验密文原样保留、错误校验修复、完整原文与重复URL、旧匹配规则/移除URL、条目密钥与错误密钥拒绝、null URI、无效账号、缓存profile、认证profile回退及账号错配/401拒绝、原始未知字段和读回验证。

可核对文本：`bitwarden-request-compat-325-jvm.xml`、`bitwarden-request-compat-325-results.json`、`bitwarden-request-compat-325-schema.txt`。原始构建/负向/抓包/独立验证日志留本地任务目录；测试只包含虚构账号、保留地址段和虚构token，没有用户凭据。最终主APK SHA256 `8a182d6af31167e26dbf0f5491cc056d6ac0428e729924473d15e1a2ddd254f2`。

## 原反馈与验证边界

前序普通324实际官方账号测试成功上传40条登录，0失败、0冲突，但未通过多URL显示验收；详见 `bitwarden-batch-upload-325.md`。本轮补齐官方参考客户端必需的校验字段，抓包与独立解密已确认正确。没有重新登录或修改该账号的100条测试数据，也没有新版官方网页逐项显示实测，因此前序40条上传不能计作本修复后的线上结果。

原反馈的Vaultwarden版本、实例/代理仍未知；本机Docker Linux引擎不可用，未运行完整Vaultwarden HTTP/认证/数据库会话。上述验证确认具体协议缺陷已修，不承诺解决所有422或认定原用户误用。未做R8、真机、HyperOS/OEM或原用户实例复测；本轮未启动/操作AVD，原API36他人模拟器保留。

## 保留的失败及收尾

初始负向批次两个账号断言失败，URI用例先因Android Base64测试环境缺失失败（2m8s），不能计为URI负向证明。补RobolectricSDK34后同旧生产三个断言全部预期失败（1m53s）。首轮普通正向主包已产出，但测试夹具误用Android编译接口不存在的Files.writeString，整体5m44s失败；改为Files.write后当前40项通过。临时脚本曾因默认GBK及Shell引号错误未执行，修正为UTF8与显式patch；原始日志保留。没有为通过测试放宽生产认证、MAC或匹配边界。

F-Droid结果目录还留有一份时间1970、0用例的AutofillCancellationTest空报告，仅保留原文件，不计为本轮执行或通过。报告合并首轮因此停止，核对全部实际用例后按6个有执行的方法套件汇总40项，没有丢弃任何失败测试。

本轮没有测试用户/设备需要移除；MockWebServer由测试use块关闭，测试密钥在finally清零。100条远端测试数据、用户网页登录状态、其他进程/AVD及既有未提交改动保留。构建包只留原构建目录，临时Rust目标和驱动只留忽略任务目录，未提供下载或复制交付。

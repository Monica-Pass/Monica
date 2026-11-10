# NFC 界面精简与银联兼容性 — 1.0.327

2026-11-10。普通版与 canonical F-Droid 均在本地 `main`；普通 versionCode 12、F-Droid 34。本次未提交、推送、打标签、发布或交付 APK。此前 Send 访问控制紧凑菜单及其验证报告保留。

## 结果与范围

- 银行卡、证件新建页移除顶部 NFC 按钮/说明，银行卡读取后的独立类型 chips 和成功消息一并移除。前台自动贴卡、冲突替换确认、异类新建确认和保留原页继续工作。卡片类型使用原 M3E 菜单，NFC 后未确认时无默认选项，用户明确选择并手动保存。
- PPSE 按受支持支付 RID 识别并保留完整 AID，SELECT 后继续核对完整 DF 名；无目录时使用全部十个已知候选，包括银联借记、贷记、准贷记。最多十二个目录应用，未增加无依据的主动 AID 猜测。
- 按卡片请求填写 PDOL：contactless EMV `9F66=20000000`、银联国家/币种 `0156`、公历日期时间、随机 `9F37`，数值/二进制按格式对齐；金额和未知字段仍零。补充 tag 56 Track 1 的卡号、姓名、有效期及 9F0B 扩展姓名，不保留完整磁道/服务码/自定义数据。
- 部分银联 PAN 不要求 Luhn。例外仅限成功 SELECT、DF 名核对后的银联应用，且为 16–19 位、62/81 前缀；其他卡组织保持 Luhn。跨字段、跨应用卡号冲突仍拒绝，不能仅凭号段认定有效卡。
- 无新增依赖。48 次交换、16 条卡片宣告记录、16 KiB 总接收、2048 字节单响应、4 次 continuation、TLV 8 层/128 节点限制保留。不发送 VERIFY、GENERATE AC、支付提交或未宣告记录扫描；GPO 仍可能使卡片内部产生 cryptogram/更新计数，不能将其描述为绝对无内部副作用。

## 开源对照

这是协议与兼容路径参考，未加入这些项目的依赖或复制整个支付内核。下载副本、固定源码 SHA 与许可证核对留在本地研究目录。

| 来源 | 用途 |
| --- | --- |
| [devnied/EMV-NFC-Paycard-Enrollment](https://github.com/devnied/EMV-NFC-Paycard-Enrollment/tree/a59820d4e979b17c1d1721fe2b03219d52ebcb35) · Apache-2.0 | AID、PDOL、Track 1/2 与姓名 tag；不采用其完整终端能力、非零金额或无宣告 record fallback。 |
| [neil-pan-s/qpboc-kernel](https://github.com/neil-pan-s/qpboc-kernel/blob/9c6c0b6467c28c221251e7aff27bf0fe68922b0f/QPboc/qpboc/src/main/java/org/ichanging/qpboc/core/QPreProcess.java#L83) · MIT | qPBOC contactless EMV 的 `0x20` 位定义。 |
| [braintree/card-validator](https://github.com/braintree/card-validator/blob/a260bc94ce1772b00c0cae0d2f3d73f690338d55/src/card-number.ts#L70) 与 [credit-card-type](https://github.com/braintree/credit-card-type/blob/b5130783e7ef172848965f97d145ae42640ca9b3/src/lib/card-types.ts#L137) · MIT | 银联 Luhn 例外及号段依据；未导入 BIN 数据库。 |
| [sinpolib/nfcard](https://github.com/sinpolib/nfcard/blob/b1043b8359d701576d508522bf497bd85bfcdc31/src/com/sinpo/xnfc/nfc/reader/pboc/StandardECash.java#L36) · GPL-3.0-or-later | 银联三个常见 AID；未复制遍历记录或交易日志读取。 |
| [metrodroid](https://github.com/metrodroid/metrodroid/tree/b910c1806fd86aa545223baf1d21fc5afe68eee2) · GPL-3.0-or-later | 核对交通卡使用独立协议，不把交通卡序列号当银行卡 PAN；未扩展此范围。 |

## 验证

两版 Debug 主/测试包与相关 JVM 联合构建通过，普通 321.207 秒、F-Droid 325.62 秒；每版 48 JVM（30 reader、13 draft、5 Send auth），无失败或跳过。最终仅更新测试夹具后的测试包分别 62.562 / 63.025 秒，生产源与各自主包快照保持一致。

最终普通版与 F-Droid 各 27/27 设备方法通过，runner 75.999 / 73.271 秒：17 NFC 编辑/控制器方法及 10 有效期输入方法。覆盖同类填入、冲突取消/替换、异类另开与返回保留、卡类型确认/保存、状态保存不含 NFC 敏感字段、锁定/后台/迟到结果、编辑忙碌与大字号菜单。使用真实 Compose 编辑器与内存 Room、合成解析结果/Tag；不是 NFC 射频或实体卡读取测试。

普通 `main-ui01`、`main-ui02` 均 26/27，失败记录保留。首轮将字号和 `hasVisualOverflow` 合为一条断言；第二轮诊断明确下拉菜单独立窗口实际为 1.0 倍，而页面局部为 1.5 倍。另有 String Text 语义重建的 paragraphWidth 905 与实际节点/layoutWidth 149 不一致，导致粗粒度 width overflow。最终夹具只在大字方法外层对自有用户设置并恢复系统字号，Activity 启动前确认生效；保留实际 1.5 倍、每行/字形四边界、无省略、垂直溢出和节点可见范围检查，没有删除视觉断言或修改生产布局以掩盖失败。

独立纯 JVM 最终 30/30；同一套 30 测试对旧 HEAD reader 为 18 过、12 预期失败，其中包含新能力、fallback 顺序和预算期望变化，不代表十二种实体卡故障。全部早期失败和原测试源码留在本地。

28 个共享源码/资源/测试文件两版字节一致，18 配置 × 11 个 NFC 文案 = 每版 198 个实际 APK 资源值逐项匹配；7 个废弃键及引用移除。Gradle/settings/catalog 声明与 HEAD 一致。F-Droid 元数据与 6 个发布脚本测试通过；短说明首次编辑超过 500 字符被写入前校验拒绝，缩短后两语言均合规，已发布 33 保留。

每版四张原生截图已查看，覆盖浅色冲突/异类确认、深色 1.5 倍菜单及已选预付卡；无系统遮挡。设计和截图只留忽略目录，不入库。文本布局诊断、原始构建/设备日志、研究来源和源码/APK 哈希留在本地 `.codex-tasks/20261110-nfc-compat`；公开摘要见同目录 `nfc-compat-327-results.json`。

## 清理与未验证范围

首次停止后立即移除用户被系统拒绝；核对用户已停止、原用户 0 前台后重试成功，保留首次失败记录。恢复 MuMu 原用户 0，仅删除本任务合成用户 11；原 0/10 的安装包集合核对未变，停止本任务启动的已有 MuMu 实例，保留数据盘。公共 API32 AVD 未在本轮重新尝试。

仅合成 MuMu API35/x86_64/Debug。实体银联借记/信用卡、真实 NFC 射频、OEM 和本轮 Release/R8 未验证，不能承诺每张银联卡都能读取。姓名、有效期是否可得由卡片公开数据决定；信用/借记/预付不能保证自动识别。居民身份证受保护号码、交通卡专有协议不在本轮支持范围，NFC UID 不作为这些号码的替代。

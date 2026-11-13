# 完整项目去重：设计与现有引擎审查

日期：2026-11-06。适用：普通版与 canonical F-Droid 当前未发布 1.0.325。

状态：代码审查、交互设计和独立算法模型验证；尚未修改生产去重逻辑，未在用户数据库上执行任何分析、合并或删除，也未构建应用。本文件中的新行为均为待实现设计。当前引擎的问题由源码调用链确认，尚未建立对应 Android 运行时复现。旧测试不能代替新增原生来源与完整项目测试。

## 1. 产品结论

值得新增“单库去重”，核心应是可解释的完整包含判定。只有一个最终保留项目完整覆盖候选项目，并满足身份、字段语义、原生属性、引用及恢复条件，才允许用户选择清理。

假定 A/B/C 是项目代号，其身份及基础属性相同：

| 项目 | 内容 | 结果 |
|---|---|---|
| A | 1、2、3 | 保留 |
| B | 1、2 | B 的每一项都在 A 中，候选清理，明确显示“B → A” |
| C | 2、3、4 | 保留；A 和 C 各自有独有内容 |

“相似”“同网站”“同用户名”只用于寻找可能相关的项目，不构成删除依据。结果数量不是优化目标。不能把 A、C 的字段拼起来证明 B 可删；一个候选必须对应一个完整的最终保留项目。

若 A/B/C 真的是不同的可见标题，而非示例代号，默认列为需检查，不能忽略标题差异。普通去重操作不修改保留项目；需要补字段、重排账号或改引用时，转为单独的合并审阅。

## 2. 现有引擎已确认的边界与缺口

生产入口是 `DedupEngineScreen` → `DedupEngineViewModel` → `DedupMergeService` → `DedupMergeExecutor`。当前目标类型只有 Monica 本地和 MDBX；KeePass、Bitwarden 可以被列为来源，尚不是该页面可选目标。来源不删除，目标通过新增副本整理。这一点避免了直接删除来源，但不代表目标副本完整。

### F1：原生 KeePass 字段与附件读取路径没有跟上新存储方式（高优先级）

`PasswordRepository.getAllPasswordEntries()` 合并 Room 与 `KeePassMonicaPasswordStore.activeRows`，原生行使用独立 UI ID。后者将自定义字段保存在 `Snapshot.customFields`，附件通过 `attachmentRows` 暴露。

但 `DedupMergeService.buildPlan()` 仍调用 `CustomFieldRepository.getFieldsByEntryIds()`；该方法只查询 Room DAO。`DedupAttachmentSupport.snapshot()` 同样仅枚举 Room attachment DAO，然后才走复制 facade。附件 facade 的原生读取能力无法补回前面未枚举的原生附件。

因此，当前原生来源可出现主字段存在、扩展字段或附件未进入计划的情况，目标可能被误判已存在，或新增副本缺失内容。不是声称所有 KeePass 导入都失败：旧 Room 镜像路径和新原生路径必须分别测试。修复应引入完整原生快照适配器，不能只给字段仓库补一个空列表兜底。

### F2：按账号行分组会破坏项目与多密码结构（高优先级）

`DedupMergeService` 用 `DedupPasswordIdentity` 按网址/应用/标题＋用户名分组；`mergePasswordGroup()` 保留一个 keeper.password，再补部分空字段。它不知道多个 `PasswordEntry` 可能属于同一个项目下的不同密码。

`mergeCustomFields()` 将普通字段与 `monica.content.credential`、`monica.content.order`、分块内容都作为平面键值集合拼接；不重建账号组、块 ID 与顺序引用，也不检查重建后能否再次编辑。

已知结构反例：两个同账号密码行的 credential 元数据不同，合并后两个不同副本都保留在单行中；`ProjectCredentialGroup.read()` 只接受完整一致的副本，因而会返回不可读。相同 block ID 的不同版本也可能产生重复头/分片，`PasswordContentBlocks.read()` 的 single/摘要验证会拒绝。应整项目重建关系后比较，不能合并这些系统字段的字符串集合。

### F3：新增主字段参与复制，但部分差异没有参与冲突提示（高优先级）

`mergePasswordGroup()` 会择一使用邮箱、电话、地址、卡号/CVV、SSH、Passkey 元数据、SSO、Wi-Fi 等值。`conflictFields()` 主要检查标题、网站、用户名、密码、备注、OTP、登录类型和自定义字段；上述其他字段差异可能没有出现在冲突提示中。

这不是“所有新增字段都没复制”：`exactContentFingerprint()` 已包含许多这些字段；问题是候选归并、冲突提示和最终写入采用不同字段清单，容易不一致。应由同一个字段描述表驱动三者。

### F4：关联、历史和原生语义未包含在完整项目里（高优先级）

`buildTargetEntry()` 显式清空 boundNoteId、ssoRefEntryId、归属/文件夹等引用，而没有在去重流程中构建关联图或映射表。passwordGroupId 以及自定义字段中的 group/project/password/block ID 又会随副本沿用，形成不一致的迁移策略。

原生 KeePass 无损工具已覆盖历史、自动输入、保护位、标签、颜色、过期/时间、图标、插件 customData 等；旧去重走 Room 展示投影，不能据此证明这些内容等价。Bitwarden 完整 cipher 文档包含 URIs 的 match、字段 type/linkedId、reprompt、history、organization/collections 等；去重服务未读取完整 cipher 文档。多个 URI 在显示投影中变成逗号拼接文本，不能据此还原每条匹配规则。

不能把去重副本当作无损数据库迁移或完整备份。引用存在时，最初的安全清理版本应保留项目，直到完整依赖分析与原子关系操作具备验收证据。

### F5：普通字段合并的等价规则过宽（中优先级）

字段名称 trim/lowercase、空名称过滤及 `distinct` 式消重会抹掉名称大小写、空名称有效内容、重复次数和部分顺序。字段值中的空格必须精确保留。某些系统字段看似重复，却代表不同账号或不同内容块。网址规范化会去掉协议和www前缀，且被“精确”目标摘要复用；这类宽松身份检索规则不应充当行为等价证明，HTTP/HTTPS和子域的差异需要保留。

内层自定义字段 fingerprint 使用控制字符分隔，未做长度编码；外层摘要虽有长度编码，无法消除内层串联歧义。例如 (name=a, value=b\u0001c) 与 (name=a\u0001b, value=c) 的内层编码可相同。应改成带类型和长度的规范编码，哈希只做索引，最终做结构相等确认。

`decryptComparablePassword()` 解密失败回退原字符串，未显式携带“无法比较”状态；单库判删不能沿用这个规则。不可读必须阻止清理，不能被视作普通值或空值。

### F6：执行时重新生成整个计划，未要求变更后重新审阅（中优先级）

`executePlan()` 会按原来源/目标/冲突策略再次 buildPlan，随后执行新计划。这样能重新检查目标，但用户所见结果和实际写入集合可能不同。新设计保留重读校验，若用户批准的项目/内容/去向/兼容结论有变化，必须产生新计划并回到审阅；不能把“重算”当成“自动重新授权”。

### 已存在且应保留的保护

- 目标不能同时作为来源；当前来源保留，写入为新增副本。
- 目标中“同身份但不同内容”会保留新增变体，不直接覆盖目标密码。
- SecureItem 内容摘要对完整 JSON 排序规范化，包含未知属性；并不是固定只比较旧的银行卡几个字段。
- 已枚举到的附件有内容摘要、读取失败标记、复制后核对与新副本失败回滚。
- Passkey 已有限制设备密钥、绑定密码、非零计数器等的独立 planner，不能用一般字段去重取代。
- Room-only 批次事务与外部文件补偿已分开；不能宣称多文件/多服务原子事务。

本次核对两版26条相关路径，19条字节一致，其余7条仅换行差异，全部经CRLF/LF规范化后相同。逐文件哈希及源码证据位置见 `dedup-engine-design-325-source-audit.json`。本次不修改旧实现，因此上述缺口仍待修复。

## 3. 比较的最小单位：项目及其完整内容图

来源限定为一个真实数据库实例/Bitwarden 账号与服务器，不是“当前列表看起来在一个文件夹”。通过已验证 project/group 元数据组装逻辑项目；没有显式关系的独立账号行不得仅因标题相同被拼成一个项目。堆叠显示、replica 展示、真实账号组必须分开处理。

读取结果分为 Complete、Incomplete、Unsupported、Unreadable、Locked、Changed。只有 Complete 可以进入删除证明；空库与未加载要有不同状态。扫描使用专门的后台读取会话，不依赖列表缓存、是否最近打开过详情或 Room 镜像是否碰巧存在。记录集合、成员关系、字段、附件及引用必须来自同一有效修订。

每个快照包括：完整来源身份与修订、项目成员、带类型内容、关系图、原生属性、全部字段覆盖清单，以及用于写入前核对的证据。原始敏感内容只在受控会话中读取；默认不输出到日志、草图、URL、崩溃报告或普通磁盘缓存。

| 内容 | 判等/包含条件 | 不允许的简化 |
|---|---|---|
| 标题、登录类型、身份与归属上下文 | 精确校验；不同标题/文件夹/归档/收藏等先要求检查 | 只看网址或“相似度高” |
| 多个账号、多个密码 | 每个账号保留用户名、认证范围、密码多重集、主次/标签、OTP和备注的对应；一对一注入映射 | 把用户名和密码分别扁平化后比较 |
| 备注和笔记 | 原文、格式、嵌入资源及绑定关系；多个笔记分别保留 | 子字符串包含、去空格后相同 |
| 普通/隐藏/布尔/关联字段 | 类型、原始名称、精确值、保护状态、重复次数及顺序约束 | 标题小写化，false=空，linked=普通文本 |
| 身份、证件、卡片、支付、联系人、地址 | 完整结构，包括钱包模板、附加字段、卡面、有效期、PIN等 | 仅卡号/证件号相同 |
| API Key/Token、SSH/GPG、二维码等块 | 先核验头/分片数量/摘要/版本，再比完整 payload 和未知属性；纯内部 ID 按合法映射重命名 | 独立合并分片，忽略未知属性或二维码模板 |
| OTP/Steam等 | secret 加类型、算法、位数、周期、计数器、PIN及账户关系；只规范化协议明确等价的编码 | 只比较当前验证码或 secret |
| Passkey | credential ID、RP、用户句柄、私钥/引用能力、计数器、绑定和保护策略 | 相同网站、相同公钥就删除；迁移设备绑定密钥 |
| Wi-Fi/SSO/应用匹配 | 完整配置、身份引用与匹配行为 | 相同显示名称或SSID即相同 |
| 附件、照片、图标、卡面 | 名称、角色、MIME、保护属性、实际完整内容/长度、重复次数、关联；先hash候选再核验 | 相同文件名/大小/下载记录即相同 |
| 历史、过期、自动输入、插件内容 | 完整保留并证明可表达；独有历史或属性阻止清理 | 忽略“UI看不到”的内容 |

不能先把所有数据强行转换成最低共同格式再比较。未知字段仍计入覆盖清单：已注册的无损不透明节点只能做严格完整相等，含未知引用/未知结构或无法证明恢复能力时为 Unsupported。新模型属性必须显式注册为内容、引用、原生行为、操作元数据或来源身份；CI 对未分类字段失败，默认不忽略。

空值仅在该字段的 schema 明确定义为“未填写”时才可按缺失处理；空字符串、空白密码、0、false、null、字段不存在不可全局等同。日期中的凭据过期/有效期是内容。创建/修改时间等来源审计元数据不要求两条重复记录数值相同，但必须保存到恢复记录；不能伪装为从未存在过。

## 4. 包含关系与保留集合

定义 B ⪯ A：存在一个保持类型、字段角色、账号绑定、保护语义、有效顺序和关系边的单射，将 B 的所有必需节点完整映射到 A；B 中任何内容都不能被 A 的不同账号或不同内容块拼凑覆盖。重复元素采用多重集，不是 Set。顺序允许 A 额外插入内容，但 B 的已知有意义的相对次序必须保留。

步骤：

1. 对完整快照建立按来源、类型与保守身份划分的候选索引。域名大小写等宽松规则只能扩展检索，不授权删除。空项目、身份不明、引用不明直接进入保留/需检查。
2. 对候选进行结构比较，生成字段到字段的覆盖证书及不能覆盖的差异。哈希使用会话随机密钥的HMAC作为内存索引，不落盘稳定密码hash；哈希碰撞仍需精确内容比较。
3. 完全相同项目先选一个稳定代表；用户可查看和调整，但变更保留者后重算。时间新旧/完整度可影响展示排序，不能跳过内容证明。相同内容不同外部引用的条目不属于这个可删等价类。
4. 严格包含形成有向无环关系。选择最大项目集合；每个删除候选最终必须直接证明被其中一个存活项目覆盖。拒绝互删、循环依赖、只依赖待删项目或多项目联合覆盖。
5. 用户取消某个候选不影响其他项目的证书；用户选择另一个保留项目必须重新验证相关证书。批次中 keeper 永远不能同时是删除目标。

性能：后台可取消、有限并发、逐库处理；附件按有界流读取并绑定来源修订；大桶分页/分批并显示未完成数量。超过工作量/内存预算不能截断后声称已完成或批准删除。HMAC和临时明文随锁定/离开清除，迟到结果不得重建可执行计划。

## 5. 单库清理：可恢复写入协议

扫描是只读。结果页默认不选中任何项目，用户选择后查看“将移入回收站 B；保留 A”的具体内容及核验结果。永久删除和清空回收站不属于此功能。

待实现状态机：扫描 → 审阅 → 锁定用户选择 → 建立恢复点 → 在写入保护内重读核验 → 软删除 → 读回验证 → 可恢复结果。取消发生在原子提交前时不提交；取消/崩溃发生在提交后通过日志核对，不把“未收到响应”当失败自动重做。

恢复点不是普通日志或临时撤销提示。保存加密的完整项目成员、原生内容/附件/历史、引用、来源修订、原位置、计划ID、操作清单与内容校验，并实际解密读回验证。敏感数据复用现有可信加密和备份能力，不新造算法。附件缺失/密钥或格式无法恢复就不清理。恢复点不会随普通回收站定时清理自动消失；提供空间占用和用户显式管理。它也不是所有硬件密钥永久丢失场景的恢复保证。

恢复操作默认不覆盖保留项目及后续编辑；原ID仍在回收站则恢复原对象，ID冲突/对象已变进入独立恢复副本审阅，依赖关系须一致。恢复前后的实际内容与引用都要核对，不能只测试“恢复按钮能点”。

执行前核验：来源授权/全局锁代/逐库锁代、来源和目标实例身份、数据库修订、完整项目成员与字段摘要、附件可读性、引用/历史集合、keep/delete集合，以及用户批准的精确计划。任何实质变化失效并返回审阅。禁止后台自动扩大删除名单。

| 后端 | 安全执行前提 | 前提不足时 |
|---|---|---|
| Monica 本地 | 在同一 Room 事务中重新读取、验证全部成员/字段/关系并软删除；加密恢复点先完成 | 保留原项，报告具体原因 |
| MDBX | 原生写事务与修订校验，项目成员/附件/关系一次提交；使用实际MDBX能力而非只改Room投影 | 只分析，禁用清理 |
| KeePass | 完整原库读取；一次原生文件变更，所有批次共享基线；原有只读/修订/外部更改/锁定规则；回收站启用；保留恢复副本并读回 | 回收站关闭时不退化成永久删除；无可靠独占/条件写入的远端提供方不宣称无竞态 |
| Bitwarden | 必须解决服务器版本条件删除、组织/账号权限、完整cipher/附件/历史备份和响应不确定处理 | 初版提供分析与逐项查看，不开放批量安全清理 |

特别注意：当前 `BitwardenApi.deleteCipher()` 只有 authorization 和 cipherId 参数，没有修订条件参数。单纯“删除前GET一次”不能排除另一客户端在GET和DELETE之间改动，不能标成无误删保证。不得为实现清理擅自改用永久DELETE或关闭同步保护。今后支持此类后端时，要么有服务端可验证条件删除，要么明确设计经过用户审阅的不同保障级别；不能冒充与本地原子事务同等安全。

跨文件操作按文件独立批次；不宣称全局原子事务。保存精确成功/失败/未知结果；失败重试先核对计划和已执行日志，不能重复创建副本或重复发送未知结果的删除。

## 6. 跨类型数据库合并

采用“源格式完整快照 → 类型化语义比较＋原生保留区 → 目标能力检查 → 写入计划 → 实际读回”的流程。现有 `KeePassLosslessTransfer`、完整 Bitwarden cipher 文档及可靠 transfer 路径可复用，但先审计其适用范围；已有同格式无损比较器不能直接当作跨格式等价证明。

每个来源节点都应有明确结果：原生保留、经认证的可逆扩展保留、阻止写入。任何未分类节点使项目不具备完整写入资格。若目标能存字符串却不能执行原行为，例如 linked field、auto-type、URL match，不算语义兼容。

| 内容 | 兼容策略 |
|---|---|
| Monica 内容块/账号组 | 作为完整项目迁移；事务内生成新project/group/password/block ID，更新所有引用并验证重开编辑；不沿用来源ID碰撞 |
| KeePass → KeePass（将来加入目标） | 优先完整原生Entry与资源转移，保留历史、附件、插件和保护属性；UUID冲突检查 |
| KeePass → Monica/MDBX | 逐项映射标准字段；自动输入/引用/未知插件数据等无等价目标语义时阻止完整合并，不偷偷改成备注 |
| Bitwarden → Monica/MDBX | URI数组＋每条match、typed/linked字段、reprompt、历史及组织权限单独核验；原始cipher用于完整读取和恢复，不能作为“不管目标能否使用”的免责存档 |
| Monica/MDBX → KeePass/Bitwarden（将来加入目标） | 显式能力门控；字段长度/分片、重复名称、类型、保护、结构和附件全部检查；目标不支持则不提供该项目的完整写入 |
| Passkey/硬件引用 | 沿用专门的可迁移性判定；硬件引用、非零计数器、不同credential身份不通用合并 |

跨库的完全包含可以减少目标副本；部分重合且各有独有内容默认写成两个独立项目。“人工合并”应是另一个明确动作：展示所有差异，允许保留多个密码/块及独有字段，重新建立结构，严禁仅按“最新/最完整”静默丢弃冲突值。

目标已存在判断同样使用完整内容及语义比较。来源被保留不等于可以输出一个不完整目标并提示成功；报告区分“完整写入”“内容已完整存在”“因不兼容保留来源”“读取失败/未完成”。默认绝不提供合并后删除来源选项。

## 7. 界面设计

本地 M3E Canvas 项目：`dedup-engine-325-design`。完整 JSON、含docz链接与预览只保存在忽略的 `docs/design/dedup-engine-325/`，不进入Git。使用现成 tabs、select、listItem、checkbox、button，跟随 Monica 配色、圆角和通用过渡。

五页草图：单库选择、分析结果、逐项覆盖与确认、跨库兼容检查、完整性明细。入口两个模式“单库去重／多库合并”，不堆叠高级选项。单库只选一个库；归档默认关闭，回收站永不参与。未解锁显示解锁入口，未读完显示实际阶段，不显示虚构百分比。

结果分“可清理／需检查／保留”，每项显示来源路径、完整项目范围、被谁包含、共有内容及保留方额外内容。未知/未读取情况说明原因和下一步；密钥默认隐藏，查看明文复用既有认证。结果中没有“强制删除无法验证项”的捷径。

底部固定本次动作及数量；大字号/长文允许自然换行；状态栏保持单层高度动画，锁定立即清除敏感内容。最终确认、扫描失效、空间不足、只读、取消、部分成功、响应未知、恢复冲突均需独立原生验证。图中的数量及字母是合成设计示例，不是用户数据。

进入实现时新增所有字符串资源并覆盖当前18配置/两版本；草图中文不是生产硬编码。恢复页应作为持续入口，成功页链接本次批次，而不只依赖几秒钟的撤销提示。

## 8. 实现顺序和必须通过的验证

1. 先补现有跨库引擎完整源快照、项目关系、字段覆盖与兼容报告；危险旧策略对复杂项目加保守门控，不能先打开单库删除。
2. 实现纯比较核心与不可变计划，接入两种模式的只读结果页。单库范围内证明B被某个最终保留项完整覆盖。
3. 实现并实际恢复验证 Monica/MDBX/KeePass 的批次恢复与软删除协议；每种 provider 达标才启用能力。
4. 最后扩展其他目标和有远端并发的清理能力。Bitwarden 的条件删除缺口必须明示并解决，不能用更多客户端检查掩盖服务端窗口。

必须用修前失败/修后通过的真实生产路径测试：原生KDBX UI ID及独立快照字段/附件、不同密码的账号组、相同块ID不同payload、重复credential/order、原生unknown/history/auto-type、Bitwarden多URI/match/linked/boolean/reprompt/raw cipher、SSO与bound-note、卡面/登机牌/二维码模板/自定义图标，以及所有新字段。

算法负例：ABC、完全相同三份、包含链、互相覆盖、联合覆盖陷阱、账号密码错配、字段同值不同名/类型/保护、重复次数、空白/控制符/Unicode、未知版本/损坏分片、字段只差一项、不同OTP参数、Passkey ID/计数器、相同附件名不同字节、独有历史/引用、跨域/大小写敏感账号、不同文件夹/标题、不可读/锁定/0与false。

写入故障注入：扫描后修改/切库/锁定/重认证、keeper被删除、同步改动、备份不足/读回失败、回收站关闭、进程在各提交点停止、网络响应未知、同批多成员中途失败、跨文件部分成功；恢复后原始内容/附件/关系/历史必须一致，重复点击/重试不多删/不多写。普通与FD独立构建/JVM/设备，所有支持的来源→目标组合分别验收，旧测试与新原生路径分开计数。

设计模型 `model-check.py` 已通过30个命名场景和3375组小集合三项目穷举，检查ABC、次序不变、重复数、完整性门控和每个被删项目直接指向存活覆盖者。它接受已解析的语义原子，未实现真实字段解码、关系图嵌入、数据库写入或Android UI，不能当成生产测试通过。初次模型脚本有两处多余括号，修正后运行成功，未改变场景或放宽断言。

交付为设计与审查文档、可编辑草图和模型验证记录。没有生产修复完成、无误删认证、用户数据库操作、GitHub提交/推送或APK交付的声明。

## 9. 源码定位（当前快照）

下列行号用于审查定位；随后代码变更以来源哈希为准。

| 文件 | 入口与行号 |
|---|---|
| `app/src/main/java/takagi/ru/monica/data/dedup/DedupMergeService.kt` | `override suspend fun buildPlan`：181；`override suspend fun executePlan`：343；`private fun mergePasswordGroup`：410；`private fun mergeCustomFields`：448；`private fun conflictFields(`：518；`private fun exactContentFingerprint(entry`：543；`private fun buildTargetEntry`：580；`private fun decryptComparablePassword`：982；`private fun fingerprint`：988 |
| `app/src/main/java/takagi/ru/monica/data/dedup/DedupModels.kt` | `sealed class DedupMergeTarget`：24 |
| `app/src/main/java/takagi/ru/monica/data/dedup/DedupMergeExecutor.kt` | `override suspend fun writePassword`：43；`internal class DedupMergeExecutor`：110 |
| `app/src/main/java/takagi/ru/monica/data/dedup/DedupAttachmentSupport.kt` | `suspend fun snapshot`：40；`suspend fun copy`：84 |
| `app/src/main/java/takagi/ru/monica/data/dedup/DedupPasswordIdentity.kt` | `fun key`：8；`fun normalizeWebsite`：29 |
| `app/src/main/java/takagi/ru/monica/data/PasswordEntry.kt` | `data class PasswordEntry`：31 |
| `app/src/main/java/takagi/ru/monica/data/CustomField.kt` | `data class CustomField(`：36 |
| `app/src/main/java/takagi/ru/monica/data/SecureItem.kt` | `data class SecureItem`：28 |
| `app/src/main/java/takagi/ru/monica/data/model/ProjectCredentialGroup.kt` | `fun read`：35；`fun parse`：42 |
| `app/src/main/java/takagi/ru/monica/data/model/EntryContentFields.kt` | `fun order`：21 |
| `app/src/main/java/takagi/ru/monica/data/model/PasswordContentBlocks.kt` | `enum class Kind`：14；`fun read`：36 |
| `app/src/main/java/takagi/ru/monica/repository/PasswordRepository.kt` | `fun getAllPasswordEntries`：57 |
| `app/src/main/java/takagi/ru/monica/repository/CustomFieldRepository.kt` | `suspend fun getFieldsByEntryIds`：45 |
| `app/src/main/java/takagi/ru/monica/repository/SecureItemRepository.kt` | `fun getAllItems`：94 |
| `app/src/main/java/takagi/ru/monica/repository/PasskeyRepository.kt` | `suspend fun getAllPasskeysSync`：53 |
| `app/src/main/java/takagi/ru/monica/keepass/KeePassMonicaPasswordStore.kt` | `val attachmentRows`：86；`fun fields(id`：471 |
| `app/src/main/java/takagi/ru/monica/keepass/KeePassLosslessTransfer.kt` | `fun entriesEquivalent`：254 |
| `app/src/main/java/takagi/ru/monica/bitwarden/service/BitwardenCipherDocument.kt` | `private val topNames`：16；`private val uriNames`：18；`private val fieldNames`：19 |
| `app/src/main/java/takagi/ru/monica/bitwarden/service/CipherSyncProcessor.kt` | `private fun parseLoginUris`：1978；`private fun decryptCustomFields`：1934 |
| `app/src/main/java/takagi/ru/monica/bitwarden/api/BitwardenApi.kt` | `suspend fun deleteCipher`：265 |
| `app/src/main/java/takagi/ru/monica/viewmodel/DedupEngineViewModel.kt` | `fun executeMerge`：192 |
| `app/src/main/java/takagi/ru/monica/ui/screens/DedupEngineScreen.kt` | `fun DedupEngineScreen`：123 |
| `app/src/main/java/takagi/ru/monica/ui/components/PasswordContentMenu.kt` | `enum class PasswordContentSection`：24 |

本地交互草图已检查五页实际渲染和选择→结果→明细→完整性、双模式切换路径。草图确认按钮未连接数据库，不能替代Android执行/无障碍/大字号验收。预览中的“未勾选时禁用按钮”仅记录在设计注释中，Canvas未实现此业务状态机，原生实现必须补齐并测试。

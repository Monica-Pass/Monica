# Monica Android：MDBX 与密码数据格式对齐说明

核对日期：2026-10-05。Android 基线：`Monica-Pass/Monica`，commit `8334e6bff9d08529f5d88b911b57a9d0a2579997`，1.0.317 开发版本。

用途：交给 Monica CLI 开发者，按 Android **当前实际读写行为**实现互通。本文只整理格式，不修改 Android 或 CLI。下文区分「已实现」「当前缺口」「CLI 建议」，不把建议当成已有协议。示例均为虚构数据。

## 1. 先确定对齐哪一层

| 层次 | 当前形式 | CLI 应如何处理 |
| --- | --- | --- |
| “Monica 本地” | Android Room/SQLite，文件名 `password_database`，Room schema version 79 | 是应用本地数据库，不是 MDBX；密码等值可能使用 Android 本地密钥加密 |
| Room 中的 MDBX 条目 | `PasswordEntry`、`SecureItem`、`PasskeyEntry` 的本机投影，加上 `local_mdbx_databases` 来源索引 | 不把 Room 主键或文件路径当作跨端身份 |
| 旧 Kotlin MDBX1 | `KOTLIN_MDBX1` / `MdbxVaultStore` | 当前仅保留管理、迁移兼容；不是新 CLI 写入目标 |
| 当前 Rust MDBX | Android 枚举仍叫 `RUST_MDBX2`，类仍叫 `Mdbx2Repository` | 通过 MDBX Rust/FFI 打开、读写、同步 |
| 当前打包引擎 | MDBX3 `3.0.0-alpha.1`，**可写存储格式仍是 `MDBX-2`** | 不根据类名推断格式，也不要发明 `MDBX-3` 文件头 |
| Android 全量备份 | JSON/CSV、附件及其他备份内容，可能再加密成备份包 | 是另一种交换格式，不等同于 MDBX JSON payload |

Android 打包来源见 [MDBX3_RUNTIME_PROVENANCE.json](../mdbx-engine/MDBX3_RUNTIME_PROVENANCE.json)：MDBX 上游 commit `90005c8c608c952093a4522ffa507a562e2e39a4` 加所列五个 overlay。包括 JSON 数值精度、字面键名、复合事务等修正。仅依赖同名上游版本、未包含修正，不能自动视为等价。

部分旧文档仍写着“Android 尚未直接使用 mdbx-ffi”或 MDBX1，这些不是本基线的现状。普通版与 F-Droid 共用这里描述的密码映射；F-Droid 不提供 OneDrive。

## 2. MDBX 文件、对象与安全边界

### 2.1 文件不是明文密码 JSON

当前实现用 SQLite 保存 MDBX 数据；本次核对的 F-Droid 随附 Rust 源码 schema version 为 **17**。不要把 `schema/v1.rs` 的旧默认值或旧 13 表说明当成完整当前格式。版本由引擎探测、校验和迁移。

主要数据层包括：

- `vault_meta`：vault 身份、格式、默认 Tiga、密钥 epoch、兼容信息及后续 header 认证扩展。
- `projects`：集合/文件夹。新 API 的 `collection` 与旧 API 的 `project` 对应。
- `entries`：类型化对象；新 API 的 `object` 与旧 API 的 `entry` 对应。
- `attachments`、`attachment_chunks`：附件元数据和内容组织。
- `commits`、`commit_parents`、`object_versions`、`tombstones`、`conflicts`：历史、删除与冲突。
- 后续 schema 还包含关系、标签、集合 profile、同步 delta、snapshot lifecycle 等表。

**不要让 CLI 直接拼 SQL 更新 `entries`。** 写入还涉及密钥、AAD、对象时钟、commit、历史、删除与同步状态。应复用引擎事务 API。

### 2.2 对象外壳与业务载荷分开

| 外壳字段（Rust/旧 API） | 新 API 对应 | 含义 |
| --- | --- | --- |
| `entry_id` / `entryId` | `objectId` | 实际 MDBX 对象 UUID |
| `project_id` / `projectId` | `collectionId` | 实际所属集合 UUID |
| `entry_type` / `entryType` | `objectTypeId` | 密码为 **`login`**，不是 `password` |
| `title_ct` → 解密 `title` | `title` | 标题在外壳，不在 Android 密码 payload 中 |
| `payload_ct` → 解密 `payloadJson` | `payloadJson` | JSON 文本；里面才有 `password_plain` 等字段 |
| `payload_schema_version` | `payloadSchemaVersion` | 对象载荷版本；与文件格式、SQLite schema、运行库版本独立 |
| `deleted` | `deleted` | 原生删除状态，不靠 payload 中加 `isDeleted` 实现 |
| `object_clock`、`head_commit_id` | 原生版本/历史接口 | 引擎维护，不能用 Android `updatedAt` 替代 |
| `created_at`、`updated_at` | 原生时间字段 | 原生模型为时间字符串；不是 Room 的毫秒整数 |

`CreateEntry`/`UpdateEntry` 是 Android 密码适配器使用的旧兼容命令；通用对象接口另有 `createObject`/`updateObject`，带显式 payload schema version。CLI 更新原对象应保留实际版本、类型和身份。

### 2.3 两层加密不能混用

- Room `PasswordEntry.password`：当前 `SecurityManager.encryptData` 优先输出 `MDK|` 加 Base64 的 AES-GCM 数据；还存在 `C2|`、`V2|` 与更早的兼容形式。密钥来自 Android 应用本地安全体系。
- MDBX `password_plain`：**解密 MDBX payload 后的用户原始密码文本**。载荷整体仍由 MDBX 引擎加密；字段后缀 `plain` 不意味着 `.mdbx` 文件明文存储。
- 随附 Rust 加密实现使用 XChaCha20-Poly1305；当前 committed AEAD envelope 为 `MDBXAE1\0` magic、32 字节 commitment、24 字节 nonce、带认证标签的密文，并兼容旧 envelope。密钥派生、epoch 与 AAD 应交给引擎，不由 CLI 重新猜测。
- 当前 Android 写入 `monica_password_encoding = "plaintext-v1"`。密码恰好以 `MDK|`、`C2|` 或 `V2|` 开头时也可能只是用户文本，CLI 不应再次解密它。
- 旧版本可能把本机密文误写进 `password_plain`。Android 修复入口仅修复本安装能够认证解密的值；CLI 没有对应密钥时不能宣称恢复成功，也不能替换为空字符串。
- Android 对 **vault 解锁密码**做 Unicode NFC 规范化；不要对条目中的密码值做 trim、大小写或 Unicode 规范化。key file/组合凭据通过引擎凭据接口处理。

## 3. 身份、文件夹与多密码分组

### 3.1 三种 ID 必须区分

| 字段 | 用途 |
| --- | --- |
| Room `id`、payload `room_id` | 某个 Android 安装中的本地主键；不是全局 ID |
| payload `monica_entry_id` | Android 业务逻辑 ID；通常 `password:<后缀>` |
| MDBX `entryId` / `objectId` | Rust 实际对象 UUID |

Android 新写入的逻辑 ID：若 `replicaGroupId` 非空且以 `password:` 开头并有后缀，就原样使用；否则为 `password:<Room id>`。

物理 UUID 计算为：

```text
UUID.nameUUIDFromBytes(UTF8("monica-entry:" + vaultId + ":" + logicalEntryId))
```

这是 Java 的 **对原始字节做 MD5，再设置 UUID v3/version 与 variant 位**；不额外拼 UUID namespace。Python 可等价写成：

```python
import hashlib
import uuid

def android_object_id(vault_id, logical_id):
    raw = f"monica-entry:{vault_id}:{logical_id}".encode("utf-8")
    return str(uuid.UUID(bytes=hashlib.md5(raw).digest(), version=3))
```

根集合 ID 同理，对 `UTF8("monica-root:" + vaultId)` 求上述 UUID；标题为 `.monica-root`。不要用标题识别并合并所有同名集合。

**CLI 新建 Android 可编辑密码的建议**：生成 `password:<新随机UUID>` 作为逻辑 ID，按照上式产生物理 ID，并写入 `monica_entry_id`。这避免跨设备 Room 数字 ID 碰撞。修改已有 Android 对象则保留已有两种 ID。

Android 读取已知类型时优先用 `monica_entry_id`，缺失才用原生 UUID，并放进 Room `replicaGroupId`。但常规密码写回仍按上述 `password:` 规则生成 ID。因此，**“任意原生 UUID 的 login 能显示”不等于“Android 编辑后必定更新同一原生对象”**；CLI 不应把原生随机 UUID 直接写入 `monica_entry_id` 后就假定完成往返兼容。这是当前适配限制，需专项验证。

### 3.2 文件夹有双重表示

- 原生真实归属：`projectId` / `collectionId`。
- Android 密码投影归属：payload `mdbx_folder_id`。
- 当前 `importPasswordEntry` 从 **payload** 恢复文件夹，不使用外壳 `collectionId` 替代。
- 根目录的 payload 字段可省略/为空；读取时也把不区分大小写的 `root` 视为根。新 CLI 不应写入字面 `"root"` 作为真实集合 UUID。
- CLI 移动 Android 密码时，应在同一原生写事务中更新真实 collection 和 `mdbx_folder_id`，否则原生浏览器与 Android 密码列表可能显示不同位置。
- Android 写入时，空或不在活动集合列表中的目标 folder 会回落到其 `.monica-root`。CLI 应先确保目标集合存在且未删除。

### 3.3 多密码不是一个 `passwords` 数组

Android 多密码编辑产生**多个独立 login 对象**，通过 `password_group_id` 关联，每个对象仍只有一个 `password_plain`，并保有自己的身份。

- `password_group_id` 是业务分组 ID，不是对象 UUID，也不是文件夹 ID。
- UI 分组键还带数据库来源作用域，不应跨 vault 合并同名组。
- 不按相同标题、网站、用户名自动推断分组。
- 非 MDBX 历史条目可能把 UUID 形式的 `replicaGroupId` 当作组 ID，`explicitPasswordGroupId()` 有兼容回退；MDBX 不能照搬这一推断。

## 4. Android 密码 payload 字段表

下列键由 `Mdbx2Repository.passwordMutation` 与 `MdbxPasswordContentFields.writeTo` 实际输出。键名区分大小写。可空字段经 `JSONObject.put(key, null)` 会被省略，不能假设每个键都存在。

| MDBX payload 键 | JSON 类型 | Room 来源 / 语义 |
| --- | --- | --- |
| `kind` | string | 固定 `password`；原生对象类型仍为 `login` |
| `monica_entry_id` | string | 逻辑 ID，见上文 |
| `room_id` | integer | `id`；仅本机来源提示，CLI 不据此覆盖本地行 |
| `password_group_id` | string，可省略 | 显式多密码分组 |
| `website` | string | `website`，不要擅自转换为 URL 数组 |
| `username` | string | `username` |
| `password_plain` | string | 解密后的 `password` 原值 |
| `monica_password_encoding` | string | 当前固定 `plaintext-v1` |
| `notes` | string | `notes` |
| `app_package_name` | string | `appPackageName`；可以是多个应用编码 |
| `app_name` | string | `appName`；与包名对应 |
| `sort_order` | integer | `sortOrder`，Android Int |
| `category_id` | integer，可省略 | `categoryId`，本地分类提示，不是 MDBX 集合身份 |
| `mdbx_folder_id` | string，可省略 | 文件夹 ID；根可省略 |
| `bound_note_room_id` | integer，可省略 | 本机绑定笔记主键 |
| `bound_note_entry_id` | string，可省略 | 可移植的绑定笔记逻辑 ID，导入时再映射本机 ID |
| `login_type` | string | 通常 `PASSWORD`，另见第 6 节 |
| `ssh_key_data` | string | 内嵌 SSH JSON 文本 |
| `authenticator_key` | string | 解密后的 OTP secret/URI |
| `passkey_bindings` | string | Passkey 绑定元数据 JSON 数组的文本 |
| `custom_fields` | array<object> | 自定义字段列表，不是 JSON 字符串 |
| `bitwarden_mode` | boolean | 写入时是否有 Bitwarden 绑定；不是可恢复的 vault 身份 |
| `keepass_mode` | boolean | 写入时是否有 KeePass 绑定；不是可恢复的库身份 |
| `email`、`phone` | string | 同名属性 |
| `address_line`、`city`、`state`、`zip_code`、`country` | string | `addressLine`、`city`、`state`、`zipCode`、`country` |
| `credit_card_number_plain` | string | 解密后的 `creditCardNumber` |
| `credit_card_holder` | string | `creditCardHolder` |
| `credit_card_expiry` | string | `creditCardExpiry`，模型约定 MM/YY |
| `credit_card_cvv_plain` | string | 解密后的 `creditCardCVV` |
| `wifi_metadata` | string | 原始 Wi-Fi JSON 文本 |

应用绑定编码：Android 写入以 `|` 连接多个包名/名称；包名读取兼容 `|`、`,`、`;` 分隔。名称按 `|` 分隔。CLI 应保留原字符串或使用同一编码，不能把整段包名当作单个 Android package。

### 4.1 自定义字段

```json
[
  {"title":"安全问题","value":"虚构答案","is_protected":true,"sort_order":0},
  {"title":"备用邮箱","value":"backup@example.invalid","is_protected":false,"sort_order":1}
]
```

- Android 写出非空标题字段，按 `sortOrder`、本地 `id` 排序；不写出 CustomField 的 Room ID。
- `is_protected` 表示隐藏显示/敏感复制语义，不是另一种 MDBX 加密格式。
- 导入同时兼容 `customFields`、`label`、`isProtected`、`sortOrder`。新 CLI 应写 snake_case 标准键。
- 导入时标题 trim，空标题及非对象元素跳过；缺少排序值按数组下标。
- 缺少字段数组时保留已有字段；显式 `[]` 会清空它们。替换列表后，本地自定义字段主键重新建立。

### 4.2 缺失、空字符串和 null 并不完全等价

| 读取位置 | 当前行为 |
| --- | --- |
| `password_plain` | 存在且非 null 时读取它；否则回退 `password` 兼容键，再尝试本机解密 |
| `website`、`username`、`notes`、`authenticator_key`、`passkey_bindings` | 常规 `optString` 读取；缺失通常变为空，不是局部 patch |
| `login_type` | 缺失默认 `PASSWORD` |
| `app_package_name` / `app_name` | snake_case 优先，兼容 camelCase；缺失或 null 保留已有投影，显式空串可清空 |
| `sort_order` | 缺失保留旧值；新投影默认 0 |
| 联系/地址/支付字段 | 缺失保留旧投影；显式 null 或空串清空 |
| `password_group_id` | 缺失保留旧投影；null/空白清除 |
| `wifi_metadata` | 兼容 `wifiMetadata`；接受 string 或 object，object 转回 JSON 文本；无非 null 键时保留旧值，其他类型报错 |
| `ssh_key_data` | 有独立 codec/兼容读取逻辑；CLI 应保留原始 JSON，不自行删未知字段 |

**CLI 应以读取完整对象、只修改目标字段、写回完整载荷为默认方式。** 不能向 Android 提交一个仅含 `password_plain` 的精简对象来表示“只改密码”。

## 5. 可直接用于互通的虚构示例

这是**解密后的 payload**，不是 `.mdbx` 文件，也不是整个原生对象。标题例如 `Example account` 应通过外壳 `title` 写入，外壳类型为 `login`。本例是根目录条目，省略本机 ID、分类、绑定笔记和文件夹键。

```json
{
  "kind": "password",
  "monica_entry_id": "password:8e9a95f8-0a4b-4b65-92a2-a274221bf01d",
  "website": "https://example.invalid",
  "username": "alice",
  "password_plain": "synthetic-password-only",
  "monica_password_encoding": "plaintext-v1",
  "notes": "仅用于跨端测试",
  "app_package_name": "com.example.synthetic",
  "app_name": "Synthetic App",
  "sort_order": 0,
  "login_type": "PASSWORD",
  "ssh_key_data": "",
  "authenticator_key": "",
  "passkey_bindings": "",
  "custom_fields": [
    {"title": "测试字段", "value": "合成数据", "is_protected": true, "sort_order": 0}
  ],
  "bitwarden_mode": false,
  "keepass_mode": false,
  "email": "alice@example.invalid",
  "phone": "",
  "address_line": "",
  "city": "",
  "state": "",
  "zip_code": "",
  "country": "",
  "credit_card_number_plain": "",
  "credit_card_holder": "",
  "credit_card_expiry": "",
  "credit_card_cvv_plain": "",
  "wifi_metadata": ""
}
```

创建流程：打开/创建 vault → 取得真实 vaultId → 确认集合 → 按第 3 节算物理 ID → 经原生 `CreateEntry` 事务写入 `login`、标题和此 payload。修改流程：保持同一物理 ID、逻辑 ID、类型和版本，合并改动后更新；删除/恢复调用原生操作。

## 6. 密码条目的附属格式

### 6.1 OTP

`authenticator_key` 是字符串，常见为 Base32 secret 或 `otpauth://` URI。非默认算法、位数、周期、HOTP counter 不能只留下 secret，否则参数会丢失。`TotpDataResolver.fromAuthenticatorKey` 不把任意 JSON 对象/数组当作有效绑定密钥。

独立验证器则是原生 `totp` 对象，Android `SecureItem.itemData` 的 `TotpData` 包含 `secret`、`issuer`、`accountName`、`period`、`digits`、`algorithm`、`otpType`、`counter`、`pin` 等，以及绑定与 Steam 扩展。枚举包括 TOTP/HOTP/STEAM/YANDEX/MOTP。它与 login 内的 `authenticator_key` 是两种表示；CLI 暂不支持的 OTP 类型/扩展应原样保留，不能降级重编码为默认 TOTP。

### 6.2 Wi-Fi、SSO 与密钥

- Wi-Fi 使用 `login_type="WIFI"`；标题通常是 SSID，`username` 为企业身份，`password_plain` 为口令；`wifi_metadata` 是内层 JSON 文本。
- `WifiData` 字段包括 `ssid`、`hiddenNetwork`、`security`、`eap`、`macRandomization`、`proxy`、`ip`、`bssid`。枚举/密封类的精确序列化以 `WifiData.kt` 为准；不支持完整表单时保留 raw JSON。
- SSO 使用 `login_type="SSO"`，但 **当前 MDBX writer 没有输出 `ssoProvider` 和 `ssoRefEntryId`**。不能认为仅保留 SSO 类型就保留了提供商与账号关系。
- 模型还识别 `GPG_KEY`、`API_KEY`；它们不应作为网站/应用登录密码候选。不要把所有 `login` 对象都解释成可自动填充的普通登录。
- `ssh_key_data` 内层 schema 为 `monica.ssh-key.v1`，包含 `algorithm`、`keySize`、`publicKeyOpenSsh`、`privateKeyOpenSsh`、`fingerprintSha256`、`comment`、`format`、`schema`。codec 保留额外字段。包含私钥时同样依赖外层 vault 加密。
- 原生 `ssh-key`、`api-token` 等类型与 login 的内嵌扩展不是同一个数据模型，不能擅自互换。

### 6.3 Passkey

`passkey_bindings` 是 JSON 数组的**字符串**；元素为 `credentialId`、`rpId`、`rpName`、`userName`、`userDisplayName`。它仅表示关联信息，**不含可签名私钥，不能单凭此字段恢复 Passkey**。

完整 Passkey 为独立 `passkey` 对象，Android writer 另写 credential、RP、user、公钥算法、公钥、解析后的私钥材料、transports、aaguid、sign_count 等。暂未实现凭据安全导入的 CLI 应保留对象及关联，不能只复制 bindings 后声称 Passkey 已迁移。

### 6.4 附件与笔记

附件是原生独立记录，关联实际 collection/entry，并可能引用外部加密块；不在 `custom_fields` 中存放图片文件路径代替附件。Android 外部存储使用工作副本及经过校验的 portable backup，并在可访问时镜像同级 `<vault-name>.blobs` 目录。

CLI 搬运 vault 应调用引擎 portable backup/export API，并包含必要外部块；直接复制正在使用的 SQLite 主文件可能遗漏 WAL 与附件。本地 `secure_attachments` 路径、SAF URI、Android 包名目录都不是跨端有效路径。

绑定笔记优先使用 `bound_note_entry_id`；Room `boundNoteId` / `bound_note_room_id` 只在某次安装内有效。目标笔记导入后再解析关系，不把原手机数字 ID 直接写到新端。

## 7. Room 的完整密码模型与当前映射缺口

Room `password_entries` 同时服务本地、KeePass、Bitwarden 与 MDBX，不是 MDBX 格式定义。`custom_fields` 是独立表，通过 `entry_id` 外键关联、级联删除。

下面这些字段存在于 `PasswordEntry`，但当前 MDBX 密码 writer 没有完整携带：

| 字段 | 当前边界 |
| --- | --- |
| `createdAt`、`updatedAt` | 未写入 login payload；导入保留已有 Room 时间，新投影使用当前时间，未映射原生对象时间 |
| `isFavorite`、`isGroupCover` | 导入保留已有投影，新条目默认 false；没有对应密码 payload 写入 |
| `isArchived`、`archivedAt` | 未写入该 payload，也未在常规密码导入构造中恢复；不能保证归档跨端保留 |
| `customIconType`、`customIconValue`、`customIconUpdatedAt` | 未由该 password writer 输出，不能假定图标随密码无损往返 |
| `ssoProvider`、`ssoRefEntryId` | 未输出，见前文 |
| `isDeleted`、`deletedAt` | 删除由原生 Delete/Restore/tombstone 表达；不能用 payload 字段替代，Room 删除时间不是跨端契约 |
| `categoryId` | 虽写 `category_id`，导入保留当前本地分类，而不是采用远端数字 ID |
| `keepassDatabaseId`、`keepassGroupPath`、`keepassEntryUuid`、`keepassGroupUuid` | 其他 provider 的本机绑定，不随此 login payload 重建 |
| `bitwardenVaultId`、`bitwardenCipherId`、`bitwardenFolderId`、`bitwardenRevisionDate`、`bitwardenCipherType`、`bitwardenLocalModified` | 同上，不能只靠 mode 布尔值恢复 |
| `mdbxDatabaseId` | 本机库索引，打开当前 vault 时重新绑定 |
| `replicaGroupId` | 导入接收 MDBX 逻辑 ID；不能通用地解释为多密码组 ID |

“Monica 本地”通常指三个外部 owner ID 都为空的条目；代码还包含处理历史 Bitwarden/KeePass 不完整绑定的分支。MDBX 与其他 provider 同时绑定会被视为 ownership conflict。CLI 不需要模仿这些本地 owner 字段来创建 MDBX 对象。

**未知字段限制**：当前常规 Android `passwordMutation` 从空 `JSONObject()` 重建已知键，没有通用地把既存 login payload 的全部未知键合并回去。Wi-Fi/SSH 内层有保留 raw/额外字段的专用逻辑，这不等于顶层所有扩展都会保留。CLI 自身应无损保存未知键，但不能单方面保证未知 login 扩展经过 Android 普通编辑后还存在。

**未知类型限制**：Android 将不支持的原生类型投影成 `MDBX_UNKNOWN:<type>` 只读占位，禁止经普通密码适配器编辑。占位中的空 password 不是原生密码为空。CLI 应保留原对象类型、payload、附件、关系和版本，不能把占位重新保存为 `login`。

## 8. 全量备份 JSON 与 MDBX 的区别

如果 CLI 对接的是 Android 全量备份，而不是 `.mdbx` 文件，应另外走备份导入路径。

- `WebDavHelper.PasswordBackupEntry` 使用 **camelCase**：例如 `password`、`authenticatorKey`、`customFields`、`passwordGroupId`、`wifiMetadata`，不是本文件第 4 节的 snake_case。
- 备份 writer 将对象写为分类目录内的 `password_<id>_<createdAt毫秒>.json`，不是一个固定的 MDBX entries 表转储。
- `createdAt`、`updatedAt`、`archivedAt` 在备份 DTO 中为毫秒整数。
- `customFields` 的备份元素为 `title`、`value`、`isProtected`，与 MDBX 的 `is_protected`、`sort_order` 不同。
- 备份 DTO 包含 SSO、图标、归档等 MDBX 密码适配器尚未完整输出的字段，不能机械按键名转换后声称没有损失。
- 备份 writer 直接接收传入条目的 `password` 等值；CLI 不能仅凭“已经解开外层 ZIP/加密包”断言每个字段都是明文。需核对导出入口的正规化及 `BackupRestoreApplier` 的恢复路径，并检测本地密文边界。

本文未把整个备份包格式、密钥恢复或全部 SecureItem 类型定义为已完成的 CLI 契约；需要这些入口时继续以对应源码和备份样本单独验证。

## 9. 给 CLI 的实施顺序与验收清单

推荐先完成 **MDBX 引擎兼容 + Android login payload + 稳定 ID/目录往返**，再扩展其他类型。不要直接接管 Android Room 数据库。

1. 读取格式与能力，匹配当前引擎/overlay；使用有界分页摘要，用户确实查看时再通过 reveal/disclosure API 读取秘密。
2. 分别保存原生对象外壳、原始 JSON 及附件关系；保留未知字段、JSON 大整数/精确小数、字面键名和 payload 版本，不转成浮点后重写。
3. 识别 `login`、`kind=password` 及 Android 逻辑 ID。对于无 Android 身份约定的原生 login，明确标记常规编辑往返尚需验证。
4. 原样读取 `password_plain`，理解嵌套 JSON 字符串、自定义字段及多密码分组；实现部分编辑时合并完整原 payload。
5. 经引擎事务创建/修改/移动/删除，重试保持操作幂等，冲突通过引擎暴露与解决，不以最后一次整文件覆盖代替冲突处理。
6. 首先验证文件/portable backup 互通；若要加入自动云同步，再单独对齐 `Mdbx2SyncEngine`、`Mdbx2RemoteSyncCoordinator` 的 delta/bootstrap/blob 协议。Android 本机 sidecar `mdbx2-sync-sidecar-v1` 不是 login 数据 schema。

建议实际运行以下验收；**本次仅整理文档，未重新运行这些测试，也未宣称 CLI 已完成对齐**：

- Android 新建 → CLI 读取/修改 → Android 刷新：标题、密码、URL、用户名、备注、Unicode、换行、自定义字段一致。
- CLI 新建带 Android ID 约定的 login → Android 修改 → CLI 检查：对象数不增长，物理 ID、逻辑 ID 与 vaultId 不变。
- 根目录及多层目录移动：collection 与 `mdbx_folder_id` 一致；目录缺失、已删除时行为明确。
- 同组两条密码、不同库同组名、独立同名账号：不意外合并，不丢任一密码。
- 空值/缺失/null、清空字段数组、旧 camelCase、自定义字段排序：符合第 4.2 节。
- 密码本身长得像 `C2|...`，以及真正历史密文：不二次解密、不覆盖为空。
- OTP 非默认参数、HOTP counter、Wi-Fi raw JSON、SSH 额外字段、Passkey 绑定：不误降级，不把元数据当完整密钥。
- 原生未知类型、未知字段、大整数、精确小数、字面 serde marker 键：不破坏；额外核对 Android 普通 login 编辑的已知缺口。
- 删除/恢复、两端并发修改、重复操作、断线重试、缺附件块：不静默丢记录或误报同步成功。
- 冷启动/重新打开 vault 后再次核对，排除只更新内存/Room 投影而没有更新 MDBX 的假成功。

现有跨端测试起点：[mdbx-cli-contract-README.md](../app/src/androidTest/assets/mdbx-cli-contract-README.md)。其中记录 CLI 生成的合成 fixture、SHA-256 和 `scripts/check_android_contract.py` 校验入口；新对齐应保留独立 CLI 期望值作为验证依据。

## 10. 源码索引

以下路径相对 Android 工程，便于 CLI 维护者复查，而不是依赖过时设计文档。

| 文件 | 关键证据 |
| --- | --- |
| [PasswordEntry.kt](../app/src/main/java/takagi/ru/monica/data/PasswordEntry.kt) | Room 完整密码实体 |
| [PasswordDatabase.kt](../app/src/main/java/takagi/ru/monica/data/PasswordDatabase.kt) | Room schema 与数据库名 |
| [CustomField.kt](../app/src/main/java/takagi/ru/monica/data/CustomField.kt) | 自定义字段及本地关联 |
| [LocalMdbxDatabase.kt](../app/src/main/java/takagi/ru/monica/data/LocalMdbxDatabase.kt) | 引擎枚举、旧库可用性、来源与解锁方式 |
| [MdbxRepositoryFactory.kt](../app/src/main/java/takagi/ru/monica/repository/MdbxRepositoryFactory.kt) | `mdbxPasswordObjectId`、物理 UUID 算法 |
| [Mdbx2Repository.kt](../app/src/main/java/takagi/ru/monica/repository/Mdbx2Repository.kt) | `passwordMutation`、`upsertMutationsInVault`、`logicalEntryId`、附件/Passkey |
| [MdbxPasswordContentFields.kt](../app/src/main/java/takagi/ru/monica/repository/MdbxPasswordContentFields.kt) | 联系/地址/支付/Wi-Fi 字段及缺失语义 |
| [MdbxViewModel.kt](../app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt) | `importPasswordEntry`、`restoreCustomFields`、`optMdbxFolderId` |
| [Mdbx2VaultSessionExecutor.kt](../app/src/main/java/takagi/ru/monica/repository/Mdbx2VaultSessionExecutor.kt) | 根集合、NFC、解锁与写入会话 |
| [PasswordProjectIdentity.kt](../app/src/main/java/takagi/ru/monica/data/PasswordProjectIdentity.kt) | 多密码分组作用域 |
| [PasswordEntryAppBindings.kt](../app/src/main/java/takagi/ru/monica/data/PasswordEntryAppBindings.kt) | 多应用包名/名称编码 |
| [PasswordOwnership.kt](../app/src/main/java/takagi/ru/monica/data/PasswordOwnership.kt) | 本地与外部 provider 归属 |
| [SecurityManager.kt](../app/src/main/java/takagi/ru/monica/security/SecurityManager.kt) | 本机密文格式与 MDK/Keystore 边界 |
| [TotpDataResolver.kt](../app/src/main/java/takagi/ru/monica/util/TotpDataResolver.kt) | OTP secret/URI 与参数 |
| [SecureItemModels.kt](../app/src/main/java/takagi/ru/monica/data/model/SecureItemModels.kt) | 独立 OTP 等内层模型 |
| [WifiData.kt](../app/src/main/java/takagi/ru/monica/data/model/WifiData.kt) | Wi-Fi 内层序列化 |
| [SshKeyModels.kt](../app/src/main/java/takagi/ru/monica/data/model/SshKeyModels.kt) | SSH schema 及未知字段保留 |
| [PasskeyBinding.kt](../app/src/main/java/takagi/ru/monica/data/model/PasskeyBinding.kt) | 绑定元数据，不是私钥 |
| [MdbxUnknownEntry.kt](../app/src/main/java/takagi/ru/monica/repository/MdbxUnknownEntry.kt) | 未知类型只读投影 |
| [Mdbx2ExternalStorage.kt](../app/src/main/java/takagi/ru/monica/repository/Mdbx2ExternalStorage.kt) | portable backup、外部附件、工作副本 |
| [WebDavHelper.kt](../app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt) | 全量备份 DTO 与导出路径 |
| [BackupRestoreApplier.kt](../app/src/main/java/takagi/ru/monica/utils/BackupRestoreApplier.kt) | 备份恢复与字段正规化 |
| [mdbx_ffi.kt](../mdbx-engine/src/main/java/uniffi/mdbx_ffi/mdbx_ffi.kt) | Android 实际生成的 FFI API |

Rust 底层复查位置：同工作区 `Monica-main/fdroid/Mdbx-ffi/crates/mdbx-storage/src/schema/`、`mdbx-core/src/model/entry.rs`、`mdbx-crypto/src/aead.rs`；上游与打包差异以 provenance 文件为准。CLI 不应把 Android 根目录的旧 `mdbx/docs` 自动视为当前打包引擎的唯一依据。

## 11. CLI 首批落地记录（2026-10-05）

同工作区 `monica-pass-cli` 已实现 v1 `login/kind=password` 适配器：`passwords create/info/edit` 本地命令、稳定 Android ID、完整载荷合并、expected-head 冲突保护，以及通过现有 `move` / `delete` 入口操作目录与原生墓碑。新建为 PASSWORD，已有 PASSWORD/WIFI/SSO 保留子类型；未知类型/版本及不匹配 Android 身份约定的条目保持只读。字段只经隐藏输入或可信 `--secrets-stdin` 传入，命令只回摘要；没有增加 MCP 取密、CLI 明文输出或剪贴板能力。操作说明在 CLI 的 `docs/android-passwords.md`。

普通版与 F-Droid 的 `MdbxCliPasswordAlignmentTest` 已在公共 API 32 AVD 通过。两版均由 CLI 生成合成库，经实际 `Mdbx2Repository` 与各自打包 FFI 读取、连续编辑和新建，再由 CLI 使用保留在 host 的独立 manifest 校验并编辑，最后 Android 重开校验。两个同名同组账号不合并、不因重复写入增加对象；ID、目录、字面 `C2|` 密码、Unicode/换行、自定义字段及非默认 HOTP URI 的往返得到覆盖。测试实际运行 arm64-v8a 库于 x86_64 AVD 的兼容环境，未使用真实凭据。

本次附件探针确认：依赖引擎的 `MoveEntry` 只改条目 collection，不改附件 collection。因此 CLI 在共享写事务内拒绝有关联活动/已删除附件的跨目录移动（`attachment_move_unsupported`），保留原数据；无附件密码的 collection 与 `mdbx_folder_id` 在同一事务更新。没有通过直接修改数据库表来绕过引擎，也没有宣称完成附件迁移。

上述结果不替代第 9 节的完整验收清单：测试构造临时 Room 投影，尚未覆盖整个 ViewModel/UI 导入、实体手机、真实云同步、历史 Keystore/MDK 密文恢复或缺附件块的密码业务流程。CLI 保留历史密文字节但不解密；引擎原生 restore 有单元测试，CLI 没有新增恢复命令。第 7 节列出的 Android writer 未知顶层字段和本机模型映射缺口仍然存在。

# Android 密钥环境与数据恢复 / Android key environments and recovery

## 中文

Monica 的本地优先意味着本地密码库可以离线使用、无需强制托管到云端。使用 Android Keystore 不违反这一原则，但本地存储不等于设备损坏或密钥变化后一定可恢复。独立加密备份可以保存在你自己控制的离线介质上，不要求使用云服务。

### 修改系统环境前

- 在存有真实密码的设备上使用或切换 OMK / OhMyKeymint、替换 Keystore / KeyMint 后端，或刷机、迁移系统，可能使应用原有密钥暂时不可访问或永久丢失。请先导出所需范围的独立加密备份，保存好备份密码，并在不覆盖原库的独立环境中验证恢复。优先在无重要数据的测试设备上试验系统模块。
- 保留原库，核对备份范围、失败/跳过项目，以及重要记录、历史和附件。不要把一次导出成功、主密码能登录或“恢复材料可用”当成所有历史数据都已验证可恢复。
- 应用私有目录中的恢复材料与应用数据一起存放，不能代替独立备份。仅复制应用目录不能复制 Android Keystore 中不可导出的密钥。硬件绑定且不可导出的 Passkey 也不能承诺跨设备恢复，应保留账号提供方的备用登录方式。

### 主密码独立恢复

1.0.331 增加需原主密码和明确确认的独立恢复路径：已有恢复材料有效时，即使 Android Keystore 不可用，也可恢复原 MDK 和加密配置。正常使用不自动切换后端，原文件与系统 alias 保留。它不补造永久丢失的旧密钥，也不代表硬件绑定项目或未迁移历史数据已全部可恢复。

选择该方式后，每个冷进程先以原主密码打开独立加密配置。指纹快捷解锁暂不启用；主密码修改与后续配置写入仍使用认证加密和修订检查。请保留独立备份，切勿降级到不认识该存储格式的旧版本。

### 已经出现无法读取的记录时

保留原应用数据、已有备份及恢复材料，避免清除应用数据、卸载应用、删除或重建 alias，或反复切换密钥后端。先记录出错的应用版本、发生时间和最近的环境变更；诊断时只提供去除敏感信息的错误类型与诊断行，不提供密码、密钥、keybox 或完整密码库。

若确实修改过密钥后端，在了解相关模块操作并保全现有材料的前提下，可以尝试恢复原先能正常读取数据的配置。恢复环境不保证能够找回永久丢失的密钥。若唯一解密密钥已经永久丢失，且没有可用的独立封装或可读备份，更新软件也不能凭空恢复对应密文。

### 不要混淆故障原因

keybox 的证明材料与加密应用数据的 AES 密钥不是同一件东西。不能因为更换过 keybox 就断言所有错误都由它造成，也不能保证每次切换 OMK 都会失败。Monica 不把 Play Integrity / keybox 证明作为普通本地密码库的开库条件。

目前的恢复机制已降低部分密钥环境变化的风险，但尚不能保证所有历史数据都独立于原设备密钥。主密码能登录而部分记录不可读时，仍需检查历史密钥、导入、升级/回退和软件自身问题。本说明不替代这些缺陷的修复，也不表示未来版本的自动迁移已经实现或通过验证。

## English

Monica's local-first approach lets you use its local vault offline without mandatory cloud hosting. Android Keystore is compatible with that approach, but local storage alone does not guarantee recovery after device damage or key changes. An independent encrypted backup can stay on offline media you control; cloud storage is not required.

### Before changing the system environment

- Enabling or switching OMK / OhMyKeymint, replacing Keystore / KeyMint backends, flashing a ROM, or migrating a system can make existing application keys temporarily inaccessible or permanently unavailable. First export an independent encrypted backup of the required scope, retain its password, and verify restoration in a separate environment without overwriting the original vault. Prefer a test device without important data for experiments with system modules.
- Keep the original vault and check backup scope, failed/skipped items, important records, history and attachments. Successful export, successful master-password login, or available recovery material alone does not prove that every historical record is recoverable.
- Recovery material inside the app's private directory can be lost together with the app data; it is not an independent backup. Copying the app directory does not copy non-exportable Android Keystore keys. Hardware-bound, non-exportable passkeys also cannot be promised to restore on another device; retain the account provider's alternative sign-in methods.

### Independent master-password recovery

Version 1.0.331 adds an explicitly confirmed recovery path using the original master password: valid existing recovery material can restore the original MDK and encrypted configuration even when Android Keystore is unavailable. Normal use does not automatically switch backends, and original files and system aliases are retained. This cannot recreate permanently lost keys or prove recovery of hardware-bound or unmigrated historical data.

Each cold process first opens the independently encrypted configuration with the original password. Biometric shortcuts remain disabled; password changes and later configuration writes retain authenticated encryption and revision checks. Keep an independent backup and avoid downgrading to versions that do not understand this storage format.

### If records are already unreadable

Preserve the original app data, existing backups and recovery material. Avoid clearing app data, uninstalling the app, deleting or regenerating aliases, or repeatedly switching key backends. Record the affected app version, timing and recent environment changes. Share only sanitized error types and relevant diagnostic lines, never passwords, keys, keyboxes or the full vault.

If you did change the key backend, restoring the previously working configuration may help, provided you understand the module's operation and preserve the existing material first. Restoring the environment does not guarantee recovery of permanently lost keys. If the only decryption key has been permanently lost and no independent wrapper or readable backup exists, a software update cannot reconstruct it.

### Distinguish the possible causes

Keybox attestation material is different from the AES keys that encrypt application data. A keybox change does not establish the cause of every failure, and switching OMK does not necessarily fail in every environment. Monica does not require Play Integrity / keybox attestation to open an ordinary local vault.

Existing recovery measures reduce some risks from key-environment changes, but do not guarantee that all historical data is independent of the original device keys. If master-password login succeeds while some records remain unreadable, historical keys, import, upgrade/downgrade and application defects still need investigation. This notice does not replace software fixes or claim that a future automatic migration is implemented or verified.

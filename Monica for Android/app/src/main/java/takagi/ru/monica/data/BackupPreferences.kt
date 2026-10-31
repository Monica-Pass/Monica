package takagi.ru.monica.data

/**
 * 备份偏好设置数据类
 * 用于控制云备份及文件 ZIP 导出中包含哪些内容类型
 */
data class BackupPreferences(
    val includePasswords: Boolean = true,
    val includeAuthenticators: Boolean = true,
    val includeDocuments: Boolean = true,
    val includeBankCards: Boolean = true,
    val includePasskeys: Boolean = true,           // ✅ 新增：验证密钥(Passkey)
    val includeGeneratorHistory: Boolean = true,   // 保留用于向后兼容
    val includeImages: Boolean = true,
    val includeNotes: Boolean = true,
    val includeTimeline: Boolean = true,           // 保留用于向后兼容
    val includeTrash: Boolean = true,              // 保留用于向后兼容
    val includeTrashAndHistory: Boolean = true,    // ✅ 新增：回收站与历史（合并项）
    val includeWebDavConfig: Boolean = false,      // Monica 配置（旧字段名，默认关闭，需手动开启）
    val includeLocalKeePass: Boolean = false      // 本地 KeePass 数据库（默认关闭）
) {
    /**
     * 检查是否至少启用了一种内容类型
     * 注意：WebDAV 配置和 KeePass 相关选项不计入必选项，因为它们是附加配置
     */
    fun hasAnyEnabled(): Boolean {
        return includePasswords || includeAuthenticators || 
               includeDocuments || includeBankCards || includePasskeys ||
               includeTrashAndHistory || includeImages || includeNotes ||
               includeLocalKeePass
    }
    
    /**
     * 检查是否所有内容类型都已启用
     * 注意：WebDAV 配置使用单独的检查
     */
    fun allEnabled(): Boolean {
        return includePasswords && includeAuthenticators && 
               includeDocuments && includeBankCards && includePasskeys &&
               includeTrashAndHistory && includeImages && includeNotes
    }
    
    /**
     * 检查是否所有内容类型都已启用（包括 WebDAV 配置）
     */
    fun allEnabledIncludingWebDav(): Boolean {
        return allEnabled() && includeWebDavConfig
    }
    
    /**
     * 获取实际的生成器历史开关值（兼容旧逻辑）
     * 新逻辑：如果 includeTrashAndHistory 为 true，则包含生成器历史
     */
    fun shouldIncludeGeneratorHistory(): Boolean = includeTrashAndHistory || includeGeneratorHistory
    
    /**
     * 获取实际的操作历史开关值（兼容旧逻辑）
     */
    fun shouldIncludeTimeline(): Boolean = includeTrashAndHistory || includeTimeline
    
    /**
     * 获取实际的回收站开关值（兼容旧逻辑）
     */
    fun shouldIncludeTrash(): Boolean = includeTrashAndHistory || includeTrash
}

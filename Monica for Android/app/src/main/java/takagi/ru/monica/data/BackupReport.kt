package takagi.ru.monica.data

import takagi.ru.monica.R
import android.content.Context
/**
 * 备份报告 - 用于向用户展示备份结果的详细信息
 */
data class BackupReport(
    val success: Boolean,
    val totalItems: ItemCounts,
    val successItems: ItemCounts,
    val failedItems: List<FailedItem>,
    val warnings: List<String>,
    /** Records the warning independently of the language used while exporting. */
    val connectionCredentialsSkipped: Boolean = false,
    val skippedItems: List<FailedItem> = emptyList(),
    /** False when secondary data (history, images, attachments, Steam) could not be exported. */
    val dataComplete: Boolean = true,
) {
    /**
     * 是否有警告或失败
     */
    fun hasIssues(): Boolean = failedItems.isNotEmpty() || warnings.isNotEmpty() || skippedItems.isNotEmpty()
    
    /**
     * 获取可读的报告摘要
     */
    fun getSummary(context: Context): String {
        return buildString {
            if (success) {
                appendLine(context.getString(R.string.legacy_ui_report_backup_success))
            } else {
                appendLine(context.getString(R.string.legacy_ui_report_backup_failed))
            }
            
            appendLine()
            appendLine(context.getString(R.string.legacy_ui_report_counts))
            appendLine(context.getString(R.string.legacy_ui_report_count_passwords, successItems.passwords, totalItems.passwords))
            appendLine(context.getString(R.string.legacy_ui_report_count_notes, successItems.notes, totalItems.notes))
            appendLine(context.getString(R.string.legacy_ui_report_count_totp, successItems.totp, totalItems.totp))
            appendLine(context.getString(R.string.legacy_ui_report_count_bankcards, successItems.bankCards, totalItems.bankCards))
            appendLine(context.getString(R.string.legacy_ui_report_count_documents, successItems.documents, totalItems.documents))
            appendLine(context.getString(R.string.legacy_ui_report_count_billingaddresses, successItems.billingAddresses, totalItems.billingAddresses))
            appendLine(context.getString(R.string.legacy_ui_report_count_paymentaccounts, successItems.paymentAccounts, totalItems.paymentAccounts))
            appendLine(context.getString(R.string.legacy_ui_report_count_passkeys, successItems.passkeys, totalItems.passkeys))
            appendLine(context.getString(R.string.legacy_ui_report_count_steammafiles, successItems.steamMaFiles, totalItems.steamMaFiles))
            appendLine(context.getString(R.string.legacy_ui_report_count_images, successItems.images, totalItems.images))
            
            if (failedItems.isNotEmpty()) {
                appendLine()
                appendLine(context.getString(R.string.legacy_ui_report_failed_items))
                failedItems.forEach { item ->
                    appendLine("  [${item.type}] ${item.title} - ${item.reason}")
                }
            }
            
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine(context.getString(R.string.legacy_ui_report_warnings))
                warnings.forEach { warning ->
                    appendLine("  • $warning")
                }
            }
        }
    }
}

/**
 * 恢复报告 - 用于向用户展示恢复结果的详细信息
 */
data class RestoreReport(
    val success: Boolean,
    val backupContains: ItemCounts,
    val restoredSuccessfully: ItemCounts,
    val failedItems: List<FailedItem>,
    val warnings: List<String>
) {
    /**
     * 是否有警告或失败
     */
    fun hasIssues(): Boolean = failedItems.isNotEmpty() || warnings.isNotEmpty()
    
    /**
     * 获取可读的报告摘要
     */
    fun getSummary(context: Context): String {
        return buildString {
            if (success) {
                appendLine(context.getString(R.string.legacy_ui_report_restore_success))
            } else {
                appendLine(context.getString(R.string.legacy_ui_report_restore_failed))
            }
            
            appendLine()
            appendLine(context.getString(R.string.legacy_ui_report_counts))
            appendLine(context.getString(R.string.legacy_ui_report_count_passwords, restoredSuccessfully.passwords, backupContains.passwords))
            appendLine(context.getString(R.string.legacy_ui_report_count_notes, restoredSuccessfully.notes, backupContains.notes))
            appendLine(context.getString(R.string.legacy_ui_report_count_totp, restoredSuccessfully.totp, backupContains.totp))
            appendLine(context.getString(R.string.legacy_ui_report_count_bankcards, restoredSuccessfully.bankCards, backupContains.bankCards))
            appendLine(context.getString(R.string.legacy_ui_report_count_documents, restoredSuccessfully.documents, backupContains.documents))
            appendLine(context.getString(R.string.legacy_ui_report_count_billingaddresses, restoredSuccessfully.billingAddresses, backupContains.billingAddresses))
            appendLine(context.getString(R.string.legacy_ui_report_count_paymentaccounts, restoredSuccessfully.paymentAccounts, backupContains.paymentAccounts))
            appendLine(context.getString(R.string.legacy_ui_report_count_passkeys, restoredSuccessfully.passkeys, backupContains.passkeys))
            appendLine(context.getString(R.string.legacy_ui_report_count_steammafiles, restoredSuccessfully.steamMaFiles, backupContains.steamMaFiles))
            appendLine(context.getString(R.string.legacy_ui_report_count_images, restoredSuccessfully.images, backupContains.images))
            
            if (failedItems.isNotEmpty()) {
                appendLine()
                appendLine(context.getString(R.string.legacy_ui_report_restore_failed_items))
                failedItems.forEach { item ->
                    appendLine("  [${item.type}] ${item.title} - ${item.reason}")
                }
            }
            
            if (warnings.isNotEmpty()) {
                appendLine()
                appendLine(context.getString(R.string.legacy_ui_report_warnings))
                warnings.forEach { warning ->
                    appendLine("  • $warning")
                }
            }
        }
    }
}

/**
 * 项目计数
 */
data class ItemCounts(
    val passwords: Int = 0,
    val notes: Int = 0,
    val totp: Int = 0,
    val bankCards: Int = 0,
    val documents: Int = 0,
    val billingAddresses: Int = 0,
    val paymentAccounts: Int = 0,
    val passkeys: Int = 0,
    val images: Int = 0,
    val generatorHistory: Int = 0,
    val steamMaFiles: Int = 0
) {
    fun getTotalCount(): Int {
        return passwords + notes + totp + bankCards + documents + billingAddresses +
            paymentAccounts + passkeys + steamMaFiles
    }
}

/**
 * 失败的项目详情
 */
data class FailedItem(
    val id: Long,
    val type: String,
    val title: String,
    val reason: String
)

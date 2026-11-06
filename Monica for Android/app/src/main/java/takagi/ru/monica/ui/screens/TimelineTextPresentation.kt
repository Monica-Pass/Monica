package takagi.ru.monica.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import takagi.ru.monica.data.model.TimelineEvent
import takagi.ru.monica.utils.StringResolver

/** Presentation only: field names also identify historical values during restoration. */
internal fun timelineFieldLabel(fieldName: String, strings: StringResolver): String =
    TIMELINE_FIELD_LABELS[fieldName]?.let { strings.get(it) } ?: fieldName

@Composable
internal fun timelineFieldLabel(fieldName: String): String =
    TIMELINE_FIELD_LABELS[fieldName]?.let { stringResource(it) } ?: fieldName

internal fun timelineFieldValue(fieldName: String, value: String, strings: StringResolver): String =
    timelineSystemValueResource(fieldName, value)?.let { strings.get(it) } ?: value

@Composable
internal fun timelineFieldValue(fieldName: String, value: String): String =
    timelineSystemValueResource(fieldName, value)?.let { stringResource(it) } ?: value

// Translate only values produced by the app in these specific fields, never user content.
private fun timelineSystemValueResource(fieldName: String, value: String): Int? = when (fieldName) {
    "模式" -> when (value) {
        "静默同步" -> R.string.timeline_display_silent_sync
        "手动同步" -> R.string.timeline_display_manual_sync
        else -> null
    }
    "处理状态" -> when (value) {
        "待处理" -> R.string.legacy_ui_pending
        "已解决" -> R.string.timeline_display_resolved
        else -> null
    }
    "状态" -> if (value == "检测到同步冲突") R.string.timeline_display_conflict_detected else null
    "解决策略" -> when (value) {
        "保留本地版本" -> R.string.timeline_display_keep_local
        "使用服务器版本" -> R.string.timeline_display_use_server
        else -> null
    }
    "类型" -> when (value) {
        "文本 Send" -> R.string.send_type_text
        "文件 Send" -> R.string.send_type_file
        else -> null
    }
    "更新" -> if (value == "编辑于") R.string.timeline_display_edited_at else null
    "来源", "创建方式", "打开方式" -> when (value) {
        "外部导入" -> R.string.timeline_display_external_import
        "工作副本" -> R.string.timeline_display_working_copy
        "远端新建" -> R.string.timeline_display_remote_create
        "远端同步" -> R.string.timeline_display_remote_sync
        "外部引用" -> R.string.mdbx_ui_external_references
        else -> null
    }
    "密钥文件" -> when (value) {
        "未设置" -> R.string.common_account_not_configured
        "已设置" -> R.string.timeline_display_configured
        else -> null
    }
    "远端同步" -> if (value == "待同步") R.string.sync_status_pending_short else null
    "默认数据库" -> when (value) {
        "是" -> R.string.yes
        "否" -> R.string.no
        else -> null
    }
    "工作副本", "外部引用" -> when (value) {
        "已刷新" -> R.string.timeline_display_refreshed
        "已存在" -> R.string.timeline_display_exists
        "已保存" -> R.string.entry_message_saved
        else -> null
    }
    else -> null
}

@Composable
internal fun timelineLogSummary(log: TimelineEvent.StandardLog): String {
    val context = LocalContext.current
    return timelineLogSummary(log, StringResolver { id, arguments -> context.getString(id, *arguments) })
}

internal fun timelineLogSummary(log: TimelineEvent.StandardLog, strings: StringResolver): String {
    if (log.itemType == "WEBDAV_DOWNLOAD" && log.summary == "同步下载") {
        return strings.get(R.string.timeline_display_sync_download)
    }
    if (log.itemType == "WEBDAV_UPLOAD") {
        val match = LEGACY_BACKUP_TITLE.matchEntire(log.summary)
        if (match != null) return strings.get(
            R.string.timeline_display_backup_upload,
            strings.get(if (match.groupValues[1] == "自动") R.string.timeline_display_automatic else R.string.timeline_display_manual),
            strings.get(if (match.groupValues[2] == "永久") R.string.timeline_display_permanent else R.string.timeline_display_temporary)
        )
    }
    if (log.itemType == "BITWARDEN_CONFLICT" && log.summary == "Bitwarden 冲突") {
        return strings.get(R.string.timeline_display_bitwarden_conflict)
    }
    if (log.operationType == "DELETE") {
        val suffixes = listOf(
            " (移入回收站（待同步删除）)" to R.string.timeline_display_trashed_pending,
            " (移入回收站)" to R.string.timeline_display_trashed
        )
        for ((suffix, id) in suffixes) {
            if (log.summary.endsWith(suffix)) {
                return log.summary.removeSuffix(suffix) + " (" + strings.get(id) + ")"
            }
        }
        if (log.itemType == "BITWARDEN_SEND") {
            val match = LEGACY_VAULT_DELETION.find(log.summary)
            if (match != null) return log.summary.substring(0, match.range.first) + " (" +
                strings.get(R.string.timeline_display_deleted_from_vault, match.groupValues[1]) + ")"
        }
    }
    return log.summary
}

private val LEGACY_BACKUP_TITLE = Regex("(自动|手动)上传 · (永久|临时)")
private val LEGACY_VAULT_DELETION = Regex(""" \(从 Vault #(\d+) 删除\)$""")
private val TIMELINE_FIELD_LABELS = mapOf(
    "标题" to R.string.title,
    "用户名" to R.string.username,
    "网站" to R.string.website,
    "密码" to R.string.password,
    "主密码" to R.string.master_password,
    "备注" to R.string.notes,
    "内容" to R.string.content,
    "名称" to R.string.name,
    "描述" to R.string.description,
    "更新" to R.string.legacy_ui_update,
    "卡号" to R.string.card_number,
    "持卡人" to R.string.cardholder_label,
    "银行" to R.string.bank_name,
    "卡类型" to R.string.card_type,
    "账单地址" to R.string.billing_address,
    "卡组织" to R.string.bank_card_brand_label,
    "卡片昵称" to R.string.bank_card_nickname_label,
    "起始月份" to R.string.bank_card_valid_from_month,
    "起始年份" to R.string.bank_card_valid_from_year,
    "路由号码" to R.string.bank_card_routing_number_label,
    "账户号码" to R.string.payment_account_numbers,
    "分行代码" to R.string.bank_card_branch_code_label,
    "币种" to R.string.bank_card_currency_label,
    "客服电话" to R.string.bank_card_customer_service_phone_label,
    "自定义字段" to R.string.custom_fields,
    "自定义卡面" to R.string.card_face_customize,
    "证件类型" to R.string.document_type,
    "证件号" to R.string.card_face_identifier_document,
    "姓名" to R.string.full_name,
    "签发日期" to R.string.issued_date,
    "有效期" to R.string.expiry_date,
    "签发机关" to R.string.issued_by,
    "国籍" to R.string.nationality,
    "附加信息" to R.string.document_additional_info_label,
    "称谓" to R.string.document_title_prefix_label,
    "名" to R.string.document_first_name_label,
    "中间名" to R.string.document_middle_name_label,
    "姓" to R.string.document_last_name_label,
    "地址 1" to R.string.document_address_line_1,
    "地址 2" to R.string.document_address_line_2,
    "地址 3" to R.string.document_address_line_3,
    "城市" to R.string.city,
    "省/州" to R.string.state_province,
    "邮编" to R.string.zip_code,
    "国家" to R.string.country,
    "公司" to R.string.document_company_label,
    "邮箱" to R.string.email,
    "电话" to R.string.document_phone_label,
    "社保号" to R.string.document_ssn_label,
    "护照号" to R.string.document_passport_number_label,
    "驾照号" to R.string.document_license_number_label,
    "街道地址" to R.string.street_address,
    "类型" to R.string.send_type_section_title,
    "来源" to R.string.timeline_display_sources,
    "数据库" to R.string.category_selection_menu_databases,
    "存储位置" to R.string.storage_location,
    "存储路径" to R.string.timeline_display_path,
    "密钥文件" to R.string.local_keepass_key_file,
    "远端同步" to R.string.timeline_display_remote_sync,
    "加密算法" to R.string.local_keepass_cipher_algorithm,
    "格式版本" to R.string.mdbx_ui_format_version,
    "外部引用" to R.string.mdbx_ui_external_references,
    "笔记" to R.string.note_detail_title,
    "卡片" to R.string.timeline_item_card,
    "证件" to R.string.item_type_document,
    "验证器" to R.string.item_type_authenticator,
    "支付方式" to R.string.payment_account,
    "模式" to R.string.timeline_display_mode,
    "本次变更" to R.string.timeline_display_changes,
    "离线可查看总数" to R.string.timeline_display_offline_items,
    "远端新增" to R.string.timeline_display_remote_added,
    "远端更新" to R.string.timeline_display_remote_updated,
    "本地上传" to R.string.timeline_display_uploaded,
    "删除处理" to R.string.timeline_display_deletions,
    "冲突数" to R.string.timeline_display_conflicts,
    "上传失败" to R.string.timeline_display_upload_failed,
    "本地待上传阻塞" to R.string.timeline_display_pending_blocked,
    "状态" to R.string.timeline_display_status,
    "处理状态" to R.string.timeline_display_resolution_status,
    "解决策略" to R.string.timeline_display_resolution_strategy,
    "有效期月份" to R.string.timeline_display_expiry_month,
    "有效期年份" to R.string.timeline_display_expiry_year,
    "公寓/单元" to R.string.timeline_display_unit,
    "创建方式" to R.string.timeline_display_creation_method,
    "打开方式" to R.string.timeline_display_open_method,
    "工作副本" to R.string.timeline_display_working_copy,
    "条目数量" to R.string.timeline_display_entry_count,
    "父级分组" to R.string.timeline_display_parent_group,
    "路径" to R.string.timeline_display_path,
    "默认数据库" to R.string.timeline_display_default_database,
)

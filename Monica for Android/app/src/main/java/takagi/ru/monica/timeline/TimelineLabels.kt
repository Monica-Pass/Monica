package takagi.ru.monica.timeline

import takagi.ru.monica.R

internal fun timelineFieldResource(field: String): Int? = when (field) {
    "title", "标题" -> R.string.title
    "username", "用户名" -> R.string.username
    "password", "密码" -> R.string.password
    "website", "网站" -> R.string.website
    "notes", "备注" -> R.string.notes
    "custom_fields" -> R.string.timeline_v2_fields
    "itemData" -> R.string.content
    "email" -> R.string.field_email
    "phone" -> R.string.field_phone
    "addressLine" -> R.string.field_address
    "city" -> R.string.field_city
    "state" -> R.string.field_state
    "zipCode" -> R.string.field_postal_code
    "country" -> R.string.field_country
    "creditCardNumber" -> R.string.field_card_number
    "creditCardHolder" -> R.string.field_cardholder
    "creditCardExpiry" -> R.string.field_expiry
    "creditCardCVV" -> R.string.field_cvv
    "isFavorite" -> R.string.favorite
    "sync.changed", "本次变更" -> R.string.timeline_v2_sync_changed
    "sync.offline", "离线可查看总数" -> R.string.timeline_v2_sync_offline
    "sync.added", "远端新增" -> R.string.timeline_v2_sync_added
    "sync.updated", "远端更新" -> R.string.timeline_v2_sync_updated
    "sync.uploaded", "本地上传" -> R.string.timeline_v2_sync_uploaded
    "sync.deleted", "删除处理" -> R.string.timeline_v2_sync_deleted
    "sync.conflicts", "冲突数" -> R.string.timeline_v2_sync_conflicts
    "sync.failed", "上传失败" -> R.string.timeline_v2_sync_failed
    "sync.pending", "本地待上传阻塞" -> R.string.timeline_v2_sync_pending
    else -> null
}

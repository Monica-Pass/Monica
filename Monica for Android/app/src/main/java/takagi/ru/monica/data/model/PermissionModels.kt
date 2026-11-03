package takagi.ru.monica.data.model

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import takagi.ru.monica.R

data class PermissionInfo(
    val id: String,
    val androidPermission: String,
    val icon: ImageVector,
    @StringRes val nameResId: Int,
    @StringRes val descriptionResId: Int,
    val category: PermissionCategory,
    val importance: PermissionImportance = PermissionImportance.OPTIONAL,
    val status: PermissionStatus = PermissionStatus.UNKNOWN,
    val settingsTarget: PermissionSettingsTarget = PermissionSettingsTarget.NONE,
    val runtimePermissions: List<String> = emptyList(),
    val requested: Boolean = true,
    val internalProtection: Boolean = false,
    val availability: Boolean = false,
    val minSdk: Int = 26,
    val maxSdk: Int? = null,
    val systemLabel: String? = null,
)

enum class PermissionCategory(@StringRes val titleResId: Int) {
    RUNTIME(R.string.pm_category_runtime),
    SPECIAL(R.string.pm_category_special),
    AUTOMATIC(R.string.pm_category_automatic),
}
enum class PermissionSettingsTarget {
    NONE, APP, NOTIFICATIONS, LIVE_UPDATES, AUTOFILL, ACCESSIBILITY, BIOMETRIC,
    OVERLAY, INSTALL, BATTERY, IME, CREDENTIALS, NFC, SHIZUKU,
}
enum class PermissionImportance(@StringRes val labelResId: Int) {
    REQUIRED(R.string.permission_importance_required),
    RECOMMENDED(R.string.permission_importance_recommended),
    OPTIONAL(R.string.permission_importance_optional),
}
enum class PermissionStatus { GRANTED, DENIED, UNAVAILABLE, UNKNOWN }
data class PermissionStats(
    val totalRequired: Int,
    val grantedRequired: Int,
    val totalPermissions: Int,
    val grantedPermissions: Int,
    val declaredPermissions: Int = totalPermissions,
)

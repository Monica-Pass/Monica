package takagi.ru.monica.ui.screens

import android.Manifest
import takagi.ru.monica.data.model.PermissionStatus
import takagi.ru.monica.data.model.PermissionInfo
import takagi.ru.monica.data.model.PermissionSettingsTarget

internal fun runtimeGrantedPermission(permission:PermissionInfo,grants:Map<String,Boolean>):String? = when {
    permission.androidPermission in permission.runtimePermissions && grants[permission.androidPermission]==true -> permission.androidPermission
    permission.androidPermission==Manifest.permission.ACCESS_FINE_LOCATION &&
        Manifest.permission.ACCESS_COARSE_LOCATION in permission.runtimePermissions &&
        grants[Manifest.permission.ACCESS_COARSE_LOCATION]==true -> Manifest.permission.ACCESS_COARSE_LOCATION
    else -> null
}

internal enum class PermissionClickAction {
    REQUEST_RUNTIME_PERMISSION,
    OPEN_AUTOFILL_SETTINGS,
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_BIOMETRIC_SETTINGS,
    OPEN_APP_SETTINGS,
    SHOW_GRANTED,
    IGNORE,
}

internal fun resolvePermissionClickAction(permission: PermissionInfo): PermissionClickAction {
    if (permission.status == PermissionStatus.UNAVAILABLE || permission.settingsTarget == PermissionSettingsTarget.NONE)
        return PermissionClickAction.IGNORE
    return if (permission.runtimePermissions.isNotEmpty() && permission.status == PermissionStatus.DENIED)
        PermissionClickAction.REQUEST_RUNTIME_PERMISSION else PermissionClickAction.OPEN_APP_SETTINGS
}

internal fun resolvePermissionClickAction(
    permissionId: String,
    status: PermissionStatus,
): PermissionClickAction {
    if (status == PermissionStatus.UNAVAILABLE) return PermissionClickAction.IGNORE

    return when (permissionId) {
        "AUTOFILL" -> PermissionClickAction.OPEN_AUTOFILL_SETTINGS
        "ACCESSIBILITY" -> PermissionClickAction.OPEN_ACCESSIBILITY_SETTINGS
        "BIOMETRIC" -> if (status == PermissionStatus.GRANTED) {
            PermissionClickAction.SHOW_GRANTED
        } else {
            PermissionClickAction.OPEN_BIOMETRIC_SETTINGS
        }
        "CAMERA", "STORAGE", "NOTIFICATION", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "NEARBY_WIFI_DEVICES" -> {
            if (status == PermissionStatus.GRANTED) {
                PermissionClickAction.OPEN_APP_SETTINGS
            } else {
                PermissionClickAction.REQUEST_RUNTIME_PERMISSION
            }
        }
        "INTERNET", "NETWORK_STATE", "VIBRATE" -> PermissionClickAction.SHOW_GRANTED
        else -> PermissionClickAction.OPEN_APP_SETTINGS
    }
}

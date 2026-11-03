package takagi.ru.monica.repository

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.credentials.CredentialManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.view.autofill.AutofillManager
import androidx.biometric.BiometricManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import takagi.ru.monica.R
import takagi.ru.monica.data.model.*
import takagi.ru.monica.notifications.LiveUpdateNotifications
import takagi.ru.monica.service.MonicaAccessibilityService

/** Inventory comes from the installed package, including dependency declarations. */
class PermissionRepository(private val context: Context) {
    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    fun getAllPermissions(forceRefresh: Boolean = false): List<PermissionInfo> {
        val pkg = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_DISABLED_COMPONENTS)
        val names = pkg.requestedPermissions.orEmpty().distinct()
        val declared = names.toSet()
        val result = names.map { name ->
            val p = descriptor(name)
            val runtime = if (name == Manifest.permission.ACCESS_FINE_LOCATION) {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).filter { it in declared }
            } else if (name in setOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.POST_NOTIFICATIONS) && p.category == PermissionCategory.RUNTIME) listOf(name) else emptyList()
            p.copy(runtimePermissions = runtime)
        }.toMutableList()
        if (result.none { it.id == "NOTIFICATION" }) result += service("NOTIFICATION", "",
            R.string.permission_notification_name, R.string.pm_notifications, PermissionSettingsTarget.NOTIFICATIONS)
        result += service("BIOMETRIC", "", R.string.permission_biometric_name,
            R.string.pm_biometric, PermissionSettingsTarget.BIOMETRIC).copy(availability = true)
        for (binding in pkg.services.orEmpty().mapNotNull { it.permission }.distinct().filterNot { it in declared }) {
            result += when (binding) {
                Manifest.permission.BIND_AUTOFILL_SERVICE -> service("AUTOFILL", binding,
                    R.string.permission_autofill_name, R.string.pm_autofill, PermissionSettingsTarget.AUTOFILL)
                Manifest.permission.BIND_ACCESSIBILITY_SERVICE -> service("ACCESSIBILITY", binding,
                    R.string.permission_accessibility_name, R.string.pm_accessibility, PermissionSettingsTarget.ACCESSIBILITY)
                Manifest.permission.BIND_INPUT_METHOD -> service("IME", binding,
                    R.string.ime_settings_title, R.string.pm_ime, PermissionSettingsTarget.IME)
                "android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE" -> service("CREDENTIALS", binding,
                    R.string.pm_credentials_name, R.string.pm_credentials, PermissionSettingsTarget.CREDENTIALS).copy(minSdk = 34)
                else -> descriptor(binding).copy(id = "BINDING:$binding", requested = false,
                    category = PermissionCategory.AUTOMATIC, internalProtection = true,
                    descriptionResId = R.string.pm_binding, settingsTarget = PermissionSettingsTarget.NONE)
            }
        }
        return result.map { it.copy(status = runCatching { status(it) }.getOrDefault(PermissionStatus.UNKNOWN)) }.sortedBy { it.category.ordinal }
    }

    private fun service(id: String, name: String, title: Int, description: Int, target: PermissionSettingsTarget) =
        PermissionInfo(id, name, Icons.Default.Settings, title, description, PermissionCategory.SPECIAL,
            settingsTarget = target, requested = false)

    @Suppress("DEPRECATION")
    private fun descriptor(name: String): PermissionInfo {
        val short = name.substringAfterLast('.')
        var p = PermissionInfo("PERMISSION:$name", name, Icons.Default.Shield, R.string.permission_management_title,
            R.string.pm_other, PermissionCategory.AUTOMATIC,
            systemLabel = runCatching { context.packageManager.getPermissionInfo(name, 0).loadLabel(context.packageManager).toString() }.getOrDefault(name))
        val baseProtection = runCatching { context.packageManager.getPermissionInfo(name, 0).protectionLevel and
            android.content.pm.PermissionInfo.PROTECTION_MASK_BASE }.getOrNull()
        if (baseProtection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS) p = p.copy(
            category = PermissionCategory.RUNTIME, settingsTarget = PermissionSettingsTarget.APP)
        if (baseProtection == android.content.pm.PermissionInfo.PROTECTION_SIGNATURE) p = p.copy(internalProtection = true)
        fun known(id: String, title: Int, desc: Int, category: PermissionCategory = PermissionCategory.AUTOMATIC,
                  target: PermissionSettingsTarget = PermissionSettingsTarget.NONE) {
            p = p.copy(id = id, nameResId = title, descriptionResId = desc, category = category, settingsTarget = target, systemLabel = null)
        }
        when (if (name == "android.permission.$short") short else null) {
            "CAMERA" -> known("CAMERA", R.string.permission_camera_name, R.string.pm_camera, PermissionCategory.RUNTIME, PermissionSettingsTarget.APP)
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION" -> known(short, R.string.permission_location_name,
                R.string.pm_location, PermissionCategory.RUNTIME, PermissionSettingsTarget.APP)
            "NEARBY_WIFI_DEVICES" -> {
                known(short, R.string.pm_nearby_name, R.string.pm_nearby, PermissionCategory.RUNTIME, PermissionSettingsTarget.APP)
                p = p.copy(minSdk = 33)
            }
            "WRITE_EXTERNAL_STORAGE" -> {
                known("STORAGE", R.string.permission_storage_name, R.string.pm_storage, PermissionCategory.RUNTIME, PermissionSettingsTarget.APP)
                p = p.copy(maxSdk = 28)
            }
            "READ_EXTERNAL_STORAGE" -> if (Build.VERSION.SDK_INT <= 28) {
                known("LEGACY_STORAGE_READ",R.string.permission_storage_name,R.string.pm_storage,PermissionCategory.RUNTIME,PermissionSettingsTarget.APP)
                p=p.copy(maxSdk=28)
            }
            "POST_NOTIFICATIONS" -> known("NOTIFICATION", R.string.permission_notification_name, R.string.pm_notifications,
                if (Build.VERSION.SDK_INT >= 33) PermissionCategory.RUNTIME else PermissionCategory.SPECIAL, PermissionSettingsTarget.NOTIFICATIONS)
            "POST_PROMOTED_NOTIFICATIONS" -> {
                known("LIVE_UPDATES", R.string.permission_live_updates_name, R.string.pm_live_updates, PermissionCategory.SPECIAL, PermissionSettingsTarget.LIVE_UPDATES)
                p = p.copy(minSdk = 36)
            }
            "NFC" -> known("NFC", R.string.pm_nfc_name, R.string.pm_nfc, target = PermissionSettingsTarget.NFC)
            "USE_BIOMETRIC", "USE_FINGERPRINT" -> {
                p = p.copy(descriptionResId = R.string.pm_biometric)
                if (short == "USE_BIOMETRIC") p = p.copy(minSdk = 28)
            }
            "INTERNET" -> known("INTERNET", R.string.permission_internet_name, R.string.pm_network)
            "ACCESS_NETWORK_STATE" -> known("NETWORK_STATE", R.string.permission_network_state_name, R.string.pm_network_state)
            "ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "CHANGE_NETWORK_STATE" -> p = p.copy(descriptionResId = R.string.pm_wifi)
            "CHANGE_WIFI_MULTICAST_STATE" -> p = p.copy(descriptionResId = R.string.pm_multicast)
            "VIBRATE" -> known("VIBRATE", R.string.permission_vibrate_name, R.string.pm_vibrate)
            "SYSTEM_ALERT_WINDOW" -> known("OVERLAY", R.string.pm_overlay_name, R.string.pm_overlay, PermissionCategory.SPECIAL, PermissionSettingsTarget.OVERLAY)
            "REQUEST_INSTALL_PACKAGES" -> known("INSTALL", R.string.pm_install_name, R.string.pm_install, PermissionCategory.SPECIAL, PermissionSettingsTarget.INSTALL)
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" -> known("BATTERY", R.string.autofill_protection_battery_title, R.string.pm_battery, PermissionCategory.SPECIAL, PermissionSettingsTarget.BATTERY)
            "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "FOREGROUND_SERVICE_SPECIAL_USE" -> p = p.copy(
                descriptionResId = R.string.pm_foreground, minSdk = if (short == "FOREGROUND_SERVICE") 28 else 34)
            "RECEIVE_BOOT_COMPLETED" -> p = p.copy(descriptionResId = R.string.pm_boot)
            "WAKE_LOCK" -> p = p.copy(descriptionResId = R.string.pm_wake)
            "HIDE_OVERLAY_WINDOWS" -> p = p.copy(descriptionResId = R.string.pm_hide_overlay, minSdk = 31)
        }
        if (name == "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") {
            p = p.copy(descriptionResId = R.string.pm_internal, internalProtection = true)
        }
        if (name == "moe.shizuku.manager.permission.API_V23") known("SHIZUKU", R.string.pm_shizuku_name, R.string.pm_shizuku,
            PermissionCategory.SPECIAL, PermissionSettingsTarget.SHIZUKU)
        if (name == Manifest.permission.ACCESS_FINE_LOCATION) p = p.copy(nameResId = R.string.pm_location_precise)
        if (name == Manifest.permission.ACCESS_COARSE_LOCATION) p = p.copy(nameResId = R.string.pm_location_approximate)
        // Future unknown declarations are visible but never requested blindly.
        return p
    }

    private fun granted(value: Boolean) = if (value) PermissionStatus.GRANTED else PermissionStatus.DENIED
    private fun status(p: PermissionInfo): PermissionStatus {
        if (Build.VERSION.SDK_INT < p.minSdk || p.maxSdk?.let { Build.VERSION.SDK_INT > it } == true) return PermissionStatus.UNAVAILABLE
        return when (p.id) {
            "BIOMETRIC" -> when (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                BiometricManager.BIOMETRIC_SUCCESS -> PermissionStatus.GRANTED
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> PermissionStatus.UNAVAILABLE
                else -> PermissionStatus.DENIED
            }
            "AUTOFILL" -> granted(context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true)
            "ACCESSIBILITY" -> granted(MonicaAccessibilityService.isServiceEnabled(context))
            "IME" -> granted(context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.any {
                it.packageName == context.packageName && it.serviceName == "takagi.ru.monica.ime.MonicaInputMethodService"
            } == true)
            "CREDENTIALS" -> if (Build.VERSION.SDK_INT >= 34) granted(context.getSystemService(CredentialManager::class.java)
                ?.isEnabledCredentialProviderService(ComponentName(context.packageName, "takagi.ru.monica.passkey.MonicaCredentialProviderService")) == true) else PermissionStatus.UNAVAILABLE
            "NOTIFICATION" -> granted(NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED))
            "LIVE_UPDATES" -> granted(NotificationManagerCompat.from(context).areNotificationsEnabled() && LiveUpdateNotifications.canPostPromotedNotifications(context))
            "OVERLAY" -> granted(Settings.canDrawOverlays(context))
            "INSTALL" -> granted(context.packageManager.canRequestPackageInstalls())
            "BATTERY" -> granted(context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true)
            "SHIZUKU" -> if (!Shizuku.pingBinder()) PermissionStatus.UNAVAILABLE else granted(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
            else -> if (!p.requested && p.internalProtection) PermissionStatus.UNKNOWN else granted(
                ContextCompat.checkSelfPermission(context, p.androidPermission) == PackageManager.PERMISSION_GRANTED)
        }
    }
    fun getPermissionsByCategory(): Map<PermissionCategory, List<PermissionInfo>> = getAllPermissions().groupBy { it.category }
    fun getPermissionStats(): PermissionStats {
        val all = getAllPermissions()
        return PermissionStats(0, 0, all.size, all.count { it.status == PermissionStatus.GRANTED }, all.count { it.requested })
    }
}

package takagi.ru.monica.autofill_ng.defaultmanager

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.credentials.CredentialManager
import android.os.Build
import android.os.UserManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** Device configuration only. No record, credential request, password or token is collected. */
internal data class HyperOsDiagnosticReport(
    val declaration: HyperOsCheck,
    val feature: HyperOsCheck,
    val unrestricted: HyperOsCheck,
    val framework: HyperOsCheck,
    val configuration: HyperOsConfigurationStatus,
)

internal object HyperOsDiagnostics {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hyperos_diagnostics", Context.MODE_PRIVATE)
    fun monitoring(context: Context): Boolean = prefs(context).getBoolean("monitor", false)
    fun setMonitoring(context: Context, enabled: Boolean) { check(prefs(context).edit().putBoolean("monitor", enabled).commit()) }
    fun verified(context: Context, snapshot: SettingsSnapshot) {
        check(snapshot.userId == android.os.Process.myUid() / 100000)
        check(prefs(context).edit().putString("verified", snapshot.toJson()).commit())
    }
    fun clearVerified(context: Context) { check(prefs(context).edit().remove("verified").commit()) }
    fun baseline(context: Context): SettingsSnapshot? = runCatching {
        prefs(context).getString("verified", null)?.let(::snapshotFromJson)
            ?.takeIf { it.userId == android.os.Process.myUid() / 100000 }
    }.getOrNull()

    suspend fun read(context: Context): HyperOsDiagnosticReport = withContext(Dispatchers.IO) {
        val supported = Build.VERSION.SDK_INT >= 34
        val provider = requireNotNull(ComponentName.unflattenFromString(MonicaDefaultServices.credential))
        val declaration = if (!supported) HyperOsCheck.UNSUPPORTED else checkValue { validProvider(context, provider) }
        val feature = if (!supported) HyperOsCheck.UNSUPPORTED else checkValue {
            context.packageManager.hasSystemFeature("android.software.credentials")
        }
        val unrestricted = checkValue { !context.getSystemService(UserManager::class.java).hasUserRestriction(UserManager.DISALLOW_AUTOFILL) }
        val framework = if (!supported) HyperOsCheck.UNSUPPORTED else checkValue {
            context.getSystemService(CredentialManager::class.java)?.isEnabledCredentialProviderService(provider)
                ?: throw IllegalStateException("No credential manager")
        }
        val current = if (ShizukuDefaultManager.access(context) == DefaultManagerAccess.READY) {
            try { ShizukuDefaultManager.read(context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        HyperOsDiagnosticReport(declaration, feature, unrestricted, framework,
            HyperOsConfigurationStatus.from(current, baseline(context), monitoring(context),
                MonicaDefaultServices.credential, MonicaDefaultServices.autofill, android.os.Process.myUid() / 100000))
    }

    private inline fun checkValue(block: () -> Boolean): HyperOsCheck = try {
        if (block()) HyperOsCheck.YES else HyperOsCheck.NO
    } catch (_: Exception) { HyperOsCheck.UNKNOWN }

    @Suppress("DEPRECATION")
    private fun validProvider(context: Context, name: ComponentName): Boolean {
        val pm = context.packageManager
        val info = pm.getServiceInfo(name, PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS)
        val override = pm.getComponentEnabledSetting(name)
        val enabled = override == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (override == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled)
        if (!info.applicationInfo.enabled || !enabled || !info.exported ||
            info.permission != "android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE") return false
        val parser = info.loadXmlMetaData(pm, "android.credentials.provider") ?: return false
        parser.use {
            var events = 0
            while (it.eventType != XmlPullParser.END_DOCUMENT && events++ < 512) {
                if (it.eventType == XmlPullParser.START_TAG && it.name == "capability" &&
                    it.getAttributeValue(null, "name") == "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL") return true
                it.next()
            }
        }
        return false
    }
}

package takagi.ru.monica.passkey.hardware

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import androidx.credentials.provider.CallingAppInfo
import takagi.ru.monica.passkey.PasskeyBrowserOrigin

/** Real installed browser certificates; production's existing allowlist remains authoritative. */
@RequiresApi(34)
internal fun trustedHardwareBrowserCaller(
    context: Context,
    origin: String = "https://login.example.com",
): android.service.credentials.CallingAppInfo {
    for (packageName in listOf("com.android.chrome", "org.mozilla.firefox")) {
        val installed = try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            continue
        }
        val signing = checkNotNull(installed.signingInfo)
        val caller = CallingAppInfo(packageName, signing, origin)
        check(PasskeyBrowserOrigin.read(context, caller) == origin) {
            "Installed browser $packageName is not trusted by the production certificate allowlist"
        }
        return android.service.credentials.CallingAppInfo(packageName, signing, origin)
    }
    error("Install officially signed Chrome or Firefox in the isolated test profile before running hardware provider tests")
}

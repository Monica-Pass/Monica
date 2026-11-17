package takagi.ru.monica.hyperos

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import io.github.libxposed.service.XposedProvider

/** Only root/system may deliver the framework binder. Never a vault/data provider. */
class HyperOsModuleProvider : ContentProvider() {
    private val delegate = XposedProvider()
    override fun onCreate(): Boolean {
        delegate.attachInfo(requireNotNull(context), ProviderInfo().apply {
            authority = requireNotNull(context).packageName + ".XposedService"
            exported = false
        })
        return true
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (Binder.getCallingUid() != 0 && Binder.getCallingUid() != 1000) throw SecurityException("Framework caller required")
        return delegate.call(method, arg, extras)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

package takagi.ru.monica.ui.icons

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import takagi.ru.monica.keepass.KeePassCustomIconEditor
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService

@Composable
internal fun KeePassCustomIconImage(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = modifier.testTag("keepass_custom_icon"))
}

/** Original-vault images remain in memory; they are never Monica uploaded-file names. */
@Composable
internal fun rememberKeePassCustomIcon(
    databaseId: Long?,
    entryUuid: String? = null,
    groupUuid: String? = null,
    groupPath: String? = null,
    enabled: Boolean = true,
): ImageBitmap? {
    if (!enabled || databaseId == null || (entryUuid == null && groupUuid == null && groupPath == null)) return null
    val unlocked by SessionManager.isUnlocked.collectAsState()
    if (!unlocked) return null
    val versions by KeePassKdbxService.nativeIconVersions.collectAsState()
    val version = versions[databaseId]
    // A new version starts empty immediately, including when the database is locked.
    var bitmap by remember(databaseId, entryUuid, groupUuid, groupPath, version) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(databaseId, entryUuid, groupUuid, groupPath, version) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val icon = KeePassKdbxService.cachedNativeCustomIcon(databaseId, entryUuid, groupUuid, groupPath)
                icon?.data?.let(::decodeKeePassCustomIcon)
            }.getOrNull()
        }.takeIf { KeePassKdbxService.nativeIconVersions.value[databaseId] == version }
    }
    return bitmap
}

/** Bound both encoded and decoded sizes before allocating pixels for untrusted KDBX images. */
internal fun decodeKeePassCustomIcon(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty() || bytes.size > KeePassCustomIconEditor.MAX_ICON_BYTES) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 192) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

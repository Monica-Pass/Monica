package takagi.ru.monica.ui.cardwallet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import takagi.ru.monica.attachments.AttachmentContainer
import takagi.ru.monica.attachments.facade.AttachmentFacade
import takagi.ru.monica.attachments.model.Attachment
import takagi.ru.monica.attachments.model.AttachmentOwner
import takagi.ru.monica.attachments.model.AttachmentSource
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.keepass.KeePassSecureItemPhotoAttachments

/** Process-memory thumbnail cache; plaintext card faces are never written to a thumbnail file. */
object CardFaceImageLoader {
    private const val MAX_ENCODED_BYTES = 25 * 1024 * 1024
    private val decodeSlots = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    suspend fun load(
        context: Context,
        item: SecureItem,
        attachment: Attachment,
        maxDimension: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        val boundedDimension = maxDimension.coerceIn(160, 1600)
        val cacheKey = "${attachment.id}:${attachment.updatedAt}:$boundedDimension"
        cache.get(cacheKey)?.takeUnless(Bitmap::isRecycled)?.let { return@withContext it }

        decodeSlots.withPermit {
            cache.get(cacheKey)?.takeUnless(Bitmap::isRecycled)?.let { return@withPermit it }
            val appContext = context.applicationContext
            val facade = AttachmentContainer.facade(appContext)
            val keepassContext = item.keepassDatabaseId
                ?.let { databaseId ->
                    item.keepassEntryUuid
                        ?.takeIf(String::isNotBlank)
                        ?.let { entryUuid -> AttachmentFacade.KeePassContext(databaseId, entryUuid) }
                }
            val bitwardenContext = item.bitwardenVaultId?.let { vaultId ->
                val database = PasswordDatabase.getDatabase(appContext)
                val vault = database.bitwardenVaultDao().getVaultById(vaultId)
                vault?.let { resolvedVault ->
                    val repository = BitwardenRepository.getInstance(appContext)
                    val needsRemoteBytes = attachment.sourceEnum == AttachmentSource.BITWARDEN &&
                        (attachment.localPath.isNullOrBlank() || attachment.wrappedCek.isNullOrBlank())
                    if (needsRemoteBytes) {
                        item.bitwardenCipherId
                            ?.takeIf(String::isNotBlank)
                            ?.let { cipherId ->
                                repository.fetchAttachmentCipherSnapshot(resolvedVault, cipherId)?.context
                            }
                    } else {
                        repository.getAttachmentBitwardenContext(resolvedVault, item.bitwardenCipherId)
                    }
                }
            }
            val bytes = runCatching {
                facade.readAttachmentBytes(
                    attachmentId = attachment.id,
                    maxBytes = MAX_ENCODED_BYTES,
                    bitwardenContext = bitwardenContext,
                    keepassContext = keepassContext
                )
            }.getOrNull() ?: return@withPermit null
            try {
                val decoded = decodeSampled(bytes, boundedDimension) ?: return@withPermit null
                cache.put(cacheKey, decoded)
                decoded
            } finally {
                bytes.fill(0)
            }
        }
    }

    fun invalidate(attachmentId: Long) {
        val keys = cache.snapshot().keys.filter { it.startsWith("$attachmentId:") }
        keys.forEach(cache::remove)
    }

    private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        return try {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}

@Composable
fun rememberCardFaceBitmap(
    item: SecureItem?,
    imageAttachmentName: String?,
    overrideBitmap: Bitmap? = null,
    maxDimension: Int = 720
): Bitmap? {
    if (overrideBitmap != null) return overrideBitmap
    if (item == null || imageAttachmentName.isNullOrBlank()) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    val facade = remember(context) { AttachmentContainer.facade(context) }
    val attachmentFlow: Flow<List<Attachment>> = remember(item?.id, facade) {
        item?.id
            ?.takeIf { it > 0L }
            ?.let { facade.observe(AttachmentOwner.secureItem(it)) }
            ?: flowOf(emptyList<Attachment>())
    }
    val attachments by attachmentFlow.collectAsState(initial = emptyList())
    var attemptedKeePassReconcile by remember(item?.id, imageAttachmentName) { mutableStateOf(false) }
    LaunchedEffect(item?.id, imageAttachmentName, attachments.size) {
        val current = item ?: return@LaunchedEffect
        if (attemptedKeePassReconcile || imageAttachmentName.isNullOrBlank()) return@LaunchedEffect
        if (attachments.any { it.fileName == imageAttachmentName }) return@LaunchedEffect
        val databaseId = current.keepassDatabaseId ?: return@LaunchedEffect
        val entryUuid = current.keepassEntryUuid?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        attemptedKeePassReconcile = true
        runCatching {
            AttachmentContainer.keepassReconciler(context).reconcile(
                owner = AttachmentOwner.secureItem(current.id),
                databaseId = databaseId,
                entryUuid = entryUuid,
                excludedFileNames = KeePassSecureItemPhotoAttachments.managedFileNames(current.itemType)
            )
        }
    }
    val attachment = remember(attachments, imageAttachmentName) {
        imageAttachmentName?.let { name -> attachments.firstOrNull { it.fileName == name } }
    }
    return key(item.id, imageAttachmentName, attachment?.id, attachment?.updatedAt, maxDimension) {
    val loaded by produceState<Bitmap?>(
        initialValue = null,
        key1 = attachment?.id,
        key2 = attachment?.updatedAt,
        key3 = item?.bitwardenCipherId
    ) {
        value = if (item != null && attachment != null) {
            CardFaceImageLoader.load(context, item, attachment, maxDimension)
        } else {
            null
        }
    }
    loaded
    }
}

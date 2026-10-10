package takagi.ru.monica.passkey

import android.content.Context
import kotlinx.coroutines.CancellationException
import takagi.ru.monica.data.PasskeyEntry

internal enum class PasskeyPortability {
    PORTABLE, DEVICE_ONLY, KEY_UNAVAILABLE, BACKUP_RESTRICTED, COUNTER_HISTORY, ALGORITHM_RESTRICTED, INVALID_FLAGS, UNKNOWN;

    companion object {
        fun classify(entry: PasskeyEntry, usable: Boolean, exportable: Boolean): PasskeyPortability = when {
            !usable -> KEY_UNAVAILABLE
            !exportable -> DEVICE_ONLY
            entry.backupEligible == false && entry.backupState == true -> INVALID_FLAGS
            entry.backupEligible == false -> BACKUP_RESTRICTED
            entry.signCount > 0 -> COUNTER_HISTORY
            entry.publicKeyAlgorithm != PasskeyEntry.ALGORITHM_ES256 -> ALGORITHM_RESTRICTED
            else -> PORTABLE
        }

        fun inspect(context: Context, entry: PasskeyEntry): PasskeyPortability = try {
            val resolved = PasskeyPrivateKeyStore.resolve(context, entry.privateKeyAlias)
            classify(entry, PasskeyPrivateKeySupport.hasUsablePrivateKey(resolved),
                PasskeyPrivateKeySupport.normalizeForBitwardenUpload(resolved) != null)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            UNKNOWN
        }
    }
}

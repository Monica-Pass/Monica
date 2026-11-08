package takagi.ru.monica.autofill_ng

import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.BoundedSecureDataRead
import takagi.ru.monica.utils.KeePassKdbxService

/** A bounded continuation of one explicit native selection; it never retains credentials. */
internal class NativeAutofillAction private constructor(
    val selection: NativeAutofillSelection,
    val targetPackage: String?,
    val webDomain: String?,
    private val issuedAt: Long,
    private val expiresAt: Long,
    private val operation: Long,
    private val targetValid: Boolean,
) {
    fun isCurrent(context: Context): Boolean {
        if (!targetValid || operation <= 0L || operation != latestOperation.get()) return false
        if (takagi.ru.monica.security.MainTaskSessionGuard.revokeIfTaskRemoved(context.applicationContext)) {
            revokeAll()
            return false
        }
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true ||
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) {
            // Observing a locked device permanently revokes this continuation, even if the
            // screen-off receiver has not run yet. A later unlock cannot revive it.
            latestOperation.compareAndSet(operation, operation + 1L)
            return false
        }
        val now = SystemClock.elapsedRealtime()
        return operation == latestOperation.get() &&
            now in issuedAt until expiresAt && expiresAt - issuedAt in 1..MAX_TTL_MS &&
            AutofillPasswordSource.isSelectionFromCurrentProcess(selection) &&
            SecurityManager.dataReadGeneration.value == selection.generation &&
            (KeePassKdbxService.nativeLockVersions.value[selection.databaseId] ?: 0L) == selection.lockVersion
    }

    suspend fun resolve(context: Context): PasswordEntry? = withContext(Dispatchers.IO) {
        var source: AutofillPasswordSource? = null
        try {
            if (!isCurrent(context)) return@withContext null
            // Constructing SecurityManager can reopen protected preferences. Keep this preparation
            // bounded, then release the worker before getSelected performs its own bounded read.
            val prepared = BoundedSecureDataRead.readChecked(isCurrent = { isCurrent(context) }) {
                AutofillPasswordSource(context, SecurityManager(context), selection.generation,
                    isAuthorized = { isCurrent(context) }, allowLockedDatabases = true)
            } ?: return@withContext null
            source = prepared
            prepared.getSelected(selection)?.takeIf { isCurrent(context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            source?.clear()
        }
    }

    fun toBundle(): Bundle = Bundle().apply {
        putLong("database", selection.databaseId)
        putString("entry", selection.entryUuid)
        putString("revision", selection.revision)
        putLong("lock", selection.lockVersion)
        putString("process", selection.processNonce)
        putLong("generation", selection.generation)
        putString("target", targetPackage)
        putString("domain", webDomain)
        putLong("issued", issuedAt)
        putLong("expires", expiresAt)
        putLong("operation", operation)
        putBoolean("target_valid", targetValid)
    }

    companion object {
        private const val MAX_TTL_MS = 60_000L
        private val latestOperation = java.util.concurrent.atomic.AtomicLong(0L)

        fun revokeAll() { latestOperation.incrementAndGet() }

        fun create(selection: NativeAutofillSelection, targetPackage: String?, webDomain: String?,
            ttlMillis: Long = MAX_TTL_MS): NativeAutofillAction {
            require(ttlMillis in 1..MAX_TTL_MS)
            val now = SystemClock.elapsedRealtime()
            val domain = webDomain?.takeIf(String::isNotBlank)?.let(AutofillHostPolicy::host)
            return NativeAutofillAction(selection, targetPackage?.takeIf(String::isNotBlank),
                domain, now, now + ttlMillis, latestOperation.incrementAndGet(),
                webDomain.isNullOrBlank() || domain != null)
        }

        fun fromBundle(bundle: Bundle?): NativeAutofillAction? = runCatching {
            bundle ?: return@runCatching null
            val selection = NativeAutofillSelection(bundle.getLong("database"),
                requireNotNull(bundle.getString("entry")), requireNotNull(bundle.getString("revision")),
                bundle.getLong("lock"), requireNotNull(bundle.getString("process")), bundle.getLong("generation"))
            NativeAutofillAction(selection, bundle.getString("target"), bundle.getString("domain"),
                bundle.getLong("issued"), bundle.getLong("expires"), bundle.getLong("operation"),
                bundle.getBoolean("target_valid", false))
        }.getOrNull()
    }
}

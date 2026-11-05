package takagi.ru.monica.bitwarden.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.bitwarden.BitwardenVault
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Excludes sync timestamps and token rotation so successful pulls do not reopen every socket. */
internal fun notificationAccountFingerprint(vault: BitwardenVault): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(vault.id.toString(), vault.userId, vault.email, vault.serverUrl, vault.identityUrl, vault.apiUrl,
        vault.environmentPreset, vault.webVaultUrl, vault.notificationsUrl, vault.transportRevision, vault.transportSecretRef,
        vault.tlsCertificateAlias, vault.tlsCaCertificatePem, vault.tlsMtlsEnabled.toString(),
        vault.tlsClientCertPkcs12Base64, vault.tlsEncryptedClientCertPassword).forEach {
        val bytes = it.orEmpty().toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal class BitwardenNotificationMonitor(
    private val sessionProvider: suspend (Boolean) -> BitwardenNotificationSession?,
    private val isCurrent: suspend (BitwardenNotificationSession) -> Boolean,
    private val requestSync: (Long) -> Unit,
    private val connection: BitwardenNotificationConnection = BitwardenNotificationConnection(),
    private val retryDelay: (Int) -> Long = { attempt ->
        minOf(60_000L, 1_000L shl attempt.coerceAtMost(6)) + Random.nextLong(0, 251)
    },
) {
    suspend fun run(): Unit = coroutineScope {
        var attempt = 0
        var forceRefresh = false
        while (isActive) {
            val started = System.nanoTime()
            try {
                val session = sessionProvider(forceRefresh)
                if (session != null) {
                    forceRefresh = false
                    val reason = connection.run(session, { isCurrent(session) }, { requestSync(session.vaultId) })
                    forceRefresh = reason == BitwardenNotificationDisconnect.AUTHENTICATION ||
                        reason == BitwardenNotificationDisconnect.EXPIRED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed token/TLS/network operation never modifies vault data or leaks credentials into logs.
            }
            if ((System.nanoTime() - started) / 1_000_000 >= 30_000) attempt = 0
            delay(retryDelay(attempt))
            attempt = (attempt + 1).coerceAtMost(6)
        }
    }
}

/** Caller owns a foreground, unlocked, network-permitted coroutine scope. Cancellation closes all sockets. */
internal suspend fun runBitwardenForegroundNotifications(
    repository: BitwardenRepository,
    networkAllowed: () -> Boolean,
    requestSync: (Long, () -> Boolean) -> Unit,
) {
    repository.getAllVaultsFlow().map { vaults ->
        vaults.filter { it.syncEnabled && !it.isLocked }.associate { it.id to notificationAccountFingerprint(it) }
    }.distinctUntilChanged().collectLatest { accounts ->
        coroutineScope {
            accounts.keys.forEach { vaultId ->
                launch {
                    repository.getVaultSessionGenerationFlow(vaultId).collectLatest { generation ->
                        if (repository.isVaultUnlocked(vaultId) && networkAllowed()) {
                            val active = AtomicBoolean(true)
                            val stillValid = {
                                active.get() && networkAllowed() && repository.isVaultUnlocked(vaultId) &&
                                    repository.getVaultSessionGeneration(vaultId) == generation
                            }
                            try {
                                BitwardenNotificationMonitor(
                                    sessionProvider = { refresh ->
                                        if (stillValid()) repository.getNotificationSession(vaultId, refresh) else null
                                    },
                                    isCurrent = { session -> stillValid() && repository.isNotificationSessionCurrent(session) },
                                    requestSync = { id -> requestSync(id, stillValid) },
                                ).run()
                            } finally { active.set(false) }
                        }
                    }
                }
            }
        }
    }
}

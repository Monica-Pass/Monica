package takagi.ru.monica.bitwarden.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import takagi.ru.monica.bitwarden.api.BitwardenApiManager
import takagi.ru.monica.bitwarden.api.BitwardenVaultApi
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.bitwarden.BitwardenHistoricalRepairStateHelper
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.util.TotpDataResolver
import java.util.Date

data class BitwardenHistoricalTotpRepairResult(
    val normalizedTotpItems: Int,
    val queuedTotpItemsForSync: Int,
    val normalizedPasswords: Int,
    val queuedPasswordsForSync: Int,
    val skippedItems: Int
) {
    val normalizedCount: Int
        get() = normalizedTotpItems + normalizedPasswords

    val queuedForSyncCount: Int
        get() = queuedTotpItemsForSync + queuedPasswordsForSync
}

class BitwardenHistoricalTotpRepairService(
    context: Context,
    private val apiManager: BitwardenApiManager = BitwardenApiManager()
) {
    companion object {
        private const val TAG = "BwHistoricalTotpRepair"
    }

    private val appContext = context.applicationContext

    // BitwardenRepository creates this service as part of its object graph even
    // when no historical repair is requested. Keep Room and Android Keystore out
    // of that construction path; both are needed only when repairHistoricalTotp()
    // actually runs.
    private val database: PasswordDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PasswordDatabase.getDatabase(appContext)
    }
    private val passwordEntryDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        database.passwordEntryDao()
    }
    private val secureItemDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        database.secureItemDao()
    }
    private val securityManager: SecurityManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecurityManager(appContext)
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun decryptStoredSensitiveValue(value: String): String {
        return runCatching {
            securityManager.decryptDataIfMonicaCiphertext(value)
        }.getOrDefault(value)
    }

    private fun encodeStoredSensitiveValueForRewrite(originalValue: String, plainValue: String): String {
        return if (securityManager.looksLikeMonicaCiphertext(originalValue)) {
            securityManager.encryptDataLegacyCompat(plainValue)
        } else {
            plainValue
        }
    }

    suspend fun repairHistoricalTotp(
        vault: BitwardenVault,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey,
        requireCurrent: () -> Unit = {},
        withPublication: (suspend (suspend () -> Unit) -> Unit)? = null,
    ): BitwardenHistoricalTotpRepairResult = withContext(Dispatchers.IO) {
        val lease = RepairLease(requireCurrent, withPublication)
        lease.requireCurrent()
        val now = Date()
        val vaultApi = apiManager.getVaultApi(vault)

        var normalizedTotpItems = 0
        var queuedTotpItemsForSync = 0
        var normalizedPasswords = 0
        var queuedPasswordsForSync = 0
        var skippedItems = 0

        val secureItems = secureItemDao.getByBitwardenVaultId(vault.id)
            .filter { it.itemType == ItemType.TOTP && !it.isDeleted && !it.bitwardenCipherId.isNullOrBlank() }
        secureItems.forEach { item ->
            when (val outcome = repairStandaloneTotpItem(item, vaultApi, accessToken, symmetricKey, now, lease)) {
                is RepairOutcome.Repaired -> {
                    if (outcome.localChanged) normalizedTotpItems += 1
                    if (outcome.queuedForSync) queuedTotpItemsForSync += 1
                }
                RepairOutcome.Skipped -> skippedItems += 1
            }
        }

        val passwordEntries = passwordEntryDao.getByBitwardenVaultId(vault.id)
            .filter { !it.isDeleted && !it.bitwardenCipherId.isNullOrBlank() && it.bitwardenCipherType == 1 }
        passwordEntries.forEach { entry ->
            when (val outcome = repairBoundPasswordTotp(entry, vaultApi, accessToken, symmetricKey, now, lease)) {
                is RepairOutcome.Repaired -> {
                    if (outcome.localChanged) normalizedPasswords += 1
                    if (outcome.queuedForSync) queuedPasswordsForSync += 1
                }
                RepairOutcome.Skipped -> skippedItems += 1
            }
        }

        lease.requireCurrent()
        BitwardenHistoricalTotpRepairResult(
            normalizedTotpItems = normalizedTotpItems,
            queuedTotpItemsForSync = queuedTotpItemsForSync,
            normalizedPasswords = normalizedPasswords,
            queuedPasswordsForSync = queuedPasswordsForSync,
            skippedItems = skippedItems
        )
    }

    private suspend fun repairStandaloneTotpItem(
        item: SecureItem,
        vaultApi: BitwardenVaultApi,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey,
        now: Date,
        lease: RepairLease,
    ): RepairOutcome {
        lease.requireCurrent()
        val remoteCipherId = item.bitwardenCipherId ?: return repairStandaloneTotpItemFromLocal(item, now, lease)
        val remote = fetchRemoteTotpContext(vaultApi, accessToken, remoteCipherId, symmetricKey, lease)
            ?: return repairStandaloneTotpItemFromLocal(item, now, lease)

        val normalizedData = TotpDataResolver.fromAuthenticatorKey(
            rawKey = remote.totpRaw,
            fallbackIssuer = remote.displayName,
            fallbackAccountName = remote.username
        ) ?: return repairStandaloneTotpItemFromLocal(item, now, lease)

        val normalizedItemDataPlain = json.encodeToString(normalizedData)
        val normalizedItemData = encodeStoredSensitiveValueForRewrite(item.itemData, normalizedItemDataPlain)
        val normalizedPayload = TotpDataResolver.toBitwardenPayload(remote.displayName, normalizedData)
        val shouldQueueRemoteRewrite = shouldQueueRemoteRewrite(remote.totpRaw, normalizedPayload)

        val updatedItem = BitwardenHistoricalRepairStateHelper.applyToSecureItem(
            candidate = item.copy(
                itemData = normalizedItemData,
                updatedAt = if (normalizedItemData != item.itemData || shouldQueueRemoteRewrite) now else item.updatedAt,
                bitwardenRevisionDate = remote.revisionDate ?: item.bitwardenRevisionDate
            ),
            shouldQueueRemoteRewrite = shouldQueueRemoteRewrite
        )
        if (updatedItem != item) {
            lease.publish {
                check(secureItemDao.getItemById(item.id) == item) { "TOTP item changed during repair" }
                secureItemDao.update(updatedItem)
            }
        }

        lease.requireCurrent()
        return RepairOutcome.Repaired(
            localChanged = normalizedItemData != item.itemData,
            queuedForSync = shouldQueueRemoteRewrite
        )
    }

    private suspend fun repairBoundPasswordTotp(
        entry: PasswordEntry,
        vaultApi: BitwardenVaultApi,
        accessToken: String,
        symmetricKey: SymmetricCryptoKey,
        now: Date,
        lease: RepairLease,
    ): RepairOutcome {
        lease.requireCurrent()
        val remoteCipherId = entry.bitwardenCipherId ?: return repairBoundPasswordTotpFromLocal(entry, now, lease)
        val remote = fetchRemoteTotpContext(vaultApi, accessToken, remoteCipherId, symmetricKey, lease)
            ?: return repairBoundPasswordTotpFromLocal(entry, now, lease)

        val normalizedTotp = TotpDataResolver.fromAuthenticatorKey(
            rawKey = remote.totpRaw,
            fallbackIssuer = remote.website.takeIf { it.isNotBlank() } ?: remote.displayName,
            fallbackAccountName = remote.username
        ) ?: return repairBoundPasswordTotpFromLocal(entry, now, lease)

        val normalizedPayloadPlain = TotpDataResolver.toBitwardenPayload(remote.displayName, normalizedTotp)
        val normalizedPayload = encodeStoredSensitiveValueForRewrite(entry.authenticatorKey, normalizedPayloadPlain)
        val shouldQueueRemoteRewrite = shouldQueueRemoteRewrite(remote.totpRaw, normalizedPayloadPlain)

        val updatedEntry = BitwardenHistoricalRepairStateHelper.applyToPasswordEntry(
            candidate = entry.copy(
                authenticatorKey = normalizedPayload,
                updatedAt = if (normalizedPayload != entry.authenticatorKey || shouldQueueRemoteRewrite) now else entry.updatedAt,
                bitwardenRevisionDate = remote.revisionDate ?: entry.bitwardenRevisionDate
            ),
            shouldQueueRemoteRewrite = shouldQueueRemoteRewrite
        )
        if (updatedEntry != entry) {
            lease.publish {
                check(passwordEntryDao.getPasswordEntryById(entry.id) == entry) { "Password changed during TOTP repair" }
                passwordEntryDao.update(updatedEntry)
            }
        }

        lease.requireCurrent()
        return RepairOutcome.Repaired(
            localChanged = normalizedPayload != entry.authenticatorKey,
            queuedForSync = shouldQueueRemoteRewrite
        )
    }

    private suspend fun repairStandaloneTotpItemFromLocal(
        item: SecureItem,
        now: Date,
        lease: RepairLease,
    ): RepairOutcome {
        lease.requireCurrent()
        val normalizedData = TotpDataResolver.parseStoredItemData(
            itemData = item.itemData,
            fallbackIssuer = item.title,
            decryptIfNeeded = ::decryptStoredSensitiveValue
        ) ?: return RepairOutcome.Skipped

        val normalizedItemDataPlain = json.encodeToString(normalizedData)
        val normalizedItemData = encodeStoredSensitiveValueForRewrite(item.itemData, normalizedItemDataPlain)
        val shouldQueueRemoteRewrite =
            (item.bitwardenCipherId != null) && (
                decryptStoredSensitiveValue(item.itemData).contains("://", ignoreCase = true) ||
                    TotpDataResolver.hasNonDefaultOtpSettings(normalizedData)
                )

        val updatedItem = BitwardenHistoricalRepairStateHelper.applyToSecureItem(
            candidate = item.copy(
                itemData = normalizedItemData,
                updatedAt = if (normalizedItemData != item.itemData || shouldQueueRemoteRewrite) now else item.updatedAt
            ),
            shouldQueueRemoteRewrite = shouldQueueRemoteRewrite
        )
        if (updatedItem != item) {
            lease.publish {
                check(secureItemDao.getItemById(item.id) == item) { "TOTP item changed during repair" }
                secureItemDao.update(updatedItem)
            }
        }

        lease.requireCurrent()
        return RepairOutcome.Repaired(
            localChanged = normalizedItemData != item.itemData,
            queuedForSync = shouldQueueRemoteRewrite
        )
    }

    private suspend fun repairBoundPasswordTotpFromLocal(
        entry: PasswordEntry,
        now: Date,
        lease: RepairLease,
    ): RepairOutcome {
        lease.requireCurrent()
        if (entry.authenticatorKey.isBlank()) {
            return RepairOutcome.Skipped
        }

        val normalizedTotp = TotpDataResolver.fromAuthenticatorKey(
            rawKey = decryptStoredSensitiveValue(entry.authenticatorKey),
            fallbackIssuer = entry.website.takeIf { it.isNotBlank() } ?: entry.title,
            fallbackAccountName = entry.username.takeIf { it.isNotBlank() } ?: entry.title
        ) ?: return RepairOutcome.Skipped

        val normalizedPayloadPlain = TotpDataResolver.toBitwardenPayload(entry.title, normalizedTotp)
        val normalizedPayload = encodeStoredSensitiveValueForRewrite(entry.authenticatorKey, normalizedPayloadPlain)
        val shouldQueueRemoteRewrite =
            (entry.bitwardenCipherId != null) && (
                decryptStoredSensitiveValue(entry.authenticatorKey).contains("://", ignoreCase = true) ||
                    TotpDataResolver.hasNonDefaultOtpSettings(normalizedTotp)
                )

        val updatedEntry = BitwardenHistoricalRepairStateHelper.applyToPasswordEntry(
            candidate = entry.copy(
                authenticatorKey = normalizedPayload,
                updatedAt = if (normalizedPayload != entry.authenticatorKey || shouldQueueRemoteRewrite) now else entry.updatedAt
            ),
            shouldQueueRemoteRewrite = shouldQueueRemoteRewrite
        )
        if (updatedEntry != entry) {
            lease.publish {
                check(passwordEntryDao.getPasswordEntryById(entry.id) == entry) { "Password changed during TOTP repair" }
                passwordEntryDao.update(updatedEntry)
            }
        }

        lease.requireCurrent()
        return RepairOutcome.Repaired(
            localChanged = normalizedPayload != entry.authenticatorKey,
            queuedForSync = shouldQueueRemoteRewrite
        )
    }

    private suspend fun fetchRemoteTotpContext(
        vaultApi: BitwardenVaultApi,
        accessToken: String,
        cipherId: String,
        symmetricKey: SymmetricCryptoKey,
        lease: RepairLease,
    ): RemoteTotpContext? {
        lease.requireCurrent()
        val response = try {
            vaultApi.getCipher(authorization = "Bearer $accessToken", cipherId = cipherId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            lease.requireCurrent()
            Log.w(TAG, "TOTP repair fetch failed; kind=${classifyBitwardenLoginError(error)?.name ?: "UNKNOWN"}")
            return null
        }
        lease.requireCurrent()
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            Log.w(TAG, "TOTP repair fetch rejected; HTTP=${response.code()}")
            return null
        }

        val cipher = response.body() ?: return null
        val login = cipher.login ?: return null
        val remoteTotp = decryptString(login.totp, symmetricKey)?.trim().orEmpty()
        if (remoteTotp.isBlank()) {
            return null
        }

        lease.requireCurrent()
        return RemoteTotpContext(
            displayName = decryptString(cipher.name, symmetricKey).orEmpty().ifBlank { "Authenticator" },
            username = decryptString(login.username, symmetricKey).orEmpty(),
            website = extractPrimaryWebsite(login.uris, symmetricKey),
            totpRaw = remoteTotp,
            revisionDate = cipher.revisionDate
        )
    }

    private fun shouldQueueRemoteRewrite(remoteTotpRaw: String, normalizedPayload: String): Boolean {
        return remoteTotpRaw.trim() != normalizedPayload.trim()
    }

    private fun extractPrimaryWebsite(
        uris: List<takagi.ru.monica.bitwarden.api.CipherUriApiData>?,
        symmetricKey: SymmetricCryptoKey
    ): String {
        if (uris.isNullOrEmpty()) return ""

        var website = ""
        uris.forEach { uriData ->
            val uri = decryptString(uriData.uri, symmetricKey) ?: return@forEach
            if (!uri.startsWith("androidapp://", ignoreCase = true) && website.isBlank()) {
                website = uri
            }
        }
        return website
    }

    private fun decryptString(encrypted: String?, key: SymmetricCryptoKey): String? {
        if (encrypted.isNullOrBlank()) return null
        return try {
            BitwardenCrypto.decryptToString(encrypted, key)
        } catch (_: Exception) {
            null
        }
    }

    private class RepairLease(
        val requireCurrent: () -> Unit,
        val withPublication: (suspend (suspend () -> Unit) -> Unit)?,
    ) {
        suspend fun publish(action: suspend () -> Unit) {
            val checked: suspend () -> Unit = {
                requireCurrent()
                action()
                requireCurrent()
            }
            if (withPublication == null) checked() else withPublication.invoke(checked)
        }
    }

    private data class RemoteTotpContext(
        val displayName: String,
        val username: String,
        val website: String,
        val totpRaw: String,
        val revisionDate: String?
    )

    private sealed class RepairOutcome {
        data class Repaired(
            val localChanged: Boolean,
            val queuedForSync: Boolean
        ) : RepairOutcome()

        data object Skipped : RepairOutcome()
    }
}

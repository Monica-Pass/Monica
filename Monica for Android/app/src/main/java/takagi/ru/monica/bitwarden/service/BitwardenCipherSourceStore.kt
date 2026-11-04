package takagi.ru.monica.bitwarden.service

import android.content.Context
import kotlinx.serialization.json.*
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.bitwarden.BitwardenCipherSource
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.bitwarden.api.CipherApiResponse
import takagi.ru.monica.bitwarden.BitwardenVaultIdentity
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager

/** Called in the existing Room acceptance transaction; never authorizes a network mutation. */
internal class BitwardenCipherSourceStore(context: Context) {
    private val dao = PasswordDatabase.getDatabase(context).bitwardenCipherSourceDao()
    private val security = SecurityManager(context)

    suspend fun accept(vault: BitwardenVault, cipher: CipherApiResponse, boundRevision: String?) {
        val source = cipher.sourceDocument ?: return
        require(cipher.revisionDate.isNotBlank()) { "Missing source revision" }
        val account = account(vault)
        val envelope = JsonObject(mapOf(
            "format" to JsonPrimitive(1), "vaultId" to JsonPrimitive(vault.id),
            "accountKey" to JsonPrimitive(account), "cipherId" to JsonPrimitive(cipher.id),
            "revisionDate" to JsonPrimitive(cipher.revisionDate), "document" to source
        ))
        dao.put(BitwardenCipherSource(vault.id, cipher.id, cipher.revisionDate, account, security.encryptData(envelope.toString())))
        dao.retain(vault.id, cipher.id, cipher.revisionDate, boundRevision ?: cipher.revisionDate)
    }

    private fun account(vault: BitwardenVault): String {
        require(!vault.userId.isNullOrBlank()) { "Missing source account identity" }
        return BitwardenVaultIdentity.buildAccountKey(vault.serverUrl, vault.userId, BitwardenVaultIdentity.resolveCanonicalEmail(vault))
    }

    suspend fun read(vault: BitwardenVault, cipherId: String, revision: String, sessionIsCurrent: () -> Boolean): CipherApiResponse? {
        val generation = SessionManager.lockGeneration.value
        fun requireSession() {
            check(SessionManager.isUnlocked.value && SessionManager.lockGeneration.value == generation && sessionIsCurrent()) {
                "Source session changed"
            }
        }
        requireSession()
        val source = dao.get(vault.id, cipherId, revision) ?: return null
        val account = account(vault)
        require(source.accountKey == account) { "Source account changed" }
        val envelope = BitwardenWireJson.parseObject(security.decryptData(source.sealedDocument))
        require(envelope["format"] == JsonPrimitive(1) && envelope["vaultId"] == JsonPrimitive(vault.id) &&
            envelope["accountKey"] == JsonPrimitive(account) && envelope["cipherId"] == JsonPrimitive(cipherId) &&
            envelope["revisionDate"] == JsonPrimitive(revision)) { "Source binding changed" }
        return BitwardenCipherDocument.decode(envelope.getValue("document").jsonObject).also {
            require(it.id == cipherId && it.revisionDate == revision) { "Source revision changed" }
            requireSession()
        }
    }
}

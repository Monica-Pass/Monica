package takagi.ru.monica.data.dedup

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.ensureActive
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.security.SecurityManager
import kotlin.coroutines.coroutineContext

/** Compare credential identities, never just account names. Does not alter source records. */
internal class DedupPasskeyPlanner(private val security: SecurityManager) {
    private data class Material(val fingerprint: String, val portable: Boolean)

    suspend fun build(
        sources: List<PasskeyEntry>,
        targetEntries: List<PasskeyEntry>,
        target: DedupMergeTarget,
        policy: DedupConflictPolicy,
        sourceLabel: (PasskeyEntry) -> String,
    ): List<DedupResolvedPasskey> {
        val materials = mutableMapOf<String, Material?>()
        fun material(entry: PasskeyEntry): Material? = materials.getOrPut(entry.privateKeyAlias) {
            runCatching {
                val resolved = PasskeyPrivateKeyStore.resolve(security, entry.privateKeyAlias) ?: return@getOrPut null
                val decoded = PasskeyPrivateKeySupport.decodeFlexiblePrivateKey(resolved)
                if (decoded != null) Material(digestBytes(decoded.pkcs8Bytes), true)
                else if (PasskeyPrivateKeySupport.hasUsablePrivateKey(resolved)) Material(digest(listOf(resolved)), false)
                else null
            }.getOrNull()
        }
        fun identity(entry: PasskeyEntry): String? {
            if (entry.rpId.isBlank() || entry.credentialId.isBlank() || entry.publicKey.isBlank()) return null
            val key = material(entry) ?: return null
            return digest(listOf(
                credentialId(entry), entry.rpId.trim().lowercase(Locale.ROOT), canonicalBase64(entry.userId),
                canonicalBase64(entry.publicKey), entry.publicKeyAlgorithm.toString(), key.fingerprint,
                entry.isDiscoverable.toString(), entry.isUserVerificationRequired.toString(),
                entry.isBackedUp.toString(), entry.signCount.toString(), entry.notes,
                entry.backupEligible.toString(), entry.backupState.toString(),
            ))
        }
        val targetByCredential = targetEntries.groupBy(::credentialId)
        val targetIdentities = targetEntries.mapNotNull { entry -> identity(entry)?.let { entry.id to it } }.toMap()
        val identities = sources.associate { it.id to identity(it) }
        val sourceIdentityCounts = sources.groupBy(::credentialId).mapValues { (_, entries) ->
            // A missing or inconsistent key with the same credential ID cannot be repaired by guessing.
            entries.map { identities[it.id] }.distinct().size
        }
        val rows = mutableListOf<DedupResolvedPasskey>()
        val candidates = mutableListOf<PasskeyEntry>()
        for (source in sources) {
            coroutineContext.ensureActive()
            val identity = identities[source.id]
            val collision = targetByCredential[credentialId(source)].orEmpty()
            val sameTarget = identity != null && collision.any {
                !it.syncStatus.equals("REFERENCE", true) && targetIdentities[it.id] == identity
            }
            val reason = when {
                sameTarget -> null
                source.syncStatus.equals("REFERENCE", true) || identity == null -> DedupPasskeySkipReason.REFERENCE_OR_MISSING_KEY
                source.signCount != 0L -> DedupPasskeySkipReason.NONZERO_COUNTER
                source.boundPasswordId != null -> DedupPasskeySkipReason.BOUND_PASSWORD
                target is DedupMergeTarget.MdbxDatabase && material(source)?.portable != true -> DedupPasskeySkipReason.DEVICE_KEY
                collision.isNotEmpty() || sourceIdentityCounts.getValue(credentialId(source)) > 1 -> DedupPasskeySkipReason.CREDENTIAL_CONFLICT
                else -> null
            }
            if (reason == null && !sameTarget) candidates += source
            else rows += DedupResolvedPasskey(
                mergeKey = "passkey-record:${source.id}", entry = source,
                sourceEntryIds = listOf(source.id), sourceLabels = listOf(sourceLabel(source)),
                preferredSourceLabel = sourceLabel(source), existsInTarget = sameTarget, skipReason = reason,
            )
        }
        for ((identity, entries) in candidates.groupBy { identities.getValue(it.id)!! }) {
            coroutineContext.ensureActive()
            fun completeness(entry: PasskeyEntry) = listOf(entry.rpName, entry.userName, entry.userDisplayName, entry.notes, entry.iconUrl.orEmpty()).count { it.isNotBlank() }
            // Passkeys have no edited-at field; do not treat last-used time as an edit.
            val comparator = if (policy == DedupConflictPolicy.NEWEST) compareBy<PasskeyEntry> { it.createdAt }.thenBy(::completeness)
                else compareBy<PasskeyEntry>(::completeness).thenBy { it.createdAt }
            val keeper = entries.maxWith(comparator)
            val copied = keeper.copy(id = 0, categoryId = null, boundPasswordId = null,
                keepassDatabaseId = null, keepassGroupPath = null, bitwardenVaultId = null,
                bitwardenCipherId = null, bitwardenFolderId = null, syncStatus = "NONE",
                mdbxDatabaseId = (target as? DedupMergeTarget.MdbxDatabase)?.databaseId, mdbxFolderId = null)
            rows += DedupResolvedPasskey(identity, copied, entries.map { it.id },
                entries.map(sourceLabel).distinct(), sourceLabel(keeper))
        }
        return rows.sortedWith(compareBy<DedupResolvedPasskey> { !it.writable }
            .thenBy { it.entry.rpId }.thenBy { it.entry.userName })
    }

    private fun credentialId(entry: PasskeyEntry): String = PasskeyCredentialIdCodec.normalize(entry.credentialId).orEmpty()

    private fun canonicalBase64(value: String): String = runCatching {
        Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value))
    }.recoverCatching {
        Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(value))
    }.getOrDefault(value)

    private fun digest(values: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash.digest())
    }

    private fun digestBytes(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
}

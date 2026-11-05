package takagi.ru.monica.bitwarden.service

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class BitwardenAccountMutationPhase { PREPARED, SENDING, UNCERTAIN, CONFIRMED, REJECTED }

/** Contains encrypted wrappers only, never a password, proof, token or unwrapped user key. */
@Serializable
internal data class BitwardenAccountMutationRecord(
    val version: Int = 1,
    val operationId: String,
    val binding: BitwardenAccountMutationBinding,
    val kind: BitwardenAccountMutationKind,
    val phase: BitwardenAccountMutationPhase,
    val oldKdf: BitwardenAccountMutationKdf,
    val newKdf: BitwardenAccountMutationKdf,
    val oldWrappedUserKey: String,
    val newWrappedUserKey: String,
    val requestedHint: String?,
    val originalProfileName: String?,
) {
    internal fun validate() {
        require(version == 1 && UUID.fromString(operationId).toString() == operationId)
        binding.validate()
        require(listOf(oldWrappedUserKey, newWrappedUserKey).all {
            it.length <= 4096 && it.startsWith("2.") && it.count { c -> c == '|' } == 2
        })
        require((requestedHint?.length ?: 0) <= 4096 && (originalProfileName?.length ?: 0) <= 4096)
        if (kind != BitwardenAccountMutationKind.KDF) require(oldKdf == newKdf)
        if (kind !in setOf(BitwardenAccountMutationKind.PASSWORD, BitwardenAccountMutationKind.KDF)) {
            require(oldWrappedUserKey == newWrappedUserKey)
        }
    }
    override fun toString() = "BitwardenAccountMutationRecord(kind=$kind, phase=$phase, redacted)"
}

internal sealed interface BitwardenAccountMutationStored {
    data object Absent : BitwardenAccountMutationStored
    class Present(val record: BitwardenAccountMutationRecord) : BitwardenAccountMutationStored {
        override fun toString() = "Present(${record.phase}, redacted)"
    }
    /** Treat corruption, locked protection keys and read failure as blocked, never as Absent. */
    data object Unreadable : BitwardenAccountMutationStored
}

/**
 * Root provides durable storage and authenticated encryption with a key available after app unlock.
 * Use one shared store instance/account gate across Repository instances. Reads must throw on I/O
 * failure, rather than returning null. A write/remove must synchronously commit before returning.
 * seal/open receive the storage key as associated context to prevent cross-account relocation.
 */
internal class BitwardenAccountMutationIntentStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Boolean,
    private val remove: (String) -> Boolean,
    private val seal: (storageKey: String, plaintext: String) -> String,
    private val open: (storageKey: String, sealed: String) -> String,
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private companion object { val monitor = Any() }
    private fun key(vaultId: Long) = "bitwarden_account_mutation_v1_$vaultId"

    fun restore(vaultId: Long): BitwardenAccountMutationStored = synchronized(monitor) {
        try {
            require(vaultId > 0)
            val storageKey = key(vaultId)
            val sealed = read(storageKey) ?: return@synchronized BitwardenAccountMutationStored.Absent
            require(sealed.length <= 128 * 1024)
            val plaintext = open(storageKey, sealed)
            require(plaintext.length <= 32 * 1024)
            val record = json.decodeFromString<BitwardenAccountMutationRecord>(plaintext)
            record.validate()
            require(record.binding.vaultId == vaultId)
            BitwardenAccountMutationStored.Present(record)
        } catch (_: Exception) { BitwardenAccountMutationStored.Unreadable }
    }

    fun stage(record: BitwardenAccountMutationRecord): BitwardenAccountMutationRecord = synchronized(monitor) {
        record.validate()
        check(record.phase == BitwardenAccountMutationPhase.PREPARED)
        check(restore(record.binding.vaultId) is BitwardenAccountMutationStored.Absent) { "Account already requires reconciliation" }
        persist(record)
    }

    fun transition(
        expected: BitwardenAccountMutationRecord,
        phase: BitwardenAccountMutationPhase,
    ): BitwardenAccountMutationRecord = synchronized(monitor) {
        val current = checkNotNull((restore(expected.binding.vaultId) as? BitwardenAccountMutationStored.Present)?.record)
        check(current == expected) { "Account mutation changed" }
        val allowed = when (current.phase) {
            BitwardenAccountMutationPhase.PREPARED -> setOf(BitwardenAccountMutationPhase.SENDING)
            BitwardenAccountMutationPhase.SENDING -> setOf(BitwardenAccountMutationPhase.UNCERTAIN,
                BitwardenAccountMutationPhase.CONFIRMED, BitwardenAccountMutationPhase.REJECTED)
            BitwardenAccountMutationPhase.UNCERTAIN -> setOf(BitwardenAccountMutationPhase.CONFIRMED,
                BitwardenAccountMutationPhase.REJECTED)
            else -> emptySet()
        }
        check(phase in allowed) { "Invalid account mutation transition" }
        persist(current.copy(phase = phase))
    }

    /** Caller may release the freeze only after this succeeds. Does not touch vault data. */
    fun discardUnsentOrRejected(expected: BitwardenAccountMutationRecord): Boolean = synchronized(monitor) {
        if (expected.phase !in setOf(BitwardenAccountMutationPhase.PREPARED, BitwardenAccountMutationPhase.REJECTED)) return@synchronized false
        removeExact(expected)
    }

    /** Root has already atomically published a verified reauthentication; deletion stays archived. */
    fun acknowledgeReconciled(expected: BitwardenAccountMutationRecord): Boolean = synchronized(monitor) {
        if (expected.kind == BitwardenAccountMutationKind.DELETE || expected.phase != BitwardenAccountMutationPhase.CONFIRMED) return@synchronized false
        removeExact(expected)
    }

    /** Fresh identity, original user key and current wrapper/KDF were verified and committed by
     * Repository. Keep the actual receipt phase unchanged if this CAS removal fails. */
    fun acknowledgeVerifiedRecovery(expected: BitwardenAccountMutationRecord): Boolean = synchronized(monitor) {
        if (expected.kind == BitwardenAccountMutationKind.DELETE &&
            expected.phase == BitwardenAccountMutationPhase.CONFIRMED) return@synchronized false
        removeExact(expected)
    }

    private fun removeExact(expected: BitwardenAccountMutationRecord): Boolean = try {
        val current = (restore(expected.binding.vaultId) as? BitwardenAccountMutationStored.Present)?.record
        current == expected && remove(key(expected.binding.vaultId)) &&
            restore(expected.binding.vaultId) is BitwardenAccountMutationStored.Absent
    } catch (_: Exception) { false }

    private fun persist(record: BitwardenAccountMutationRecord): BitwardenAccountMutationRecord {
        val storageKey = key(record.binding.vaultId)
        val sealed = seal(storageKey, json.encodeToString(record))
        check(sealed.isNotBlank() && sealed.length <= 128 * 1024) { "Account mutation protection failed" }
        check(write(storageKey, sealed)) { "Account mutation persistence failed" }
        check((restore(record.binding.vaultId) as? BitwardenAccountMutationStored.Present)?.record == record) {
            "Account mutation persistence verification failed"
        }
        return record
    }
}

package takagi.ru.monica.bitwarden.service

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto
import takagi.ru.monica.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import takagi.ru.monica.utils.PasswordStrengthAnalyzer

/** Identity and transport captured by Repository after authenticated profile validation. */
@Serializable
internal data class BitwardenAccountMutationBinding(
    val vaultId: Long,
    val userId: String,
    val canonicalEmail: String,
    val environmentFingerprint: String,
    val transportRevision: String,
    val transportSecretRef: String?,
    val accountGeneration: Long,
    val appGeneration: Long,
    val securityStamp: String?,
) {
    internal fun validate() {
        require(vaultId > 0 && userId.isNotBlank() && userId.length <= 256)
        require(canonicalEmail.isNotBlank() && canonicalEmail.length <= 320 &&
            canonicalEmail == canonicalEmail.trim().lowercase(Locale.ROOT))
        require(environmentFingerprint.isNotBlank() && transportRevision.isNotBlank())
        require(listOfNotNull(environmentFingerprint, transportRevision, transportSecretRef).all {
            it.length <= 512 && '#' !in it && '\u0000' !in it
        })
        require(accountGeneration >= 0 && appGeneration >= 0)
    }

    override fun toString() = "BitwardenAccountMutationBinding(redacted)"
}

@Serializable
internal data class BitwardenAccountMutationKdf(
    val type: Int,
    val iterations: Int,
    val memoryMiB: Int? = null,
    val parallelism: Int? = null,
) {
    internal fun json(): JsonObject = buildJsonObject {
        put("kdfType", type)
        put("iterations", iterations)
        put("memory", memoryMiB?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
        put("parallelism", parallelism?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
    }
}

/** Bounds for *new* choices. Existing login/KDF compatibility is not modified here. */
internal data class BitwardenAccountMutationKdfLimits(
    val maxPbkdf2Iterations: Int = 2_000_000,
    val maxArgonIterations: Int = 10,
    val maxArgonMemoryMiB: Int = 64,
    val maxArgonParallelism: Int = 16,
)

/** Missing hint metadata is not an empty hint. Unsupported crypto/auth must be explicit. */
internal class BitwardenAccountMutationSnapshot(
    val binding: BitwardenAccountMutationBinding,
    val kdf: BitwardenAccountMutationKdf,
    val salt: String,
    val wrappedUserKey: String,
    val hasMasterPassword: Boolean,
    val usesKeyConnector: Boolean,
    val legacyUserKey: Boolean,
    val profileName: String?,
    val hintPresent: Boolean,
    val hint: String?,
    val hintEditingAllowed: Boolean,
) {
    override fun toString() = "BitwardenAccountMutationSnapshot(redacted)"
}

internal sealed interface BitwardenAccountMutationHint {
    data object Preserve : BitwardenAccountMutationHint
    class Replace(val value: String?) : BitwardenAccountMutationHint {
        override fun toString() = "Replace(redacted)"
    }
}

internal sealed interface BitwardenAccountMutationAction {
    class HintProfile(val hint: String?) : BitwardenAccountMutationAction {
        override fun toString() = "HintProfile(redacted)"
    }
    /** Deliberate password-endpoint action: never selected as an HTTP fallback. */
    class HintWithPassword(val hint: String?) : BitwardenAccountMutationAction {
        override fun toString() = "HintWithPassword(redacted)"
    }
    class Password(
        val password: String,
        val confirmation: String,
        val hint: BitwardenAccountMutationHint,
    ) : BitwardenAccountMutationAction {
        override fun toString() = "Password(redacted)"
    }
    class Kdf(val kdf: BitwardenAccountMutationKdf) : BitwardenAccountMutationAction
    data object Delete : BitwardenAccountMutationAction
}

@Serializable
internal enum class BitwardenAccountMutationKind { HINT_PROFILE, HINT_WITH_PASSWORD, PASSWORD, KDF, DELETE }

internal class BitwardenAccountMutationRequest(
    val binding: BitwardenAccountMutationBinding,
    val method: String,
    val relativePath: String,
    val body: JsonObject,
) {
    override fun toString() = "BitwardenAccountMutationRequest(redacted)"
}

/** The request/proofs are transient; the record deliberately cannot reconstruct a write request. */
internal class BitwardenAccountMutationPrepared internal constructor(
    val record: BitwardenAccountMutationRecord,
    private var request: BitwardenAccountMutationRequest?,
) {
    private val consumed = AtomicBoolean(false)
    internal fun consume(): BitwardenAccountMutationRequest {
        check(consumed.compareAndSet(false, true)) { "Account mutation already consumed" }
        return checkNotNull(request).also { request = null }
    }
    fun discard() { consumed.set(true); request = null }
    override fun toString() = "BitwardenAccountMutationPrepared(redacted)"
}

internal class BitwardenAccountMutationProtocol(
    private val limits: BitwardenAccountMutationKdfLimits = BitwardenAccountMutationKdfLimits(),
    private val passwordAllowed: (String) -> Boolean = {
        it.length >= 12 && PasswordStrengthAnalyzer.calculateStrength(it) >= 61
    },
) {
    fun prepare(
        snapshot: BitwardenAccountMutationSnapshot,
        action: BitwardenAccountMutationAction,
        currentPassword: String,
        existingUserKey: SymmetricCryptoKey,
    ): BitwardenAccountMutationPrepared {
        snapshot.binding.validate()
        require(snapshot.hasMasterPassword && !snapshot.usesKeyConnector && snapshot.legacyUserKey) {
            "Account authentication or key format is unsupported"
        }
        require(snapshot.salt == snapshot.binding.canonicalEmail) { "Account salt is unsupported" }
        require(currentPassword.isNotEmpty() && currentPassword.length <= 4096)
        validateKdf(snapshot.kdf, newChoice = false)
        requireLegacyWrapper(snapshot.wrappedUserKey)
        val kind = when (action) {
            is BitwardenAccountMutationAction.HintProfile -> BitwardenAccountMutationKind.HINT_PROFILE
            is BitwardenAccountMutationAction.HintWithPassword -> BitwardenAccountMutationKind.HINT_WITH_PASSWORD
            is BitwardenAccountMutationAction.Password -> BitwardenAccountMutationKind.PASSWORD
            is BitwardenAccountMutationAction.Kdf -> BitwardenAccountMutationKind.KDF
            BitwardenAccountMutationAction.Delete -> BitwardenAccountMutationKind.DELETE
        }
        val hint = when (action) {
            is BitwardenAccountMutationAction.HintProfile -> {
                require(snapshot.hintPresent && snapshot.hintEditingAllowed && snapshot.profileName != null) {
                    "Server does not provide editable password hint metadata"
                }
                action.hint
            }
            is BitwardenAccountMutationAction.HintWithPassword -> action.hint
            is BitwardenAccountMutationAction.Password -> when (val selection = action.hint) {
                BitwardenAccountMutationHint.Preserve -> {
                    require(snapshot.hintPresent) { "Current password hint is unavailable" }
                    snapshot.hint
                }
                is BitwardenAccountMutationHint.Replace -> selection.value
            }
            else -> null
        }
        require(hint == null || (hint.length <= 4096 && '\u0000' !in hint)) { "Invalid password hint" }
        if (kind == BitwardenAccountMutationKind.HINT_WITH_PASSWORD || kind == BitwardenAccountMutationKind.PASSWORD) {
            require(snapshot.hintEditingAllowed || hint.isNullOrEmpty()) { "Server disallows password hints" }
        }
        val nextPassword = if (action is BitwardenAccountMutationAction.Password) {
            require(action.password == action.confirmation) { "New passwords do not match" }
            require(action.password.length <= 4096 && passwordAllowed(action.password)) { "New password is not permitted" }
            action.password
        } else currentPassword
        val nextKdf = (action as? BitwardenAccountMutationAction.Kdf)?.kdf ?: snapshot.kdf
        if (action is BitwardenAccountMutationAction.Kdf) validateKdf(nextKdf, newChoice = true)
        val oldMaster = derive(currentPassword, snapshot.salt, snapshot.kdf)
        val oldStretched = BitwardenCrypto.stretchMasterKey(oldMaster)
        var unwrapped: ByteArray? = null
        val expected = existingUserKey.encKey + existingUserKey.macKey
        var newMaster: ByteArray? = null
        var newStretched: SymmetricCryptoKey? = null
        try {
            unwrapped = BitwardenCrypto.decrypt(snapshot.wrappedUserKey, oldStretched)
            require(unwrapped.size == 64 && MessageDigest.isEqual(unwrapped, expected)) { "Account key verification failed" }
            val proof = BitwardenCrypto.deriveMasterPasswordHash(oldMaster, currentPassword)
            val rewrap = kind == BitwardenAccountMutationKind.PASSWORD || kind == BitwardenAccountMutationKind.KDF
            val nextWrapper: String
            val nextProof: String
            if (rewrap) {
                newMaster = derive(nextPassword, snapshot.salt, nextKdf)
                newStretched = BitwardenCrypto.stretchMasterKey(newMaster)
                nextWrapper = BitwardenCrypto.encrypt(unwrapped, newStretched)
                val checkBytes = BitwardenCrypto.decrypt(nextWrapper, newStretched)
                try { require(MessageDigest.isEqual(checkBytes, expected)) { "Account key verification failed" } }
                finally { checkBytes.fill(0) }
                nextProof = BitwardenCrypto.deriveMasterPasswordHash(newMaster, nextPassword)
            } else {
                nextWrapper = snapshot.wrappedUserKey
                nextProof = proof
            }
            val body = when (kind) {
                BitwardenAccountMutationKind.HINT_PROFILE -> buildJsonObject {
                    put("name", snapshot.profileName!!)
                    put("masterPasswordHint", hint?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                }
                BitwardenAccountMutationKind.DELETE -> buildJsonObject {
                    put("MasterPasswordHash", proof); put("otp", JsonNull)
                }
                else -> buildJsonObject {
                    put("masterPasswordHash", proof)
                    if (kind != BitwardenAccountMutationKind.KDF) {
                        put("masterPasswordHint", hint?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                    }
                    // Identical compatibility aliases, matching the official Android KDF request.
                    put("key", nextWrapper)
                    put("newMasterPasswordHash", nextProof)
                    put("authenticationData", buildJsonObject {
                        put("salt", snapshot.salt); put("kdf", nextKdf.json())
                        put("masterPasswordAuthenticationHash", nextProof)
                    })
                    put("unlockData", buildJsonObject {
                        put("salt", snapshot.salt); put("kdf", nextKdf.json())
                        put("masterKeyWrappedUserKey", nextWrapper); put("containedKeyId", JsonNull)
                    })
                }
            }
            val (method, path) = when (kind) {
                BitwardenAccountMutationKind.HINT_PROFILE -> "PUT" to "accounts/profile"
                BitwardenAccountMutationKind.KDF -> "POST" to "accounts/kdf"
                BitwardenAccountMutationKind.DELETE -> "DELETE" to "accounts"
                else -> "POST" to "accounts/password"
            }
            val record = BitwardenAccountMutationRecord(
                operationId = UUID.randomUUID().toString(), binding = snapshot.binding, kind = kind,
                phase = BitwardenAccountMutationPhase.PREPARED, oldKdf = snapshot.kdf, newKdf = nextKdf,
                oldWrappedUserKey = snapshot.wrappedUserKey, newWrappedUserKey = nextWrapper,
                requestedHint = hint, originalProfileName = snapshot.profileName,
            )
            return BitwardenAccountMutationPrepared(record, BitwardenAccountMutationRequest(snapshot.binding, method, path, body))
        } finally {
            oldMaster.fill(0); oldStretched.clear(); unwrapped?.fill(0); expected.fill(0)
            newMaster?.fill(0); newStretched?.clear()
        }
    }

    /** Must be called on a fresh, independently authenticated profile response. */
    fun hintReadbackMatches(record: BitwardenAccountMutationRecord, profile: JsonObject): Boolean = runCatching {
        require(record.kind == BitwardenAccountMutationKind.HINT_PROFILE)
        fun field(name: String) = BitwardenAccountMutationMetadata.field(profile, name)
        val id = BitwardenAccountMutationMetadata.string(profile, "id")
        val email = BitwardenAccountMutationMetadata.string(profile, "email")?.trim()?.lowercase(Locale.ROOT)
        val hint = field("masterPasswordHint") ?: return false
        if (hint != JsonNull && (hint !is kotlinx.serialization.json.JsonPrimitive || !hint.isString)) return false
        id == record.binding.userId && email == record.binding.canonicalEmail &&
            hint.jsonPrimitive.contentOrNull == record.requestedHint &&
            BitwardenAccountMutationMetadata.string(profile, "name") == record.originalProfileName
    }.getOrDefault(false)

    /** Root verifies fresh identity/transport first, then uses this before replacing any local key. */
    fun recoveryKeyMatches(
        record: BitwardenAccountMutationRecord,
        freshBinding: BitwardenAccountMutationBinding,
        recoveredUserKey: SymmetricCryptoKey,
        preservedUserKey: SymmetricCryptoKey,
    ): Boolean = record.binding.vaultId == freshBinding.vaultId &&
        record.binding.userId == freshBinding.userId &&
        record.binding.canonicalEmail == freshBinding.canonicalEmail &&
        record.binding.environmentFingerprint == freshBinding.environmentFingerprint &&
        record.binding.transportRevision == freshBinding.transportRevision &&
        record.binding.transportSecretRef == freshBinding.transportSecretRef &&
        MessageDigest.isEqual(recoveredUserKey.encKey, preservedUserKey.encKey) &&
        MessageDigest.isEqual(recoveredUserKey.macKey, preservedUserKey.macKey)

    private fun validateKdf(kdf: BitwardenAccountMutationKdf, newChoice: Boolean) {
        when (kdf.type) {
            0 -> require(kdf.memoryMiB == null && kdf.parallelism == null &&
                kdf.iterations in (if (newChoice) 600_000 else 1)..(if (newChoice) limits.maxPbkdf2Iterations else Int.MAX_VALUE))
            1 -> require(kdf.iterations in (if (newChoice) 2 else 1)..(if (newChoice) limits.maxArgonIterations else Int.MAX_VALUE) &&
                kdf.memoryMiB != null && kdf.memoryMiB in (if (newChoice) 16 else 1)..(if (newChoice) limits.maxArgonMemoryMiB else Int.MAX_VALUE / 1024) &&
                kdf.parallelism != null && kdf.parallelism in 1..(if (newChoice) limits.maxArgonParallelism else 16))
            else -> error("Account KDF is unsupported")
        }
    }

    private fun derive(password: String, salt: String, kdf: BitwardenAccountMutationKdf): ByteArray = when (kdf.type) {
        0 -> BitwardenCrypto.deriveMasterKeyPbkdf2(password, salt, kdf.iterations)
        1 -> BitwardenCrypto.deriveMasterKeyArgon2(password, salt, kdf.iterations, kdf.memoryMiB!!, kdf.parallelism!!)
        else -> error("Account KDF is unsupported")
    }

    private fun requireLegacyWrapper(value: String) {
        require(value.length <= 4096 && value.startsWith("2.") && value.count { it == '|' } == 2) {
            "Account key wrapper is unsupported"
        }
    }
}

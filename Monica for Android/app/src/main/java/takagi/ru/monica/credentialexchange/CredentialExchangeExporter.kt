package takagi.ru.monica.credentialexchange

import takagi.ru.monica.repository.readMdbxSshKeyData
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import takagi.ru.monica.R
import takagi.ru.monica.bitwarden.repository.BitwardenRepository
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.linkedAppBindings
import takagi.ru.monica.passkey.PasskeyCredentialIdCodec
import takagi.ru.monica.passkey.PasskeyPrivateKeyStore
import takagi.ru.monica.passkey.PasskeyPrivateKeySupport
import takagi.ru.monica.repository.KeePassWorkspaceRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassKdbxService
import java.util.Base64
import java.util.Date
import org.json.JSONObject
import takagi.ru.monica.utils.PasswordWebsiteCodec

class CredentialExchangeExporter(private val context: Context) {
    class Prepared(val json: String, val passwordCount: Int, val passkeyCount: Int, val skippedPasskeys: Int,
        val skippedSharedItems: Int = 0, val skippedOtps: Int = 0) {
        override fun toString() = "Prepared credential exchange (<redacted>)"
    }

    /** Must be called only after a fresh user authentication and explicit export confirmation. */
    suspend fun prepare(destination: ImportDestination, requestedTypes: Set<String>): Prepared = withContext(Dispatchers.IO) {
        val db = PasswordDatabase.getDatabase(context)
        val security = SecurityManager(context)
        var passwords: List<PasswordEntry>
        var passkeys: List<PasskeyEntry>
        val appScopes = mutableMapOf<Long, JsonArray>()
        var skippedSharedItems = 0
        if (destination.keepassId != null) {
            val service = KeePassKdbxService(context, db.localKeePassDatabaseDao(), security)
            val repository = KeePassWorkspaceRepository(service)
            val snapshot = repository.loadWorkspace(destination.databaseId).getOrThrow()
            // A passkey's UserName/URL are metadata, not an additional empty password credential.
            passwords = snapshot.passwords.filter {
                !it.isInRecycleBin && (!it.hasPasskeyFields || it.password.isNotEmpty())
            }.mapIndexed { index, entry ->
                val id = -(index + 1L)
                entry.customFields.firstOrNull { it.title == CxfAndroidAppScope.FIELD_NAME }?.value?.let {
                    appScopes[id] = CxfAndroidAppScope.restore(it)
                }
                PasswordEntry(id = id, title = entry.title,
                    username = entry.username, password = entry.password, website = entry.url, notes = entry.notes,
                    keepassDatabaseId = destination.databaseId, loginType = entry.loginType,
                    authenticatorKey = entry.authenticatorKey,
                    createdAt = Date(entry.createdAtMillis ?: 0L), updatedAt = Date(entry.updatedAtMillis ?: 0L),
                    appPackageName = entry.appPackageName, appName = entry.appName)
            }
            passkeys = repository.readPasskeyEntries(destination.databaseId).getOrThrow()
        } else if (destination.mdbxId != null) {
            // Read the actual file; its Room projection may not have been loaded by a list screen.
            val entries = takagi.ru.monica.repository.MdbxRepositoryFactory.create(context, db, security)
                .readStoredEntries(destination.databaseId).filterNot { it.deleted }
            passwords = entries.filter { it.entryType == "login" }.mapIndexed { index, stored ->
                val data = JSONObject(stored.payloadJson)
                val id = -(index + 1L)
                val fields = data.optJSONArray("custom_fields")
                (0 until (fields?.length() ?: 0)).mapNotNull { fields?.optJSONObject(it) }
                    .firstOrNull { it.optString("title") == CxfAndroidAppScope.FIELD_NAME }?.optString("value")?.let {
                        appScopes[id] = CxfAndroidAppScope.restore(it)
                    }
                PasswordEntry(id = id, title = stored.title, username = data.optString("username"),
                    password = data.getString("password_plain"), website = data.optString("website"),
                    authenticatorKey = data.optString("authenticator_key"),
                    notes = data.optString("notes"), loginType = data.optString("login_type", "PASSWORD"),
                        sshKeyData = data.readMdbxSshKeyData(),
                    appPackageName = data.optString("app_package_name"), appName = data.optString("app_name"))
            }
            passkeys = entries.filter { it.entryType == "passkey" }.map { stored ->
                val data = JSONObject(stored.payloadJson)
                PasskeyEntry(credentialId = data.getString("credential_id"), rpId = data.getString("rp_id"),
                    rpName = data.optString("rp_name", stored.title), userId = data.getString("user_id"),
                    userName = data.optString("user_name"), userDisplayName = data.optString("user_display_name"),
                    publicKeyAlgorithm = data.getInt("public_key_algorithm"), publicKey = data.optString("public_key"),
                    privateKeyAlias = data.getString("private_key_alias"), signCount = data.getLong("sign_count"),
                        backupEligible = takagi.ru.monica.passkey.PasskeyBackupFlags.readBoolean(data, "backup_eligible"),
                        backupState = takagi.ru.monica.passkey.PasskeyBackupFlags.readBoolean(data, "backup_state"))
            }
        } else {
            destination.bitwardenId?.let { id ->
                check(db.bitwardenVaultDao().getVaultById(id)?.isConnected == true &&
                    BitwardenRepository.getInstance(context).isVaultUnlocked(id)) {
                    context.getString(R.string.exchange_destination_unlock)
                }
            }
            passwords = db.passwordEntryDao().getAllPasswordEntriesSync().filter { destination.contains(it) && !it.isDeleted }
            db.customFieldDao().getFieldsByEntryIds(passwords.map { it.id })
                .filter { it.title == CxfAndroidAppScope.FIELD_NAME }
                .forEach { appScopes[it.entryId] = CxfAndroidAppScope.restore(it.value) }
            passkeys = db.passkeyDao().getAllPasskeysSync().filter(destination::contains)
            destination.bitwardenId?.let { id ->
                // CXF Account.items MUST exclude items shared with this account. Room does not
                // retain organization ownership, so confirm it with the server instead of guessing.
                val existingIds = (passwords.mapNotNull { it.bitwardenCipherId } + passkeys.mapNotNull { it.bitwardenCipherId }).toSet()
                if (existingIds.isNotEmpty()) {
                    val vault = checkNotNull(db.bitwardenVaultDao().getVaultById(id))
                    val session = checkNotNull(BitwardenRepository.getInstance(context).getAttachmentBitwardenContext(vault, existingIds.first()))
                    val ownedIds = try {
                        val response = session.vaultApi.sync("Bearer ${session.accessToken}")
                        check(response.isSuccessful)
                        checkNotNull(response.body()).ciphers.filter { it.organizationId == null && it.deletedDate == null }.map { it.id }.toSet()
                    } finally { session.wrappingKey.encKey.fill(0); session.wrappingKey.macKey.fill(0) }
                    val originalCount = passwords.size + passkeys.size
                    passwords = passwords.filter { it.bitwardenCipherId == null || it.bitwardenCipherId in ownedIds }
                    passkeys = passkeys.filter { it.bitwardenCipherId == null || it.bitwardenCipherId in ownedIds }
                    skippedSharedItems = originalCount - passwords.size - passkeys.size
                }
            }
        }
        val items = mutableListOf<CxfCredentialCodec.Item>()
        var skipped = 0
        var passwordCount = 0
        var passkeyCount = 0
        var skippedOtps = 0
        if ("basic-auth" in requestedTypes) passwords.filter { it.loginType == "PASSWORD" }.forEach { entry ->
            val nativePlaintext = destination.keepassId != null || destination.mdbxId != null
            fun plaintext(value: String) = if (nativePlaintext) value else security.decryptDataIfMonicaCiphertext(value)
            val username = plaintext(entry.username)
            val otpPayload = plaintext(entry.authenticatorKey)
            val otp = if ("totp" in requestedTypes) CxfTotpExport.fromPayload(otpPayload, entry.title, username) else null
            if (otpPayload.isNotBlank() && otp == null) skippedOtps++
            // One basic-auth per Item: receivers often store only one login per item.
            // Keep its OTP beside that exact password, even within a multi-account project.
            items += CxfCredentialCodec.Item(
                id = "password:${entry.id}", title = entry.title,
                urls = PasswordWebsiteCodec.parse(entry.website).filter { it.isNotBlank() },
                logins = listOf(CxfCredentialCodec.Login(username, plaintext(entry.password))),
                totp = otp,
                notes = entry.notes, createdAt = entry.createdAt.time, modifiedAt = entry.updatedAt.time, favorite = entry.isFavorite,
                androidApps = appScopes[entry.id] ?: buildJsonArray {
                    entry.linkedAppBindings().forEach { app -> addJsonObject {
                        put("bundleId", app.packageName); put("name", app.appName)
                    } }
                },
            )
            passwordCount++
        }
        if ("passkey" in requestedTypes) passkeys.forEach { entry ->
            // CXF 3.3.12: counters cannot be reset by an exporter or carried in an invented field.
            if (entry.signCount != 0L || entry.backupEligible == false) { skipped++; return@forEach }
            val raw = PasskeyPrivateKeyStore.resolve(context, entry.privateKeyAlias)
            val pkcs8 = PasskeyPrivateKeySupport.normalizeForBitwardenUpload(raw)
                ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            val material = pkcs8?.let(CxfPasskeyMaterial::decode)
            val id = PasskeyCredentialIdCodec.toWebAuthnId(entry.credentialId)
            val handle = runCatching { Base64.getUrlDecoder().decode(entry.userId) }.getOrNull()
                ?: runCatching { Base64.getDecoder().decode(entry.userId) }.getOrNull()
            if (pkcs8 == null || material == null || material.algorithm != entry.publicKeyAlgorithm || id.isNullOrBlank() ||
                runCatching { Base64.getUrlDecoder().decode(id).size in 1..1023 }.getOrDefault(false).not() ||
                handle == null || handle.size !in 1..64 || entry.rpId.isBlank()) {
                pkcs8?.fill(0)
                skipped++
                return@forEach
            }
            try {
                items += CxfCredentialCodec.Item(
                    id = "passkey:${entry.id}", title = entry.rpName.ifBlank { entry.rpId },
                    passkeys = listOf(CxfCredentialCodec.Passkey(
                        credentialId = id, rpId = entry.rpId, username = entry.userName,
                        userDisplayName = entry.userDisplayName, userHandle = CxfCredentialCodec.base64Url(handle),
                        key = CxfCredentialCodec.base64Url(pkcs8), algorithm = material.algorithm, publicKey = material.cosePublicKey,
                    )), notes = entry.notes, createdAt = entry.createdAt,
                )
                passkeyCount++
            } finally { pkcs8.fill(0) }
        }
        val json = CxfCredentialCodec.encode(items, "Monica", requestedTypes)
        Prepared(json, passwordCount, passkeyCount, skipped, skippedSharedItems, skippedOtps)
    }
}

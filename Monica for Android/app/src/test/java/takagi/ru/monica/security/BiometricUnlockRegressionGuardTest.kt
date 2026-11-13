package takagi.ru.monica.security

import takagi.ru.monica.testing.readSourceText

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricUnlockRegressionGuardTest {

    @Test
    fun mainPasswordLoginUsesFullVaultUnlockPath() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readSourceText()
        val authenticateBody = source.substringAfter("fun authenticate(password: String): Boolean {")
            .substringBefore("fun restoreAuthenticatedUiState()")

        assertTrue(
            "Main app password login must repair MDK/KeyStore state by using unlockVaultWithPassword.",
            authenticateBody.contains("securityManager.unlockVaultWithPassword(password)")
        )
        assertFalse(
            "Do not regress to verifyMasterPassword here; it can allow login while biometric key repair failed.",
            authenticateBody.contains("securityManager.verifyMasterPassword(password)")
        )
    }

    @Test
    fun invalidatedAndUnrecoverableKeysRetainOriginalWrappers() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/security/SecurityManager.kt").readSourceText()
        val persistBody = source.substringAfter("private fun persistKeystoreWrappedMdk(mdk: ByteArray): Boolean {")
            .substringBefore("private fun persistCompatKeystoreWrappedMdk")
        val ensureBody = source.substringAfter("private fun ensureMdkInitializedWithPassword(")
            .substringBefore("private fun publishPasswordMdk(")
        val publishBody = source.substringAfter("private fun publishPasswordMdk(")
            .substringBefore("private fun ensureMdkKeystoreWrapper()")
        val compatBody = source.substringAfter("private fun persistCompatKeystoreWrappedMdk(")
            .substringBefore("private fun generateRandom(")
        val commitBody = source.substringAfter("private fun commitLocalPassword(")
            .substringBefore("private fun ensureMdkInitializedWithPassword(")
        assertTrue(persistBody.contains("KeyPermanentlyInvalidatedException"))
        assertTrue(persistBody.contains("UnrecoverableKeyException"))
        assertFalse("A failed key read is not authority to delete the original alias", persistBody.contains("deleteSecureKeyAlias("))
        assertFalse("An existing unreadable wrapper must remain available for recovery", ensureBody.contains("hasKeystoreBlob = false"))
        assertTrue("Normal password auth may only add a missing compatibility wrapper using an existing device key",
            ensureBody.contains("if (!sharedPreferences.contains(MDK_KEYSTORE_BLOB_KEY)) persistCompatKeystoreWrappedMdk(mdk)") &&
                compatBody.contains("allowInitialKeyCreation: Boolean = false") &&
                compatBody.contains("else getExistingDataKey(compat = true)"))
        assertTrue("New device keys remain limited to initial enrollment without a password or ready MDK",
            commitBody.replace(Regex("\\s+"), " ").contains(
                "allowInitialKeyCreation = original[MASTER_PASSWORD_HASH_KEY] == null && original[MDK_READY_KEY] != true"))
        assertTrue("Only current authenticated credentials may publish MDK state and clear its cooldown",
            ensureBody.contains("publishPasswordMdk(mdk, authenticatedCredentials, generation, epoch, attempt)") &&
                publishBody.contains("check(isDataReadCurrent(generation) && credentialsMatch(credentials))") &&
                publishBody.contains("mdkAuthUnavailableUntilMillis = 0L"))
    }

    @Test
    fun biometricUnlockClearsPreviousMdkCooldownBeforeReadingKeyMaterial() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SecurityManager.kt"
        ).readSourceText()
        val biometricBody = source.substringAfter("fun unlockVaultWithBiometric(): Boolean {")
            .substringBefore("fun isVaultRuntimeUnlocked()")

        assertTrue(
            "A previous failed MDK read must not block a fresh successful biometric auth attempt.",
            biometricBody.contains("mdkAuthUnavailableUntilMillis = 0L")
        )
    }

    @Test
    fun emptyRuntimeMdkCacheCannotMaskReadableKeystoreWrapper() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SecurityManager.kt"
        ).readSourceText()
        val getMdkBody = source.substringAfter("private fun getMdkForCrypto(): ByteArray? {")
            .substringBefore("private val DATA_PREFIX_MDK")
        val getOrCreateBody = source.substringAfter("private fun getOrCreateMdkBytes(): ByteArray {")
            .substringBefore("private fun getMdkForCrypto()")
        val ensureBody = source.substringAfter("private fun ensureMdkInitializedWithPassword(")
            .substringBefore("private fun publishPasswordMdk(")
        val passwordReadBody = source.substringAfter("private fun readPasswordAuthenticatedMdk(")
            .substringBefore("private fun credentialUpdates(")
        val credentialSource = projectFile(
            "app/src/main/java/takagi/ru/monica/security/LocalPasswordCredentials.kt"
        ).readSourceText()
        val unwrapBody = credentialSource.substringAfter("fun unwrap(").substringBefore("fun create(")
        val cipherUnwrapBody = credentialSource.substringAfter("private fun unwrapWithKey(")

        assertTrue(
            "An empty runtime MDK cache must be ignored so a freshly persisted wrapper can still be read.",
            getMdkBody.contains("cached.isNotEmpty()")
        )
        assertTrue(
            "Clearing an empty runtime MDK cache prevents repeated false locked states.",
            getMdkBody.contains("processCachedMdk = null")
        )
        assertTrue(
            "MDK recovery should also ignore empty runtime cache before falling back to the keystore wrapper.",
            getOrCreateBody.contains("cached.isNotEmpty()")
        )
        assertTrue("An existing password wrapper must be authenticated before migration, without a cache/error fallback",
            ensureBody.contains("val mdk = readPasswordAuthenticatedMdk(password, original)") &&
                passwordReadBody.contains("check(hasBlob == hasSalt)") &&
                passwordReadBody.contains("if (hasBlob) return LocalPasswordCredentials.unwrap(password, original)") &&
                !passwordReadBody.contains("catch (") && !passwordReadBody.contains("runCatching") &&
                unwrapBody.contains("unwrapWithKey(key, values[BLOB] as String, profile)") &&
                !unwrapBody.contains("catch ("))
        assertTrue("Password MDK unwrap must validate its authenticated envelope, profile AAD and 32-byte key",
            cipherUnwrapBody.contains("require(blob.length == 80)") &&
                cipherUnwrapBody.contains("require(bytes.size == 60)") &&
                cipherUnwrapBody.contains("PasswordKdf.mdkAad(profile)?.let(cipher::updateAAD)") &&
                cipherUnwrapBody.contains("cipher.doFinal(bytes, 12, 48).also { require(it.size == 32) }"))
        assertFalse("Authentication must not rewrite an unreadable existing password wrapper",
            ensureBody.contains("shouldRewritePasswordBlob = true"))
    }

    @Test
    fun compatDataEncryptionDoesNotUsePredictableMasterKeyString() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SecurityManager.kt"
        ).readSourceText()
        val encryptDataBody = source.substringAfter("fun encryptData(data: String): String {")
            .substringBefore("/**\n     * Compatibility helper")
        val legacyCompatBody = source.substringAfter("fun encryptDataLegacyCompat(data: String): String {")
            .substringBefore("private fun encryptDataV2")
        val compatEncryptBody = source.substringAfter("private fun encryptDataCompat(data: String): String {")
            .substringBefore("fun decryptData(")
        val legacyReadBody = source.substringAfter("private fun decryptLegacyV1OrPlainText(encryptedData: String): String {")
            .substringBefore("/**\n     * Generate secure random password")

        assertTrue(
            "Compat data encryption must have an explicit prefix so callers can treat it as ciphertext.",
            source.contains("private val DATA_PREFIX_COMPAT = \"C2|\"") &&
                compatEncryptBody.contains("return DATA_PREFIX_COMPAT +")
        )
        assertTrue(
            "MDK fallback and legacy-compat callers must write the compat Keystore format, not historical V1.",
            encryptDataBody.contains("return encryptDataCompat(data)") &&
                legacyCompatBody.contains("return encryptDataCompat(data)")
        )
        assertTrue(
            "Compat encryption must use the real Android Keystore compat key.",
            compatEncryptBody.contains("getOrGenerateCompatSecureKey()")
        )
        assertFalse(
            "New encryption paths must never derive keys from MasterKey.toString().",
            encryptDataBody.contains("masterKey.toString()") ||
                legacyCompatBody.contains("masterKey.toString()") ||
                compatEncryptBody.contains("masterKey.toString()")
        )
        assertTrue(
            "Historical unprefixed V1 reading can keep MasterKey.toString only for old-data compatibility.",
            legacyReadBody.contains("masterKey.toString()") &&
                legacyReadBody.contains("Keep it read-only so old local data can be opened and migrated")
        )
    }

    @Test
    fun compatDataPrefixIsRecognizedAtPasswordBoundaries() {
        val autofillSecretResolver = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillSecretResolver.kt"
        ).readSourceText()
        val accountFillPolicy = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AccountFillPolicy.kt"
        ).readSourceText()
        val dataExportImportViewModel = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/DataExportImportViewModel.kt"
        ).readSourceText()
        val bitwardenSyncService = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/BitwardenSyncService.kt"
        ).readSourceText()
        val webDavHelper = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readSourceText()

        assertTrue(
            "C2 compat ciphertext must be recognized anywhere password payloads are classified.",
            autofillSecretResolver.contains("DATA_PREFIX_COMPAT = \"C2|\"") &&
                accountFillPolicy.contains("value.startsWith(\"C2|\")") &&
                dataExportImportViewModel.contains("trimmed.startsWith(\"C2|\")") &&
                bitwardenSyncService.contains("candidate.startsWith(\"C2|\")") &&
                webDavHelper.contains("trimmed.startsWith(\"C2|\")")
        )
        assertTrue(
            "Autofill account identifiers should try decrypting all known encrypted payload formats, including C2.",
            accountFillPolicy.contains("if (looksEncryptedPayload(entry.username))")
        )
    }

    @Test
    fun monicaCiphertextDetectionRequiresExplicitPrefix() {
        val source = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SecurityManager.kt"
        ).readSourceText()
        val detectorBody = source.substringAfter("fun looksLikeMonicaCiphertext(value: String): Boolean {")
            .substringBefore("fun decryptDataIfMonicaCiphertext")
        val conditionalDecryptBody = source.substringAfter("fun decryptDataIfMonicaCiphertext(value: String): String {")
            .substringBefore("private fun encryptDataV2")

        assertTrue(
            "Field-level migration must only treat explicitly-prefixed Monica values as ciphertext, so legacy JSON/Base32/otpauth data stays readable.",
            detectorBody.contains("trimmed.startsWith(DATA_PREFIX_MDK)") &&
                detectorBody.contains("trimmed.startsWith(DATA_PREFIX_V2)") &&
                detectorBody.contains("trimmed.startsWith(DATA_PREFIX_COMPAT)")
        )
        assertFalse(
            "Do not classify random Base64-looking legacy values as encrypted field data.",
            detectorBody.contains("Base64.decode") ||
                detectorBody.contains("contains(\"==\")")
        )
        assertTrue(
            "Conditional field decrypt must leave legacy plaintext unchanged.",
            conditionalDecryptBody.contains("looksLikeMonicaCiphertext(value)") &&
                conditionalDecryptBody.contains("decryptData(value)") &&
            conditionalDecryptBody.contains("else {\n            value\n        }")
        )
    }

    @Test
    fun totpFieldEncryptionCompatibilityReadsThroughSingleHelper() {
        val resolverSource = projectFile(
            "app/src/main/java/takagi/ru/monica/util/TotpDataResolver.kt"
        ).readSourceText()
        val viewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readSourceText()

        assertTrue(
            "TOTP resolver must support optional field decrypt before parsing so old plaintext and new ciphertext share one path.",
            resolverSource.contains("decryptIfNeeded: ((String) -> String)? = null") &&
                resolverSource.contains("val resolvedItemData = decryptIfNeeded") &&
                resolverSource.contains("json.decodeFromString<TotpData>(resolvedItemData)") &&
                resolverSource.contains("rawKey = resolvedItemData")
        )
        assertTrue(
            "TotpViewModel must decrypt only known Monica ciphertext and encrypt every new rewrite while preserving legacy plaintext reads.",
            viewModelSource.contains("decryptDataIfMonicaCiphertext(value)") &&
                viewModelSource.contains("looksLikeMonicaCiphertext(value)") &&
                viewModelSource.contains("encodeStoredSensitiveValueForRewrite") &&
                viewModelSource.contains("SensitiveValueEncryptor(it::encryptDataLegacyCompat)") &&
                viewModelSource.contains("requireNotNull(sensitiveValueEncryptor)") &&
                !viewModelSource.contains("encryptDataLegacyCompat(plainValue) ?: plainValue")
        )
        assertFalse(
            "TotpViewModel must not parse item.itemData directly; direct parsing would hide encrypted legacy TOTP data from old users after migration.",
            viewModelSource.contains("Json.decodeFromString<TotpData>(item.itemData)") ||
                viewModelSource.contains("Json.decodeFromString<TotpData>(target.itemData)") ||
            viewModelSource.contains("Json.decodeFromString<TotpData>(candidate.itemData)")
        )
    }

    @Test
    fun sharedDatabaseAndSyncBoundariesDoNotExportLocalOnlyTotpCiphertext() {
        val mdbxStoreSource = projectFile(
            "app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt"
        ).readSourceText()
        val bitwardenSyncSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/BitwardenSyncService.kt"
        ).readSourceText()
        val webDavHelperSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readSourceText()
        val keePassKdbxSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/KeePassKdbxService.kt"
        ).readSourceText()
        val secureItemCodecSource = projectFile(
            "app/src/main/java/takagi/ru/monica/keepass/KeePassSecureItemFieldCodec.kt"
        ).readSourceText()

        assertTrue(
            "MDBX write boundary must preserve current plaintext data, but decrypt explicitly-prefixed Monica ciphertext before writing shared payloads.",
            mdbxStoreSource.contains("\"authenticator_key\",") &&
                mdbxStoreSource.contains("fieldName = \"authenticator_key\"") &&
                mdbxStoreSource.contains("\"item_data\",") &&
                mdbxStoreSource.contains("fieldName = \"item_data\"") &&
                mdbxStoreSource.contains("!securityManager.looksLikeMonicaCiphertext(value)") &&
                mdbxStoreSource.contains("securityManager.decryptData(value)") &&
                mdbxStoreSource.contains("Cannot write encrypted")
        )
        assertTrue(
            "Bitwarden upload and pending reconciliation must compare/upload the real TOTP payload, not a local Room ciphertext wrapper.",
            bitwardenSyncSource.contains("private fun resolvePlainStoredSensitiveValueForBitwardenUpload(") &&
                bitwardenSyncSource.contains("securityManager.looksLikeMonicaCiphertext(storedValue)") &&
                bitwardenSyncSource.contains("securityManager.decryptData(storedValue)") &&
                bitwardenSyncSource.contains("val plainAuthenticatorKey = resolvePlainStoredSensitiveValueForBitwardenUpload(") &&
                bitwardenSyncSource.contains("rawKey = plainAuthenticatorKey") &&
                bitwardenSyncSource.contains("val localAuthenticatorKey = resolvePlainStoredSensitiveValueForBitwardenUpload(") &&
                bitwardenSyncSource.contains("localAuthenticatorKey == remoteTotp")
        )
        assertTrue(
            "ZIP/WebDAV/OneDrive backup must export portable TOTP values instead of device-local Room ciphertext.",
            webDavHelperSource.contains("portablePasswordForBackup(") &&
                webDavHelperSource.contains("portableSecureItemForBackup(") &&
                webDavHelperSource.contains("portableSensitiveBackupValue(") &&
                webDavHelperSource.contains("PortableSecretExportPolicy.resolve(") &&
                webDavHelperSource.contains("decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext") &&
                webDavHelperSource.contains(".map { portablePasswordForBackup(it, securityManager) }") &&
                webDavHelperSource.contains(".map { portableSecureItemForBackup(it, securityManager) }")
        )
        assertTrue(
            "KeePass service must own portable payload decryption and reject unreadable ciphertext.",
            keePassKdbxSource.contains("private fun portableSecureItemDataForKeePass(") &&
                keePassKdbxSource.contains("securityManager.looksLikeMonicaCiphertext(itemData)") &&
                keePassKdbxSource.contains("securityManager.decryptData(itemData)") &&
                keePassKdbxSource.contains("Cannot write encrypted secure item data to KeePass") &&
                keePassKdbxSource.contains("private val secureItemFieldCodec = KeePassSecureItemFieldCodec(") &&
                keePassKdbxSource.contains("portableSecureItemDataForKeePass = ::portableSecureItemDataForKeePass")
        )
        assertTrue(
            "KeePass codec must consume the service-prepared portable payload and protect it in KDBX fields.",
            secureItemCodecSource.contains("val portableItemData = portableSecureItemDataForKeePass(item)") &&
                secureItemCodecSource.contains("FIELD_MONICA_ITEM_DATA to EntryValue.Encrypted(EncryptedValue.fromString(portableItemData))") &&
                secureItemCodecSource.contains("val portable = portableSecureItemDataForKeePass(item)") &&
                secureItemCodecSource.contains("FIELD_MONICA_TOTP_DATA to EntryValue.Encrypted(EncryptedValue.fromString(portable))")
        )
    }

    @Test
    fun totpNewWritesStoreLocalCiphertextWithoutBreakingPortableBoundaries() {
        val totpViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readSourceText()
        val passwordViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readSourceText()
        val bitwardenSyncSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/BitwardenSyncService.kt"
        ).readSourceText()
        val cipherSyncSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/CipherSyncProcessor.kt"
        ).readSourceText()

        assertTrue(
            "Standalone TOTP saves must encrypt new Room itemData writes, while keeping plaintext JSON only as the portable business value.",
            totpViewModelSource.contains("private fun encodeStoredSensitiveValueForNewWrite(plainValue: String): String") &&
                totpViewModelSource.contains("SensitiveValueEncryptor(it::encryptDataLegacyCompat)") &&
                totpViewModelSource.contains("requireNotNull(sensitiveValueEncryptor)") &&
                !totpViewModelSource.contains("encryptDataLegacyCompat(plainValue) ?: plainValue") &&
                totpViewModelSource.contains("val storedItemData = encodeStoredSensitiveValueForNewWrite(itemDataJson)") &&
                totpViewModelSource.contains("itemData = storedItemData")
        )
        assertTrue(
            "Password-bound TOTP saves must encrypt PasswordEntry.authenticatorKey before storing it locally.",
            passwordViewModelSource.contains("private fun encodeAuthenticatorKeyForStorage(value: String): String") &&
                passwordViewModelSource.contains("authenticatorKey = encodeAuthenticatorKeyForStorage(entryToUpdate.authenticatorKey)") &&
                passwordViewModelSource.contains("authenticatorKey = encodeAuthenticatorKeyForStorage(normalizedBoundEntry.authenticatorKey)") &&
                passwordViewModelSource.contains("repository.updateAuthenticatorKey(id, encodeAuthenticatorKeyForStorage(authenticatorKey))")
        )
        assertTrue(
            "Bitwarden sync imports must store TOTP locally encrypted, while upload paths still decrypt before sending.",
            bitwardenSyncSource.contains("private fun encodeBitwardenTotpForLocalStorage(totpPayload: String): String") &&
                bitwardenSyncSource.contains("authenticatorKey = encodeBitwardenTotpForLocalStorage(totp)") &&
                bitwardenSyncSource.contains("val storedTotp = remoteTotp?.let(::encodeBitwardenTotpForLocalStorage) ?: entry.authenticatorKey") &&
                cipherSyncSource.contains("private fun encodeBitwardenTotpForLocalStorage(totpPayload: String): String") &&
                cipherSyncSource.contains("val storedTotp = encodeBitwardenTotpForLocalStorage(totp)") &&
                cipherSyncSource.contains("authenticatorKey = storedTotp") &&
                cipherSyncSource.contains("securityManager.encryptDataLegacyCompat(json.encodeToString(totpData))") &&
                cipherSyncSource.contains("decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext")
        )
    }

    @Test
    fun bankCardAndDocumentItemDataUseEncryptedLocalStorageWithPortableBoundaries() {
        val codecSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/model/CardWalletDataCodec.kt"
        ).readSourceText()
        val bankCardViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/BankCardViewModel.kt"
        ).readSourceText()
        val documentViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/DocumentViewModel.kt"
        ).readSourceText()
        val cipherSyncSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/CipherSyncProcessor.kt"
        ).readSourceText()
        val cipherUploadSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/service/CipherUploadProcessor.kt"
        ).readSourceText()
        val webDavHelperSource = projectFile(
            "app/src/main/java/takagi/ru/monica/utils/WebDavHelper.kt"
        ).readSourceText()
        val autofillStructuredSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillStructuredDataSupport.kt"
        ).readSourceText()
        val mdbxViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt"
        ).readSourceText()

        assertTrue(
            "Card wallet codec must support conditional field decrypt before parsing old plaintext or new ciphertext.",
            codecSource.contains("decryptIfNeeded: ((String) -> String)? = null") &&
                codecSource.contains("resolveStoredData(raw, decryptIfNeeded)") &&
                codecSource.contains("parseLegacyBankCardData(resolvedRaw)") &&
                codecSource.contains("parseLegacyDocumentData(resolvedRaw)")
        )
        assertTrue(
            "Bank card and document ViewModels must encrypt new local itemData writes and decrypt when reading for UI/edit.",
            bankCardViewModelSource.contains("private fun encodeCardDataForLocalStorage(cardData: BankCardData): String") &&
                bankCardViewModelSource.contains("SensitiveValueEncryptor(it::encryptDataLegacyCompat)") &&
                bankCardViewModelSource.contains("requireNotNull(sensitiveValueEncryptor)") &&
                !bankCardViewModelSource.contains("encryptDataLegacyCompat(plainValue) ?: plainValue") &&
                bankCardViewModelSource.contains("decryptIfNeeded = ::decryptStoredSensitiveValue") &&
                documentViewModelSource.contains("private fun encodeDocumentDataForLocalStorage(documentData: DocumentData): String") &&
                documentViewModelSource.contains("SensitiveValueEncryptor(it::encryptDataLegacyCompat)") &&
                documentViewModelSource.contains("requireNotNull(sensitiveValueEncryptor)") &&
                !documentViewModelSource.contains("encryptDataLegacyCompat(plainValue) ?: plainValue") &&
                documentViewModelSource.contains("decryptIfNeeded = ::decryptStoredSensitiveValue")
        )
        assertTrue(
            "Bitwarden download must store card/document itemData locally encrypted, while upload decrypts before building remote payloads.",
            cipherSyncSource.contains("private fun encodeSecureItemDataForLocalStorage(itemData: String): String") &&
                cipherSyncSource.contains("CardWalletDataCodec.encodeBankCardData(cardData)") &&
                cipherSyncSource.contains("CardWalletDataCodec.encodeDocumentData(docData)") &&
                cipherUploadSource.contains("decryptIfNeeded = securityManager::decryptDataIfMonicaCiphertext")
        )
        assertTrue(
            "Backup and autofill boundaries must parse/export portable card/document business data, not local Room ciphertext.",
            webDavHelperSource.contains("item.itemType != ItemType.BANK_CARD") &&
                webDavHelperSource.contains("item.itemType != ItemType.DOCUMENT") &&
                autofillStructuredSource.contains("decryptIfNeeded: ((String) -> String)? = null") &&
                autofillStructuredSource.contains("CardWalletDataCodec.parseBankCardData(") &&
                autofillStructuredSource.contains("CardWalletDataCodec.parseDocumentData(")
        )
        assertTrue(
            "MDBX import must wrap portable TOTP/card/document payloads in local ciphertext when caching them in Room.",
            mdbxViewModelSource.contains("private fun encodeMdbxSensitiveValueForLocalStorage(") &&
                mdbxViewModelSource.contains("itemType != ItemType.BANK_CARD") &&
                mdbxViewModelSource.contains("itemType != ItemType.DOCUMENT") &&
                mdbxViewModelSource.contains("authenticatorKey = encodeMdbxSensitiveValueForLocalStorage(") &&
                mdbxViewModelSource.contains("itemData = storedItemData")
        )
    }

    @Test
    fun highTrafficTotpReadSurfacesUseEncryptedFieldCompatibleResolver() {
        val files = listOf(
            "app/src/main/java/takagi/ru/monica/ui/components/TotpCodeCard.kt",
            "app/src/main/java/takagi/ru/monica/service/NotificationValidatorService.kt",
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPickerActivityV2.kt",
            "app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt",
            "app/src/main/java/takagi/ru/monica/ui/components/QrCodeDialog.kt",
            "app/src/main/java/takagi/ru/monica/ui/AuthenticatorTabPane.kt",
            "app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt",
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchMoveMixedSupport.kt",
            "app/src/main/java/takagi/ru/monica/ui/screens/KeePassKdbxViewModel.kt",
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        )

        files.forEach { relativePath ->
            val source = projectFile(relativePath).readSourceText()
            assertFalse(
                "$relativePath must not directly decode SecureItem.itemData as plaintext TOTP; encrypted fields must pass through TotpDataResolver.",
                source.contains("Json.decodeFromString<TotpData>") ||
                    source.contains("decodeFromString<TotpData>") ||
                    source.contains("decodeFromString(TotpData.serializer(), item.itemData)")
            )
        }

        val combinedSource = files.joinToString("\n") { projectFile(it).readSourceText() }
        assertTrue(
            "High-traffic TOTP surfaces should explicitly route stored itemData through the compatibility resolver.",
            combinedSource.contains("TotpDataResolver.parseStoredItemData") &&
                combinedSource.contains("decryptDataIfMonicaCiphertext")
        )
    }

    @Test
    fun productionTotpItemDataParsingIsCentralizedInResolver() {
        val mainSourceDir = projectPath("app/src/main/java/takagi/ru/monica")
        val offenders = mainSourceDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("util/TotpDataResolver.kt") }
            // This compatibility decoder validates plaintext from an already decrypted KDBX
            // entry. Treating malformed native JSON as a raw OTP secret would hide the entry.
            .filterNot { it.invariantSeparatorsPath.endsWith("keepass/KeePassSecureItemPayload.kt") }
            .mapNotNull { file ->
                val source = file.readSourceText()
                val directlyParsesTotp =
                    source.contains("Json.decodeFromString<TotpData>") ||
                        source.contains("json.decodeFromString<TotpData>") ||
                        source.contains("decodeFromString<TotpData>") ||
                        source.contains("decodeFromString(TotpData.serializer(), item.itemData)")
                if (directlyParsesTotp) file.relativeTo(mainSourceDir).invariantSeparatorsPath else null
            }
            .toList()

        assertTrue(
            "Stored TOTP parsing must stay centralized so plaintext, legacy V1, MDK/V2 and C2 field formats remain compatible. Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun legacySensitiveFieldMigrationRunsAfterUnlockAndOnlyTouchesLocalRoomCache() {
        val migrationSource = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SensitiveFieldMigrationManager.kt"
        ).readSourceText()
        val mainActivitySource = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readSourceText()
        val totpViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readSourceText()
        val securityManagerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SecurityManager.kt"
        ).readSourceText()
        val passwordDaoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/PasswordEntryDao.kt"
        ).readSourceText()
        val secureItemDaoSource = projectFile(
            "app/src/main/java/takagi/ru/monica/data/SecureItemDao.kt"
        ).readSourceText()
        val policySource = projectFile(
            "app/src/main/java/takagi/ru/monica/security/LegacySensitiveValuePolicy.kt"
        ).readSourceText()

        assertTrue(
            "Every Room change must permit a fresh scan, including restored IDs below old completion markers.",
            migrationSource.contains("invalidationTracker.createFlow(\"secure_items\", \"password_entries\")") &&
                migrationSource.contains(".conflate()") && migrationSource.contains(".collect {") &&
                !migrationSource.contains(".collectLatest") && migrationSource.contains("runUnlockedSmallBatch()") &&
                migrationSource.contains("var after = 0L") && !migrationSource.contains("getSharedPreferences(") &&
                !migrationSource.contains("completedKey(") && !migrationSource.contains("lastIdKey(")
        )
        assertTrue(
            "Migration must be gated by usable unlocked key material and run from the authenticated main-app path.",
            migrationSource.contains("securityManager.isVaultRuntimeUnlocked()") &&
                migrationSource.contains("SessionManager.lockGeneration.value == app") &&
                migrationSource.contains("SecurityManager.dataReadGeneration.value == key") &&
                migrationSource.contains("SecurityManager.hasRuntimeUnlockCache()") &&
                mainActivitySource.contains("LaunchedEffect(isAuthenticated, mainSessionUnlocked, mainSessionLockGeneration") &&
                mainActivitySource.contains("if (isAuthenticated && mainSessionUnlocked)") &&
                mainActivitySource.contains("SensitiveFieldMigrationManager(") &&
                mainActivitySource.contains("repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED)") &&
                mainActivitySource.contains("sensitiveFieldMigrationManager.observeWhileUnlocked()")
        )
        assertTrue(
            "Migration must scan bounded pages on IO, yielding and checking cancellation between rows.",
            migrationSource.contains("private const val BATCH_SIZE = 64") &&
                migrationSource.contains("withContext(Dispatchers.IO)") && migrationSource.contains("yield()") &&
                migrationSource.contains("currentCoroutineContext().ensureActive()") &&
                migrationSource.contains("while (stamp.isCurrent())") && migrationSource.contains("after = row.id") &&
                !migrationSource.contains("MAX_BATCHES_PER_DOMAIN") &&
                passwordDaoSource.contains("getAuthenticatorKeyMigrationBatch") &&
                secureItemDaoSource.contains("getItemDataMigrationBatch")
        )
        assertTrue(
            "Only positively identified legacy plaintext may be wrapped, with exact roundtrip and full-row conflict checks.",
            migrationSource.contains("LegacySensitiveValuePolicy.acceptsItem(row.itemType, row.itemData)") &&
                migrationSource.contains("LegacySensitiveValuePolicy.acceptsAuthenticatorKey(row.authenticatorKey)") &&
                migrationSource.contains("val encrypted = encryptor.encrypt(raw)") &&
                migrationSource.contains("check(encrypted.startsWith(\"MDK|\") && securityManager.decryptData(encrypted) == raw)") &&
                migrationSource.contains("latest != observed || !local(latest)") &&
                migrationSource.contains("database.runInTransaction(Callable") &&
                policySource.contains("CardWalletDataCodec.parseBankCardData") &&
                policySource.contains("CardWalletDataCodec.parseDocumentData") &&
                !policySource.contains("decryptData(")
        )
        assertTrue(
            "Migration must use DAO column updates instead of repositories, so MDBX/Bitwarden/KeePass/autofill chains do not see a user edit.",
            migrationSource.contains("passwordEntryDao.compareAndSetAuthenticatorKey") &&
                migrationSource.contains("secureItemDao.compareAndSetItemData") &&
                passwordDaoSource.contains("UPDATE password_entries SET authenticatorKey = :replacement WHERE id = :id AND authenticatorKey = :expected") &&
                secureItemDaoSource.contains("UPDATE secure_items SET itemData = :replacement WHERE id = :id AND itemData = :expected")
        )
        assertFalse(
            "The migration manager must not depend on sync repositories or shared database writers.",
            migrationSource.contains("PasswordRepository") ||
                migrationSource.contains("SecureItemRepository") ||
                migrationSource.contains("MdbxVaultStore") ||
                migrationSource.contains("BitwardenSyncService") ||
                migrationSource.contains("KeePassKdbxService") ||
                migrationSource.contains("WebDavHelper")
        )
        assertTrue(
            "TOTP list merging and filtering must not decrypt migrated C2 fields on the main thread.",
            totpViewModelSource.contains("import kotlinx.coroutines.Dispatchers") &&
                totpViewModelSource.contains("}.flowOn(Dispatchers.Default)") &&
                totpViewModelSource.contains("val allTotpItems: StateFlow<List<SecureItem>> = allTotpItemsSource.stateIn")
        )
        assertTrue(
            "Opening the TOTP editor must use the compatibility parser; direct JSON decode loses migrated encrypted fields.",
            mainActivitySource.contains("initialData = totpViewModel.parseTotpDataForDisplay(item)") &&
                !mainActivitySource.contains("Json.decodeFromString(item.itemData)")
        )
        assertTrue(
            "C2 decrypt paths are hot after migration; the compat AndroidKeyStore key must be cached per SecurityManager instance.",
            securityManagerSource.contains("private var cachedCompatDataKey: SecretKey? = null") &&
                securityManagerSource.contains("private val compatDataKeyLock = Any()") &&
                securityManagerSource.contains("cachedCompatDataKey?.let { return it }") &&
                securityManagerSource.contains("cachedCompatDataKey = it")
        )
    }

    @Test
    fun pageSwitchHotPathsDoNotRunAuthOrBitwardenSyncWorkOnMainThread() {
        val mainActivitySource = projectFile(
            "app/src/main/java/takagi/ru/monica/MainActivity.kt"
        ).readSourceText()
        val monicaContentBody = mainActivitySource
            .substringAfter("fun MonicaContent(")
            .substringBefore("DisposableEffect(lifecycleOwner)")
        val bitwardenOrchestratorSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/sync/BitwardenSyncOrchestrator.kt"
        ).readSourceText()
        val bitwardenViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/viewmodel/BitwardenViewModel.kt"
        ).readSourceText()
        val bitwardenAutoSyncEffectSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/ui/BitwardenAutoSyncEffect.kt"
        ).readSourceText()
        val bitwardenPageAutoSyncSchedulerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/bitwarden/sync/BitwardenPageAutoSyncScheduler.kt"
        ).readSourceText()
        val cardWalletScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt"
        ).readSourceText()
        val walletListPreparationSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/cardwallet/WalletListPreparation.kt"
        ).readSourceText()
        val passkeyListScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/PasskeyListScreen.kt"
        ).readSourceText()
        val sendScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/SendScreen.kt"
        ).readSourceText()
        val passwordListContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/password/PasswordListContent.kt"
        ).readSourceText()
        val vaultV2PaneSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt"
        ).readSourceText()
        val totpListContentForSyncSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt"
        ).readSourceText()
        val securityManagerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/security/SecurityManager.kt"
        ).readSourceText()
        val totpCodeCardSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/TotpCodeCard.kt"
        ).readSourceText()
        val bankCardCardSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/BankCardCard.kt"
        ).readSourceText()
        val documentCardSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/DocumentCard.kt"
        ).readSourceText()
        val bankCardViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/BankCardViewModel.kt"
        ).readSourceText()
        val documentViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/DocumentViewModel.kt"
        ).readSourceText()
        val totpViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/TotpViewModel.kt"
        ).readSourceText()
        val passwordViewModelSource = projectFile(
            "app/src/main/java/takagi/ru/monica/viewmodel/PasswordViewModel.kt"
        ).readSourceText()
        val totpListContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/totp/TotpListContent.kt"
        ).readSourceText()
        val authenticatorTabPaneSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/AuthenticatorTabPane.kt"
        ).readSourceText()
        val passwordDetailScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/PasswordDetailScreen.kt"
        ).readSourceText()
        val addEditPasswordScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt"
        ).readSourceText().replace("\r\n", "\n")
        val addEditBankCardScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditBankCardScreen.kt"
        ).readSourceText()
        val addEditDocumentScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AddEditDocumentScreen.kt"
        ).readSourceText()
        val cardWalletDetailPaneContentSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/cardwallet/CardWalletDetailPaneContent.kt"
        ).readSourceText()
        val commonNameSuggestionSheetSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/CommonNameSuggestionSheet.kt"
        ).readSourceText()

        assertTrue(
            "Authenticated Compose recompositions must reuse an authenticated access state instead of re-running lock policy on every frame.",
            monicaContentBody.contains("val authenticatedAccessState = remember") &&
                monicaContentBody.contains("val mainAppAccessState = remember(") &&
                monicaContentBody.contains("if (isAuthenticated)") &&
                monicaContentBody.contains("authenticatedAccessState")
        )
        assertFalse(
            "Main app access state must not be resolved directly as a top-level composable value; that causes hot SecurityManager checks during page switches.",
            monicaContentBody.contains("val mainAppAccessState = MainAppLockPolicy.resolveAccessState(")
        )
        assertTrue(
            "Bitwarden sync execution must leave viewModelScope's Main dispatcher before network/database work starts.",
            bitwardenOrchestratorSource.contains("import kotlinx.coroutines.Dispatchers") &&
                bitwardenOrchestratorSource.contains("import kotlinx.coroutines.withContext") &&
                bitwardenOrchestratorSource.contains("withContext(Dispatchers.IO)") &&
                bitwardenOrchestratorSource.contains("executeSync(vaultId, silent)")
        )
        assertTrue(
            "Silent Bitwarden auto sync must not immediately warm every offline secret or refresh every UI snapshot during tab switching.",
            bitwardenViewModelSource.contains("private const val SILENT_SYNC_UI_REFRESH_DELAY_MS") &&
                bitwardenViewModelSource.contains("private const val SILENT_SYNC_CACHE_WARM_DELAY_MS") &&
                bitwardenViewModelSource.contains("val warmedCount = if (silent)") &&
                bitwardenViewModelSource.contains("scheduleSilentPostSyncRefresh(vault.id)") &&
                bitwardenViewModelSource.contains("scheduleSilentOfflineCacheWarm(vault.id)")
        )
        assertTrue(
            "Page-visible auto sync triggers should wait for a short stable visibility window so fast bottom-tab switches cancel them.",
                bitwardenPageAutoSyncSchedulerSource.contains("PAGE_ENTER_AUTO_SYNC_DELAY_MS = 1_200L") &&
                bitwardenAutoSyncEffectSource.contains("viewModel?.beginPageEnterAutoSync(selectedVaultId)") &&
                bitwardenAutoSyncEffectSource.contains("viewModel?.endPageAutoSync(sessionId)") &&
                cardWalletScreenSource.contains("BitwardenAutoSyncEffect(") &&
                cardWalletScreenSource.contains("SyncTaskRunner.request(") &&
                passkeyListScreenSource.contains("delay(1_200L)") &&
                passkeyListScreenSource.contains("viewModel.refreshKeePassPasskeys(trigger = \"PASSKEY_PAGE_ENTER\")") &&
                passkeyListScreenSource.contains("BitwardenAutoSyncEffect(") &&
                sendScreenSource.contains("delay(1_200L)") &&
                sendScreenSource.contains("BitwardenAutoSyncEffect(") &&
                passwordListContentSource.contains("BitwardenAutoSyncEffect(") &&
                vaultV2PaneSource.contains("BitwardenAutoSyncEffect(") &&
                totpListContentForSyncSource.contains("BitwardenAutoSyncEffect(")
        )
        assertTrue(
            "Multi-account passive Bitwarden auto sync must be serialized to avoid startup jank.",
            bitwardenOrchestratorSource.contains("passiveAutoSyncMutex") &&
                bitwardenViewModelSource.contains("fun beginPageEnterAutoSync(") &&
                bitwardenViewModelSource.contains("BitwardenPageAutoSyncScheduler(") &&
                bitwardenViewModelSource.contains("MULTI_VAULT_AUTO_SYNC_STAGGER_MS") &&
                !mainActivitySource.contains("requestStartupAutoSync(")
        )

        assertTrue(
            "Routine SecurityManager access checks must stay out of logcat hot paths; warning diagnostics remain separate.",
            securityManagerSource.contains("private fun logRoutineDebug(message: String)") &&
                securityManagerSource.contains("logRoutineDebug(\"canRestoreMainAppSession: session inactive -> locked\")") &&
                securityManagerSource.contains("logRoutineDebug(\"canRestoreMainAppSession: runtime MDK cache present\")") &&
                securityManagerSource.contains("logRoutineDebug(\"canAccessVaultNow: session inactive -> locked\")") &&
                securityManagerSource.contains("logRoutineDebug(\"canAccessVaultNow: runtime MDK cache present -> accessible\")")
        )
        assertFalse(
            "Routine SecurityManager success/locked checks must not spam android.util.Log during recomposition or page switches.",
            securityManagerSource.contains("android.util.Log.d(logTag, \"canRestoreMainAppSession: session inactive -> locked\")") ||
                securityManagerSource.contains("android.util.Log.d(logTag, \"canRestoreMainAppSession: runtime MDK cache present\")") ||
                securityManagerSource.contains("android.util.Log.d(logTag, \"canAccessVaultNow: session inactive -> locked\")") ||
                securityManagerSource.contains("android.util.Log.d(logTag, \"canAccessVaultNow: runtime MDK cache present -> accessible\")")
        )
        assertTrue(
            "Authenticator and card-wallet hot cards must consume parsed UI data so recomposition does not decrypt or parse encrypted payloads.",
                totpCodeCardSource.contains("parsedTotpData: TotpData? = null") &&
                totpListContentSource.contains("val parsedTotpState by viewModel.parsedTotpState.collectAsState()") &&
                totpListContentSource.contains("val parsedTotpItems = parsedTotpState.items") &&
                totpListContentSource.contains("parsedTotpData = totpDataById[item.id]") &&
                authenticatorTabPaneSource.contains("val parsedTotpItems by totpViewModel.parsedTotpItems.collectAsState()") &&
                authenticatorTabPaneSource.contains("parsedTotpItems.firstOrNull { it.item.id == selectedTotpId }?.totpData") &&
                bankCardCardSource.contains("cardData: BankCardData? = null") &&
                documentCardSource.contains("documentData: DocumentData? = null") &&
                cardWalletScreenSource.contains("val parsedCardsState by bankCardViewModel.parsedCardsState.collectAsState") &&
                cardWalletScreenSource.contains("val parsedDocumentsState by documentViewModel.parsedDocumentsState.collectAsState") &&
                cardWalletScreenSource.contains("val parsedBillingAddressesState by billingAddressViewModel.parsedBillingAddressesState.collectAsState") &&
                cardWalletScreenSource.contains("val preparedWalletState by rememberPreparedWallet(") &&
                walletListPreparationSource.contains("withContext(Dispatchers.Default)") &&
                walletListPreparationSource.contains("cards.items.map { it.item.toBankCardWalletListItem(it.cardData) }") &&
                walletListPreparationSource.contains("documents.items.map { it.item.toDocumentWalletListItem(it.documentData) }") &&
                cardWalletScreenSource.contains("cardData = walletItem.bankCardData") &&
                cardWalletScreenSource.contains("documentData = walletItem.documentData")
        )
        assertFalse(
            "Card composables must not construct SecurityManager; field decrypt belongs in ViewModel/repository state pipelines.",
            totpCodeCardSource.contains("SecurityManager(") ||
                bankCardCardSource.contains("SecurityManager(") ||
                documentCardSource.contains("SecurityManager(")
        )
        assertFalse(
            "Wide authenticator detail pane must not parse/decrypt encrypted TOTP payloads directly in Compose.",
            authenticatorTabPaneSource.contains("SecurityManager(") ||
                authenticatorTabPaneSource.contains("TotpDataResolver.parseStoredItemData")
        )
        assertTrue(
            "Card and authenticator ViewModels should prepare parsed models on a background dispatcher before UI rendering.",
            bankCardViewModelSource.contains("val parsedCards: StateFlow<List<ParsedBankCardItem>>") &&
                bankCardViewModelSource.substringAfter("private val parsedCardsStateSource:")
                    .substringBefore("val parsedCardsState:").contains(".flowOn(Dispatchers.Default)") &&
                documentViewModelSource.contains("val parsedDocuments: StateFlow<List<ParsedDocumentItem>>") &&
                documentViewModelSource.substringAfter("private val parsedDocumentsStateSource:")
                    .substringBefore("val parsedDocumentsState:").contains(".flowOn(Dispatchers.Default)") &&
                totpViewModelSource.contains("val parsedTotpItems: StateFlow<List<ParsedTotpItem>>") &&
                totpViewModelSource.substringAfter("val parsedTotpState:")
                    .substringBefore("val parsedTotpItems:").contains(".flowOn(Dispatchers.Default)")
        )
        assertTrue(
            "Password detail must render the base entry before attachment reconcile, sibling grouping, password decrypt, and custom field loading finish.",
            passwordDetailScreenSource.indexOf("passwordEntry = entry") in 1 until
                passwordDetailScreenSource.indexOf("AttachmentContainer.keepassReconciler") &&
                passwordDetailScreenSource.contains("initialDetailDataLoaded = false") &&
                passwordDetailScreenSource.contains("if (!initialDetailDataLoaded) return@LaunchedEffect")
        )
        val passwordAuthenticatorSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/PasswordRelatedContent.kt"
        ).readSourceText()
        val codeCardSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/components/TotpCodeCard.kt"
        ).readSourceText()
        assertTrue(
            "Password detail must reuse the authenticator renderer, whose HMAC result is cached per generation window.",
            passwordDetailScreenSource.contains("PasswordAuthenticatorCard(") &&
                passwordAuthenticatorSource.contains("TotpCodeCard(") &&
                codeCardSource.contains("val currentCode = remember(generationWindow, totpData, settings.totpTimeOffset)") &&
                codeCardSource.contains("val nextCode = remember(generationWindow, totpData, settings.totpTimeOffset)")
        )
        assertTrue(
            "Password detail linked TOTP lookup must parse encrypted authenticator items off the main thread.",
            passwordViewModelSource.contains("fun getLinkedTotpFlow(passwordId: Long): Flow<TotpData?>") &&
                passwordViewModelSource.contains("preferred?.second") &&
                passwordViewModelSource.contains("}.flowOn(Dispatchers.Default)")
        )
        assertTrue(
            "Password editor must not subscribe to the plaintext all-password stream just for SSO reference metadata.",
            addEditPasswordScreenSource.contains("val allPasswordsForRef by viewModel.allPasswordsForUi.collectAsState") &&
                !addEditPasswordScreenSource.contains("val allPasswordsForRef by viewModel.allPasswords.collectAsState")
        )
        assertTrue(
            "Password editor bank-card picker must reuse parsed card state instead of parsing encrypted card data in Compose.",
            addEditPasswordScreenSource.contains("val bankCards by bankCardViewModel.parsedCards.collectAsState") &&
                !addEditPasswordScreenSource.contains("bankCardViewModel.allCards.collectAsState") &&
                !addEditPasswordScreenSource.contains("bankCardViewModel.parseCardData(item.itemData)")
        )
        assertTrue(
            "Password editor load must move entry lookup, sibling scan, secret inspection, and custom fields off the main thread.",
            addEditPasswordScreenSource.contains("withContext(Dispatchers.IO) {\n                    viewModel.getRawPasswordEntryById(actualId, forEdit = true)") &&
                addEditPasswordScreenSource.contains("withContext(Dispatchers.Default) {\n                        viewModel.inspectSecretState(rawEntry)") &&
                addEditPasswordScreenSource.contains("withContext(Dispatchers.IO) {\n                            viewModel.getRawActivePasswordEntries()") &&
                addEditPasswordScreenSource.substringAfter("templateDraft = withContext(Dispatchers.IO) {")
                    .substringBefore("} catch").let { block ->
                        block.contains("TemplateCredentialDraft.load(entry,") &&
                            block.contains("viewModel.getCustomFieldsByEntryIdSync(actualId).map(CustomFieldDraft::fromCustomField)")
                    }
        )
        assertTrue(
            "Card-wallet editors must parse encrypted itemData on a background dispatcher before filling form state.",
            addEditBankCardScreenSource.contains("withContext(Dispatchers.Default) {\n                    viewModel.parseCardData(item.itemData)") &&
                addEditDocumentScreenSource.contains("withContext(Dispatchers.Default) {\n                    embeddedEditor?.data ?: viewModel.parseDocumentData(item.itemData)")
        )
        val cardSaveConditions = addEditBankCardScreenSource.substringAfter("val canSave = ")
            .substringBefore('\n').split("&&").map(String::trim)
        assertTrue(
            "Card-wallet editors must not show a blank edit form before encrypted fields have been parsed.",
            addEditBankCardScreenSource.contains("val isExistingCardReady = cardId == null || hasLoadedExistingCardFields") &&
                cardSaveConditions.containsAll(listOf("nfcTypeConfirmed", "isExistingCardReady",
                    "(cardNumber.isNotBlank() || embeddedDraft != null)", "!isSaving")) &&
                addEditBankCardScreenSource.contains("if (!nfcTypeConfirmed || !isExistingCardReady || isSaving || (cardNumber.isBlank() && embeddedDraft == null)) return@saveAction") &&
                addEditBankCardScreenSource.contains("if (!isExistingCardReady)") &&
                addEditBankCardScreenSource.contains("BankCardEditLoadingPlaceholder(") &&
                addEditBankCardScreenSource.substringAfter("withContext(Dispatchers.IO) {").substringBefore("}?.let").contains("viewModel.getCardById(requireNotNull(cardId))") &&
                addEditDocumentScreenSource.contains("val isExistingDocumentReady = (documentId == null && embeddedDraft == null) || hasLoadedExistingDocumentFields") &&
                addEditDocumentScreenSource.contains("val canSave = isExistingDocumentReady && hasRequiredContent && !isSaving") &&
                addEditDocumentScreenSource.contains("if (!isExistingDocumentReady)") &&
                addEditDocumentScreenSource.contains("DocumentEditLoadingPlaceholder(") &&
                addEditDocumentScreenSource.substringAfter("withContext(Dispatchers.IO) {").substringBefore("}?.let").contains("documentId?.let { viewModel.getDocumentById(it) }")
        )
        assertTrue(
            "Card-wallet add/edit screens must not subscribe to full card/document replica lists while creating a new item.",
            addEditBankCardScreenSource.contains("if (cardId != null) viewModel.allCards else flowOf(emptyList())") &&
                addEditDocumentScreenSource.contains("if (documentId != null) viewModel.allDocuments else flowOf(emptyList())")
        )
        assertTrue(
            "Card-wallet common-name analysis must stay lazy so add-page navigation animations are not blocked by full SecureItem scans.",
            commonNameSuggestionSheetSource.contains("includeAnalyzedItems: Boolean = true") &&
                commonNameSuggestionSheetSource.contains("if (includeAnalyzedItems)") &&
                commonNameSuggestionSheetSource.contains("flowOf(CommonFieldSuggestionIndex(emptyList(), CommonSuggestionField.FULL_NAME))") &&
                addEditBankCardScreenSource.contains("includeAnalyzedItems = shouldLoadCommonNameAnalysis || showCommonNamePicker") &&
                addEditDocumentScreenSource.contains("includeAnalyzedItems = shouldLoadCommonNameAnalysis || showCommonNamePicker")
        )
        assertTrue(
            "Wide card-wallet detail pane should animate add/edit/detail changes instead of hard switching after heavy composition work.",
            cardWalletDetailPaneContentSource.contains("AnimatedContent(") &&
                cardWalletDetailPaneContentSource.contains("targetState = detailContent") &&
                cardWalletDetailPaneContentSource.contains("transitionSpec = AnimationUtils.pageTransitionSpec()") &&
                cardWalletDetailPaneContentSource.contains("CardWalletDetailContent.BankCardAdd") &&
                cardWalletDetailPaneContentSource.contains("CardWalletDetailContent.DocumentAdd")
        )
    }

    @Test
    fun autofillPasswordSelectionReturnPathDoesNotBlockMainThread() {
        val pickerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPickerActivityV2.kt"
        ).readSourceText()
        val listItemSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/ui/PasswordListItem.kt"
        ).readSourceText()
        val handleSignature = Regex("""private\s+fun\s+handleAutofill\(\s*\w+:\s*PasswordEntry,\s*forceAddUri:\s*Boolean,\s*generation:\s*Long\s*\)\s*\{""")
        val handleMatches = handleSignature.findAll(pickerSource).toList()
        assertTrue("The guard must locate exactly one real handler regardless of the selection parameter name", handleMatches.size == 1)
        val handleBody = pickerSource.substring(handleMatches.single().range.last + 1)
            .substringBefore("private suspend fun preparePasswordAutofill(")
        val prepareBody = pickerSource.substringAfter("private suspend fun preparePasswordAutofill(")
            .substringBefore("private fun handleGeneratedPasswordFill(")
        val otpActionBody = pickerSource.substringAfter("private suspend fun processSelectedOtpActions(password: PasswordEntry) {")
            .substringBefore("private suspend fun generateOtpCodeForPassword")

        assertFalse(
            "Autofill button confirmation must not decrypt passwords directly on the main-thread return path.",
            handleBody.contains("AutofillSecretResolver.decryptPasswordOrNull") ||
                handleBody.contains("AutofillSecretResolver.resolvePasswordOrNull") ||
                handleBody.contains("AccountFillPolicy.resolveAccountIdentifier")
        )
        assertFalse(
            "Autofill button confirmation must not block on preference or database IO before returning to the target app.",
            handleBody.contains("runBlocking")
        )
        assertTrue("Password reads retain a native worker slot and the original authorization generation",
            prepareBody.contains("BoundedSecureDataRead.read<PasswordAutofillPreparation>(securityManager, generation)") &&
                prepareBody.contains("AutofillSecretResolver.resolvePasswordOrNull") &&
                handleBody.contains("isDataReadCurrent(generation)") && handleBody.contains("canPublishCredentials()") &&
                handleBody.contains("if (!source.isCurrent(password)) return@launch"))
        val resolverSource = projectFile("app/src/main/java/takagi/ru/monica/autofill_ng/AutofillSecretResolver.kt").readSourceText()
        val resolveBody = resolverSource.substringAfter("fun resolvePasswordOrNull(").substringBefore("fun decryptPasswordOrNull(")
        assertTrue("Native projection routing must retain the local decryption boundary rather than bypassing it",
            resolveBody.contains("if (AutofillPasswordSource.isNative(entry)) entry.password") &&
                resolveBody.contains("else decryptPasswordOrNull(securityManager, entry.password, logTag)"))
        assertFalse("Stale ID-only plaintext prewarming must not return", pickerSource.contains("private fun prewarmPasswordAutofill("))
        assertTrue(
            "OTP copy/notification side effects should use suspending IO reads instead of runBlocking on the selection path.",
            otpActionBody.contains("AutofillOtpActions(applicationContext).process(password, isAuthorized = {") &&
                otpActionBody.contains("!AutofillPasswordSource.isNative(password) || source?.isCurrent(password) == true") &&
                !otpActionBody.contains("runBlocking") &&
                projectFile("app/src/main/java/takagi/ru/monica/autofill_ng/AutofillOtpActions.kt").readSourceText()
                    .let { it.contains("withContext(Dispatchers.IO)") && !it.contains("runBlocking") }
        )
        assertTrue(
            "Autofill picker initial load should use the password-list M3E loading indicator and move first database reads off the main thread.",
            pickerSource.contains("PasswordListInitialLoadingIndicator()") &&
                pickerSource.contains("val loadedData = withContext(Dispatchers.IO)") &&
                pickerSource.contains("AutofillPickerLoadedData(") &&
                !pickerSource.substringAfter("if (isLoading) {")
                    .substringBefore("} else {")
                    .contains("CircularProgressIndicator()")
        )
    }

    @Test
    fun migratedSecondaryBiometricsKeepOneUseRequestAndForegroundGates() {
        val settings = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/SettingsScreen.kt").readSourceText()
        val currentDeveloper = settings.substringAfter("fun currentDeveloperRequest(").substringBefore("fun cancelDeveloperVerification()")
        val cancelDeveloper = settings.substringAfter("fun cancelDeveloperVerification() {").substringBefore("fun completeDeveloperVerification(")
        val completeDeveloper = settings.substringAfter("fun completeDeveloperVerification(").substringBefore("fun startDeveloperBiometric(")
        val developerBiometric = settings.substringAfter("fun startDeveloperBiometric(").substringBefore("DisposableEffect(developerLifecycle)")
        assertTrue("Developer first prompt and retry must use the same guarded flow",
            settings.contains("startDeveloperBiometric(request)") && settings.contains("startDeveloperBiometric(currentDeveloperVerification)"))
        assertTrue("Developer callbacks must remain bound to the original live request, lock generation and foreground",
            currentDeveloper.contains("developerAlive.get() && developerRequest === request") &&
                currentDeveloper.contains("dataReadGeneration.value == request.generation") &&
                currentDeveloper.contains("Lifecycle.State.RESUMED") &&
                currentDeveloper.contains("?.isInteractive == true") && currentDeveloper.contains("?.isKeyguardLocked == false"))
        assertTrue("Developer completion must reject stale callbacks and consume the request before navigation",
            completeDeveloper.contains("if (!currentDeveloperRequest(request)) return") &&
                completeDeveloper.indexOf("cancelDeveloperVerification()") < completeDeveloper.indexOf("developerNavigate.value()"))
        assertTrue("Canceled prompts cannot reopen the password dialog or inherit a replacement's callbacks",
            cancelDeveloper.contains("developerRequest = null") && cancelDeveloper.contains("prompt?.cancelAuthentication()") &&
                developerBiometric.contains("val prompt = BiometricAuthHelper(context)") &&
                developerBiometric.contains("developerBiometric === prompt && currentDeveloperRequest(request)") &&
                settings.contains("Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP"))

        val export = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/ExportDataScreen.kt").readSourceText()
        val currentSteam = export.substringAfter("fun currentSteam(").substringBefore("fun cancelSteamIdentity()")
        val verifiedSteam = export.substringAfter("fun verifiedSteam(").substringBefore("fun verifiedJson(")
        assertTrue("Steam identity must remain bound to the original selected source, format, IDs and generation",
            currentSteam.contains("steamIdentityRequest === request") && currentSteam.contains("request.source.key ==") &&
                currentSteam.contains("request.option.name ==") && currentSteam.contains("request.ids == selectedSteamIds") &&
                currentSteam.contains("SecurityManager.dataReadGeneration.value == request.generation") &&
                currentSteam.contains("Lifecycle.State.RESUMED"))
        assertTrue("Password, skip and biometric Steam success share a single consumed authorization",
            verifiedSteam.contains("if (!currentSteam(request)) return") &&
                verifiedSteam.indexOf("cancelSteamIdentity()") < verifiedSteam.indexOf("chooseLocation()") &&
                export.contains("onSuccess = { verifiedSteam(steamRequest) }") &&
                export.contains("onSuccess = { if (steamBiometric === prompt) verifiedSteam(steamRequest) }"))
        assertTrue("Steam retains strong biometric entry and revokes it when its owner pauses or closes",
            export.contains("val prompt = takagi.ru.monica.utils.BiometricAuthHelper(context)") &&
                export.contains("Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) cancelSteamIdentity()") &&
                export.contains("onDismiss = ::cancelSteamIdentity") && export.contains("prompt?.cancelAuthentication()"))
    }

    private fun projectFile(relativePath: String): File {
        val file = projectPath(relativePath)
        if (file.isFile) return file
        error("Unable to find project file: $relativePath from ${System.getProperty("user.dir")}")
    }

    private fun projectPath(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }

        return candidates.firstOrNull { it.exists() }
            ?: error("Unable to find project path: $relativePath from ${System.getProperty("user.dir")}")
    }
}

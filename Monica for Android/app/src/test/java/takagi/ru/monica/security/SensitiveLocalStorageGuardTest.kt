package takagi.ru.monica.security

import takagi.ru.monica.testing.readSourceText

import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveLocalStorageGuardTest {

    @Test
    fun noteDrafts_useProtectedStorageWithLegacyMigration() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/notes/domain/NoteDraftStore.kt")

        assertTrue(source.contains("SecurityManager(context.applicationContext)"))
        assertTrue(source.contains("securityManager.putProtectedString(key(noteId, \"content\"), content)"))
        assertTrue(source.contains("securityManager.getProtectedString(key(noteId, \"content\"))"))
        assertTrue(source.contains("securityManager.removeProtectedString(key(noteId, \"content\"))"))
        assertFalse(source.contains(".putString(key(noteId, \"content\"), content)"))
    }

    @Test
    fun passwordGenerationHistory_isStoredAsEncryptedPayload() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/data/PasswordHistoryManager.kt")

        assertTrue(source.contains("migrateLegacyHistoryIfNeeded"))
        assertTrue(source.contains("securityManager.encryptDataLegacyCompat(json.encodeToString(history))"))
        assertTrue(source.contains("securityManager.decryptData(raw)"))
        assertFalse(source.contains("preferences[HISTORY_KEY] = json.encodeToString"))
    }

    @Test
    fun commonAccountSensitiveFields_areStoredAsEncryptedPayloads() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/data/CommonAccountPreferences.kt")

        assertTrue(source.contains("migrateSensitivePreferencesIfNeeded"))
        assertTrue(source.contains("protectedPreferenceValue(CardWalletDataCodec.encodeBillingAddress(address))"))
        assertTrue(source.contains("protectedPreferenceValue(encodeTemplates"))
        assertTrue(source.contains("securityManager.encryptDataLegacyCompat(it)"))
        assertFalse(source.contains("preferences[KEY_TEMPLATES_JSON] = encodeTemplates"))
        assertFalse(source.contains("preferences[KEY_BILLING_ADDRESS_JSON] = CardWalletDataCodec.encodeBillingAddress(address)"))
    }

    @Test
    fun oneDriveBackupConfig_usesProtectedStorageWithLegacyMigration() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/utils/OneDriveBackupHelper.kt")

        assertTrue(source.contains("SecurityManager(appContext)"))
        assertTrue(source.contains("migrateLegacyConfigIfNeeded"))
        assertTrue(source.contains("securityManager.putProtectedString(SECURE_KEY_USERNAME"))
        assertTrue(source.contains("securityManager.getProtectedString(SECURE_KEY_USERNAME)"))
        assertTrue(source.contains("securityManager.removeProtectedString(SECURE_KEY_USERNAME)"))
        assertFalse(source.contains(".putString(KEY_USERNAME, session.username)"))
        assertFalse(source.contains(".putString(KEY_FOLDER_PATH, normalizedFolderPath)"))
    }

    @Test
    fun webDavBackoffPersistence_hashesHostKeys() {
        val source = projectFile("app/src/main/java/takagi/ru/monica/webdav/WebDavBackoffState.kt")

        assertTrue(source.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(source.contains("private fun storageKey(host: String)"))
        assertTrue(source.contains("readState(p, storageKey(host))"))
        assertTrue(source.contains("private fun legacyStorageKey(host: String)"))
        assertFalse(source.contains(".putString(KEY_PREFIX + host + KEY_RL_WINDOW"))
        assertFalse(source.contains(".putLong(KEY_PREFIX + host + KEY_BLOCK_UNTIL"))
    }

    @Test
    fun importAndBitwardenSyncLogs_doNotExposeSensitiveRawValues() {
        val importManager = projectFile("app/src/main/java/takagi/ru/monica/util/DataExportImportManager.kt")
        val cipherSync = projectFile("app/src/main/java/takagi/ru/monica/bitwarden/service/CipherSyncProcessor.kt")

        assertFalse(importManager.contains("第一行: ${'$'}firstLine"))
        assertFalse(importManager.contains("内容: ${'$'}fields"))
        assertFalse(importManager.contains("读取第${'$'}{lineCount}行: ${'$'}currentLine"))
        assertFalse(importManager.contains("解析CSV行失败: ${'$'}line"))
        assertFalse(cipherSync.contains("SSH_FIELD_DUMP"))
        assertFalse(cipherSync.contains("SSH_RESOLVE"))
        assertFalse(cipherSync.contains("resolvedPrivateKey.take"))
    }

    @Test
    fun importAndMediaLogs_doNotExposeTitlesUrisOrLocalPaths() {
        val importViewModel = projectFile("app/src/main/java/takagi/ru/monica/viewmodel/DataExportImportViewModel.kt")
        val imageManager = projectFile("app/src/main/java/takagi/ru/monica/util/ImageManager.kt")
        val noteScreen = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/AddEditNoteScreen.kt")
        val dualPhotoPicker = projectFile("app/src/main/java/takagi/ru/monica/ui/components/DualPhotoPicker.kt")
        val keepassViewModel = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/KeePassKdbxViewModel.kt")
        val accessibilityService = projectFile("app/src/main/java/takagi/ru/monica/service/MonicaAccessibilityService.kt")
        val autofillPreferences = projectFile("app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPreferences.kt")
        val autofillPicker = projectFile("app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPickerActivityV2.kt")
        val oneDriveBackup = projectFile("app/src/main/java/takagi/ru/monica/utils/OneDriveBackupHelper.kt")
        val autoBackupWorker = projectFile("app/src/main/java/takagi/ru/monica/workers/AutoBackupWorker.kt")
        val webDavBackupScreen = projectFile("app/src/main/java/takagi/ru/monica/ui/screens/WebDavBackupScreen.kt")
        val passkeyRepository = projectFile("app/src/main/java/takagi/ru/monica/repository/PasskeyRepository.kt")
        val passkeyCreate = projectFile("app/src/main/java/takagi/ru/monica/passkey/PasskeyCreateActivity.kt")
        val passkeyAuth = projectFile("app/src/main/java/takagi/ru/monica/passkey/PasskeyAuthActivity.kt")
        val cipherSync = projectFile("app/src/main/java/takagi/ru/monica/bitwarden/service/CipherSyncProcessor.kt")

        assertFalse(importViewModel.contains("成功插入到PasswordEntry表: ${'$'}{exportItem.title}"))
        assertFalse(importViewModel.contains("跳过重复条目: ${'$'}{aegisEntry.name}"))
        assertFalse(importViewModel.contains("成功插入Steam Guard: ${'$'}title"))
        assertFalse(imageManager.contains("uri=${'$'}uri"))
        assertFalse(imageManager.contains("path=${'$'}{file.absolutePath}"))
        assertFalse(noteScreen.contains("tempPath=${'$'}tempPath"))
        assertFalse(noteScreen.contains("uri=${'$'}tempUri"))
        assertFalse(dualPhotoPicker.contains("tempPath=${'$'}tempPath"))
        assertFalse(dualPhotoPicker.contains("uri=${'$'}tempUri"))
        assertFalse(keepassViewModel.contains("Starting local KDBX import from uri=${'$'}sourceUri"))
        assertFalse(keepassViewModel.contains("Failed to parse otpauth URI: ${'$'}uri"))
        assertFalse(accessibilityService.contains("url=${'$'}url"))
        assertFalse(autofillPreferences.contains("id=${'$'}normalized, passwordId=${'$'}passwordId"))
        assertFalse(autofillPicker.contains("app=${'$'}applicationId, web=${'$'}webDomain"))
        assertFalse(oneDriveBackup.contains("folder=${'$'}{config.folderPath}"))
        assertFalse(oneDriveBackup.contains("target=${'$'}targetName"))
        assertFalse(autoBackupWorker.contains("无法解密密码 ${'$'}{entry.title}"))
        assertFalse(webDavBackupScreen.contains("无法解密密码 ${'$'}{entry.title}"))
        assertFalse(webDavBackupScreen.contains("entry.website.ifBlank { entry.username }"))
        assertFalse(passkeyRepository.contains("[${'$'}action] ${'$'}details"))
        assertFalse(passkeyRepository.contains("Keystore: ${'$'}keyAlias"))
        assertFalse(passkeyCreate.contains("Passkey created successfully: ${'$'}credentialIdB64"))
        assertFalse(passkeyAuth.contains("Passkey not found: ${'$'}credentialId"))
        assertFalse(passkeyAuth.contains("Authentication successful for: ${'$'}{passkey.credentialId}"))
        assertFalse(cipherSync.contains("title=${'$'}name"))
    }

    @Test
    fun passkeyPrivateKeys_areProtectedOutsideRoom() {
        val passkeyCreate = projectFile("app/src/main/java/takagi/ru/monica/passkey/PasskeyCreateActivity.kt")
        val passkeyAuth = projectFile("app/src/main/java/takagi/ru/monica/passkey/PasskeyAuthActivity.kt")
        val privateKeyStore = projectFile("app/src/main/java/takagi/ru/monica/passkey/PasskeyPrivateKeyStore.kt")
        val repository = projectFile("app/src/main/java/takagi/ru/monica/repository/PasskeyRepository.kt")

        assertTrue(privateKeyStore.contains("putProtectedString(storageKey, pkcs8Base64)"))
        assertTrue(privateKeyStore.contains("REF_PREFIX + storageKey"))
        assertTrue(passkeyCreate.contains("PasskeyPrivateKeyStore.protectForStorage"))
        assertFalse(passkeyCreate.contains("privateKeyAlias = privateKeyB64"))
        assertTrue(passkeyAuth.contains("PasskeyPrivateKeyStore.resolve(applicationContext, privateKeyData)"))
        assertTrue(repository.contains("protectPlaintextPrivateKeys"))
        assertTrue(repository.contains("protectPrivateKeyForRoom"))
    }

    @Test
    fun legacyOperationLogs_keepTheirRedactionMigration() {
        val database = projectFile("app/src/main/java/takagi/ru/monica/data/PasswordDatabase.kt")

        assertTrue(database.contains("MIGRATION_68_69"))
        assertTrue(database.contains("UPDATE operation_logs"))
        assertTrue(database.contains("changesJson = ''"))
    }

    @Test
    fun persistentMdbxDiagnostics_redactSensitiveMetadata() {
        val logger = projectFile("app/src/main/java/takagi/ru/monica/mdbx/MdbxDiagLogger.kt")

        assertTrue(logger.contains("name|filePath|workingCopy|cacheCopy|treeUri|uri|externalUri|localCopy"))
        assertTrue(logger.contains("rows=<redacted>"))
        assertTrue(logger.contains("(content|file)://"))
        assertTrue(logger.contains("<path>"))
    }

    private fun projectFile(relativePath: String): String {
        val start = Paths.get("").toAbsolutePath()
        var cursor = start
        while (cursor.parent != null) {
            val candidate = cursor.resolve(relativePath).toFile()
            if (candidate.exists()) {
                return candidate.readSourceText()
            }
            cursor = cursor.parent
        }
        error("Project file not found from $start: $relativePath")
    }
}

package takagi.ru.monica.keepass

import android.net.Uri
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.TimeData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.utils.KeePassKdbxService
import java.io.File
import java.util.UUID
import java.time.Instant

/** Exercises the real encrypted file -> Android service -> legacy projection boundary. */
@RunWith(AndroidJUnit4::class)
class KeePassNativeCompatibilityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
    private val security = SecurityManager(context)
    private val service = KeePassKdbxService(context, dao, security)

    @Test
    fun fiveAuthenticatorsInALargeKeePassDatabaseAreAllProjected() = runBlocking {
        val authenticators = listOf(
            entry("URI", "otp" to "otpauth://totp/URI:alice?secret=$SECRET&issuer=URI"),
            entry("Tray TOTP", "TOTP Seed" to SECRET, "TOTP Settings" to "30;6"),
            entry("Firefox", "TimeOtp-Secret-Base32" to SECRET),
            entry("Hex secret", "TimeOtp-Secret-Hex" to "3132333435363738393031323334353637383930"),
            entry("Base64 secret", "TimeOtp-Secret-Base64" to "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA=")
        )
        val passwords = List(401) { entry("Account $it", "Password" to "synthetic-password-$it") }
        fixture(authenticators + passwords) { id ->
            val items = service.readSecureItems(id, setOf(ItemType.TOTP)).getOrThrow()
            assertEquals("Every source authenticator UUID must survive projection", authenticators.map { it.uuid.toString() }.toSet(),
                items.map { it.item.keepassEntryUuid }.toSet())
            items.forEach { assertEquals(SECRET, Json.decodeFromString<TotpData>(it.item.itemData).secret) }
            assertEquals(406, service.readPasswordEntries(id).getOrThrow().size)
        }
    }

    @Test
    fun unknownAndIncompleteMonicaMetadataCannotHideAnEntry() = runBlocking {
        val source = listOf(
            entry("Future type", "MonicaItemType" to "FUTURE_TYPE", "MonicaItemData" to "{\"future\":true}"),
            entry("Password marker", "MonicaItemType" to "PASSWORD"),
            entry("Missing note data", "MonicaItemType" to "NOTE", "Notes" to "My only note"),
            entry("Broken OTP metadata", "MonicaItemType" to "TOTP", "MonicaItemData" to "not-json",
                "otp" to "otpauth://totp/Recovery?secret=$SECRET"),
            entry("Broken passkey", "MonicaPasskeyCredentialId" to "broken", "MonicaPasskeyData" to "not-json")
        )
        fixture(source) { id ->
            val workspace = service.loadWorkspace(id).getOrThrow()
            val visibleIds = workspace.passwords.map { it.entryUuid } + workspace.secureItems.map { it.item.keepassEntryUuid }
            assertEquals(source.map { it.uuid.toString() }.toSet(), visibleIds.toSet())
            val recovered = workspace.secureItems.single { it.item.keepassEntryUuid == source[3].uuid.toString() }
            assertEquals(SECRET, Json.decodeFromString<TotpData>(recovered.item.itemData).secret)
        }
    }

    @Test
    fun customFieldOnlyEntriesRemainReachableIncludingEmptyFields() = runBlocking {
        val source = listOf(entry("", "Recovery instructions" to "Custom-only data"), entry("", "Empty custom" to ""))
        fixture(source) { id ->
            val passwords = service.readPasswordEntries(id).getOrThrow()
            assertEquals(source.map { it.uuid.toString() }.toSet(), passwords.map { it.entryUuid }.toSet())
            assertEquals("Empty custom", passwords.single { it.entryUuid == source[1].uuid.toString() }.customFields.single().title)
        }
    }

    @Test
    fun ordinaryTrashNamedGroupsAreNotRecycleBins() = runBlocking {
        val source = entry("Keep this account", "Password" to "synthetic")
        fixture(emptyList(), listOf(Group(UUID.randomUUID(), name = "Trash", entries = listOf(source)))) { id ->
            assertFalse(service.readPasswordEntries(id).getOrThrow().single().isInRecycleBin)
            assertTrue(service.listGroups(id).getOrThrow().any { it.name == "Trash" })
        }
    }

    @Test
    fun databaseCardCountsNativeEntriesRegardlessOfProjectionType() = runBlocking {
        val source = listOf(
            entry("Note", "MonicaItemType" to "NOTE", "MonicaItemData" to "{\"content\":\"hello\"}"),
            entry("Authenticator", "MonicaItemType" to "TOTP", "MonicaItemData" to "{\"secret\":\"$SECRET\"}")
        )
        fixture(source) { id ->
            service.loadWorkspace(id, allowedSecureItemTypes = setOf(ItemType.TOTP)).getOrThrow()
            assertEquals(2, dao.getDatabaseById(id)!!.entryCount)
            service.readPasswordEntries(id).getOrThrow()
            assertEquals(2, dao.getDatabaseById(id)!!.entryCount)
        }
    }

    @Test
    fun editingAnAuthenticatorOnALoginPreservesLoginFieldsAndForeignMetadata() = runBlocking {
        val source = entry("Firefox", "UserName" to "alice", "Password" to "original-login-secret", "URL" to "https://example.com",
            "Notes" to "original notes", "otp" to "otpauth://totp/Firefox:alice?secret=$SECRET",
            "Foreign plugin" to "must survive", "Empty custom" to "").copy(
            tags = listOf("original-tag"),
            times = Instant.parse("2020-01-01T00:00:00Z").let { TimeData(it, it, it, it, it) },
            customData = mapOf("plugin" to CustomDataValue("opaque state")),
            history = listOf(entry("Historic title", "Password" to "historic-secret"))
        )
        fixture(listOf(source)) { id ->
            val projected = service.readSecureItems(id, setOf(ItemType.TOTP)).getOrThrow().single().item
            val data = Json.decodeFromString<TotpData>(projected.itemData)
            service.updateSecureItem(id, projected.copy(itemData = Json.encodeToString(TotpData.serializer(), data.copy(period = 60)))).getOrThrow()
            val file = File(context.filesDir, dao.getDatabaseById(id)!!.filePath)
            val reopened = file.inputStream().use { KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(PASSWORD))) }
                .content.group.entries.single()
            listOf("UserName", "Password", "URL", "Notes", "Foreign plugin", "Empty custom").forEach { key ->
                assertEquals("OTP editing must preserve $key", source.fields[key]?.content, reopened.fields[key]?.content)
            }
            assertNull(reopened.fields["MonicaItemType"])
            assertTrue(reopened.fields.getValue("Password") is EntryValue.Encrypted)
            assertEquals(source.tags, reopened.tags)
            assertEquals(source.customData, reopened.customData)
            assertTrue(reopened.history.any { it.fields["Title"]?.content == "Historic title" })
            KeePassKdbxService.invalidateProcessCache(id)
            assertEquals(1, service.readPasswordEntries(id).getOrThrow().size)
            val updated = service.readSecureItems(id, setOf(ItemType.TOTP)).getOrThrow().single().item
            assertEquals(60, Json.decodeFromString<TotpData>(updated.itemData).period)
        }
    }

    @Test
    fun editingOneOfTwoIdenticalLoginNamesTargetsOnlyItsUuid() = runBlocking {
        val first = entry("Duplicate", "UserName" to "alice", "Password" to "first-secret")
        val second = entry("Duplicate", "UserName" to "alice", "Password" to "second-secret")
        fixture(listOf(first, second)) { id ->
            service.updatePasswordEntry(id, PasswordEntry(title = "Duplicate", username = "alice",
                password = "edited-second-secret", website = "", keepassDatabaseId = id,
                keepassEntryUuid = second.uuid.toString()), { it.password }).getOrThrow()
            val file = File(context.filesDir, dao.getDatabaseById(id)!!.filePath)
            val entries = file.inputStream().use { KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(PASSWORD))) }
                .content.group.entries.associateBy { it.uuid }
            assertEquals("first-secret", entries.getValue(first.uuid).fields.getValue("Password").content)
            assertEquals("edited-second-secret", entries.getValue(second.uuid).fields.getValue("Password").content)
        }
    }

    @Test
    fun nativeDraftRoundTripsFieldsPropertiesIconsAndAttachmentsInBothKdbxVersions() = runBlocking {
        for (version3 in listOf(false, true)) {
            val source = entry("Original", "Code" to "upper", "code" to "lower", " Code " to "space",
                "Empty" to "", "Whitespace" to " \t\r\n ", "Notes" to "{S:Code}", "Password" to " synthetic-secret ")
                .copy(tags = listOf("original:tag"))
            fixture(listOf(source), version3 = version3) { id ->
                val attachment = File.createTempFile("kdbx-attachment-", ".txt", context.cacheDir)
                try {
                    val bytes = "synthetic attachment with Unicode 附件".toByteArray()
                    attachment.writeBytes(bytes)
                    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    val png = java.io.ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                    bitmap.recycle()
                    val expiry = Instant.parse("2030-06-15T12:30:00Z")
                    val fields = source.fields.map { (name, value) -> KeePassFieldChange(name,
                        if (name == "Title") "Updated" else value.content, value is EntryValue.Encrypted) }
                    service.saveNativeEntryDraft(id, source.uuid, null, fields,
                        KeePassNativeEntryPresentationUpdate(tags = listOf("work", "project:inside"), expires = true,
                            expiryTime = expiry, customIcon = KeePassNativeCustomIconPayload(png, "Synthetic icon")),
                        listOf(Uri.fromFile(attachment)), revision(id)).getOrThrow()
                    val reopened = reopen(id)
                    assertEquals(version3, reopened is KeePassDatabase.Ver3x)
                    val saved = reopened.content.group.entries.single()
                    assertEquals("Updated", saved.fields.getValue("Title").content)
                    for (name in listOf("Code", "code", " Code ", "Empty", "Whitespace", "Notes", "Password")) {
                        assertEquals(source.fields.getValue(name).content, saved.fields.getValue(name).content)
                    }
                    assertEquals(listOf("work", "project:inside"), saved.tags)
                    assertEquals(expiry, saved.times?.expiryTime)
                    assertEquals(true, saved.times?.expires)
                    assertNotNull(reopened.content.meta.customIcons[saved.customIconUuid])
                    assertArrayEquals(bytes, reopened.binaries.getValue(saved.binaries.single().hash).inputStream().use { it.readBytes() })
                    assertEquals(1, saved.history.size)
                    assertEquals("Original", saved.history.single().fields.getValue("Title").content)
                    KeePassKdbxService.invalidateProcessCache(id)
                    val browser = service.openNativeBrowser(id).getOrThrow()
                    assertEquals("upper", browser.entries.single().field("Notes")?.displayValue)
                    // Synthetic encrypted artifacts for independent reader interoperability checks.
                    databaseFile(id).copyTo(File(context.getExternalFilesDir(null),
                        "keepass-native-roundtrip-v${if (version3) 3 else 4}.kdbx"), overwrite = true)
                } finally { attachment.delete() }
            }
        }
    }

    @Test
    fun failedAttachmentOrIconDoesNotPartiallySaveAndCanBeRetried() = runBlocking {
        val original = entry("Before", "Password" to "secret")
        fixture(listOf(original)) { id ->
            val file = databaseFile(id)
            val before = file.readBytes()
            val token = revision(id)
            val fields = listOf(KeePassFieldChange("Title", "After"))
            val missing = Uri.fromFile(File(context.cacheDir, "missing-${UUID.randomUUID()}.txt"))
            assertTrue(service.saveNativeEntryDraft(id, original.uuid, null, fields,
                KeePassNativeEntryPresentationUpdate(tags = listOf("changed")), listOf(missing), token).isFailure)
            assertArrayEquals(before, file.readBytes())
            val parent = service.openNativeBrowser(id).getOrThrow().rootGroup.identity.groupUuid
            assertTrue(service.saveNativeEntryDraft(id, null, parent, fields,
                KeePassNativeEntryPresentationUpdate(customIcon = KeePassNativeCustomIconPayload(byteArrayOf(1, 2))),
                emptyList(), token).isFailure)
            assertArrayEquals(before, file.readBytes())
            service.saveNativeEntryDraft(id, original.uuid, null, fields, null, emptyList(), token).getOrThrow()
            assertEquals("After", reopen(id).content.group.entries.single().fields.getValue("Title").content)
            val created = service.saveNativeEntryDraft(id, null, parent,
                listOf(KeePassFieldChange("Title", ""), KeePassFieldChange("code", ""), KeePassFieldChange("Code", "value")),
                KeePassNativeEntryPresentationUpdate(tags = listOf("unnamed")), emptyList(), revision(id)).getOrThrow()
            assertEquals(listOf("unnamed"), created.tags)
            assertTrue(created.history.isEmpty())
            assertEquals(2, reopen(id).content.group.entries.size)
        }
    }

    @Test
    fun staleAndReadOnlyEditsPreserveTheCommittedDatabase() = runBlocking {
        val source = entry("Before", "Password" to "secret")
        fixture(listOf(source)) { id ->
            val stale = revision(id)
            service.replaceNativeEntryFields(id, source.uuid,
                listOf(KeePassFieldChange("Title", "Committed")), stale).getOrThrow()
            val before = databaseFile(id).readBytes()
            val fields = listOf(KeePassFieldChange("Title", "Stale overwrite"))
            assertTrue(service.saveNativeEntryDraft(id, source.uuid, null, fields,
                KeePassNativeEntryPresentationUpdate(tags = listOf("stale")), emptyList(), stale).isFailure)
            assertArrayEquals(before, databaseFile(id).readBytes())
            service.setDatabaseReadOnly(id, true)
            try {
                assertTrue(service.saveNativeEntryDraft(id, source.uuid, null, fields, null, emptyList(), revision(id)).isFailure)
                assertArrayEquals(before, databaseFile(id).readBytes())
                assertEquals("Committed", service.openNativeBrowser(id).getOrThrow().entries.single().title)
            } finally { service.setDatabaseReadOnly(id, false) }
        }
    }

    @Test
    fun groupsRecycleAttachmentsAndHistoryRemainUsableAfterReopen() = runBlocking {
        val source = entry("Original", "Password" to "secret")
        fixture(listOf(source)) { id ->
            val root = service.openNativeBrowser(id).getOrThrow().rootGroup.identity.groupUuid
            val group = service.createNativeGroup(id, root, "Trash", revision(id)).getOrThrow()
            service.moveNativeEntries(id, setOf(source.uuid), group.identity.groupUuid, revision(id)).getOrThrow()
            assertEquals(group.identity.groupUuid.toString(), service.resolveRestoreTarget(id, "Trash").getOrThrow().groupUuid)
            val added = service.addNativeAttachment(id, source.uuid, "original.txt", "attachment".toByteArray(), revision(id)).getOrThrow()
            val ref = added.attachments.single()
            service.renameNativeAttachment(id, source.uuid, ref.hash, ref.name, "renamed.txt", revision(id)).getOrThrow()
            val restored = service.restoreNativeEntryHistory(id, source.uuid, 1, revision(id)).getOrThrow()
            assertEquals("original.txt", restored.attachments.single().name)
            val settings = service.readNativeDatabaseSettings(id).getOrThrow()
            service.updateNativeDatabaseSettings(id, settings.toUpdate().copy(recycleBinEnabled = true)).getOrThrow()
            service.deleteNativeEntries(id, setOf(source.uuid), KeePassNativeDeleteMode.RECYCLE_BIN, revision(id)).getOrThrow()
            KeePassKdbxService.invalidateProcessCache(id)
            assertTrue(service.openNativeBrowser(id).getOrThrow().entries.single().isInRecycleBin)
            service.moveNativeEntries(id, setOf(source.uuid), group.identity.groupUuid, revision(id)).getOrThrow()
            val moved = service.openNativeBrowser(id).getOrThrow().entries.single()
            assertFalse(moved.isInRecycleBin)
            assertEquals("Original", moved.title)
            service.deleteNativeAttachment(id, source.uuid, moved.attachments.single().hash,
                moved.attachments.single().name, revision(id)).getOrThrow()
            val reopened = reopen(id)
            val final = reopened.content.group.groups.first { it.uuid == group.identity.groupUuid }.entries.single()
            assertTrue(final.binaries.isEmpty())
            assertTrue(final.history.any { it.binaries.isNotEmpty() })
            assertTrue(reopened.binaries.isNotEmpty())
        }
    }

    @Test
    fun changingPasswordAndKeyFileRequiresBothNewCredentialsAfterReopen() = runBlocking {
        fixture(listOf(entry("Keep", "Password" to "entry-secret"))) { id ->
            val key = File.createTempFile("kdbx-key-", ".key", context.cacheDir)
            try {
                val keyBytes = ByteArray(32) { (it + 1).toByte() }
                key.writeBytes(keyBytes)
                service.changeMasterCredentials(id, "new synthetic password", KeePassKeyFileChangeMode.REPLACE,
                    Uri.fromFile(key), keepInternalKeyFileCopy = false).getOrThrow()
                val file = databaseFile(id)
                assertTrue(runCatching { reopen(id) }.isFailure)
                assertTrue(runCatching { file.inputStream().use { KeePassDatabase.decode(it,
                    Credentials.from(EncryptedValue.fromString("new synthetic password"))) } }.isFailure)
                val decoded = file.inputStream().use { KeePassDatabase.decode(it,
                    Credentials.from(EncryptedValue.fromString("new synthetic password"), keyBytes)) }
                assertEquals("entry-secret", decoded.content.group.entries.single().fields.getValue("Password").content)
                KeePassKdbxService.invalidateProcessCache(id)
                assertEquals("Keep", service.openNativeBrowser(id).getOrThrow().entries.single().title)
            } finally { key.delete() }
        }
    }

    private suspend fun revision(id: Long) = service.openNativeBrowser(id).getOrThrow().sourceRevision.sha256
    private suspend fun databaseFile(id: Long) = File(context.filesDir, dao.getDatabaseById(id)!!.filePath)
    private suspend fun reopen(id: Long) = databaseFile(id).inputStream().use {
        KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(PASSWORD)), contentParser = KeePassXmlContentParser)
    }

    private fun entry(title: String, vararg custom: Pair<String, String>) = Entry(
        uuid = UUID.randomUUID(),
        fields = EntryFields.of(*((listOf("Title" to title) + custom).map { (key, value) ->
            key to if (key.contains("Secret") || key == "otp" || key == "Password")
                EntryValue.Encrypted(EncryptedValue.fromString(value)) else EntryValue.Plain(value)
        }.toTypedArray()))
    )

    private suspend fun fixture(entries: List<Entry>, groups: List<Group> = emptyList(), version3: Boolean = false, block: suspend (Long) -> Unit) {
        val file = File(context.filesDir, "keepass-compatibility-${UUID.randomUUID()}.kdbx")
        val credentials = Credentials.from(EncryptedValue.fromString(PASSWORD))
        val created = KeePassDatabase.Ver4x.create("Root", Meta(name = "Compatibility fixture", recycleBinEnabled = false), credentials)
        val seed = when (val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        val base: KeePassDatabase = if (version3) {
            KeePassDatabase.Ver3x.create("Root", Meta(name = "Compatibility fixture", recycleBinEnabled = false), credentials)
                .let { it.copy(header = it.header.copy(transformRounds = 100U)) }
        } else created.copy(header = created.header.copy(kdfParameters = KdfParameters.Aes(rounds = 100U, seed = seed)))
        val database = base
            .modifyParentGroup { copy(entries = entries, groups = groups) }
        file.outputStream().use { database.encode(it, contentParser = KeePassXmlContentParser) }
        val id = dao.insertDatabase(LocalKeePassDatabase(name = "KDBX compatibility fixture", filePath = file.name,
            encryptedPassword = security.encryptData(PASSWORD)))
        try {
            block(id)
        } finally {
            KeePassKdbxService.invalidateProcessCache(id)
            dao.deleteDatabaseById(id)
            check(file.canonicalFile.parentFile == context.filesDir.canonicalFile && file.name.startsWith("keepass-compatibility-"))
            file.delete()
        }
    }

    private companion object {
        const val PASSWORD = "Synthetic KDBX compatibility password"
        const val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    }
}

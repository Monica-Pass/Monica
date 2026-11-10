package takagi.ru.monica.keepass

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.constants.GroupOverride
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.AutoTypeItem
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService

/** Public legacy group paths must keep native data through real encrypted file commits. */
@RunWith(AndroidJUnit4::class)
class KeePassServiceGroupExtractionInstrumentedTest {
    @Test
    fun legacyGroupPathsCreateRenameMoveDeleteAndNativeCreateSurviveColdRead() = runBlocking {
        withVaults { service, source, _ ->
            val original = reopen(source)
            val originalGroup = original.content.group.groups.single()
            val sentinel = original.content.group.entries.single()

            val parent = service.createGroup(source.id, " Container ").getOrThrow()
            assertEquals("Container", parent.path)
            assertEquals("Container", service.listGroups(source.id).getOrThrow().single { it.uuid == parent.uuid }.path)
            val browser = service.openNativeBrowser(source.id).getOrThrow()
            val created = service.saveNativeEntryDraft(source.id, null, UUID.fromString(parent.uuid), listOf(
                KeePassFieldChange("Title", "Created through native entry API"),
                KeePassFieldChange("Password", "synthetic new password", true),
                KeePassFieldChange("External plugin", "native custom", true),
            ), null, emptyList(), browser.sourceRevision.sha256).getOrThrow()
            assertEquals("native custom", created.field("External plugin")!!.rawValue)
            assertTrue(created.field("External plugin")!!.isProtected)

            val renamed = service.renameGroup(source.id, "Seed", " Renamed ").getOrThrow()
            assertEquals(originalGroup.uuid.toString(), renamed.uuid)
            assertEquals("Renamed", renamed.path)
            val afterRename = coldRead(service, source)
            assertFalse(service.listGroups(source.id).getOrThrow().any { it.path == "Seed" || it.path.startsWith("Seed/") })
            assertTreePayload(original, originalGroup, afterRename, afterRename.content.group.groups.single { it.uuid == originalGroup.uuid })

            val moved = service.moveGroup(source.id, renamed.path, source.id, parent.path).getOrThrow()
            assertEquals("Container/Renamed", moved.path)
            assertEquals(originalGroup.uuid.toString(), moved.uuid)
            val afterMove = coldRead(service, source)
            val container = afterMove.content.group.groups.single { it.uuid.toString() == parent.uuid }
            val movedGroup = container.groups.single()
            assertTreePayload(original, originalGroup, afterMove, movedGroup)
            assertEquals("Container/Renamed/Child", service.listGroups(source.id).getOrThrow()
                .single { it.uuid == originalGroup.groups.single().uuid.toString() }.path)
            assertFalse(service.listGroups(source.id).getOrThrow().any { it.path == "Renamed" || it.path.startsWith("Renamed/") })
            val createdOnDisk = container.entries.single { it.uuid == created.identity.entryUuid }
            assertEquals("synthetic new password", createdOnDisk.fields.getValue("Password").content)
            assertTrue(createdOnDisk.fields.getValue("Password") is EntryValue.Encrypted)
            assertEquals("native custom", createdOnDisk.fields.getValue("External plugin").content)
            assertTrue(createdOnDisk.fields.getValue("External plugin") is EntryValue.Encrypted)
            assertEntryPayload(original, sentinel, afterMove, afterMove.content.group.entries.single())

            service.deleteGroup(source.id, moved.path).getOrThrow()
            val afterDelete = coldRead(service, source)
            assertEquals(listOf("Container"), service.listGroups(source.id).getOrThrow().map { it.path })
            assertTrue(afterDelete.content.group.groups.single().groups.isEmpty())
            assertEquals(created.identity.entryUuid, afterDelete.content.group.groups.single().entries.single().uuid)
            assertEntryPayload(original, sentinel, afterDelete, afterDelete.content.group.entries.single())
        }
    }

    @Test
    fun crossVaultLegacyGroupMovePreservesCompleteTreeAndHistoryOnlyAttachments() = runBlocking {
        withVaults { service, source, target ->
            val original = reopen(source)
            val originalGroup = original.content.group.groups.single()
            val originalTarget = reopen(target)
            val sourceSentinel = original.content.group.entries.single()
            val targetSentinel = originalTarget.content.group.entries.single()
            val targetParent = service.createGroup(target.id, "Destination").getOrThrow()

            // This public API builds the recursive group/entry snapshot and both payload pools.
            val moved = service.moveGroup(source.id, "Seed", target.id, targetParent.path).getOrThrow()
            assertEquals("Destination/Seed", moved.path)
            assertEquals(originalGroup.uuid.toString(), moved.uuid)
            val sourceAfter = coldRead(service, source)
            val targetAfter = coldRead(service, target)
            assertTrue(service.listGroups(source.id).getOrThrow().isEmpty())
            assertTrue(sourceAfter.content.group.groups.isEmpty())
            assertEntryPayload(original, sourceSentinel, sourceAfter, sourceAfter.content.group.entries.single())
            assertEntryPayload(originalTarget, targetSentinel, targetAfter, targetAfter.content.group.entries.single())

            val copied = targetAfter.content.group.groups.single().groups.single()
            assertEquals(originalGroup.name, copied.name)
            assertEquals(originalGroup.times, copied.times)
            assertTreePayload(original, originalGroup, targetAfter, copied)
            assertEquals(setOf("Destination", "Destination/Seed", "Destination/Seed/Child"),
                service.listGroups(target.id).getOrThrow().map { it.path }.toSet())
            val legacyRows = service.readPasswordEntries(target.id).getOrThrow()
            assertEquals("Destination/Seed", legacyRows.single { it.entryUuid == originalGroup.entries.single().uuid.toString() }.groupPath)
            assertEquals("Destination/Seed/Child", legacyRows.single { it.entryUuid == originalGroup.groups.single().entries.single().uuid.toString() }.groupPath)
        }
    }

    private data class Vault(val id: Long, val file: File)

    private suspend fun withVaults(block: suspend (KeePassKdbxService, Vault, Vault) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val user = Process.myUid() / 100_000
        check(user > 0 && InstrumentationRegistry.getArguments().getString("keepassRefactorUser") == user.toString()) {
            "Requires an explicitly owned secondary user via keepassRefactorUser"
        }
        val context = instrumentation.targetContext
        val startup = withContext(Dispatchers.Default) { SecureStorageStartup.prepareForEntry(context, timeoutMillis = 15_000L) }
        assertTrue("Storage must be prepared before constructing Android services", startup is SecureStartupResult.Ready)
        val security = SecurityManager(context)
        val dao = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
        val service = KeePassKdbxService(context, dao, security)
        val files = mutableListOf<File>()
        val vaults = mutableListOf<Vault>()
        val wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        try {
            for (withTree in listOf(true, false)) {
                val file = File(context.filesDir, "keepass-service-refactor-${UUID.randomUUID()}.kdbx")
                files += file
                file.outputStream().use { seed(withTree).encode(it, contentParser = KeePassXmlContentParser) }
                val id = dao.insertDatabase(LocalKeePassDatabase(name = "Synthetic group extraction fixture", filePath = file.name,
                    encryptedPassword = security.encryptData(PASSWORD)))
                vaults += Vault(id, file)
            }
            block(service, vaults[0], vaults[1])
        } finally {
            for (vault in vaults) {
                KeePassKdbxService.invalidateProcessCache(vault.id)
                dao.deleteDatabaseById(vault.id)
            }
            for (file in files) {
                check(file.canonicalFile.parentFile == context.filesDir.canonicalFile && file.name.startsWith("keepass-service-refactor-"))
                check(!file.exists() || file.delete()) { "Could not remove owned fixture ${file.name}" }
            }
            if (!wasUnlocked) SessionManager.markLocked()
        }
    }

    private fun reopen(vault: Vault): KeePassDatabase = vault.file.inputStream().use {
        KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(PASSWORD)), contentParser = KeePassXmlContentParser)
    }

    private suspend fun coldRead(service: KeePassKdbxService, vault: Vault): KeePassDatabase {
        KeePassKdbxService.invalidateProcessCache(vault.id)
        service.openNativeBrowser(vault.id).getOrThrow()
        return reopen(vault)
    }

    private fun seed(withTree: Boolean): KeePassDatabase {
        val now = Instant.parse("2026-11-10T00:00:00Z")
        val times = TimeData(now.minusSeconds(500), now.minusSeconds(400), now.minusSeconds(300),
            now.minusSeconds(200), now.plusSeconds(86400), expires = true, usageCount = 7)
        val currentBinary = BinaryData.Uncompressed(true, "current synthetic attachment".toByteArray())
        val historyBinary = BinaryData.Uncompressed(false, "history-only synthetic attachment".toByteArray())
        val iconId = UUID.randomUUID()
        fun entry(title: String) = Entry(uuid = UUID.randomUUID(), times = times, fields = EntryFields.of(
            "Title" to EntryValue.Plain(title),
            "UserName" to EntryValue.Plain("fixture@example.invalid"),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic original password")),
            "External plugin field" to EntryValue.Plain("opaque untouched payload"),
            "External protected field" to EntryValue.Encrypted(EncryptedValue.fromString("opaque protected payload")),
        ), tags = listOf("foreign-tag"), customData = mapOf("foreign-entry" to CustomDataValue("entry-state", now)),
            autoType = AutoTypeData(true, defaultSequence = "{USERNAME}{TAB}{PASSWORD}",
                items = listOf(AutoTypeItem("Synthetic Window", "{PASSWORD}{ENTER}"))))
        val current = entry("Moved login").copy(customIconUuid = iconId,
            binaries = listOf(BinaryReference(currentBinary.hash, "current.bin")),
            history = listOf(entry("Historical login").copy(customIconUuid = iconId,
                binaries = listOf(BinaryReference(historyBinary.hash, "history-only.bin")))))
        val tree = Group(uuid = UUID.randomUUID(), name = "Seed", times = times,
            notes = "foreign group notes", tags = listOf("foreign-group"), customIconUuid = iconId,
            enableSearching = GroupOverride.Enabled, enableAutoType = GroupOverride.Disabled,
            defaultAutoTypeSequence = "{USERNAME}{ENTER}",
            customData = mapOf("foreign-group" to CustomDataValue("group-state", now)),
            entries = listOf(current), groups = listOf(Group(uuid = UUID.randomUUID(), name = "Child", times = times,
                entries = listOf(entry("Nested login")))))
        val initial = KeePassDatabase.Ver4x.create("Root", Meta(name = "Synthetic group fixture", recycleBinEnabled = false),
            Credentials.from(EncryptedValue.fromString(PASSWORD)))
        val salt = when (val kdf = initial.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        return initial.copy(header = initial.header.copy(kdfParameters = KdfParameters.Aes(rounds = 100U, seed = salt)))
            .modifyParentGroup { copy(entries = listOf(entry("Unrelated root sentinel")), groups = if (withTree) listOf(tree) else emptyList()) }
            .modifyBinaries { if (withTree) mapOf(currentBinary.hash to currentBinary, historyBinary.hash to historyBinary) else emptyMap() }
            .modifyCustomIcons { if (withTree) mapOf(iconId to CustomIcon(byteArrayOf(1, 2, 4, 8), "Opaque custom icon", now)) else emptyMap() }
    }

    private fun assertTreePayload(before: KeePassDatabase, expected: Group, after: KeePassDatabase, actual: Group) {
        assertEquals(expected.uuid, actual.uuid)
        assertEquals(expected.notes, actual.notes)
        assertEquals(expected.tags, actual.tags)
        assertEquals(expected.customData, actual.customData)
        assertEquals(expected.enableSearching, actual.enableSearching)
        assertEquals(expected.enableAutoType, actual.enableAutoType)
        assertEquals(expected.defaultAutoTypeSequence, actual.defaultAutoTypeSequence)
        assertIcon(before, expected.customIconUuid, after, actual.customIconUuid)
        assertEquals(expected.entries.map { it.uuid }, actual.entries.map { it.uuid })
        expected.entries.zip(actual.entries).forEach { (left, right) -> assertEntryPayload(before, left, after, right) }
        assertEquals(expected.groups.map { it.uuid to it.name }, actual.groups.map { it.uuid to it.name })
        expected.groups.zip(actual.groups).forEach { (left, right) -> assertTreePayload(before, left, after, right) }
    }

    private fun assertEntryPayload(before: KeePassDatabase, expected: Entry, after: KeePassDatabase, actual: Entry) {
        assertEquals(expected.uuid, actual.uuid)
        assertEquals(expected.fields.map { (name, value) -> Triple(name, value.content, value is EntryValue.Encrypted) },
            actual.fields.map { (name, value) -> Triple(name, value.content, value is EntryValue.Encrypted) })
        assertEquals(expected.times, actual.times)
        assertEquals(expected.tags, actual.tags)
        assertEquals(expected.customData, actual.customData)
        assertEquals(expected.autoType, actual.autoType)
        assertEquals(expected.binaries, actual.binaries)
        for (reference in expected.binaries) {
            val left = before.binaries.getValue(reference.hash)
            val right = after.binaries.getValue(reference.hash)
            assertEquals(left.memoryProtection, right.memoryProtection)
            assertArrayEquals(left.getContent(), right.getContent())
        }
        assertIcon(before, expected.customIconUuid, after, actual.customIconUuid)
        assertEquals(expected.history.size, actual.history.size)
        expected.history.zip(actual.history).forEach { (left, right) -> assertEntryPayload(before, left, after, right) }
    }

    private fun assertIcon(before: KeePassDatabase, expected: UUID?, after: KeePassDatabase, actual: UUID?) {
        assertEquals(expected, actual)
        if (expected != null) {
            val left = before.content.meta.customIcons.getValue(expected)
            val right = after.content.meta.customIcons.getValue(expected)
            assertEquals(left.name, right.name)
            assertEquals(left.lastModified, right.lastModified)
            assertArrayEquals(left.data, right.data)
        }
    }

    companion object { private const val PASSWORD = "Synthetic refactor fixture password" }
}

package takagi.ru.monica.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.credentialexchange.*
import takagi.ru.monica.data.*
import takagi.ru.monica.keepass.KeePassFieldChange
import uniffi.mdbx_ffi.*
import java.util.UUID

class DatabaseManagerTransferTest {
    @Test fun keePassSparseAndLegacyDefaultNotesTransferButUnknownEmptyFieldDoesNot() = runBlocking {
        val fixture = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(fixture.context)
            val source = fixture.keepass()
            for (variant in listOf("sparse", "legacy", "favorite", "unknown")) {
                val native = repo.keepass.openNativeBrowser(source.databaseId).getOrThrow()
                val fields = listOf(KeePassFieldChange("Title", "${fixture.prefix}-$variant"),
                    KeePassFieldChange("UserName", ""), KeePassFieldChange("Password", "", true), KeePassFieldChange("URL", ""),
                    KeePassFieldChange("MonicaItemType", "NOTE"), KeePassFieldChange("MonicaItemData", "{\"content\":\"body\"}", true),
                    KeePassFieldChange("Notes", "body"), KeePassFieldChange("MonicaItemNotes", "", true)) + when (variant) {
                    "legacy" -> listOf(KeePassFieldChange("MonicaImagePaths", ""), KeePassFieldChange("MonicaIsFavorite", "false"))
                    "favorite" -> listOf(KeePassFieldChange("MonicaIsFavorite", "true"))
                    "unknown" -> listOf(KeePassFieldChange("Unknown empty custom field", "", true))
                    else -> emptyList()
                }
                val entry = repo.keepass.createNativeEntry(source.databaseId, native.rootGroup.identity.groupUuid,
                    fields, native.sourceRevision.sha256).getOrThrow()
                val target = fixture.mdbx()
                val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(target), setOf(entry.identity.entryUuid.toString()), true)
                assertEquals(result.failures.toString(), if (variant == "unknown") 0 else 1, result.completed)
                val objects = repo.mdbx.nativeBrowser(target.databaseId).objects
                if (variant == "unknown") assertTrue(objects.isEmpty()) else {
                    assertEquals(1, objects.size)
                    val payload = repo.mdbx.nativeObject(target.databaseId, objects.values.single().id).payload
                    assertEquals(variant == "favorite", org.json.JSONObject(payload).getBoolean("is_favorite"))
                }
                assertNotNull(repo.keepass.openNativeBrowser(source.databaseId).getOrThrow().entry(entry.identity))
            }
        } finally { fixture.close() }
    }

    @Test fun nativeFavoriteAndCreationDateTransferWithoutARoomProjection() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = fixture.mdbx(); val repo = DatabaseManagerRepository(fixture.context)
            val path = fixture.db.localMdbxDatabaseDao().getDatabaseById(source.databaseId)!!.filePath
            val logicalId = UUID.randomUUID().toString()
            val payload = org.json.JSONObject().put("kind", "password").put("monica_entry_id", logicalId)
                .put("username", "synthetic").put("password_plain", "synthetic").put("is_favorite", true)
                .put("created_at", 1_700_000_000_000L).toString()
            val id = openVault(path, "Synthetic transfer fixture password", "manager-test").use { vault ->
                vault.createObject(Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId), "login", fixture.prefix, payload, 1u).objectId
            }
            Mdbx2NativeReadSessions.clear()
            assertTrue(fixture.importedPasswords(source).isEmpty())
            val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(ImportDestination.Local), setOf(id), true)
            assertEquals(result.failures.toString(), 1, result.completed)
            val copied = fixture.importedPasswords(ImportDestination.Local).single()
            assertTrue(copied.isFavorite)
            assertEquals(1_700_000_000_000L, copied.createdAt.time)
            assertNotNull(repo.mdbx.nativeBrowser(source.databaseId).objects[id])
        } finally { fixture.close() }
    }

    @Test fun unknownFieldInKnownSchemaStillBlocksPortableMove() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = fixture.mdbx(); val repo = DatabaseManagerRepository(fixture.context)
            val path = fixture.db.localMdbxDatabaseDao().getDatabaseById(source.databaseId)!!.filePath
            val payload = """{"kind":"password","password_plain":"synthetic","future_secret":"must remain"}"""
            val id = openVault(path, "Synthetic transfer fixture password", "manager-test").use { vault ->
                vault.createObject(Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId), "login", fixture.prefix, payload, 1u).objectId
            }
            Mdbx2NativeReadSessions.clear()
            val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(ImportDestination.Local), setOf(id), false)
            assertEquals(0, result.completed)
            assertTrue(result.failures.single().reason.contains("fields unknown"))
            val retained = org.json.JSONObject(repo.mdbx.nativeObject(source.databaseId, id).payload)
            assertEquals(setOf("kind", "password_plain", "future_secret"), retained.keys().asSequence().toSet())
            assertEquals("must remain", retained.getString("future_secret"))
            assertEquals("synthetic", retained.getString("password_plain"))
            assertEquals("password", retained.getString("kind"))
            assertTrue(fixture.importedPasswords(ImportDestination.Local).isEmpty())
        } finally { fixture.close() }
    }

    @Test fun keePassNoteWithUnmappedNativeFieldCannotLoseItInPortableMove() = runBlocking {
        val fixture = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(fixture.context)
            val source = fixture.keepass(); val target = fixture.mdbx()
            val native = repo.keepass.openNativeBrowser(source.databaseId).getOrThrow()
            val entry = repo.keepass.createNativeEntry(source.databaseId, native.rootGroup.identity.groupUuid,
                listOf(KeePassFieldChange("Title", fixture.prefix), KeePassFieldChange("MonicaItemType", "NOTE"),
                    KeePassFieldChange("MonicaItemData", "{\"content\":\"body\"}", true), KeePassFieldChange("Notes", "body"),
                    KeePassFieldChange("UnmappedNativeSecret", "must remain", true)), native.sourceRevision.sha256).getOrThrow()
            val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(target), setOf(entry.identity.entryUuid.toString()), false)
            assertEquals(0, result.completed); assertEquals(1, result.failures.size)
            assertEquals("must remain", repo.keepass.openNativeBrowser(source.databaseId).getOrThrow().entries.single().field("UnmappedNativeSecret")!!.rawValue)
            assertTrue(repo.mdbx.nativeBrowser(target.databaseId).objects.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun sourceChangeBetweenBatchItemsInvalidatesThePortableSnapshot() = runBlocking {
        val fixture = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(fixture.context)
            val source = fixture.mdbx()
            repeat(2) { i -> fixture.passwords.insertPasswordEntry(PasswordEntry(title = "${fixture.prefix}-$i",
                website = "", username = "before", password = fixture.security.encryptData("synthetic"), mdbxDatabaseId = source.databaseId)) }
            val location = DatabaseManagerLocation(source)
            val rows = repo.browse(location).nodes.filter { it.type == MdbxStructureNodeType.ENTRY }
            val secondLogicalId = org.json.JSONObject(repo.mdbx.nativeObject(source.databaseId, rows[1].id).payload).getString("monica_entry_id")
            val result = repo.transfer(location, DatabaseManagerLocation(ImportDestination.Local), rows.map { it.id }.toSet(), false) { done, _ ->
                if (done == 1) {
                    val second = fixture.importedPasswords(source).single { it.replicaGroupId == secondLogicalId }
                    fixture.passwords.updatePasswordEntry(second.copy(username = "updated during transfer"))
                }
            }
            assertEquals(result.failures.toString(), 2, result.completed)
            assertEquals("updated during transfer", fixture.importedPasswords(ImportDestination.Local).single { it.title == rows[1].name }.username)
            assertTrue(repo.mdbx.nativeBrowser(source.databaseId).objects.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun keePassFolderCopyPreservesItsNativeProfile() = runBlocking {
        val fixture = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(fixture.context)
            val source = fixture.keepass(); val target = fixture.keepass()
            val id = repo.createFolder(DatabaseManagerLocation(source), fixture.prefix)
            val native = repo.keepass.openNativeBrowser(source.databaseId).getOrThrow()
            repo.kdbx.updateNativeGroupProperties(source.databaseId, UUID.fromString(id), takagi.ru.monica.keepass.KeePassNativeGroupUpdate(
                notes = "native group notes", tags = listOf("synthetic", "archive"), expanded = false,
                defaultAutoTypeSequence = "{USERNAME}{TAB}{PASSWORD}",
                enableSearching = app.keemobile.kotpass.constants.GroupOverride.Disabled), native.sourceRevision.sha256).getOrThrow()
            val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(target), setOf(id), true)
            assertEquals(result.failures.toString(), 1, result.completed)
            val destination = repo.keepass.openNativeBrowser(target.databaseId).getOrThrow().groups.single { it.name == fixture.prefix }
            assertEquals("native group notes", destination.notes)
            assertEquals(listOf("synthetic", "archive"), destination.tags)
            assertEquals("{USERNAME}{TAB}{PASSWORD}", destination.defaultAutoTypeSequence)
            assertEquals(app.keemobile.kotpass.constants.GroupOverride.Disabled, destination.enableSearching)
            assertFalse(destination.expanded)
        } finally { fixture.close() }
    }

    @Test fun localNoteAttachmentsRoundTripThroughMdbxAndKeePass() = runBlocking {
        val fixture = TransferFixture()
        try {
            val repo = DatabaseManagerRepository(fixture.context)
            val mdbx = fixture.mdbx(); val keepass = fixture.keepass()
            val id = fixture.db.secureItemDao().insertItem(SecureItem(title = fixture.prefix, itemType = ItemType.NOTE,
                notes = "extra notes", itemData = fixture.security.encryptData("{\"content\":\"synthetic note\"}")))
            val facade = takagi.ru.monica.attachments.AttachmentContainer.facade(fixture.context)
            for (i in 1..3) facade.addInlineAttachment(takagi.ru.monica.attachments.facade.AttachmentFacade.InlineUploadRequest(
                takagi.ru.monica.attachments.model.AttachmentOwner.secureItem(id), takagi.ru.monica.attachments.model.AttachmentSource.LOCAL,
                "${fixture.prefix}-$i.${if (i == 3) "png" else "bin"}", if (i == 3) "image/png" else "application/octet-stream",
                ByteArray(i * 99) { (it % 101).toByte() }, true))
            val copied = repo.transfer(DatabaseManagerLocation(ImportDestination.Local), DatabaseManagerLocation(mdbx), setOf("item:$id"), true)
            assertEquals(copied.failures.toString(), 1, copied.completed)
            val native = repo.mdbx.nativeBrowser(mdbx.databaseId).objects.values.single()
            val moved = repo.transfer(DatabaseManagerLocation(mdbx), DatabaseManagerLocation(keepass), setOf(native.id), false)
            assertEquals(moved.failures.toString(), 1, moved.completed)
            assertTrue(repo.mdbx.nativeBrowser(mdbx.databaseId).objects.isEmpty())
            val destination = repo.keepass.openNativeBrowser(keepass.databaseId).getOrThrow().entries.single()
            assertEquals(3, destination.attachments.size)
            assertTrue(destination.attachments.all { !it.isMissing })
            assertEquals("synthetic note", destination.field("Notes")!!.rawValue)
            assertEquals("extra notes", destination.field("MonicaItemNotes")!!.rawValue)
            assertNotNull(fixture.db.secureItemDao().getItemById(id))
        } finally { fixture.close() }
    }

    @Test fun twoNativeFoldersMoveCompletelyWithoutInvalidatingTheNextSelection() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = fixture.mdbx(); val target = fixture.mdbx()
            val repo = DatabaseManagerRepository(fixture.context)
            val path = fixture.db.localMdbxDatabaseDao().getDatabaseById(source.databaseId)!!.filePath
            val folders = (1..2).map { UUID.randomUUID().toString() }
            openVault(path, "Synthetic transfer fixture password", "manager-test").use { vault ->
                folders.forEachIndexed { i, folder ->
                    vault.executeWriteOperation(UUID.randomUUID().toString(), "fixture", listOf(MdbxWriteCommand.CreateProject(folder, "${fixture.prefix}-$i")))
                    vault.createObject(folder, "com.example.future", fixture.prefix, "{\"future\":true}", 9u)
                }
            }
            Mdbx2NativeReadSessions.clear()
            val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(target), folders.toSet(), false)
            assertEquals(result.failures.toString(), 2, result.completed)
            assertTrue(repo.mdbx.nativeBrowser(source.databaseId).nodes.isEmpty())
            val destination = repo.mdbx.nativeBrowser(target.databaseId)
            assertEquals(2, destination.objects.size)
            assertEquals(2, destination.nodes.count { it.type == MdbxStructureNodeType.FOLDER })
        } finally { fixture.close() }
    }

    @Test fun nativePasswordCopyGetsIndependentIdentityAndFolderMetadata() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = fixture.mdbx(); val repo = DatabaseManagerRepository(fixture.context)
            fixture.passwords.insertPasswordEntry(PasswordEntry(title = fixture.prefix, website = "", username = "test",
                password = fixture.security.encryptData("synthetic"), passwordGroupId = "synthetic-group", mdbxDatabaseId = source.databaseId))
            val original = repo.mdbx.nativeBrowser(source.databaseId).objects.values.single()
            val folder = repo.createFolder(DatabaseManagerLocation(source), fixture.prefix + " target")
            val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(source, folder), setOf(original.id), true)
            assertEquals(result.failures.toString(), 1, result.completed)
            val values = repo.mdbx.readStoredEntries(source.databaseId)
            assertEquals(2, values.size)
            assertEquals(2, values.map { it.entryId }.distinct().size)
            val copied = values.single { org.json.JSONObject(it.payloadJson).optString("mdbx_folder_id") == folder }
            val payload = org.json.JSONObject(copied.payloadJson)
            assertEquals("synthetic", payload.getString("password_plain"))
            assertNotEquals("synthetic-group", payload.getString("password_group_id"))
            val physical = repo.mdbx.nativeBrowser(source.databaseId).objects.values.single { it.collectionId == folder }
            val moved = repo.transfer(DatabaseManagerLocation(source, folder), DatabaseManagerLocation(source), setOf(physical.id), false)
            assertEquals(moved.failures.toString(), 1, moved.completed)
            assertTrue(org.json.JSONObject(repo.mdbx.nativeObject(source.databaseId, physical.id).payload).isNull("mdbx_folder_id"))
        } finally { fixture.close() }
    }

    @Test fun unsupportedPortableSchemaKeepsSourceAndDoesNotCreateDestinationRecords() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = fixture.mdbx(); val repo = DatabaseManagerRepository(fixture.context)
            val path = fixture.db.localMdbxDatabaseDao().getDatabaseById(source.databaseId)!!.filePath
            val id = openVault(path, "Synthetic transfer fixture password", "manager-test").use { vault ->
                vault.createObject(Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId), "com.example.future", fixture.prefix, "{\"future\":true}", 8u).objectId
            }
            Mdbx2NativeReadSessions.clear()
            val before = fixture.importedPasswords(ImportDestination.Local).size
            val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(ImportDestination.Local), setOf(id), false)
            assertEquals(0, result.completed); assertEquals(1, result.failures.size)
            assertNotNull(repo.mdbx.nativeBrowser(source.databaseId).objects[id])
            assertEquals(before, fixture.importedPasswords(ImportDestination.Local).size)
        } finally { fixture.close() }
    }

    @Test fun nativeCopyPreservesFutureSchemaUnknownJsonAndAttachmentBytes() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = fixture.mdbx(); val target = fixture.mdbx()
            val repo = DatabaseManagerRepository(fixture.context)
            val path = fixture.db.localMdbxDatabaseDao().getDatabaseById(source.databaseId)!!.filePath
            val payload = """{"credentials":[{"name":"one","passwords":["a","b"],"otp":"TEST"},{"name":"two","passwords":["c"]}],"future":{"number":1.234567890123456789,"array":[true,null,{}]}}"""
            val bytes = ByteArray(23456) { (it % 251).toByte() }
            var objectId = ""
            openVault(path, "Synthetic transfer fixture password", "manager-test").use { vault ->
                val root = Mdbx2VaultSessionExecutor.rootProjectId(vault.info().vaultId)
                val value = vault.createObject(root, "com.example.future", fixture.prefix, payload, 7u)
                objectId = value.objectId
                vault.executeAttachmentBatch(UUID.randomUUID().toString(), listOf(MdbxAttachmentBatchCommand.Create(
                    UUID.randomUUID().toString(), root, objectId, "front.bin", "application/octet-stream", bytes)))
            }
            Mdbx2NativeReadSessions.clear()
            val targetFolder = repo.createFolder(DatabaseManagerLocation(target), fixture.prefix + " folder")
            val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(target, targetFolder), setOf(objectId), true)
            assertEquals(result.failures.toString(), 1, result.completed)
            val copy = repo.mdbx.nativeBrowser(target.databaseId).objects.values.single()
            assertEquals(7u, copy.version)
            assertEquals("com.example.future", copy.type)
            val captured = repo.mdbx.managerCapture(target.databaseId, copy.id)
            val original = repo.mdbx.managerCapture(source.databaseId, objectId)
            try { assertEquals(original.payload, captured.payload); assertTrue(captured.payload.contains("1.234567890123456789")); assertArrayEquals(bytes, captured.assets.single().content) }
            finally { captured.clear(); original.clear() }
            assertNotNull(repo.mdbx.nativeBrowser(source.databaseId).objects[objectId])
            // A same-vault move must migrate attachment ownership atomically too.
            val another = repo.createFolder(DatabaseManagerLocation(target), fixture.prefix + " another")
            val moved = repo.transfer(DatabaseManagerLocation(target, targetFolder), DatabaseManagerLocation(target, another), setOf(copy.id), false)
            assertEquals(moved.failures.toString(), 1, moved.completed)
            val movedValue = repo.mdbx.managerCapture(target.databaseId, copy.id)
            try { assertEquals(another, movedValue.summary.collectionId); assertArrayEquals(bytes, movedValue.assets.single().content) } finally { movedValue.clear() }
        } finally { fixture.close() }
    }

    @Test fun staleNativeSourceCannotBeDeletedAfterCopy() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = fixture.mdbx(); val target = fixture.mdbx()
            val id = fixture.passwords.insertPasswordEntry(PasswordEntry(title = fixture.prefix, website = "", username = "one",
                password = fixture.security.encryptData("synthetic"), mdbxDatabaseId = source.databaseId))
            val repo = DatabaseManagerRepository(fixture.context)
            val sourceId = repo.mdbx.nativeBrowser(source.databaseId).objects.keys.single()
            val capture = repo.mdbx.managerCapture(source.databaseId, sourceId)
            try {
                repo.mdbx.managerCopy(target.databaseId, capture, null)
                repo.mdbx.renameNativeObject(source.databaseId, capture.summary, fixture.prefix + " changed")
                assertTrue(runCatching { repo.mdbx.managerDeleteVerifiedSource(source.databaseId, capture) }.isFailure)
                assertTrue(repo.mdbx.nativeBrowser(source.databaseId).objects.containsKey(sourceId))
            } finally { capture.clear() }
        } finally { fixture.close() }
    }

    @Test fun localPasswordWithAdditionalFieldsCopiesIntoMdbxFolderThenBackToLocal() = runBlocking {
        val fixture = TransferFixture()
        try {
            val target = fixture.mdbx(); val repo = DatabaseManagerRepository(fixture.context)
            val password = PasswordEntry(title = fixture.prefix, website = "https://example.invalid", username = "user",
                password = fixture.security.encryptData("test-password"), authenticatorKey = fixture.security.encryptData("JBSWY3DPEHPK3PXP"),
                notes = "Notes", email = "one@example.invalid", wifiMetadata = "{\"future\":true}")
            val id = fixture.passwords.insertPasswordEntry(password)
            fixture.db.customFieldDao().insert(CustomField(entryId = id, title = "arbitrary", value = "{\"x\":[1,2,null]}", isProtected = true))
            val folder = repo.createFolder(DatabaseManagerLocation(target), fixture.prefix + " folder")
            val copy = repo.transfer(DatabaseManagerLocation(ImportDestination.Local), DatabaseManagerLocation(target, folder), setOf("password:$id"), true)
            assertEquals(copy.failures.toString(), 1, copy.completed)
            val native = repo.mdbx.nativeBrowser(target.databaseId).objects.values.single()
            assertEquals(folder, native.collectionId)
            val back = repo.transfer(DatabaseManagerLocation(target, folder), DatabaseManagerLocation(ImportDestination.Local), setOf(native.id), true)
            assertEquals(back.failures.toString(), 1, back.completed)
            assertEquals(2, fixture.importedPasswords(ImportDestination.Local).size)
        } finally { fixture.close() }
    }

    @Test fun keepassNativeEntriesCopyAcrossDatabasesAndMoveIntoOppositeGroup() = runBlocking {
        val fixture = TransferFixture()
        try {
            val source = fixture.keepass(); val target = fixture.keepass(); val repo = DatabaseManagerRepository(fixture.context)
            val native = repo.keepass.openNativeBrowser(source.databaseId).getOrThrow()
            val entry = repo.keepass.createNativeEntry(source.databaseId, native.rootGroup.identity.groupUuid,
                listOf(KeePassFieldChange("Title", fixture.prefix), KeePassFieldChange("FutureField", "arbitrary", true)), native.sourceRevision.sha256).getOrThrow()
            val folder = repo.createFolder(DatabaseManagerLocation(target), fixture.prefix + " group")
            val result = repo.transfer(DatabaseManagerLocation(source), DatabaseManagerLocation(target, folder), setOf(entry.identity.entryUuid.toString()), false)
            assertEquals(result.failures.toString(), 1, result.completed)
            val copied = repo.keepass.openNativeBrowser(target.databaseId).getOrThrow().entries.single()
            assertEquals("arbitrary", copied.field("FutureField")!!.rawValue)
            assertEquals(folder, copied.parentGroup.groupUuid.toString())
            assertTrue(repo.keepass.openNativeBrowser(source.databaseId).getOrThrow().entries.isEmpty())
        } finally { fixture.close() }
    }
}

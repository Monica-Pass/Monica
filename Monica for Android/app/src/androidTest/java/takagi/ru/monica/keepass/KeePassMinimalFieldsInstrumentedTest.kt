package takagi.ru.monica.keepass

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.ProjectCredentialGroup
import java.util.UUID

/** Issue 177: inspect the serialized original KDBX after ordinary editor-store saves. */
class KeePassMinimalFieldsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val standard = setOf("Title", "UserName", "Password", "URL", "Notes")
    private val defaults = mapOf(
        "MonicaIsFavorite" to "false", "MonicaIsArchived" to "false", "MonicaSortOrder" to "0",
        "MonicaIsGroupCover" to "false", "MonicaCategoryId" to "", "MonicaSsoRefEntryUuid" to "",
        "MonicaBoundNoteUuid" to "", "MonicaBoundNoteId" to "", "MonicaPasskeyBindings" to "",
        "MonicaPasswordGroupId" to "", "MonicaReplicaGroupId" to "",
        "MonicaCustomIconType" to "NONE", "MonicaCustomIconValue" to ""
    )

    @Test fun simpleNativeLoginCreatesOnlyStandardFieldsAndReopens() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, _ ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                var row = store.saveCredentialRows(id, listOf(PasswordEntry(title = "Minimal login",
                    username = "alice", password = "synthetic secret", website = "https://example.test",
                    notes = "Standard note", keepassDatabaseId = id) to emptyList())).single()
                repeat(2) { iteration ->
                    val saved = service.openNativeBrowser(id, true).getOrThrow().entries.single {
                        it.identity.entryUuid.toString() == row.keepassEntryUuid
                    }
                    assertEquals(standard, saved.fields.map { it.name }.toSet())
                    assertEquals("synthetic secret", saved.field("Password")!!.rawValue)
                    assertTrue(saved.field("Password")!!.isProtected)
                    assertEquals("Standard note", saved.field("Notes")!!.rawValue)
                    assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                    if (iteration == 0) {
                        store.refresh(id)
                        row = requireNotNull(store.get(row.id, true))
                        row = store.saveCredentialRows(id, listOf(row.copy(title = "Edited minimal login") to emptyList())).single()
                    }
                }
            }
        }
    }

    @Test fun explicitSaveRemovesOnlyRedundantMetadataAndPreservesReferencesAndNativeData() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, uri ->
            var browser = service.openNativeBrowser(id, true).getOrThrow()
            val target = browser.entries.first()
            val falseRef = "{REF:U@I:${target.identity.entryUuid.toString().replace("-", "")}}"
            val additions = defaults.map { (name, value) -> KeePassFieldChange(name, value) } + listOf(
                KeePassFieldChange("MonicaLocalId", "123"), KeePassFieldChange("UserName", "false"),
                KeePassFieldChange("MonicaIsFavorite", falseRef, true),
                KeePassFieldChange("User empty field", "", true), KeePassFieldChange("User whitespace", "  ", true),
                KeePassFieldChange("MonicaFuturePluginData", "{\"future\":1}", true),
                KeePassFieldChange("_etm_template_uuid", "external-template")
            )
            val replacements = additions.associateBy { it.name }
            service.replaceNativeEntryFields(id, target.identity.entryUuid,
                target.fields.filterNot { it.name in replacements }.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } + replacements.values,
                browser.sourceRevision.sha256).getOrThrow()
            browser = service.openNativeBrowser(id, true).getOrThrow()
            val payload = "Synthetic issue177 attachment".toByteArray()
            service.addNativeAttachment(id, target.identity.entryUuid, "keep.txt", payload, browser.sourceRevision.sha256).getOrThrow()
            val before = service.openNativeBrowser(id, true).getOrThrow()
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                var row = store.rows(id).first().single { it.keepassEntryUuid == target.identity.entryUuid.toString() }
                repeat(2) { iteration ->
                    row = requireNotNull(store.get(row.id, true))
                    val fields = store.fields(row.id).map(CustomFieldDraft::fromCustomField)
                    row = store.saveCredentialRows(id, listOf(row.copy(title = "Sparse edit $iteration") to fields)).single()
                    val after = service.openNativeBrowser(id, true).getOrThrow()
                    val saved = after.entry(target.identity)!!
                    defaults.keys.filterNot { it == "MonicaIsFavorite" }.forEach { assertNull(it, saved.field(it)) }
                    assertEquals(falseRef, saved.field("MonicaIsFavorite")!!.rawValue)
                    assertTrue(saved.field("MonicaIsFavorite")!!.isProtected)
                    assertEquals("false", saved.field("MonicaIsFavorite")!!.displayValue)
                    for (name in listOf("User empty field", "User whitespace", "MonicaFuturePluginData", "_etm_template_uuid", "MonicaLocalId", "Password")) {
                        val oldField = before.entry(target.identity)!!.field(name)!!
                        val newField = saved.field(name)!!
                        // KDBX re-encoding rotates in-memory protection salts; compare
                        // the actual field text and protection policy, not the salt.
                        assertEquals(name, oldField.rawValue, newField.rawValue)
                        assertEquals(name, oldField.displayValue, newField.displayValue)
                        assertEquals(name, oldField.isProtected, newField.isProtected)
                    }
                    assertEquals(before.entry(target.identity)!!.attachments, saved.attachments)
                    assertTrue(saved.history.size > before.entry(target.identity)!!.history.size)
                    before.entries.filter { it.identity != target.identity }.forEach { other ->
                        val next = after.entry(other.identity)!!
                        assertEquals(other.fields.map { Triple(it.name, it.rawValue, it.isProtected) },
                            next.fields.map { Triple(it.name, it.rawValue, it.isProtected) })
                        assertEquals(other.nativeEntry.copy(fields = app.keemobile.kotpass.models.EntryFields.of()),
                            next.nativeEntry.copy(fields = app.keemobile.kotpass.models.EntryFields.of()))
                    }
                    // A fresh read of the encrypted file, rather than the presentation cache.
                    assertTrue(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.isNotEmpty())
                    store.refresh(id)
                }
            }
        }
    }

    @Test fun enabledMetadataSurvivesAndExplicitlyClearingItUsesMissingFieldDefaults() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, _ ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val group = UUID.randomUUID().toString()
                var row = store.saveCredentialRows(id, listOf(PasswordEntry(title = "State",
                    username = "user", password = "secret", website = "", keepassDatabaseId = id,
                    isFavorite = true, isArchived = true, isGroupCover = true, sortOrder = 7, categoryId = 9,
                    passwordGroupId = group, replicaGroupId = "replica:$group", passkeyBindings = "[\"binding\"]",
                    customIconType = "SIMPLE_ICON", customIconValue = "github") to emptyList())).single()
                assertTrue(row.isFavorite && row.isArchived && row.isGroupCover)
                assertEquals(7, row.sortOrder); assertEquals(9L, row.categoryId)
                assertEquals(group, row.passwordGroupId); assertEquals("replica:$group", row.replicaGroupId)
                assertEquals("[\"binding\"]", row.passkeyBindings)
                assertEquals("github", row.customIconValue)
                row = store.saveCredentialRows(id, listOf(requireNotNull(store.get(row.id, true)).copy(
                    isFavorite = false, isArchived = false, isGroupCover = false, sortOrder = 0, categoryId = null,
                    passwordGroupId = null, replicaGroupId = null, passkeyBindings = "",
                    customIconType = "NONE", customIconValue = null) to emptyList())).single()
                val saved = service.openNativeBrowser(id, true).getOrThrow().entries.single { it.identity.entryUuid.toString() == row.keepassEntryUuid }
                assertEquals(standard, saved.fields.map { it.name }.toSet())
                assertFalse(row.isFavorite || row.isArchived || row.isGroupCover)
                assertEquals(0, row.sortOrder); assertNull(row.categoryId); assertNull(row.passwordGroupId)
                assertNull(row.replicaGroupId); assertEquals("", row.passkeyBindings); assertEquals("NONE", row.customIconType)
            }
        }
    }

    @Test fun accountMetadataRetainsNotesIdentitiesOrderAndUnknownKeys() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, _ ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val groups = listOf(ProjectCredentialGroup.Group(username = "personal", note = "test Notiz"),
                    ProjectCredentialGroup.Group(label = "Work", username = "work",
                        passwords = listOf(ProjectCredentialGroup.Password(value = "one"), ProjectCredentialGroup.Password(value = "two"))))
                val project = UUID.randomUUID().toString()
                val rows = ProjectCredentialGroup.rows(groups)
                val saved = store.saveCredentialRows(id, rows.map { row ->
                    val metadata = row.metadata.forProject(project).let {
                        it.copy(raw = JsonObject(it.raw + ("future" to JsonPrimitive(9007199254740993L))))
                    }
                    PasswordEntry(title = "Accounts", username = row.username, password = row.password.value,
                        website = "", keepassDatabaseId = id, passwordGroupId = project, replicaGroupId = project) to
                        ProjectCredentialGroup.put(emptyList(), metadata)
                })
                val disk = service.openNativeBrowser(id, true).getOrThrow()
                val fieldMap = saved.associate { row ->
                    val entry = disk.entries.single { it.identity.entryUuid.toString() == row.keepassEntryUuid }
                    val metadata = requireNotNull(ProjectCredentialGroup.parse(entry.field(ProjectCredentialGroup.FIELD)!!.rawValue))
                    assertEquals(JsonPrimitive(9007199254740993L), metadata.raw["future"])
                    row.id to listOf(CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = metadata.raw.toString()))
                }
                val restored = ProjectCredentialGroup.restore(saved.reversed(), fieldMap)
                assertEquals(groups.map { it.id }, restored.map { it.id })
                assertEquals(groups.map { it.note }, restored.map { it.note })
                assertEquals(listOf("one", "two"), restored.last().passwords.map { it.value })
                assertEquals("Work", restored.last().label)
            }
        }
    }

    @Test fun secureNoteOmitsDefaultsButRetainsEmptyIndependentNoteAndProtectedBody() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, _ ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                var item = store.saveSecure(takagi.ru.monica.data.SecureItem(title = "Sparse note",
                    itemType = takagi.ru.monica.data.ItemType.NOTE, itemData = "{\"content\":\"Body\"}", keepassDatabaseId = id))
                repeat(2) { iteration ->
                    val entry = service.openNativeBrowser(id, true).getOrThrow().entries.single { it.identity.entryUuid.toString() == item.keepassEntryUuid }
                    assertNull(entry.field("MonicaSecureItemId")); assertNull(entry.field("MonicaImagePaths")); assertNull(entry.field("MonicaIsFavorite"))
                    assertEquals("Body", entry.field("Notes")!!.rawValue)
                    assertEquals("", entry.field("MonicaItemNotes")!!.rawValue)
                    assertTrue(entry.field("MonicaItemNotes")!!.isProtected)
                    assertTrue(entry.field("MonicaItemData")!!.isProtected)
                    assertEquals("", item.notes)
                    if (iteration == 0) item = store.saveSecure(item.copy(title = "Edited sparse note"))
                }
            }
        }
    }

    @Test fun legacyProjectionPreservesLocalOnlyMetadataWhenSourceNeverWroteIt() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, _, _ ->
            val source = takagi.ru.monica.utils.KeePassEntryData("Projection", "user", "secret", "", "",
                monicaLocalId = null, entryUuid = UUID.randomUUID().toString(), groupPath = null, groupUuid = null, isInRecycleBin = false)
            val old = PasswordEntry(title = "Projection", username = "user", password = "secret", website = "", keepassDatabaseId = id,
                isFavorite = true, isArchived = true, sortOrder = 9, categoryId = 4, isGroupCover = true,
                passwordGroupId = "group", replicaGroupId = "replica", customIconType = "SIMPLE_ICON", customIconValue = "github",
                boundNoteId = 7, passkeyBindings = "[\"binding\"]")
            val method = model.javaClass.getDeclaredMethod("withKeePassPresentation", PasswordEntry::class.java,
                takagi.ru.monica.utils.KeePassEntryData::class.java).apply { isAccessible = true }
            val updated = method.invoke(model, old, source) as PasswordEntry
            assertEquals(old, updated)
        }
    }

    @Test fun secureFavoriteReferencesPreserveUnchangedFalseAndAllowExplicitTrueToFalse() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, _ ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                for (favorite in listOf(false, true)) {
                    var item = store.saveSecure(takagi.ru.monica.data.SecureItem(title = "REF note $favorite",
                        itemType = takagi.ru.monica.data.ItemType.NOTE, itemData = "{\"content\":\"Body\"}", keepassDatabaseId = id))
                    val before = service.openNativeBrowser(id, true).getOrThrow()
                    val entry = before.entries.single { it.identity.entryUuid.toString() == item.keepassEntryUuid }
                    val reference = "{REF:U@I:${entry.identity.entryUuid.toString().replace("-", "")}}"
                    service.replaceNativeEntryFields(id, entry.identity.entryUuid,
                        entry.fields.filterNot { it.name in setOf("MonicaIsFavorite", "UserName") }
                            .map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } +
                            listOf(KeePassFieldChange("UserName", favorite.toString()), KeePassFieldChange("MonicaIsFavorite", reference, true)),
                        before.sourceRevision.sha256).getOrThrow()
                    store.refresh(id)
                    item = requireNotNull(store.getSecure(item.id, true))
                    assertEquals(favorite, item.isFavorite)
                    item = store.saveSecure(item.copy(isFavorite = false))
                    assertFalse(item.isFavorite)
                    val after = service.openNativeBrowser(id, true).getOrThrow().entry(entry.identity)!!
                    if (favorite) assertNull(after.field("MonicaIsFavorite")) else {
                        assertEquals(reference, after.field("MonicaIsFavorite")!!.rawValue)
                        assertTrue(after.field("MonicaIsFavorite")!!.isProtected)
                    }
                    assertEquals(favorite.toString(), after.field("UserName")!!.rawValue)
                }
            }
        }
    }
}

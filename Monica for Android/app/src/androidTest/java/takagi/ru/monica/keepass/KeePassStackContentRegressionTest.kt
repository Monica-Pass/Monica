package takagi.ru.monica.keepass

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.util.UUID
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.utils.KeePassKdbxService

/** Exercise the same multi-selection command as the Monica password page. */
class KeePassStackContentRegressionTest {
    @Test fun projectCredentialsResaveInOneCommitWithDistinctFieldsAndNewRows() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, uri ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val seed = store.rows(id).first().first()
                val groups = listOf(ProjectCredentialGroup.Group(username = "first account", passwords = listOf(ProjectCredentialGroup.Password(value = "first secret"))),
                    ProjectCredentialGroup.Group(username = "second account", passwords = listOf(ProjectCredentialGroup.Password(value = "second secret"))))
                val project = UUID.randomUUID().toString()
                val metadata = ProjectCredentialGroup.rows(groups)
                val initial = store.saveCredentialRows(id, metadata.mapIndexed { index, value ->
                    seed.copy(id = 0L, keepassEntryUuid = null, title = "Project fixture", passwordGroupId = project,
                        username = value.username, password = value.password.value) to ProjectCredentialGroup.put(
                        listOf(CustomFieldDraft(title = "Own field", value = "Account $index", isProtected = index == 0)), value.metadata.forProject(project))
                })
                val browser = service.openNativeBrowser(id).getOrThrow()
                val attachment = "Project attachment ✓".toByteArray()
                service.addNativeAttachment(id, UUID.fromString(initial.first().keepassEntryUuid), "project.txt", attachment, browser.sourceRevision.sha256).getOrThrow()
                store.refresh(id)
                // The actual editor initially opens only its primary entry.
                val existing = initial.map { requireNotNull(store.get(it.id)) }
                requireNotNull(store.get(existing.first().id, forEdit = true))
                assertTrue(model.prepareNativeEditorSiblings(existing.first().id, existing.map { it.id }))
                val fields = existing.associate { it.id to store.fields(it.id).map(CustomFieldDraft::fromCustomField) }
                val restored = ProjectCredentialGroup.restore(existing, fields) + ProjectCredentialGroup.Group(username = "third account",
                    passwords = listOf(ProjectCredentialGroup.Password(value = "third secret")))
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                fun commits() = resolver.query(uri, arrayOf("commits"), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) }
                val before = commits()
                val done = CompletableDeferred<Long?>()
                withContext(Dispatchers.Main) { model.savePasswordsAcrossTargets(existing.map { it.id }, existing.first().copy(title = "Edited project"),
                    emptyList(), listOf(StorageTarget.KeePass(id, null)), fields.getValue(existing.first().id), projectCredentials = restored,
                    onComplete = { done.complete(it) }) }
                assertNotNull(withTimeout(30000) { done.await() })
                assertEquals(1, commits() - before)
                KeePassKdbxService.invalidateProcessCache(id)
                val disk = service.openNativeBrowser(id).getOrThrow()
                existing.forEachIndexed { index, row ->
                    val record = requireNotNull(disk.entry(KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid))))
                    assertEquals("Account $index", record.field("Own field")!!.rawValue)
                    assertEquals(index == 0, record.field("Own field")!!.isProtected)
                    assertEquals("Edited project", record.title)
                    assertEquals(groups[index].passwords.single().value, record.field("Password")!!.rawValue)
                    assertTrue(record.field("Password")!!.isProtected)
                }
                assertArrayEquals(attachment, disk.entry(KeePassNativeEntryIdentity(id, UUID.fromString(existing.first().keepassEntryUuid)))!!
                    .attachments.single { it.name == "project.txt" }.binary!!.getContent())
                assertTrue(disk.entries.any { it.field("UserName")?.rawValue == "third account" && it.field("Password")?.rawValue == "third secret" })
            }
        }
    }

    @Test fun deniedProjectBatchCannotPartiallySaveAnExistingAccount() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, _, uri ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val rows = store.rows(id).first().take(2).map { requireNotNull(store.get(it.id, forEdit = true)) }
                val groups = rows.map { ProjectCredentialGroup.Group(username = it.username,
                    passwords = listOf(ProjectCredentialGroup.Password(value = it.password, originalEntryId = it.id))) }
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                val before = resolver.openInputStream(uri)!!.use { it.readBytes() }
                resolver.call(uri, "deny-write", uri.toString(), null)
                val done = CompletableDeferred<Long?>()
                withContext(Dispatchers.Main) { model.savePasswordsAcrossTargets(rows.map { it.id }, rows.first().copy(title = "Denied project"),
                    emptyList(), listOf(StorageTarget.KeePass(id, null)), projectCredentials = groups, onComplete = { done.complete(it) }) }
                assertNull(withTimeout(30000) { done.await() })
                assertArrayEquals(before, resolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        }
    }

    @Test fun copiedContentPublishesAfterSavingAnotherCredentialInSameFile() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, _ ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val row = requireNotNull(store.get(store.rows(id).first().first().id, forEdit = true))
                val fields = store.fields(row.id).map(CustomFieldDraft::fromCustomField)
                store.save(id, listOf(row.copy(title = "Initial content save")), mapOf(row.id to fields))
                store.save(id, listOf(row.copy(id = 0, keepassEntryUuid = null, title = "Sibling credential")), mapOf(0L to fields))
                val wallet = EmbeddedWalletContent.create(SecureItem(itemType = ItemType.DOCUMENT,
                    title = "Synthetic document", itemData = "{\"documentNumber\":\"EX1234567\"}"))
                val finalFields = EmbeddedWalletContent.put(fields, wallet)
                model.publishEmbeddedContent(row.id, DeferredEmbeddedContentSave.Commit(finalFields, null))
                KeePassKdbxService.invalidateProcessCache(id)
                val browser = service.openNativeBrowser(id).getOrThrow()
                val saved = requireNotNull(browser.entry(KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid))))
                assertEquals(wallet.encode(), saved.field(EmbeddedWalletContent.fieldName(wallet.kind))!!.rawValue)
                assertTrue(saved.field(EmbeddedWalletContent.fieldName(wallet.kind))!!.isProtected)
                assertEquals("Initial content save", saved.title)
                assertTrue(browser.entries.any { it.title == "Sibling credential" })
            }
        }
    }

    @Test fun copiedContentRejectsChangedDestinationEvenAfterListRefresh() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, uri ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val row = requireNotNull(store.get(store.rows(id).first().first().id, forEdit = true))
                val fields = store.fields(row.id).map(CustomFieldDraft::fromCustomField)
                store.save(id, listOf(row), mapOf(row.id to fields))
                val browser = service.openNativeBrowser(id).getOrThrow()
                val record = requireNotNull(browser.entry(KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid))))
                service.replaceNativeEntryFields(id, record.identity.entryUuid,
                    record.fields.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } + KeePassFieldChange("Other writer", "retained", true),
                    browser.sourceRevision.sha256).getOrThrow()
                store.refresh(id)
                assertFalse(model.prepareNativeEditorSiblings(row.id, listOf(row.id)))
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                val before = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertTrue(runCatching { model.publishEmbeddedContent(row.id, DeferredEmbeddedContentSave.Commit(fields, null)) }.isFailure)
                assertArrayEquals(before, resolver.openInputStream(uri)!!.use { it.readBytes() })
                service.lockDatabase(id)
                assertTrue(runCatching { model.publishEmbeddedContent(row.id, DeferredEmbeddedContentSave.Commit(fields, null)) }.isFailure)
                assertArrayEquals(before, resolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        }
    }

    @Test fun stackModesCommitTheWholeSelectionOnceAndSurviveColdReopen() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val selected = store.rows(id).first().take(2)
                val ids = selected.map { it.id }
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                fun commits() = resolver.query(uri, arrayOf("commits"), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) }
                // Existing editor drafts are a normal precondition after editing either password.
                selected.forEach { requireNotNull(store.get(it.id, forEdit = true)) }
                for (mode in listOf(PasswordViewModel.ManualStackMode.STACK,
                    PasswordViewModel.ManualStackMode.NEVER_STACK, PasswordViewModel.ManualStackMode.AUTO_STACK,
                    PasswordViewModel.ManualStackMode.STACK)) {
                    val before = commits()
                    assertEquals(2, model.applyManualStackMode(ids, mode))
                    assertEquals("A KDBX selection must be one atomic file save", 1, commits() - before)
                    KeePassKdbxService.invalidateProcessCache(id)
                    val browser = service.openNativeBrowser(id, true).getOrThrow()
                    val records = selected.map { browser.entry(KeePassNativeEntryIdentity(id, UUID.fromString(it.keepassEntryUuid)))!! }
                    when (mode) {
                        PasswordViewModel.ManualStackMode.STACK -> {
                            val groups = records.map { it.field("__monica_manual_stack_group")?.rawValue }
                            assertNotNull(groups.first()); assertEquals(1, groups.distinct().size)
                            assertTrue(records.all { it.field("__monica_no_stack") == null })
                        }
                        PasswordViewModel.ManualStackMode.NEVER_STACK -> assertTrue(records.all {
                            it.field("__monica_no_stack")?.rawValue == "1" && it.field("__monica_manual_stack_group") == null })
                        PasswordViewModel.ManualStackMode.AUTO_STACK -> assertTrue(records.all {
                            it.field("__monica_no_stack") == null && it.field("__monica_manual_stack_group") == null })
                    }
                    assertEquals(selected.map { it.password }, records.map { it.field("Password")!!.displayValue })
                    assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                }
                // A metadata action must not silently authorise an old editor to overwrite it.
                val beforeStaleEdit = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertTrue(runCatching { store.save(id, listOf(selected.first().copy(title = "Stale edit")), emptyMap()) }.isFailure)
                assertArrayEquals(beforeStaleEdit, resolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        }
    }

    @Test fun deniedBatchStackDoesNotPersistAnyMember() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, _, id, _, uri ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val ids = store.rows(id).first().take(3).map { it.id }
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                val before = resolver.openInputStream(uri)!!.use { it.readBytes() }
                resolver.call(uri, "deny-write", uri.toString(), null)
                assertTrue(runCatching { model.applyManualStackMode(ids, PasswordViewModel.ManualStackMode.STACK) }.isFailure)
                assertArrayEquals(before, resolver.openInputStream(uri)!!.use { it.readBytes() })
                assertTrue(ids.all { store.fields(it).none { field -> field.title.startsWith("__monica_") } })
            }
        }
    }

    @Test fun externalRevisionRejectsStackWithoutOverwritingAnotherWriter() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, uri ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val ids = store.rows(id).first().take(2).map { it.id }
                val browser = service.openNativeBrowser(id).getOrThrow()
                val record = browser.entries.first()
                service.replaceNativeEntryFields(id, record.identity.entryUuid,
                    record.fields.map { KeePassFieldChange(it.name, it.rawValue, it.isProtected) } + KeePassFieldChange("Other writer", "retained", true),
                    browser.sourceRevision.sha256).getOrThrow()
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                val before = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertTrue(runCatching { model.applyManualStackMode(ids, PasswordViewModel.ManualStackMode.STACK) }.isFailure)
                assertArrayEquals(before, resolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        }
    }

    @Test fun lockingOrRemovingASelectedIdentityRejectsWholeBatch() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, uri ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val ids = store.rows(id).first().take(2).map { it.id }
                val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                val before = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertTrue(runCatching { store.applyManualStackMode(ids + Long.MAX_VALUE, UUID.randomUUID().toString(), false) }.isFailure)
                assertArrayEquals(before, resolver.openInputStream(uri)!!.use { it.readBytes() })
                service.lockDatabase(id)
                assertTrue(runCatching { model.applyManualStackMode(ids, PasswordViewModel.ManualStackMode.STACK) }.isFailure)
                assertArrayEquals(before, resolver.openInputStream(uri)!!.use { it.readBytes() })
            }
        }
    }

    @Test fun nativeCustomFieldsRoundTripWithoutLosingUnknownOrProtectedFields() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, _, _ ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val row = store.rows(id).first().first()
                val fields = listOf(CustomFieldDraft(title = "Fixture text", value = "原样\nUnicode ✓"),
                    CustomFieldDraft(title = "Fixture hidden", value = "synthetic secret", isProtected = true),
                    CustomFieldDraft(title = "Other plugin field", value = "untouched"))
                model.saveCustomFieldsForEntry(row.id, fields)
                val browser = service.openNativeBrowser(id, true).getOrThrow()
                val record = browser.entry(KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid)))!!
                fields.forEach { expected ->
                    val actual = requireNotNull(record.field(expected.title))
                    assertEquals(expected.value, actual.rawValue); assertEquals(expected.isProtected, actual.isProtected)
                }
                assertEquals(row.password, record.field("Password")!!.displayValue)
            }
        }
    }

    @Test fun everyAddContentEncodingSurvivesEditAndColdReopenInOriginalKdbx() = runBlocking {
        KeePassDeleteInvestigationTest().scenario { model, service, id, room, _ ->
            model.holdNativeKeePassRead(id).use {
                val store = requireNotNull(model.monicaKeePassStore)
                store.refresh(id)
                val row = store.rows(id).first().first()
                var fields = listOf(CustomFieldDraft(title = "Fixture text", value = "text\nUnicode ✓"),
                    CustomFieldDraft(title = "Fixture hidden", value = "  hidden secret  ", isProtected = true),
                    CustomFieldDraft(title = "Other plugin", value = "untouched", isProtected = true))
                val blocks = PasswordContentBlocks.Kind.entries.map { kind ->
                    PasswordContentBlocks.create(kind).edited("Fixture $kind", PasswordContentBlocks.editableKeys(kind)
                        .associateWith { key -> if (key == "notes") "分块测试 ✓".repeat(900) else "  synthetic $kind $key  " })
                }
                blocks.forEach { fields = PasswordContentBlocks.put(fields, it) }
                val wallets = EmbeddedWalletContent.Kind.entries.map { kind ->
                    EmbeddedWalletContent.create(SecureItem(itemType = kind.itemType, title = "Fixture $kind",
                        itemData = "{\"synthetic\":\"原样 ✓\",\"unknownFuture\":{\"keep\":true}}", notes = "wallet note"))
                }
                wallets.forEach { fields = EmbeddedWalletContent.put(fields, it) }
                fields = EntryContentFields.update(fields, "CONTACT", "passportNumber", "EX1234567", true)
                fields = EntryContentFields.update(fields, "ADDRESS", "apartment", "Suite 9", false)
                fields = EntryContentFields.withOrder(fields, listOf("NOTES", "DOCUMENT", "PAYMENT", "ADDRESS", "CUSTOM_FIELDS", "ATTACHMENTS") + blocks.map { PasswordContentBlocks.token(it.id) })
                val rich = row.copy(notes = "Original note\n第二行", email = "alice@example.test", phone = "1234567890",
                    addressLine = "Fixture street", city = "Fixture city", state = "Fixture state", zipCode = "12345", country = "CN",
                    creditCardNumber = "4242424242424242", creditCardHolder = "SYNTHETIC", creditCardExpiry = "09/30", creditCardCVV = "123",
                    authenticatorKey = "JBSWY3DPEHPK3PXP")
                store.save(id, listOf(rich), mapOf(row.id to fields))
                var browser = service.openNativeBrowser(id, true).getOrThrow()
                val identity = KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid))
                val bytes = "Synthetic attachment\n原样 ✓".toByteArray()
                service.addNativeAttachment(id, identity.entryUuid, "fixture.txt", bytes, browser.sourceRevision.sha256).getOrThrow()
                store.refresh(id)
                val edited = requireNotNull(store.get(row.id, forEdit = true))
                val currentFields = store.fields(row.id).map(CustomFieldDraft::fromCustomField)
                store.save(id, listOf(edited.copy(title = "All content edited")), mapOf(row.id to currentFields))
                val anotherId = store.rows(id).first().first { it.id != row.id }.id
                assertEquals(2, model.applyManualStackMode(listOf(row.id, anotherId), PasswordViewModel.ManualStackMode.STACK))
                KeePassKdbxService.invalidateProcessCache(id)
                browser = service.openNativeBrowser(id, true).getOrThrow()
                val record = requireNotNull(browser.entry(identity))
                fields.forEach { expected ->
                    val actual = requireNotNull(record.field(expected.title)) { "Missing field ${expected.title}" }
                    assertEquals(expected.value, actual.rawValue); assertEquals(expected.isProtected, actual.isProtected)
                }
                val reloadedFields = record.fields.map { CustomFieldDraft(title = it.name, value = it.rawValue, isProtected = it.isProtected) }
                assertEquals(blocks.map { it.raw }, PasswordContentBlocks.read(reloadedFields).map { it.block!!.raw })
                wallets.forEach { wallet -> assertEquals(wallet.raw, (EmbeddedWalletContent.read(record.field(EmbeddedWalletContent.fieldName(wallet.kind))!!.rawValue)
                    as EmbeddedWalletContent.ReadResult.Available).snapshot.raw) }
                assertEquals("All content edited", record.title)
                assertEquals(row.password, record.field("Password")!!.displayValue)
                assertTrue(record.field("Password")!!.isProtected)
                assertEquals("Original note\n第二行", record.field("Notes")!!.rawValue)
                assertEquals("alice@example.test", record.field("Email")!!.rawValue)
                assertEquals("4242424242424242", record.field("Card Number")!!.rawValue)
                assertTrue(record.field("Card Number")!!.isProtected)
                assertEquals("123", record.field("Card CVV")!!.rawValue)
                assertTrue(record.field("Card CVV")!!.isProtected)
                assertArrayEquals(bytes, record.attachments.single { it.name == "fixture.txt" }.binary!!.getContent())
                assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
            }
        }
    }
}

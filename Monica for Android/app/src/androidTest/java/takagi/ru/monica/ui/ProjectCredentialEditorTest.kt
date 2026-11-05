package takagi.ru.monica.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import takagi.ru.monica.ui.components.DetailGroupItem
import takagi.ru.monica.ui.components.LocalCompactCredentialFields
import takagi.ru.monica.R
import kotlin.math.abs
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.ProjectCredentialGroup
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File
import kotlinx.serialization.json.*
import takagi.ru.monica.data.model.EntryContentFields

class ProjectCredentialEditorTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var security: SecurityManager
    private var editId by mutableStateOf<Long?>(null)
    private var editorEpoch by mutableStateOf(0)
    private var savedId: Long? = null
    private var detail by mutableStateOf(false)
    private var previousContent = false

    @Before fun setup() {
        runBlocking {
            val settings = takagi.ru.monica.utils.SettingsManager(context)
            previousContent = settings.settingsFlow.first().passwordContentEditorEnabled
            settings.updatePasswordContentEditorEnabled(true)
        }
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()), security,
            customFieldRepository = CustomFieldRepository(db.customFieldDao()), strings = AppLocaleStringResolver(context))
    }
    @After fun cleanup() {
        model.viewModelScope.cancel(); db.close()
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(previousContent) }
    }
    private fun prefix(value: String) = SemanticsMatcher("tag starts with $value") { node ->
        node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(value)
    }
    private fun scroll(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.onNodeWithTag("password_content_editor").performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun save() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30000) { savedId != null }
    }
    private fun showSimpleEditor() {
        compose.setContent {
            CompositionLocalProvider(LocalUiSecurityManager provides security) {
                MaterialTheme { key(editId, editorEpoch) {
                    AddEditPasswordScreen(model, passwordId = editId, initialStorageExplicit = true,
                        initialDraft = if (editId == null) AddEditPasswordInitialDraft(title = "Credential note",
                            username = "demo", password = "synthetic-password") else null,
                        onSaveCompleted = { savedId = it }, onNavigateBack = {})
                } }
            }
        }
    }
    @Test fun urlRulesMoveWithRowsAndReopenAfterSaving() {
        showSimpleEditor()
        scroll(hasTestTag("url_row_0")).performTextReplacement("https://prod.example.com")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        fun choose(index: Int, mode: String) {
            scroll(hasTestTag("url_actions_$index")).performClick()
            compose.onNodeWithTag("url_match_action_$index").performClick()
            compose.onNodeWithTag("url_match_$mode").performScrollTo().performClick()
            compose.onNodeWithTag("url_match_$mode").assertIsSelected()
            // Compose can be idle before the separate dialog window finishes its platform fade.
            compose.waitForIdle()
            Thread.sleep(350)
            if (mode == "HOST") File(context.filesDir, "url-match-dialog.png").outputStream().use {
                requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("url_match_confirm").performClick()
        }
        choose(0, "HOST")
        scroll(hasTestTag("url_add")).performClick()
        scroll(hasTestTag("url_row_1")).performTextReplacement("https://accounts.example.com")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        choose(1, "BASE_DOMAIN")
        scroll(hasTestTag("url_actions_1")).performClick()
        compose.waitForIdle()
        Thread.sleep(350)
        File(context.filesDir, "url-action-menu.png").outputStream().use {
            requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("url_up_1").performClick()
        scroll(hasTestTag("url_row_0")).assertTextContains("https://accounts.example.com")
        save()
        val id = requireNotNull(savedId)
        fun rules() = takagi.ru.monica.data.model.PasswordUrlRules.read(runBlocking {
            model.getCustomFieldsByEntryIdSync(id)
        }.map(CustomFieldDraft::fromCustomField))
        assertEquals(listOf("BASE_DOMAIN", "HOST"), rules()!!.map { it.mode.name })
        compose.runOnIdle { editId = id; savedId = null }
        scroll(hasTestTag("url_actions_0")).performClick()
        compose.onNodeWithTag("url_match_action_0").performClick()
        compose.onNodeWithTag("url_match_BASE_DOMAIN").assertIsSelected()
        compose.onNodeWithTag("url_match_DEFAULT").performClick()
        compose.onNodeWithTag("url_match_confirm").performClick()
        choose(1, "DEFAULT")
        save()
        assertNull(rules())
    }

    @Test fun singleCredentialNoteSavesReopensAndClearsWithoutPageNotes() {
        showSimpleEditor()
        scroll(hasTestTag("password_editor_credential_note")).performTextReplacement("Only this credential\nSecond line")
        save()
        val id = requireNotNull(savedId)
        val row = runBlocking { requireNotNull(db.passwordEntryDao().getPasswordEntryById(id)) }
        val fields = runBlocking { model.getCustomFieldsByEntryIdSync(id) }.map(CustomFieldDraft::fromCustomField)
        assertEquals("", row.notes)
        assertEquals("Only this credential\nSecond line", ProjectCredentialGroup.read(fields)?.note)
        compose.runOnIdle { editId = id; savedId = null }
        scroll(hasTestTag("password_editor_credential_note")).assertTextContains("Only this credential\nSecond line")
            .performTextClearance()
        save()
        val updated = runBlocking { model.getCustomFieldsByEntryIdSync(id) }.map(CustomFieldDraft::fromCustomField)
        assertEquals("", ProjectCredentialGroup.read(updated)?.note)
    }
    @Test fun localPrimaryPasswordsSaveReopenAndEditSecondWithoutOtp() {
        checkLocalPrimaryPasswordRoundTrip(withCredentialNote = false)
    }

    @Test fun repeatedLocalMetadataSavesUnchangedThenEditsAndReopens() = checkRepeatedMetadata(null)
    @Test fun repeatedLocalMetadataRetainsLegacyOtpOnlyOnFirstRow() = checkRepeatedMetadata(0)
    @Test fun repeatedLocalMetadataRetainsLegacyOtpOnlyOnSecondRow() = checkRepeatedMetadata(1)

    @Test fun conflictingMetadataCopiesBlockSaveAndKeepOriginalRows() {
        val row = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(username = "synthetic"))).single()
        val originalFields = listOf(CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = row.metadata.raw.toString()),
            CustomFieldDraft(title = ProjectCredentialGroup.FIELD,
                value = JsonObject(row.metadata.raw + ("extension" to JsonPrimitive("conflict"))).toString()))
        val id = runBlocking {
            db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Conflict fixture", username = "synthetic",
                password = security.encryptData("unchanged"), website = "")).also {
                CustomFieldRepository(db.customFieldDao()).saveFieldsForEntry(it, originalFields)
            }
        }
        val before = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }
        val fieldsBefore = runBlocking { model.getCustomFieldsByEntryIdSync(id) }
        editId = id
        showSimpleEditor()
        scroll(hasTestTag("password_editor_value_0")).assertTextContains("unchanged")
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitForIdle()
        assertNull(savedId)
        assertEquals(before, runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() })
        assertEquals(fieldsBefore, runBlocking { model.getCustomFieldsByEntryIdSync(id) })
    }

    private fun checkRepeatedMetadata(otpIndex: Int?) {
        val project = java.util.UUID.randomUUID().toString()
        val orderToken = ProjectCredentialGroup.token(java.util.UUID.randomUUID().toString())
        val values = ProjectCredentialGroup.rows(listOf(ProjectCredentialGroup.Group(username = "synthetic-user",
            note = "Account note", passwords = listOf(ProjectCredentialGroup.Password(value = "first-before"),
                ProjectCredentialGroup.Password(value = "second-before")))))
        val otp = "JBSWY3DPEHPK3PXP"
        val ids = runBlocking { values.mapIndexed { index, row ->
            val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = "Repeated metadata fixture",
                username = row.username, password = security.encryptData(row.password.value), website = "",
                notes = "Page note", passwordGroupId = project, replicaGroupId = project,
                authenticatorKey = if (otpIndex == index) security.encryptData(otp) else ""))
            val raw = JsonObject(row.metadata.forProject(project).raw + ("extension" to JsonPrimitive(9007199254740993L)))
            CustomFieldRepository(db.customFieldDao()).saveFieldsForEntry(id, listOf(
                CustomFieldDraft(title = EntryContentFields.ORDER, value = orderToken),
                CustomFieldDraft(title = EntryContentFields.ORDER, value = orderToken, isProtected = true),
                CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = raw.toString()),
                CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = raw.toString(), isProtected = true),
                CustomFieldDraft(title = "Repeated user field", value = "first"),
                CustomFieldDraft(title = "Repeated user field", value = "second", isProtected = true)))
            id
        } }
        editId = ids.first()
        showSimpleEditor()
        scroll(hasTestTag("password_editor_value_1")).assertTextContains("second-before")
        save()
        fun assertStored(second: String) {
            val stored = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.sortedBy { it.id }
            assertEquals(ids, stored.map { it.id })
            assertEquals(listOf("first-before", second), stored.map { security.decryptData(it.password) })
            assertEquals(listOf(0, 1).map { if (it == otpIndex) otp else "" }, stored.map {
                security.decryptDataIfMonicaCiphertext(it.authenticatorKey) })
            assertTrue(stored.all { it.passwordGroupId == project && it.username == "synthetic-user" && it.notes == "Page note" })
            runBlocking { ids.forEachIndexed { index, id ->
                val fields = model.getCustomFieldsByEntryIdSync(id).map(CustomFieldDraft::fromCustomField)
                assertEquals(1, fields.count { it.title == ProjectCredentialGroup.FIELD })
                assertEquals(1, fields.count { it.title == EntryContentFields.ORDER })
                assertTrue(fields.single { it.title == ProjectCredentialGroup.FIELD }.isProtected)
                assertTrue(fields.single { it.title == EntryContentFields.ORDER }.isProtected)
                assertTrue(orderToken in EntryContentFields.order(fields))
                assertEquals(listOf("first", "second"), fields.filter { it.title == "Repeated user field" }.map { it.value })
                val metadata = requireNotNull(ProjectCredentialGroup.read(fields))
                assertEquals(values[index].metadata.passwordId, metadata.passwordId)
                assertEquals(values[index].metadata.groupId, metadata.groupId)
                assertEquals("Account note", metadata.note)
                assertEquals(JsonPrimitive(9007199254740993L), metadata.raw["extension"])
            } }
        }
        assertStored("second-before")
        compose.runOnIdle { editorEpoch++; savedId = null }
        scroll(hasTestTag("password_editor_value_1")).performTextReplacement("second-after")
        save()
        assertStored("second-after")
        compose.runOnIdle { editorEpoch++; savedId = null }
        scroll(hasTestTag("password_editor_value_1")).assertTextContains("second-after")
        save()
        assertStored("second-after")
    }

    @Test fun localPrimaryPasswordsWithMetadataSaveReopenAndEditSecondWithoutOtp() {
        checkLocalPrimaryPasswordRoundTrip(withCredentialNote = true)
    }

    private fun checkLocalPrimaryPasswordRoundTrip(withCredentialNote: Boolean) {
        showSimpleEditor()
        if (withCredentialNote) {
            scroll(hasTestTag("password_editor_credential_note")).performTextReplacement("Synthetic account note")
        }
        scroll(hasTestTag("password_editor_add_password")).performClick()
        scroll(hasTestTag("password_editor_value_1")).performTextReplacement("second-before")
        save()
        val primaryId = requireNotNull(savedId)
        val before = runBlocking { db.passwordEntryDao().getAllPasswordEntries().first() }.sortedBy { it.id }
        assertEquals(2, before.size)
        assertTrue(before.all { it.isLocalOnlyEntry() })
        assertEquals(listOf("synthetic-password", "second-before"), before.map { security.decryptData(it.password) })
        val metadataBefore = runBlocking { model.getCustomFieldsByEntryIds(before.map { it.id }) }
        assertEquals(withCredentialNote, metadataBefore.values.flatten().any { it.title == ProjectCredentialGroup.FIELD })
        compose.runOnIdle { editId = primaryId; savedId = null }
        scroll(hasTestTag("password_editor_value_1")).performTextReplacement("second-after")
        save()
        val after = runBlocking { db.passwordEntryDao().getAllPasswordEntries().first() }.sortedBy { it.id }
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.passwordGroupId }, after.map { it.passwordGroupId })
        assertEquals(before.map { it.username }, after.map { it.username })
        assertEquals(listOf("synthetic-password", "second-after"), after.map { security.decryptData(it.password) })
        if (withCredentialNote) {
            val metadataAfter = runBlocking { model.getCustomFieldsByEntryIds(after.map { it.id }) }
            assertEquals(metadataBefore.mapValues { (_, fields) -> fields.map { kotlinx.serialization.json.Json.parseToJsonElement(it.value) } },
                metadataAfter.mapValues { (_, fields) -> fields.map { kotlinx.serialization.json.Json.parseToJsonElement(it.value) } })
        }
    }

    @Test fun legacyLocalProjectWithoutMetadataSavesWithoutEdits() {
        checkLegacyLocalProjectSave(withMetadata = false)
    }

    @Test fun legacyV1LocalProjectWithoutOptionalMetadataSavesWithoutEdits() {
        checkLegacyLocalProjectSave(withMetadata = true)
    }

    private fun checkLegacyLocalProjectSave(withMetadata: Boolean) {
        val legacyProjectId = java.util.UUID.randomUUID().toString()
        val groups = listOf(ProjectCredentialGroup.Group(username = "legacy-demo", passwords = listOf(
            ProjectCredentialGroup.Password(value = "legacy-first"),
            ProjectCredentialGroup.Password(value = "legacy-second"))))
        val values = ProjectCredentialGroup.rows(groups)
        val ids = runBlocking {
            values.map { row ->
                val id = db.passwordEntryDao().insertPasswordEntry(PasswordEntry(
                    title = "Synthetic legacy local project", username = row.username,
                    password = security.encryptData(row.password.value), website = "",
                    replicaGroupId = legacyProjectId))
                if (withMetadata) {
                    val oldJson = kotlinx.serialization.json.JsonObject(row.metadata.raw - "projectId" - "note")
                    CustomFieldRepository(db.customFieldDao()).saveFieldsForEntry(id,
                        listOf(CustomFieldDraft(title = ProjectCredentialGroup.FIELD, value = oldJson.toString())))
                }
                id
            }
        }
        editId = ids.first()
        showSimpleEditor()
        scroll(hasTestTag("password_editor_value_1")).assertExists()
        save()
        val after = runBlocking { db.passwordEntryDao().getAllPasswordEntries().first() }.sortedBy { it.id }
        assertEquals(ids, after.map { it.id })
        assertTrue(after.all { it.isLocalOnlyEntry() && it.username == "legacy-demo" })
        assertTrue(after.all { it.title == "Synthetic legacy local project" && it.authenticatorKey.isEmpty() })
        assertEquals(listOf("legacy-first", "legacy-second"), after.map { security.decryptData(it.password) })
        if (withMetadata) {
            val fields = runBlocking { model.getCustomFieldsByEntryIds(ids) }
            assertEquals(values.map { it.metadata.passwordId }, ids.map { id ->
                requireNotNull(ProjectCredentialGroup.read(fields.getValue(id).map(CustomFieldDraft::fromCustomField))).passwordId
            })
        }
    }

    @Test fun deletingStoredAuthenticatorThenSavingLocalMultiPasswordAccountKeepsBothPasswords() {
        checkDeleteAuthenticatorThenSave(virtual = false)
    }

    @Test fun deletingVirtualAuthenticatorThenSavingLocalMultiPasswordAccountKeepsBothPasswords() {
        checkDeleteAuthenticatorThenSave(virtual = true)
    }

    private fun checkDeleteAuthenticatorThenSave(virtual: Boolean) {
        showSimpleEditor()
        scroll(hasTestTag("password_editor_credential_note")).performTextReplacement("Account note")
        scroll(hasTestTag("password_editor_add_password")).performClick()
        scroll(hasTestTag("password_editor_value_1")).performTextReplacement("second-password")
        scroll(hasTestTag("password_otp_secret")).performTextReplacement("JBSWY3DPEHPK3PXP")
        save()
        val primaryId = requireNotNull(savedId)
        val passwords = PasswordRepository(db.passwordEntryDao())
        val items = SecureItemRepository(db.secureItemDao())
        val before = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.sortedBy { it.id }
        assertEquals(2, before.size)
        assertTrue(before.all { it.authenticatorKey.isNotEmpty() })
        val otp = requireNotNull(takagi.ru.monica.util.TotpDataResolver.fromAuthenticatorKey(
            security.decryptDataIfMonicaCiphertext(before.first().authenticatorKey))).copy(boundPasswordId = primaryId)
        val totpModel = takagi.ru.monica.viewmodel.TotpViewModel(items, passwords,
            securityManager = security, strings = AppLocaleStringResolver(context))
        try {
            val target = runBlocking {
                if (virtual) {
                    kotlinx.coroutines.withTimeout(10000) { totpModel.allParsedTotpItems.first {
                        it.any { value -> value.item.id == -primaryId }
                    } }.first { it.item.id == -primaryId }.item
                } else {
                    val id = items.insertItem(SecureItem(title = "Bound account OTP", itemType = ItemType.TOTP,
                        itemData = security.encryptDataLegacyCompat(kotlinx.serialization.json.Json.encodeToString(
                            takagi.ru.monica.data.model.TotpData.serializer(), otp))))
                    requireNotNull(items.getItemById(id))
                }
            }
            totpModel.deleteTotpItem(target)
            runBlocking {
                kotlinx.coroutines.withTimeout(10000) {
                    if (virtual) passwords.getAllPasswordEntries().first { rows ->
                        rows.first { it.id == primaryId }.authenticatorKey.isEmpty()
                    } else db.secureItemDao().getItemsByType(ItemType.TOTP).first { it.isEmpty() }
                }
            }
            compose.runOnIdle { editId = primaryId; savedId = null }
            scroll(hasTestTag("password_editor_value_1")).assertExists()
            // Exercise the actual editor save guard, not a seeded metadata conflict.
            save()
            val after = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }.sortedBy { it.id }
            assertEquals(before.map { it.id }, after.map { it.id })
            assertEquals(before.map { security.decryptData(it.password) }, after.map { security.decryptData(it.password) })
            assertTrue("Deleting an account OTP must not leave a sibling OTP behind", after.all { it.authenticatorKey.isEmpty() })
            assertEquals("Account note", ProjectCredentialGroup.read(runBlocking {
                model.getCustomFieldsByEntryIdSync(primaryId)
            }.map(CustomFieldDraft::fromCustomField))?.note)
        } finally {
            runBlocking { totpModel.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
        }
    }


    @Test fun primaryAddPasswordExpandsOverAnimationFrames() {
        showSimpleEditor()
        scroll(hasTestTag("password_editor_add_password"))
        fun height() = compose.onNodeWithTag("password_editor_credentials").fetchSemanticsNode().size.height
        val before = height()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("password_editor_add_password").performClick()
            compose.mainClock.advanceTimeBy(80)
            val during = height()
            compose.mainClock.advanceTimeBy(1200)
            val after = height()
            assertTrue("New row expands instead of appearing instantly: $before < $during < $after",
                before < during && during < after)
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun addAccountWithTwoPasswordsAndEditFromItsOwnRowKeepsPrimaryAccount() {
        compose.setContent {
            CompositionLocalProvider(LocalUiSecurityManager provides security) {
                MaterialTheme { key(editId, detail) {
                    if (detail) PasswordDetailScreen(model, passwordId = requireNotNull(savedId), biometricEnabled = false,
                        onNavigateBack = {}, onEditPassword = {})
                    else AddEditPasswordScreen(model, passwordId = editId, initialStorageExplicit = true,
                        initialDraft = if (editId == null) AddEditPasswordInitialDraft(title = "Account groups", username = "personal", password = "primary-secret") else null,
                        onSaveCompleted = { savedId = it }, onNavigateBack = {})
                } }
            }
        }
        scroll(hasTestTag("password_editor_add_password")).performClick()
        compose.onNodeWithTag("password_editor_remove_1").assertDoesNotExist()
        scroll(hasTestTag("password_editor_value_1")).assertExists()
        scroll(hasTestTag("password_otp_secret"))
        assertTrue(compose.onNodeWithTag("password_editor_value_1").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("password_otp_secret").fetchSemanticsNode().boundsInRoot.top)
        scroll(hasTestTag("password_editor_1_menu")).performClick()
        compose.onNodeWithTag("password_editor_1_generate").assertExists()
        compose.onNodeWithTag("password_editor_remove_1").performClick()
        compose.onNodeWithTag("password_editor_value_1").assertDoesNotExist()
        scroll(hasTestTag("password_editor_credential_note")).performTextReplacement("Personal note\nSecond line")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll(hasTestTag("password_otp_secret")).performTextReplacement("JBSWY3DPEHPK3PXP")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll(hasTestTag("password_otp_inline_preview")).assertIsDisplayed()
        assertEditorNoteConnected("password_otp_secret", "password_editor_credential_note", "password_otp_inline_preview", "password_editor_add_password")
        scroll(hasTestTag("password_content_add"))
        compose.onNodeWithTag("password_content_editor").performTouchInput { swipeUp() }
        compose.onNodeWithTag("password_content_add").performClick()
        try { compose.waitUntil(10000) { compose.onAllNodesWithTag("add_project_credential").fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Throwable) {
            File(context.filesDir, "project-credential-failure.png").outputStream().use {
                compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            throw error
        }
        compose.onNodeWithTag("add_project_credential").performClick()
        scroll(prefix("credential_username_")).performTextReplacement("work")
        val groupTag = compose.onNode(prefix("credential_username_")).fetchSemanticsNode().config[SemanticsProperties.TestTag].removePrefix("credential_username_")
        scroll(hasTestTag("credential_otp_$groupTag")).assertExists().performTextReplacement("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll(hasTestTag("credential_otp_preview")).assertIsDisplayed()
        scroll(prefix("credential_password_")).performTextReplacement("work-secret-1")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll(prefix("credential_add_password_")).performClick()
        scroll(hasTestTag("credential_note_$groupTag")).performTextReplacement("Work note")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        assertEditorNoteConnected("credential_otp_$groupTag", "credential_note_$groupTag", "credential_otp_preview", "credential_add_password_$groupTag")
        val secondTag = compose.onAllNodes(prefix("credential_password_")).fetchSemanticsNodes().last().config[SemanticsProperties.TestTag]
        scroll(hasTestTag(secondTag)).performTextReplacement("work-secret-2")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        File(context.filesDir, "project-credential-editor.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        scroll(hasTestTag("credential_otp_$groupTag"))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        File(context.filesDir, "project-credential-extra-otp.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(compose.onNodeWithTag(secondTag).fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("credential_otp_$groupTag").fetchSemanticsNode().boundsInRoot.top)
        save()
        val rows = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }
        assertEquals(3, rows.size)
        assertEquals(setOf("personal", "work"), rows.map { it.username }.toSet())
        val savedFields = runBlocking { model.getCustomFieldsByEntryIds(rows.map { it.id }) }
            .mapValues { (_, fields) -> fields.map(CustomFieldDraft::fromCustomField) }
        val restored = ProjectCredentialGroup.restore(rows.map { it.copy(
            password = security.decryptData(it.password),
            authenticatorKey = security.decryptDataIfMonicaCiphertext(it.authenticatorKey)) }, savedFields)
        assertEquals(listOf("Personal note\nSecond line", "Work note"), restored.map { it.note })
        fun secret(row: PasswordEntry) = takagi.ru.monica.util.TotpDataResolver.fromAuthenticatorKey(
            security.decryptDataIfMonicaCiphertext(row.authenticatorKey))?.secret
        assertEquals("JBSWY3DPEHPK3PXP", secret(rows.single { it.username == "personal" }))
        assertTrue(rows.filter { it.username == "work" }.all { secret(it) == "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" })
        val additionalId = rows.first { it.username == "work" }.id
        compose.runOnIdle { editId = additionalId; savedId = null }
        compose.waitUntil(20000) { compose.onAllNodesWithText("Account groups").fetchSemanticsNodes().isNotEmpty() }
        scroll(prefix("credential_username_")).assertTextContains("work")
        scroll(hasTestTag("password_editor_credential_note")).assertTextContains("Personal note\nSecond line")
        scroll(hasTestTag("credential_note_$groupTag")).assertTextContains("Work note")
        scroll(hasTestTag("credential_otp_$groupTag")).assertTextContains("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        scroll(prefix("credential_username_")).performTextReplacement("work-edited")
        save()
        val edited = runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync() }
        assertEquals(rows.map { it.id }.toSet(), edited.map { it.id }.toSet())
        assertEquals("primary-secret", security.decryptData(edited.single { it.username == "personal" }.password))
        assertEquals(setOf("work-secret-1", "work-secret-2"), edited.filter { it.username == "work-edited" }.map { security.decryptData(it.password) }.toSet())
        assertEquals("JBSWY3DPEHPK3PXP", secret(edited.single { it.username == "personal" }))
        assertTrue(edited.filter { it.username == "work-edited" }.all { secret(it) == "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" })
        compose.runOnIdle { detail = true }
        compose.waitUntil(20000) { compose.onAllNodesWithText("personal").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("personal").assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Personal note\nSecond line"))
        compose.onNodeWithText("Personal note\nSecond line").assertIsDisplayed()
        compose.onNodeWithTag("credential_note_card_${restored[0].id}").assertExists()
        // Both accounts have independent detail sections; the second can be scrolled into view.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("work-edited"))
        compose.onNodeWithText("work-edited").assertIsDisplayed()
        edited.filter { it.username == "work-edited" }.forEach { row ->
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("detail_password_${row.id}"))
            compose.onNodeWithTag("detail_password_${row.id}").performScrollTo().assertIsDisplayed()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Work note"))
        compose.onNodeWithText("Work note").assertIsDisplayed()
        compose.onNodeWithTag("credential_note_card_${restored[1].id}").assertExists()
        val noteBounds = compose.onNodeWithTag("credential_note_card_${restored[1].id}").fetchSemanticsNode().boundsInRoot
        val otpBounds = compose.onAllNodesWithTag("password_authenticator_card").fetchSemanticsNodes().last().boundsInRoot
        assertTrue("Note follows OTP with only group spacing and inner padding",
            noteBounds.top - otpBounds.bottom <= with(compose.density) { 14.dp.toPx() })
        File(context.filesDir, "credential-detail-grouped.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun legacySingleAccountUsesConnectedAuthenticatorWithoutRepeatingIdentity() {
        val id = runBlocking { db.passwordEntryDao().insertPasswordEntry(PasswordEntry(
            title = "Single account", username = "personal", password = security.encryptData("primary-secret"), website = "",
            authenticatorKey = "otpauth://totp/HiddenIssuer:personal?secret=JBSWY3DPEHPK3PXP&issuer=HiddenIssuer")) }
        compose.setContent { CompositionLocalProvider(LocalUiSecurityManager provides security) {
            MaterialTheme { PasswordDetailScreen(model, passwordId = id, biometricEnabled = false,
                onNavigateBack = {}, onEditPassword = {}) }
        } }
        compose.waitUntil(20000) { compose.onAllNodesWithTag("password_authenticator_card").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("HiddenIssuer").assertDoesNotExist()
        compose.onNodeWithText("personal").assertExists()
        compose.onNodeWithTag("credential_note_card_$id").assertDoesNotExist()
        compose.onNodeWithTag("password_authenticator_card").performScrollTo().assertIsDisplayed()
        File(context.filesDir, "credential-detail-single.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("password_authenticator_card").performClick()
        compose.onNodeWithTag("auth_copy_current").assertIsDisplayed()
        compose.onNodeWithTag("auth_copy_next").assertIsDisplayed()
        compose.onNodeWithTag("auth_qr_migrate").assertIsDisplayed()
    }

    @Test fun passwordDeleteSharesStrengthRowAndSurvivesDisabledAnalysis() {
        var analysis by mutableStateOf(true)
        var deleted = false
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalCompactCredentialFields provides true) {
                DetailGroupItem(0, 1) {
                    PasswordItemRow(PasswordEntry(id = 701, title = "Synthetic", username = "demo", password = "", website = ""),
                        displayPassword = "synthetic-password", unavailableSource = null,
                        onResyncUnreadable = {}, isResyncingUnreadable = false,
                        showSecurityAnalysis = analysis, index = 1, showIndex = true,
                        onDelete = { deleted = true }, context = context, canDelete = true, onCreateSend = null)
                }
            }
        } }
        val strength = compose.onNodeWithTag("detail_password_strength_701").fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithTag("detail_password_delete_701").fetchSemanticsNode().boundsInRoot
        assertTrue("Delete and strength occupy the same row", abs(strength.center.y - delete.center.y) <= 1f)
        assertTrue("Delete keeps a 48dp touch target", delete.width >= with(compose.density) { 48.dp.toPx() })
        compose.onNodeWithTag("detail_password_delete_701").assertContentDescriptionEquals(context.getString(R.string.delete))
        compose.onNodeWithText(context.getString(R.string.delete)).assertDoesNotExist()
        File(context.filesDir, "credential-detail-compact-password.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.runOnIdle { analysis = false }
        compose.onNodeWithTag("detail_password_strength_701").assertDoesNotExist()
        compose.onNodeWithTag("detail_password_delete_701").performClick()
        compose.runOnIdle { assertTrue(deleted) }
    }

    @Test fun credentialNotesConnectAndHideEmptyContentAtLargeFont() {
        val note = "Personal note\nSecond line\nIndependent of the work account"
        val groups = listOf(ProjectCredentialGroup.Group(username = "personal", note = note),
            ProjectCredentialGroup.Group(username = "work", note = "  \n  "))
        val rows = ProjectCredentialGroup.rows(groups)
        val entries = rows.mapIndexed { index, row -> PasswordEntry(id = 801L + index,
            title = "Synthetic", username = row.username, password = "", website = "") }
        val metadata = entries.mapIndexed { index, row -> row.id to rows[index].metadata }.toMap()
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEach { entry ->
                        ProjectCredentialDetailCard(listOf(entry), metadata, mapOf(entry.id to "synthetic-password"),
                            emptyMap(), context, AppSettings(), null, {}, {},
                            onResyncUnreadable = {}, isResyncingUnreadable = false)
                    }
                }
            }
        } }
        compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("credential_note_card_${groups[0].id}").assertExists()
        compose.onNodeWithTag("credential_note_copy").assertExists()
        compose.onNodeWithText("work").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("credential_note_card_${groups[1].id}").assertDoesNotExist()
        File(context.filesDir, "credential-detail-empty-note-large-font.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertEditorNoteConnected(otpTag: String, noteTag: String, previewTag: String, addTag: String) {
        scroll(hasTestTag(noteTag))
        val otp = compose.onNodeWithTag(otpTag).getUnclippedBoundsInRoot()
        val note = compose.onNodeWithTag(noteTag).getUnclippedBoundsInRoot()
        assertEquals("Note immediately follows the verification key", 2f, (note.top - otp.bottom).value, 0.5f)
        val preview = compose.onNodeWithTag(previewTag).getUnclippedBoundsInRoot()
        val add = compose.onNodeWithTag(addTag).getUnclippedBoundsInRoot()
        assertTrue("Preview follows the connected fields", preview.top >= note.bottom)
        assertTrue("Add password follows the whole credential", add.top >= preview.bottom)
    }

    @Test fun sanitizedMetadataStillDisplaysEveryResolvedPassword() {
        val rows = listOf(501L, 502L).map {
            PasswordEntry(id = it, title = "Sanitized fixture", username = "secondary", password = "", website = "")
        }
        compose.setContent { MaterialTheme {
            ProjectCredentialDetailCard(rows = rows, metadata = emptyMap(),
                values = mapOf(501L to "first-secret", 502L to "second-secret"), unavailable = emptyMap(),
                context = context, settings = takagi.ru.monica.data.AppSettings(), onCreateSend = null,
                onEdit = {}, onDelete = {}, onResyncUnreadable = {}, isResyncingUnreadable = false)
        } }
        compose.onNodeWithTag("detail_password_501").assertIsDisplayed()
        compose.onNodeWithTag("detail_password_502").assertIsDisplayed()
    }

}

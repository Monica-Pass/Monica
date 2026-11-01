package takagi.ru.monica.keepass

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.*
import takagi.ru.monica.repository.SecureItemRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.LocalUiSecurityManager
import takagi.ru.monica.ui.bringAboveFloatingActions
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.*
import java.util.UUID

/** Add via the actual Monica menu, save to KDBX, then reopen and save again. */
class KeePassAddContentUiRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun scroll(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val roots = listOf("content_detail_scroll", "embedded_bank_editor", "embedded_document_editor", "billing_item_editor")
        val root = roots.firstOrNull { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        val list = if (root != null) compose.onNodeWithTag(root) else compose.onNode(hasTestTag("password_content_editor") or hasTestTag("password_classic_editor"))
        list.performScrollToNode(matcher)
        val node = if (root != null) compose.onNode(matcher and hasAnyAncestor(hasTestTag(root))) else compose.onNode(matcher)
        compose.bringAboveFloatingActions(node, list)
        return node
    }
    private fun scroll(tag: String) = scroll(hasTestTag(tag))
    private fun closeDetails() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        if (listOf("embedded_bank_editor", "embedded_document_editor", "billing_item_editor").any {
            compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()
        }) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.save)).onLast().performClick()
            compose.waitUntil(15000) { listOf("embedded_bank_editor", "embedded_document_editor", "billing_item_editor").all {
                compose.onAllNodesWithTag(it).fetchSemanticsNodes().isEmpty()
            } }
        }
        if (compose.onAllNodesWithTag("content_detail_back").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("content_detail_back").performClick()
        }
    }
    private fun menu() {
        closeDetails()
        scroll("password_content_add").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("block_add_API_KEY").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun add(section: String) {
        menu(); compose.onNodeWithTag("password_content_choose_$section").performScrollTo().performClick()
    }

    @Test fun allVisibleAddContentChoicesPersistInOriginalVault() = runBlocking {
        val settings = SettingsManager(context)
        val previousMode = settings.settingsFlow.first().passwordContentEditorEnabled
        settings.updatePasswordContentEditorEnabled(true)
        try {
            KeePassDeleteInvestigationTest().scenario { model, service, id, room, uri ->
                val security = SecurityManager(context)
                val libraries = LocalKeePassViewModel(context.applicationContext as android.app.Application,
                    PasswordDatabase.getDatabase(context).localKeePassDatabaseDao(), security)
                val notes = NoteViewModel(SecureItemRepository(room.secureItemDao()), context = context,
                    strings = AppLocaleStringResolver(context))
                var visible by mutableStateOf(true)
                var epoch by mutableStateOf(0)
                val gpg = withContext(Dispatchers.Default) { GpgKeyGenerator.generate("Synthetic fixture", "fixture@example.test", bits = 3072) }
                model.holdNativeKeePassRead(id).use {
                    val store = requireNotNull(model.monicaKeePassStore)
                    store.refresh(id)
                    val row = store.rows(id).first().first()
                    val identity = KeePassNativeEntryIdentity(id, UUID.fromString(row.keepassEntryUuid))
                    val attachment = "Synthetic UI attachment ✓".toByteArray()
                    service.addNativeAttachment(id, identity.entryUuid, "ui-fixture.txt", attachment,
                        service.openNativeBrowser(id).getOrThrow().sourceRevision.sha256).getOrThrow()
                    store.refresh(id)
                    try {
                        compose.setContent { key(epoch) { CompositionLocalProvider(LocalUiSecurityManager provides security) {
                            MonicaTheme { if (visible) AddEditPasswordScreen(model, noteViewModel = notes,
                                localKeePassViewModel = libraries, passwordId = row.id, onSaveCompleted = {},
                                onNavigateBack = { visible = false }) }
                        } } }
                        compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("password_editor_title") and hasText(row.title)).fetchSemanticsNodes().isNotEmpty() }
                        add("NOTES"); scroll("password_content_notes").performTextReplacement("UI note\n第二行")
                        add("PAYMENT"); scroll("entry_payment_number").performTextReplacement("4242424242424242")
                        scroll("entry_payment_holder").performTextReplacement("SYNTHETIC")
                        scroll("entry_payment_expiry").performTextReplacement("09/2030")
                        scroll("entry_payment_cvv").performTextReplacement("123")
                        add("DOCUMENT")
                        scroll(hasSetTextAction() and hasText(context.getString(R.string.document_number_required))).performTextReplacement("EX1234567")
                        add("ADDRESS"); scroll("entry_address_street").performTextReplacement("12 Fixture Street")
                        scroll("entry_address_city").performTextReplacement("Fixture City")
                        add("CUSTOM_FIELDS")
                        for ((index, title) in listOf("UI text", "UI hidden").withIndex()) {
                            scroll("entry_content_add").performClick()
                            compose.onNodeWithTag("entry_field_kind_$index").performScrollTo().performClick()
                            scroll(hasSetTextAction() and hasText(context.getString(R.string.custom_field_name_placeholder))).performTextReplacement(title)
                            scroll(hasSetTextAction() and hasText(context.getString(R.string.custom_field_value))).performTextReplacement("$title synthetic ✓")
                            androidx.test.espresso.Espresso.closeSoftKeyboard()
                            compose.onAllNodesWithContentDescription(context.getString(R.string.collapse)).onLast().performClick()
                        }
                        add("ATTACHMENTS")
                        compose.waitUntil(15000) { compose.onAllNodesWithText("ui-fixture.txt").fetchSemanticsNodes().isNotEmpty() }
                        compose.onNodeWithText("ui-fixture.txt").assertExists()
                        closeDetails()
                        for (kind in PasswordContentBlocks.Kind.entries) {
                            menu()
                            if (kind == PasswordContentBlocks.Kind.GPG_KEY) compose.onNodeWithText(context.getString(R.string.content_block_more)).performScrollTo().performClick()
                            compose.onNodeWithTag("block_add_${kind.name}").performScrollTo().performClick()
                            compose.onNodeWithTag("block_title").performScrollTo().performTextReplacement("UI $kind")
                            val values = when (kind) {
                                PasswordContentBlocks.Kind.API_KEY -> mapOf("key" to "  UI synthetic key  ", "url" to "https://example.test/api")
                                PasswordContentBlocks.Kind.API_TOKEN -> mapOf("token" to "  UI synthetic token  ", "provider" to "github")
                                PasswordContentBlocks.Kind.SSH_KEY -> mapOf("publicKeyOpenSsh" to "ssh-ed25519 AAAA synthetic-ui")
                                PasswordContentBlocks.Kind.GPG_KEY -> mapOf("publicKey" to gpg.publicKey, "privateKey" to gpg.privateKey)
                                PasswordContentBlocks.Kind.QR_CODE -> mapOf("content" to "UI QR 原文 ✓")
                            }
                            values.forEach { (name, value) -> compose.onNodeWithTag("block_field_$name").performScrollTo().performTextReplacement(value) }
                            androidx.test.espresso.Espresso.closeSoftKeyboard()
                            val blockSave = compose.onNodeWithTag("block_save")
                            if (kind == PasswordContentBlocks.Kind.QR_CODE) blockSave.performScrollTo()
                            blockSave.performClick()
                            compose.waitUntil(15000) { compose.onAllNodesWithTag("block_save").fetchSemanticsNodes().isEmpty() }
                        }
                        // Account credentials are another menu choice, persisted as UUID-linked entries.
                        menu(); compose.onNodeWithTag("add_project_credential").performClick()
                        val prefix = { value: String -> SemanticsMatcher("tag prefix $value") {
                            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(value)
                        } }
                        scroll(prefix("credential_username_")).performTextReplacement("UI second account")
                        scroll(prefix("credential_password_")).performTextReplacement("UI second synthetic secret")
                        androidx.test.espresso.Espresso.closeSoftKeyboard()
                        compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
                        compose.waitUntil(30000) { !visible }
                        KeePassKdbxService.invalidateProcessCache(id)
                        store.refresh(id)
                        suspend fun checkDisk() {
                            val browser = service.openNativeBrowser(id, true).getOrThrow()
                            val saved = requireNotNull(browser.entry(identity))
                            val fields = saved.fields.map { CustomFieldDraft(title = it.name, value = it.rawValue, isProtected = it.isProtected) }
                            assertEquals(PasswordContentBlocks.Kind.entries.toSet(), PasswordContentBlocks.read(fields).map { it.block!!.kind }.toSet())
                            val blocks = PasswordContentBlocks.read(fields).map { it.block!! }.associateBy { it.kind }
                            assertEquals("  UI synthetic key  ", blocks.getValue(PasswordContentBlocks.Kind.API_KEY).value("key"))
                            assertEquals("  UI synthetic token  ", blocks.getValue(PasswordContentBlocks.Kind.API_TOKEN).value("token"))
                            assertEquals("ssh-ed25519 AAAA synthetic-ui", blocks.getValue(PasswordContentBlocks.Kind.SSH_KEY).value("publicKeyOpenSsh"))
                            assertEquals(gpg.publicKey, blocks.getValue(PasswordContentBlocks.Kind.GPG_KEY).value("publicKey"))
                            assertEquals(gpg.privateKey, blocks.getValue(PasswordContentBlocks.Kind.GPG_KEY).value("privateKey"))
                            assertEquals("UI QR 原文 ✓", blocks.getValue(PasswordContentBlocks.Kind.QR_CODE).value("content"))
                            assertTrue(fields.filter { PasswordContentBlocks.owns(it.title) }.all { it.isProtected })
                            assertEquals("UI note\n第二行", saved.field("Notes")!!.rawValue)
                            assertEquals(row.password, saved.field("Password")!!.rawValue)
                            assertTrue(saved.field("Password")!!.isProtected)
                            assertEquals("UI text synthetic ✓", saved.field("UI text")!!.rawValue)
                            assertFalse(saved.field("UI text")!!.isProtected)
                            assertEquals("UI hidden synthetic ✓", saved.field("UI hidden")!!.rawValue)
                            assertTrue(saved.field("UI hidden")!!.isProtected)
                            listOf(EmbeddedWalletContent.Kind.BANK_CARD, EmbeddedWalletContent.Kind.DOCUMENT, EmbeddedWalletContent.Kind.ADDRESS).forEach {
                                assertTrue(EmbeddedWalletContent.read(saved.field(EmbeddedWalletContent.fieldName(it))!!.rawValue) is EmbeddedWalletContent.ReadResult.Available)
                                assertTrue(saved.field(EmbeddedWalletContent.fieldName(it))!!.isProtected)
                            }
                            fun walletValue(kind: EmbeddedWalletContent.Kind, name: String): String {
                                val wallet = (EmbeddedWalletContent.read(saved.field(EmbeddedWalletContent.fieldName(kind))!!.rawValue)
                                    as EmbeddedWalletContent.ReadResult.Available).snapshot
                                return security.decryptDataIfMonicaCiphertext(wallet.itemData.getValue(name).jsonPrimitive.content)
                            }
                            assertEquals("4242424242424242", walletValue(EmbeddedWalletContent.Kind.BANK_CARD, "cardNumber"))
                            assertEquals("123", walletValue(EmbeddedWalletContent.Kind.BANK_CARD, "cvv"))
                            assertEquals("EX1234567", walletValue(EmbeddedWalletContent.Kind.DOCUMENT, "documentNumber"))
                            assertEquals("12 Fixture Street", walletValue(EmbeddedWalletContent.Kind.ADDRESS, "streetAddress"))
                            assertEquals("Fixture City", walletValue(EmbeddedWalletContent.Kind.ADDRESS, "city"))
                            assertArrayEquals(attachment, saved.attachments.single { it.name == "ui-fixture.txt" }.binary!!.getContent())
                            assertTrue(browser.entries.any { it.field("UserName")?.rawValue == "UI second account" && it.field("Password")?.rawValue == "UI second synthetic secret" })
                            assertTrue(room.passwordEntryDao().getPasswordEntriesByKeePassDatabaseSync(id).isEmpty())
                        }
                        checkDisk()
                        compose.runOnIdle { epoch++; visible = true }
                        compose.waitUntil(20000) { compose.onAllNodes(hasTestTag("password_editor_title") and hasText(row.title)).fetchSemanticsNodes().isNotEmpty() }
                        // Wait for persisted content to render, beyond the initially empty form shell.
                        compose.waitUntil(20000) { runCatching { scroll(hasText("UI GPG_KEY")).assertExists(); true }.getOrDefault(false) }
                        compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
                        compose.waitUntil(30000) { !visible }
                        KeePassKdbxService.invalidateProcessCache(id)
                        checkDisk()
                    } finally {
                        compose.runOnIdle { visible = false }
                        libraries.viewModelScope.cancel(); notes.viewModelScope.cancel()
                    }
                }
            }
        } finally { settings.updatePasswordContentEditorEnabled(previousMode) }
    }
}

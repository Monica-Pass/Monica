package takagi.ru.monica.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.model.EntryContentFields
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.repository.CustomFieldRepository
import takagi.ru.monica.repository.PasswordRepository
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.AddEditPasswordInitialDraft
import takagi.ru.monica.ui.screens.AddEditPasswordScreen
import takagi.ru.monica.util.TotpDataResolver
import takagi.ru.monica.utils.AppLocaleStringResolver
import takagi.ru.monica.viewmodel.PasswordViewModel
import java.io.File

@RunWith(AndroidJUnit4::class)
class PasswordOtpEditorTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var model: PasswordViewModel
    private lateinit var security: SecurityManager
    private var editId by mutableStateOf<Long?>(null)
    private var savedId: Long? = null
    private var detailVisible by mutableStateOf(false)
    private var editorOpenCount by mutableStateOf(0)
    private var previousContentEditor = false

    @Before fun setup() {
        runBlocking {
            assertTrue("Standalone UI fixture requires prepared secure storage",
                takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(context) is
                    takagi.ru.monica.security.SecureStartupResult.Ready)
        }
        runBlocking {
            val settings = takagi.ru.monica.utils.SettingsManager(context)
            previousContentEditor = settings.settingsFlow.first().passwordContentEditorEnabled
            settings.updatePasswordContentEditorEnabled(false)
        }
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        security = SecurityManager(context)
        compose.runOnIdle {
            model = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()),
                security, customFieldRepository = CustomFieldRepository(db.customFieldDao()),
                strings = AppLocaleStringResolver(context))
        }
    }
    @After fun cleanup() {
        model.viewModelScope.cancel(); db.close()
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(previousContentEditor) }
    }

    @Test fun defaultInlineAuthenticatorPreviewsSavesReopensAndClears() {
        show()
        scroll("password_otp_secret").assertExists()
        compose.onNodeWithTag("otp_type_selector").assertDoesNotExist()
        scroll("password_otp_secret").performTextReplacement("JBSWY3DPEHPK3PXP")
        compose.waitUntil(10000) { compose.onAllNodesWithTag("password_otp_inline_preview").fetchSemanticsNodes().isNotEmpty() }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("password_otp_inline_preview").assertIsDisplayed()
        capture("inline-otp-default")
        save()
        assertEquals(OtpType.TOTP, payload().otpType)
        compose.runOnIdle { editId = savedId; savedId = null }
        scroll("password_otp_secret")
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("password_otp_secret") and hasText("JBSWY3DPEHPK3PXP")).fetchSemanticsNodes().isNotEmpty() }
        scroll("password_otp_secret").performTextReplacement("")
        compose.onNodeWithTag("password_otp_inline_preview").assertDoesNotExist()
        save()
        val entry = runBlocking { requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!)) }
        assertTrue(security.decryptDataIfMonicaCiphertext(entry.authenticatorKey).isBlank())
    }

    @Test fun addContentMenuDoesNotOfferDefaultAuthenticator() {
        show()
        scroll("password_content_add").performClick()
        compose.onNodeWithTag("password_content_choose_AUTHENTICATOR").assertDoesNotExist()
        compose.onNodeWithTag("password_content_choose_PAYMENT").assertExists()
    }

    @Test fun invalidInlineOtpParametersReopenSettingsBeforeSaving() {
        show()
        scroll("password_otp_secret").performTextReplacement("JBSWY3DPEHPK3PXP")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("password_otp_advanced").performClick()
        scroll("otp_period").performTextReplacement("0")
        closeContent()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.onNodeWithTag("content_detail_scroll").assertExists()
        assertEquals(null, savedId)
        scroll("otp_period").assertTextContains("0").performTextReplacement("45")
        save()
        assertEquals(45, payload().period)
    }

    @Test fun editingOldLocalPasswordMovesItToRecentWithoutChangingCreationDate() {
        val oldTime = System.currentTimeMillis() - 20 * 86_400_000L
        val ids = runBlocking {
            (0..9).map { index ->
                db.passwordEntryDao().insertPasswordEntry(takagi.ru.monica.data.PasswordEntry(
                    title = "Recent fixture $index", username = "alice", website = "",
                    password = security.encryptData("fixture-only-password"),
                    createdAt = java.util.Date(oldTime + index * 86_400_000L),
                    updatedAt = java.util.Date(oldTime + index * 86_400_000L)))
            }
        }
        editId = ids.first()
        show()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Recent fixture 0").fetchSemanticsNodes().isNotEmpty()
        }
        scroll("password_editor_value_0").performTextReplacement("edited-fixture-password")
        val beforeSave = System.currentTimeMillis()
        save()
        val stored = runBlocking { requireNotNull(db.passwordEntryDao().getPasswordEntryById(ids.first())) }
        assertEquals(oldTime, stored.createdAt.time)
        assertTrue("Edit must persist its modification time", stored.updatedAt.time >= beforeSave)
        assertEquals("edited-fixture-password", security.decryptDataIfMonicaCiphertext(stored.password))
        val visible = runBlocking {
            kotlinx.coroutines.withTimeout(20_000) {
                model.allPasswordsForUi.first { entries ->
                    entries.any { it.id == ids.first() && it.updatedAt.time >= beforeSave }
                }
            }
        }
        val recent = takagi.ru.monica.ui.vaultv2.recentVaultOverviewItems(
            takagi.ru.monica.ui.vaultv2.buildVaultV2PasswordItems(visible))
        assertEquals(ids.first(), recent.first().passwordEntry?.id)
        assertEquals(8, recent.size)
    }

    @Test fun legacySsoAndDisabledStylePreferenceSurviveUnifiedEditor() {
        val id = runBlocking {
            db.passwordEntryDao().insertPasswordEntry(takagi.ru.monica.data.PasswordEntry(
                title = "Legacy SSO", username = "alice", password = security.encryptData(""),
                website = "https://example.com", notes = "legacy notes", loginType = "SSO",
                ssoProvider = "GOOGLE", ssoRefEntryId = 987654L,
            ))
        }
        editId = id
        show()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("password_content_editor").assertExists()
        scrollTo(hasText(context.getString(R.string.sso_provider_label)))
        compose.onNodeWithText(context.getString(R.string.login_type_password)).assertDoesNotExist()
        save()
        val entry = runBlocking { requireNotNull(db.passwordEntryDao().getPasswordEntryById(id)) }
        assertEquals("SSO", entry.loginType)
        assertEquals("GOOGLE", entry.ssoProvider)
        assertEquals(987654L, entry.ssoRefEntryId)
        assertEquals("legacy notes", entry.notes)
        assertEquals("alice", entry.username)
    }

    @Test fun strengthBadgesExpandEachPasswordCardAndCollapseForEmptyValues() {
        show()
        scroll("password_editor_value_0")
        compose.onNodeWithTag("password_strength_badge_0").assertExists()
            .assert(hasAnyAncestor(hasTestTag("password_editor_card_0")))
        val filledHeight = compose.onNodeWithTag("password_editor_card_0").fetchSemanticsNode().boundsInRoot.height
        scroll("password_editor_value_0").performTextReplacement("")
        compose.waitForIdle()
        compose.onNodeWithTag("password_strength_badge_0").assertDoesNotExist()
        val emptyHeight = compose.onNodeWithTag("password_editor_card_0").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Badge must occupy space inside the card", filledHeight > emptyHeight)
        scroll("password_editor_value_0").performTextReplacement("correct-horse-Long-Fixture-89!")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scrollTo(hasText(context.getString(R.string.add_password)) and hasClickAction()).performClick()
        scroll("password_editor_value_1").performTextReplacement("123")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("password_strength_badge_1").assertExists()
            .assert(hasAnyAncestor(hasTestTag("password_editor_card_1")))
        capture("password-strength-inside-cards")
        save()
        val values = runBlocking { db.passwordEntryDao().getActiveEntries().first().map { security.decryptDataIfMonicaCiphertext(it.password) }.toSet() }
        assertEquals(setOf("correct-horse-Long-Fixture-89!", "123"), values)
    }

    @Test fun groupedPasswordRowsKeepValuesAfterMiddleRemoval() {
        show()
        repeat(2) { scrollTo(hasText(context.getString(R.string.add_password)) and hasClickAction()).performClick() }
        scroll("password_editor_value_1").performTextReplacement("middle-fixture")
        scroll("password_editor_value_2").performTextReplacement("last-fixture")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        capture("password-group-three")
        scroll("password_editor_1_menu").performClick()
        compose.onNodeWithTag("password_editor_remove_1").assertIsDisplayed().performClick()
        scroll("password_editor_value_1").assertTextContains("last-fixture")
        capture("password-group-after-remove")
        scrollTo(hasText(context.getString(R.string.title_required))).assertExists()
        capture("credential-title-storage")
        save()
        val values = runBlocking { db.passwordEntryDao().getActiveEntries().first().map { security.decryptDataIfMonicaCiphertext(it.password) }.toSet() }
        assertEquals(setOf("fixture-only-password", "last-fixture"), values)
    }

    @Test fun unifiedEditorHasNoIntroOrLoginTypePicker() {
        show()
        compose.onNodeWithTag("password_content_editor").assertExists()
        compose.onNodeWithTag("password_classic_editor").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.password_content_optional_login)).assertDoesNotExist()
        scrollTo(hasText(context.getString(R.string.add_password)) and hasClickAction())
        compose.onNodeWithText(context.getString(R.string.login_type_sso)).assertDoesNotExist()
        capture("compact-filled-credentials")
        save()
        val entry = runBlocking { requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!)) }
        assertEquals("alice", entry.username)
        assertEquals("fixture-only-password", security.decryptDataIfMonicaCiphertext(entry.password))
    }

    @Test fun vaultGeneratorSuggestionsArePlaintextAndCanBeApplied() {
        val wasUnlocked = takagi.ru.monica.security.SessionManager.isUnlocked.value
        takagi.ru.monica.security.SessionManager.markUnlocked()
        try {
            runBlocking {
                repeat(3) { index ->
                    db.passwordEntryDao().insertPasswordEntry(takagi.ru.monica.data.PasswordEntry(
                        title = "Generator fixture $index", website = "", username = "fixture-frequent-account",
                        password = security.encryptData("Fixture Frequent Secret!")))
                }
                db.passwordEntryDao().insertPasswordEntry(takagi.ru.monica.data.PasswordEntry(
                    title = "Excluded fixture", website = "", username = "excluded-archived-account",
                    password = security.encryptData("Excluded Archived Secret!"), isArchived = true))
            }
            show()
            scrollTo(hasContentDescription(context.getString(R.string.cg_username_title))).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("fixture-frequent-account").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("excluded-archived-account").assertDoesNotExist()
            compose.onNodeWithText("fixture-frequent-account").performClick()
            scroll("password_editor_0_menu").performClick()
        compose.onNodeWithTag("password_editor_0_generate").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture Frequent Secret!").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Excluded Archived Secret!").assertDoesNotExist()
            val screenshotDir = File(context.getExternalFilesDir(null), "generator-ui").apply { mkdirs() }
            File(screenshotDir, "vault-generator-plaintext.png").outputStream().use {
                compose.onNodeWithTag("credential_generator_scroll").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithText("Fixture Frequent Secret!").performClick()
            save()
            val saved = runBlocking { requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!)) }
            assertEquals("fixture-frequent-account", saved.username)
            assertEquals("Fixture Frequent Secret!", security.decryptDataIfMonicaCiphertext(saved.password))
        } finally {
            if (!wasUnlocked) takagi.ru.monica.security.SessionManager.markLocked()
        }
    }

    @Test fun generatorsApplyOnlyAfterUseAndPersist() {
        show()
        fun generated(): String {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("generator_result").fetchSemanticsNodes().firstOrNull()
                    ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text.isNotEmpty() } == true
            }
            return compose.onNodeWithTag("generator_result").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        }
        scrollTo(hasContentDescription(context.getString(R.string.cg_username_title))).performClick()
        generated()
        compose.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        scrollTo(hasText("alice")).assertExists()
        scrollTo(hasContentDescription(context.getString(R.string.cg_username_title))).performClick()
        val account = generated()
        compose.onNodeWithTag("generator_apply").performScrollTo().performClick()
        scroll("password_editor_0_menu").performClick()
        compose.onNodeWithTag("password_editor_0_generate").assertIsDisplayed().performClick()
        val password = generated()
        compose.onNodeWithTag("generator_apply").performScrollTo().performClick()
        save()
        val entry = runBlocking { requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!)) }
        assertEquals(account, entry.username)
        assertEquals(password, security.decryptDataIfMonicaCiphertext(entry.password))
    }

    @Test fun totpCreateAndReedit() = verify(OtpType.TOTP)
    @Test fun hotpCreateAndReedit() = verify(OtpType.HOTP)
    @Test fun steamCreateAndReedit() = verify(OtpType.STEAM)
    @Test fun yandexCreateAndReedit() = verify(OtpType.YANDEX)
    @Test fun motpCreateAndReedit() = verify(OtpType.MOTP)
    @Test fun onDemandEditorAddsMotpAndKeepsItAfterReedit() = verify(OtpType.MOTP, onDemand = true)
    @Test fun onDemandEditorAddsTotpAndKeepsItAfterReedit() = verify(OtpType.TOTP, onDemand = true)
    @Test fun onDemandEditorAddsHotpAndKeepsItAfterReedit() = verify(OtpType.HOTP, onDemand = true)
    @Test fun onDemandEditorAddsSteamAndKeepsItAfterReedit() = verify(OtpType.STEAM, onDemand = true)
    @Test fun onDemandEditorAddsYandexAndKeepsItAfterReedit() = verify(OtpType.YANDEX, onDemand = true)

    @Test fun mergedAddressContactsSurviveSaveReeditAndClearing() {
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(true) }
        show()
        addContent("ADDRESS")
        openEmbeddedWalletSection("billing_item_editor", "contact")
        compose.onNodeWithTag("entry_contact_email_0").performScrollTo().performTextReplacement("alice@example.invalid")
        compose.onNodeWithTag("entry_contact_phone_0").performScrollTo().performTextReplacement("+44 20 7946 0123")
        closeEmbeddedWalletSection()
        embeddedWalletField("billing_item_editor", "entry_address_street").performTextReplacement("12 Example Street")
        embeddedWalletField("billing_item_editor", "entry_address_city").performTextReplacement("London")
        embeddedWalletField("billing_item_editor", "entry_address_postal").performTextReplacement("SW1A 1AA")
        embeddedWalletField("billing_item_editor", "entry_address_country").performTextReplacement("UK")
        saveEmbeddedWallet("billing_item_editor")
        save()
        val original = runBlocking { requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!)) }
        assertEquals("alice@example.invalid", original.email)
        assertEquals("+44 20 7946 0123", original.phone)
        assertEquals("12 Example Street", original.addressLine)
        assertEquals("London", original.city)
        assertEquals("SW1A 1AA", original.zipCode)
        assertEquals("UK", original.country)
        val originalSnapshot = savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.ADDRESS)
        val originalAddress = requireNotNull(takagi.ru.monica.data.model.CardWalletDataCodec.parseBillingAddressData(originalSnapshot.itemData.toString()))
        assertEquals(original.email, originalAddress.email)
        assertEquals(original.phone, originalAddress.phone)
        assertEquals(original.addressLine, originalAddress.streetAddress)
        assertEquals(original.city, originalAddress.city)
        assertEquals(original.zipCode, originalAddress.postalCode)
        assertEquals(original.country, originalAddress.country)
        compose.runOnIdle { editId = savedId; savedId = null }
        addContent("ADDRESS")
        openEmbeddedWalletSection("billing_item_editor", "contact")
        compose.onNodeWithTag("entry_contact_email_0").performScrollTo().assertTextContains("alice@example.invalid")
        compose.onNodeWithTag("entry_contact_phone_0").performScrollTo().assertTextContains("+44 20 7946 0123")
        compose.onNodeWithTag("entry_contact_phone_0").performScrollTo().performTextReplacement("")
        closeEmbeddedWalletSection()
        embeddedWalletField("billing_item_editor", "entry_address_postal").assertTextContains("SW1A 1AA")
        embeddedWalletField("billing_item_editor", "entry_address_city").performTextReplacement("Oxford")
        saveEmbeddedWallet("billing_item_editor")
        save()
        runBlocking {
            val edited = requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!))
            assertEquals(original.id, edited.id)
            assertEquals(original.email, edited.email)
            assertEquals("", edited.phone)
            assertEquals("Oxford", edited.city)
            assertEquals(original.addressLine, edited.addressLine)
            assertEquals(original.zipCode, edited.zipCode)
            assertEquals(original.country, edited.country)
        }
        val editedSnapshot = savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.ADDRESS)
        assertEquals(originalSnapshot.id, editedSnapshot.id)
        assertEquals(originalAddress.copy(phone = "", city = "Oxford"),
            takagi.ru.monica.data.model.CardWalletDataCodec.parseBillingAddressData(editedSnapshot.itemData.toString()))
        capture("content-merged-contact-address-reedited")
    }

    @Test fun onDemandHiddenCustomFieldSurvivesSaveReeditAndExplicitDeletion() {
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(true) }
        show()
        addContent("CUSTOM_FIELDS")
        scroll("entry_content_add").performClick()
        compose.onNodeWithTag("entry_field_kind_1").performScrollTo().performClick()
        val name = hasSetTextAction() and hasText(context.getString(R.string.custom_field_name_placeholder))
        val value = hasSetTextAction() and hasText(context.getString(R.string.custom_field_value))
        scrollTo(name).performTextReplacement("Recovery phrase")
        scrollTo(value).performTextReplacement("synthetic hidden recovery")
        save()
        val original = runBlocking { db.customFieldDao().getFieldsByEntryIdSync(savedId!!).filterNot { it.title == EntryContentFields.ORDER }.single() }
        assertEquals("Recovery phrase", original.title)
        assertTrue(original.isProtected)
        assertEquals("synthetic hidden recovery", security.decryptDataIfMonicaCiphertext(original.value))
        compose.runOnIdle { editId = savedId; savedId = null }
        val title = hasText("Recovery phrase")
        addContent("CUSTOM_FIELDS")
        scrollTo(title).assertIsDisplayed()
        compose.onNodeWithText("synthetic hidden recovery").assertDoesNotExist()
        save()
        runBlocking {
            val saved = db.customFieldDao().getFieldsByEntryIdSync(savedId!!).filterNot { it.title == EntryContentFields.ORDER }.single()
            assertEquals(original.title, saved.title)
            assertEquals(original.isProtected, saved.isProtected)
            assertEquals("synthetic hidden recovery", security.decryptDataIfMonicaCiphertext(saved.value))
        }
        // Saving completes the screen; reopen it as navigation does before editing again.
        compose.runOnIdle { editId = savedId; savedId = null; editorOpenCount++ }
        addContent("CUSTOM_FIELDS")
        scrollTo(title)
        scrollTo(hasContentDescription(context.getString(R.string.delete))).performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.runOnIdle { savedId = null }
        save()
        runBlocking { assertTrue(db.customFieldDao().getFieldsByEntryIdSync(savedId!!).none { it.title != EntryContentFields.ORDER }) }
    }

    @Test fun embeddedPaymentAndAddressPreserveValuesProtectionAndCurrentOrder() {
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(true) }
        show()
        compose.waitForIdle()
        capture("content-current-editor-top")
        addContent("PAYMENT")
        openEmbeddedWalletSection("embedded_bank_editor", "extended")
        compose.onNodeWithTag("entry_extra_add").performScrollTo().performClick()
        compose.onNodeWithTag("entry_extra_choose_pin").performScrollTo().performClick()
        compose.onNodeWithTag("entry_extra_pin").performScrollTo().performTextReplacement("8642")
        compose.onNodeWithTag("entry_extra_pin").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.onNodeWithTag("entry_extra_reveal_pin").performScrollTo().performClick()
        compose.onNodeWithTag("entry_extra_pin").assertTextContains("8642")
        closeEmbeddedWalletSection()
        saveEmbeddedWallet("embedded_bank_editor")
        addContent("ADDRESS")
        openEmbeddedWalletSection("billing_item_editor", "custom_hidden")
        compose.onNodeWithTag("wallet_custom_name_0").performScrollTo().performTextReplacement("contact.passportNumber")
        compose.onNodeWithTag("wallet_custom_value_0").performScrollTo().performTextReplacement("EX1234567")
        compose.onNodeWithTag("wallet_custom_value_0").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        closeEmbeddedWalletSection()
        val apartment = hasText(context.getString(R.string.apartment)) and hasSetTextAction()
        compose.onNodeWithTag("billing_item_editor").performScrollToNode(apartment)
        compose.onNode(apartment and hasAnyAncestor(isDialog())).performScrollTo().performTextReplacement("Suite 9")
        compose.onNode(apartment and hasAnyAncestor(isDialog())).assert(
            SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        saveEmbeddedWallet("billing_item_editor")
        save()
        fun checkCurrent(order: String) = runBlocking {
            val fields = db.customFieldDao().getFieldsByEntryIdSync(savedId!!)
            assertEquals(order, fields.single { it.title == EntryContentFields.ORDER }.value)
            val payment = savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.BANK_CARD)
            assertEquals("8642", payment.itemData["pin"].toString().trim('"'))
            val address = savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.ADDRESS)
            val data = requireNotNull(takagi.ru.monica.data.model.CardWalletDataCodec.parseBillingAddressData(address.itemData.toString()))
            assertEquals("Suite 9", data.apartment)
            val passport = data.customFields.single()
            assertEquals("contact.passportNumber", passport.label)
            assertEquals("EX1234567", passport.value)
            assertTrue(passport.isProtected())
            assertEquals(2, fields.count { takagi.ru.monica.data.model.EmbeddedWalletContent.isMetadata(it.title) })
        }
        checkCurrent("PAYMENT,ADDRESS")
        val originalPayment = savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.BANK_CARD)
        val originalAddress = savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.ADDRESS)
        compose.runOnIdle { editId = savedId; savedId = null }
        addContent("PAYMENT")
        openEmbeddedWalletSection("embedded_bank_editor", "extended")
        compose.onNodeWithTag("entry_extra_pin").performScrollTo().assertExists()
        compose.onNodeWithTag("entry_extra_pin").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        closeEmbeddedWalletSection()
        saveEmbeddedWallet("embedded_bank_editor")
        addContent("ADDRESS")
        compose.onNodeWithTag("billing_item_editor").performScrollToNode(hasTestTag("wallet_content_custom"))
        compose.onNodeWithTag("wallet_content_custom").performClick()
        compose.onNodeWithTag("wallet_detail_custom").assertIsDisplayed()
        compose.onNodeWithTag("wallet_custom_name_0").performScrollTo().assertTextContains("contact.passportNumber")
        compose.onNodeWithTag("wallet_custom_value_0").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        closeEmbeddedWalletSection()
        saveEmbeddedWallet("billing_item_editor")
        capture("content-current-editor-overview")
        save()
        checkCurrent("PAYMENT,ADDRESS")
        val savedPayment = savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.BANK_CARD)
        val savedAddress = savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.ADDRESS)
        assertEquals(originalPayment.id, savedPayment.id)
        assertEquals(originalPayment.itemData, savedPayment.itemData)
        assertEquals(originalAddress.id, savedAddress.id)
        assertEquals(originalAddress.itemData, savedAddress.itemData)
        compose.runOnIdle { editId = savedId; savedId = null; editorOpenCount++ }
        scrollTo(hasTestTag("content_panel_ADDRESS"))
        val dragHandle = compose.onNodeWithTag("content_panel_ADDRESS")
        val distance = compose.onNodeWithTag("content_panel_ADDRESS").fetchSemanticsNode().boundsInRoot.height * 0.65f
        dragHandle.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -distance), delayMillis = 150)
            up()
        }
        capture("content-current-editor-after-drag")
        save()
        checkCurrent("AUTHENTICATOR,ADDRESS,PAYMENT")
        assertEquals(savedPayment.itemData, savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.BANK_CARD).itemData)
        assertEquals(savedAddress.itemData, savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.ADDRESS).itemData)
        compose.runOnIdle { editId = savedId; savedId = null; editorOpenCount++ }
        scrollTo(hasTestTag("content_actions_ADDRESS")).performClick()
        compose.onNodeWithTag("content_remove").performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        scrollTo(hasTestTag("content_panel_ADDRESS")).assertIsDisplayed()
        save()
        checkCurrent("AUTHENTICATOR,ADDRESS,PAYMENT")
        assertEquals(savedPayment.itemData, savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.BANK_CARD).itemData)
        assertEquals(savedAddress.itemData, savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.ADDRESS).itemData)
        compose.runOnIdle { editId = savedId; savedId = null; editorOpenCount++ }
        scrollTo(hasTestTag("content_actions_ADDRESS")).performClick()
        compose.onNodeWithTag("content_remove").performClick()
        compose.onNodeWithTag("content_remove_confirm").performClick()
        compose.onNodeWithTag("content_panel_ADDRESS").assertDoesNotExist()
        save()
        runBlocking {
            val fields = db.customFieldDao().getFieldsByEntryIdSync(savedId!!)
            assertEquals("AUTHENTICATOR,PAYMENT", fields.single { it.title == EntryContentFields.ORDER }.value)
            assertFalse(fields.any { it.title == takagi.ru.monica.data.model.EmbeddedWalletContent.fieldName(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.ADDRESS) })
            assertFalse(fields.any { it.title.startsWith("monica.content.contact.") || it.title.startsWith("monica.content.address.") })
            assertEquals(1, fields.count { takagi.ru.monica.data.model.EmbeddedWalletContent.isMetadata(it.title) })
            val remaining = savedEmbeddedWallet(takagi.ru.monica.data.model.EmbeddedWalletContent.Kind.BANK_CARD)
            assertEquals(savedPayment.id, remaining.id)
            assertEquals(savedPayment.itemData, remaining.itemData)
            assertEquals("8642", remaining.itemData["pin"].toString().trim('"'))
            val row = requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!))
            assertEquals("", row.email)
            assertEquals("", row.phone)
            assertEquals("", row.addressLine)
            assertEquals("", row.city)
            assertEquals("", row.zipCode)
            assertEquals("", row.country)
        }
    }

    /** Current embedded wallet paths; used only by the two current-contract acceptance methods. */
    private fun openEmbeddedWalletSection(root: String, key: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag(root).performScrollToNode(hasTestTag("item_editor_add_content"))
        compose.onNodeWithTag("item_editor_add_content").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("item_editor_choose_$key").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("item_editor_choose_$key").performScrollTo().performClick()
        compose.onNodeWithTag("wallet_detail_${if (key.startsWith("custom_")) "custom" else key}").assertIsDisplayed()
    }

    private fun closeEmbeddedWalletSection() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("wallet_detail_done").performClick()
        compose.waitForIdle()
    }

    private fun embeddedWalletField(root: String, tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(root).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun saveEmbeddedWallet(root: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNode(hasContentDescription(context.getString(R.string.save)) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag(root).fetchSemanticsNodes().isEmpty() }
    }

    private fun savedEmbeddedWallet(kind: takagi.ru.monica.data.model.EmbeddedWalletContent.Kind): takagi.ru.monica.data.model.EmbeddedWalletContent.Snapshot = runBlocking {
        val field = db.customFieldDao().getFieldsByEntryIdSync(savedId!!).single {
            it.title == takagi.ru.monica.data.model.EmbeddedWalletContent.fieldName(kind)
        }
        assertTrue("Embedded $kind envelope must stay protected", field.isProtected)
        val decoded = takagi.ru.monica.data.model.EmbeddedWalletContent.read(security.decryptDataIfMonicaCiphertext(field.value))
        assertTrue("Embedded $kind must remain readable", decoded is takagi.ru.monica.data.model.EmbeddedWalletContent.ReadResult.Available)
        (decoded as takagi.ru.monica.data.model.EmbeddedWalletContent.ReadResult.Available).snapshot
    }

    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val editor = activeScroll()
        editor.performScrollToNode(matcher)
        val scoped = if (compose.onAllNodesWithTag("content_detail_scroll").fetchSemanticsNodes().isNotEmpty()) matcher and hasAnyAncestor(hasTestTag("content_detail_scroll")) else matcher
        val node = compose.onNode(scoped)
        compose.bringAboveFloatingActions(node, editor)
        return node
    }

    private fun closeContent() {
        if (compose.onAllNodesWithTag("embedded_bank_editor").fetchSemanticsNodes().isNotEmpty()) {
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNode(hasContentDescription(context.getString(R.string.save)) and
                hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("embedded_bank_editor").fetchSemanticsNodes().isEmpty() }
        }
        if (compose.onAllNodesWithTag("content_detail_back").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("content_detail_back").performClick()
            compose.waitForIdle()
        }
    }
    private fun activeScroll(): SemanticsNodeInteraction =
        if (compose.onAllNodesWithTag("content_detail_scroll").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("content_detail_scroll")
        else compose.onNode(hasTestTag("password_classic_editor") or hasTestTag("password_content_editor"))

    private fun addContent(section: String) {
        if (section == "AUTHENTICATOR") {
            closeContent()
            openOtpSettings()
            return
        }
        closeContent()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scrollTo(hasTestTag("password_content_add")).performClick()
        compose.onNodeWithTag("password_content_choose_$section").performScrollTo().performClick()
    }

    @Test fun onDemandPaymentAndAuthenticatorSaveReopenAndAppearTogetherInDetails() {
        runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(true) }
        show("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP")
        addContent("ATTACHMENTS")
        captureAttachmentFooter("content-attachments-large")
        closeContent()
        addContent("PAYMENT")
        scroll("entry_payment_number").performTextReplacement("4242 4242 4242 4242")
        scroll("entry_payment_holder").performTextReplacement("ALICE EXAMPLE")
        scroll("entry_payment_expiry").performTextReplacement("09/2030")
        scroll("entry_payment_cvv").performTextReplacement("123")
        save()
        val original = payload()
        runBlocking {
            val entry = requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!))
            assertEquals("4242424242424242", entry.creditCardNumber)
            assertEquals("ALICE EXAMPLE", entry.creditCardHolder)
            assertEquals("09/2030", entry.creditCardExpiry)
            assertEquals("123", entry.creditCardCVV)
        }
        compose.runOnIdle { editId = savedId; savedId = null }
        if (compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty()) addContent("AUTHENTICATOR")
        scroll("otp_type_selector")
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("password_otp_secret") and hasText(original.secret)).fetchSemanticsNodes().isNotEmpty()
        }
        closeContent()
        val paymentTitle = context.getString(R.string.payment_info)
        compose.onNodeWithTag("password_content_editor").performScrollToNode(hasText(paymentTitle))
        compose.bringAboveFloatingActions(compose.onNodeWithText(paymentTitle), compose.onNodeWithTag("password_content_editor"))
        compose.onNodeWithText(paymentTitle).performClick()
        scroll("entry_payment_number")
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("entry_payment_number") and hasText("4242424242424242"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        save()
        assertEquals(original, payload())
        compose.runOnIdle { detailVisible = true }
        compose.waitUntil(20_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("password_authenticator_card"))
        compose.onNodeWithTag("password_authenticator_card").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("password_payment_face"))
        compose.onNodeWithTag("password_payment_face").assertIsDisplayed()
        compose.onNodeWithText("4242424242424242").assertDoesNotExist()
        capture("payment-and-otp-detail")
    }

    private fun show(qr: String? = null) {
        compose.setContent {
            key(editId, editorOpenCount) {
                CompositionLocalProvider(LocalUiSecurityManager provides security) {
                    MaterialTheme {
                        if (detailVisible) {
                            takagi.ru.monica.ui.screens.PasswordDetailScreen(model, passwordId = requireNotNull(savedId),
                                biometricEnabled = false, onNavigateBack = {}, onEditPassword = {})
                        } else AddEditPasswordScreen(viewModel = model, passwordId = editId,
                            initialStorageExplicit = true,
                            initialDraft = if (editId == null) AddEditPasswordInitialDraft(
                                title = "OTP fixture", username = "alice", password = "fixture-only-password") else null,
                            pendingQrResult = if (editId == null) qr else null,
                            onSaveCompleted = { savedId = it }, onNavigateBack = {})
                    }
                }
            }
        }
    }

    private fun scroll(tag: String): SemanticsNodeInteraction {
        if (tag in setOf("password_otp_secret", "password_otp_settings", "password_otp_inline_preview")) closeContent()
        else if ((tag.startsWith("otp_") || tag == "password_otp_advanced") &&
            compose.onAllNodesWithTag("content_detail_scroll").fetchSemanticsNodes().isEmpty()) {
            openOtpSettings()
        }
        if (compose.onAllNodesWithTag("embedded_bank_editor").fetchSemanticsNodes().isNotEmpty()) {
            val node = compose.onNodeWithTag(tag).performScrollTo()
            compose.bringAboveFloatingActions(node, compose.onNodeWithTag("embedded_bank_editor"))
            return node
        }
        val editor = activeScroll()
        editor.performScrollToNode(hasTestTag(tag))
        val node = compose.onNodeWithTag(tag)
        compose.bringAboveFloatingActions(node, editor)
        return node
    }
    private fun openOtpSettings() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("password_otp_settings").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("editor_advanced_open").fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasTestTag("editor_advanced_open") and
            hasAnyAncestor(hasTestTag("editor_action_menu"))).assertIsDisplayed().performClick()
        compose.onNodeWithTag("content_detail_scroll").assertExists()
    }

    private fun save() {
        closeContent()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("password_editor_save").assertIsDisplayed().performClick()
        compose.waitUntil(20_000) { savedId != null }
    }
    private fun payload() = runBlocking {
        val entry = requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId!!))
        requireNotNull(TotpDataResolver.fromAuthenticatorKey(security.decryptDataIfMonicaCiphertext(entry.authenticatorKey)))
    }

    private fun verify(type: OtpType, onDemand: Boolean = false) {
        if (onDemand) runBlocking { takagi.ru.monica.utils.SettingsManager(context).updatePasswordContentEditorEnabled(true) }
        show()
        addContent("AUTHENTICATOR")
        scroll("password_otp_secret").performTextReplacement(
            if (type == OtpType.MOTP) "a1b2c3d4e5f60708" else "JBSWY3DPEHPK3PXP")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("otp_type_selector").performClick()
        OtpType.entries.forEach { compose.onNodeWithTag("otp_type_${it.name}").assertExists() }
        compose.onNodeWithTag("otp_type_${type.name}").performClick()
        if (type == OtpType.HOTP) scroll("otp_counter").performTextReplacement("42")
        if (type in listOf(OtpType.MOTP, OtpType.YANDEX)) {
            scroll("otp_pin").performTextReplacement("0421")
            compose.onNodeWithTag("otp_pin").assert(
                SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        }
        closeContent()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("otp_type_selector")
        capture((if (onDemand) "content-" else "") + type.name.lowercase())
        save()
        val original = payload()
        assertEquals(type, original.otpType)
        if (type == OtpType.HOTP) assertEquals(42L, original.counter)
        if (type in listOf(OtpType.MOTP, OtpType.YANDEX)) assertEquals("0421", original.pin)
        compose.runOnIdle { editId = savedId; savedId = null }
        if (compose.onAllNodesWithTag("password_content_editor").fetchSemanticsNodes().isNotEmpty()) addContent("AUTHENTICATOR")
        scroll("otp_type_selector")
        // Wait for the async existing-entry load, then save again without changing OTP.
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("password_otp_secret") and hasText(original.secret))
                .fetchSemanticsNodes().isNotEmpty()
        }
        save()
        assertEquals(original, payload())
    }

    @Test fun hotpQrRestoresCounterAndAlgorithmBeforeChangingSecret() {
        show("otpauth://hotp/Example:alice?secret=JBSWY3DPEHPK3PXP&counter=99&digits=8&algorithm=SHA512")
        addContent("AUTHENTICATOR")
        scroll("otp_counter").assertTextContains("99")
        scroll("password_otp_secret").performTextReplacement("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        save()
        val result = payload()
        assertEquals(OtpType.HOTP, result.otpType)
        assertEquals(99L, result.counter)
        assertEquals(8, result.digits)
        assertEquals("SHA512", result.algorithm)
    }

    @Test fun switchingAndRemovingCredentialsKeepsOtpDraftsSeparate() {
        show()
        addContent("AUTHENTICATOR")
        scroll("password_otp_secret").performTextReplacement("JBSWY3DPEHPK3PXP")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("otp_type_selector").performClick()
        compose.onNodeWithTag("otp_type_HOTP").performClick()
        scroll("otp_counter").performTextReplacement("42")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        closeContent()
        compose.onNodeWithContentDescription(context.getString(R.string.add_credential)).performClick()
        addContent("AUTHENTICATOR")
        scroll("password_otp_secret").performTextReplacement("a1b2c3d4e5f60708")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        scroll("otp_type_selector").performClick()
        compose.onNodeWithTag("otp_type_MOTP").performClick()
        scroll("otp_pin").performTextReplacement("0421")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        fun select(index: Int) {
            closeContent()
            compose.onNodeWithTag("password_credential_switcher").performClick()
            compose.onNodeWithTag("password_credential_$index").performClick()
            addContent("AUTHENTICATOR")
        }
        select(0)
        scroll("otp_counter").assertTextContains("42")
        select(1)
        scroll("otp_pin").assertTextContains("0421")
        closeContent()
        compose.onNodeWithTag("password_credential_switcher").performClick()
        compose.onNodeWithTag("password_credential_remove").performClick()
        compose.onNodeWithTag("password_credential_confirm_remove").performClick()
        addContent("AUTHENTICATOR")
        scroll("otp_counter").assertTextContains("42")
        save()
        assertEquals(OtpType.HOTP, payload().otpType)
        assertEquals(42L, payload().counter)
    }

    private fun captureAttachmentFooter(name: String) {
        val editor = activeScroll()
        val buttonText = context.getString(R.string.attachments_add)
        editor.performScrollToNode(hasText(buttonText))
        compose.bringAboveFloatingActions(compose.onNodeWithText(buttonText), editor)
        capture(name)
    }

    private fun capture(name: String) {
        val dir = File(context.filesDir, "password-otp-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

package takagi.ru.monica.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import takagi.ru.monica.data.*
import takagi.ru.monica.data.model.GpgEntryFields
import takagi.ru.monica.repository.*
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.screens.GpgKeyScreen
import takagi.ru.monica.utils.*
import takagi.ru.monica.viewmodel.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class GpgKeyFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: PasswordDatabase
    private lateinit var passwords: PasswordViewModel
    private lateinit var editor: GpgEditorViewModel
    @Before fun setup() {
        runBlocking {
            assertTrue("Standalone UI fixture requires prepared secure storage",
                takagi.ru.monica.security.SecureStorageStartup.prepareWithRetry(context) is
                    takagi.ru.monica.security.SecureStartupResult.Ready)
        }
        db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        val security = SecurityManager(context)
        compose.runOnIdle {
            passwords = PasswordViewModel(PasswordRepository(db.passwordEntryDao(), categoryDao = db.categoryDao()),
                security, customFieldRepository = CustomFieldRepository(db.customFieldDao()),
                strings = AppLocaleStringResolver(context))
            editor = GpgEditorViewModel()
        }
    }
    @After fun cleanup() { passwords.viewModelScope.cancel(); editor.viewModelScope.cancel(); db.close() }

    @Test fun generatorResultCreatesUsernameDraftInIndependentEditor() {
        val generator = GeneratorViewModel()
        try {
            compose.setContent { TestTheme {
                takagi.ru.monica.ui.screens.GeneratorScreen(onNavigateBack = {},
                    passwordViewModel = passwords, viewModel = generator)
            } }
            compose.waitUntil(10_000) { generator.symbolResult.value.isNotEmpty() }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            compose.runOnIdle { generator.updateSymbolResult("menu-user-fixture") }
            capture("generator-menu-before.png")
            compose.onNodeWithTag("generator_result").performClick()
            capture("generator-menu-after.png")
            compose.onNode(isPopup()).assertExists()
            compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.generator_create_username)).performClick()
            compose.onNode(isDialog()).assertExists()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("menu-user-fixture") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText("menu-user-fixture") and hasSetTextAction()).assertExists()
            compose.onNodeWithTag("password_editor_save").assertIsDisplayed()
        } finally { generator.viewModelScope.cancel() }
    }

    @Test fun generatorGpgSaveActionIsVisibleInIndependentEditor() {
        val generator = GeneratorViewModel()
        try {
            compose.setContent { TestTheme {
                takagi.ru.monica.ui.screens.GeneratorScreen(onNavigateBack = {},
                    passwordViewModel = passwords, viewModel = generator)
            } }
            compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.generator_switch_type)).performClick()
            compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_title)).performClick()
            compose.onNodeWithTag("gpg_name").performTextInput("Dialog fixture")
            compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.regenerate)).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("gpg_result").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("gpg_result").performClick()
            compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_create_entry)).performScrollTo().performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithTag("password_editor_save").assertIsDisplayed()
            val save = compose.onNodeWithTag("password_editor_save").getUnclippedBoundsInRoot()
            assertTrue("Save retains a full touch target", save.bottom.value - save.top.value >= 48f)
            compose.onNodeWithTag("password_editor_save").performClick()
            compose.waitUntil(30_000) { runBlocking { db.passwordEntryDao().getAllPasswordEntriesSync().any { it.loginType == "GPG_KEY" } } }
            runBlocking {
                val saved = db.passwordEntryDao().getAllPasswordEntriesSync().single { it.loginType == "GPG_KEY" }
                val loaded = requireNotNull(passwords.getPasswordEntryById(saved.id))
                val fields = passwords.getCustomFieldsByEntryIdSync(saved.id).associate { it.title to it.value }
                assertTrue(GpgKeyGenerator.parse(loaded.password).userId.contains("Dialog fixture"))
                assertEquals(GpgKeyGenerator.parse(loaded.password).fingerprint,
                    GpgKeyGenerator.parse(GpgEntryFields.publicKey(fields)).fingerprint)
            }
        } finally { generator.viewModelScope.cancel() }
    }

    @Test fun resultCardAndMoreOpenGroupedActions() {
        val key = GpgKeyGenerator.Key("-----BEGIN PGP PUBLIC KEY BLOCK-----", "private-test", "AB12CD34EF56", "Menu test")
        var copied: Pair<String, Boolean>? = null
        var exported: takagi.ru.monica.ui.screens.GpgExportKind? = null
        var created = false
        compose.setContent { TestTheme {
            takagi.ru.monica.ui.screens.GpgKeyResult(key, false, {},
                { value, secret -> copied = value to secret }, { exported = it }, { created = true })
        } }
        fun open() { compose.onNodeWithTag("gpg_result").performClick() }
        fun action(id: Int) { compose.onNodeWithText(context.getString(id)).performScrollTo().performClick() }
        open()
        capture("result-actions.png")
        action(takagi.ru.monica.R.string.gpg_copy_public)
        assertEquals(key.publicKey to false, copied)
        open(); action(takagi.ru.monica.R.string.gpg_copy_fingerprint)
        assertEquals(key.fingerprint to false, copied)
        compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.more_options)).performClick()
        action(takagi.ru.monica.R.string.gpg_copy_private)
        assertEquals(key.privateKey to true, copied)
        compose.onNodeWithTag("gpg_private").assertDoesNotExist()
        open(); action(takagi.ru.monica.R.string.gpg_export_public)
        assertEquals(takagi.ru.monica.ui.screens.GpgExportKind.PUBLIC, exported)
        open(); action(takagi.ru.monica.R.string.gpg_export_private)
        assertEquals(takagi.ru.monica.ui.screens.GpgExportKind.PRIVATE, exported)
        open(); action(takagi.ru.monica.R.string.gpg_export_pair)
        assertEquals(takagi.ru.monica.ui.screens.GpgExportKind.PAIR, exported)
        open(); action(takagi.ru.monica.R.string.gpg_create_entry)
        assertTrue(created)
    }

    @Test fun publicOnlyResultOmitsPrivateActions() {
        val key = GpgKeyGenerator.Key("public", "", "AB12", "Public key")
        compose.setContent { TestTheme {
            takagi.ru.monica.ui.screens.GpgKeyResult(key, false, {}, { _, _ -> }, {})
        } }
        compose.onNodeWithTag("gpg_result").performClick()
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_copy_private)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_export_private)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_export_pair)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_create_entry)).assertDoesNotExist()
    }

    @Test fun actionsRemainReachableInDarkLargeText() {
        var created = false
        val key = GpgKeyGenerator.Key("-----BEGIN PGP PUBLIC KEY BLOCK-----", "secret", "AB12CD34EF567890AB12CD34EF567890AB12CD34", "Menu test")
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, fontScale = 1.5f)) {
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    takagi.ru.monica.ui.screens.GpgKeyResult(key, false, {}, { _, _ -> }, {}, { created = true })
                }
            }
        }
        compose.onNodeWithTag("gpg_result").performClick()
        capture("result-actions-dark-large.png")
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_create_entry)).performScrollTo().performClick()
        assertTrue(created)
    }

    @Test fun generatedResultCopiesActualClipboardAndCreatesEntry() {
        val key = GpgKeyGenerator.Key("public fixture", "private fixture", "AB12CD34", "Clipboard test")
        var created = false
        compose.setContent { TestTheme {
            takagi.ru.monica.ui.screens.GpgGeneratedResult(key) { created = true }
        } }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        for ((id, expected) in listOf(
            takagi.ru.monica.R.string.gpg_copy_public to key.publicKey,
            takagi.ru.monica.R.string.gpg_copy_fingerprint to key.fingerprint,
            takagi.ru.monica.R.string.gpg_copy_private to key.privateKey)) {
            compose.onNodeWithTag("gpg_result").performClick()
            compose.onNodeWithText(context.getString(id)).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        }
        compose.onNodeWithTag("gpg_result").performClick()
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_create_entry)).performScrollTo().performClick()
        assertTrue(created)
        compose.runOnIdle { ClipboardUtils.cancelPendingAutoClear(); clipboard.clearPrimaryClip() }
    }

    @Test fun creationTypeMenuOffersGpg() {
        var chosen: takagi.ru.monica.ui.components.EntryTypeChipOption? = null
        compose.setContent { TestTheme {
            takagi.ru.monica.ui.components.EntryTypeChip(
                current = takagi.ru.monica.ui.components.EntryTypeChipOption.PASSWORD,
                showGpg = true, onSelect = { chosen = it })
        } }
        compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.entry_type_chip_content_description)).performClick()
        compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_title)).performClick()
        assertEquals(takagi.ru.monica.ui.components.EntryTypeChipOption.GPG_KEY, chosen)
    }

    @Test fun generatorTypeMenuOpensGpgForm() {
        val generator = GeneratorViewModel()
        try {
            compose.setContent { TestTheme {
                takagi.ru.monica.ui.screens.GeneratorScreen(onNavigateBack = {}, passwordViewModel = passwords, viewModel = generator)
            } }
            compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.generator_switch_type)).performClick()
            compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_title)).performClick()
            compose.onNodeWithTag("gpg_name").assertIsDisplayed()
            assertEquals(GeneratorType.GPG_KEY, generator.selectedGenerator.value)
            capture("generator.png")
        } finally { generator.viewModelScope.cancel() }
    }

    @Test fun adaptiveGeneratorProducesAnInteractiveGpgResult() {
        val generator = GeneratorViewModel()
        generator.updateSelectedGenerator(GeneratorType.GPG_KEY)
        try {
            compose.setContent { TestTheme {
                takagi.ru.monica.ui.screens.GeneratorScreen(onNavigateBack = {}, passwordViewModel = passwords, viewModel = generator)
            } }
            compose.onNodeWithTag("gpg_name").performTextInput("Adaptive layout test")
            compose.onNodeWithContentDescription(context.getString(takagi.ru.monica.R.string.regenerate)).performClick()
            compose.waitUntil(60_000) {
                compose.onAllNodesWithTag("gpg_result").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("gpg_result").performScrollTo().assertIsDisplayed()
            capture("adaptive-generator-result.png")
            compose.onNodeWithTag("gpg_result").performClick()
            compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_copy_fingerprint))
                .performScrollTo().assertIsDisplayed()
            capture("adaptive-generator-menu.png")
            compose.onNodeWithText(context.getString(takagi.ru.monica.R.string.gpg_copy_fingerprint)).performClick()
            compose.onNodeWithTag("gpg_result").assertIsDisplayed()
        } finally { generator.viewModelScope.cancel() }
    }

    @Test fun generateSaveReloadAndPreserveEncryptedPrivateKey() {
        val generated = GpgKeyGenerator.generate("Monica device test", "device@example.org", 3072, 365, "protected-fixture".toCharArray())
        editor.key = generated
        editor.title = "GPG test key"
        var id by mutableStateOf<Long?>(null)
        var session by mutableIntStateOf(0)
        var saved: Long? = null
        compose.setContent { TestTheme { key(session) {
            GpgKeyScreen(passwords, onBack = {}, passwordId = id, editor = editor, onSaved = { saved = it })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("template_field_publicKey") and hasText(generated.publicKey)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("password_editor_favorite").performClick()
        compose.onNodeWithTag("template_field_privateKey").performScrollTo().assertTextContains(generated.privateKey)
        closeFocusedKeyboard()
        compose.onNodeWithTag("password_editor_save").performClick()
        compose.waitUntil(30_000) { saved != null }
        val savedId = requireNotNull(saved)
        runBlocking {
            val raw = requireNotNull(db.passwordEntryDao().getPasswordEntryById(savedId))
            assertTrue(raw.isFavorite)
            assertFalse(raw.password.contains("PRIVATE KEY"))
            val decrypted = requireNotNull(passwords.getPasswordEntryById(savedId))
            assertEquals(generated.privateKey, decrypted.password)
            val ring = org.bouncycastle.openpgp.PGPSecretKeyRing(org.bouncycastle.openpgp.PGPUtil.getDecoderStream(decrypted.password.byteInputStream()), org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator())
            assertEquals(org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags.AES_256, ring.secretKey.keyEncryptionAlgorithm)
            val fields = passwords.getCustomFieldsByEntryIdSync(savedId).associate { it.title to it.value }
            assertEquals(generated.publicKey, GpgEntryFields.publicKey(fields))
            assertTrue(fields.values.none { it.contains("PRIVATE KEY") })
        }
        compose.runOnIdle { editor.key = null; id = savedId; session++ }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("template_field_publicKey") and hasText(generated.publicKey)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("password_editor_favorite").assertIsSelected()
        compose.onNodeWithTag("template_field_privateKey").performScrollTo().assertTextContains(generated.privateKey)
        capture("editor.png")
    }

    @Test fun newEntryAndGenerationSheetRemainUsableInDarkLargeText() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, fontScale = 1.5f)) {
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    GpgKeyScreen(passwords, onBack = {}, editor = editor)
                }
            }
        }
        assertHeadingFits()
        compose.onNodeWithTag("password_editor_favorite").assertIsDisplayed().performClick()
        compose.onNodeWithTag("password_editor_favorite").assertIsSelected()
        compose.onNodeWithTag("gpg_open_generation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("password_editor_save").assertIsDisplayed()
        capture("new-entry-dark-large.png")
        compose.onNodeWithTag("gpg_open_generation").performScrollTo().performClick()
        compose.onNodeWithTag("gpg_name").performScrollTo().assertIsDisplayed()
        capture("generation-dark-large.png")
        compose.onNodeWithTag("gpg_generate").performScrollTo().assertIsDisplayed()
    }

    @Test fun localizedEditorAndValidationRemainReachable() {
        var localeTag by mutableStateOf("lzh")
        fun localized(tag: String): android.content.Context {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            return context.createConfigurationContext(config)
        }
        compose.setContent {
            val host = androidx.compose.ui.platform.LocalContext.current
            val local = remember(localeTag, host) {
                val translated = localized(localeTag).resources
                object : android.content.ContextWrapper(host) {
                    override fun getResources(): android.content.res.Resources = translated
                    override fun getAssets(): android.content.res.AssetManager = translated.assets
                }
            }
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides local,
                androidx.compose.ui.platform.LocalResources provides local.resources,
                androidx.compose.ui.platform.LocalConfiguration provides local.resources.configuration,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)
            ) {
                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    key(localeTag) { GpgKeyScreen(passwords, onBack = {}, editor = editor) }
                }
            }
        }
        for (tag in listOf("lzh", "zh-Hant", "zh-CN", "de")) {
            compose.runOnIdle { localeTag = tag }
            val local = localized(tag)
            capture("entry-$tag.png")
            assertHeadingFits()
            val publicLabel = local.getString(takagi.ru.monica.R.string.content_block_public)
            assertNotEquals("Public key", publicLabel)
            compose.onNodeWithTag("template_field_publicKey").performScrollTo().assertTextContains(publicLabel)
            compose.onNodeWithTag("password_editor_favorite").assertIsDisplayed()
            val editorList = compose.onNode(hasTestTag("password_content_editor") or hasTestTag("password_classic_editor"))
            compose.onNodeWithTag("gpg_open_generation").performScrollTo()
            compose.bringAboveFloatingActions(compose.onNodeWithTag("gpg_open_generation"), editorList)
            compose.onNodeWithTag("gpg_open_generation").performClick()
            compose.onNodeWithTag("gpg_generate").performScrollTo().performClick()
            compose.onNodeWithText(local.getString(takagi.ru.monica.R.string.gpg_name_invalid))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("gpg_name").performScrollTo().performTextInput("ji")
            compose.onNodeWithTag("gpg_email").performScrollTo().performTextInput("dgbbd")
            compose.onNodeWithTag("gpg_generate").performScrollTo().performClick()
            compose.onNodeWithText(local.getString(takagi.ru.monica.R.string.gpg_email_invalid))
                .performScrollTo().assertIsDisplayed()
            capture("validation-$tag.png")
            closeFocusedKeyboard()
        }
    }

    private fun assertHeadingFits() {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("password_editor_heading").assertIsDisplayed().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
        ) { it(layouts) }
        assertTrue("Heading must expose its actual text layout", layouts.isNotEmpty())
        assertFalse("Heading must not be clipped or ellipsized", layouts.single().hasVisualOverflow)
    }

    @Composable private fun TestTheme(content: @Composable () -> Unit) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(density.density, fontScale = 1f)) {
            MaterialTheme(content = content)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File(context.getExternalFilesDir("gpg-verification"), name)
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { screenshot ->
            file.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }
}

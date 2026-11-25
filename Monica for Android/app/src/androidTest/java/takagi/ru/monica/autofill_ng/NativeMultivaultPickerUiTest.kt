package takagi.ru.monica.autofill_ng

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants
import takagi.ru.monica.data.*
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.DocumentType
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.SettingsManager
import uniffi.mdbx_ffi.MdbxObjectSummary
import uniffi.mdbx_ffi.MdbxObjectDisclosureLimits
import uniffi.mdbx_ffi.MdbxWriteCommand
import uniffi.mdbx_ffi.openVault
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import takagi.ru.monica.utils.StringResolver
import takagi.ru.monica.utils.WebDavKeePassSupport
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Actual V2 Activity, master-password verification, source menus and framework result values.
 * Uses only owned synthetic users; inherited fixture keeps native files isolated from Room.
 */
class NativeMultivaultPickerUiTest : MdbxAutofillFixture() {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appDb by lazy { PasswordDatabase.getDatabase(context) }
    private val settings by lazy { SettingsManager(context) }
    private val preferences by lazy { AutofillPreferences(context) }
    private val suffix = UUID.randomUUID().toString().take(8)
    private val localTitle = "LocalScope-$suffix"
    private val scenarios = mutableListOf<ActivityScenario<AutofillPickerActivityV2>>()
    private val ownedPasswordIds = mutableListOf<Long>()
    private val nativeFixtures = mutableListOf<Fixture>()
    private val nativeBefore = mutableMapOf<Long, Pair<MdbxObjectSummary?, String>>()
    private val ownedBwIds = mutableListOf<Long>()
    private data class KdbxFixture(val id: Long, val name: String, val title: String, val uri: Uri, val bytes: ByteArray)
    private val keepassFixtures = mutableListOf<KdbxFixture>()
    private val remoteSourceIds = mutableListOf<Long>()
    private val remoteMirrorFiles = mutableListOf<File>()
    private var oldSettings: AppSettings? = null
    private var oldScope: AutofillScope? = null
    private var baselinePasswords: List<PasswordEntry>? = null
    private var baselineItems: List<SecureItem>? = null

    @Before fun prepareUi(): Unit = runBlocking {
        // The inherited @Before already checks the explicitly owned secondary user and MDK.
        check(appDb.localKeePassDatabaseDao().getAllDatabases().first().isEmpty())
        check(appDb.localMdbxDatabaseDao().getAllDatabases().first().isEmpty())
        check(appDb.bitwardenVaultDao().getAllVaults().isEmpty())
        baselinePasswords = appDb.passwordEntryDao().getAllPasswordEntriesSync()
        baselineItems = appDb.secureItemDao().getAllItemsIncludingArchived().first()
        oldSettings = settings.settingsFlow.first()
        oldScope = preferences.v2DefaultScope.first()
        settings.updateBiometricEnabled(false)
        settings.updateAutofillAuthRequired(true)
        settings.updateAutofillKeepUnlocked(false)
        settings.updateAutoLockMinutes(5)
        SessionManager.updateAutoLockTimeout(5)
        preferences.setV2DefaultScope(AutofillScope.Local)
        AutofillSessionGrants.clear()
        ownedPasswordIds += appDb.passwordEntryDao().insertPasswordEntry(PasswordEntry(
            title = localTitle, website = "https://native.example", username = "local-synthetic",
            password = security.encryptData("local-synthetic-password")))
    }

    @After fun cleanupUi(): Unit = runBlocking {
        if (oldSettings == null) return@runBlocking
        // JUnit runs this subclass cleanup before the inherited native-file cleanup.
        scenarios.asReversed().forEach { it.close() }
        AutofillSessionGrants.clear()
        check(security.unlockVaultWithPassword(MASTER))
        ownedPasswordIds.forEach { appDb.passwordEntryDao().deletePasswordEntryById(it) }
        nativeFixtures.forEach { appDb.localMdbxDatabaseDao().deleteDatabaseById(it.databaseId) }
        ownedBwIds.forEach { id -> appDb.bitwardenVaultDao().getVaultById(id)?.let { appDb.bitwardenVaultDao().delete(it) } }
        keepassFixtures.forEach {
            KeePassKdbxService.invalidateProcessCache(it.id)
            appDb.localKeePassDatabaseDao().deleteDatabaseById(it.id)
            context.contentResolver.delete(it.uri, null, null)
        }
        remoteSourceIds.forEach { appDb.keepassRemoteSourceDao().deleteSourceById(it) }
        remoteMirrorFiles.forEach {
            check(it.canonicalFile.parentFile == context.filesDir.canonicalFile && it.name.startsWith("autofill-webdav-331-"))
            if (it.exists()) check(it.delete())
        }
        oldScope?.let { preferences.setV2DefaultScope(it) }
        oldSettings?.let {
            settings.updateBiometricEnabled(it.biometricEnabled)
            settings.updateAutofillAuthRequired(it.autofillAuthRequired)
            settings.updateAutofillKeepUnlocked(it.autofillKeepUnlocked)
            settings.updateAutoLockMinutes(it.autoLockMinutes)
            SessionManager.updateAutoLockTimeout(it.autoLockMinutes)
        }
        baselinePasswords?.let { assertEquals("Preserve unrelated Room passwords", it,
            appDb.passwordEntryDao().getAllPasswordEntriesSync()) }
        baselineItems?.let { assertEquals("Native wallet fills must not create Room copies", it,
            appDb.secureItemDao().getAllItemsIncludingArchived().first()) }
    }

    @Test fun mdbxDatabaseMenuSelectsExactNativeLoginAndReturnsLiteralDataset() {
        val first = runBlocking { native("MDBX-A-$suffix", "Native-A-$suffix", payload = login("first-user", "first-secret")) }
        val second = runBlocking { native("MDBX-B-$suffix", "Native-B-$suffix", payload = login("second-user", "MDK|native-literal-secret")) }
        val targets = targets(2)
        val scenario = launch(targets, listOf("USERNAME", "PASSWORD"))
        waitText(localTitle)
        selectSource(R.string.filter_mdbx)
        waitText("Native-A-$suffix")
        waitText("Native-B-$suffix")
        selectDatabase(text(R.string.password_picker_all_databases), "MDBX-A-$suffix")
        waitText("Native-A-$suffix")
        assertAbsent("Native-B-$suffix")
        assertAbsent(localTitle)
        assertScope(AutofillScope.Mdbx(first.databaseId))
        selectDatabase("MDBX-A-$suffix", "MDBX-B-$suffix")
        waitText("Native-B-$suffix")
        assertAbsent("Native-A-$suffix")
        assertScope(AutofillScope.Mdbx(second.databaseId))
        selectLoginAndFill("Native-B-$suffix")
        assertDataset(scenario, targets, listOf("second-user", "MDK|native-literal-secret"))
        assertNativeUnchanged()
    }

    @Test fun mdbxBankCardSelectionReturnsEveryRequestedNativeValue() {
        val values = listOf("4111111111111111", "MDK|literal-card-holder", "12", "2030", "123")
        val data = BankCardData(cardNumber = values[0], cardholderName = values[1],
            expiryMonth = values[2], expiryYear = values[3], cvv = values[4])
        wallet("card", "bank_card", Json.encodeToString(data), R.string.item_type_bank_card,
            listOf("cc-number", "cc-name", "cc-exp-month", "cc-exp-year", "cc-csc"), values)
    }

    @Test fun mdbxDocumentSelectionReturnsEveryRequestedNativeValue() {
        val values = listOf("V2|literal-document-number", "Synthetic Person", "Synthetic", "Person")
        val data = DocumentData(documentType = DocumentType.PASSPORT, documentNumber = values[0],
            fullName = values[1], firstName = values[2], lastName = values[3])
        wallet("document-ref", "document", Json.encodeToString(data), R.string.item_type_document,
            listOf("document-number", "name", "given-name", "family-name"), values)
    }

    @Test fun mdbxBillingAddressSelectionReturnsEveryRequestedNativeValue() {
        val values = listOf("C2|literal-native-street", "Suite 328", "Synthetic City", "32800", "Synthetic Address Owner")
        val data = BillingAddressData(streetAddress = values[0], apartment = values[1], city = values[2],
            postalCode = values[3], fullName = values[4])
        wallet("billing-address", "billing_address", Json.encodeToString(data), R.string.billing_address,
            listOf("address-line1", "address-line2", "address-level2", "postal-code", "name"), values)
    }

    @Test fun concreteKeePassDatabaseChangesExcludePreviousRowsAndReturnSelectedDataset() {
        val first = runBlocking { keepass("KDBX-A-$suffix", "Kdbx-A-$suffix", "kdbx-a", "kdbx-secret-a") }
        val second = runBlocking { keepass("KDBX-B-$suffix", "Kdbx-B-$suffix", "kdbx-b", "kdbx-secret-b") }
        val targets = targets(2)
        val scenario = launch(targets, listOf("USERNAME", "PASSWORD"))
        waitText(localTitle)
        selectSource(R.string.filter_keepass)
        waitText(first.title)
        waitText(second.title)
        selectDatabase(text(R.string.password_picker_all_databases), first.name)
        waitText(first.title)
        assertAbsent(second.title)
        assertAbsent(localTitle)
        assertScope(AutofillScope.KeePass(first.id))
        selectDatabase(first.name, second.name)
        waitText(second.title)
        assertAbsent(first.title)
        assertScope(AutofillScope.KeePass(second.id))
        selectLoginAndFill(second.title)
        assertDataset(scenario, targets, listOf("kdbx-b", "kdbx-secret-b"))
        keepassFixtures.forEach { assertArrayEquals(it.bytes, context.contentResolver.openInputStream(it.uri)!!.use { input -> input.readBytes() }) }
    }

    @Test fun concreteOfflineBitwardenVaultChangesKeepOnlySelectedMirrorAndReturnDataset() {
        val first = runBlocking { bitwarden("BW-A-$suffix", "Bw-A-$suffix", "bw-a", "bw-secret-a") }
        val second = runBlocking { bitwarden("BW-B-$suffix", "Bw-B-$suffix", "bw-b", "bw-secret-b") }
        val targets = targets(2)
        val scenario = launch(targets, listOf("USERNAME", "PASSWORD"))
        waitText(localTitle)
        selectSource(R.string.filter_bitwarden)
        waitText("Bw-A-$suffix")
        waitText("Bw-B-$suffix")
        selectDatabase(text(R.string.password_picker_all_vaults), "BW-A-$suffix")
        waitText("Bw-A-$suffix")
        assertAbsent("Bw-B-$suffix")
        assertScope(AutofillScope.Bitwarden(first))
        selectDatabase("BW-A-$suffix", "BW-B-$suffix")
        waitText("Bw-B-$suffix")
        assertAbsent("Bw-A-$suffix")
        assertScope(AutofillScope.Bitwarden(second))
        selectSource(R.string.filter_monica)
        waitText(localTitle)
        assertAbsent("Bw-A-$suffix")
        assertAbsent("Bw-B-$suffix")
        selectSource(R.string.filter_bitwarden)
        selectDatabase(text(R.string.password_picker_all_vaults), "BW-B-$suffix")
        waitText("Bw-B-$suffix")
        assertAbsent("Bw-A-$suffix")
        selectLoginAndFill("Bw-B-$suffix")
        assertDataset(scenario, targets, listOf("bw-b", "bw-secret-b"))
        runBlocking {
            ownedBwIds.forEach { id ->
                val vault = requireNotNull(appDb.bitwardenVaultDao().getVaultById(id))
                assertTrue(vault.isLocked)
                assertFalse(vault.isConnected)
                assertNull(vault.encryptedAccessToken)
            }
        }
    }

    private fun selectLoginAndFill(title: String) {
        // Login rows open the shared action dialog; selecting a row alone does not fill.
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithTag("autofill_password_actions").assertIsDisplayed()
        compose.onNode(hasText(title) and hasAnyAncestor(hasTestTag("autofill_password_actions")))
            .assertIsDisplayed()
        compose.onNodeWithTag("autofill_action_default").assertIsDisplayed().performClick()
    }

    private fun wallet(type: String, kind: String, data: String, label: Int, hints: List<String>, values: List<String>) {
        val title = "NativeWallet-$suffix"
        val fixture = runBlocking { native("MDBX-Wallet-$suffix", title, type,
            buildJsonObject { put("kind", kind); put("item_data", data) }.toString()) }
        runBlocking { preferences.setV2DefaultScope(AutofillScope.Mdbx(fixture.databaseId)) }
        val targets = targets(hints.size)
        val scenario = launch(targets, hints)
        selectContent(label)
        waitText(title)
        assertAbsent(localTitle)
        assertScope(AutofillScope.Mdbx(fixture.databaseId))
        compose.onNodeWithText(title).performClick()
        assertDataset(scenario, targets, values)
        assertNativeUnchanged()
    }

    private fun targets(count: Int): ArrayList<AutofillId> = compose.runOnUiThread {
        ArrayList((0 until count).map { android.widget.EditText(context).autofillId }).also {
            assertEquals("Each requested field must have a distinct framework ID", count, it.toSet().size)
        }
    }

    @Test fun refreshedFrameworkPickerRecommendsAndSearchesBindingsAcrossAllProviders() {
        val app = instrumentation.context.packageName
        val packages = "com.synthetic.other|androidapp://$app"
        runBlocking {
            val local = requireNotNull(appDb.passwordEntryDao().getPasswordEntryById(ownedPasswordIds.first()))
            appDb.passwordEntryDao().updatePasswordEntry(local.copy(appPackageName = packages))
        }
        val kdbx = runBlocking { keepass("Bound-KDBX-$suffix", "Bound-Kdbx-$suffix", "synthetic-kdbx", "synthetic-secret", packages) }
        val bw = runBlocking { bitwarden("Bound-BW-$suffix", "Bound-Bw-$suffix", "synthetic-bw", "synthetic-secret", packages) }
        runBlocking { native("Bound-MDBX-$suffix", "Bound-Mdbx-$suffix", payload = buildJsonObject {
            put("username", "synthetic-mdbx"); put("password_plain", "synthetic-secret"); put("app_package_name", packages)
        }.toString()) }
        launch(targets(2), listOf("USERNAME", "PASSWORD"), AutofillPickerActivityV2.Args(
            applicationId = app, autofillIds = targets(2), autofillHints = arrayListOf("USERNAME", "PASSWORD"),
            suggestedPasswordIds = longArrayOf(), refreshLoginSuggestions = true, rememberLastFilled = false))
        val label = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(app, 0)).toString()
        fun verify(title: String) {
            waitText(title)
            waitText(text(R.string.autofill_suggested_fill))
            compose.onNode(hasSetTextAction()).performTextReplacement("NoMatchingApplication331")
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
            compose.onNode(hasSetTextAction()).performTextReplacement("  $label  ")
            waitText(title)
            compose.onNode(hasSetTextAction()).performTextClearance()
            waitText(text(R.string.autofill_suggested_fill))
        }
        verify(localTitle)
        selectSource(R.string.filter_keepass); verify(kdbx.title); assertAbsent(localTitle)
        selectSource(R.string.filter_bitwarden); verify("Bound-Bw-$suffix"); assertAbsent(kdbx.title)
        assertScope(AutofillScope.Bitwarden())
        selectSource(R.string.filter_mdbx); verify("Bound-Mdbx-$suffix"); assertAbsent("Bound-Bw-$suffix")
        assertNativeUnchanged()
        keepassFixtures.forEach { assertArrayEquals(it.bytes, context.contentResolver.openInputStream(it.uri)!!.use { input -> input.readBytes() }) }
    }

    @Test fun webDavCachedMirrorRecommendsBoundLoginAfterAuthenticationAndSearchesAppName() {
        verifyWebDavBinding(KeePassOpenMode.CACHED_MIRROR)
    }

    @Test fun webDavWorkingCopyRecommendsBoundLoginAfterAuthenticationAndSearchesAppName() {
        verifyWebDavBinding(KeePassOpenMode.WORKING_COPY)
    }

    private fun verifyWebDavBinding(mode: KeePassOpenMode) {
        val app = instrumentation.context.packageName
        val kdbx = runBlocking { keepass("WebDAV-$mode-$suffix", "WebDAV-Login-$suffix",
            "synthetic-webdav-user", "synthetic-webdav-secret", app) }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                    "HEAD" -> MockResponse().setResponseCode(200)
                    "PROPFIND" -> MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(
                        """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"><d:response>
                            <d:href>/bound-login.kdbx</d:href><d:propstat><d:prop><d:displayname>bound-login.kdbx</d:displayname>
                            <d:getetag>fixture-revision</d:getetag><d:getcontentlength>${kdbx.bytes.size}</d:getcontentlength>
                            <d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                            </d:response></d:multistatus>""".trimIndent())
                    "GET" -> MockResponse().setResponseCode(200).setBody(Buffer().write(kdbx.bytes))
                    else -> MockResponse().setResponseCode(405)
                }
            }
            runBlocking {
                val source = KeepassRemoteSource(providerType = KeePassRemoteProviderType.WEBDAV,
                    displayName = "Synthetic autofill WebDAV", baseUrl = server.url("/").toString(),
                    remotePath = "bound-login.kdbx")
                val sourceId = appDb.keepassRemoteSourceDao().insertSource(source).also(remoteSourceIds::add)
                val strings = StringResolver { id, args -> context.getString(id, *args) }
                // Download exact encrypted bytes through the production WebDAV transport.
                val downloaded = WebDavKeePassSupport.createFileSource(source, security, strings).read()
                assertArrayEquals(kdbx.bytes, downloaded)
                val working = File(context.filesDir, "autofill-webdav-331-$suffix-working.kdbx").also(remoteMirrorFiles::add)
                val cached = File(context.filesDir, "autofill-webdav-331-$suffix-cache.kdbx").also(remoteMirrorFiles::add)
                working.writeBytes(downloaded); cached.writeBytes(downloaded)
                val registration = requireNotNull(appDb.localKeePassDatabaseDao().getDatabaseById(kdbx.id))
                appDb.localKeePassDatabaseDao().updateDatabase(registration.copy(
                    sourceType = KeePassDatabaseSourceType.REMOTE_WEBDAV, sourceId = sourceId,
                    storageLocation = KeePassStorageLocation.INTERNAL, openMode = mode,
                    filePath = working.name, workingCopyPath = working.name, cacheCopyPath = cached.name,
                    lastSyncStatus = KeePassSyncStatus.IN_SYNC))
                KeePassKdbxService.invalidateProcessCache(kdbx.id)
                preferences.setV2DefaultScope(AutofillScope.KeePass(kdbx.id))
            }
            val downloadedRequestCount = server.requestCount
            assertTrue("The remote fixture must actually be downloaded", downloadedRequestCount > 0)
            val ids = targets(2)
            val scenario = launch(ids, listOf("USERNAME", "PASSWORD"), AutofillPickerActivityV2.Args(
                applicationId = app, autofillIds = ids, autofillHints = arrayListOf("USERNAME", "PASSWORD"),
                suggestedPasswordIds = longArrayOf(), refreshLoginSuggestions = true, rememberLastFilled = false))
            waitText(kdbx.title)
            waitText(text(R.string.autofill_suggested_fill))
            assertAbsent(localTitle)
            assertScope(AutofillScope.KeePass(kdbx.id))
            compose.onNode(hasSetTextAction()).performTextReplacement("NoMatchingApplication331")
            compose.waitUntil(10_000) { compose.onAllNodesWithText(kdbx.title).fetchSemanticsNodes().isEmpty() }
            val label = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(app, 0)).toString()
            compose.onNode(hasSetTextAction()).performTextReplacement("  $label  ")
            waitText(kdbx.title)
            compose.onNode(hasSetTextAction()).performTextClearance()
            waitText(text(R.string.autofill_suggested_fill))
            selectLoginAndFill(kdbx.title)
            assertDataset(scenario, ids, listOf("synthetic-webdav-user", "synthetic-webdav-secret"))
            remoteMirrorFiles.forEach { assertArrayEquals("Autofill preserves encrypted mirrors", kdbx.bytes, it.readBytes()) }
            assertEquals("Autofill does not write or re-download the remote vault", downloadedRequestCount, server.requestCount)
            assertArrayEquals(kdbx.bytes, context.contentResolver.openInputStream(kdbx.uri)!!.use { it.readBytes() })
        }
    }

    private fun launch(ids: ArrayList<AutofillId>, hints: List<String>, overrideArgs: AutofillPickerActivityV2.Args? = null): ActivityScenario<AutofillPickerActivityV2> {
        AutofillSessionGrants.clear()
        val args = overrideArgs ?: AutofillPickerActivityV2.Args(applicationId = "org.example.multivault328",
            webDomain = "native.example", interactionIdentifier = "native-ui-${UUID.randomUUID()}",
            autofillIds = ids, autofillHints = ArrayList(hints), rememberLastFilled = false)
        val scenario = ActivityScenario.launchActivityForResult<AutofillPickerActivityV2>(AutofillPickerActivityV2.getIntent(context, args))
        scenarios += scenario
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        compose.onNode(hasSetTextAction()).performTextReplacement(MASTER)
        scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        return scenario
    }

    private fun selectSource(label: Int) {
        compose.onNodeWithText(text(R.string.filter_title)).performClick()
        compose.onNode(hasText(text(label)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.filter_title)).performClick()
    }

    private fun selectDatabase(current: String, next: String) {
        compose.onNodeWithText(text(R.string.filter_title)).performClick()
        // A selected database also appears in the clickable filter summary. Only the
        // read-only TextField anchor exposes its value as EditableText.
        val anchor = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(current)) and hasClickAction()
        compose.onNode(anchor).assertIsDisplayed().performClick()
        compose.onNodeWithText(next).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.filter_title)).performClick()
    }

    private fun selectContent(label: Int) {
        val matcher = hasText(text(label), substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(matcher).performScrollTo().performClick()
    }

    private fun waitText(value: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    private fun assertAbsent(value: String) { compose.onNodeWithText(value).assertDoesNotExist() }
    private fun text(id: Int) = context.getString(id)
    private fun assertScope(expected: AutofillScope) {
        waitUntil("Saved source must match the actual selected source") {
            runBlocking { preferences.v2DefaultScope.first() } == expected
        }
    }

    private fun assertDataset(scenario: ActivityScenario<AutofillPickerActivityV2>, targets: List<AutofillId>, expected: List<String>) {
        waitUntil("Selected record must finish with a framework Dataset") { scenario.state == Lifecycle.State.DESTROYED }
        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        @Suppress("DEPRECATION")
        val dataset = requireNotNull(result.resultData.getParcelableExtra<Dataset>(AutofillManager.EXTRA_AUTHENTICATION_RESULT))
        @Suppress("UNCHECKED_CAST")
        val ids = Dataset::class.java.getMethod("getFieldIds").invoke(dataset) as List<AutofillId>
        @Suppress("UNCHECKED_CAST")
        val values = Dataset::class.java.getMethod("getFieldValues").invoke(dataset) as List<AutofillValue?>
        assertEquals(targets.size, ids.size)
        assertEquals(targets.toSet(), ids.toSet())
        assertEquals(ids.size, values.size)
        val actual = ids.zip(values).associate { (id, value) ->
            val typed = requireNotNull(value)
            assertTrue(typed.isText)
            id to typed.textValue.toString()
        }
        assertEquals(targets.zip(expected).toMap(), actual)
        assertFalse("System fill must not unlock main application session", SessionManager.isUnlocked.value)
    }

    private fun waitUntil(reason: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 20_000
        do {
            compose.mainClock.advanceTimeByFrame()
            if (condition()) return
            Thread.sleep(20)
        } while (SystemClock.elapsedRealtime() < until)
        throw AssertionError(reason)
    }

    private fun login(username: String, password: String) = buildJsonObject {
        put("username", username); put("password_plain", password); put("website", "https://native.example")
    }.toString()

    private suspend fun native(name: String, title: String, type: String = "login", payload: String): Fixture {
        val original = fixture(type, payload)
        val vault = openVault(original.file.path, vaultPassword, "synthetic-picker-ui")
        try { vault.executeWriteOperation(UUID.randomUUID().toString(), "synthetic-title", listOf(
            MdbxWriteCommand.UpdateEntry(original.objectId, original.folderId, type, title, payload))) }
        finally { vault.close() }
        val registration = requireNotNull(database.localMdbxDatabaseDao().getDatabaseById(original.databaseId))
        val id = appDb.localMdbxDatabaseDao().insertDatabase(registration.copy(id = 0L, name = name))
        return original.copy(databaseId = id).also {
            nativeFixtures += it
            nativeBefore[id] = nativeSnapshot(it)
        }
    }

    private fun nativeSnapshot(fixture: Fixture): Pair<MdbxObjectSummary?, String> {
        val vault = openVault(fixture.file.path, vaultPassword, "synthetic-picker-evidence")
        return try { vault.getObjectSummary(fixture.objectId) to requireNotNull(
            vault.revealObjectWithLimits(fixture.objectId, MdbxObjectDisclosureLimits(1024uL * 1024uL)).`object`).payloadJson }
        finally { vault.close() }
    }

    private fun assertNativeUnchanged() {
        nativeFixtures.forEach { assertEquals(nativeBefore.getValue(it.databaseId), nativeSnapshot(it)) }
        runBlocking {
            assertTrue(appDb.passwordEntryDao().getAllPasswordEntriesSync().none { it.mdbxDatabaseId in nativeFixtures.map(Fixture::databaseId) })
            assertEquals(baselineItems, appDb.secureItemDao().getAllItemsIncludingArchived().first())
        }
    }

    private suspend fun bitwarden(name: String, title: String, username: String, password: String, packages: String = ""): Long {
        val email = "${UUID.randomUUID()}@example.invalid"
        val id = appDb.bitwardenVaultDao().insert(BitwardenVault(email = email, canonicalEmail = email,
            accountKey = UUID.randomUUID().toString(), displayName = name, serverUrl = "https://example.invalid",
            identityUrl = "https://example.invalid/identity", apiUrl = "https://example.invalid/api",
            isLocked = true, isConnected = false)).also(ownedBwIds::add)
        ownedPasswordIds += appDb.passwordEntryDao().insertPasswordEntry(PasswordEntry(title = title,
            website = "https://native.example", username = username, password = security.encryptData(password), appPackageName = packages,
            bitwardenVaultId = id, bitwardenCipherId = UUID.randomUUID().toString()))
        return id
    }

    private suspend fun keepass(name: String, title: String, username: String, password: String, packages: String = ""): KdbxFixture {
        val credentials = Credentials.from(EncryptedValue.fromString(KDBX_PASSWORD))
        val created = KeePassDatabase.Ver4x.create("Multivault328", Meta(generator = "Synthetic picker UI fixture"), credentials)
        val seed = when (val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        val value = created.copy(header = created.header.copy(kdfParameters = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2d, salt = seed, parallelism = 2U,
            memory = 32uL * 1024uL * 1024uL, iterations = 8U, version = 0x13U,
            secretKey = null, associatedData = null))).modifyParentGroup {
            copy(entries = listOf(Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                "Title" to EntryValue.Plain(title), "UserName" to EntryValue.Plain(username),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString(password)),
                "URL" to EntryValue.Plain("https://native.example"), "AppPackageName" to EntryValue.Plain(packages)))))
        }
        val uri = Uri.parse("content://${instrumentation.context.packageName}.kdbx-delete/delete-${UUID.randomUUID()}.kdbx")
        val bytes = ByteArrayOutputStream().also { value.encode(it) }.toByteArray()
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        val id = appDb.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(name = name, filePath = uri.toString(),
            storageLocation = KeePassStorageLocation.EXTERNAL, sourceType = KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI,
            encryptedPassword = security.encryptData(KDBX_PASSWORD)))
        return KdbxFixture(id, name, title, uri, bytes).also(keepassFixtures::add)
    }

    companion object {
        private const val MASTER = "Batch328-synthetic-master!"
        private const val KDBX_PASSWORD = "Native328-kdbx-synthetic-password!"
    }
}

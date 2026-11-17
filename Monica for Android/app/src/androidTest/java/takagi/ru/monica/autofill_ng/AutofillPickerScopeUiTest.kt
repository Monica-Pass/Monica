package takagi.ru.monica.autofill_ng

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.SecureItem
import takagi.ru.monica.data.isLocalOnlyItem
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.CardWalletDataCodec
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.SettingsManager

/** Actual picker Activity and editors. Never run in owner user 0 or against a personal vault. */
class AutofillPickerScopeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val database by lazy { PasswordDatabase.getDatabase(context) }
    private val settings by lazy { SettingsManager(context) }
    private val preferences by lazy { AutofillPreferences(context) }
    private val suffix = UUID.randomUUID().toString().take(8)
    private val localTitle = "Local328-$suffix"
    private val nativeTitle = "Native328-$suffix"
    private val cardTitle = "Card328-$suffix"
    private val newCardTitle = "NewCard328-$suffix"
    private val ownedPasswordIds = mutableSetOf<Long>()
    private val ownedItemIds = mutableSetOf<Long>()
    private val scenarios = mutableListOf<ActivityScenario<AutofillPickerActivityV2>>()
    private var baselinePasswords: List<PasswordEntry>? = null
    private var baselineItems: List<SecureItem>? = null
    private var oldSettings: AppSettings? = null
    private var oldSource: AutofillPreferences.AutofillDefaultSourceFilter? = null
    private var oldKeePassId: Long? = null
    private var oldBitwardenId: Long? = null
    private lateinit var security: SecurityManager
    private lateinit var service: KeePassKdbxService
    private lateinit var uri: Uri
    private var databaseId = 0L
    private var originalNativeBytes = byteArrayOf()
    private val masterPassword get() = arguments.getString("autofillBatchPassword") ?: "Batch328-synthetic-master!"

    @Before fun prepare(): Unit = runBlocking {
        val user = android.os.Process.myUid() / 100000
        check(user > 0 && arguments.getString("autofillBatchUser") == user.toString()) {
            "Use only an owned secondary user with -e autofillBatchUser <id>"
        }
        // ALL-mode tests must not accidentally open another task's or a user's original vault.
        check(database.localKeePassDatabaseDao().getAllDatabases().first().isEmpty()) {
            "Picker UI fixture requires no pre-existing KeePass registrations"
        }
        security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(masterPassword))
        SessionManager.attachAppContext(context)
        SessionManager.markLocked()
        check(security.unlockVaultWithPassword(masterPassword)) { "Not the expected synthetic test vault" }
        AutofillSessionGrants.clear()
        baselinePasswords = database.passwordEntryDao().getAllPasswordEntriesSync()
        baselineItems = database.secureItemDao().getAllItemsIncludingArchived().first()
        oldSettings = settings.settingsFlow.first()
        oldSource = preferences.v2DefaultSourceFilter.first()
        oldKeePassId = preferences.v2DefaultKeepassDatabaseId.first()
        oldBitwardenId = preferences.v2DefaultBitwardenVaultId.first()
        settings.updateBiometricEnabled(false)
        settings.updateAutofillAuthRequired(true)
        settings.updateAutofillKeepUnlocked(true)
        settings.updateAutoLockMinutes(5)
        SessionManager.updateAutoLockTimeout(5)
        preferences.setV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.LOCAL)
        preferences.setV2DefaultKeepassDatabaseId(null)
        preferences.setV2DefaultBitwardenVaultId(null)
        ownedPasswordIds += database.passwordEntryDao().insertPasswordEntry(PasswordEntry(
            title = localTitle, website = "https://picker328.example.test", username = "synthetic-local",
            password = security.encryptData("synthetic-local-password")))
        ownedItemIds += database.secureItemDao().insertItem(SecureItem(itemType = ItemType.BANK_CARD,
            title = cardTitle, itemData = Json.encodeToString(BankCardData(
                cardNumber = security.encryptData(PAN), cardholderName = "SYNTHETIC HOLDER",
                expiryMonth = "12", expiryYear = "2030", cvv = security.encryptData("123")))))
        service = KeePassKdbxService(context, database.localKeePassDatabaseDao(), security)
        registerNative()
        assertFalse(SessionManager.isUnlocked.value)
    }

    @After fun cleanup(): Unit = runBlocking {
        // Every blocker is released before closing Activities or deleting only this fixture's rows.
        if (::uri.isInitialized) command("release-read")
        scenarios.asReversed().forEach { it.close() }
        AutofillSessionGrants.clear()
        if (::security.isInitialized) {
            check(security.unlockVaultWithPassword(masterPassword)) { "Synthetic cleanup could not reauthenticate" }
            val created = database.secureItemDao().getAllItemsIncludingArchived().first()
                .filter { it.title == newCardTitle && it.id !in baselineItems.orEmpty().map(SecureItem::id) }
            ownedItemIds += created.map(SecureItem::id)
            ownedItemIds.forEach { database.secureItemDao().deleteItemById(it) }
            ownedPasswordIds.forEach { database.passwordEntryDao().deletePasswordEntryById(it) }
            if (databaseId != 0L) {
                KeePassKdbxService.invalidateProcessCache(databaseId)
                database.localKeePassDatabaseDao().deleteDatabaseById(databaseId)
            }
        }
        if (::uri.isInitialized) context.contentResolver.delete(uri, null, null)
        oldSettings?.let {
            settings.updateBiometricEnabled(it.biometricEnabled)
            settings.updateAutofillAuthRequired(it.autofillAuthRequired)
            settings.updateAutofillKeepUnlocked(it.autofillKeepUnlocked)
            settings.updateAutoLockMinutes(it.autoLockMinutes)
            SessionManager.updateAutoLockTimeout(it.autoLockMinutes)
        }
        oldSource?.let {
            preferences.setV2DefaultSourceFilter(it)
            preferences.setV2DefaultKeepassDatabaseId(oldKeePassId)
            preferences.setV2DefaultBitwardenVaultId(oldBitwardenId)
        }
        baselinePasswords?.let { assertEquals("Unrelated passwords must be preserved", it,
            database.passwordEntryDao().getAllPasswordEntriesSync()) }
        baselineItems?.let { assertEquals("Unrelated secure items must be preserved", it,
            database.secureItemDao().getAllItemsIncludingArchived().first()) }
        if (::security.isInitialized) SecurityManager.clearRuntimeUnlockCache()
    }

    @Test fun savedLocalDefaultDisplaysWithoutOpeningSlowOrUnreadableNativeVault() {
        val before = counter("reads")
        command("block-read")
        try {
            val first = launchAndAuthenticate()
            waitText(localTitle)
            selectContent(R.string.item_type_bank_card)
            waitText(cardTitle)
            assertEquals(before, counter("reads"))
            assertEquals(0, counter("read_blocked"))
            closePicker(first)
        } finally { command("release-read") }
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        KeePassKdbxService.invalidateProcessCache(databaseId)
        val unreadableBefore = counter("reads")
        val second = launchAndAuthenticate()
        waitText(localTitle)
        selectContent(R.string.item_type_bank_card)
        waitText(cardTitle)
        assertEquals("Saved LOCAL must not inspect even an unreadable registered file", unreadableBefore, counter("reads"))
        closePicker(second)
    }

    @Test fun localAllLocalSwitchRetiresBlockedResultsAndCanLoadAllAgain() {
        val scenario = launchAndAuthenticate()
        waitText(localTitle)
        openFilters()
        command("block-read")
        try {
            selectSource(R.string.filter_all)
            waitProviderBlocked()
            selectSource(R.string.filter_monica)
            closeFilters()
            // LOCAL is usable before the old native provider is released.
            waitText(localTitle)
            assertEquals(1, counter("read_blocked"))
            assertAbsent(nativeTitle)
        } finally { command("release-read") }
        waitUntil("Old provider read should leave its barrier") { counter("read_blocked") == 0 }
        assertStableLocal(scenario)
        openFilters()
        selectSource(R.string.filter_all)
        closeFilters()
        waitText(nativeTitle)
        waitText(localTitle)
        openFilters()
        selectSource(R.string.filter_monica)
        closeFilters()
        waitText(localTitle)
        assertStableLocal(scenario)
        assertNativeUnchanged()
    }

    @Test fun closeWhileAllLoadIsBlockedReturnsCancelledAndRejectsLateRows() {
        val scenario = launchAndAuthenticate()
        waitText(localTitle)
        openFilters()
        command("block-read")
        try {
            selectSource(R.string.filter_all)
            waitProviderBlocked()
            closePicker(scenario)
        } finally { command("release-read") }
        waitUntil("Cancelled provider read should finish") { counter("read_blocked") == 0 }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        assertNativeUnchanged()
    }

    @Test fun globalRelockDuringAllLoadClosesAndReauthenticationCannotReviveActivity() {
        val scenario = launchAndAuthenticate()
        waitText(localTitle)
        openFilters()
        command("block-read")
        try {
            selectSource(R.string.filter_all)
            waitProviderBlocked()
            SecurityManager.clearRuntimeUnlockCache()
            waitDestroyed(scenario)
        } finally { command("release-read") }
        check(security.unlockVaultWithPassword(masterPassword))
        waitUntil("Revoked provider read should finish") { counter("read_blocked") == 0 }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        assertFalse(SessionManager.isUnlocked.value)
        assertNativeUnchanged()
    }

    @Test fun nativeLockWhileShowingLocalCannotBeReauthorizedByAFilterChange() {
        val scenario = launchAndAuthenticate()
        waitText(localTitle)
        openFilters()
        val before = counter("reads")
        // Lock before the next click in the very same main-thread event. The click is the real
        // existing selectable UI action; it must not create an authorization for the new lock epoch.
        val all = compose.onNode(sourceMatcher(R.string.filter_all)).fetchSemanticsNode()
        compose.runOnUiThread {
            service.lockDatabase(databaseId)
            val clicked = all.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke()
            check(clicked == true)
        }
        waitDestroyed(scenario)
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
        assertEquals("Cross-scope switch must not reopen a newly locked native vault", before, counter("reads"))
        assertNativeUnchanged()
    }

    @Test fun cancellingEachAddTypeReturnsToLocalListWithoutWritingItems() {
        val scenario = launchAndAuthenticate()
        waitText(localTitle)
        val beforePasswords = runBlocking { database.passwordEntryDao().getAllPasswordEntriesSync() }
        val beforeItems = runBlocking { database.secureItemDao().getAllItemsIncludingArchived().first() }
        listOf(
            R.string.item_type_password to "password_editor_heading",
            R.string.item_type_bank_card to "bank_item_editor",
            R.string.item_type_document to "document_item_editor",
            R.string.add_billing_address to "billing_item_editor",
        ).forEach { (label, editorTag) ->
            openAdd(label)
            waitTag(editorTag)
            hideIme(scenario)
            compose.onNodeWithContentDescription(text(R.string.back)).performClick()
            waitText(localTitle)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertAbsent(nativeTitle)
        }
        assertEquals(beforePasswords, runBlocking { database.passwordEntryDao().getAllPasswordEntriesSync() })
        assertEquals(beforeItems, runBlocking { database.secureItemDao().getAllItemsIncludingArchived().first() })
        assertNativeUnchanged()
    }

    @Test fun savingBankCardReturnsToReloadedLocalListAndReadsStoredCard() {
        val scenario = launchAndAuthenticate()
        waitText(localTitle)
        openAdd(R.string.item_type_bank_card)
        waitTag("bank_item_editor")
        compose.onNodeWithTag("item_editor_title").performTextReplacement(newCardTitle)
        compose.onNodeWithTag("bank_item_editor").performScrollToNode(hasTestTag("entry_payment_number"))
        compose.onNodeWithTag("entry_payment_number").performTextReplacement(PAN)
        compose.onNodeWithTag("bank_item_editor").performScrollToNode(hasTestTag("entry_payment_holder"))
        compose.onNodeWithTag("entry_payment_holder").performTextReplacement("NEW SYNTHETIC HOLDER")
        hideIme(scenario)
        compose.onNodeWithContentDescription(text(R.string.save)).performClick()
        waitText(localTitle)
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        selectContent(R.string.item_type_bank_card)
        waitText(newCardTitle)
        waitText(cardTitle)
        val stored = runBlocking { database.secureItemDao().getAllItemsIncludingArchived().first()
            .single { it.title == newCardTitle } }
        ownedItemIds += stored.id
        assertTrue(stored.isLocalOnlyItem())
        val payload = requireNotNull(CardWalletDataCodec.parseBankCardData(stored.itemData,
            security::decryptDataIfMonicaCiphertext))
        assertEquals(PAN, security.decryptDataIfMonicaCiphertext(payload.cardNumber))
        assertEquals("NEW SYNTHETIC HOLDER", security.decryptDataIfMonicaCiphertext(payload.cardholderName))
        assertFalse(SessionManager.isUnlocked.value)
        assertAbsent(nativeTitle)
        assertNativeUnchanged()
    }

    @Test fun twoSourceClicksInOneFrameKeepFinalLocalScopeUsable() {
        val scenario = launchAndAuthenticate()
        waitText(localTitle)
        openFilters()
        val all = compose.onNode(sourceMatcher(R.string.filter_all)).fetchSemanticsNode()
        val local = compose.onNode(sourceMatcher(R.string.filter_monica)).fetchSemanticsNode()
        val before = counter("reads")
        // Both real selectable callbacks are from the SAME composition and run in one UI event.
        // No waitForIdle, frame advance, or source reload is allowed between them.
        compose.runOnUiThread {
            check(all.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke() == true)
            check(local.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke() == true)
        }
        closeFilters()
        waitText(localTitle)
        assertStableLocal(scenario)
        assertEquals("Coalesced ALL to LOCAL must not open the intermediate native scope", before, counter("reads"))
        // The final source must be usable for later real switching, not just leave stale text visible.
        openFilters()
        selectSource(R.string.filter_all)
        closeFilters()
        waitText(nativeTitle)
        openFilters()
        selectSource(R.string.filter_monica)
        closeFilters()
        waitText(localTitle)
        assertStableLocal(scenario)
        assertNativeUnchanged()
    }

    @Test fun savingNewPasswordReturnsExactFrameworkDatasetAfterEditorNavigation() {
        val savedTitle = "NewPass328-$suffix"
        val savedUsername = "saved-user-$suffix"
        val savedPassword = "Saved328!Password-$suffix"
        val targets = compose.runOnUiThread {
            arrayListOf(android.widget.EditText(context).autofillId, android.widget.EditText(context).autofillId)
        }
        assertNotEquals(targets[0], targets[1])
        AutofillSessionGrants.clear()
        val args = AutofillPickerActivityV2.Args(
            applicationId = "org.example.picker328", interactionIdentifier = "picker328-save-${UUID.randomUUID()}",
            capturedUsername = savedUsername, capturedPassword = savedPassword,
            autofillIds = targets, autofillHints = arrayListOf("USERNAME", "PASSWORD"),
            rememberLastFilled = false,
        )
        // Non-manual mode exercises the production framework-result path, not clipboard fallback.
        val scenario = ActivityScenario.launchActivityForResult<AutofillPickerActivityV2>(
            AutofillPickerActivityV2.getIntent(context, args))
        scenarios += scenario
        try {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            compose.onNode(hasSetTextAction()).performTextReplacement(masterPassword)
            hideIme(scenario)
            compose.onNodeWithText(text(R.string.confirm)).performClick()
            waitText(localTitle)
            openAdd(R.string.item_type_password)
            waitTag("password_editor_heading")
            compose.onNodeWithTag("password_editor_title").performScrollTo().performTextReplacement(savedTitle)
            // Replace the visible fields too, so this does not only depend on initial-draft injection.
            compose.onNode(hasText(text(R.string.username)) and hasSetTextAction())
                .performScrollTo().performTextReplacement(savedUsername)
            compose.onNodeWithTag("password_editor_value_0").performScrollTo().performTextReplacement(savedPassword)
            hideIme(scenario)
            compose.onNodeWithTag("password_editor_save").assertIsEnabled().performClick()
            waitUntil("Saving a password must return an actual framework result after editor navigation") {
                scenario.state == Lifecycle.State.DESTROYED
            }
            val result = scenario.result
            assertEquals(Activity.RESULT_OK, result.resultCode)
            @Suppress("DEPRECATION")
            val dataset = result.resultData.getParcelableExtra<android.service.autofill.Dataset>(
                android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT)
            assertNotNull("Actual authentication result must contain a Dataset", dataset)
            val delivered = requireNotNull(dataset)
            // These Android getters are public at runtime but absent from the public SDK stubs.
            @Suppress("UNCHECKED_CAST")
            val fieldIds = android.service.autofill.Dataset::class.java.getMethod("getFieldIds")
                .invoke(delivered) as List<android.view.autofill.AutofillId>
            @Suppress("UNCHECKED_CAST")
            val fieldValues = android.service.autofill.Dataset::class.java.getMethod("getFieldValues")
                .invoke(delivered) as List<android.view.autofill.AutofillValue?>
            assertEquals(targets.toSet(), fieldIds.toSet())
            assertEquals(2, fieldIds.size)
            assertEquals(fieldIds.size, fieldValues.size)
            val actual = fieldIds.zip(fieldValues).associate { (id, value) ->
                val textValue = requireNotNull(value)
                check(textValue.isText) { "Login targets require actual text AutofillValues" }
                id to textValue.textValue.toString()
            }
            assertEquals(savedUsername, actual[targets[0]])
            assertEquals(savedPassword, actual[targets[1]])
            val persisted = runBlocking { database.passwordEntryDao().getAllPasswordEntriesSync() }
                .filter { it.title == savedTitle }
            assertEquals("Save must create exactly one record", 1, persisted.size)
            val stored = persisted.single()
            ownedPasswordIds += stored.id
            assertTrue(stored.isLocalOnlyEntry())
            assertEquals(savedUsername, security.decryptDataIfMonicaCiphertext(stored.username))
            assertEquals(savedPassword, security.decryptDataIfMonicaCiphertext(stored.password))
            assertTrue("Framework result must originate from that exact saved row",
                takagi.ru.monica.autofill_ng.core.AutofillLogger.getRecentLogs(500).any {
                    it.category == "PICKER" &&
                        it.message == "Returning auth result: mode=dataset, passwordId=${stored.id}"
                })
            assertFalse(SessionManager.isUnlocked.value)
            assertNativeUnchanged()
        } finally {
            // Also own the newly saved row when a broken callback saves it but never returns a Dataset.
            ownedPasswordIds += runBlocking { database.passwordEntryDao().getAllPasswordEntriesSync() }
                .filter { it.title == savedTitle && it.id !in baselinePasswords.orEmpty().map(PasswordEntry::id) }
                .map(PasswordEntry::id)
        }
    }


    private fun launchAndAuthenticate(): ActivityScenario<AutofillPickerActivityV2> {
        AutofillSessionGrants.clear()
        val intent = AutofillPickerActivityV2.getIntent(context, AutofillPickerActivityV2.Args(
            applicationId = "org.example.picker328", interactionIdentifier = "picker328-${UUID.randomUUID()}"))
            .putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_MODE, true)
        val scenario = ActivityScenario.launchActivityForResult<AutofillPickerActivityV2>(intent)
        scenarios += scenario
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        compose.onNode(hasSetTextAction()).performTextReplacement(masterPassword)
        hideIme(scenario)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        return scenario
    }

    private fun hideIme(scenario: ActivityScenario<AutofillPickerActivityV2>) {
        scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView)
            .hide(WindowInsetsCompat.Type.ime()) }
        compose.waitForIdle()
    }

    private fun sourceMatcher(label: Int) = hasText(text(label)) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private fun openFilters() { compose.onNodeWithText(text(R.string.filter_title)).performClick() }
    private fun closeFilters() { compose.onNodeWithText(text(R.string.filter_title)).performClick() }

    private fun selectSource(label: Int) {
        compose.onNode(sourceMatcher(label)).performScrollTo().performClick()
    }

    private fun selectContent(label: Int) {
        compose.onNode(hasText(text(label), substring = true) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performScrollTo().performClick()
    }

    private fun openAdd(label: Int) {
        compose.onNodeWithContentDescription(text(R.string.add)).performClick()
        compose.onNode(hasText(text(label)) and hasClickAction()).performClick()
    }

    private fun closePicker(scenario: ActivityScenario<AutofillPickerActivityV2>) {
        compose.onNodeWithText(text(R.string.close)).performClick()
        waitDestroyed(scenario)
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
    }

    private fun waitDestroyed(scenario: ActivityScenario<AutofillPickerActivityV2>) {
        waitUntil("Picker must close after cancellation or lock") { scenario.state == Lifecycle.State.DESTROYED }
    }

    private fun waitText(value: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(value).assertIsDisplayed()
    }

    private fun waitTag(value: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertAbsent(value: String) { compose.onNodeWithText(value).assertDoesNotExist() }

    private fun assertStableLocal(scenario: ActivityScenario<AutofillPickerActivityV2>) {
        // Observe multiple actual idle frames after releasing the old read, not just its first frame.
        val until = SystemClock.elapsedRealtime() + 1_000
        do {
            compose.waitForIdle()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            compose.onNodeWithText(localTitle).assertIsDisplayed()
            assertAbsent(nativeTitle)
            Thread.sleep(40)
        } while (SystemClock.elapsedRealtime() < until)
    }

    private fun waitProviderBlocked() {
        waitUntil("ALL load must reach the actual provider read barrier") { counter("read_blocked") == 1 }
    }

    private fun waitUntil(reason: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        do {
            // StateFlow invalidations and cancellation effects still need frames under the test clock.
            compose.mainClock.advanceTimeByFrame()
            if (condition()) return
            Thread.sleep(20)
        } while (SystemClock.elapsedRealtime() < until)
        throw AssertionError(reason)
    }

    private suspend fun registerNative() {
        val credentials = Credentials.from(EncryptedValue.fromString(KDBX_PASSWORD))
        val created = KeePassDatabase.Ver4x.create("Picker328", Meta(generator = "Synthetic picker UI fixture"), credentials)
        val seed = when (val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        val fixture = created.copy(header = created.header.copy(kdfParameters = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2d, salt = seed, parallelism = 2U,
            memory = 32uL * 1024uL * 1024uL, iterations = 8U, version = 0x13U,
            secretKey = null, associatedData = null))).modifyParentGroup {
            copy(entries = listOf(Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                "Title" to EntryValue.Plain(nativeTitle), "UserName" to EntryValue.Plain("synthetic-native"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("native-synthetic-password")),
                "URL" to EntryValue.Plain("https://picker328.example.test")))))
        }
        uri = Uri.parse("content://${instrumentation.context.packageName}.kdbx-delete/delete-${UUID.randomUUID()}.kdbx")
        originalNativeBytes = java.io.ByteArrayOutputStream().also { fixture.encode(it) }.toByteArray()
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(originalNativeBytes) }
        databaseId = database.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(
            name = "Picker328-$suffix", filePath = uri.toString(), storageLocation = KeePassStorageLocation.EXTERNAL,
            sourceType = KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI,
            encryptedPassword = security.encryptData(KDBX_PASSWORD)))
    }

    private fun assertNativeUnchanged() = assertArrayEquals(originalNativeBytes,
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })

    private fun command(name: String) { context.contentResolver.call(uri, name, uri.toString(), null) }
    private fun counter(name: String): Int = context.contentResolver.query(uri, arrayOf(name), null, null, null)!!
        .use { check(it.moveToFirst()); it.getInt(0) }
    private fun text(id: Int) = context.getString(id)

    companion object {
        private const val PAN = "4242424242424242"
        private const val KDBX_PASSWORD = "Picker328-native-synthetic-password!"
    }
}

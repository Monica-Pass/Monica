package takagi.ru.monica.databasecompare

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.R
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.credentialexchange.ImportDestination
import takagi.ru.monica.credentialexchange.ImportDestinationKind
import takagi.ru.monica.ui.screens.DatabaseManagerScreen
import takagi.ru.monica.viewmodel.MdbxViewModel
import takagi.ru.monica.ui.screens.DatabaseCompareScreen
import takagi.ru.monica.ui.screens.KeePassNativeDatabaseToolsScreen
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

/** Real production screen/controller and encrypted fixtures. Only Android picker delivery is controlled. */
class DatabaseCompareUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val dao get() = PasswordDatabase.getDatabase(context).localKeePassDatabaseDao()
    private val visible = mutableStateOf(true)
    private val picker = PickerResults()
    private val outputUris = mutableListOf<Uri>()
    private val registrationIds = mutableListOf<Long>()
    private lateinit var lifecycle: TestLifecycle
    private lateinit var root: File
    private lateinit var leftFile: File
    private lateinit var rightFile: File
    private lateinit var leftBytes: ByteArray
    private lateinit var rightBytes: ByteArray
    private lateinit var left: LocalKeePassDatabase
    private lateinit var right: LocalKeePassDatabase
    private var model: LocalKeePassViewModel? = null
    private var mdbxModel: MdbxViewModel? = null
    private var wasUnlocked = false
    private var backCount = 0
    private val entryId = UUID.fromString("d3223b73-bf09-4b14-9585-040404040404")
    private val inputPassword = "Synthetic input password!"
    private val outputPassword = "Synthetic output password!"
    private val leftSecret = "synthetic-secret-left-731"
    private val rightSecret = "synthetic-secret-right-842"

    @Before fun prepare() {
        runBlocking {
            assertTrue("UI fixture requires ready secure storage",
                SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        }
        wasUnlocked = SessionManager.isUnlocked.value
        SessionManager.markUnlocked()
        compose.runOnUiThread { lifecycle = TestLifecycle() }
        root = File(context.filesDir, "compare-ui-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val original = KeePassDatabase.Ver4x.create("Synthetic root", Meta(),
            Credentials.from(EncryptedValue.fromString(inputPassword)))
        // Fast KDF belongs only to synthetic fixtures; no production defaults are changed.
        val database = original.copy(header = original.header.copy(kdfParameters = KdfParameters.Aes(10u, original.header.masterSeed)))
        val entry = Entry(entryId, fields = EntryFields.of("Title" to EntryValue.Plain("Synthetic account")))
        fun save(name: String, secret: String): File {
            val value = entry.copy(fields = EntryFields.of(
                "Title" to EntryValue.Plain("Synthetic account"),
                "UserName" to EntryValue.Plain("synthetic-user"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString(secret))))
            val copy = database.modifyContent { copy(group = group.copy(entries = listOf(value))) }
            return File(root, name).also { file -> file.outputStream().use { copy.encode(it, contentParser = KdbxComparisonXml()) } }
        }
        leftFile = save("left.kdbx", leftSecret)
        rightFile = save("right.kdbx", rightSecret)
        leftBytes = leftFile.readBytes(); rightBytes = rightFile.readBytes()
        fun register(file: File, name: String): LocalKeePassDatabase {
            val record = LocalKeePassDatabase(name = name, filePath = file.relativeTo(context.filesDir).path)
            val id = runBlocking { dao.insertDatabase(record) }
            registrationIds += id
            return record.copy(id = id)
        }
        left = register(leftFile, "Compare UI current ${root.name.takeLast(8)}")
        right = register(rightFile, "Compare UI other ${root.name.takeLast(8)}")
    }

    @After fun cleanOwnedFixtures() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        try {
            if (::leftBytes.isInitialized) assertArrayEquals("Current input must stay byte-identical", leftBytes, leftFile.readBytes())
            if (::rightBytes.isInitialized) assertArrayEquals("Other input must stay byte-identical", rightBytes, rightFile.readBytes())
        } finally {
            runBlocking {
                model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                mdbxModel?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                registrationIds.forEach { KeePassKdbxService.invalidateProcessCache(it); dao.deleteDatabaseById(it) }
            }
            outputUris.forEach { uri ->
                runCatching { command(uri, "release-read") }
                runCatching { command(uri, "release-write") }
                context.contentResolver.delete(uri, null, null)
            }
            if (::root.isInitialized) root.deleteRecursively()
            if (wasUnlocked) SessionManager.markUnlocked() else SessionManager.markLocked()
        }
    }

    private fun label(id: Int): String = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }).getString(id)

    private fun show(fontScale: Float = 1f, management: Boolean = false, currentManagement: Boolean = false,
        managerKey: String = ImportDestination(ImportDestinationKind.KEEPASS, left.id).key) {
        if (management || currentManagement) model = LocalKeePassViewModel(context.applicationContext as Application, dao, SecurityManager(context))
        if (currentManagement) {
            val db = PasswordDatabase.getDatabase(context)
            mdbxModel = MdbxViewModel(context.applicationContext as Application, db.localMdbxDatabaseDao(),
                db.mdbxRemoteSourceDao(), db.passwordEntryDao(), db.secureItemDao(), db.passkeyDao(),
                db.attachmentDao(), db.customFieldDao(), SecurityManager(context))
        }
        compose.setContent {
            val activity = LocalContext.current
            val configuration = remember {
                Configuration(activity.resources.configuration).apply { setLocale(Locale.ENGLISH); this.fontScale = fontScale }
            }
            val localized = remember { ContextThemeWrapper(activity, 0).apply { applyOverrideConfiguration(configuration) } }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources, LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLifecycleOwner provides lifecycle, LocalActivityResultRegistryOwner provides picker) {
                MonicaTheme {
                    if (visible.value) Box(Modifier.width(320.dp).fillMaxHeight()) {
                        if (currentManagement) DatabaseManagerScreen(managerKey, checkNotNull(mdbxModel),
                            checkNotNull(model), { backCount++ })
                        else if (management) KeePassNativeDatabaseToolsScreen(left, checkNotNull(model), { backCount++ }, {})
                        else DatabaseCompareScreen(left.id) { backCount++ }
                    }
                }
            }
        }
    }

    private fun waitForTag(tag: String) = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun chooseInputsAndStart() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(left.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("compare_right_choose").performScrollTo().performClick()
        compose.onNodeWithText(right.name).performScrollTo().performClick()
        fill("compare_left_password", inputPassword)
        fill("compare_right_password", inputPassword)
        compose.onNodeWithTag("compare_start").performScrollTo().performClick()
    }

    private fun startComparison(fontScale: Float = 1f) {
        show(fontScale)
        chooseInputsAndStart()
        waitForTag("compare_review")
    }

    private fun fill(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        Espresso.closeSoftKeyboard()
    }

    private fun openEntry() {
        compose.onNodeWithTag("compare_review").performScrollToNode(hasTestTag("compare_row_entry:$entryId"))
        compose.onNodeWithTag("compare_row_entry:$entryId").performClick()
        waitForTag("compare_details")
    }

    private fun chooseRight() {
        openEntry()
        compose.onNodeWithTag("compare_details").performScrollToNode(hasTestTag("compare_choice_RIGHT"))
        compose.onNodeWithTag("compare_choice_RIGHT").assertIsEnabled().performClick()
        waitForTag("compare_review")
    }

    private fun outputForm() {
        chooseRight()
        compose.onNodeWithTag("compare_review").performScrollToNode(hasTestTag("compare_continue"))
        compose.onNodeWithTag("compare_continue").assertIsEnabled().performClick()
        waitForTag("compare_output_password")
    }

    private fun generate() {
        fill("compare_output_password", outputPassword)
        fill("compare_repeat_password", outputPassword)
        compose.onNodeWithTag("compare_generate").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(20_000) { picker.pendingCode != null }
        assertTrue(picker.lastContract is ActivityResultContracts.CreateDocument)
    }

    private fun back() = compose.onNodeWithContentDescription(label(R.string.back)).performClick()

    private fun authenticateRegistered(database: LocalKeePassDatabase, external: Boolean = false): KeePassKdbxService {
        val security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword("Synthetic compare UX master!"))
        val registration = database.copy(encryptedPassword = security.encryptData(inputPassword),
            filePath = if (external) Uri.fromFile(File(context.filesDir, database.filePath)).toString() else database.filePath,
            storageLocation = if (external) KeePassStorageLocation.EXTERNAL else KeePassStorageLocation.INTERNAL)
        val service = KeePassKdbxService(context, dao, security)
        runBlocking { dao.updateDatabase(registration); service.openNativeSession(database.id).getOrThrow() }
        return service
    }

    @Test fun authenticatedInternalAndRegisteredExternalCompareWithoutPasswordAndExport() {
        authenticateRegistered(left)
        authenticateRegistered(right, external = true)
        show()
        waitForTag("compare_left_authenticated")
        compose.onNodeWithTag("compare_left_password").assertDoesNotExist()
        compose.onNodeWithTag("compare_right_choose").performScrollTo().performClick()
        compose.onNodeWithTag("compare_source_${right.id}").performScrollTo().performClick()
        waitForTag("compare_right_authenticated")
        compose.onNodeWithTag("compare_right_password").assertDoesNotExist()
        capture("authenticated-sources")
        compose.onNodeWithTag("compare_start").performScrollTo().assertIsEnabled().performClick()
        waitForTag("compare_review")
        outputForm(); generate()
        val destination = output()
        compose.runOnIdle { picker.deliver(destination) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText(label(R.string.db_compare_saved)).fetchSemanticsNodes().isNotEmpty() }
        val reopened = context.contentResolver.openInputStream(destination)!!.use {
            KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(outputPassword)), contentParser = KdbxComparisonXml())
        }
        assertEquals(rightSecret, reopened.content.group.entries.single().fields.password!!.content)
        assertTrue(reopened.content.group.entries.single().history.any { it.fields.password?.content == leftSecret })
    }

    @Test fun externalDocumentStillRequiresPasswordEvenWhenSameRegisteredFileIsAuthenticated() {
        authenticateRegistered(left); authenticateRegistered(right)
        show(); waitForTag("compare_left_authenticated")
        compose.onNodeWithTag("compare_right_choose").performScrollTo().performClick()
        compose.onNodeWithTag("compare_open_file").performClick()
        compose.waitUntil(10_000) { picker.pendingCode != null }
        compose.runOnIdle { picker.deliver(Uri.fromFile(rightFile)) }
        waitForTag("compare_right_password")
        compose.onNodeWithTag("compare_right_authenticated").assertDoesNotExist()
        fill("compare_right_password", "incorrect synthetic password")
        compose.onNodeWithTag("compare_start").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(label(R.string.db_compare_failed)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("compare_review").assertDoesNotExist()
        fill("compare_right_password", inputPassword)
        compose.onNodeWithTag("compare_start").performScrollTo().performClick()
        waitForTag("compare_review")
    }

    @Test fun explicitNativeLockRestoresCredentialsAndRevokesBorrowedReader() {
        val service = authenticateRegistered(left)
        val reader = runBlocking { checkNotNull(service.comparisonCredentialReader(left.id)) }
        show(); waitForTag("compare_left_authenticated")
        compose.runOnIdle { service.lockDatabase(left.id) }
        waitForTag("compare_left_password")
        compose.onNodeWithTag("compare_left_authenticated").assertDoesNotExist()
        assertTrue(runCatching { reader() }.isFailure)
        assertNull(runBlocking { service.comparisonCredentialReader(left.id) })
    }

    @Test fun globalRelockCannotReuseOldNativeAuthenticationAfterUnlock() {
        val service = authenticateRegistered(left)
        val reader = runBlocking { checkNotNull(service.comparisonCredentialReader(left.id)) }
        SessionManager.markLocked(); SessionManager.markUnlocked()
        assertTrue(runCatching { reader() }.isFailure)
        assertNull(runBlocking { service.comparisonCredentialReader(left.id) })
        show(); waitForTag("compare_left_password")
        compose.onNodeWithTag("compare_left_authenticated").assertDoesNotExist()
    }

    @Test fun maskedValuesExplicitChoicesAndVerifiedExportUseRealScreen() {
        startComparison()
        capture("review")
        compose.onNodeWithTag("compare_review").performScrollToNode(hasTestTag("compare_continue"))
        compose.onNodeWithTag("compare_continue").assertIsNotEnabled()
        openEntry()
        compose.onNodeWithTag("compare_details").performScrollToNode(hasText(label(R.string.password)))
        compose.onAllNodesWithText(leftSecret).assertCountEquals(0)
        compose.onAllNodesWithText(rightSecret).assertCountEquals(0)
        assertTrue(compose.onAllNodesWithText("••••••").fetchSemanticsNodes().size >= 2)
        capture("masked-details")
        compose.onNodeWithTag("compare_details").performScrollToIndex(0)
        compose.onNodeWithText(label(R.string.db_compare_reveal)).performClick()
        compose.onNodeWithTag("compare_details").performScrollToNode(hasText(leftSecret))
        compose.onNodeWithText(leftSecret).assertIsDisplayed()
        compose.onNodeWithText(rightSecret).assertIsDisplayed()
        back()
        outputForm()
        fill("compare_output_password", outputPassword)
        fill("compare_repeat_password", "mismatch")
        compose.onNodeWithTag("compare_generate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.db_compare_password_mismatch)).performScrollTo().assertIsDisplayed()
        capture("output-validation")
        generate()
        val destination = output()
        compose.runOnIdle { picker.deliver(destination) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText(label(R.string.db_compare_saved)).fetchSemanticsNodes().isNotEmpty() }
        capture("saved")
        val reopened = context.contentResolver.openInputStream(destination)!!.use {
            KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(outputPassword)), contentParser = KdbxComparisonXml())
        }
        assertEquals(rightSecret, reopened.content.group.entries.single().fields.password!!.content)
        assertTrue(reopened.content.group.entries.single().history.any { it.fields.password?.content == leftSecret })
    }

    @Test fun backFromOutputClearsPasswordsAndBackFromReviewClearsSession() {
        startComparison(); outputForm()
        fill("compare_output_password", outputPassword)
        back()
        compose.onNodeWithTag("compare_review").assertIsDisplayed()
        compose.onNodeWithTag("compare_review").performScrollToNode(hasTestTag("compare_continue"))
        compose.onNodeWithTag("compare_continue").performClick()
        assertEmptyPassword("compare_output_password")
        compose.onNodeWithTag("compare_generate").performScrollTo().assertIsNotEnabled()
        back(); back()
        compose.onNodeWithTag("compare_review").assertDoesNotExist()
        assertEmptyPassword("compare_left_password")
        assertEmptyPassword("compare_right_password")
        back()
        assertEquals(1, backCount)
    }

    @Test fun cancellingDestinationPickerDiscardsEncryptedResult() {
        startComparison(); outputForm(); generate()
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED; picker.deliver(null) }
        waitForTag("compare_start")
        compose.onNodeWithTag("compare_review").assertDoesNotExist()
        compose.onNodeWithTag("compare_generate").assertDoesNotExist()
        assertTrue("Cancel must not request another picker", picker.pendingCode == null)
    }

    @Test fun lockAfterRevealingValuesDiscardsReviewAndOldUnlockGeneration() {
        startComparison(); openEntry()
        compose.onNodeWithText(label(R.string.db_compare_reveal)).performClick()
        compose.onNodeWithTag("compare_details").performScrollToNode(hasText(leftSecret))
        compose.onNodeWithText(leftSecret).assertIsDisplayed()
        compose.runOnIdle { SessionManager.markLocked() }
        compose.onNodeWithTag("compare_details").assertDoesNotExist()
        compose.onAllNodesWithText(leftSecret).assertCountEquals(0)
        compose.runOnIdle { SessionManager.markUnlocked() }
        waitForTag("compare_start")
        compose.onNodeWithTag("compare_review").assertDoesNotExist()
        compose.onNodeWithTag("compare_start").performScrollTo().assertIsNotEnabled()
    }

    @Test fun backgroundClearsReviewAndLateInputPickerCannotCrossLock() {
        startComparison()
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        waitForTag("compare_start")
        compose.onNodeWithTag("compare_review").assertDoesNotExist()
        compose.onNodeWithText(right.name).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.db_compare_file)).performScrollTo().performClick()
        compose.waitUntil(10_000) { picker.pendingCode != null }
        assertTrue(picker.lastContract is ActivityResultContracts.OpenDocument)
        val late = output()
        context.contentResolver.openOutputStream(late, "wt")!!.use { it.write(rightBytes) }
        compose.runOnIdle { SessionManager.markLocked() }
        compose.waitForIdle()
        compose.runOnIdle { SessionManager.markUnlocked(); picker.deliver(late) }
        waitForTag("compare_start")
        compose.onNodeWithTag("compare_start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("compare_review").assertDoesNotExist()
        compose.onAllNodesWithText(late.lastPathSegment!!).assertCountEquals(0)
    }

    @Test fun cancellingBusyReadRejectsLateComparisonAndPreservesSource() {
        val source = output()
        context.contentResolver.openOutputStream(source, "wt")!!.use { it.write(leftBytes) }
        runBlocking { dao.updateDatabase(left.copy(filePath = source.toString(), storageLocation = KeePassStorageLocation.EXTERNAL)) }
        val comparisonRoot = File(context.cacheDir, "database-compare")
        val existingCaptures = comparisonRoot.listFiles().orEmpty().map { it.name }.toSet()
        command(source, "block-read", Bundle().apply { putInt("read", 2) })
        show(); chooseInputsAndStart()
        try {
            compose.waitUntil(10_000) {
                context.contentResolver.query(source, arrayOf("read_blocked"), null, null, null)!!.use { it.moveToFirst(); it.getInt(0) == 1 }
            }
            compose.onNodeWithText(label(R.string.cancel)).performClick()
        } finally { command(source, "release-read") }
        compose.waitUntil(10_000) { comparisonRoot.listFiles().orEmpty().all { it.name in existingCaptures } }
        waitForTag("compare_start")
        compose.onNodeWithTag("compare_review").assertDoesNotExist()
        assertArrayEquals(leftBytes, context.contentResolver.openInputStream(source)!!.use { it.readBytes() })
    }

    @Test fun realScreenAtLargeFontKeepsReviewChoicesAndOutputReachable() {
        startComparison(fontScale = 1.5f)
        capture("review-large")
        openEntry()
        compose.onNodeWithTag("compare_details").performScrollToNode(hasTestTag("compare_choice_RIGHT"))
        assertCompleteButtonText("compare_choice_RIGHT", label(R.string.db_compare_use_right), 1.5f)
        capture("choices-large")
        compose.onNodeWithTag("compare_choice_RIGHT").performClick()
        compose.onNodeWithTag("compare_review").performScrollToNode(hasTestTag("compare_continue"))
        assertCompleteButtonText("compare_continue", label(R.string.db_compare_preview), 1.5f)
        compose.onNodeWithTag("compare_continue").performClick()
        compose.onNodeWithTag("compare_generate").performScrollTo().assertIsDisplayed()
        assertCompleteButtonText("compare_generate", label(R.string.db_compare_write), 1.5f)
        compose.onNodeWithTag("compare_output_password").performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        capture("output-large")
    }

    @Test fun nativeDatabaseToolsEntryOpensComparisonWithCurrentDatabaseAndReturns() {
        show(management = true)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label(R.string.db_compare_title)))
        compose.onNodeWithText(label(R.string.db_compare_title)).performClick()
        waitForTag("database_compare_screen")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(left.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(left.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("compare_start").performScrollTo().assertIsNotEnabled()
        capture("management-entry")
        back()
        compose.onNodeWithTag("database_compare_screen").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.keepass_database_tools_title)).assertIsDisplayed()
        assertEquals(0, backCount)
    }

    private fun openPaneMenu(pane: Int) {
        compose.onNode(hasContentDescription(label(R.string.more_options)) and
            hasAnyAncestor(hasTestTag("manager-header-$pane"))).performClick()
    }

    @Test fun currentManagerRightPaneUsesItsDatabaseAndBackPreservesBothPanes() {
        show(currentManagement = true)
        waitForTag("manager-header-0")
        if (compose.onAllNodesWithTag("manager-header-1").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription(label(R.string.manager_dual)).performClick()
        }
        waitForTag("manager-header-1")
        openPaneMenu(1)
        compose.onNodeWithTag("manager-compare-1").assertDoesNotExist()
        Espresso.pressBack()
        val rightKey = ImportDestination(ImportDestinationKind.KEEPASS, right.id).key
        waitForTag("manager-store-1-$rightKey")
        compose.onNodeWithTag("manager-store-1-$rightKey").performScrollTo().performClick()
        openPaneMenu(1)
        compose.onNodeWithTag("manager-compare-1").assertIsEnabled().performClick()
        waitForTag("database_compare_screen")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(right.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(right.name).performScrollTo().assertIsDisplayed()
        back()
        compose.onNodeWithTag("manager-path-0").assertIsDisplayed()
        compose.onNodeWithTag("manager-path-1").assertIsDisplayed()
        openPaneMenu(0)
        compose.onNodeWithTag("manager-compare-0").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(left.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(left.name).performScrollTo().assertIsDisplayed()
        assertEquals(0, backCount)
    }

    @Test fun currentManagerKeepsComparisonRouteAcrossOutputPickerAndReturns() {
        show(currentManagement = true)
        waitForTag("manager-header-0")
        openPaneMenu(0)
        compose.onNodeWithTag("manager-compare-0").performClick()
        chooseInputsAndStart()
        waitForTag("compare_review")
        outputForm(); generate()
        val destination = output()
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED; picker.deliver(destination) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText(label(R.string.db_compare_saved)).fetchSemanticsNodes().isNotEmpty() }
        val reopened = context.contentResolver.openInputStream(destination)!!.use {
            KeePassDatabase.decode(it, Credentials.from(EncryptedValue.fromString(outputPassword)), contentParser = KdbxComparisonXml())
        }
        assertEquals(rightSecret, reopened.content.group.entries.single().fields.password!!.content)
        capture("current-manager-saved")
        back()
        compose.onNodeWithTag("database_compare_screen").assertDoesNotExist()
        compose.onNodeWithTag("manager-path-0").assertIsDisplayed()
        assertEquals(0, backCount)
    }

    @Test fun localManagerDoesNotOfferKdbxComparisonAndLockedScreenHidesMenu() {
        show(currentManagement = true, managerKey = ImportDestination.Local.key)
        waitForTag("manager-header-0")
        openPaneMenu(0)
        compose.onNodeWithTag("manager-compare-0").assertDoesNotExist()
        Espresso.pressBack()
        compose.runOnIdle { SessionManager.markLocked() }
        compose.onNodeWithTag("manager-header-0").assertDoesNotExist()
    }

    private fun assertCompleteButtonText(tag: String, text: String, fontScale: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertEquals(fontScale, layout.layoutInput.density.fontScale, 0f)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        assertFalse((0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertTrue(layout.multiParagraph.height <= layout.size.height + 1f)
    }

    private fun assertEmptyPassword(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    private fun output(): Uri {
        val authority = "${instrumentation.context.packageName}.kdbx-delete"
        return Uri.parse("content://$authority/delete-${UUID.randomUUID()}.kdbx").also { uri ->
            context.contentResolver.openOutputStream(uri, "wt")!!.close()
            outputUris += uri
        }
    }

    private fun command(uri: Uri, method: String, extras: Bundle? = null) =
        context.contentResolver.call(uri, method, uri.toString(), extras)

    private fun capture(name: String) {
        val directory = File(context.filesDir, "database-compare-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onNodeWithTag("database_compare_screen").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class PickerResults : ActivityResultRegistryOwner {
        var pendingCode: Int? = null
        var lastContract: ActivityResultContract<*, *>? = null
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                check(pendingCode == null)
                pendingCode = requestCode
                lastContract = contract
            }
        }
        fun deliver(uri: Uri?) {
            val code = checkNotNull(pendingCode)
            pendingCode = null
            activityResultRegistry.dispatchResult(code, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let { Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) })
        }
    }
}

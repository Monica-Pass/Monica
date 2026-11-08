package takagi.ru.monica.autofill_ng

import android.app.UiAutomation
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import takagi.ru.monica.R
import takagi.ru.monica.autofill_ng.auth.AutofillGrantContext
import takagi.ru.monica.autofill_ng.auth.AutofillSessionGrants
import takagi.ru.monica.autofill_ng.core.AutofillLogger
import takagi.ru.monica.autofill_ng.fixture.AutofillFormFixtureActivity
import takagi.ru.monica.autofill_ng.utils.SmartCopyNotificationHelper
import takagi.ru.monica.data.AppSettings
import takagi.ru.monica.data.KeePassDatabaseSourceType
import takagi.ru.monica.data.KeePassStorageLocation
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.keepass.KeePassMonicaPasswordStore
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import takagi.ru.monica.utils.KeePassKdbxService
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.utils.buildKeePassPathKey

/** Real KDBX plus actual V2/framework entry points; only an explicitly owned secondary user. */
class KeePassAutofillInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val user = android.os.Process.myUid() / 100000
    private val database by lazy { PasswordDatabase.getDatabase(context) }
    private val settings by lazy { SettingsManager(context) }
    private val preferences by lazy { AutofillPreferences(context) }
    private lateinit var security: SecurityManager
    private lateinit var service: KeePassKdbxService
    private lateinit var ui: UiAutomation
    private lateinit var uri: Uri
    private lateinit var fixture: KeePassDatabase
    private var databaseId = 0L
    private val ownedRoomIds = mutableListOf<Long>()
    private var oldSettings: AppSettings? = null
    private var oldIme: String? = null
    private var oldAutofillService: String? = null
    private var oldPreferences: List<Boolean>? = null
    private var oldEngine: AutofillPreferences.AutofillEngineMode? = null
    private var oldSource: AutofillPreferences.AutofillDefaultSourceFilter? = null
    private var oldKeePassId: Long? = null
    private var oldBitwardenId: Long? = null
    private var systemSettingsChanged = false
    private var successfulScrollActions = 0
    private val authorized = AtomicBoolean(true)
    private val credentials get() = Credentials.from(EncryptedValue.fromString(KDBX_PASSWORD))

    @Before fun prepare(): Unit = runBlocking {
        // Fail, rather than silently skip, if a host points this fixture at another user's vault.
        check(user > 0 && InstrumentationRegistry.getArguments().getString("issue179User") == user.toString()) {
            "Run only in the newly created issue179 test user with -e issue179User <id>"
        }
        ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        oldAutofillService = Settings.Secure.getString(context.contentResolver, "autofill_service")
        oldSettings = settings.settingsFlow.first()
        oldSource = preferences.v2DefaultSourceFilter.first()
        oldKeePassId = preferences.v2DefaultKeepassDatabaseId.first()
        oldBitwardenId = preferences.v2DefaultBitwardenVaultId.first()
        oldEngine = preferences.autofillEngineMode.first()
        oldPreferences = listOf(preferences.isAutofillEnabled.first(), preferences.isFillSuggestionsEnabled.first(),
            preferences.isManualSelectionEnabled.first(), preferences.isPasswordSuggestionEnabled.first(),
            preferences.isRequestSaveDataEnabled.first(), preferences.isOtpNotificationEnabled.first(),
            preferences.isAutoCopyOtpEnabled.first())
        security = SecurityManager(context)
        if (!security.isMasterPasswordSet()) check(security.setMasterPassword(MASTER_PASSWORD))
        SessionManager.attachAppContext(context)
        SessionManager.markLocked()
        check(security.unlockVaultWithPassword(MASTER_PASSWORD)) { "Existing vault is not this synthetic test vault" }
        assertFalse("Autofill must not unlock the main app", SessionManager.isUnlocked.value)
        service = KeePassKdbxService(context, database.localKeePassDatabaseDao(), security)
        settings.updateBiometricEnabled(false)
        settings.updateAutofillAuthRequired(true)
        settings.updateAutofillKeepUnlocked(true)
        settings.updateScreenshotProtectionEnabled(false)
        settings.updateAutoLockMinutes(5)
        SessionManager.updateAutoLockTimeout(5)
        preferences.setAutofillEnabled(true)
        preferences.setFillSuggestionsEnabled(true)
        preferences.setManualSelectionEnabled(true)
        preferences.setPasswordSuggestionEnabled(false)
        preferences.setRequestSaveDataEnabled(false)
        preferences.setOtpNotificationEnabled(false)
        preferences.setAutoCopyOtpEnabled(false)
        preferences.setAutofillEngineMode(AutofillPreferences.AutofillEngineMode.BITWARDEN_V2)
        preferences.setV2DefaultSourceFilter(AutofillPreferences.AutofillDefaultSourceFilter.ALL)
        preferences.setV2DefaultKeepassDatabaseId(null)
        preferences.setV2DefaultBitwardenVaultId(null)
        fixture = createFixture()
        uri = Uri.parse("content://$fixturePackage.kdbx-delete/delete-${UUID.randomUUID()}.kdbx")
        writeFixture(fixture)
        databaseId = database.localKeePassDatabaseDao().insertDatabase(LocalKeePassDatabase(
            name = DATABASE_NAME, filePath = uri.toString(), storageLocation = KeePassStorageLocation.EXTERNAL,
            sourceType = KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI,
            encryptedPassword = security.encryptData(KDBX_PASSWORD)))
        assertNoRoomMirror()
    }

    @After fun cleanup(): Unit = runBlocking {
        if (::ui.isInitialized) repeat(2) {
            ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
        if (databaseId != 0L) {
            if (::service.isInitialized) service.setDatabaseReadOnly(databaseId, false)
            KeePassKdbxService.invalidateProcessCache(databaseId)
            ownedRoomIds.forEach { database.passwordEntryDao().deletePasswordEntryById(it) }
            database.localKeePassDatabaseDao().deleteDatabaseById(databaseId)
        }
        if (::uri.isInitialized) context.contentResolver.delete(uri, null, null)
        oldSettings?.let {
            settings.updateBiometricEnabled(it.biometricEnabled)
            settings.updateAutofillAuthRequired(it.autofillAuthRequired)
            settings.updateAutofillKeepUnlocked(it.autofillKeepUnlocked)
            settings.updateScreenshotProtectionEnabled(it.screenshotProtectionEnabled)
            settings.updateAutoLockMinutes(it.autoLockMinutes)
            SessionManager.updateAutoLockTimeout(it.autoLockMinutes)
        }
        oldPreferences?.let {
            preferences.setAutofillEnabled(it[0]); preferences.setFillSuggestionsEnabled(it[1])
            preferences.setManualSelectionEnabled(it[2]); preferences.setPasswordSuggestionEnabled(it[3])
            preferences.setRequestSaveDataEnabled(it[4]); preferences.setOtpNotificationEnabled(it[5])
            preferences.setAutoCopyOtpEnabled(it[6])
        }
        oldEngine?.let { preferences.setAutofillEngineMode(it) }
        oldSource?.let { preferences.setV2DefaultSourceFilter(it) }
        if (oldSource != null) {
            preferences.setV2DefaultKeepassDatabaseId(oldKeePassId)
            preferences.setV2DefaultBitwardenVaultId(oldBitwardenId)
        }
        if (systemSettingsChanged) {
            restoreSecureSetting("autofill_service", oldAutofillService)
            if (!oldIme.isNullOrBlank()) {
                shell("ime set --user $user ${safeComponent(requireNotNull(oldIme))}")
                assertEquals(oldIme, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            } else restoreSecureSetting(Settings.Secure.DEFAULT_INPUT_METHOD, oldIme)
        }
        if (::security.isInitialized) {
            AutofillSessionGrants.clear()
            SessionManager.markLocked()
        }
    }

    @Test fun originalKdbxLoadsWithoutRoomOrMainSessionAndNewSourceResolvesStableIds() = runBlocking {
        val before = bytes()
        val localId = database.passwordEntryDao().insertPasswordEntry(PasswordEntry(
            title = "Issue179 ordinary local login", username = "local-user", password = security.encryptData("local-secret"), website = ""))
        ownedRoomIds += localId
        val source = source()
        val loaded = source.load()
        assertTrue("Ordinary Room entries remain available", loaded.any { it.id == localId })
        val rows = loaded.filter { it.keepassDatabaseId == databaseId }
        assertEquals(PASSWORDS.size, rows.size)
        assertTrue(rows.all { KeePassMonicaPasswordStore.isUiId(it.id) })
        assertEquals(PASSWORDS.toSet(), rows.map { it.password }.toSet())
        val expectedFolder = buildKeePassPathKey(null, FOLDER)
        val expectedEmptyFolder = buildKeePassPathKey(null, EMPTY_FOLDER)
        assertEquals("Native groups use exact encoded path keys", setOf(expectedFolder, expectedEmptyFolder),
            source.groups.getValue(databaseId).toSet())
        assertTrue("All fixture rows belong to the populated native folder", rows.all { it.keepassGroupPath == expectedFolder })
        val next = source()
        rows.forEach { entry ->
            assertEquals(entry, next.get(entry.id))
            assertEquals(entry, next.resolve(entry))
            assertTrue(next.isCurrent(entry))
            val selection = requireNotNull(source.selection(entry))
            assertTrue(next.matchesSelection(entry, selection))
            assertFalse(next.matchesSelection(entry, selection.copy(revision = "stale-revision")))
            assertFalse(next.matchesSelection(entry, selection.copy(generation = selection.generation + 1)))
        }
        assertNoRoomMirror()
        assertFalse(SessionManager.isUnlocked.value)
        assertArrayEquals(before, bytes())
    }

    @Test fun nativeCiphertextLookingValuesRemainLiteralAtAccountAndSecretBoundary() = runBlocking {
        val rows = source().load().filter { it.keepassDatabaseId == databaseId }
        rows.forEach { entry ->
            assertEquals("Native account is plaintext", nativeEntry(entry).fields.getValue("UserName").content,
                AccountFillPolicy.resolveAccountIdentifier(entry, security))
            assertEquals("Native secret is plaintext", nativeEntry(entry).fields.getValue("Password").content,
                AutofillSecretResolver.resolvePasswordOrNull(security, entry))
        }
        assertNoRoomMirror()
    }

    @Test fun nativeDeferredActionReopensAfterSourceClosesButCannotOutliveReplacementOrExpiry() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        val selection = requireNotNull(source.selection(entry))
        val action = NativeAutofillAction.create(selection, fixturePackage, null)
        val bundle = action.toBundle()
        PASSWORDS.forEach { assertFalse("Deferred token must not carry a password", bundle.toString().contains(it)) }
        assertFalse("Deferred token must not carry the account", bundle.toString().contains(entry.username))
        val transported = requireNotNull(NativeAutofillAction.fromBundle(bundle))
        source.clear()
        assertTrue(transported.isCurrent(context))
        assertEquals(entry, transported.resolve(context))
        assertFalse(SessionManager.isUnlocked.value)
        val replacement = NativeAutofillAction.create(selection, fixturePackage, null, ttlMillis = 1)
        assertFalse(action.isCurrent(context))
        assertNull(action.resolve(context))
        delay(10)
        assertFalse(replacement.isCurrent(context))
        assertNull(replacement.resolve(context))
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun nativeDeferredActionRejectsNativeLockAndReauthenticationCannotReviveOldGeneration() = runBlocking {
        val firstSource = source()
        val entry = firstSource.load().first { it.keepassDatabaseId == databaseId }
        val lockedAction = NativeAutofillAction.create(requireNotNull(firstSource.selection(entry)), fixturePackage, null)
        firstSource.clear()
        service.lockDatabase(databaseId)
        assertFalse(lockedAction.isCurrent(context))
        assertNull(lockedAction.resolve(context))
        // A new explicit synthetic authorization may read the newly locked epoch; the old token cannot.
        val secondSource = source(allowLockedDatabases = true)
        val current = requireNotNull(secondSource.get(entry.id))
        val revokedAction = NativeAutofillAction.create(requireNotNull(secondSource.selection(current)), fixturePackage, null)
        secondSource.clear()
        SecurityManager.clearRuntimeUnlockCache()
        check(security.unlockVaultWithPassword(MASTER_PASSWORD))
        assertFalse(revokedAction.isCurrent(context))
        assertNull(revokedAction.resolve(context))
        assertFalse(SessionManager.isUnlocked.value)
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun nativeDeferredActionRejectsExternalRevisionAfterPickerSourceWasCleared() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        val action = NativeAutofillAction.create(requireNotNull(source.selection(entry)), fixturePackage, null)
        source.clear()
        writeFixture(fixture.modifyParentGroup { copy(name = "Changed after picker closed") })
        val external = bytes()
        assertNull(action.resolve(context))
        assertArrayEquals(external, bytes())
        assertNoRoomMirror()
    }

    @Test fun realScreenOffThenWakeCannotReviveDeferredNativeAction() = runBlocking {
        ActivityScenario.launch<takagi.ru.monica.suggestions.CommonInfoTestActivity>(
            Intent(context, takagi.ru.monica.suggestions.CommonInfoTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)).use {
            val power = context.getSystemService(android.os.PowerManager::class.java)
            val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            check(power.isInteractive && !keyguard.isKeyguardLocked) { "Owned test user must start awake and unlocked" }
            val source = source()
            val entry = source.load().first { it.keepassDatabaseId == databaseId }
            val selection = requireNotNull(source.selection(entry))
            val action = NativeAutofillAction.create(selection, fixturePackage, null)
            source.clear()
            assertTrue(action.isCurrent(context))
            val screenOff = kotlinx.coroutines.CompletableDeferred<Unit>()
            val observer = object : android.content.BroadcastReceiver() {
                override fun onReceive(receiverContext: android.content.Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) screenOff.complete(Unit)
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(context, observer,
                android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                shell("input keyevent KEYCODE_SLEEP")
                withTimeout(10_000) { while (power.isInteractive) delay(20) }
                withTimeout(10_000) { screenOff.await() }
                withContext(Dispatchers.Main) { /* Process the delivered real screen-off event before waking. */ }
                // Do not observe action.isCurrent while asleep: that also revokes a token.
                // The post-wake assertion must prove the real screen-off broadcast revoked it.
            } finally {
                // Never leave the shared emulator asleep if a verification assertion fails.
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                context.unregisterReceiver(observer)
            }
            withTimeout(10_000) { while (!power.isInteractive || keyguard.isKeyguardLocked) delay(20) }
            assertFalse("Screen-off revocation must survive waking the device", action.isCurrent(context))
            assertNull(action.resolve(context))
            check(security.unlockVaultWithPassword(MASTER_PASSWORD))
            assertFalse("Password unlock must not revive the old deferred action", action.isCurrent(context))
            assertNull(action.resolve(context))
            assertFalse(SessionManager.isUnlocked.value)
            assertArrayEquals(encodedFixtureBytes, bytes())
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "ISSUE179 realScreenOff=true awakened=true actionRevoked=true " +
                    "generationChanged=${selection.generation != SecurityManager.dataReadGeneration.value}\n")
            })
        }
    }

    @Test fun realNativeSmartCopyNotificationResolvesAfterSourceClosesAndRejectsLockedVault() = runBlocking {
        val notifications = context.getSystemService(NotificationManager::class.java)
        check(notifications.areNotificationsEnabled()) {
            "Grant POST_NOTIFICATIONS to this task's synthetic test user before the notification test"
        }
        val channelExisted = notifications.getNotificationChannel("smart_copy_channel") != null
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        ActivityScenario.launch<AutofillPickerActivityV2>(pickerIntent()).use {
            verifyMasterPassword()
        }
        // Keep a non-vault foreground window for Android's clipboard-read policy. Locking a
        // native database intentionally closes the real picker; that must not fake a null clip.
        ActivityScenario.launch<takagi.ru.monica.suggestions.CommonInfoTestActivity>(
            Intent(context, takagi.ru.monica.suggestions.CommonInfoTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)).use {
            val oldClip = withContext(Dispatchers.Main) { clipboard.primaryClip }
            try {
                suspend fun queueNative(): Pair<AutofillPasswordSource, android.app.PendingIntent> {
                    val source = source()
                    val entry = source.load().first { it.keepassDatabaseId == databaseId && it.title == title(0) }
                    val action = NativeAutofillAction.create(requireNotNull(source.selection(entry)), fixturePackage, null)
                    assertTrue(SmartCopyNotificationHelper.copyAndQueueNative(context, action,
                        usernameFirst = true, isRequestCurrent = { true }))
                    val pending = withTimeout(5_000) {
                        var notification = notifications.activeNotifications.firstOrNull { it.id == 9527 }
                        while (notification == null) {
                            delay(10)
                            notification = notifications.activeNotifications.firstOrNull { it.id == 9527 }
                        }
                        requireNotNull(notification.notification.contentIntent)
                    }
                    return source to pending
                }
                val (firstSource, validPending) = queueNative()
                firstSource.clear()
                validPending.send()
                withTimeout(10_000) {
                    while (withContext(Dispatchers.Main) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() } != PASSWORDS[0]) delay(20)
                }
                withTimeout(5_000) { while (notifications.activeNotifications.any { it.id == 9527 }) delay(10) }
                val (secondSource, lockedPending) = queueNative()
                secondSource.clear()
                val sentinel = "Issue179 clipboard must remain unchanged"
                withContext(Dispatchers.Main) { clipboard.setPrimaryClip(ClipData.newPlainText("Synthetic sentinel", sentinel)) }
                service.lockDatabase(databaseId)
                lockedPending.send()
                withTimeout(5_000) { while (notifications.activeNotifications.any { it.id == 9527 }) delay(10) }
                assertEquals(sentinel, withContext(Dispatchers.Main) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() })
                assertFalse(SessionManager.isUnlocked.value)
                assertNoRoomMirror()
                assertArrayEquals(encodedFixtureBytes, bytes())
            } finally {
                SmartCopyNotificationHelper.dismissNotification(context)
                withContext(Dispatchers.Main) {
                    if (oldClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(oldClip)
                }
                if (!channelExisted) notifications.deleteNotificationChannel("smart_copy_channel")
            }
        }
    }

    @Test fun targetAuthorizationRevocationRejectsLoadedAndNewSelections() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        authorized.set(false)
        assertFalse(source.isCurrent(entry))
        assertNull(runCatching { source.resolve(entry) }.getOrNull())
        assertNull(runCatching { source().get(entry.id) }.getOrNull())
        assertFalse(runCatching { source.saveUriBinding(entry, "org.issue179.revoked", null) }.getOrDefault(false))
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun runtimeGenerationRevocationCannotBeRevivedByReloadingTheKey() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        SecurityManager.clearRuntimeUnlockCache()
        check(security.unlockVaultWithPassword(MASTER_PASSWORD))
        assertFalse(source.isCurrent(entry))
        assertNull(runCatching { source.resolve(entry) }.getOrNull())
        assertFalse(SessionManager.isUnlocked.value)
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun clearingRequestPermanentlyRevokesItWithoutRevokingAnotherNewRequest() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        source.clear()
        assertFalse(source.isCurrent(entry))
        assertTrue(source.load().isEmpty())
        assertNull(source.get(entry.id))
        assertNull(source.resolve(entry))
        assertTrue(source.groups.isEmpty())
        assertFalse(source.saveUriBinding(entry, "org.issue179.afterclear", null))
        assertEquals(entry, source().get(entry.id))
        assertNoRoomMirror()
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun clearedRequestRejectsLateNativeReadAfterProviderUnblocks() = runBlocking {
        lateReadIsRejected(clear = true)
    }

    @Test fun revokedAuthorizationRejectsLateNativeReadAfterProviderUnblocks() = runBlocking {
        lateReadIsRejected(clear = false)
    }

    private suspend fun lateReadIsRejected(clear: Boolean) = kotlinx.coroutines.coroutineScope {
        val source = source()
        command("block-read")
        val read = async(Dispatchers.Default) { source.load() }
        try {
            withTimeout(10_000) { while (counter("read_blocked") == 0) delay(10) }
            if (clear) source.clear() else authorized.set(false)
        } finally {
            command("release-read")
        }
        assertTrue("Late data must not be published", withTimeout(15_000) { read.await() }.isEmpty())
        assertTrue(source.groups.isEmpty())
        assertTrue(source.load().isEmpty())
        assertFalse("Revoked decoder must not leave a usable native cache", service.isDatabaseUnlocked(databaseId))
        assertNoRoomMirror()
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun explicitNativeLockRejectsSelectionsAndDoesNotFallBackToRoomMirror() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        insertShadow(entry)
        service.lockDatabase(databaseId)
        assertFalse(source.isCurrent(entry))
        assertNull(runCatching { source.resolve(entry) }.getOrNull())
        assertTrue(runCatching { source().load() }.getOrDefault(emptyList()).none { it.keepassDatabaseId == databaseId })
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun unreadableNativeFileDoesNotLeakStaleRoomProjection() = runBlocking {
        val entry = source().load().first { it.keepassDatabaseId == databaseId }
        insertShadow(entry)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }
        KeePassKdbxService.invalidateProcessCache(databaseId)
        assertTrue(runCatching { source().load() }.getOrDefault(emptyList()).none { it.keepassDatabaseId == databaseId })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes())
    }

    @Test fun lowIdNativeMirrorCipherCallbackRequiresFreshPickerSelection() = runBlocking {
        assertLowIdMirrorCallbackRedirects(includeDirectDataset = false)
    }

    @Test fun lowIdNativeMirrorDirectDatasetRequiresFreshPickerSelection() = runBlocking {
        assertLowIdMirrorCallbackRedirects(includeDirectDataset = true)
    }

    @Test fun directDatasetWithMissingRoomOwnerIsCancelled() = runBlocking {
        val id = android.widget.EditText(context).autofillId
        val missingId = -179L
        assertNull(database.passwordEntryDao().getPasswordEntryById(missingId))
        val callback = AutofillCipherCallbackActivity.getIntent(context, AutofillCipherCallbackActivity.Args(
            passwordId = missingId, applicationId = fixturePackage,
            autofillIds = arrayListOf(id), autofillHints = arrayListOf("PASSWORD"), requireAuthentication = false,
        )).putExtra(AutofillCipherCallbackActivity.EXTRA_DIRECT_DATASET,
            android.service.autofill.Dataset.Builder(android.widget.RemoteViews(context.packageName,
                android.R.layout.simple_list_item_1)).setValue(id,
                android.view.autofill.AutofillValue.forText("stale-direct-must-not-fill")).build())
        ActivityScenario.launchActivityForResult<AutofillCipherCallbackActivity>(callback).use { scenario ->
            val result = scenario.result
            assertEquals(android.app.Activity.RESULT_CANCELED, result.resultCode)
            assertFalse(result.resultData?.hasExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT) == true)
        }
        assertFalse(SessionManager.isUnlocked.value)
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    private suspend fun assertLowIdMirrorCallbackRedirects(includeDirectDataset: Boolean) {
        val source = source()
        val native = source.load().first { it.keepassDatabaseId == databaseId }
        insertShadow(native)
        val lowId = ownedRoomIds.last()
        assertFalse(KeePassMonicaPasswordStore.isUiId(lowId))
        source.clear()
        val unreadable = byteArrayOf(1, 2, 3, 4)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(unreadable) }
        KeePassKdbxService.invalidateProcessCache(databaseId)
        AutofillSessionGrants.clear()
        val id = android.widget.EditText(context).autofillId
        val callback = AutofillCipherCallbackActivity.getIntent(context, AutofillCipherCallbackActivity.Args(
            passwordId = lowId, applicationId = fixturePackage,
            autofillIds = arrayListOf(id), autofillHints = arrayListOf("PASSWORD"), requireAuthentication = false,
        ))
        if (includeDirectDataset) callback.putExtra(AutofillCipherCallbackActivity.EXTRA_DIRECT_DATASET,
            android.service.autofill.Dataset.Builder(android.widget.RemoteViews(context.packageName,
                android.R.layout.simple_list_item_1)).setValue(id,
                android.view.autofill.AutofillValue.forText("stale-direct-must-not-fill")).build())
        val pickerMonitor = instrumentation.addMonitor(AutofillPickerActivityV2::class.java.name, null, false)
        try {
            ActivityScenario.launchActivityForResult<AutofillCipherCallbackActivity>(callback).use { scenario ->
                val picker = instrumentation.waitForMonitorWithTimeout(pickerMonitor, 10_000)
                assertNotNull("A registered native mirror must redirect to the real picker", picker)
                waitNode { it.isEditable && it.isPassword }
                assertAbsent { it.text?.toString() == "stale-shadow-must-not-fill" ||
                    it.text?.toString() == "stale-direct-must-not-fill" }
                hideResumedPickerIme()
                tap(waitText(context.getString(R.string.cancel)))
                val result = scenario.result
                assertEquals(android.app.Activity.RESULT_CANCELED, result.resultCode)
                assertFalse(result.resultData?.hasExtra(android.view.autofill.AutofillManager.EXTRA_AUTHENTICATION_RESULT) == true)
            }
        } finally {
            instrumentation.removeMonitor(pickerMonitor)
        }
        assertFalse(SessionManager.isUnlocked.value)
        assertArrayEquals(unreadable, bytes())
        assertNotNull("Rejecting the mirror must retain its existing Room data", database.passwordEntryDao().getPasswordEntryById(lowId))
    }

    @Test fun externalRevisionChangeRejectsSelectedValueAndBindingWithoutWriting() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        writeFixture(fixture.modifyParentGroup { copy(name = "Externally changed root") })
        val external = bytes()
        assertNull(runCatching { source.resolve(entry) }.getOrNull())
        assertFalse(runCatching { source.saveUriBinding(entry, "org.issue179.stale", "stale.example.test") }.getOrDefault(false))
        assertArrayEquals(external, bytes())
    }

    @Test fun readOnlyNativeFileCanFillButCannotBind() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        service.setDatabaseReadOnly(databaseId, true)
        assertEquals(entry, source.resolve(entry))
        assertFalse(runCatching { source.saveUriBinding(entry, "org.issue179.readonly", null) }.getOrDefault(false))
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun referencedNativeBindingFieldsAreNotExpandedOrOverwritten() = runBlocking {
        val target = allNativeEntries(fixture).first { it.fields["Title"]?.content == title(1) }
        val rawUrl = "{S:Foreign URL}"
        val rawApp = "{REF:U@I:${target.uuid.toString().replace("-", "")}}"
        fixture = fixture.modifyParentGroup {
            copy(groups = groups.map { group -> group.copy(entries = group.entries.map { entry ->
                if (entry.fields["Title"]?.content == title(0)) entry.copy(fields = entry.fields +
                    ("URL" to EntryValue.Plain(rawUrl)) +
                    ("Foreign URL" to EntryValue.Plain("https://reference.example.test")) +
                    ("App Package Name" to EntryValue.Encrypted(EncryptedValue.fromString(rawApp))))
                else entry
            }) })
        }
        writeFixture(fixture)
        val before = bytes()
        val source = source()
        val entry = source.load().single { it.keepassDatabaseId == databaseId && it.title == title(0) }
        assertEquals("https://reference.example.test", entry.website)
        assertEquals(username(1), entry.appPackageName)
        assertFalse(source.saveUriBinding(entry, null, "new.example.test"))
        assertFalse(source.saveUriBinding(entry, "org.issue179.newapp", null))
        assertArrayEquals(before, bytes())
        val reopened = context.contentResolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
        val original = allNativeEntries(reopened).single { it.uuid.toString() == entry.keepassEntryUuid }
        assertEquals(rawUrl, original.fields.getValue("URL").content)
        assertEquals(rawApp, original.fields.getValue("App Package Name").content)
        assertTrue(original.fields.getValue("App Package Name") is EntryValue.Encrypted)
        assertNoRoomMirror()
    }

    @Test fun revocationDuringFinalSourceHashPreventsNativeBindingCommit() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        val selection = requireNotNull(source.selection(entry))
        val before = bytes()
        val writesBefore = counter("writes")
        val commitsBefore = counter("commits")
        // With the source already cached, writeExternal reads the original, the encrypted
        // recovery point, then the final source hash immediately before its commit boundary.
        command("block-read", Bundle().apply { putInt("read", 3) })
        val write = async(Dispatchers.IO) {
            service.patchAutofillUriBinding(databaseId, UUID.fromString(entry.keepassEntryUuid),
                selection.revision, "https://must-not-commit.example.test", null, null) {
                check(source.isCurrent(entry)) { "Synthetic autofill authorization revoked" }
            }
        }
        try {
            withTimeout(10_000) { while (counter("read_blocked") == 0) delay(10) }
            assertEquals("No output stream may open before final authorization", writesBefore, counter("writes"))
            authorized.set(false)
        } finally {
            command("release-read")
        }
        assertTrue(withTimeout(15_000) { write.await() }.isFailure)
        assertEquals(writesBefore, counter("writes"))
        assertEquals("No truncation/commit or rollback of an untouched file", commitsBefore, counter("commits"))
        assertArrayEquals(before, bytes())
        assertNoRoomMirror()
    }

    @Test fun bindingPreservesUnknownProtectedFieldsHistoryAttachmentsAndSibling() = runBlocking {
        val source = source()
        val entry = source.load().first { it.keepassDatabaseId == databaseId }
        val before = nativeEntry(entry)
        assertTrue(source.saveUriBinding(entry, "org.issue179.bound", "bound.example.test"))
        val reopened = context.contentResolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
        val after = allNativeEntries(reopened).single { it.uuid == before.uuid }
        for (key in listOf("Title", "UserName", "Password", "Notes", "Foreign protected", "Empty custom")) {
            assertEquals(key, before.fields[key]?.content, after.fields[key]?.content)
            assertEquals(key, before.fields[key] is EntryValue.Encrypted, after.fields[key] is EntryValue.Encrypted)
        }
        assertEquals(before.uuid, after.uuid)
        assertEquals(before.tags, after.tags)
        assertEquals(before.customData, after.customData)
        assertTrue(after.history.any { it.fields["Title"]?.content == "Old native title" })
        assertEquals(before.binaries, after.binaries)
        assertArrayEquals(ATTACHMENT, reopened.binaries.getValue(after.binaries.single().hash).getContent())
        allNativeEntries(fixture).filter { it.uuid != before.uuid }.forEach { original ->
            val unchanged = allNativeEntries(reopened).single { it.uuid == original.uuid }
            assertEquals(original.fields.toMap().mapValues { it.value.content }, unchanged.fields.toMap().mapValues { it.value.content })
        }
        assertTrue(after.fields["App Package Name"]?.content.orEmpty().contains("org.issue179.bound"))
        assertTrue(after.fields["URL"]?.content.orEmpty().contains("bound.example.test"))
        assertNoRoomMirror()
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun immediatePickerRecreationRetainsAuthenticationWithinDuplicateWindow() {
        val requestId = "issue179-recreation-${UUID.randomUUID()}"
        withPickerLifecycleProbe(requestId) { creations, stages ->
            ActivityScenario.launch<AutofillPickerActivityV2>(pickerRegressionIntent(requestId)).use { scenario ->
                val original = creations.single().first
                scenario.recreate()
                val recreated = creations.single { it.first !== original }
                val elapsed = recreated.second - creations.first().second
                assertTrue("Regression must exercise recreation inside the real 1500 ms duplicate window: $elapsed ms", elapsed in 0L..1500L)
                assertEquals(Stage.RESUMED, stages[recreated.first])
                scenario.onActivity {
                    assertSame(recreated.first, it)
                    assertFalse(it.isFinishing)
                }
                waitNode { it.isEditable && it.isPassword }
                assertFalse("Recreation must not authenticate the request", AutofillSessionGrants.isGranted(
                    AutofillGrantContext(fixturePackage, null, requestId, null)))
                assertFalse(SessionManager.isUnlocked.value)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "ISSUE179 recreationElapsedMs=$elapsed newActivityResumed=true authenticationRequired=true\n")
                })
            }
        }
    }

    @Test fun nativeLockDuringPickerRecreationCannotRestoreVerification() = runBlocking {
        val requestId = "issue179-lock-recreation-${UUID.randomUUID()}"
        val grant = AutofillGrantContext(fixturePackage, null, requestId, null)
        ActivityScenario.launch<AutofillPickerActivityV2>(pickerRegressionIntent(requestId)).use { scenario ->
            verifyMasterPassword()
            assertTrue("Only actual password verification may grant this request", AutofillSessionGrants.isGranted(grant))
            val oldSource = source()
            val oldEntry = oldSource.load().first { it.keepassDatabaseId == databaseId }
            val oldSelection = requireNotNull(oldSource.selection(oldEntry))
            var original: AutofillPickerActivityV2? = null
            scenario.onActivity { original = it }
            val lockVersion = KeePassKdbxService.nativeLockVersions.value[databaseId] ?: 0L
            val lockedAtRecreation = AtomicBoolean(false)
            val grantWasActiveAtLock = AtomicBoolean(false)
            val replacementStage = java.util.concurrent.atomic.AtomicReference<Stage>()
            val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
            val observer = ActivityLifecycleCallback { activity, stage ->
                if (activity is AutofillPickerActivityV2 && activity !== original &&
                    activity.intent.getStringExtra("issue179_regression_request") == requestId) {
                    replacementStage.set(stage)
                    if (stage == Stage.CREATED && lockedAtRecreation.compareAndSet(false, true)) {
                        // Exercise the real native lock at the replacement Activity boundary.
                        grantWasActiveAtLock.set(AutofillSessionGrants.isGranted(grant))
                        service.lockDatabase(databaseId)
                    }
                }
            }
            instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(observer) }
            try {
                scenario.onActivity { it.recreate() }
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while ((!lockedAtRecreation.get() || replacementStage.get() !in setOf(Stage.RESUMED, Stage.DESTROYED)) &&
                    SystemClock.elapsedRealtime() < deadline) delay(20)
                assertTrue("The replacement must cross the actual lock boundary", lockedAtRecreation.get())
                assertTrue("The lock must revoke a real existing password grant", grantWasActiveAtLock.get())
                assertEquals(lockVersion + 1, KeePassKdbxService.nativeLockVersions.value[databaseId])
                assertFalse(AutofillSessionGrants.isGranted(grant))
                assertFalse(oldSource.isCurrent(oldEntry))
                assertNull(oldSource.getSelected(oldSelection))
                if (replacementStage.get() != Stage.DESTROYED) {
                    waitNode { (it.isEditable && it.isPassword) ||
                        it.text?.toString() == context.getString(R.string.keepass_native_load_failed) }
                }
                assertAbsent { it.text?.toString() in PASSWORDS.indices.map(::title) }
                assertFalse(SessionManager.isUnlocked.value)
            } finally {
                oldSource.clear()
                instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(observer) }
            }
        }
        // A fresh request may reopen this epoch only after actual password verification.
        val newRequestId = "$requestId-verified"
        ActivityScenario.launch<AutofillPickerActivityV2>(pickerRegressionIntent(newRequestId)).use {
            verifyMasterPassword()
            assertTrue(AutofillSessionGrants.isGranted(AutofillGrantContext(fixturePackage, null, newRequestId, null)))
            waitText(title(0))
            assertFalse(SessionManager.isUnlocked.value)
            assertNoRoomMirror()
            assertArrayEquals(encodedFixtureBytes, bytes())
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "ISSUE179 nativeLockDuringRecreation=true oldGrantRevoked=true reverifiedByPassword=true\n")
            })
        }
    }

    @Test fun genuineDuplicatePickerLaunchStillFinishesOnlySecondActivity() {
        val requestId = "issue179-duplicate-${UUID.randomUUID()}"
        withPickerLifecycleProbe(requestId) { creations, stages ->
            val intent = pickerRegressionIntent(requestId)
            ActivityScenario.launch<AutofillPickerActivityV2>(intent).use {
                val original = creations.single().first
                val suppressionsBefore = AutofillLogger.getRecentLogs(500).count {
                    it.category == "PICKER" && it.message.startsWith("Suppress duplicate picker launch") }
                // ActivityScenario may add CLEAR_TASK to its launch intent. A second user
                // launch must not clear the first task before production can reject it.
                context.startActivity(Intent(intent).apply { removeFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) })
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while ((creations.size < 2 || stages[creations.last().first] != Stage.DESTROYED) &&
                    SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                assertEquals("Two independent Activity instances must have been created", 2, creations.size)
                val duplicate = creations.last()
                val elapsed = duplicate.second - creations.first().second
                assertTrue("Regression must exercise a fresh launch inside the real 1500 ms window: $elapsed ms", elapsed in 0L..1500L)
                assertEquals(Stage.DESTROYED, stages[duplicate.first])
                assertEquals(suppressionsBefore + 1, AutofillLogger.getRecentLogs(500).count {
                    it.category == "PICKER" && it.message.startsWith("Suppress duplicate picker launch") })
                assertEquals("The original picker must remain usable", Stage.RESUMED, stages[original])
                waitNode { it.isEditable && it.isPassword }
                assertFalse(SessionManager.isUnlocked.value)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("stream", "ISSUE179 duplicateElapsedMs=$elapsed duplicateDestroyed=true originalResumed=true\n")
                })
            }
        }
    }

    @Test fun actualPickerAuthenticatesSearchesNativeRowsAndShowsEmptyFolders() = runBlocking {
        ActivityScenario.launch<AutofillPickerActivityV2>(pickerIntent()).use { scenario ->
            // Folder controls are verified in portrait; other framework flows retain device orientation.
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            val portraitDeadline = SystemClock.elapsedRealtime() + 5_000
            var portraitReady = false
            do {
                scenario.onActivity {
                    val root = it.window.decorView
                    portraitReady = it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT &&
                        root.width > 0 && root.height > root.width && it.hasWindowFocus()
                }
                if (!portraitReady) Thread.sleep(50)
            } while (!portraitReady && SystemClock.elapsedRealtime() < portraitDeadline)
            assertTrue("Folder UI fixture must have a focused portrait window", portraitReady)
            verifyMasterPassword()
            waitText(title(0))
            screenshotIfRequested("picker-list")
            setSearch(title(1))
            waitText(title(1))
            assertAbsent(requirePickerWindow = true, expectedVisibleText = title(1)) { it.text?.toString() == title(0) }
            screenshotIfRequested("picker-search")
            setSearch(username(2))
            waitText(title(2))
            setSearch("")
            waitText(title(0))
            // ACTION_SET_TEXT may not open an IME; Back could dismiss the picker itself.
            hidePickerIme(scenario)
            tapPickerControl(context.getString(R.string.filter_title))
            tapPickerControl(context.getString(R.string.filter_keepass))
            tapPickerControl(context.getString(R.string.password_picker_all_databases), editable = true)
            tapPickerControl(DATABASE_NAME, editable = false)
            tapPickerControl(context.getString(R.string.password_picker_all_folders), editable = true)
            waitText(EMPTY_FOLDER)
            assertAbsent(requirePickerWindow = true, expectedVisibleText = EMPTY_FOLDER) {
                it.text?.toString() in setOf(buildKeePassPathKey(null, FOLDER), buildKeePassPathKey(null, EMPTY_FOLDER))
            }
            screenshotIfRequested("picker-folders")
            tapPickerControl(EMPTY_FOLDER, editable = false)
            tapPickerControl(context.getString(R.string.filter_title))
            assertAbsent(requirePickerWindow = true) { it.text?.toString() in PASSWORDS.indices.map(::title) }
            tapPickerControl(context.getString(R.string.filter_title))
            tapPickerControl(EMPTY_FOLDER, editable = true)
            tapPickerControl(FOLDER, editable = false)
            tapPickerControl(context.getString(R.string.filter_title))
            waitText(title(0))
            scenario.recreate()
            waitText(title(0))
            assertNoRoomMirror()
            assertFalse(SessionManager.isUnlocked.value)
            assertArrayEquals(encodedFixtureBytes, bytes())
        }
    }

    @Test fun frameworkManualPickerAuthenticatesAndFillsNativePrefixPassword() = runBlocking {
        enableFramework()
        openForm(0)
        focusForm()
        tap(waitText(context.getString(R.string.autofill_manual_entry_title)))
        verifyMasterPassword()
        tap(waitText(title(0)))
        tap(waitText(context.getString(R.string.autofill)))
        expectForm("user=OK password=OK")
        assertFalse(SessionManager.isUnlocked.value)
        assertNoRoomMirror()
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun frameworkSuggestionsResolveAllLiteralNativePasswordsAfterPickerAuthentication() = runBlocking {
        enableFramework()
        // Real app password authentication grants this exact external form app, while main stays locked.
        ActivityScenario.launch<AutofillPickerActivityV2>(pickerIntent()).use {
            verifyMasterPassword()
            waitText(title(0))
            assertTrue(AutofillSessionGrants.isGranted(AutofillGrantContext(fixturePackage, null, null, null)))
        }
        for (index in PASSWORDS.indices) {
            openForm(index)
            focusForm()
            val scrollsBefore = successfulScrollActions
            tapFrameworkSuggestion(title(index))
            expectForm("user=OK password=OK")
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "ISSUE179 suggestion=$index scrollActions=${successfulScrollActions - scrollsBefore} user=OK password=OK\n")
            })
        }
        assertNoRoomMirror()
        assertFalse(SessionManager.isUnlocked.value)
        assertArrayEquals(encodedFixtureBytes, bytes())
    }

    @Test fun frameworkFillAndBindCommitsNativeBindingAndStillReturnsBothValues() = runBlocking {
        // The selected entry must lack this app binding; a no-op save would miss the regression.
        fixture = fixture.modifyParentGroup {
            copy(groups = groups.map { group ->
                group.copy(entries = group.entries.map { entry ->
                    if (entry.fields["Title"]?.content == title(0)) entry.copy(fields = entry.fields +
                        ("App Package Name" to EntryValue.Plain("")) + ("URL" to EntryValue.Plain("")))
                    else entry
                })
            })
        }
        writeFixture(fixture)
        val before = bytes()
        enableFramework()
        openForm(0)
        focusForm()
        tap(waitText(context.getString(R.string.autofill_manual_entry_title)))
        verifyMasterPassword()
        tap(waitText(title(0)))
        tap(waitText(context.getString(R.string.autofill_and_save_uri)))
        expectForm("user=OK password=OK")
        val reopened = context.contentResolver.openInputStream(uri)!!.use { KeePassDatabase.decode(it, credentials) }
        val after = allNativeEntries(reopened).single { it.fields["Title"]?.content == title(0) }
        assertFalse("The binding action must have actually committed", before.contentEquals(bytes()))
        assertTrue(after.fields["App Package Name"]?.content.orEmpty().contains(fixturePackage))
        assertEquals(PASSWORDS[0], after.fields.getValue("Password").content)
        assertTrue(after.fields.getValue("Password") is EntryValue.Encrypted)
        assertEquals("opaque-native-value", after.fields.getValue("Foreign protected").content)
        assertTrue(after.fields.getValue("Foreign protected") is EntryValue.Encrypted)
        assertFalse(SessionManager.isUnlocked.value)
        assertNoRoomMirror()
    }

    @Test fun cancellingNativePickerAuthenticationLeavesFrameworkFormEmpty() = runBlocking {
        enableFramework()
        openForm(0)
        focusForm()
        tap(waitText(context.getString(R.string.autofill_manual_entry_title)))
        waitNode { it.isEditable && it.isPassword }
        awaitFocusedPasswordIme()
        hideResumedPickerIme()
        tap(waitText(context.getString(R.string.cancel)))
        expectForm("user=EMPTY password=EMPTY")
        assertFalse(AutofillSessionGrants.isGranted(AutofillGrantContext(fixturePackage, null, null, null)))
        assertFalse(SessionManager.isUnlocked.value)
        assertNoRoomMirror()
    }

    private var encodedFixtureBytes = byteArrayOf()
    private fun createFixture(): KeePassDatabase {
        val attachment = BinaryData.Uncompressed(true, ATTACHMENT)
        val created = KeePassDatabase.Ver4x.create("Issue179Root", Meta(generator = "Issue179 fixture"), credentials)
        val seed = when (val kdf = created.header.kdfParameters) {
            is KdfParameters.Aes -> kdf.seed
            is KdfParameters.Argon2 -> kdf.salt
        }
        return created.copy(header = created.header.copy(kdfParameters = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2d, salt = seed, parallelism = 2U,
            memory = 32uL * 1024uL * 1024uL, iterations = 8U, version = 0x13U,
            secretKey = null, associatedData = null)))
            .modifyParentGroup {
                copy(groups = listOf(Group(uuid = UUID.randomUUID(), name = FOLDER,
                    entries = PASSWORDS.mapIndexed { index, password ->
                        val entry = Entry(uuid = UUID.randomUUID(), fields = EntryFields.of(
                            "Title" to EntryValue.Plain(title(index)), "UserName" to EntryValue.Plain(username(index)),
                            "Password" to EntryValue.Encrypted(EncryptedValue.fromString(password)),
                            "URL" to EntryValue.Plain("https://autofill.example.test"),
                            "App Package Name" to EntryValue.Plain(fixturePackage),
                            "Notes" to EntryValue.Plain("Synthetic native note"),
                            "Foreign protected" to EntryValue.Encrypted(EncryptedValue.fromString("opaque-native-value")),
                            "Empty custom" to EntryValue.Plain("")),
                            tags = listOf("native-fixture"), customData = mapOf("foreign-plugin" to CustomDataValue("opaque-state")),
                            binaries = listOf(BinaryReference(attachment.hash, "synthetic.bin")))
                        entry.copy(history = listOf(entry.copy(fields = entry.fields + ("Title" to EntryValue.Plain("Old native title")))))
                    }), Group(uuid = UUID.randomUUID(), name = EMPTY_FOLDER)))
            }.modifyBinaries { it + (attachment.hash to attachment) }
    }

    private fun source(allowLockedDatabases: Boolean = false) = AutofillPasswordSource(context, security,
        SecurityManager.dataReadGeneration.value, { authorized.get() }, database, allowLockedDatabases)

    private fun allNativeEntries(db: KeePassDatabase): List<Entry> {
        fun entries(group: Group): List<Entry> = group.entries + group.groups.flatMap(::entries)
        return entries(db.content.group)
    }

    private fun nativeEntry(entry: PasswordEntry) = allNativeEntries(fixture).single { it.uuid.toString() == entry.keepassEntryUuid }
    private fun bytes() = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun command(name: String, extras: Bundle? = null) {
        context.contentResolver.call(uri, name, uri.toString(), extras)
    }
    private fun counter(name: String): Int = context.contentResolver.query(uri, arrayOf(name), null, null, null)!!.use {
        check(it.moveToFirst())
        it.getInt(0)
    }
    private fun writeFixture(value: KeePassDatabase) {
        context.contentResolver.openOutputStream(uri, "wt")!!.use { value.encode(it) }
        if (encodedFixtureBytes.isEmpty()) encodedFixtureBytes = bytes()
    }
    private suspend fun assertNoRoomMirror() {
        assertTrue("Native reads must not create Room passwords", database.passwordEntryDao()
            .getPasswordEntriesByKeePassDatabaseSync(databaseId).isEmpty())
    }
    private suspend fun insertShadow(entry: PasswordEntry) {
        ownedRoomIds += database.passwordEntryDao().insertPasswordEntry(entry.copy(id = 0,
            password = security.encryptData("stale-shadow-must-not-fill")))
    }
    private fun pickerIntent() = AutofillPickerActivityV2.getIntent(context,
        AutofillPickerActivityV2.Args(applicationId = fixturePackage))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        .putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_MODE, true)

    private fun pickerRegressionIntent(requestId: String) = AutofillPickerActivityV2.getIntent(context,
        AutofillPickerActivityV2.Args(applicationId = fixturePackage, interactionIdentifier = requestId))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        .putExtra(AutofillPickerActivityV2.EXTRA_MANUAL_MODE, true)
        .putExtra("issue179_regression_request", requestId)

    private fun withPickerLifecycleProbe(requestId: String, block: (
        List<Pair<AutofillPickerActivityV2, Long>>, Map<AutofillPickerActivityV2, Stage>
    ) -> Unit) {
        val creations = CopyOnWriteArrayList<Pair<AutofillPickerActivityV2, Long>>()
        val stages = ConcurrentHashMap<AutofillPickerActivityV2, Stage>()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val observer = ActivityLifecycleCallback { activity, stage ->
            if (activity is AutofillPickerActivityV2 && activity.intent.getStringExtra("issue179_regression_request") == requestId) {
                stages[activity] = stage
                if (stage == Stage.CREATED) creations += activity to SystemClock.elapsedRealtime()
            }
        }
        instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(observer) }
        try { block(creations, stages) } finally {
            instrumentation.runOnMainSync {
                lifecycle.removeLifecycleCallback(observer)
                creations.map { it.first }.filterNot { it.isDestroyed || it.isFinishing }.forEach { it.finish() }
            }
        }
    }

    private fun verifyMasterPassword() {
        val field = waitNode { it.isEditable && it.isPassword }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, MASTER_PASSWORD)
        }))
        hideResumedPickerIme()
        tap(waitText(context.getString(R.string.confirm)))
        waitText(title(0))
        assertFalse("Autofill verification must keep main session locked", SessionManager.isUnlocked.value)
    }

    private fun setSearch(value: String) {
        val field = waitNode { it.isEditable && !it.isPassword && it.packageName?.toString() == context.packageName }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    private fun hidePickerIme(scenario: ActivityScenario<AutofillPickerActivityV2>) {
        scenario.onActivity {
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            var ready = false
            scenario.onActivity {
                ready = !it.isFinishing && it.hasWindowFocus() &&
                    ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            if (ready) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Picker must retain focus with its IME hidden")
    }

    private fun awaitFocusedPasswordIme() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            val focused = nodes().any { it.packageName?.toString() == context.packageName &&
                it.isVisibleToUser && it.isEditable && it.isPassword && it.isFocused }
            var imeVisible = false
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AutofillPickerActivityV2>()
                    .firstOrNull { !it.isFinishing && it.hasWindowFocus() }?.let { picker ->
                        imeVisible = ViewCompat.getRootWindowInsets(picker.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
            }
            // The password card requests focus and shows the IME after its first composition.
            // Wait for that actual state before hiding it so a pending show cannot cover Cancel.
            if (focused && imeVisible) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Password field must be focused with its IME visible before cancellation")
    }

    private fun hideResumedPickerIme() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            var ready = false
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AutofillPickerActivityV2>()
                    .firstOrNull { !it.isFinishing && it.hasWindowFocus() }?.let { picker ->
                        val root = picker.window.decorView
                        WindowCompat.getInsetsController(picker.window, root).hide(WindowInsetsCompat.Type.ime())
                        ready = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                    }
            }
            if (ready) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Resumed picker must retain focus with its IME hidden before authentication action")
    }

    private fun enableFramework() {
        systemSettingsChanged = true
        shell("settings --user $user put secure autofill_service ${safeComponent(context.packageName + "/" + MonicaAutofillServiceNg::class.java.name)}")
        val ime = "$fixturePackage/takagi.ru.monica.autofill_ng.fixture.AutofillFixtureInputMethodService"
        shell("ime enable --user $user ${safeComponent(ime)}")
        shell("ime set --user $user ${safeComponent(ime)}")
        assertEquals(ime, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
    }

    private fun openForm(index: Int) {
        val instance = UUID.randomUUID().toString()
        context.startActivity(Intent().apply {
            component = ComponentName(fixturePackage, AutofillFormFixtureActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("scenario", "standard"); putExtra("fixtureInstance", instance)
            putExtra("expectedUsername", username(index)); putExtra("expectedPassword", PASSWORDS[index])
        })
        waitNode { it.contentDescription?.toString() == "fixture-instance-$instance" }
    }
    private fun focusForm() = tap(waitNode { it.contentDescription?.toString() == "fixture-field-0" })
    private fun expectForm(expected: String) = waitNode {
        it.contentDescription?.toString() == "fixture-status" && it.text?.toString()?.contains(expected) == true
    }
    private fun waitText(value: String) = waitNode { it.text?.toString() == value || it.contentDescription?.toString() == value }

    private fun screenshotIfRequested(name: String) {
        if (InstrumentationRegistry.getArguments().getString("issue179Screenshots") != "true") return
        require(name.matches(Regex("[a-z-]+")))
        // Give the existing short Compose transition its final frame; screenshots are optional evidence.
        SystemClock.sleep(300)
        val directories = listOf(java.io.File(context.filesDir, "issue179"),
            java.io.File(requireNotNull(context.getExternalFilesDir(null)), "issue179"))
        directories.forEach { check(it.isDirectory || it.mkdirs()) }
        val bitmap = requireNotNull(ui.takeScreenshot()) { "Requested synthetic picker screenshot unavailable" }
        try {
            val encoded = java.io.ByteArrayOutputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
            directories.forEach { java.io.File(it, "$name.png").writeBytes(encoded) }
        } finally { bitmap.recycle() }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        ui.rootInActiveWindow?.let(queue::add)
        ui.windows.mapNotNull { it.root }.forEach(queue::add)
        val result = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty() && result.size < 700) {
            val node = queue.removeFirst()
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return result
    }
    private fun waitNode(scroll: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var scrollAttempts = 0
        do {
            if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
            val visible = nodes().filter { it.refresh() && it.isVisibleToUser }
            visible.firstOrNull(predicate)?.let { return it }
            if (scroll && scrollAttempts < 8) {
                // The framework dropdown is shorter than four rows on MuMu. Scroll its actual
                // accessible list only while the requested row is outside the visible viewport.
                visible.firstOrNull { it.isScrollable && it.packageName?.toString() != fixturePackage }?.let { target ->
                    scrollAttempts++
                    try {
                        ui.executeAndWaitForEvent({
                            if (target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) successfulScrollActions++
                        }, { event -> event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED }, 1_000).recycle()
                    } catch (_: TimeoutException) {
                        // Some framework surfaces omit the event; the next node snapshot is decisive.
                    }
                }
            }
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        // Log structure and synthetic labels only; never app field values from unrelated packages.
        val visible = nodes().filter { it.packageName?.toString() in setOf(context.packageName, fixturePackage) }
            .filterNot { it.isPassword }.map { "${it.className}|${it.text}|${it.contentDescription}" }
        throw AssertionError("Expected issue179 UI not visible: ${visible.joinToString("\n")}")
    }
    private fun assertAbsent(
        requirePickerWindow: Boolean = false,
        expectedVisibleText: String? = null,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ) {
        fun observe(): Pair<Boolean, List<String>> {
            if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
            val windows = ui.windows
            val boundsByWindow = windows.associate { it.id to Rect().also(it::getBoundsInScreen) }
            val roots = windows.mapNotNull { it.root }.toMutableList()
            ui.rootInActiveWindow?.let { root ->
                if (roots.none { it.windowId == root.windowId }) roots += root
            }
            val visible = mutableListOf<AccessibilityNodeInfo>()
            val appRootWindows = mutableSetOf<Int>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            roots.forEach { root ->
                if (root.refresh()) {
                    if (root.packageName?.toString() == context.packageName && root.childCount > 0) {
                        appRootWindows += root.windowId
                    }
                    queue.add(root)
                }
            }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 700) {
                val node = queue.removeFirst()
                if (!node.refresh()) continue
                val bounds = Rect().also(node::getBoundsInScreen)
                val windowBounds = boundsByWindow[node.windowId]
                // Even a partially visible old row must fail; only absent/off-window nodes are ignored.
                if (node.isVisibleToUser && !bounds.isEmpty && windowBounds != null && Rect.intersects(bounds, windowBounds)) {
                    visible += node
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
            }
            val pickerReady = !requirePickerWindow || (
                windows.any { it.isFocused && it.id in appRootWindows } &&
                    visible.any { it.windowId in appRootWindows && it.packageName?.toString() == context.packageName } &&
                    (expectedVisibleText == null || visible.any {
                        it.windowId in appRootWindows && it.text?.toString() == expectedVisibleText && !it.isEditable
                    })
                )
            val matches = visible.filter(predicate).map { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                val value = node.text?.toString()
                val label = value?.takeIf { !node.isPassword &&
                    (it.startsWith("Issue179 ") || it.startsWith("stale-") || it == buildKeePassPathKey(null, FOLDER) ||
                        it == buildKeePassPathKey(null, EMPTY_FOLDER)) } ?: "<redacted>"
                "label=$label class=${node.className} window=${node.windowId} visible=${node.isVisibleToUser} bounds=$bounds"
            }
            return pickerReady to matches
        }
        val disappearanceDeadline = SystemClock.elapsedRealtime() + 5_000
        var observation = observe()
        while ((!observation.first || observation.second.isNotEmpty()) && SystemClock.elapsedRealtime() < disappearanceDeadline) {
            Thread.sleep(50)
            observation = observe()
        }
        val deadline = SystemClock.elapsedRealtime() + 800
        do {
            assertTrue("A current focused picker window and expected result must be visible before asserting absence", observation.first)
            assertTrue("Unexpected stale/native row is still visible: ${observation.second.joinToString()}", observation.second.isEmpty())
            Thread.sleep(50)
            observation = observe()
        } while (SystemClock.elapsedRealtime() < deadline)
    }
    private fun tapFrameworkSuggestion(title: String) {
        fun matches(node: AccessibilityNodeInfo) =
            node.text?.toString() == title || node.contentDescription?.contains(title) == true
        val initial = waitNode(scroll = true, predicate = ::matches)
        val windowId = initial.windowId
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var lastBounds: Rect? = null
        var stableFrames = 0
        do {
            // A scroll event reports motion beginning, not completion. Observe fresh frames
            // until the actual system row stops moving before invoking its click action.
            val frame = CountDownLatch(1)
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { frame.countDown() } }
            check(frame.await(1, TimeUnit.SECONDS)) { "No UI frame while waiting for suggestion bounds" }
            val node = nodes().firstOrNull { it.windowId == windowId && it.refresh() && it.isVisibleToUser && matches(it) }
            val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
            val windowBounds = ui.windows.firstOrNull { it.id == windowId }?.let { Rect().also(it::getBoundsInScreen) }
            if (node == null || bounds == null || bounds.isEmpty || windowBounds?.contains(bounds) != true) {
                stableFrames = 0
                lastBounds = null
                continue
            }
            stableFrames = if (bounds == lastBounds) stableFrames + 1 else 0
            lastBounds = bounds
            if (stableFrames < 3) continue
            var clickTarget: AccessibilityNodeInfo? = node
            while (clickTarget != null && !clickTarget.isClickable && clickTarget.windowId == windowId) {
                clickTarget = clickTarget.parent
            }
            if (clickTarget != null && clickTarget.windowId == windowId && clickTarget.isVisibleToUser && clickTarget.isEnabled) {
                check(clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "System suggestion rejected its click action" }
            } else tap(node)
            return
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("System suggestion did not settle inside its visible window: $title")
    }

    private fun tapPickerControl(label: String, editable: Boolean? = null) {
        fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            while (current != null && current.windowId == node.windowId) {
                if (current.isClickable) return current
                current = current.parent
            }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var lastBounds: Rect? = null
        var lastWindow = -1
        var stableFrames = 0
        var scrollAttempts = 0
        do {
            val frame = CountDownLatch(1)
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { frame.countDown() } }
            check(frame.await(1, TimeUnit.SECONDS)) { "No UI frame while waiting for picker control" }
            if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
            val snapshot = nodes()
            val target = snapshot.asSequence().filter {
                it.refresh() && it.isVisibleToUser && it.packageName?.toString() == context.packageName &&
                    it.text?.toString() == label &&
                    (editable == null || (it.className?.toString() == "android.widget.EditText") == editable)
            }.mapNotNull(::clickableAncestor).firstOrNull { it.refresh() && it.isVisibleToUser && it.isEnabled }
            val bounds = target?.let { Rect().also(it::getBoundsInScreen) }
            val windowBounds = target?.let { node -> ui.windows.firstOrNull { it.id == node.windowId } }
                ?.let { Rect().also(it::getBoundsInScreen) }
            if (target == null || bounds == null || bounds.isEmpty || windowBounds?.contains(bounds) != true) {
                stableFrames = 0
                lastBounds = null
                if (scrollAttempts < 8) {
                    snapshot.firstOrNull { it.isVisibleToUser && it.isScrollable &&
                        it.packageName?.toString() == context.packageName &&
                        it.actionList.any { action -> action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id }
                    }?.let { scrollable ->
                        scrollAttempts++
                        try {
                            ui.executeAndWaitForEvent({
                                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            }, { event -> event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED }, 1_000).recycle()
                        } catch (_: TimeoutException) { /* The next observed tree determines visibility. */ }
                    }
                }
                continue
            }
            stableFrames = if (lastWindow == target.windowId && lastBounds == bounds) stableFrames + 1 else 0
            lastBounds = bounds
            lastWindow = target.windowId
            if (stableFrames < 3) continue
            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Picker control rejected its click: $label" }
            return
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Picker control did not become stable and fully visible: $label (editable=$editable)")
    }

    private fun tap(node: AccessibilityNodeInfo) {
        check(node.refresh() && node.isVisibleToUser && node.isEnabled) { "Tap target is no longer visible/enabled" }
        val rect = Rect().also(node::getBoundsInScreen)
        check(!rect.isEmpty) { "Tap target has empty bounds" }
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(60)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(ui.injectInputEvent(event, action == MotionEvent.ACTION_UP)) } finally { event.recycle() }
        }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .use { it.bufferedReader().readText() }
    private fun safeComponent(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_./:$]+")))
        return value
    }
    private fun restoreSecureSetting(name: String, value: String?) {
        require(name in setOf("autofill_service", Settings.Secure.DEFAULT_INPUT_METHOD))
        // Preserve null and empty settings exactly; they are not service component names.
        ui.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(context.contentResolver, name, value))
            assertEquals("Restore original $name", value, Settings.Secure.getString(context.contentResolver, name))
        } finally { ui.dropShellPermissionIdentity() }
    }

    companion object {
        private const val MASTER_PASSWORD = "Monica-Issue179-Test-2026!"
        private const val KDBX_PASSWORD = "Synthetic issue179 KDBX password"
        private const val DATABASE_NAME = "Issue179 native vault"
        private const val FOLDER = "Issue179 populated folder"
        private const val EMPTY_FOLDER = "Issue179 empty folder"
        private val PASSWORDS = listOf("C2|literal-native-179", "MDK|literal-native-179", "V2|literal-native-179",
            "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0NTY3ODkwQUJDREVGR0g=")
        private val ATTACHMENT = "Synthetic issue179 attachment bytes".toByteArray()
        private fun title(index: Int) = "Issue179 native login $index"
        private fun username(index: Int) = "C2|native-user-$index"
    }
}

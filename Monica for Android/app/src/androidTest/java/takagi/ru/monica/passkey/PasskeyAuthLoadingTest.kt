package takagi.ru.monica.passkey

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Rect
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.data.PasskeyDao
import takagi.ru.monica.data.PasskeyEntry
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecureStartupResult
import takagi.ru.monica.security.SecureStorageStartup
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.security.SessionManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual provider Activity, actual Room rows and public framework request transport.
 * The only test substitution is a latch around one DAO read; no authentication bypass
 * or test hook exists in production. The responsiveness method also runs on the old APK.
 */
@SdkSuppress(minSdkVersion = 34)
class PasskeyAuthLoadingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun await(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue(message, condition())
    }

    private fun selected(activity: PasskeyAuthActivity): PasskeyEntry? {
        var value: PasskeyEntry? = null
        instrumentation.runOnMainSync {
            value = PasskeyAuthActivity::class.java.getDeclaredField("passkey")
                .apply { isAccessible = true }.get(activity) as PasskeyEntry?
        }
        return value
    }

    private fun containsText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        // Compose may merge adjacent text nodes. The synthetic account includes a UUID,
        // so containment still identifies this exact account rather than a generic label.
        if (node.packageName?.toString() == context.packageName && node.isVisibleToUser &&
            node.text?.toString()?.contains(text) == true) return true
        return (0 until node.childCount).any { containsText(node.getChild(it), text) }
    }

    private fun dumpUiFailure(target: PasskeyAuthActivity) {
        instrumentation.runOnMainSync {
            val decor = target.window.decorView
            println("PASSKEY_UI target=${target.componentName.flattenToShortString()} " +
                "task=${target.taskId} lifecycle=${target.lifecycle.currentState} " +
                "finishing=${target.isFinishing} destroyed=${target.isDestroyed} " +
                "focus=${decor.hasWindowFocus()} size=${decor.width}x${decor.height} " +
                "orientation=${target.resources.configuration.orientation}")
        }
        fun dump(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null) { println("PASSKEY_UI root=null"); return }
            val bounds = Rect().also(node::getBoundsInScreen)
            val samePackage = node.packageName?.toString() == context.packageName
            // Only synthetic target-app text is captured; other applications are identified
            // by package/class/bounds without copying their visible content into this log.
            val text = if (samePackage) node.text?.toString().orEmpty().replace("\n", " | ") else "<non-target>"
            println("PASSKEY_UI depth=$depth package=${node.packageName} class=${node.className} " +
                "visible=${node.isVisibleToUser} bounds=$bounds text=$text")
            for (index in 0 until node.childCount) dump(node.getChild(index), depth + 1)
        }
        dump(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun resumeExistingResultCaller(taskId: Int) {
        require(taskId >= 0)
        val automation = instrumentation.uiAutomation
        automation.adoptShellPermissionIdentity(
            "android.permission.REORDER_TASKS",
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
        )
        try {
            // ActivityScenario starts the target for result in BootstrapActivity's task.
            // Home backgrounds that caller too: resume that existing task, never relaunch
            // the Passkey Activity or manufacture the result through a test broadcast.
            context.getSystemService(ActivityManager::class.java).moveTaskToFront(taskId, 0)
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun activity(scenario: ActivityScenario<PasskeyAuthActivity>): PasskeyAuthActivity {
        var value: PasskeyAuthActivity? = null
        scenario.onActivity { value = it }
        return requireNotNull(value)
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val used = AtomicBoolean()
        val recordReads = AtomicInteger()
        val fallbackReads = AtomicInteger()
        @Volatile var fail = false
        @Volatile var blockedMain = false
    }

    private inner class Fixture(val dao: PasskeyDao, val row: PasskeyEntry, val decoy: PasskeyEntry) {
        fun requestJson(challenge: String = "c3ludGhldGljLWxvb2t1cC0zMjg"): String = JSONObject().apply {
            put("rpId", row.rpId)
            put("challenge", challenge)
            put("allowCredentials", JSONArray().put(JSONObject().apply {
                put("type", "public-key")
                put("id", requireNotNull(PasskeyCredentialIdCodec.toWebAuthnId(row.credentialId)))
            }))
        }.toString()

        fun intent(recordId: Long = row.id, credentialId: String = row.credentialId,
                   platformJson: String = requestJson()): Intent {
            val option = GetPublicKeyCredentialOption(platformJson)
            @Suppress("DEPRECATION")
            val signingInfo = requireNotNull(context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo)
            val platform = android.service.credentials.GetCredentialRequest(
                android.service.credentials.CallingAppInfo(context.packageName, signingInfo),
                listOf(android.credentials.CredentialOption.Builder(option.type,
                    option.requestData, option.candidateQueryData).build()),
            )
            return Intent(context, PasskeyAuthActivity::class.java)
                .putExtra("android.service.credentials.extra.GET_CREDENTIAL_REQUEST", platform)
                .putExtra(MonicaCredentialProviderService.EXTRA_REQUEST_JSON, requestJson())
                .putExtra(MonicaCredentialProviderService.EXTRA_CREDENTIAL_ID, credentialId)
                .putExtra(MonicaCredentialProviderService.EXTRA_RECORD_ID, recordId)
        }
    }

    private fun withLookup(
        intent: Fixture.() -> Intent = { intent() },
        block: Fixture.(Gate, java.util.concurrent.Future<ActivityScenario<PasskeyAuthActivity>>) -> Unit,
    ) {
        check(context.packageName.contains("perf328")) { "An isolated performance fixture package is required" }
        runBlocking { assertTrue(SecureStorageStartup.prepareForEntry(context) is SecureStartupResult.Ready) }
        SessionManager.markLocked()
        val database = PasswordDatabase.getDatabase(context)
        val real = database.passkeyDao()
        val uuid = UUID.randomUUID().toString()
        val original = PasskeyEntry(credentialId = uuid, rpId = "lookup328.example.invalid",
            rpName = "Synthetic lookup 328", userId = "c3ludGhldGlj", userName = "lookup-$uuid",
            userDisplayName = "Selected synthetic $uuid", publicKey = "synthetic-public-metadata",
            privateKeyAlias = "missing-synthetic-key-$uuid", useCount = 7, signCount = 0,
            lastUsedAt = 1_000L)
        val row = original.copy(id = runBlocking { real.insertCopy(original) })
        val other = original.copy(userName = "decoy-$uuid", userDisplayName = "Unselected synthetic $uuid", lastUsedAt = 2_000L)
        val decoy = other.copy(id = runBlocking { real.insertCopy(other) })
        val fixture = Fixture(real, row, decoy)
        val gate = Gate()
        val field = database.javaClass.getDeclaredField("_passkeyDao").apply { isAccessible = true }
        val proxy = Proxy.newProxyInstance(PasskeyDao::class.java.classLoader, arrayOf(PasskeyDao::class.java)) { _, method, args ->
            if (method.name == "getAllPasskeysSync") gate.fallbackReads.incrementAndGet()
            if (method.name == "getPasskeyByRecordId") gate.recordReads.incrementAndGet()
            // Each request's first read is held, including the compatibility lookup without recordId.
            val isRead = method.name in setOf("getPasskeyByRecordId", "getPasskeyById", "getAllPasskeysSync")
            if (isRead && gate.used.compareAndSet(false, true)) {
                gate.blockedMain = Looper.myLooper() == Looper.getMainLooper()
                gate.entered.countDown()
                try {
                    check(gate.release.await(25, TimeUnit.SECONDS)) { "Test did not release selected lookup" }
                    if (gate.fail) throw IllegalStateException("Synthetic DAO read failure")
                } finally { gate.finished.countDown() }
            }
            try { method.invoke(real, *(args ?: emptyArray())) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        } as PasskeyDao
        field.set(database, proxy)
        val executor = Executors.newSingleThreadExecutor()
        val launched = executor.submit<ActivityScenario<PasskeyAuthActivity>> {
            ActivityScenario.launchActivityForResult<PasskeyAuthActivity>(intent.invoke(fixture))
        }
        try {
            assertTrue("Actual Activity did not enter selected Room lookup", gate.entered.await(10, TimeUnit.SECONDS))
            fixture.block(gate, launched)
        } finally {
            gate.release.countDown()
            try { launched.get(20, TimeUnit.SECONDS).close() } finally {
                field.set(database, real)
                executor.shutdownNow()
                runBlocking { real.deleteByRecordIds(listOf(row.id, decoy.id)) }
            }
        }
    }

    private fun assertUnchanged(fixture: Fixture) {
        runBlocking {
            assertEquals(fixture.row, fixture.dao.getPasskeyByRecordId(fixture.row.id))
            assertEquals(fixture.decoy, fixture.dao.getPasskeyByRecordId(fixture.decoy.id))
        }
        assertFalse(SessionManager.isUnlocked.value)
        assertFalse(SecurityManager.hasRuntimeUnlockCache())
    }

    private fun assertCancelled(scenario: ActivityScenario<PasskeyAuthActivity>, target: PasskeyAuthActivity) {
        await("Cancelled provider Activity must finish") { target.isFinishing || target.isDestroyed }
        val result = scenario.result
        assertEquals(Activity.RESULT_OK, result.resultCode)
        assertTrue(PendingIntentHandler.retrieveGetCredentialException(result.resultData) is GetCredentialCancellationException)
        assertNull("Late row must not be published", selected(target))
    }

    @Test fun actualSelectedLookupKeepsMainResponsive() = withLookup { gate, launched ->
        val heartbeat = CountDownLatch(1)
        val started = SystemClock.elapsedRealtime()
        mainHandler.post { heartbeat.countDown() }
        val responsive = heartbeat.await(1_000, TimeUnit.MILLISECONDS)
        println("PASSKEY_LOOKUP main_heartbeat_ms=${SystemClock.elapsedRealtime() - started} query_on_main=${gate.blockedMain}")
        assertTrue("Selected Room lookup blocked the actual Activity main looper", responsive)
        assertFalse("Even synchronous DAO setup must run off main", gate.blockedMain)
        val target = activity(launched.get(10, TimeUnit.SECONDS))
        assertNull(selected(target))
        gate.release.countDown()
        await("Selected row must load after release") { selected(target) == row }
        try {
            // In the fixed-height landscape layout, the secondary username may be
            // clipped. Assert the actual visible account title instead: it contains the
            // unique fixture UUID and differs from the unselected duplicate's title.
            await("Exact selected account title must be visible in the real provider UI") {
                containsText(instrumentation.uiAutomation.rootInActiveWindow, row.userDisplayName)
            }
        } catch (failure: AssertionError) {
            dumpUiFailure(target)
            throw failure
        }
        assertUnchanged(this)
    }

    @Test fun backDuringLookupRejectsLateRowAndReturnsCancellation() = withLookup { gate, launched ->
        val scenario = launched.get(10, TimeUnit.SECONDS)
        val target = activity(scenario)
        instrumentation.runOnMainSync { target.onBackPressedDispatcher.onBackPressed() }
        assertCancelled(scenario, target)
        gate.release.countDown()
        assertTrue(gate.finished.await(2, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertNull(selected(target))
        assertUnchanged(this)
    }

    @Test fun stopDuringLookupRejectsLateRowAndReturnsCancellation() = withLookup { gate, launched ->
        val scenario = launched.get(10, TimeUnit.SECONDS)
        val target = activity(scenario)
        val resultTaskId = target.taskId
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        await("Home must finish the pending Passkey request before returning to its caller") {
            target.isFinishing || target.isDestroyed
        }
        assertNull("Background request must not admit a row", selected(target))
        resumeExistingResultCaller(resultTaskId)
        assertCancelled(scenario, target)
        gate.release.countDown()
        assertTrue(gate.finished.await(2, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertNull(selected(target))
        assertUnchanged(this)
    }

    @Test fun lockingDuringLookupRejectsLateRowAndReturnsCancellation() = withLookup { gate, launched ->
        val scenario = launched.get(10, TimeUnit.SECONDS)
        val target = activity(scenario)
        instrumentation.runOnMainSync { SessionManager.markLocked() }
        gate.release.countDown()
        assertCancelled(scenario, target)
        assertUnchanged(this)
    }

    @Test fun recreationStartsFreshLookupWithoutPublishingOldResult() = withLookup { gate, launched ->
        val scenario = launched.get(10, TimeUnit.SECONDS)
        val old = activity(scenario)
        scenario.recreate()
        val fresh = activity(scenario)
        assertNotSame(old, fresh)
        assertTrue(old.isDestroyed)
        await("Recreated Activity must independently load selected row") { selected(fresh) == row }
        gate.release.countDown()
        assertTrue(gate.finished.await(2, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertNull(selected(old))
        assertEquals(row, selected(fresh))
        assertTrue(gate.recordReads.get() >= 2)
        assertUnchanged(this)
    }

    @Test fun normalizedCredentialIdStillSelectsExactRecordInsteadOfNewerDuplicate() =
        withLookup(intent = { intent(credentialId = requireNotNull(PasskeyCredentialIdCodec.toWebAuthnId(row.credentialId))) }) { gate, launched ->
            val target = activity(launched.get(10, TimeUnit.SECONDS))
            gate.release.countDown()
            await("Normalized ID must retain the exact selected record") { selected(target) == row }
            assertEquals(1, gate.fallbackReads.get())
            assertUnchanged(this)
        }

    @Test fun compatibilityLookupWithoutRecordIdKeepsExistingSelectionOrder() =
        withLookup(intent = { intent(recordId = 0L) }) { gate, launched ->
            val target = activity(launched.get(10, TimeUnit.SECONDS))
            gate.release.countDown()
            await("Legacy credential-only lookup retains the existing newer-row preference") { selected(target) == decoy }
            assertUnchanged(this)
        }

    @Test fun deletionDuringLookupCannotFallBackToDuplicateCredential() = withLookup { gate, launched ->
        val scenario = launched.get(10, TimeUnit.SECONDS)
        val target = activity(scenario)
        runBlocking { dao.deleteByRecordId(row.id) }
        gate.release.countDown()
        await("Deleted selected record must reject the request") { target.isFinishing || target.isDestroyed }
        assertTrue(PendingIntentHandler.retrieveGetCredentialException(scenario.result.resultData) is GetCredentialUnknownException)
        assertNull(selected(target))
        runBlocking { assertEquals(decoy, dao.getPasskeyByRecordId(decoy.id)) }
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun differentPlatformChallengeCannotAdmitAuthentication() =
        withLookup(intent = { intent(platformJson = requestJson("ZGlmZmVyZW50LWNoYWxsZW5nZQ")) }) { gate, launched ->
            val scenario = launched.get(10, TimeUnit.SECONDS)
            val target = activity(scenario)
            gate.release.countDown()
            await("Mismatched actual platform request must finish") { target.isFinishing || target.isDestroyed }
            assertTrue(PendingIntentHandler.retrieveGetCredentialException(scenario.result.resultData) is GetCredentialUnknownException)
            assertUnchanged(this)
        }

    @Test fun failedDaoReadReturnsControlledErrorAndPreservesRows() = withLookup { gate, launched ->
        val scenario = launched.get(10, TimeUnit.SECONDS)
        val target = activity(scenario)
        gate.fail = true
        gate.release.countDown()
        await("DAO failure must return a controlled credential error") { target.isFinishing || target.isDestroyed }
        assertTrue(PendingIntentHandler.retrieveGetCredentialException(scenario.result.resultData) is GetCredentialUnknownException)
        assertNull(selected(target))
        assertUnchanged(this)
    }

    @Test fun lookupDeadlineRejectsUncooperativeLateResult() = withLookup { gate, launched ->
        val scenario = launched.get(10, TimeUnit.SECONDS)
        val target = activity(scenario)
        // The DAO intentionally ignores coroutine cancellation. We do not promise a hard
        // interrupt: after it releases beyond the 15-second budget, its row is rejected.
        SystemClock.sleep(16_000)
        gate.release.countDown()
        await("Lookup exceeding its deadline must finish without admitting a row") { target.isFinishing || target.isDestroyed }
        assertTrue(PendingIntentHandler.retrieveGetCredentialException(scenario.result.resultData) is GetCredentialUnknownException)
        assertNull(selected(target))
        assertUnchanged(this)
    }

    @Test fun deletionAfterDisplayIsStillRejectedBySigningTimeReread() = withLookup { gate, launched ->
        val scenario = launched.get(10, TimeUnit.SECONDS)
        val target = activity(scenario)
        gate.release.countDown()
        await("Selected row must load first") { selected(target) == row }
        runBlocking { dao.deleteByRecordId(row.id) }
        // Negative-only direct invocation: it must reject before any key access or write.
        // This does not claim biometric/password authentication or successful signing.
        instrumentation.runOnMainSync {
            PasskeyAuthActivity::class.java.getDeclaredMethod("authenticateWithPasskey", String::class.java,
                PasskeyEntry::class.java).apply { isAccessible = true }.invoke(target, requestJson(), row)
        }
        await("Signing-time re-read must reject the deleted selected record") { target.isFinishing || target.isDestroyed }
        assertTrue(PendingIntentHandler.retrieveGetCredentialException(scenario.result.resultData) is GetCredentialUnknownException)
        runBlocking { assertEquals(decoy, dao.getPasskeyByRecordId(decoy.id)) }
        assertTrue(gate.recordReads.get() >= 2)
    }
}

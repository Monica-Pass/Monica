package takagi.ru.monica.update

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.utils.UpdateCheckResult

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateControllerTest {
    private val release = UpdateCheckResult("1.0.327", "V1.0.328", "Update", "https://example.com/release",
        "app.apk", "https://example.com/app.apk", "# New version", true, releaseIdentity = "standard:V1.0.328")

    @Test fun disabledAutomaticCheckAndSkippedVersionStillAllowManualCheck() = runTest {
        var requests = 0
        val controller = controller(backgroundScope,
            UpdatePreferencesState(autoCheckEnabled = false, skippedReleaseIdentity = release.releaseIdentity),
            fetch = { requests++; Result.success(release) })
        controller.setEnvironment(true, true, true)
        runCurrent()
        assertEquals(0, requests)
        controller.checkManually()
        runCurrent()
        assertEquals(1, requests)
        assertTrue(controller.state.value.dialogVisible)
    }

    @Test fun foregroundChangesCannotDuplicateInFlightOrDailyRequests() = runTest {
        val response = CompletableDeferred<UpdateCheckResult>()
        var requests = 0
        val controller = controller(backgroundScope, fetch = { requests++; Result.success(response.await()) })
        controller.setEnvironment(true, true, true)
        runCurrent()
        repeat(5) { controller.setEnvironment(true, true, true); controller.checkManually() }
        runCurrent()
        assertEquals(1, requests)
        response.complete(release)
        runCurrent()
        assertTrue(controller.state.value.dialogVisible)
        controller.dismiss()
        controller.setEnvironment(true, true, true)
        runCurrent()
        assertEquals(1, requests)
    }

    @Test fun lockRejectsNonCooperativeLateCheckAndManualFailureIsVisible() = runTest {
        val response = CompletableDeferred<UpdateCheckResult>()
        var fail = false
        val controller = controller(backgroundScope, fetch = {
            if (fail) Result.failure(IllegalStateException("Synthetic failure"))
            else withContext(NonCancellable) { Result.success(response.await()) }
        })
        controller.setEnvironment(true, true, true)
        runCurrent()
        controller.setEnvironment(false, true, false)
        response.complete(release)
        runCurrent()
        assertFalse(controller.state.value.dialogVisible)
        assertNull(controller.state.value.result)
        fail = true
        controller.setEnvironment(true, true, false)
        controller.checkManually()
        runCurrent()
        assertTrue(controller.state.value.dialogVisible)
        assertEquals("Synthetic failure", controller.state.value.error)
    }

    @Test fun disablingAutomaticChecksRevokesLateAutomaticResult() = runTest {
        val response = CompletableDeferred<UpdateCheckResult>()
        val controller = controller(backgroundScope, fetch = {
            withContext(NonCancellable) { Result.success(response.await()) }
        })
        controller.setEnvironment(true, true, true)
        runCurrent()
        controller.setAutoCheckEnabled(false)
        response.complete(release)
        runCurrent()
        assertFalse(controller.state.value.dialogVisible)
        assertFalse(controller.state.value.preferences.autoCheckEnabled)
    }

    @Test fun skipPersistsExactIdentityAndDismissesImmediately() = runTest {
        var saved: String? = null
        val controller = controller(backgroundScope, skipped = { saved = it })
        controller.setEnvironment(true, true, true)
        runCurrent()
        controller.skipVersion()
        assertFalse(controller.state.value.dialogVisible)
        runCurrent()
        assertEquals(release.releaseIdentity, saved)
    }

    @Test fun backgroundDuringDownloadNeverLaunchesInstallerAndDeletesReturnedFile() = runTest {
        val complete = CompletableDeferred<Unit>()
        val temporary = File.createTempFile("update-controller-test", ".apk")
        temporary.writeText("synthetic")
        var installs = 0
        val controller = AppUpdateController(backgroundScope, { UpdatePreferencesState() }, {}, {}, {},
            { Result.success(release) }, { _, _ -> withContext(NonCancellable) { complete.await(); Result.success(temporary) } },
            { installs++ }, { 100_000L })
        controller.setEnvironment(true, true, true)
        runCurrent()
        controller.startDownload()
        runCurrent()
        controller.setEnvironment(true, false, false)
        complete.complete(Unit)
        runCurrent()
        assertEquals(0, installs)
        assertFalse(controller.state.value.dialogVisible)
        assertFalse(temporary.exists())
    }

    @Test fun anOlderFailedDisableWriteCannotUndoANewerSuccessfulEnable() = runTest {
        val finishOldWrite = CompletableDeferred<Unit>()
        var persistedEnabled = true
        val writes = mutableListOf<Boolean>()
        val controller = AppUpdateController(
            backgroundScope, { UpdatePreferencesState() },
            { enabled ->
                writes += enabled
                if (!enabled) {
                    finishOldWrite.await()
                    throw java.io.IOException("Synthetic old preference failure")
                }
                persistedEnabled = enabled
            }, {}, {}, { Result.success(release) },
            { _, _ -> Result.failure(IllegalStateException("No download expected")) }, {}, { 100_000L },
        )
        controller.setEnvironment(true, true, false)
        runCurrent()
        controller.setAutoCheckEnabled(false)
        runCurrent()
        assertFalse(controller.state.value.preferences.autoCheckEnabled)
        controller.setAutoCheckEnabled(true)
        runCurrent()
        assertTrue(controller.state.value.preferences.autoCheckEnabled)
        finishOldWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, true), writes)
        assertTrue(persistedEnabled)
        assertTrue("An older failed write must not overwrite the latest visible preference",
            controller.state.value.preferences.autoCheckEnabled)
    }

    @Test fun failedSkipWriteShowsTheOriginalReleaseAndCanBeRetried() = runTest {
        var attempts = 0
        var persistedIdentity: String? = null
        val controller = controller(backgroundScope, skipped = { identity ->
            attempts++
            if (attempts == 1) throw java.io.IOException("Synthetic skip storage failure")
            persistedIdentity = identity
        })
        controller.setEnvironment(true, true, true)
        runCurrent()
        assertTrue(controller.state.value.dialogVisible)
        controller.skipVersion()
        runCurrent()
        assertEquals(1, attempts)
        assertNull(persistedIdentity)
        assertTrue("A failed skip must remain visible and retryable", controller.state.value.dialogVisible)
        assertEquals(release, controller.state.value.result)
        assertFalse(controller.state.value.error.isNullOrBlank())
        assertNull("An unpersisted skip must not suppress a later automatic check",
            controller.state.value.preferences.skippedReleaseIdentity)
        controller.skipVersion()
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(release.releaseIdentity, persistedIdentity)
        assertFalse(controller.state.value.dialogVisible)
        assertEquals(release.releaseIdentity, controller.state.value.preferences.skippedReleaseIdentity)
    }

    @Test fun leavingMainPageRevokesLateAutomaticResponseWhileManualCheckCanContinue() = runTest {
        val automaticResponse = CompletableDeferred<UpdateCheckResult>()
        val manualResponse = CompletableDeferred<UpdateCheckResult>()
        var requests = 0
        val controller = controller(backgroundScope, fetch = {
            requests++
            if (requests == 1) withContext(NonCancellable) { Result.success(automaticResponse.await()) }
            else Result.success(manualResponse.await())
        })
        controller.setEnvironment(true, true, true)
        runCurrent()
        assertEquals(1, requests)
        assertTrue(controller.state.value.checking)
        controller.setEnvironment(true, true, false)
        assertFalse("Automatic work is canceled when its page becomes ineligible", controller.state.value.checking)
        controller.checkManually()
        runCurrent()
        assertEquals(2, requests)
        assertTrue(controller.state.value.checking)
        automaticResponse.complete(release)
        runCurrent()
        assertTrue("The retired automatic job cannot clear the newer manual job", controller.state.value.checking)
        assertFalse(controller.state.value.dialogVisible)
        assertNull(controller.state.value.result)
        val newer = release.copy(latestVersion = "V1.0.329", releaseIdentity = "standard:V1.0.329")
        manualResponse.complete(newer)
        runCurrent()
        assertTrue(controller.state.value.dialogVisible)
        assertEquals(newer, controller.state.value.result)
        assertFalse(controller.state.value.checking)
    }

    private fun controller(
        scope: CoroutineScope,
        preferences: UpdatePreferencesState = UpdatePreferencesState(),
        fetch: suspend () -> Result<UpdateCheckResult> = { Result.success(release) },
        skipped: suspend (String) -> Unit = {},
    ) = AppUpdateController(scope, { preferences }, {}, {}, skipped, fetch,
        { _, _ -> Result.failure(IllegalStateException("No download expected")) }, {}, { 100_000L })
}

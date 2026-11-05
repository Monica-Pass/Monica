package takagi.ru.monica.bitwarden.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.bitwarden.service.NewDeviceOtpResendException
import takagi.ru.monica.bitwarden.service.NewDeviceOtpResendFailure

class NewDeviceOtpResendControllerTest {
    private class Fixture(parent: CoroutineScope) {
        val scope = CoroutineScope(parent.coroutineContext + Job())
        var now = 100_000L
        private val ticks = Channel<Unit>(Channel.UNLIMITED)
        val controller = NewDeviceOtpResendController(scope, { now }, { ticks.receive() })
        suspend fun advance(millis: Long) { now += millis; ticks.send(Unit); yield() }
        fun close() { scope.cancel() }
    }

    @Test fun bindingDoesNotSendAndConcurrentOrRapidClicksAreIgnored() = runBlocking {
        val fixture = Fixture(this)
        try {
            val session = Any()
            val response = CompletableDeferred<Result<Unit>>()
            var calls = 0
            fixture.controller.bind(session)
            yield()
            assertEquals(0, calls)
            assertTrue(fixture.controller.request(session) { calls++; response.await() })
            assertFalse(fixture.controller.request(session) { calls++; Result.success(Unit) })
            yield()
            assertEquals(1, calls)
            response.complete(Result.success(Unit))
            yield()
            assertEquals(NewDeviceOtpResendOutcome.SENT, fixture.controller.state.value.outcome)
            assertFalse(fixture.controller.request(session) { calls++; Result.success(Unit) })
            fixture.advance(29_001)
            assertEquals(1, fixture.controller.state.value.remainingSeconds)
            fixture.advance(999)
            assertTrue(fixture.controller.request(session) { calls++; Result.success(Unit) })
            yield()
            assertEquals(2, calls)
        } finally { fixture.close() }
    }

    @Test fun rateLimitExtendsCooldownAndSurvivesChallengeReplacement() = runBlocking {
        val fixture = Fixture(this)
        try {
            val first = Any()
            fixture.controller.bind(first)
            fixture.controller.request(first) { Result.failure(NewDeviceOtpResendException(NewDeviceOtpResendFailure.RATE_LIMITED, 90)) }
            yield()
            assertEquals(NewDeviceOtpResendOutcome.RATE_LIMITED, fixture.controller.state.value.outcome)
            assertEquals(90, fixture.controller.state.value.remainingSeconds)
            fixture.advance(30_000)
            fixture.controller.bind(null)
            val replacement = Any()
            fixture.controller.bind(replacement)
            assertEquals(60, fixture.controller.state.value.remainingSeconds)
            assertFalse(fixture.controller.request(replacement) { Result.success(Unit) })
            fixture.advance(60_000)
            assertTrue(fixture.controller.request(replacement) { Result.success(Unit) })
        } finally { fixture.close() }
    }

    @Test fun cancelledRequestCannotPublishLateSuccessIntoReplacementSession() = runBlocking {
        val fixture = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            val first = Any()
            fixture.controller.bind(first)
            fixture.controller.request(first) { withContext(NonCancellable) { release.await(); Result.success(Unit) } }
            yield()
            fixture.controller.bind(Any())
            release.complete(Unit)
            yield(); yield()
            assertEquals(NewDeviceOtpResendOutcome.NONE, fixture.controller.state.value.outcome)
            assertFalse(fixture.controller.state.value.sending)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun backgroundOrLockInvalidationRevokesAnUncooperativeFailure() = runBlocking {
        val fixture = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            val session = Any()
            fixture.controller.bind(session)
            fixture.controller.request(session) { withContext(NonCancellable) {
                release.await(); Result.failure(NewDeviceOtpResendException(NewDeviceOtpResendFailure.FAILED))
            } }
            yield()
            fixture.controller.bind(null)
            release.complete(Unit)
            yield(); yield()
            assertEquals(NewDeviceOtpResendState(), fixture.controller.state.value)
            assertFalse(fixture.controller.request(session) { Result.success(Unit) })
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun sessionRevokedBeforeDispatchSendsNothing() = runBlocking {
        val fixture = Fixture(this)
        try {
            val session = Any()
            var valid = true
            var calls = 0
            fixture.controller.bind(session)
            fixture.controller.request(session, { valid }) { calls++; Result.success(Unit) }
            valid = false
            yield()
            assertEquals(0, calls)
            assertEquals(NewDeviceOtpResendState(), fixture.controller.state.value)
        } finally { fixture.close() }
    }

    @Test fun sessionRevokedDuringNetworkCannotPublishSuccess() = runBlocking {
        val fixture = Fixture(this)
        val response = CompletableDeferred<Result<Unit>>()
        try {
            val session = Any()
            var valid = true
            fixture.controller.bind(session)
            fixture.controller.request(session, { valid }) { response.await() }
            yield()
            valid = false
            response.complete(Result.success(Unit))
            yield()
            assertEquals(NewDeviceOtpResendState(), fixture.controller.state.value)
        } finally { response.complete(Result.success(Unit)); fixture.close() }
    }

    @Test fun unsupportedServerDisablesResendAfterCooldown() = runBlocking {
        val fixture = Fixture(this)
        try {
            val session = Any()
            fixture.controller.bind(session)
            fixture.controller.request(session) { Result.failure(NewDeviceOtpResendException(NewDeviceOtpResendFailure.UNAVAILABLE)) }
            yield()
            fixture.advance(30_000)
            assertEquals(NewDeviceOtpResendOutcome.UNAVAILABLE, fixture.controller.state.value.outcome)
            assertFalse(fixture.controller.state.value.canResend)
            assertFalse(fixture.controller.request(session) { Result.success(Unit) })
        } finally { fixture.close() }
    }
}

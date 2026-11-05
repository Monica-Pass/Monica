package takagi.ru.monica.security

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedSecureDataReadInstrumentedTest {
    private suspend fun preparedManager(): SecurityManager {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(SecureStorageStartup.prepareWithRetry(context) is SecureStartupResult.Ready)
        return SecurityManager(context)
    }

    @Test fun timedOutReadRetainsNativeSlotUntilWorkerReallyExits() = runBlocking {
        val manager = preparedManager()
        val generation = SecurityManager.dataReadGeneration.value
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val first = async {
                BoundedSecureDataRead.read(manager, generation, timeoutMillis = 250) {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    "late-result-must-not-escape"
                }
            }
            withTimeout(5_000) { while (entered.count != 0L) delay(10) }
            assertNull(first.await())
            assertEquals(1L, release.count)
            repeat(3) {
                assertNull(BoundedSecureDataRead.read(manager, generation, timeoutMillis = 50) {
                    calls.incrementAndGet()
                    "must-not-start-while-old-native-is-running"
                })
            }
            assertEquals(1, calls.get())
            release.countDown()
            assertEquals("fresh", BoundedSecureDataRead.read(manager, generation) { calls.incrementAndGet(); "fresh" })
            assertEquals(2, calls.get())
        } finally { release.countDown() }
    }

    @Test fun cancelledWaitingRequestNeverRunsAfterPriorNativeCallReturns() = runBlocking {
        val manager = preparedManager()
        val generation = SecurityManager.dataReadGeneration.value
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelledCalls = AtomicInteger()
        try {
            val first = async {
                BoundedSecureDataRead.read(manager, generation) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    "first"
                }
            }
            withTimeout(5_000) { while (entered.count != 0L) delay(10) }
            val cancelled = async {
                BoundedSecureDataRead.read(manager, generation) { cancelledCalls.incrementAndGet(); "cancelled" }
            }
            delay(50)
            cancelled.cancel()
            cancelled.join()
            release.countDown()
            assertEquals("first", first.await())
            assertEquals("last", BoundedSecureDataRead.read(manager, generation) { "last" })
            assertEquals(0, cancelledCalls.get())
        } finally { release.countDown() }
    }
}

package takagi.ru.monica.bitwarden.discovery

import android.annotation.TargetApi
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Platform default-network TXT only. Construction never performs DNS or creates an HTTP client. */
internal class AndroidBitwardenDnsTxtResolver(
    private val executor: Executor = CallbackExecutor,
) : BitwardenDnsTxtResolver {
    override suspend fun queryTxt(name: String): BitwardenTxtLookup {
        val canonical = BitwardenDnsTxtParser.canonicalQueryName(name)
            ?: return BitwardenTxtLookup.Failure(BitwardenTxtFailure.INVALID_QUERY)
        if (Build.VERSION.SDK_INT < 29) return BitwardenTxtLookup.Unsupported
        return querySupported(canonical)
    }

    @TargetApi(29)
    private suspend fun querySupported(name: String): BitwardenTxtLookup = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        val completed = AtomicBoolean(false)
        continuation.invokeOnCancellation {
            completed.set(true)
            cancellation.cancel()
        }
        fun finish(result: BitwardenTxtLookup) {
            if (completed.compareAndSet(false, true)) continuation.resume(result)
        }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        try {
            DnsResolver.getInstance().rawQuery(
                null, name, 1, BitwardenDnsTxtParser.TYPE_TXT, DnsResolver.FLAG_EMPTY,
                executor, cancellation, object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (completed.get()) return
                        if (answer.size < 4 || (answer[3].toInt() and 15) != rcode) {
                            finish(BitwardenTxtLookup.Failure(BitwardenTxtFailure.MALFORMED_RESPONSE))
                        } else finish(BitwardenDnsTxtParser.parse(name, answer))
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        finish(BitwardenTxtLookup.Failure(BitwardenTxtFailure.TRANSPORT))
                    }
                },
            )
        } catch (_: RuntimeException) {
            cancellation.cancel()
            finish(BitwardenTxtLookup.Failure(BitwardenTxtFailure.TRANSPORT))
        }
    }

    private companion object {
        val CallbackExecutor: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Monica-Dns-Txt").apply { isDaemon = true }
        }
    }
}

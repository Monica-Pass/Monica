package takagi.ru.monica.webdav

import okhttp3.Interceptor
import okhttp3.Response
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 对接 [WebDavBackoffState] 的 OkHttp 拦截器。
 *
 * 请求前：若目标主机仍处于 backoff 期，直接抛 [RateLimitedIOException]
 * 终止此次调用，避免继续打穿服务器速率限制。
 *
 * 响应后：
 * - 429 或 503：解析 Retry-After（秒数或 HTTP-date）并
 *   调用 [WebDavBackoffState.recordRateLimit]。
 * - 2xx：调用 [WebDavBackoffState.recordSuccess]，重置该主机 backoff。
 */
class RateLimitInterceptor(
    private val backoff: WebDavBackoffStateApi = DefaultBackoffStateApi,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (host.isNotEmpty() && backoff.shouldBlock(host, clock())) {
            val wait = backoff.suggestedWaitMillis(host, clock())
            throw RateLimitedIOException(
                "Blocked by local backoff for $host (retry after ${wait}ms)",
                retryAfterMillis = wait,
            )
        }

        val response = chain.proceed(request)
        val code = response.code
        if (code == 429 || code == 503) {
            val retryAfterMs = parseRetryAfterMillis(
                headerValue = response.header(HEADER_RETRY_AFTER),
                now = clock(),
            )
            backoff.recordRateLimit(host, retryAfterMs, clock())
        } else if (code in 200..299 && host.isNotEmpty()) {
            backoff.recordSuccess(host)
        }
        return response
    }

    companion object {
        internal const val HEADER_RETRY_AFTER = "Retry-After"

        internal fun parseRetryAfterMillis(headerValue: String?, now: Long): Long? {
            if (headerValue.isNullOrBlank()) return null
            val trimmed = headerValue.trim()
            trimmed.toLongOrNull()?.let { seconds ->
                if (seconds <= 0L) return null
                return seconds * 1000L
            }
            // HTTP-date formats permitted by RFC 7231 §7.1.1.1
            for (pattern in HTTP_DATE_FORMATS) {
                try {
                    val format = SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                        isLenient = false
                    }
                    val parsed: Date = format.parse(trimmed) ?: continue
                    val delta = parsed.time - now
                    if (delta > 0L) return delta
                } catch (_: ParseException) {
                    // try next
                }
            }
            return null
        }

        private val HTTP_DATE_FORMATS = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
    }
}

/** 对 [WebDavBackoffState] 的可替换抽象，便于单测注入。 */
interface WebDavBackoffStateApi {
    fun shouldBlock(host: String, now: Long): Boolean
    fun suggestedWaitMillis(host: String, now: Long): Long
    fun recordRateLimit(host: String, retryAfterMillis: Long?, now: Long)
    fun recordSuccess(host: String)
}

internal object DefaultBackoffStateApi : WebDavBackoffStateApi {
    override fun shouldBlock(host: String, now: Long): Boolean =
        WebDavBackoffState.shouldBlock(host, now)

    override fun suggestedWaitMillis(host: String, now: Long): Long =
        WebDavBackoffState.suggestedWaitMillis(host, now)

    override fun recordRateLimit(host: String, retryAfterMillis: Long?, now: Long) {
        WebDavBackoffState.recordRateLimit(host, retryAfterMillis, now)
    }

    override fun recordSuccess(host: String) {
        WebDavBackoffState.recordSuccess(host)
    }
}

package takagi.ru.monica.webdav

import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import takagi.ru.monica.utils.MdbxRemoteWriteMode

/**
 * Performs WebDAV writes with server-enforced preconditions.
 *
 * A preceding PROPFIND is useful for user feedback and idempotency checks, but
 * it cannot prevent another client from changing the object before PUT. These
 * headers make the write itself atomic at the HTTP/WebDAV boundary.
 */
internal class WebDavConditionalWriter(
    private val httpClient: OkHttpClient
) {
    fun write(
        targetUrl: String,
        source: File,
        mode: MdbxRemoteWriteMode,
        expectedVersion: String?
    ): String? {
        val request = Request.Builder()
            .url(targetUrl)
            .put(source.asRequestBody(OCTET_STREAM))
            .apply {
                when (mode) {
                    MdbxRemoteWriteMode.CREATE_ONLY -> header("If-None-Match", "*")
                    MdbxRemoteWriteMode.IF_MATCH -> header(
                        "If-Match",
                        expectedVersion?.takeIf(String::isNotBlank)
                            ?: throw IllegalArgumentException(
                                "WebDAV conditional replacement requires an ETag"
                            )
                    )
                    MdbxRemoteWriteMode.REPLACE -> Unit
                }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code in SUCCESS_CODES) return response.header("ETag")?.takeIf(String::isNotBlank)
            val body = response.body?.string().orEmpty()
            when (response.code) {
                409, 412 -> throw WebDavPreconditionException(response.code, body)
                429, 503 -> throw RateLimitedIOException(
                    "WebDAV write throttled: HTTP ${response.code}",
                    RateLimitInterceptor.parseRetryAfterMillis(response.header("Retry-After"), System.currentTimeMillis())
                        ?: 30_000L
                )
                else -> throw IOException(
                    body.ifBlank { "WebDAV write failed: HTTP ${response.code}" }
                )
            }
        }
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val SUCCESS_CODES = setOf(200, 201, 204)
    }
}

internal class WebDavPreconditionException(
    val statusCode: Int,
    responseBody: String
) : IOException(
    "HTTP $statusCode: " + responseBody.ifBlank { "WebDAV precondition failed" }
)

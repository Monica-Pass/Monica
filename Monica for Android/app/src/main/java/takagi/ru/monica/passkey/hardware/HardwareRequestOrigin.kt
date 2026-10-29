package takagi.ru.monica.passkey.hardware

import android.content.Context
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.credentials.provider.CallingAppInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import takagi.ru.monica.passkey.PasskeyBrowserOrigin
import java.net.URI
import java.util.concurrent.TimeUnit

/** Hardware operations fail closed; do not reuse the software provider's compatibility fallbacks. */
object HardwareRequestOrigin {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
    @Volatile private var suffixes: Set<String>? = null

    private fun toAscii(value: String): String {
        val info = android.icu.text.IDNA.Info()
        val result = StringBuilder()
        android.icu.text.IDNA.getUTS46Instance(android.icu.text.IDNA.USE_STD3_RULES or android.icu.text.IDNA.NONTRANSITIONAL_TO_ASCII or
            android.icu.text.IDNA.CHECK_BIDI or android.icu.text.IDNA.CHECK_CONTEXTJ).nameToASCII(value, result, info)
        require(!info.hasErrors()) { "Unsupported IDN" }
        return result.toString().lowercase(java.util.Locale.ROOT)
    }

    fun canonicalHost(value: String): String {
        require(value.isNotEmpty() && value.length <= 253 && value == value.trim() && !value.endsWith('.') && !value.contains(':'))
        val host = toAscii(value)
        require(host.contains('.') && host.split('.').all { it.isNotEmpty() && it.length <= 63 } && !host.all { it.isDigit() || it == '.' })
        return host
    }

    internal fun registrable(host: String, rules: Set<String>): Boolean {
        val labels = host.split('.')
        var suffixCount = 1
        for (i in labels.indices) {
            val part = labels.drop(i).joinToString(".")
            if ("!$part" in rules) return labels.size > labels.size - i - 1
            if (part in rules) suffixCount = maxOf(suffixCount, labels.size - i)
            if (i > 0 && "*.$part" in rules) suffixCount = maxOf(suffixCount, labels.size - i + 1)
        }
        return labels.size > suffixCount
    }

    fun requireWebOrigin(rpId: String, origin: String, rules: Set<String>): String {
        val rp = canonicalHost(rpId)
        require(registrable(rp, rules)) { "RP ID is a public suffix" }
        val uri = URI(origin)
        require(uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")) { "Untrusted origin" }
        val host = canonicalHost(uri.host ?: error("Missing origin host"))
        require(host == rp || host.endsWith(".$rp")) { "RP ID does not match origin" }
        return "https://$host" + if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
    }

    @RequiresApi(34)
    fun resolve(context: Context, caller: CallingAppInfo, rpId: String, suppliedHash: ByteArray?): String {
        val rules = suffixes ?: synchronized(this) {
            suffixes ?: context.assets.open("hardware_passkey_public_suffix_list.dat").bufferedReader().useLines { lines ->
                lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("//") }.mapNotNull { rule ->
                    val prefix = when { rule.startsWith("!") -> "!"; rule.startsWith("*.") -> "*."; else -> "" }
                    // Older Android ICU releases cannot represent every new IDN rule. Those
                    // domains also fail canonicalHost validation; unrelated RPs remain usable.
                    runCatching { prefix + toAscii(rule.removePrefix(prefix)) }.getOrNull()
                }.toSet()
            }.also { suffixes = it }
        }
        val rp = canonicalHost(rpId)
        require(registrable(rp, rules)) { "Invalid RP ID" }
        if (caller.isOriginPopulated()) {
            val origin = PasskeyBrowserOrigin.read(context, caller) ?: error("Browser origin not authorized")
            require(suppliedHash == null || suppliedHash.size == 32) { "Invalid client data hash" }
            return requireWebOrigin(rp, origin, rules)
        }
        require(suppliedHash == null) { "Client data hash requires a trusted browser" }
        val cert = caller.signingInfo.apkContentsSigners.singleOrNull()?.toByteArray() ?: error("Ambiguous caller certificate")
        val hash = HardwarePin.sha(cert)
        val fingerprint = hash.joinToString(":") { "%02X".format(it) }
        client.newCall(Request.Builder().url("https://$rp/.well-known/assetlinks.json").build()).execute().use { response ->
            require(response.isSuccessful) { "App association unavailable" }
            val body = response.body ?: error("Empty app association")
            require(body.contentLength() <= 262144) { "App association too large" }
            val output = java.io.ByteArrayOutputStream()
            val input = body.byteStream()
            val buffer = ByteArray(4096)
            while (output.size() <= 262144) {
                val read = input.read(buffer, 0, minOf(buffer.size, 262145 - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= 262144)
            require(associated(JSONArray(String(bytes, Charsets.UTF_8)), caller.packageName, fingerprint)) { "App not associated with RP" }
        }
        return "android:apk-key-hash:" + Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    internal fun associated(statements: JSONArray, packageName: String, fingerprint: String): Boolean =
        (0 until statements.length()).any { index ->
            val statement = statements.optJSONObject(index) ?: return@any false
            val target = statement.optJSONObject("target") ?: return@any false
            val relation = statement.optJSONArray("relation") ?: return@any false
            val hashes = target.optJSONArray("sha256_cert_fingerprints") ?: return@any false
            target.optString("namespace") == "android_app" && target.optString("package_name") == packageName &&
                (0 until relation.length()).any { relation.optString(it) in listOf("delegate_permission/common.handle_all_urls", "delegate_permission/common.get_login_creds") } &&
                (0 until hashes.length()).any { hashes.optString(it).equals(fingerprint, ignoreCase = true) }
        }
}

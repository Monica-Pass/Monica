package takagi.ru.monica.autofill_ng

import com.google.re2j.Pattern
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import takagi.ru.monica.data.model.PasswordUrlRules.Mode
import takagi.ru.monica.data.model.PasswordUrlRules.Rule

/** Never invent an unavailable scheme, path or query from a browser's hostname. */
internal object PasswordUrlRuleMatcher {
    private fun http(value: String): HttpUrl? = value.trim().toHttpUrlOrNull()
    private fun host(value: String): String? = AutofillHostPolicy.host(value)

    fun validRegex(pattern: String): Boolean = pattern.length <= 2048 &&
        runCatching { Pattern.compile(pattern) }.isSuccess

    fun score(rule: Rule, target: String): Int {
        val value = rule.url.trim()
        if (value.isEmpty() || target.isBlank()) return 0
        val targetUrl = http(target)
        val targetHost = host(target) ?: return 0
        val savedHost = host(value)
        return when (rule.mode) {
            Mode.DEFAULT, Mode.NEVER -> 0 // DEFAULT is handled by the existing global policy.
            Mode.HOST -> if (savedHost == targetHost) 140 else 0
            Mode.BASE_DOMAIN -> {
                val saved = AutofillHostPolicy.address(value)
                val actual = targetUrl ?: AutofillHostPolicy.address(targetHost)
                val a = saved?.topPrivateDomain() ?: saved?.host
                val b = actual?.topPrivateDomain() ?: actual?.host
                if (a != null && a == b) 100 else 0
            }
            Mode.EXACT -> {
                if (targetUrl != null && http(value) == targetUrl) 160
                else if (!value.contains("://") && value == savedHost && value.equals(targetHost, true) && targetUrl == null) 160
                else 0
            }
            Mode.STARTS_WITH -> {
                // With only a hostname, match that complete authority; never guess a URL path.
                if (!value.contains("://") && value == savedHost &&
                    targetHost == savedHost) 125
                else if (targetUrl != null && http(value)?.let { saved ->
                    saved.scheme == targetUrl.scheme && saved.host == targetUrl.host &&
                        saved.port == targetUrl.port && targetUrl.toString().startsWith(saved.toString())
                } == true) 125 else 0
            }
            Mode.REGEX -> if (validRegex(value) && target.length <= 8192 &&
                runCatching { Pattern.compile(value).matcher(target).find() }.getOrDefault(false)) 110 else 0
        }
    }
}

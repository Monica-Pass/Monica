package takagi.ru.monica.ui.password

import java.net.URI
import java.util.Locale
import takagi.ru.monica.data.PasswordEntry

enum class WebsiteStackMatchMode(val storageValue: String) {
    STRICT("strict"),
    RELAXED("relaxed")
}

private val COMMON_SECOND_LEVEL_DOMAINS = setOf(
    "ac",
    "co",
    "com",
    "edu",
    "gov",
    "mil",
    "net",
    "nom",
    "org"
)

private fun parseWebsiteStackMatchMode(mode: String): WebsiteStackMatchMode {
    return when (mode.lowercase(Locale.ROOT)) {
        WebsiteStackMatchMode.RELAXED.storageValue -> WebsiteStackMatchMode.RELAXED
        else -> WebsiteStackMatchMode.STRICT
    }
}

fun getPasswordInfoKey(entry: PasswordEntry): String {
    val sourceKey = buildPasswordSourceKey(entry)
    // Native objects have no credential in the list model: matching titles do not
    // establish identical passwords and must never merge them into one detail link.
    if (entry.loginType == "API_TOKEN") return "$sourceKey|api-token:${entry.id}"
    // Keys from one provider can have different endpoints or notes; each keeps its own detail/editor.
    if (entry.isApiKeyEntry()) return "$sourceKey|api-key:${entry.id}"
    val title = entry.title.trim().lowercase(Locale.ROOT)
    val username = entry.username.trim().lowercase(Locale.ROOT)
    val website = normalizeWebsiteForInfoKey(entry.website)
    return "$sourceKey|$title|$website|$username"
}

fun buildPasswordSourceKey(entry: PasswordEntry): String {
    return when {
        !entry.bitwardenCipherId.isNullOrBlank() ->
            "bw:${entry.bitwardenVaultId}:${entry.bitwardenCipherId}"
        entry.bitwardenVaultId != null ->
            "bw-local:${entry.bitwardenVaultId}:${entry.bitwardenFolderId.orEmpty()}"
        entry.keepassDatabaseId != null ->
            "kp:${entry.keepassDatabaseId}:${entry.keepassGroupPath.orEmpty()}"
        entry.mdbxDatabaseId != null ->
            "mdbx:${entry.mdbxDatabaseId}:${entry.categoryId ?: "root"}"
        else -> "local:${entry.categoryId ?: "root"}"
    }
}

fun getPasswordNoteStackLabel(entry: PasswordEntry): String? {
    return entry.notes
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

fun getGroupKeyForMode(
    entry: PasswordEntry,
    mode: String,
    websiteStackMatchMode: String = WebsiteStackMatchMode.STRICT.storageValue
): String {
    val noteLabel = getPasswordNoteStackLabel(entry)
    val website = normalizeWebsiteForStackGroupKey(entry.website, websiteStackMatchMode)
    val appName = entry.appName.trim()
    val packageName = entry.appPackageName.trim()
    val title = entry.title.trim()
    val idKey = "id-${entry.id}"

    return when (mode) {
        "folder" -> getPasswordFolderStackKey(entry)
        "note" -> noteLabel.takeUnless { it.isNullOrEmpty() } ?: idKey
        "website" -> website.takeUnless { it.isEmpty() } ?: idKey
        "app" -> appName.takeUnless { it.isEmpty() }
            ?: packageName.takeUnless { it.isEmpty() }
            ?: idKey
        "title" -> title.takeUnless { it.isEmpty() } ?: idKey
        else -> {
            noteLabel.takeUnless { it.isNullOrEmpty() }
                ?: website.takeUnless { it.isEmpty() }
                ?: appName.takeUnless { it.isEmpty() }
                ?: packageName.takeUnless { it.isEmpty() }
                ?: title.takeUnless { it.isEmpty() }
                ?: idKey
        }
    }
}

/** Stable folder identity for the password page. External database paths are preferred;
 * local categories fall back to their numeric id so same-named folders never merge. */
fun getPasswordFolderStackKey(entry: PasswordEntry): String {
    return when {
        entry.keepassDatabaseId != null ->
            "kp-folder:${entry.keepassDatabaseId}:${entry.keepassGroupPath.orEmpty().ifBlank { "root" }}"
        entry.bitwardenVaultId != null ->
            "bw-folder:${entry.bitwardenVaultId}:${entry.bitwardenFolderId.orEmpty().ifBlank { "root" }}"
        entry.mdbxDatabaseId != null ->
            "mdbx-folder:${entry.mdbxDatabaseId}:${entry.mdbxFolderId.orEmpty().ifBlank { "root" }}"
        entry.categoryId != null -> "local-folder:${entry.categoryId}"
        else -> "root"
    }
}

fun getPasswordFolderStackLabel(entry: PasswordEntry): String {
    val raw = when {
        !entry.keepassGroupPath.isNullOrBlank() -> entry.keepassGroupPath.orEmpty()
        !entry.bitwardenFolderId.isNullOrBlank() -> entry.bitwardenFolderId.orEmpty()
        !entry.mdbxFolderId.isNullOrBlank() -> entry.mdbxFolderId.orEmpty()
        entry.categoryId != null -> "Category ${entry.categoryId}"
        else -> "Root"
    }
    return raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "Root" }
}

fun getPasswordGroupTitle(
    entry: PasswordEntry,
    websiteStackMatchMode: String = WebsiteStackMatchMode.STRICT.storageValue
): String = getGroupKeyForMode(entry, "smart", websiteStackMatchMode)

private fun normalizeWebsiteStrict(raw: String): String {
    val host = extractHost(raw)
    if (!host.isNullOrBlank()) {
        return host
    }

    return raw
        .trim()
        .lowercase(Locale.ROOT)
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("www.")
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('/')
}

private fun normalizeWebsiteForInfoKey(value: String): String {
    val raw = value.trim()
    if (raw.isEmpty()) return ""
    return normalizeWebsiteStrict(raw)
}

private fun normalizeWebsiteForStackGroupKey(
    value: String,
    websiteStackMatchMode: String
): String {
    val raw = value.trim()
    if (raw.isEmpty()) return ""
    return when (parseWebsiteStackMatchMode(websiteStackMatchMode)) {
        WebsiteStackMatchMode.STRICT -> normalizeWebsiteStrict(raw)
        WebsiteStackMatchMode.RELAXED -> extractPrimaryDomain(raw) ?: raw
    }
}

private fun extractPrimaryDomain(raw: String): String? {
    val host = extractHost(raw) ?: return null
    if (host.isBlank()) return null
    if (isIpAddress(host)) return host

    val labels = host.split('.').filter { it.isNotBlank() }
    if (labels.size <= 2) return labels.joinToString(".")

    val tld = labels.last()
    val secondLevel = labels[labels.lastIndex - 1]
    val thirdLevel = labels[labels.lastIndex - 2]

    return if (isCountryCodeTld(tld) && secondLevel in COMMON_SECOND_LEVEL_DOMAINS) {
        "$thirdLevel.$secondLevel.$tld"
    } else {
        "$secondLevel.$tld"
    }
}

private fun extractHost(raw: String): String? {
    val normalized = raw.trim().lowercase(Locale.ROOT)
    if (normalized.isBlank()) return null

    val fromUri = runCatching {
        val withScheme = if ("://" in normalized) normalized else "https://$normalized"
        URI(withScheme).host
    }.getOrNull()

    val fallback = normalized
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')

    val host = (fromUri ?: fallback)
        .substringBefore(':')
        .trim('.')
        .removePrefix("www.")

    return host.ifBlank { null }
}

private fun isCountryCodeTld(tld: String): Boolean =
    tld.length == 2 && tld.all { it in 'a'..'z' }

private fun isIpAddress(host: String): Boolean {
    val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    return ipv4Regex.matches(host) || host.contains(':')
}

enum class StackCardMode {
    AUTO,
    ALWAYS_EXPANDED
}

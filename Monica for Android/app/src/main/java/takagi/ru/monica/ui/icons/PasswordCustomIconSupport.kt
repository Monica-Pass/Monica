package takagi.ru.monica.ui.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.random.Random

const val PASSWORD_ICON_TYPE_NONE = "NONE"
const val PASSWORD_ICON_TYPE_SIMPLE = "SIMPLE_ICON"
const val PASSWORD_ICON_TYPE_UPLOADED = "UPLOADED"
const val PASSWORD_ICON_TYPE_EMOJI = "EMOJI"
private const val STRATUM_ICON_ASSET_ROOT = "stratum_icons"
private const val STRATUM_ICON_ASSET_MAIN_DIR = "$STRATUM_ICON_ASSET_ROOT/icons"
private const val STRATUM_ICON_ASSET_EXTRA_DIR = "$STRATUM_ICON_ASSET_ROOT/extraicons"

data class SimpleIconOption(
    val slug: String,
    val label: String
)

data class AutoMatchedSimpleIcon(
    val slug: String?,
    val bitmap: ImageBitmap?,
    val resolved: Boolean
)

object SimpleIconCatalog {
    @Volatile
    private var cachedOptions: List<SimpleIconOption>? = null
    @Volatile
    private var cachedSlugs: Set<String>? = null

    fun search(context: Context, query: String): List<SimpleIconOption> {
        val q = query.trim().lowercase(Locale.ROOT)
        val options = getOptions(context)
        if (q.isEmpty()) return options
        return options.filter { option ->
            option.label.lowercase(Locale.ROOT).contains(q) ||
                option.slug.lowercase(Locale.ROOT).contains(q)
        }
    }

    private fun getOptions(context: Context): List<SimpleIconOption> {
        cachedOptions?.let { return it }
        val slugs = LinkedHashSet<String>()
        collectSlugs(context, STRATUM_ICON_ASSET_MAIN_DIR, slugs)
        collectSlugs(context, STRATUM_ICON_ASSET_EXTRA_DIR, slugs)

        val resolved = slugs.map { slug ->
            SimpleIconOption(slug = slug, label = prettyLabel(slug))
        }.sortedBy { it.label.lowercase(Locale.ROOT) }

        cachedOptions = resolved
        return resolved
    }

    fun getSlugs(context: Context): Set<String> {
        cachedSlugs?.let { return it }
        val slugs = getOptions(context).mapTo(LinkedHashSet()) { it.slug }
        cachedSlugs = slugs
        return slugs
    }

    private fun collectSlugs(context: Context, assetDir: String, output: MutableSet<String>) {
        val files = runCatching { context.assets.list(assetDir).orEmpty() }.getOrDefault(emptyArray())
        files.forEach { name ->
            if (!name.endsWith(".png", ignoreCase = true)) return@forEach
            val raw = name.removeSuffix(".png")
            val normalized = if (raw.endsWith("_dark")) raw.removeSuffix("_dark") else raw
            if (normalized.isNotBlank()) {
                output.add(normalized.lowercase(Locale.ROOT))
            }
        }
    }

    private fun prettyLabel(slug: String): String {
        val parts = slug.replace('_', ' ').replace('-', ' ')
            .split(" ")
            .filter { it.isNotBlank() }
        return parts.joinToString(" ") { part ->
            if (part.length <= 3) {
                part.uppercase(Locale.ROOT)
            } else {
                part.replaceFirstChar { c ->
                    if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString()
                }
            }
        }.ifBlank { slug }
    }
}

private val DOMAIN_ALIAS_TO_ICON_SLUG = mapOf(
    "steampowered" to "steam",
    "steamcommunity" to "steam",
    "office365" to "office",
    "live" to "microsoft",
    "x" to "twitter"
)

private val WEB_SCHEMES = setOf("http", "https")

private val GENERIC_HOST_LABELS = setOf(
    "www", "m", "mobile", "login", "auth", "account", "accounts", "secure", "id", "api", "app", "store",
    "com", "net", "org", "io", "co", "dev", "android"
)

private val GENERIC_PACKAGE_PARTS = setOf(
    "com", "net", "org", "io", "co", "dev", "app", "android"
)

private val MULTI_PART_PUBLIC_SUFFIX = setOf(
    "co.uk", "org.uk", "ac.uk", "gov.uk", "com.cn", "com.hk", "co.jp", "com.au", "com.br", "co.in"
)

private val NON_ALNUM_REGEX = Regex("[^a-z0-9]")
private val NON_ALNUM_OR_SPACE_REGEX = Regex("[^a-z0-9 ]")

private data class ParsedWebsite(
    val scheme: String?,
    val host: String
)

private fun normalizeAutoSlugToken(value: String): String {
    return value.trim()
        .lowercase(Locale.ROOT)
        .replace(NON_ALNUM_REGEX, "")
}

private fun parseWebsite(rawWebsite: String): ParsedWebsite {
    val raw = rawWebsite.trim()
    if (raw.isBlank()) return ParsedWebsite(scheme = null, host = "")
    val withScheme = if (raw.contains("://")) raw else "https://$raw"
    val parsed = runCatching { URI(withScheme) }.getOrNull()
    return ParsedWebsite(
        scheme = parsed?.scheme?.trim()?.lowercase(Locale.ROOT),
        host = parsed?.host.orEmpty().trim().lowercase(Locale.ROOT)
    )
}

private fun extractRegistrableDomainLabel(host: String): String {
    val labels = host.split('.').filter { it.isNotBlank() }
    if (labels.isEmpty()) return ""
    if (labels.size == 1) return labels.first()

    val lastTwo = labels.takeLast(2).joinToString(".")
    return if (lastTwo in MULTI_PART_PUBLIC_SUFFIX && labels.size >= 3) {
        labels[labels.size - 3]
    } else {
        labels[labels.size - 2]
    }
}

private fun buildAutoMatchCandidates(
    website: String,
    title: String?,
    appPackageName: String?
): List<String> {
    val candidates = LinkedHashSet<String>()

    fun addCandidate(raw: String?) {
        if (raw.isNullOrBlank()) return
        val normalized = normalizeAutoSlugToken(raw)
        if (normalized.isBlank()) return
        candidates.add(normalized)
        DOMAIN_ALIAS_TO_ICON_SLUG[normalized]?.let { alias -> candidates.add(alias) }
    }

    val parsedWebsite = parseWebsite(website)
    val host = parsedWebsite.host
    val isWebScheme = parsedWebsite.scheme == null || parsedWebsite.scheme in WEB_SCHEMES
    if (host.isNotBlank()) {
        val cleanHost = host.removePrefix("www.")
        val labels = cleanHost.split('.').filter { it.isNotBlank() }

        if (isWebScheme) {
            addCandidate(extractRegistrableDomainLabel(cleanHost))
            addCandidate(labels.joinToString(""))
            labels.forEachIndexed { index, label ->
                if (index < labels.lastIndex && label !in GENERIC_HOST_LABELS) {
                    addCandidate(label)
                }
            }
        } else {
            labels.forEach { label ->
                if (label !in GENERIC_HOST_LABELS) {
                    addCandidate(label)
                }
            }
            // Non-web URI (e.g. android://com.example.app) should not prefer a fake "domain" segment.
            if (labels.size >= 2) {
                addCandidate(labels[labels.size - 2])
            } else if (labels.size == 1) {
                addCandidate(labels.first())
            }
        }
    }

    // A web address is the authoritative identity for a website entry. Title
    // and app-package tokens are weak signals and can belong to an unrelated
    // linked app (for example com.github.android on a linux.do entry), so they
    // must not replace the website favicon with another site's brand icon.
    if (host.isNotBlank() && isWebScheme) {
        return candidates.toList()
    }

    title?.takeIf { it.isNotBlank() }?.let { rawTitle ->
        val compactTitle = rawTitle.lowercase(Locale.ROOT).replace(NON_ALNUM_OR_SPACE_REGEX, " ")
        compactTitle.split(' ')
            .asSequence()
            .filter { it.isNotBlank() && it.length > 1 && it !in GENERIC_HOST_LABELS }
            .forEach { token -> addCandidate(token) }
        addCandidate(compactTitle.replace(" ", ""))
    }

    appPackageName?.takeIf { it.isNotBlank() }?.let { pkg ->
        val parts = pkg.lowercase(Locale.ROOT).split('.')
        parts.asReversed()
            .asSequence()
            .filter { it.isNotBlank() && it !in GENERIC_PACKAGE_PARTS }
            .forEach { part -> addCandidate(part) }
    }

    return candidates.toList()
}

fun resolveAutoMatchedSimpleIconSlug(
    context: Context,
    website: String,
    title: String? = null,
    appPackageName: String? = null
): String? {
    val hasAnyInput = website.isNotBlank() || !title.isNullOrBlank() || !appPackageName.isNullOrBlank()
    if (!hasAnyInput) return null
    val availableSlugs = SimpleIconCatalog.getSlugs(context)
    if (availableSlugs.isEmpty()) return null

    return resolveAutoMatchedSimpleIconSlugFromSlugs(
        availableSlugs = availableSlugs,
        website = website,
        title = title,
        appPackageName = appPackageName
    )
}

/**
 * Resolves an automatic icon using a supplied catalog.
 *
 * Keeping the catalog as an argument makes the precedence rule testable without
 * constructing an Android AssetManager. Website entries must never inherit an
 * unrelated icon from an associated application's package name.
 */
internal fun resolveAutoMatchedSimpleIconSlugFromSlugs(
    availableSlugs: Set<String>,
    website: String,
    title: String? = null,
    appPackageName: String? = null
): String? {
    val candidates = buildAutoMatchCandidates(
        website = website,
        title = title,
        appPackageName = appPackageName
    )

    return candidates.firstOrNull { candidate -> candidate in availableSlugs }
}

object PasswordCustomIconStore {
    private const val TAG = "PasswordCustomIconStore"
    private const val ICON_DIR = "password_icons"
    private const val MAX_DIMENSION = 384

    fun getIconDir(context: Context): File {
        val dir = File(context.filesDir, ICON_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun resolveIconFile(context: Context, value: String?): File? {
        if (value.isNullOrBlank()) return null
        val safeName = File(value).name
        val file = File(getIconDir(context), safeName)
        return if (file.exists()) file else null
    }

    fun deleteIconFile(context: Context, value: String?): Boolean {
        val file = resolveIconFile(context, value) ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    suspend fun duplicateIconFile(context: Context, value: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val source = resolveIconFile(context, value)
                ?: throw IllegalStateException("Icon source is unavailable")
            val extension = source.extension.takeIf { it.isNotBlank() } ?: "png"
            val fileName = "icon_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}.$extension"
            source.copyTo(File(getIconDir(context), fileName), overwrite = false)
            fileName
        }
    }

    suspend fun importAndCompress(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = decodeBitmapCompat(context, uri)
                ?: throw IllegalStateException("Unsupported image format")
            try {
                writeBitmap(context, decoded)
            } finally {
                decoded.recycle()
            }
        }
    }

    /** The caller retains ownership of [bitmap]. */
    suspend fun importBitmap(context: Context, bitmap: Bitmap): Result<String> = withContext(Dispatchers.IO) {
        runCatching { writeBitmap(context, bitmap) }
    }

    private fun writeBitmap(context: Context, bitmap: Bitmap): String {
        val finalBitmap = resizeIfNeeded(bitmap, MAX_DIMENSION)
        val fileName = "icon_${java.util.UUID.randomUUID()}.png"
        val target = File(getIconDir(context), fileName)
        try {
            FileOutputStream(target).use { out ->
                if (!finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Failed to compress image")
                }
                out.flush()
            }
            return fileName
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            if (finalBitmap !== bitmap) finalBitmap.recycle()
        }
    }

    private fun decodeBitmapCompat(context: Context, uri: Uri): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val sample = calculateSampleSize(info.size.width, info.size.height, MAX_DIMENSION)
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            }.onFailure {
                Log.w(TAG, "ImageDecoder failed, fallback to BitmapFactory", it)
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > maxDimension || currentHeight > maxDimension) {
            sample *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun resizeIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap
        val scale = minOf(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
}

private object SimpleIconCache {
    private const val TAG = "SimpleIconCache"
    private const val DISK_DIR = "stratum_icons"
    private const val CACHE_VERSION = "stratum_v1_4_0"
    private val memory = LruCache<String, ImageBitmap>(80)

    suspend fun getIcon(context: Context, slug: String, darkTheme: Boolean): ImageBitmap? {
        val normalizedSlug = normalizeSimpleIconSlug(slug)
        if (normalizedSlug.isEmpty()) return null
        val key = "${normalizedSlug}_${if (darkTheme) "dark" else "light"}_$CACHE_VERSION"

        memory.get(key)?.let { return it }

        val diskDir = File(context.cacheDir, DISK_DIR).also { if (!it.exists()) it.mkdirs() }
        val diskFile = File(diskDir, "$key.png")

        return withContext(Dispatchers.IO) {
            if (diskFile.exists()) {
                BitmapFactory.decodeFile(diskFile.absolutePath)?.let { bitmap ->
                    val image = bitmap.asImageBitmap()
                    memory.put(key, image)
                    return@withContext image
                }
            }

            runCatching {
                val bitmap = fetchSimpleIconBitmap(context, normalizedSlug, darkTheme) ?: return@runCatching null
                FileOutputStream(diskFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
                val image = bitmap.asImageBitmap()
                memory.put(key, image)
                image
            }.onFailure { error ->
                Log.w(TAG, "Failed to load stratum icon: slug=$slug", error)
            }.getOrNull()
        }
    }

    private fun fetchSimpleIconBitmap(context: Context, normalizedSlug: String, darkTheme: Boolean): Bitmap? {
        val candidates = if (darkTheme) {
            listOf(
                "$STRATUM_ICON_ASSET_MAIN_DIR/${normalizedSlug}_dark.png",
                "$STRATUM_ICON_ASSET_EXTRA_DIR/${normalizedSlug}_dark.png",
                "$STRATUM_ICON_ASSET_MAIN_DIR/$normalizedSlug.png",
                "$STRATUM_ICON_ASSET_EXTRA_DIR/$normalizedSlug.png"
            )
        } else {
            listOf(
                "$STRATUM_ICON_ASSET_MAIN_DIR/$normalizedSlug.png",
                "$STRATUM_ICON_ASSET_EXTRA_DIR/$normalizedSlug.png",
                "$STRATUM_ICON_ASSET_MAIN_DIR/${normalizedSlug}_dark.png",
                "$STRATUM_ICON_ASSET_EXTRA_DIR/${normalizedSlug}_dark.png"
            )
        }

        for (assetPath in candidates) {
            val bitmap = runCatching {
                context.assets.open(assetPath).use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
            if (bitmap != null) {
                return bitmap
            }
        }
        return null
    }
}

fun normalizeSimpleIconSlug(input: String): String {
    return input.trim().lowercase(Locale.ROOT).replace(" ", "")
}

private object UploadedPasswordIconMemoryCache {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val memory = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun load(context: Context, value: String): ImageBitmap? {
        val file = PasswordCustomIconStore.resolveIconFile(context, value) ?: return null
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        val bitmap = memory.get(key) ?: BitmapFactory.decodeFile(file.absolutePath)
            ?.also { decoded -> memory.put(key, decoded) }
        return bitmap?.asImageBitmap()
    }
}

@Composable
fun rememberUploadedPasswordIcon(value: String?): ImageBitmap? {
    val context = LocalContext.current
    var icon by remember(value) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(value) {
        if (value.isNullOrBlank()) {
            icon = null
            return@LaunchedEffect
        }
        icon = withContext(Dispatchers.IO) {
            UploadedPasswordIconMemoryCache.load(context, value)
        }
    }
    return icon
}

@Composable
@Suppress("UNUSED_PARAMETER")
fun rememberSimpleIconBitmap(slug: String?, tintColor: Color, enabled: Boolean = true): ImageBitmap? {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    var icon by remember(slug, darkTheme, enabled) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(slug, darkTheme, enabled) {
        if (!enabled || slug.isNullOrBlank()) {
            icon = null
            return@LaunchedEffect
        }
        icon = SimpleIconCache.getIcon(context, slug, darkTheme)
    }
    return icon
}

@Composable
fun rememberAutoMatchedSimpleIcon(
    website: String,
    title: String? = null,
    appPackageName: String? = null,
    tintColor: Color,
    enabled: Boolean = true
): AutoMatchedSimpleIcon {
    val context = LocalContext.current
    var matchedSlug by remember(website, title, appPackageName, enabled) { mutableStateOf<String?>(null) }
    var resolved by remember(website, title, appPackageName, enabled) { mutableStateOf(!enabled) }

    LaunchedEffect(website, title, appPackageName, enabled) {
        val hasAnyInput = website.isNotBlank() || !title.isNullOrBlank() || !appPackageName.isNullOrBlank()
        if (!enabled || !hasAnyInput) {
            matchedSlug = null
            resolved = true
            return@LaunchedEffect
        }
        resolved = false
        matchedSlug = withContext(Dispatchers.Default) {
            resolveAutoMatchedSimpleIconSlug(
                context = context,
                website = website,
                title = title,
                appPackageName = appPackageName
            )
        }
        resolved = true
    }

    val bitmap = rememberSimpleIconBitmap(
        slug = matchedSlug,
        tintColor = tintColor,
        enabled = enabled && !matchedSlug.isNullOrBlank()
    )
    return AutoMatchedSimpleIcon(slug = matchedSlug, bitmap = bitmap, resolved = resolved)
}

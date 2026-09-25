package takagi.ru.monica.ui.icons

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

internal data class InstalledIconPack(val packageName: String, val label: String)

internal data class InstalledIconOption(
    val packageName: String,
    val label: String,
    val drawableName: String? = null,
) {
    val key: String get() = "$packageName:${drawableName.orEmpty()}"
}

/** Read installed artwork without launching or loading code from the source application. */
internal object InstalledIconCatalog {
    private val packActions = listOf(
        "org.adw.launcher.THEMES",
        "org.adw.launcher.icons.ACTION_PICK_ICON",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
    )

    @Suppress("DEPRECATION")
    suspend fun applications(context: Context): List<InstalledIconOption> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { it.activityInfo?.takeIf { activity -> activity.enabled }?.applicationInfo }
            .filter { it.enabled }
            .distinctBy { it.packageName }
            .map { InstalledIconOption(it.packageName, it.loadLabel(manager).toString()) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    @Suppress("DEPRECATION")
    suspend fun packs(context: Context): List<InstalledIconPack> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        packActions.flatMap { manager.queryIntentActivities(Intent(it), 0) }
            .mapNotNull { it.activityInfo?.takeIf { activity -> activity.enabled }?.applicationInfo }
            .filter { it.enabled }
            .distinctBy { it.packageName }
            .map { InstalledIconPack(it.packageName, it.loadLabel(manager).toString()) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    suspend fun icons(context: Context, pack: InstalledIconPack): List<InstalledIconOption> =
        withContext(Dispatchers.IO) {
            val resources = context.packageManager.getResourcesForApplication(pack.packageName)
            val names = linkedSetOf<String>()
            for (fileName in listOf("drawable", "appfilter")) {
                val resourceId = resources.getIdentifier(fileName, "xml", pack.packageName)
                if (resourceId != 0) {
                    resources.getXml(resourceId).use { names += readIconPackDrawableNames(it) }
                } else {
                    // Some packs ship the catalog only as an asset instead of a compiled resource.
                    val stream = try {
                        resources.assets.open("$fileName.xml")
                    } catch (_: java.io.FileNotFoundException) {
                        null
                    }
                    stream?.use {
                        val parser = Xml.newPullParser().apply { setInput(it, "UTF-8") }
                        names += readIconPackDrawableNames(parser)
                    }
                }
            }
            names.mapNotNull { name ->
                if (drawableId(resources, pack.packageName, name) == 0) null
                else InstalledIconOption(pack.packageName, name.replace('_', ' '), name)
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        }

    suspend fun bitmap(context: Context, option: InstalledIconOption, size: Int): Bitmap =
        withContext(Dispatchers.IO) { loadBitmap(context, option, size) }

    /** Persist a copy, so package upgrades or uninstallation cannot invalidate the saved icon. */
    suspend fun importIcon(context: Context, option: InstalledIconOption): Result<String> {
        var savedFile: String? = null
        return try {
            val name = withContext(Dispatchers.IO) {
                val bitmap = loadBitmap(context, option, 384)
                try {
                    PasswordCustomIconStore.importBitmap(context, bitmap).getOrThrow().also { savedFile = it }
                } finally {
                    bitmap.recycle()
                }
            }
            Result.success(name)
        } catch (cancelled: CancellationException) {
            savedFile?.let { PasswordCustomIconStore.deleteIconFile(context, it) }
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun loadBitmap(context: Context, option: InstalledIconOption, size: Int): Bitmap {
        require(size in 1..384)
        val drawable = if (option.drawableName == null) {
            context.packageManager.getApplicationIcon(option.packageName)
        } else {
            val resources = context.packageManager.getResourcesForApplication(option.packageName)
            val id = drawableId(resources, option.packageName, option.drawableName)
            require(id != 0) { "Icon is no longer available" }
            resources.getDrawable(id, null)
        }.mutate()
        val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: size
        val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: size
        val scale = size.toFloat() / maxOf(sourceWidth, sourceHeight)
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        // Always draw into an owned bitmap; resource Bitmaps may be shared by Android.
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(result))
            return result
        } catch (error: Exception) {
            result.recycle()
            throw error
        }
    }

    private fun drawableId(resources: Resources, packageName: String, name: String): Int =
        resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
            ?: resources.getIdentifier(name, "mipmap", packageName)
}

/** Accept both common launcher catalogs, ignoring categories and duplicate app mappings. */
internal fun readIconPackDrawableNames(parser: XmlPullParser): Set<String> {
    val names = linkedSetOf<String>()
    var event = parser.eventType
    var events = 0
    while (event != XmlPullParser.END_DOCUMENT) {
        require(++events <= 250_000) { "Icon catalog is too large" }
        if (event == XmlPullParser.START_TAG && parser.name == "item") {
            normalizeIconDrawableName(parser.getAttributeValue(null, "drawable"))?.let { names += it }
            require(names.size <= 50_000) { "Icon catalog is too large" }
        }
        event = parser.next()
    }
    return names
}

internal fun normalizeIconDrawableName(value: String?): String? {
    val name = value?.trim()?.removePrefix("@drawable/")?.removePrefix("@mipmap/") ?: return null
    return name.takeIf { ICON_DRAWABLE_NAME.matches(it) }
}

private val ICON_DRAWABLE_NAME = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

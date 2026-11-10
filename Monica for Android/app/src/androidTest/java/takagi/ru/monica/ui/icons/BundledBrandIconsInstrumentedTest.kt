package takagi.ru.monica.ui.icons

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R

/** Original catalog contract is independent of the loader's current extension/fallback logic. */
@RunWith(AndroidJUnit4::class)
class BundledBrandIconsInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    private val root = File(base.cacheDir, "bundled-icons-327-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir() = root
    }
    private val contract by lazy {
        instrumentation.context.assets.open("bundled-icon-catalog-327.json").bufferedReader().use {
            JSONObject(it.readText())
        }
    }

    @After fun removeOwnedCache() {
        check(root.parentFile == base.cacheDir && root.name.startsWith("bundled-icons-327-"))
        root.deleteRecursively()
    }

    @Test fun originalCatalogSlugsLabelsAndOrderSurviveMixedEncodings() {
        val entries = contract.getJSONArray("entries")
        val expected = (0 until entries.length()).map {
            entries.getJSONObject(it).let { entry -> SimpleIconOption(entry.getString("slug"), entry.getString("label")) }
        }
        assertEquals(838, expected.size)
        assertEquals(expected, SimpleIconCatalog.search(context, ""))
        assertEquals(expected.map { it.slug }.toSet(), SimpleIconCatalog.getSlugs(context))
        assertEquals(listOf(SimpleIconOption("monica", "Monica")), SimpleIconCatalog.search(context, "Monica"))
        assertEquals("github.png", normalizeSimpleIconSlug("github.png"))
    }

    @Test fun everyPackagedImageDecodesWithItsOriginalDimensions() {
        val expected = contract.getJSONArray("assets")
        val expectedSources = (0 until expected.length()).associate {
            val item = expected.getJSONObject(it)
            item.getString("source") to (item.getInt("width") to item.getInt("height"))
        }
        val actual = packagedPaths()
        assertEquals(expectedSources.keys, actual.keys)
        assertEquals(1078, actual.size)
        actual.forEach { (source, path) ->
            val decoded = decodeAsset(path)
            try {
                val size = expectedSources.getValue(source)
                assertEquals("$path width", size.first, decoded.width)
                assertEquals("$path height", size.second, decoded.height)
                assertFalse("$path pixels", decoded.isRecycled)
            } finally { decoded.recycle() }
        }
    }

    @Test fun everySavedSlugLoadsOriginalLightAndDarkSourcePixels() = runBlocking {
        val assets = packagedPaths()
        val entries = contract.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val slug = entry.getString("slug")
            for (dark in listOf(false, true)) {
                val source = entry.getString(if (dark) "dark" else "light")
                val expected = if (source == "drawable/monica_launcher") decodeLauncher()
                    else decodeAsset(assets.getValue(source))
                try {
                    val actual = checkNotNull(SimpleIconCache.getIcon(context, slug, dark)) { "$slug dark=$dark missing" }
                    assertPixels("$slug dark=$dark", expected, actual.asAndroidBitmap())
                    assertSame("warm cache $slug dark=$dark", actual, SimpleIconCache.getIcon(context, slug, dark))
                    if (slug.all { it in 'a'..'z' || it in '0'..'9' }) {
                        assertEquals(slug, resolveAutoMatchedSimpleIconSlug(context, "", title = slug))
                    }
                } finally { expected.recycle() }
            }
        }
    }

    @Test fun monicaRemainsSelectableWithoutDuplicateAssetAndMatchesLauncher() = runBlocking {
        val files = context.assets.list("stratum_icons/extraicons").orEmpty()
        assertFalse(files.any { it.substringBeforeLast('.') == "monica" })
        val expected = decodeLauncher()
        try {
            for (dark in listOf(false, true)) {
                assertPixels("monica dark=$dark", expected,
                    checkNotNull(SimpleIconCache.getIcon(context, " Monica ", dark)).asAndroidBitmap())
            }
            assertEquals("monica", resolveAutoMatchedSimpleIconSlug(context, "https://monica.example"))
        } finally { expected.recycle() }
    }

    @Test fun existingPngDiskCacheIsReusedUnchangedAndMemoryHitDoesNotRewriteIt() = runBlocking {
        val slug = "legacyfixture${UUID.randomUUID().toString().replace("-", "")}" // never a bundled slug
        val cached = File(File(root, "stratum_icons").apply { mkdirs() }, "${slug}_light_stratum_v1_4_0.png")
        val expected = syntheticPngBitmap()
        try {
            val bytes = pngBytes(expected)
            cached.writeBytes(bytes)
            val beforeModified = cached.lastModified()
            val loaded = checkNotNull(SimpleIconCache.getIcon(context, slug, false))
            assertPixels("old PNG cache", expected, loaded.asAndroidBitmap())
            assertArrayEquals(bytes, cached.readBytes())
            assertEquals(beforeModified, cached.lastModified())
            assertSame(loaded, SimpleIconCache.getIcon(context, slug, false))
            assertArrayEquals(bytes, cached.readBytes())
            assertEquals(beforeModified, cached.lastModified())
            assertNull(SimpleIconCache.getIcon(context, "missing${UUID.randomUUID()}", true))
        } finally { expected.recycle() }
    }

    private fun packagedPaths(): Map<String, String> {
        val paths = listOf("stratum_icons/icons", "stratum_icons/extraicons").flatMap { directory ->
            context.assets.list(directory).orEmpty().filter { it.endsWith(".png") || it.endsWith(".webp") }
                .map { "$directory/$it" }
        }
        val grouped = paths.groupBy { it.substringBeforeLast('.') }
        assertTrue("one encoding per logical asset", grouped.values.all { it.size == 1 })
        return grouped.mapValues { it.value.single() }
    }

    private fun decodeAsset(path: String): Bitmap = context.assets.open(path).use {
        checkNotNull(BitmapFactory.decodeStream(it)) { "Cannot decode $path" }
    }
    private fun decodeLauncher(): Bitmap = checkNotNull(BitmapFactory.decodeResource(context.resources,
        R.drawable.monica_launcher, BitmapFactory.Options().apply { inScaled = false }))
}

internal fun assertPixels(label: String, expected: Bitmap, actual: Bitmap) {
    assertEquals("$label width", expected.width, actual.width)
    assertEquals("$label height", expected.height, actual.height)
    val left = IntArray(expected.width * expected.height)
    val right = IntArray(actual.width * actual.height)
    expected.getPixels(left, 0, expected.width, 0, 0, expected.width, expected.height)
    actual.getPixels(right, 0, actual.width, 0, 0, actual.width, actual.height)
    assertArrayEquals(label, left, right)
}

internal fun syntheticPngBitmap(): Bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888).apply {
    for (y in 0 until height) for (x in 0 until width) {
        setPixel(x, y, android.graphics.Color.argb(if ((x + y) % 3 == 0) 96 else 255, x * 30, y * 45, 180))
    }
}

internal fun pngBytes(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
}

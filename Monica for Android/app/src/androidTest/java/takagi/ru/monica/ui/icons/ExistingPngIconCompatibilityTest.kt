package takagi.ru.monica.ui.icons

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.utils.BackupContentScope
import takagi.ru.monica.data.BackupPreferences
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.utils.WebDavHelper

@RunWith(AndroidJUnit4::class)
class ExistingPngIconCompatibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "old-png-icons-327-${UUID.randomUUID()}-"
    private val root = File(base.cacheDir, prefix).apply { mkdirs() }
    private val preferenceNames = mutableSetOf<String>()
    private val faviconAttempts = AtomicInteger()
    private val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo() = android.content.pm.ApplicationInfo(base.applicationInfo).apply { dataDir = root.path }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
        override fun getCacheDir(): File {
            // The real favicon consumer reaches its cache before any HTTP request. Trap that
            // boundary so a failed bundled lookup cannot quietly contact an external service.
            if (Thread.currentThread().stackTrace.any { it.className.contains("FaviconCache") }) {
                faviconAttempts.incrementAndGet()
                throw IOException("Unexpected favicon fallback for a bundled brand")
            }
            return File(root, "cache").apply { mkdirs() }
        }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            synchronized(preferenceNames) { preferenceNames.add(name) }
            return base.getSharedPreferences(prefix + name, mode)
        }
    }

    @After fun cleanOwnedFixtures() {
        compose.runOnIdle { }
        synchronized(preferenceNames) { preferenceNames.forEach { base.deleteSharedPreferences(prefix + it) } }
        check(root.parentFile == base.cacheDir && root.name.startsWith("old-png-icons-327-"))
        root.deleteRecursively()
    }

    @Test fun existingUploadedPngRendersAndDuplicatesWithoutChangingOriginalNameOrBytes() = runBlocking {
        val name = "icon_legacy_2019.png"
        val expected = syntheticPngBitmap()
        val original = File(PasswordCustomIconStore.getIconDir(context), name)
        try {
            val bytes = pngBytes(expected)
            original.writeBytes(bytes)
            val entry = PasswordEntry(title = "Synthetic old PNG project", website = "", username = "fixture", password = "",
                customIconType = PASSWORD_ICON_TYPE_UPLOADED, customIconValue = name)
            val bitmap = AtomicReference<ImageBitmap?>()
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    val loaded = rememberUploadedPasswordIcon(entry.customIconValue)
                    SideEffect { bitmap.set(loaded) }
                    MaterialTheme { VaultItemIcon("", entry.title, "", entry.customIconType, entry.customIconValue, Icons.Default.Key) }
                }
            }
            compose.waitUntil(10_000) { bitmap.get() != null }
            assertPixels("stored PNG displayed", expected, checkNotNull(bitmap.get()).asAndroidBitmap())
            assertEquals(name, PasswordCustomIconStore.resolveIconFile(context, "old/export/$name")?.name)
            val duplicate = PasswordCustomIconStore.duplicateIconFile(context, entry.customIconValue).getOrThrow()
            assertTrue(duplicate.endsWith(".png"))
            assertNotEquals(name, duplicate)
            assertArrayEquals(bytes, checkNotNull(PasswordCustomIconStore.resolveIconFile(context, duplicate)).readBytes())
            assertEquals(name, entry.customIconValue)
            assertArrayEquals(bytes, original.readBytes())
        } finally { expected.recycle() }
    }

    @Test fun pngPhotoImportStillCreatesOwnedPngAndLeavesSourceUntouched() = runBlocking {
        val expected = syntheticPngBitmap()
        val source = File(root, "existing-project-icon.png")
        try {
            val bytes = pngBytes(expected)
            source.writeBytes(bytes)
            val name = PasswordCustomIconStore.importAndCompress(context, Uri.fromFile(source)).getOrThrow()
            assertTrue(name.endsWith(".png"))
            val stored = checkNotNull(PasswordCustomIconStore.resolveIconFile(context, name))
            assertTrue(stored.parentFile == PasswordCustomIconStore.getIconDir(context))
            val decoded = checkNotNull(BitmapFactory.decodeFile(stored.absolutePath))
            try { assertPixels("imported project PNG", expected, decoded) } finally { decoded.recycle() }
            assertArrayEquals(bytes, source.readBytes())
            assertEquals("existing-project-icon.png", source.name)
        } finally { expected.recycle() }
    }

    @Test fun existingUploadedPngAndSavedBrandSlugsSurviveRealZipExportAndRestore() = runBlocking {
        val expected = syntheticPngBitmap()
        val name = "old-project-preserved.png"
        val original = File(PasswordCustomIconStore.getIconDir(context), name)
        try {
            val bytes = pngBytes(expected)
            original.writeBytes(bytes)
            val entries = listOf(
                PasswordEntry(id = -32701, title = "Synthetic PNG", website = "", username = "fixture", password = "",
                    customIconType = PASSWORD_ICON_TYPE_UPLOADED, customIconValue = name),
                PasswordEntry(id = -32702, title = "Synthetic brand", website = "", username = "fixture", password = "",
                    customIconType = PASSWORD_ICON_TYPE_SIMPLE, customIconValue = "monica"),
                PasswordEntry(id = -32703, title = "Unknown legacy value", website = "", username = "fixture", password = "",
                    customIconType = PASSWORD_ICON_TYPE_SIMPLE, customIconValue = "unknown-name.png"),
            )
            val preferences = BackupPreferences(includePasswords = true, includeAuthenticators = false,
                includeDocuments = false, includeBankCards = false, includePasskeys = false,
                includeGeneratorHistory = false, includeImages = false, includeNotes = false,
                includeTimeline = false, includeTrash = false, includeTrashAndHistory = false,
                includeWebDavConfig = false, includeLocalKeePass = false)
            val helper = WebDavHelper(context)
            val (archive, report) = helper.createBackupZip(entries, emptyList(), preferences,
                contentScope = BackupContentScope.MONICA_LOCAL_ONLY, allowBackupEncryption = false).getOrThrow()
            try {
                assertTrue(report.success)
                assertEquals(3, report.successItems.passwords)
                ZipFile(archive).use { zip ->
                    val icon = zip.entries().asSequence().single { it.name.endsWith("password_icons/$name") }
                    assertArrayEquals(bytes, zip.getInputStream(icon).use { it.readBytes() })
                }
                assertArrayEquals(bytes, original.readBytes())
                assertTrue(original.delete()) // Only this fixture; force the actual restore to re-create it.
                val restored = helper.restoreFromBackupFile(archive, restoreMonicaConfig = false, importDataOnly = true).getOrThrow()
                try {
                    val values = restored.content.passwords.associate { it.title to (it.customIconType to it.customIconValue) }
                    assertEquals(entries.associate { it.title to (it.customIconType to it.customIconValue) }, values)
                    assertEquals(name, checkNotNull(PasswordCustomIconStore.resolveIconFile(context, name)).name)
                    assertArrayEquals(bytes, original.readBytes())
                    val decoded = checkNotNull(BitmapFactory.decodeFile(original.absolutePath))
                    try { assertPixels("restored PNG", expected, decoded) } finally { decoded.recycle() }
                } finally { restored.content.portableAttachments?.close() }
            } finally { archive.delete() }
        } finally { expected.recycle() }
    }

    @Test fun bundledAutoMatchColdAndWarmNeverRequestsFaviconFallback() {
        var slug by mutableStateOf("github")
        val resolved = AtomicReference<AutoMatchedSimpleIcon?>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                val match = rememberAutoMatchedSimpleIcon("https://$slug.example", tintColor = Color.Black)
                SideEffect { resolved.set(match) }
                MaterialTheme { VaultItemIcon("https://$slug.example", slug, "", PASSWORD_ICON_TYPE_NONE, null, Icons.Default.Key) }
            }
        }
        for (requested in listOf("github", "monica", "github", "monica")) {
            compose.runOnIdle { slug = requested }
            compose.waitUntil(10_000) {
                resolved.get()?.let { it.resolved && it.slug == requested && it.bitmap != null } == true
            }
            compose.runOnIdle { assertEquals("$requested must stay local", 0, faviconAttempts.get()) }
        }
    }
}

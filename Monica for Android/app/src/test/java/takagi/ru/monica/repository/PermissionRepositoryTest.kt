package takagi.ru.monica.repository

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import takagi.ru.monica.data.model.*
import takagi.ru.monica.ui.screens.*
import takagi.ru.monica.util.PhotoPickerHelper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 32, 34], application = Application::class)
class PermissionRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Suppress("DEPRECATION")
    private fun declared() = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
    private fun all() = PermissionRepository(context).getAllPermissions(true)

    @Test fun inventoryExactlyCoversInstalledDeclarationsAndKeepsBindingProtectionSeparate() {
        val list = all()
        assertEquals(declared(), list.filter { it.requested }.map { it.androidPermission }.toSet())
        assertEquals(list.size, list.map { it.id }.toSet().size)
        listOf(Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.BIND_AUTOFILL_SERVICE).forEach { assertFalse("Unexpected $it at API ${Build.VERSION.SDK_INT}: ${declared()}",it in declared()) }
        // Android 8/9 derives legacy read access from the retained maxSdk28 write permission.
        assertEquals(Build.VERSION.SDK_INT<=28,Manifest.permission.READ_EXTERNAL_STORAGE in declared())
        assertTrue(Manifest.permission.NFC in declared())
        assertTrue(Manifest.permission.USE_FINGERPRINT in declared())
        assertTrue(list.any { it.id == "AUTOFILL" && !it.requested && it.androidPermission == Manifest.permission.BIND_AUTOFILL_SERVICE })
        assertTrue(list.any { it.id == "IME" && !it.requested })
    }

    @Test fun authorizationUsesOnlyDeclaredPermissionsAndRequestsCoarseWithFine() {
        val list = all()
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            list.first { it.id == "ACCESS_FINE_LOCATION" }.runtimePermissions)
        list.flatMap { it.runtimePermissions }.forEach { assertTrue(it in declared()) }
        list.filter { it.category != PermissionCategory.RUNTIME }.forEach { assertTrue(it.runtimePermissions.isEmpty()) }
        val camera = list.first { it.id == "CAMERA" }.copy(status = PermissionStatus.DENIED)
        assertEquals(PermissionClickAction.REQUEST_RUNTIME_PERMISSION, resolvePermissionClickAction(camera))
        assertEquals(PermissionClickAction.OPEN_APP_SETTINGS, resolvePermissionClickAction(camera.copy(status = PermissionStatus.GRANTED)))
    }

    @Test fun notificationStateRespectsSystemDisablementEvenBeforeAndroid13() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(PermissionStatus.DENIED, all().first { it.id == "NOTIFICATION" }.status)
        shadowOf(manager).setNotificationsEnabled(true)
        assertEquals(PermissionStatus.GRANTED, all().first { it.id == "NOTIFICATION" }.status)
        val notification = all().first { it.id == "NOTIFICATION" }.copy(status = PermissionStatus.DENIED)
        assertEquals(if (Build.VERSION.SDK_INT >= 33) PermissionClickAction.REQUEST_RUNTIME_PERMISSION else PermissionClickAction.OPEN_APP_SETTINGS,
            resolvePermissionClickAction(notification))
    }

    @Test fun overlayRefreshAndDedicatedSettingsNeverUseRuntimeRequests() {
        ShadowSettings.setCanDrawOverlays(false)
        assertEquals(PermissionStatus.DENIED, all().first { it.id == "OVERLAY" }.status)
        ShadowSettings.setCanDrawOverlays(true)
        assertEquals(PermissionStatus.GRANTED, all().first { it.id == "OVERLAY" }.status)
        val overlay = all().first { it.id == "OVERLAY" }
        assertEquals(PermissionClickAction.OPEN_APP_SETTINGS, resolvePermissionClickAction(overlay))
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, createPermissionSettingsIntent(context, overlay.settingsTarget).action)
        assertEquals("package:${context.packageName}", createPermissionSettingsIntent(context, overlay.settingsTarget).data.toString())
        assertEquals(Settings.ACTION_INPUT_METHOD_SETTINGS, createPermissionSettingsIntent(context, PermissionSettingsTarget.IME).action)
    }

    @Test fun versionSpecificDeclarationsCannotRequestOnUnsupportedAndroid() {
        for (p in all().filter { Build.VERSION.SDK_INT < it.minSdk || it.maxSdk?.let { max -> Build.VERSION.SDK_INT > max } == true }) {
            assertEquals(p.androidPermission, PermissionStatus.UNAVAILABLE, p.status)
            assertEquals(PermissionClickAction.IGNORE, resolvePermissionClickAction(p))
        }
        assertTrue(all().first { it.id == "NFC" }.runtimePermissions.isEmpty())
    }

    @Suppress("DEPRECATION")
    @Test fun unexpectedDependencyDeclarationRemainsVisibleWithoutBlindRequest() {
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val names = listOf("fixture.permission.FUTURE", "other.permission.FUTURE", "fixture.permission.CAMERA", "CAMERA")
        pkg.requestedPermissions = (pkg.requestedPermissions.orEmpty().toList() + names).toTypedArray()
        shadowOf(context.packageManager).installPackage(pkg)
        val list = all()
        assertEquals(list.size, list.map { it.id }.toSet().size)
        for (name in names) {
            val p = list.first { it.androidPermission == name }
            assertEquals("PERMISSION:$name", p.id)
            assertEquals(takagi.ru.monica.R.string.pm_other, p.descriptionResId)
            assertTrue(p.requested)
            assertTrue(p.runtimePermissions.isEmpty())
            assertNotEquals(PermissionClickAction.REQUEST_RUNTIME_PERMISSION, resolvePermissionClickAction(p))
        }
        val camera = list.first { it.androidPermission == Manifest.permission.CAMERA }
        assertEquals("CAMERA", camera.id)
        assertEquals(listOf(Manifest.permission.CAMERA), camera.runtimePermissions)
    }

    @Test fun galleryUsesOpenableSingleImageUriWithoutExplicitGalleryOrBroadGrant() {
        val intent = PhotoPickerHelper.galleryIntent()
        assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("image/*", intent.type)
        assertTrue(intent.hasCategory(android.content.Intent.CATEGORY_OPENABLE))
        assertNull(intent.component)
        assertNull(intent.data)
        assertEquals(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
    }

    @Test fun approximateConsentIsReportedWithoutAcceptingUnrelatedGrants() {
        val location=all().first{it.id=="ACCESS_FINE_LOCATION"}
        assertEquals(Manifest.permission.ACCESS_COARSE_LOCATION,runtimeGrantedPermission(location,mapOf(
            Manifest.permission.ACCESS_FINE_LOCATION to false,Manifest.permission.ACCESS_COARSE_LOCATION to true)))
        assertNull(runtimeGrantedPermission(location,mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false)))
        assertNull(runtimeGrantedPermission(all().first{it.id=="CAMERA"},mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true)))
    }
}

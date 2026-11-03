package takagi.ru.monica.util

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.MainActivity

class PhotoPickerPermissionInstrumentedTest {
    @Test fun systemChooserGrantsOnlySelectedImageAndLegacyCallbackCopiesItsOriginalBytes() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val authority="${inst.context.packageName}.permission-photos"
        val selected=DocumentsContract.buildDocumentUri(authority,"selected.png")
        val unselected=DocumentsContract.buildDocumentUri(authority,"unselected.png")
        for(p in listOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.READ_MEDIA_IMAGES))
            assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(p))
        assertTrue(runCatching { context.contentResolver.openInputStream(unselected)?.close() }.isFailure)
        val path=AtomicReference<String?>();val error=AtomicReference<String?>();val done=CountDownLatch(1)
        val system=PermissionTestSystemUi()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    PhotoPickerHelper.setCallback(activity,object:PhotoPickerHelper.PhotoPickerCallback {
                        override fun onPhotoSelected(imagePath:String?){path.set(imagePath);done.countDown()}
                        override fun onError(value:String){error.set(value);done.countDown()}
                    })
                    PhotoPickerHelper.pickFromGallery(activity)
                }
                system.await("System document chooser not shown"){system.hasDocumentPicker()}
                if(system.find("Monica Photo Permission Tests")==null)system.click("Show roots")
                system.click("Monica Photo Permission Tests")
                system.tap("selected.png")
                assertTrue("Gallery result returned",done.await(10,TimeUnit.SECONDS));assertNull(error.get());assertNotNull(path.get())
                val copied=File(path.get()!!).readBytes()
                assertArrayEquals(context.contentResolver.openInputStream(selected)!!.use{it.readBytes()},copied)
                val bitmap=BitmapFactory.decodeByteArray(copied,0,copied.size)
                assertEquals(Color.RED,bitmap.getPixel(0,0));bitmap.recycle()
                assertTrue("Unselected image must remain unreadable",runCatching {context.contentResolver.openInputStream(unselected)?.close()}.isFailure)
                for(p in listOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.READ_MEDIA_IMAGES))
                    assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(p))
            }
        } finally {
            system.close()
            path.get()?.let{File(it).delete()}
            PhotoPickerHelper.setCallback(context,object:PhotoPickerHelper.PhotoPickerCallback {
                override fun onPhotoSelected(imagePath:String?){}
                override fun onError(error:String){}
            })
        }
    }
}

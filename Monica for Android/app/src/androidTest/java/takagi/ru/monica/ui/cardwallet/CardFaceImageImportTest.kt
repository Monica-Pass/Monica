package takagi.ru.monica.ui.cardwallet

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardFaceImageImportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun source(kind: String) = Uri.parse("content://takagi.ru.monica.test.cardface.images/$kind/${UUID.randomUUID()}")

    @Test
    fun importsRealMegabyteJpegFromUnknownSizeStreamThatCannotBeReopened() = runBlocking {
        val prepared = CardFaceImageProcessor.prepare(context, source("jpeg")).getOrThrow()
        try {
            assertTrue(prepared.preview.width <= 1280)
            assertTrue(abs(prepared.preview.width.toFloat() / prepared.preview.height - CardFaceImageProcessor.CARD_ASPECT_RATIO) < 0.005f)
            assertEquals(0xff, prepared.bytes[0].toInt() and 0xff)
            assertEquals(0xd8, prepared.bytes[1].toInt() and 0xff)
            val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)
            assertEquals(prepared.preview.width, decoded.width)
            assertEquals(prepared.preview.height, decoded.height)
            decoded.recycle()
        } finally {
            prepared.bytes.fill(0)
            prepared.preview.recycle()
        }
    }

    @Test
    fun preservesCameraExifOrientationBeforeCropping() = runBlocking {
        val prepared = CardFaceImageProcessor.prepare(context, source("rotated")).getOrThrow()
        try {
            val left = prepared.preview.getPixel(prepared.preview.width / 4, prepared.preview.height / 2)
            val right = prepared.preview.getPixel(prepared.preview.width * 3 / 4, prepared.preview.height / 2)
            assertTrue(Color.blue(left) > Color.red(left))
            assertTrue(Color.red(right) > Color.blue(right))
        } finally {
            prepared.bytes.fill(0)
            prepared.preview.recycle()
        }
    }

    @Test
    fun reportsUnsupportedFormatSeparatelyFromUnreadableSource() = runBlocking {
        val unsupported = CardFaceImageProcessor.prepare(context, source("gif")).exceptionOrNull()
        assertEquals(CardFaceImageProcessor.Failure.UNSUPPORTED, (unsupported as CardFaceImageProcessor.ImportException).reason)
        val unreadable = CardFaceImageProcessor.prepare(context, source("missing")).exceptionOrNull()
        assertEquals(CardFaceImageProcessor.Failure.UNREADABLE, (unreadable as CardFaceImageProcessor.ImportException).reason)
    }
}

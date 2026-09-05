package takagi.ru.monica.ui.cardwallet

import kotlin.math.abs
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFaceImageProcessorTest {
    @Test
    fun boundedSourceReadAcceptsOneMegabyteWithoutDescriptorMetadata() {
        val source = ByteArray(1024 * 1024) { index -> (index and 0xff).toByte() }

        val copied = CardFaceImageProcessor.readBoundedBytes(
            input = ByteArrayInputStream(source),
            maxBytes = 25L * 1024L * 1024L
        )

        assertTrue(copied?.contentEquals(source) == true)
    }

    @Test
    fun boundedSourceReadRejectsPayloadOverImageLimit() {
        val source = ByteArray(25 * 1024 + 1)

        val copied = CardFaceImageProcessor.readBoundedBytes(
            input = ByteArrayInputStream(source),
            maxBytes = 25L * 1024L
        )

        assertEquals(null, copied)
    }

    @Test
    fun wideImageIsCenterCroppedToBankCardRatio() {
        val (width, height) = CardFaceImageProcessor.centerCropSize(
            width = 2400,
            height = 1000,
            aspectRatio = CardFaceImageProcessor.CARD_ASPECT_RATIO
        )

        assertEquals(1000, height)
        assertTrue(width < 2400)
        assertTrue(abs(width.toFloat() / height - CardFaceImageProcessor.CARD_ASPECT_RATIO) < 0.002f)
    }

    @Test
    fun portraitImageIsCenterCroppedToBankCardRatio() {
        val (width, height) = CardFaceImageProcessor.centerCropSize(
            width = 1000,
            height = 2400,
            aspectRatio = CardFaceImageProcessor.CARD_ASPECT_RATIO
        )

        assertEquals(1000, width)
        assertTrue(height < 2400)
        assertTrue(abs(width.toFloat() / height - CardFaceImageProcessor.CARD_ASPECT_RATIO) < 0.002f)
    }
}

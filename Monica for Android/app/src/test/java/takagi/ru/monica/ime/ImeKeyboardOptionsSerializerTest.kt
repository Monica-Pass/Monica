package takagi.ru.monica.ime

import androidx.datastore.core.CorruptionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ImeKeyboardOptionsSerializerTest {
    @Test fun oldSettingsKeepPinOptionsAndLeaveVibrationOff() = runBlocking {
        for (shuffle in 0..1) for (preview in 0..1) {
            val restored = ImeKeyboardOptionsSerializer.readFrom(ByteArrayInputStream(byteArrayOf(1, shuffle.toByte(), preview.toByte())))
            assertEquals(ImeKeyboardOptions(shuffle == 1, preview == 1, false), restored)
        }
    }

    @Test fun everyCombinationSurvivesWritingAndReading() = runBlocking {
        for (shuffle in listOf(false, true)) for (preview in listOf(false, true)) for (vibration in listOf(false, true)) {
            val options = ImeKeyboardOptions(shuffle, preview, vibration)
            val output = ByteArrayOutputStream()
            ImeKeyboardOptionsSerializer.writeTo(options, output)
            assertEquals(options, ImeKeyboardOptionsSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
        }
    }

    @Test fun truncatedInvalidAndUnknownVersionsAreRejected() = runBlocking {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(2, 1, 1), byteArrayOf(3, 0, 0, 0),
            byteArrayOf(2, 0, 0, 2), byteArrayOf(1, 1, 1, 0), byteArrayOf(2, 0, 0, 0, 0))) {
            try {
                ImeKeyboardOptionsSerializer.readFrom(ByteArrayInputStream(bytes))
                fail("Accepted malformed keyboard options")
            } catch (_: CorruptionException) { }
        }
    }
}

package takagi.ru.monica.ime

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.InputStream
import java.io.OutputStream

/** The settings activity and :ime service must observe the same persisted options. */
internal object ImeKeyboardPreferences {
    @Volatile private var instance: DataStore<ImeKeyboardOptions>? = null

    fun get(context: Context): DataStore<ImeKeyboardOptions> = instance ?: synchronized(this) {
        instance ?: MultiProcessDataStoreFactory.create(
            serializer = ImeKeyboardOptionsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { ImeKeyboardOptions() },
            produceFile = { context.applicationContext.filesDir.resolve("datastore/ime_keyboard_options.bin") },
        ).also { instance = it }
    }

}

internal object ImeKeyboardOptionsSerializer : Serializer<ImeKeyboardOptions> {
    override val defaultValue = ImeKeyboardOptions()

    override suspend fun readFrom(input: InputStream): ImeKeyboardOptions {
        val version = input.read()
        val shuffle = input.read()
        val hidePreview = input.read()
        val vibration = if (version == 2) input.read() else 0
        if (version !in 1..2 || shuffle !in 0..1 || hidePreview !in 0..1 ||
            vibration !in 0..1 || input.read() != -1) {
            throw CorruptionException("Invalid keyboard options")
        }
        return ImeKeyboardOptions(shuffle == 1, hidePreview == 1, vibration == 1)
    }

    override suspend fun writeTo(t: ImeKeyboardOptions, output: OutputStream) {
        output.write(byteArrayOf(2, if (t.scramblePin) 1 else 0, if (t.hidePinPreview) 1 else 0,
            if (t.keyVibrationEnabled) 1 else 0))
    }
}

package takagi.ru.monica.autofill_ng

import android.os.Bundle

/** An absent discriminator belongs to the old KDBX protocol; an unknown one grants nothing. */
internal fun Bundle.nativeAutofillStorage(key: String): NativeAutofillStorage? {
    if (!containsKey(key)) return NativeAutofillStorage.KEEPASS
    val name = runCatching { getString(key) }.getOrNull() ?: return null
    return NativeAutofillStorage.entries.singleOrNull { it.name == name }
}

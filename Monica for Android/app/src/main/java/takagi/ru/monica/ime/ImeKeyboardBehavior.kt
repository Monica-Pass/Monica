package takagi.ru.monica.ime

import android.text.InputType
import java.security.SecureRandom
import java.util.Collections
import java.util.Random

data class ImeKeyboardOptions(val scramblePin: Boolean = false, val hidePinPreview: Boolean = false)

internal val StandardImePinDigits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

/** Called at session/mode boundaries, never after a digit is committed. */
internal fun createImePinDigits(scramble: Boolean, random: Random = SecureRandom()): List<String> =
    if (!scramble) StandardImePinDigits else StandardImePinDigits.toMutableList().also { Collections.shuffle(it, random) }

internal fun imeModeForInputType(inputType: Int): MonicaKeyboardMode = when (inputType and InputType.TYPE_MASK_CLASS) {
    InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> MonicaKeyboardMode.NUMBERS
    else -> MonicaKeyboardMode.LETTERS
}

internal fun imeInputIsSensitive(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        else -> false
    }
}

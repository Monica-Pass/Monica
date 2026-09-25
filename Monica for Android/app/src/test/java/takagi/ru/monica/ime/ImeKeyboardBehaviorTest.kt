package takagi.ru.monica.ime

import android.text.InputType
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class ImeKeyboardBehaviorTest {
    @Test fun defaultsAndDisabledShuffleKeepTheFamiliarOrder() {
        assertEquals(ImeKeyboardOptions(false, false), ImeKeyboardOptions())
        repeat(10) { assertEquals(StandardImePinDigits, createImePinDigits(false)) }
    }

    @Test fun scramblingIsAlwaysAPermutationWithZeroAppearingExactlyOnce() {
        val random = Random(41)
        val layouts = List(100) { createImePinDigits(true, random) }
        layouts.forEach { assertEquals((0..9).map(Int::toString), it.sorted()) }
        assertTrue(layouts.toSet().size > 90)
    }

    @Test fun numericAndPhoneFieldsUseTheNumberPadWhileSearchUsesLetters() {
        assertEquals(MonicaKeyboardMode.NUMBERS, imeModeForInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL))
        assertEquals(MonicaKeyboardMode.NUMBERS, imeModeForInputType(InputType.TYPE_CLASS_PHONE))
        assertEquals(MonicaKeyboardMode.LETTERS, imeModeForInputType(InputType.TYPE_CLASS_TEXT))
        assertTrue(imeInputIsSensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertTrue(imeInputIsSensitive(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertFalse(imeInputIsSensitive(InputType.TYPE_CLASS_NUMBER))
    }
}

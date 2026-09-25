package takagi.ru.monica.ime

import org.junit.Assert.*
import org.junit.Test

class ImeKeyGeometryTest {
    @Test fun everyRowAlignsOnNarrowPhonesAndWideScreens() {
        listOf(304f, 344f, 396f, 584f).forEach { width ->
            val keys = ImeKeyGeometry(width)
            assertEquals(width, keys.letter * 10 + keys.gap * 9, 0.001f)
            assertEquals(width, keys.letter * 9 + keys.gap * 8 + keys.homeInset * 2, 0.001f)
            assertEquals(width, keys.letter * 7 + keys.action * 2 + keys.gap * 8, 0.001f)
            assertEquals(width, keys.space + keys.action * 2 + keys.letter * 2 + keys.gap * 4, 0.001f)
            assertEquals(width, keys.number * 4 + keys.gap * 3, 0.001f)
            assertTrue(keys.letter > 24 && keys.action > keys.letter && keys.space > keys.action)
        }
    }
}

package takagi.ru.monica.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class Schema85SavedStateTest {
    @Test fun retiredTimelineSavedStateDecodesWithoutReopeningARemovedRoute() {
        val saved = ByteArrayOutputStream()
        ObjectOutputStream(saved).use { it.writeObject(PasswordHistoryPageMode.valueOf("TIMELINE")) }
        val restored = ObjectInputStream(ByteArrayInputStream(saved.toByteArray())).use { it.readObject() as PasswordHistoryPageMode }
        assertFalse(restored.isVisible)
        assertFalse(PasswordHistoryPageMode.NONE.isVisible)
        assertTrue(PasswordHistoryPageMode.TRASH.isVisible)
    }
}

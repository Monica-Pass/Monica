package takagi.ru.monica.data

import org.junit.Assert.*
import org.junit.Test

class DatabaseDisplayOrderTest {
    @Test fun filteredSourceKeepsOtherSourcesInTheirSlots() {
        assertEquals(listOf(5L, 2L, 1L, 4L, 3L), mergeDatabaseDisplayOrder(listOf(1L,2L,3L,4L,5L), listOf(5L,1L,3L)))
    }
    @Test fun deletedAndNewlyAddedRowsAreNotRecreatedOrDropped() {
        assertEquals(listOf(3L, 4L, 1L, 5L), mergeDatabaseDisplayOrder(listOf(1L,4L,3L,5L), listOf(3L,2L,1L)))
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateIdsRejectTheWholeOperation() {
        mergeDatabaseDisplayOrder(listOf(1L,2L), listOf(1L,1L))
    }
}

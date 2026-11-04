package takagi.ru.monica.ui.screens

import java.util.concurrent.atomic.AtomicInteger
import takagi.ru.monica.credentialexchange.ImportDestination

/** One authentication attempt. Old biometric callbacks cannot authorize a replacement request. */
internal class BitwardenJsonExportRequest(
    val source: ImportDestination,
    val option: ExportOption,
    val lockGeneration: Long,
) {
    private val state = AtomicInteger(0)
    val active: Boolean get() = state.get() in 0..2
    val choosingDocument: Boolean get() = state.get() == 2
    fun authenticated(): Boolean = state.compareAndSet(0, 1)
    fun chooseDocument(): Boolean = state.compareAndSet(1, 2)
    fun returnDocument(): Boolean = state.compareAndSet(2, 3)
    fun revoke() { state.set(4) }
}

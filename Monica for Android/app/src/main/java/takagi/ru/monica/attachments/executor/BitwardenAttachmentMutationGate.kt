package takagi.ru.monica.attachments.executor

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One ownership gate for upload, adoption, metadata reconciliation and allocation cleanup.
 * Never enter twice: guarded public operations delegate to unguarded private helpers.
 * No Room/SessionManager publication lock is held while waiting or doing network work.
 */
object BitwardenAttachmentMutationGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(context: Context, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val parent = context.applicationContext.filesDir.canonicalFile
            val path = File(parent, "bitwarden_attachment_mutations.lock")
            check(path.canonicalFile == path)
            RandomAccessFile(path, "rw").use { file ->
                file.channel.lock().use {
                    currentCoroutineContext().ensureActive()
                    action()
                }
            }
        }
    }
}

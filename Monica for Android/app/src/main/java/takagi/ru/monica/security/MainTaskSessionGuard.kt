package takagi.ru.monica.security

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

/** Revokes main and secondary access when the user removes a main-app task.
 * Backgrounding and configuration recreation retain the same task and do not revoke it.
 * All task queries concern only this application's own tasks; no polling service is needed.
 */
object MainTaskSessionGuard {
    @Suppress("DEPRECATION")
    private fun taskId(info: ActivityManager.RecentTaskInfo): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) info.taskId else info.id

    private val lock = Any()
    private val mainTaskIds = mutableSetOf<Int>()

    /** Discover a task left in Recents before an autofill-only process restart. */
    fun initialize(context: Context) {
        val existing = runCatching {
            context.getSystemService(ActivityManager::class.java).appTasks.mapNotNull { task ->
                val info = requireNotNull(task.taskInfo)
                val component = info.baseActivity ?: info.baseIntent.component
                if (component != null && (component.className == "takagi.ru.monica.MainActivity" ||
                    context.packageManager.getActivityInfo(component,
                        android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS).targetActivity ==
                        "takagi.ru.monica.MainActivity")) taskId(info) else null
            }
        }.getOrDefault(emptyList())
        synchronized(lock) { mainTaskIds.addAll(existing) }
    }

    fun onMainTaskCreated(activity: Activity) {
        revokeIfTaskRemoved(activity)
        val newTask = synchronized(lock) { mainTaskIds.add(activity.taskId) }
        if (newTask) SessionManager.markLocked()
    }

    fun onMainTaskDestroyed(activity: Activity) {
        if (!activity.isFinishing || activity.isChangingConfigurations) return
        val removed = synchronized(lock) { mainTaskIds.remove(activity.taskId) }
        if (removed) SessionManager.markLocked()
    }

    fun revokeIfTaskRemoved(context: Context): Boolean {
        val tracked = synchronized(lock) { mainTaskIds.toSet() }
        if (tracked.isEmpty()) return false
        val current = runCatching {
            context.getSystemService(ActivityManager::class.java).appTasks.map { taskId(requireNotNull(it.taskInfo)) }.toSet()
        }.getOrDefault(emptySet())
        val removed = tracked - current
        if (removed.isEmpty()) return false
        // Never hold our monitor while clearing sessions: the grant store can call us.
        synchronized(lock) { mainTaskIds.removeAll(removed) }
        SessionManager.markLocked()
        return true
    }
}

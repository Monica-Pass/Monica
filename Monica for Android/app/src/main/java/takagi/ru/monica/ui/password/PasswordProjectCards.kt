package takagi.ru.monica.ui

import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.ui.password.getPasswordInfoKey

/** Unstack projects, never the password rows within an explicitly identified project.
 * Keep the existing first-occurrence order and singleton keys; matching content is not identity.
 */
internal fun buildUnstackedPasswordProjectGroups(
    entries: List<PasswordEntry>,
): Map<String, List<PasswordEntry>> = entries
    .groupBy(::getPasswordInfoKey)
    .values
    .associate { project -> "entry_${project.first().id}" to project }

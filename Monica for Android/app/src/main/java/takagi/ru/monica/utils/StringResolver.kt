package takagi.ru.monica.utils

import androidx.annotation.StringRes

/** Resolves display text without coupling presentation models to an Android Context. */
internal fun interface StringResolver {
    fun get(@StringRes resourceId: Int, vararg arguments: Any): String
}

package takagi.ru.monica.ui.model

import takagi.ru.monica.domain.provider.PasswordSource

sealed class SecretValueState {
    data class Available(val value: String) : SecretValueState()
    object Empty : SecretValueState()
    data class Unreadable(val source: PasswordSource, val reason: PasswordReadFailure = PasswordReadFailure.UNKNOWN) : SecretValueState()
    object Hidden : SecretValueState()
}

fun SecretValueState.plainValueOrEmpty(): String {
    return when (this) {
        is SecretValueState.Available -> value
        SecretValueState.Empty -> ""
        SecretValueState.Hidden -> ""
        is SecretValueState.Unreadable -> ""
    }
}

fun SecretValueState.isUnreadable(): Boolean = this is SecretValueState.Unreadable

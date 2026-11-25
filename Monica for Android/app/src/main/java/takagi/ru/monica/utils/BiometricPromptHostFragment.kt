package takagi.ru.monica.utils

import androidx.fragment.app.Fragment

/** One request's AndroidX biometric ViewModel owner. Contains no UI or stored credentials. */
class BiometricPromptHostFragment : Fragment() {
    internal var onHostDestroyed: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        // Framework restoration never restores the application's authorization callback.
        // Remove a restored empty host instead of reviving an old authentication request.
        if (onHostDestroyed == null) {
            parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
        }
    }

    override fun onDestroy() {
        val callback = onHostDestroyed
        onHostDestroyed = null
        callback?.invoke()
        super.onDestroy()
    }
}

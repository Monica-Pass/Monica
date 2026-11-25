package takagi.ru.monica.utils

import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.FragmentActivity

/** Empty host for real AndroidX prompt lifecycle regressions; never opens a user's vault. */
class BiometricPromptTestActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTurnScreenOn(true)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Biometric lifecycle regression fixture" })
    }
}

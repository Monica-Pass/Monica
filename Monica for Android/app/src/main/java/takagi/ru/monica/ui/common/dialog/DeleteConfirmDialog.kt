package takagi.ru.monica.ui.common.dialog

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import takagi.ru.monica.R
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.ui.components.SecondaryPasswordVerificationDialog
import takagi.ru.monica.ui.rememberUiSecurityManager
import takagi.ru.monica.utils.BiometricHelper

/**
 * 支持指纹验证的删除确认对话框
 *
 * @param itemTitle 要删除的项目标题
 * @param itemType 项目类型描述（如"密码"、"验证器"、"证件"）
 * @param onDismiss 取消删除的回调
 * @param onPasswordVerified 密码验证及最终授权门控通过后的回调
 * @param onConfirmWithBiometric 使用指纹确认删除的回调
 */
@Composable
fun DeleteConfirmDialog(
    itemTitle: String,
    itemType: String = "Item",
    biometricEnabled: Boolean,
    skipIdentityVerification: Boolean = false,
    onDismiss: () -> Unit,
    onConfirmWithoutVerification: () -> Unit = {},
    onPasswordVerified: () -> Unit,
    onConfirmWithBiometric: () -> Unit,
    isRequestCurrent: () -> Boolean = { true },
    title: String = stringResource(R.string.delete_item_title, itemType),
    message: String = stringResource(R.string.delete_item_message, itemType, itemTitle),
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val manager = rememberUiSecurityManager()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val generation = remember(manager) { SecurityManager.dataReadGeneration.value }
    val active = remember(manager) { AtomicBoolean(true) }
    val requestCurrent = rememberUpdatedState(isRequestCurrent)
    DisposableEffect(manager) { onDispose { active.set(false) } }
    fun accept(action: () -> Unit) {
        if (manager.isDataReadCurrent(generation) && requestCurrent.value() &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == false &&
            active.compareAndSet(true, false)
        ) action()
    }
    val dismiss = { active.set(false); onDismiss() }
    val biometricHelper = remember { BiometricHelper(context) }
    val isBiometricAvailable = remember(biometricEnabled) {
        biometricEnabled && biometricHelper.isBiometricAvailable()
    }

    val biometricAction = if (!skipIdentityVerification && isBiometricAvailable && activity != null) {
        {
            biometricHelper.authenticate(
                activity = activity,
                title = context.getString(R.string.verify_identity),
                subtitle = context.getString(R.string.verify_to_delete),
                description = context.getString(R.string.biometric_login_description),
                onSuccess = {
                    accept(onConfirmWithBiometric)
                },
                onError = { error ->
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                },
                onFailed = {}
            )
        }
    } else {
        null
    }
    SecondaryPasswordVerificationDialog(
        manager = manager,
        title = title,
        message = message,
        onDismiss = dismiss,
        onSuccess = { accept(if (skipIdentityVerification) onConfirmWithoutVerification else onPasswordVerified) },
        isRequestCurrent = { active.get() && requestCurrent.value() },
        requirePassword = !skipIdentityVerification,
        confirmText = stringResource(R.string.delete),
        icon = Icons.Default.Warning,
        destructiveConfirm = true,
        onBiometricClick = biometricAction,
        biometricHintText = if (biometricAction == null) context.getString(R.string.biometric_not_available) else null,
    )
}

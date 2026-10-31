package takagi.ru.monica.autofill_ng

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import takagi.ru.monica.R

@Composable
fun HyperOsSaveOverlayPermission(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = Settings.canDrawOverlays(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!granted) TextButton(modifier = Modifier.testTag("hyperos_save_overlay_permission"), onClick = {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        try { context.startActivity(intent) }
        catch (_: Exception) { Toast.makeText(context, R.string.autofill_save_overlay_permission_required, Toast.LENGTH_LONG).show() }
    }) { Text(stringResource(R.string.autofill_save_overlay_permission_action)) }
}

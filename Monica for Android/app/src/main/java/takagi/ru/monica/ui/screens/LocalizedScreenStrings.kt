package takagi.ru.monica.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import takagi.ru.monica.utils.StringResolver

@Composable
internal fun rememberScreenStrings(): StringResolver {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        StringResolver { id, arguments ->
            if (arguments.isEmpty()) context.getString(id)
            else context.getString(id, *arguments)
        }
    }
}

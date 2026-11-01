package takagi.ru.monica.ui.main.navigation

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** Keep the regional Portuguese short labels together in narrow navigation items. */
@Composable
fun DockLabel(text: String) {
    val isPortuguese = LocalConfiguration.current.locales[0].language == "pt"
    if (isPortuguese) {
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            // SP still follows the user's font scale; avoid splitting a word at large text.
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp),
        )
    } else {
        Text(text = text, maxLines = 2, overflow = TextOverflow.Clip)
    }
}

package takagi.ru.monica.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import takagi.ru.monica.autofill_ng.ui.isWebAddress
import takagi.ru.monica.autofill_ng.ui.rememberAppIcon
import takagi.ru.monica.autofill_ng.ui.rememberFavicon
import takagi.ru.monica.data.UnmatchedIconHandlingStrategy

/** The vault list and overview share icon precedence, background loading and memory caches. */
@Composable
internal fun VaultItemIcon(
    website: String,
    title: String,
    appPackageName: String,
    customIconType: String,
    customIconValue: String?,
    defaultIcon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val simpleIcon = if (customIconType == PASSWORD_ICON_TYPE_SIMPLE) {
        rememberSimpleIconBitmap(customIconValue, MaterialTheme.colorScheme.primary)
    } else null
    val uploadedIcon = if (customIconType == PASSWORD_ICON_TYPE_UPLOADED) {
        rememberUploadedPasswordIcon(customIconValue)
    } else null
    val emoji = customIconValue.takeIf { customIconType == PASSWORD_ICON_TYPE_EMOJI }
    val autoMatchedSimpleIcon = rememberAutoMatchedSimpleIcon(
        website = website,
        title = title,
        appPackageName = appPackageName.ifBlank { null },
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = customIconType == PASSWORD_ICON_TYPE_NONE,
    )
    val favicon = if (website.isNotBlank()) {
        rememberFavicon(
            url = website,
            enabled = autoMatchedSimpleIcon.resolved && autoMatchedSimpleIcon.slug == null,
        )
    } else null
    val appIcon = if (appPackageName.isNotBlank() && !isWebAddress(website)) {
        rememberAppIcon(appPackageName)
    } else null

    // Reserve the same space during async loading so titles never shift when an icon arrives.
    Box(modifier.size(40.dp)) {
        val brandIcon = simpleIcon ?: uploadedIcon ?: autoMatchedSimpleIcon.bitmap
        val platformIcon = favicon ?: appIcon
        when {
            emoji != null -> EmojiIconText(
                emoji = emoji,
                size = 28.dp,
                modifier = Modifier.fillMaxSize()
            )
            brandIcon != null -> Image(
                bitmap = brandIcon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(2.dp),
            )
            platformIcon != null -> Image(
                bitmap = platformIcon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
            else -> UnmatchedIconFallback(
                strategy = UnmatchedIconHandlingStrategy.DEFAULT_ICON,
                primaryText = website,
                secondaryText = title,
                defaultIcon = defaultIcon,
                iconSize = 40.dp,
            )
        }
    }
}

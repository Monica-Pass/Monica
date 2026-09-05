package takagi.ru.monica.ui.cardwallet

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import takagi.ru.monica.R
import takagi.ru.monica.data.model.BankCardData
import takagi.ru.monica.data.model.BillingAddressData
import takagi.ru.monica.data.model.CardBrand
import takagi.ru.monica.data.model.CardBrandDetector
import takagi.ru.monica.data.model.CardFaceDisplayMode
import takagi.ru.monica.data.model.DocumentData
import takagi.ru.monica.data.model.DocumentType
import takagi.ru.monica.ui.components.MonicaItemCardShape

/** Display content only. Images and persistence stay outside the drawing component. */
data class CardFacePreviewData(
    val title: String,
    val subtitle: String = "",
    val identifier: String = "",
    val footerStart: String = "",
    val footerEnd: String = "",
    val identifierLabel: String = "",
    val maskIdentifier: Boolean = true,
    val brand: CardBrand? = null,
    val icon: ImageVector = Icons.Default.CreditCard
)

@Composable
fun bankCardFacePreviewData(title: String, data: BankCardData) = CardFacePreviewData(
    title = title,
    subtitle = data.bankName,
    identifier = data.cardNumber,
    footerStart = data.cardholderName,
    footerEnd = listOf(data.expiryMonth, data.expiryYear).filter(String::isNotBlank).joinToString("/"),
    identifierLabel = stringResource(R.string.card_face_identifier_bank),
    brand = CardBrandDetector.detectStoredCard(
        number = data.cardNumber,
        storedBrand = listOf(data.brand, title, data.nickname, data.bankName).joinToString(" ")
    )
)

@Composable
fun documentCardFacePreviewData(title: String, data: DocumentData) = CardFacePreviewData(
    title = title.ifBlank {
        stringResource(when (data.documentType) {
            DocumentType.ID_CARD -> R.string.id_card
            DocumentType.PASSPORT -> R.string.passport
            DocumentType.DRIVER_LICENSE -> R.string.drivers_license
            DocumentType.SOCIAL_SECURITY -> R.string.social_security_card
            DocumentType.OTHER -> R.string.other_document
        })
    },
    subtitle = data.issuedBy,
    identifier = data.documentNumber,
    footerStart = data.fullName,
    footerEnd = data.expiryDate,
    identifierLabel = stringResource(R.string.card_face_identifier_document),
    icon = Icons.Default.Badge
)

@Composable
fun billingAddressCardFacePreviewData(title: String, data: BillingAddressData) = CardFacePreviewData(
    title = title,
    subtitle = data.fullName,
    identifier = listOf(data.streetAddress, data.apartment).filter(String::isNotBlank).joinToString(" "),
    footerStart = listOf(data.city, data.stateProvince).filter(String::isNotBlank).joinToString(" "),
    footerEnd = listOf(data.postalCode, data.country).filter(String::isNotBlank).joinToString(" "),
    identifierLabel = stringResource(R.string.card_face_identifier_address),
    maskIdentifier = false,
    icon = Icons.Default.Home
)

@Composable
fun CardFaceArtwork(
    previewData: CardFacePreviewData,
    bitmap: Bitmap?,
    displayMode: CardFaceDisplayMode,
    modifier: Modifier = Modifier,
    showBrandIcon: Boolean = true,
    reservedTopEndWidth: Dp = 0.dp
) {
    val foreground = if (bitmap != null) Color.White else MaterialTheme.colorScheme.onSurface
    val textStyle = TextStyle(
        color = foreground,
        shadow = if (bitmap != null) Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1.5f), 4f) else null
    )
    val showBrand = showBrandIcon && previewData.brand != null && displayMode != CardFaceDisplayMode.HIDDEN
    BoxWithConstraints(
        modifier = modifier
            .clip(MonicaItemCardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        val compact = maxWidth < 300.dp
        val inset = if (compact) 14.dp else 20.dp
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (bitmap != null && displayMode != CardFaceDisplayMode.HIDDEN) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.4f),
                        0.35f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.56f)
                    )
                )
            )
        }
        if (displayMode == CardFaceDisplayMode.ALL) {
            Row(
                modifier = Modifier.align(Alignment.TopStart)
                    .fillMaxWidth().padding(inset).padding(end = reservedTopEndWidth),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (previewData.brand == null) {
                    Icon(previewData.icon, contentDescription = null, tint = foreground, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = previewData.title,
                        style = MaterialTheme.typography.titleMedium.merge(textStyle),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (previewData.subtitle.isNotBlank()) {
                        Text(
                            text = previewData.subtitle,
                            style = MaterialTheme.typography.bodySmall.merge(textStyle),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (displayMode != CardFaceDisplayMode.HIDDEN && previewData.identifier.isNotBlank()) {
            // A proportional position stays consistent between list, detail and editor previews.
            Text(
                text = if (previewData.maskIdentifier) maskedCardFaceIdentifier(previewData.identifier) else previewData.identifier,
                modifier = Modifier.align(BiasAlignment(-1f, 0.28f)).fillMaxWidth().padding(horizontal = inset),
                style = MaterialTheme.typography.titleLarge.merge(textStyle),
                fontFamily = if (previewData.maskIdentifier) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (compact) 16.sp else 20.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = if (previewData.maskIdentifier) 1.sp else 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (displayMode == CardFaceDisplayMode.ALL) {
            Row(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(inset)
                    .padding(end = if (showBrand) 64.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = previewData.footerStart,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium.merge(textStyle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (previewData.footerEnd.isNotBlank()) {
                    Text(
                        text = previewData.footerEnd,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium.merge(textStyle),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (showBrand) {
            CardBrandIcon(
                brand = requireNotNull(previewData.brand),
                tint = foreground,
                modifier = Modifier.align(Alignment.BottomEnd).padding(inset).size(width = 52.dp, height = 32.dp)
            )
        }
    }
}

@Composable
fun BankCardFaceArtwork(
    title: String,
    cardData: BankCardData,
    bitmap: Bitmap?,
    displayMode: CardFaceDisplayMode,
    modifier: Modifier = Modifier,
    reservedTopEndWidth: Dp = 0.dp
) = CardFaceArtwork(
    previewData = bankCardFacePreviewData(title, cardData),
    bitmap = bitmap,
    displayMode = displayMode,
    showBrandIcon = cardData.cardFace?.showBrandIcon ?: true,
    reservedTopEndWidth = reservedTopEndWidth,
    modifier = modifier
)

internal fun maskedCardFaceIdentifier(value: String): String {
    val clean = value.filterNot(Char::isWhitespace)
    return if (clean.length < 4) "••••" else "••••  ••••  ••••  ${clean.takeLast(4)}"
}

internal fun maskedCardNumber(cardNumber: String): String = maskedCardFaceIdentifier(cardNumber.filter(Char::isDigit))

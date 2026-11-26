package takagi.ru.monica.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LoadingIndicator as MaterialExpressiveLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.data.model.OtpType
import takagi.ru.monica.data.model.TotpData
import takagi.ru.monica.util.TotpGenerator

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InlineTotpPreviewCard(
    totpData: TotpData,
    currentSeconds: Long,
    progressTimeMillis: Long,
    timeOffset: Int,
    smoothProgress: Boolean,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    showProgress: Boolean = true
) {
    val periodMillis = remember(totpData.period) {
        (totpData.period * 1000L).coerceAtLeast(1000L)
    }
    val correctedMillis = remember(progressTimeMillis, timeOffset) {
        progressTimeMillis + (timeOffset * 1000L)
    }
    val synchronizedSeconds = remember(progressTimeMillis) {
        Math.floorDiv(progressTimeMillis, 1000L)
    }
    val elapsedInPeriodMillis = remember(correctedMillis, periodMillis) {
        ((correctedMillis % periodMillis) + periodMillis) % periodMillis
    }
    val synchronizedRemainingSeconds = remember(elapsedInPeriodMillis, periodMillis) {
        ((periodMillis - elapsedInPeriodMillis + 999L) / 1000L).toInt()
            .coerceIn(0, (periodMillis / 1000L).toInt().coerceAtLeast(1))
    }
    val currentCode = remember(synchronizedSeconds, currentSeconds, totpData, timeOffset) {
        when (totpData.otpType) {
            OtpType.HOTP -> TotpGenerator.generateOtp(totpData)
            else -> TotpGenerator.generateOtp(
                totpData = totpData,
                timeOffset = timeOffset,
                currentSeconds = synchronizedSeconds
            )
        }
    }
    val remainingSeconds = remember(synchronizedRemainingSeconds, totpData.otpType) {
        if (totpData.otpType == OtpType.HOTP) {
            0
        } else {
            synchronizedRemainingSeconds
        }
    }
    val badgeValue = if (totpData.otpType == OtpType.HOTP) {
        totpData.counter.toString()
    } else {
        remainingSeconds.toString()
    }
    val badgeContainerColor = if (totpData.otpType == OtpType.HOTP) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val badgeContentColor = if (totpData.otpType == OtpType.HOTP) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = formatInlinePreviewOtpCode(currentCode, totpData.otpType),
                    style = MaterialTheme.typography.titleLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        InlineTotpNumericBadge(
            value = badgeValue,
            containerColor = badgeContainerColor,
            contentColor = badgeContentColor,
            isHotp = totpData.otpType == OtpType.HOTP
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InlineTotpNumericBadge(
    value: String,
    containerColor: Color,
    contentColor: Color,
    isHotp: Boolean
) {
    val shapeTransition = rememberInfiniteTransition(label = "inline_totp_badge_shape_transition")
    val shapeProgress by shapeTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inline_totp_badge_shape_progress"
    )

    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        MaterialExpressiveLoadingIndicator(
            progress = { shapeProgress },
            modifier = Modifier.size(60.dp),
            color = if (isHotp) containerColor else contentColor,
            polygons = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.surface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

private fun formatInlinePreviewOtpCode(code: String, otpType: OtpType): String {
    return when (otpType) {
        OtpType.STEAM -> {
            if (code.length == 5) {
                "${code.substring(0, 2)} ${code.substring(2)}"
            } else {
                code
            }
        }

        else -> {
            when (code.length) {
                6 -> "${code.substring(0, 3)} ${code.substring(3)}"
                8 -> "${code.substring(0, 4)} ${code.substring(4)}"
                else -> code
            }
        }
    }
}

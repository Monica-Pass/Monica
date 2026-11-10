package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.utils.SshKeyGenerator

/**
 * SSH 密钥生成器的配置卡片。
 *
 * 独立出来避免 [GeneratorScreen] 继续膨胀。外部负责监听参数变化并驱动生成；
 * 本卡片只负责渲染并把用户选择写回 ViewModel 对应 `StateFlow`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorSshKeySection(
    algorithm: String,
    onAlgorithmChange: (String) -> Unit,
    rsaKeySize: Int,
    onRsaKeySizeChange: (Int) -> Unit
) {
    val fieldShape = RoundedCornerShape(12.dp)
    val isRsa = algorithm.equals(SshKeyData.ALGORITHM_RSA, ignoreCase = true)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.generator_ssh_key),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        SshKeyAlgorithmDropdown(
            selected = algorithm,
            onSelect = onAlgorithmChange,
            fieldShape = fieldShape
        )

        if (isRsa) {
            SshKeyRsaSizeDropdown(
                selected = rsaKeySize,
                onSelect = onRsaKeySizeChange,
                fieldShape = fieldShape
            )
        }

        AlgorithmDescriptionCard(isRsa = isRsa)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshKeyAlgorithmDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    fieldShape: androidx.compose.ui.graphics.Shape
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        selected.equals(SshKeyData.ALGORITHM_RSA, ignoreCase = true) ->
            stringResource(R.string.ssh_key_algorithm_rsa)
        else -> stringResource(R.string.ssh_key_algorithm_ed25519)
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ssh_key_algorithm_label)) },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = fieldShape,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ssh_key_algorithm_ed25519)) },
                onClick = {
                    expanded = false
                    onSelect(SshKeyData.ALGORITHM_ED25519)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ssh_key_algorithm_rsa)) },
                onClick = {
                    expanded = false
                    onSelect(SshKeyData.ALGORITHM_RSA)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshKeyRsaSizeDropdown(
    selected: Int,
    onSelect: (Int) -> Unit,
    fieldShape: androidx.compose.ui.graphics.Shape
) {
    var expanded by remember { mutableStateOf(false) }
    val options = SshKeyGenerator.RSA_ALLOWED_KEY_SIZES.sorted()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = "$selected bits",
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ssh_key_size_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = fieldShape,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { size ->
                DropdownMenuItem(
                    text = { Text("$size bits") },
                    onClick = {
                        expanded = false
                        onSelect(size)
                    }
                )
            }
        }
    }
}

@Composable
private fun AlgorithmDescriptionCard(isRsa: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = stringResource(
                    if (isRsa) R.string.ssh_key_rsa_description
                    else R.string.ssh_key_ed25519_description
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(4.dp))
}

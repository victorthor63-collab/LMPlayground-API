@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.apiserver.ApiServerController
import com.druk.lmplayground.apiserver.LoadedModelHolder

@Composable
fun ApiServerScreen(
    status: ApiServerController.Status,
    loadedModel: LoadedModelHolder.LoadedModel?,
    port: Int,
    apiKey: String,
    onEnabledChange: (Boolean) -> Unit,
    onPortChange: (Int) -> Unit,
    onRegenerateKey: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenLogClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.api_server)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        ApiServerContent(
            status = status,
            loadedModel = loadedModel,
            port = port,
            apiKey = apiKey,
            onEnabledChange = onEnabledChange,
            onPortChange = onPortChange,
            onRegenerateKey = onRegenerateKey,
            onCopy = onCopy,
            onOpenLogClick = onOpenLogClick,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
fun ApiServerContent(
    status: ApiServerController.Status,
    loadedModel: LoadedModelHolder.LoadedModel?,
    port: Int,
    apiKey: String,
    onEnabledChange: (Boolean) -> Unit,
    onPortChange: (Int) -> Unit,
    onRegenerateKey: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenLogClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Enable toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEnabledChange(!status.enabled) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.api_server_enable),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.api_server_enable_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = status.enabled, onCheckedChange = onEnabledChange)
        }

        // Live status
        StatusCard(status = status)

        if (status.enabled && loadedModel == null) {
            Text(
                stringResource(R.string.api_server_no_model_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Base URL (copyable)
        status.url?.let { url ->
            CopyField(
                label = stringResource(R.string.api_server_base_url),
                value = "$url/v1",
                onCopy = onCopy,
            )
        }

        // API key (copyable + regenerate)
        CopyField(
            label = stringResource(R.string.api_server_api_key),
            value = apiKey,
            onCopy = onCopy,
            trailing = {
                IconButton(onClick = onRegenerateKey) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.api_server_regenerate_key),
                    )
                }
            },
        )

        // Port
        OutlinedTextField(
            value = port.toString(),
            onValueChange = { it.toIntOrNull()?.let(onPortChange) },
            label = { Text(stringResource(R.string.api_server_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        // Example curl
        status.url?.let { url ->
            Column {
                Text(
                    stringResource(R.string.api_server_curl_hint),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                val curl = "curl $url/v1/chat/completions \\\n" +
                    "  -H \"Authorization: Bearer $apiKey\" \\\n" +
                    "  -H \"Content-Type: application/json\" \\\n" +
                    "  -d '{\"model\":\"${loadedModel?.id ?: "model"}\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'"
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            curl,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onCopy(curl) }) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.api_server_copy),
                            )
                        }
                    }
                }
            }
        }

        // Request log entry
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenLogClick() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.api_server_debug_log),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.api_server_debug_log_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(status: ApiServerController.Status) {
    val text = when {
        status.running && status.url != null ->
            stringResource(R.string.api_server_status_running, status.url)
        status.message != null -> status.message
        status.enabled -> stringResource(R.string.api_server_status_no_wifi)
        else -> stringResource(R.string.api_server_status_off)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (status.running) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun CopyField(
    label: String,
    value: String,
    onCopy: (String) -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onCopy(value) }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.api_server_copy),
                )
            }
            trailing?.invoke()
        }
    }
}

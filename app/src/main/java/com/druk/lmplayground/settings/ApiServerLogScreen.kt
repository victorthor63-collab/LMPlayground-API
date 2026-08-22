@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.apiserver.ApiRequestLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ApiServerLogScreen(
    entries: List<ApiRequestLog.Entry>,
    onClear: () -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.api_server_debug_log)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.api_server_debug_clear),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.api_server_debug_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(entries, key = { it.id }) { entry ->
                    LogRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: ApiRequestLog.Entry) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(entry.statusCode)
            Spacer(Modifier.width(8.dp))
            Text(
                "${entry.method} ${entry.path}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (entry.streamed) {
                Text(
                    "stream",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "${entry.durationMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatTime(entry.timestamp)}  ·  ${entry.remoteAddr ?: "?"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entry.error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            BodyBlock(stringResource(R.string.api_server_debug_request), entry.requestBody)
            Spacer(Modifier.height(8.dp))
            BodyBlock(stringResource(R.string.api_server_debug_response), entry.responseBody)
        }
    }
}

@Composable
private fun BodyBlock(label: String, body: String?) {
    if (body.isNullOrEmpty()) return
    Text(label, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun StatusChip(statusCode: Int) {
    val color = when (statusCode) {
        in 200..299 -> Color(0xFF2E7D32)
        in 400..499 -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color) {
        Text(
            statusCode.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

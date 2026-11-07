package com.imagecompressor.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagecompressor.app.engine.OutcomeStatus
import com.imagecompressor.app.engine.Storage
import com.imagecompressor.app.util.formatSize
import com.imagecompressor.app.util.percentSaved
import com.imagecompressor.app.vm.MainViewModel
import com.imagecompressor.app.vm.Phase
import com.imagecompressor.app.vm.ResultItem
import com.imagecompressor.app.vm.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    vm: MainViewModel,
    state: UiState,
    snackbar: SnackbarHostState,
    onSaveOne: (Long) -> Unit,
    onSaveAll: () -> Unit
) {
    val context = LocalContext.current
    val results = state.results
    val total = results.size
    val finished = results.count { it.phase == Phase.DONE || it.phase == Phase.FAILED }
    val running = results.firstOrNull { it.phase == Phase.RUNNING }?.progress ?: 0f
    val overallTarget = if (total == 0) 0f else (finished + running) / total
    val overall by animateFloatAsState(overallTarget, label = "overall")

    val ok = results.filter { it.outcome != null }
    val failed = results.count { it.phase == Phase.FAILED }
    val totalOriginal = ok.sumOf { it.image.sizeBytes }
    val totalFinal = ok.sumOf { it.outcome!!.sizeBytes }
    val allSaved = ok.isNotEmpty() && ok.all { it.saved }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Results", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = vm::backToHome) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item("summary") {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.animateContentSize()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        if (state.isRunning) {
                            Text(
                                "Compressing ${minOf(finished + 1, total)} of $total",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { overall },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(10.dp))
                        } else {
                            Text(
                                "Compression complete",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Row(Modifier.fillMaxWidth()) {
                            Stat("Original", formatSize(totalOriginal), Modifier.weight(1f))
                            Stat("Compressed", formatSize(totalFinal), Modifier.weight(1f))
                            Stat(
                                "Saved",
                                if (ok.isEmpty()) "–" else "${formatSize(maxOf(0L, totalOriginal - totalFinal))} (${percentSaved(totalOriginal, totalFinal)}%)",
                                Modifier.weight(1.3f)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            buildString {
                                append("${ok.size} of $total compressed")
                                if (failed > 0) append(" · $failed failed")
                                append(" · target ${formatSize(state.runTargetBytes)}")
                                if (total > 1) append(" each")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            item("actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onSaveAll,
                        enabled = !state.isRunning && ok.isNotEmpty() && !allSaved,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) { Text(if (allSaved) "All saved" else if (total > 1) "Save All" else "Save Image") }
                    OutlinedButton(
                        onClick = { Storage.share(context, ok.map { it.outcome!!.file }) },
                        enabled = !state.isRunning && ok.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (total > 1) "Share all" else "Share")
                    }
                }
            }

            items(results, key = { it.image.id }) { item ->
                ResultCard(
                    item = item,
                    targetBytes = state.runTargetBytes,
                    onPreview = { item.outcome?.let { vm.openPreview(it.file) } },
                    onSave = { onSaveOne(item.image.id) },
                    onShare = { item.outcome?.let { Storage.share(context, listOf(it.file)) } }
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun ResultCard(
    item: ResultItem,
    targetBytes: Long,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    val outcome = item.outcome
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.animateContentSize()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(item.image.thumb, 64.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.image.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (outcome != null) {
                        val pct = percentSaved(item.image.sizeBytes, outcome.sizeBytes)
                        Text(
                            "${formatSize(item.image.sizeBytes)}  →  ${formatSize(outcome.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (pct >= 0) "$pct% smaller" else "${-pct}% larger than original",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (pct >= 0) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            "Original ${formatSize(item.image.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            when (item.phase) {
                Phase.PENDING -> Text(
                    "Waiting…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Phase.RUNNING -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Compressing…", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Phase.FAILED -> StatusLine(
                    warning = true,
                    text = item.error ?: "Couldn't compress this image.",
                    error = true
                )

                Phase.DONE -> if (outcome != null) {
                    val warn = outcome.status == OutcomeStatus.CLOSEST
                    StatusLine(
                        warning = warn,
                        text = when (outcome.status) {
                            OutcomeStatus.TARGET_MET -> "Target reached · ${outcome.message}"
                            OutcomeStatus.ALREADY_SMALL -> outcome.message
                            OutcomeStatus.CLOSEST -> outcome.message
                        }
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onPreview) { Text("Preview") }
                        TextButton(onClick = onSave, enabled = !item.saved) {
                            Text(if (item.saved) "Saved" else "Save")
                        }
                        TextButton(onClick = onShare) { Text("Share") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(warning: Boolean, text: String, error: Boolean = false) {
    val color = when {
        error -> MaterialTheme.colorScheme.error
        warning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (warning) Icons.Default.Warning else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

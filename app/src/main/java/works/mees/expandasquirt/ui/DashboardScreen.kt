package works.mees.expandasquirt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import works.mees.expandasquirt.ble.SensorReading

enum class SensorHealth {
    Ok,
    Fault,
    NoData,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    onMenuFirmwareUpdate: () -> Unit,
    onMenuDiagnostics: () -> Unit,
    onForget: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var detailSensor by remember { mutableStateOf<String?>(null) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var nowNs by remember { mutableStateOf(System.nanoTime()) }
    val isStale = state.lastFrameMonoNs != 0L && nowNs - state.lastFrameMonoNs > STALE_NS

    LaunchedEffect(Unit) {
        while (true) {
            nowNs = System.nanoTime()
            delay(1_000)
        }
    }

    detailSensor?.let { key ->
        val spec = SENSOR_SPECS.first { it.key == key }
        val reading = state.frame?.readings?.get(key)
        SensorDetailSheet(
            label = spec.label,
            value = reading?.let { formatValue(it) },
            unit = reading?.unit,
            samples = state.histories[key].orEmpty(),
            onDismiss = { detailSensor = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Expandasquirt",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            state.deviceName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    ConnectionChip(connected = state.connected)
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Firmware update...",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            onClick = {
                                menuOpen = false
                                onMenuFirmwareUpdate()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Diagnostics",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            onClick = {
                                menuOpen = false
                                onMenuDiagnostics()
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Forget device",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            onClick = {
                                menuOpen = false
                                onForget()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SensorTile(
                label = "Oil Pressure",
                value = state.frame?.readings?.get("oilP")?.let { formatValue(it) },
                unit = state.frame?.readings?.get("oilP")?.unit,
                health = healthFor(state.frame?.readings?.get("oilP")),
                samples = state.histories["oilP"].orEmpty().takeLast(60),
                isHero = true,
                isStale = isStale,
                onClick = { detailSensor = "oilP" },
            )

            SupportingGrid(
                state = state,
                isStale = isStale,
                onSelect = { detailSensor = it },
            )

            Spacer(Modifier.weight(1f))
            DiagnosticsStrip(
                state = state,
                expanded = diagnosticsExpanded,
                lastFrameAgeMs = lastFrameAgeMs(state.lastFrameMonoNs, nowNs),
                onToggle = { diagnosticsExpanded = !diagnosticsExpanded },
            )
        }
    }
}

@Composable
private fun ConnectionChip(connected: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(if (connected) "Connected" else "Disconnected") },
        leadingIcon = {
            Icon(
                if (connected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                contentDescription = null,
            )
        },
        colors = if (connected) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
    )
}

@Composable
private fun SupportingGrid(
    state: DashboardState,
    isStale: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SENSOR_SPECS.filter { !it.hero }.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { spec ->
                    val reading = state.frame?.readings?.get(spec.key)
                    SensorTile(
                        label = spec.label,
                        value = reading?.let { formatValue(it) },
                        unit = reading?.unit,
                        health = healthFor(reading),
                        samples = emptyList(),
                        isHero = false,
                        isStale = isStale,
                        onClick = { onSelect(spec.key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SensorTile(
    label: String,
    value: String?,
    unit: String?,
    health: SensorHealth,
    samples: List<Float>,
    isHero: Boolean,
    isStale: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val colors = when {
        health == SensorHealth.Fault -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        isStale -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(if (isHero) 140.dp else 112.dp)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
        shape = RoundedCornerShape(20.dp),
        colors = colors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isHero) PaddingValues(18.dp, 14.dp) else PaddingValues(14.dp)),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value ?: "\u2014",
                    style = if (isHero) {
                        MaterialTheme.typography.displayMedium
                    } else {
                        MaterialTheme.typography.headlineLarge
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                if (!unit.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = if (isHero) 10.dp else 6.dp),
                    )
                }
            }
            if (isHero) {
                Sparkline(samples = samples)
            }
        }
    }
}

@Composable
private fun Sparkline(samples: List<Float>) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        if (samples.size < 2) return@Canvas
        val min = samples.minOrNull() ?: return@Canvas
        val max = samples.maxOrNull() ?: return@Canvas
        val range = (max - min).takeIf { it != 0f } ?: 1f
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = size.width * index / (samples.lastIndex).coerceAtLeast(1)
            val y = size.height - ((sample - min) / range) * size.height
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path = path, color = lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun DiagnosticsStrip(
    state: DashboardState,
    expanded: Boolean,
    lastFrameAgeMs: Long?,
    onToggle: () -> Unit,
) {
    val frame = state.frame
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (frame != null) {
                        "seq ${frame.seq} · health 0x%02X".format(frame.healthBitmask)
                    } else {
                        "seq \u2014 · health \u2014"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse diagnostics" else "Expand diagnostics",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            if (expanded) {
                Text(
                    "ready ${frame?.ready ?: "\u2014"} · age ${lastFrameAgeMs?.let { "$it ms" } ?: "\u2014"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SENSOR_SPECS.forEach { spec ->
                    val reading = frame?.readings?.get(spec.key)
                    Text(
                        "${spec.key}: ${if (reading?.healthOk == true) "ok" else "fault"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun healthFor(reading: SensorReading?): SensorHealth = when {
    reading == null -> SensorHealth.NoData
    reading.healthOk -> SensorHealth.Ok
    else -> SensorHealth.Fault
}

private fun formatValue(reading: SensorReading): String = "%.1f".format(reading.value)

private fun lastFrameAgeMs(lastFrameMonoNs: Long, nowNs: Long): Long? =
    if (lastFrameMonoNs == 0L) null else (nowNs - lastFrameMonoNs) / 1_000_000L

private data class SensorSpec(
    val key: String,
    val label: String,
    val hero: Boolean = false,
)

private val SENSOR_SPECS = listOf(
    SensorSpec("oilP", "Oil Pressure", hero = true),
    SensorSpec("oilT", "Oil Temp"),
    SensorSpec("fuelP", "Fuel Pressure"),
    SensorSpec("preP", "Pre-IC MAP"),
    SensorSpec("postT", "Post-IC Temp"),
)

private const val STALE_NS = 2_000_000_000L

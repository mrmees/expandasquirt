package works.mees.expandasquirt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDetailSheet(
    label: String,
    value: String?,
    unit: String?,
    samples: List<Float>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value ?: "\u2014",
                    style = MaterialTheme.typography.displayLarge,
                )
                if (!unit.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        unit,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DetailChart(samples = samples.takeLast(300))
            StatChips(samples = samples.takeLast(300))
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailChart(samples: List<Float>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        repeat(4) { index ->
            val y = size.height * (index + 1) / 5f
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
        }

        if (samples.size < 2) return@Canvas
        val min = samples.minOrNull() ?: return@Canvas
        val max = samples.maxOrNull() ?: return@Canvas
        val padding = if (min == max) 1f else (max - min) * 0.05f
        val yMin = min - padding
        val yMax = max + padding
        val range = max(yMax - yMin, 1f)
        val path = Path()
        var lastPoint = Offset.Zero
        samples.forEachIndexed { index, sample ->
            val x = size.width * index / (samples.lastIndex).coerceAtLeast(1)
            val y = size.height - ((sample - yMin) / range) * size.height
            lastPoint = Offset(x, y)
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = lineColor, radius = 4.dp.toPx(), center = lastPoint)
    }
}

@Composable
private fun StatChips(samples: List<Float>) {
    val min = samples.minOrNull()
    val avg = samples.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val max = samples.maxOrNull()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatChip("min", min)
        StatChip("avg", avg)
        StatChip("max", max)
    }
}

@Composable
private fun StatChip(label: String, value: Float?) {
    AssistChip(
        onClick = {},
        label = {
            Text("$label ${value?.let { "%.1f".format(it) } ?: "\u2014"}")
        },
    )
}

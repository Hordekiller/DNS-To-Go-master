package com.hololo.app.dnschanger.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hololo.app.dnschanger.R

@Composable
fun StatusCard(
    isRunning: Boolean,
    dnsName: String,
    pingMs: Long,
    latencyHistory: List<Float>,
    onStartStopClick: () -> Unit,
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val powerColor by animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.error else accentColor,
        label = "powerColor",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0x15FFFFFF))
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(powerColor.copy(alpha = 0.4f))
            )

            Spacer(Modifier.height(12.dp))

            IconButton(
                onClick = onStartStopClick,
                modifier = Modifier
                    .size(120.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.PowerOff else Icons.Default.PowerSettingsNew,
                        contentDescription = if (isRunning) "Stop" else "Start",
                        modifier = Modifier.size(44.dp),
                        tint = powerColor,
                    )
                    Canvas(Modifier.size(120.dp)) {
                        val strokeWidth = 3.dp.toPx()
                        drawCircle(
                            color = powerColor,
                            radius = size.minDimension / 2 - strokeWidth / 2,
                            style = Stroke(width = strokeWidth),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (isRunning) stringResource(R.string.connected) else stringResource(R.string.disconnected),
                color = if (isRunning) accentColor else Color.Gray,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.15.sp,
            )

            Text(
                text = if (isRunning && dnsName.isNotEmpty())
                    stringResource(R.string.connected_to, dnsName)
                else
                    stringResource(R.string.tap_to_start),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.latency),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    letterSpacing = 0.1.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (pingMs >= 0) "${pingMs} ms" else "-- ms",
                    color = accentColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(8.dp))

            if (latencyHistory.isNotEmpty()) {
                LatencySparkLine(
                    points = latencyHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    color = accentColor,
                )
            }
        }
    }
}

@Composable
private fun LatencySparkLine(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val lineColor = color
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val stepX = size.width / (points.size - 1)
        val maxVal = points.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val path = Path()
        points.forEachIndexed { i, value ->
            val x = i * stepX
            val y = size.height - (value / maxVal * size.height * 0.9f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
        val fillPath = Path()
        fillPath.addPath(path)
        fillPath.lineTo(size.width, size.height)
        fillPath.lineTo(0f, size.height)
        fillPath.close()
        drawPath(fillPath, color = lineColor.copy(alpha = 0.1f))
    }
}

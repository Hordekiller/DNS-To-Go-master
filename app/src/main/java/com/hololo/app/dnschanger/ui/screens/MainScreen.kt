package com.hololo.app.dnschanger.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MainUiState(
    val isRunning: Boolean = false,
    val dnsName: String = "",
    val primaryDns: String = "1.1.1.1",
    val secondaryDns: String = "1.0.0.1",
    val pingMs: Long = -1,
    val latencyHistory: List<Float> = emptyList(),
    val totalQueries: Long = 0,
    val blockedQueries: Long = 0,
    val blockPercent: Float = 0f,
)

@Composable
fun MainScreen(
    state: MainUiState,
    onStartStopClick: () -> Unit,
    onSelectServerClick: () -> Unit,
    onPrimaryDnsChange: (String) -> Unit,
    onSecondaryDnsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Status Card
        StatusCard(
            isRunning = state.isRunning,
            dnsName = state.dnsName,
            pingMs = state.pingMs,
            latencyHistory = state.latencyHistory,
            onStartStopClick = onStartStopClick,
        )

        Spacer(Modifier.height(12.dp))

        // Stats Card
        StatsCard(
            totalQueries = state.totalQueries,
            blockedQueries = state.blockedQueries,
            blockPercent = state.blockPercent,
        )

        Spacer(Modifier.height(12.dp))

        // DNS Config Card
        DnsConfigCard(
            primaryDns = state.primaryDns,
            secondaryDns = state.secondaryDns,
            onPrimaryDnsChange = onPrimaryDnsChange,
            onSecondaryDnsChange = onSecondaryDnsChange,
            enabled = !state.isRunning,
        )

        Spacer(Modifier.height(12.dp))

        // Select Server Button
        OutlinedButton(
            onClick = onSelectServerClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ),
        ) {
            Icon(
                Icons.Default.Speed,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "SELECT FASTEST SERVER",
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(
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
            // Connection indicator dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(powerColor.copy(alpha = 0.4f))
            )

            Spacer(Modifier.height(12.dp))

            // Power button
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
                    // Border ring
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
                text = if (isRunning) "CONNECTED" else "DISCONNECTED",
                color = if (isRunning) accentColor else Color.Gray,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.15.sp,
            )

            Text(
                text = if (isRunning && dnsName.isNotEmpty()) "Connected to $dnsName" else "Tap to Start DNS",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )

            Spacer(Modifier.height(16.dp))

            // Latency
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LATENCY",
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

            // Mini latency chart
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
        // Fill under curve
        val fillPath = Path()
        fillPath.addPath(path)
        fillPath.lineTo(size.width, size.height)
        fillPath.lineTo(0f, size.height)
        fillPath.close()
        drawPath(fillPath, color = lineColor.copy(alpha = 0.1f))
    }
}

@Composable
private fun StatsCard(
    totalQueries: Long,
    blockedQueries: Long,
    blockPercent: Float,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0x15FFFFFF))
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        ) {
            StatItem(
                label = "HEALTH",
                value = totalQueries.toString(),
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            StatItem(
                label = "BLOCKED",
                value = blockedQueries.toString(),
                valueColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            StatDivider()
            StatItem(
                label = "BLOCK RATE",
                value = "%.1f%%".format(blockPercent),
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            letterSpacing = 0.1.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(Color(0x20FFFFFF))
    )
}

@Composable
private fun DnsConfigCard(
    primaryDns: String,
    secondaryDns: String,
    onPrimaryDnsChange: (String) -> Unit,
    onSecondaryDnsChange: (String) -> Unit,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0x15FFFFFF))
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "CONFIGURATION",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                letterSpacing = 0.12.sp,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(12.dp))

            DnsInputRow(
                icon = Icons.Default.Public,
                iconTint = MaterialTheme.colorScheme.secondary,
                label = "PRIMARY DNS",
                value = primaryDns,
                onValueChange = onPrimaryDnsChange,
                enabled = enabled,
            )

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x15FFFFFF)))
            Spacer(Modifier.height(12.dp))

            DnsInputRow(
                icon = Icons.Default.Public,
                iconTint = MaterialTheme.colorScheme.tertiary,
                label = "SECONDARY DNS",
                value = secondaryDns,
                onValueChange = onSecondaryDnsChange,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun DnsInputRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = iconTint,
        )
        Spacer(Modifier.width(12.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            label = {
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            },
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
}

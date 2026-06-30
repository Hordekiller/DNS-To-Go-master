package com.hololo.app.dnschanger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hololo.app.dnschanger.model.DNSModel

data class DnsPickerItem(
    val model: DNSModel,
    val pingMs: Long = -1,
)

@Composable
fun DnsPickerContent(
    items: List<DnsPickerItem>,
    onItemClick: (DNSModel) -> Unit,
    onTestClick: (DNSModel, (Long) -> Unit) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        // Handle
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .align(Alignment.CenterHorizontally)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(2.dp)
                )
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "DNS Servers",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = "Choose a server to optimize your connection",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.model.name + it.model.firstDns }) { item ->
                DnsServerCard(
                    item = item,
                    onClick = { onItemClick(item.model) },
                    onTest = { callback -> onTestClick(item.model, callback) },
                )
            }
        }
    }
}

@Composable
private fun DnsServerCard(
    item: DnsPickerItem,
    onClick: () -> Unit,
    onTest: ((Long) -> Unit) -> Unit,
) {
    val model = item.model

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(Color(0x15FFFFFF))
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.category != null && model.category.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = model.category,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                            modifier = Modifier
                                .background(
                                    Color(0x1500A3FF),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = model.firstDns + if (model.secondDns.isNotEmpty()) " | ${model.secondDns}" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )

                val features = model.features
                if (features != null && features.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        features.forEach { proto ->
                            if (proto == "DoH" || proto == "DoT" || proto == "UDP") {
                                val chipColor = when (proto) {
                                    "DoH" -> MaterialTheme.colorScheme.secondary
                                    "DoT" -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                Text(
                                    text = proto,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = chipColor,
                                    modifier = Modifier
                                        .background(
                                            chipColor.copy(alpha = 0.1f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (item.pingMs >= 0) "${item.pingMs} ms" else "-- ms",
                    color = if (item.pingMs >= 0 && item.pingMs < 100)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )

                TextButton(
                    onClick = { onTest { result -> } },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.secondary,
                    ),
                ) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = "Test",
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("TEST", fontSize = 11.sp)
                }
            }
        }
    }
}

package com.hololo.app.dnschanger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hololo.app.dnschanger.R

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

        StatusCard(
            isRunning = state.isRunning,
            dnsName = state.dnsName,
            pingMs = state.pingMs,
            latencyHistory = state.latencyHistory,
            onStartStopClick = onStartStopClick,
        )

        Spacer(Modifier.height(12.dp))

        StatsCard(
            totalQueries = state.totalQueries,
            blockedQueries = state.blockedQueries,
            blockPercent = state.blockPercent,
        )

        Spacer(Modifier.height(12.dp))

        DnsConfigCard(
            primaryDns = state.primaryDns,
            secondaryDns = state.secondaryDns,
            onPrimaryDnsChange = onPrimaryDnsChange,
            onSecondaryDnsChange = onSecondaryDnsChange,
            enabled = !state.isRunning,
        )

        Spacer(Modifier.height(12.dp))

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
                stringResource(R.string.choose_dns_server),
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

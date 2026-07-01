package com.hololo.app.dnschanger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import com.hololo.app.dnschanger.R

@Composable
fun DrawerContent(
    currentServerName: String,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onNavigate: (DrawerItem) -> Unit,
) {
    ModalDrawerSheet {
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.dns_changer_title),
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = currentServerName,
            modifier = Modifier.padding(horizontal = 28.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        val selectedItem = remember { mutableStateOf(DrawerItem.HOME) }
        val items = listOf(
            DrawerItem.HOME,
            DrawerItem.LOGS,
            DrawerItem.SETTINGS,
            DrawerItem.APPS,
            DrawerItem.ABOUT,
        )
        items.forEach { item ->
            NavigationDrawerItem(
                icon = { Icon(item.icon, null) },
                label = { Text(stringResource(item.labelRes)) },
                selected = selectedItem.value == item,
                onClick = {
                    selectedItem.value = item
                    onNavigate(item)
                },
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                )
            )
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleTheme() }
                .padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = if (isDarkTheme) stringResource(R.string.dark_mode) else stringResource(R.string.light_mode),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

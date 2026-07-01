package com.hololo.app.dnschanger.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpCenter
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.hololo.app.dnschanger.R

enum class DrawerItem(
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(R.string.home, Icons.Default.Home),
    LOGS(R.string.dns_logs, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.settings, Icons.Default.Settings),
    APPS(R.string.app_filter, Icons.Default.PhoneAndroid),
    ABOUT(R.string.about, Icons.AutoMirrored.Filled.HelpCenter),
}

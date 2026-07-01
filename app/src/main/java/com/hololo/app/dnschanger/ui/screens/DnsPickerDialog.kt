package com.hololo.app.dnschanger.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.hololo.app.dnschanger.model.DNSModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsPickerDialog(
    dnsList: List<DNSModel>,
    onItemClick: (DNSModel) -> Unit,
    onTestClick: (DNSModel, (Long) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        val items = dnsList.map { DnsPickerItem(it, it.lastPing) }
        DnsPickerContent(
            items = items,
            onItemClick = onItemClick,
            onTestClick = onTestClick,
        )
    }
}

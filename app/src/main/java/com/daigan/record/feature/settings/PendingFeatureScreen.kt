package com.daigan.record.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader

/**
 * Placeholder destination for settings entries whose features belong to later phases.
 * It never renders invented data or working-looking controls.
 */
@Composable
fun PendingFeatureScreen(
    title: String,
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(title = title, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            EmptyStateCard(title = "功能尚未提供", description = message)
        }
    }
}

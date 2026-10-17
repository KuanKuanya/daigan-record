package com.daigan.record.feature.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader

@Composable
fun StatsScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "数据统计",
            subtitle = "实收、待收与订单数量",
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            EmptyStateCard(
                title = "暂无统计数据",
                description = "收款、欠款与订单统计将在第三阶段根据真实订单和收款记录计算。" +
                    "本页不展示示例金额，也不使用固定演示数字。",
            )
        }
    }
}

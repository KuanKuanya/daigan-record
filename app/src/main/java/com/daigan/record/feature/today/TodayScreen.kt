package com.daigan.record.feature.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader

@Composable
fun TodayScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TodayScreen(
        uiState = uiState,
        onSelectTab = viewModel::selectTab,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@Composable
internal fun TodayScreen(
    uiState: TodayUiState,
    onSelectTab: (TodayTab) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "今日事项",
            subtitle = uiState.dateLabel,
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            },
        )
        TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
            TodayTab.entries.forEach { tab ->
                Tab(
                    selected = uiState.selectedTab == tab,
                    onClick = { onSelectTab(tab) },
                    text = { Text(tab.label) },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            when (uiState.selectedTab) {
                TodayTab.HOSTING -> EmptyStateCard(
                    title = "还没有托管打卡",
                    description = "长期托管订单与每日打卡将在第二阶段接入真实数据。" +
                        "本版本不会显示示例任务或虚构的完成进度。",
                )

                TodayTab.SHORT_TASK -> EmptyStateCard(
                    title = "还没有短期任务",
                    description = "短期订单、截止时间与进度记录将在第二阶段接入。" +
                        "当前版本仅提供老板与游戏账号管理。",
                )
            }
        }
    }
}

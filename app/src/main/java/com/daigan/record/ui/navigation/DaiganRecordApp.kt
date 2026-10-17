package com.daigan.record.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.daigan.record.feature.accounts.AccountFormScreen
import com.daigan.record.feature.accounts.AccountsScreen
import com.daigan.record.feature.accounts.ArchivedRecordsScreen
import com.daigan.record.feature.accounts.OwnerDetailScreen
import com.daigan.record.feature.accounts.OwnerFormScreen
import com.daigan.record.feature.settings.PendingFeatureScreen
import com.daigan.record.feature.settings.SettingsScreen
import com.daigan.record.feature.stats.StatsScreen
import com.daigan.record.feature.today.TodayScreen

private data class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val mainDestinations = listOf(
    MainDestination(Routes.TODAY, "今日事项", Icons.AutoMirrored.Filled.List),
    MainDestination(Routes.ACCOUNTS, "账号信息", Icons.Filled.Person),
    MainDestination(Routes.STATS, "数据统计", Icons.Filled.BarChart),
)

@Composable
fun DaiganRecordApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in Routes.mainDestinations

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    mainDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateToMain(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TODAY,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.TODAY) {
                TodayScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }
            composable(Routes.ACCOUNTS) {
                AccountsScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenOwner = { ownerId -> navController.navigate(Routes.ownerDetail(ownerId)) },
                    onCreateOwner = { navController.navigate(Routes.ownerForm()) },
                    onOpenArchived = { navController.navigate(Routes.ARCHIVED) },
                )
            }
            composable(Routes.STATS) {
                StatsScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSection = { section -> navController.navigate(Routes.settingsSection(section)) },
                )
            }
            composable(
                route = Routes.SETTINGS_PLACEHOLDER,
                arguments = listOf(navArgument(Routes.SECTION_ARG) { type = NavType.StringType }),
            ) { entry ->
                val section = SettingsSection.fromKey(entry.arguments?.getString(Routes.SECTION_ARG))
                PendingFeatureScreen(
                    title = section?.title ?: "设置",
                    message = section?.pendingMessage ?: "该设置项尚未实现。",
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ARCHIVED) {
                ArchivedRecordsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.OWNER_DETAIL,
                arguments = listOf(navArgument(Routes.OWNER_ID_ARG) { type = NavType.StringType }),
            ) {
                OwnerDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEditOwner = { ownerId -> navController.navigate(Routes.ownerForm(ownerId)) },
                    onAddAccount = { ownerId -> navController.navigate(Routes.accountForm(ownerId)) },
                    onEditAccount = { ownerId, accountId ->
                        navController.navigate(Routes.accountForm(ownerId, accountId))
                    },
                )
            }
            composable(
                route = Routes.OWNER_FORM,
                arguments = listOf(
                    navArgument(Routes.OWNER_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                OwnerFormScreen(onDone = { navController.popBackStack() })
            }
            composable(
                route = Routes.ACCOUNT_FORM,
                arguments = listOf(
                    navArgument(Routes.OWNER_ID_ARG) { type = NavType.StringType },
                    navArgument(Routes.ACCOUNT_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                AccountFormScreen(onDone = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateToMain(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

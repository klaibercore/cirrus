package dev.klaiber.cirrus.ui

import android.net.Uri
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.klaiber.cirrus.ui.chat.ChatScreen
import dev.klaiber.cirrus.ui.conversations.ConversationDrawer
import dev.klaiber.cirrus.ui.agents.AgentsScreen
import dev.klaiber.cirrus.ui.memory.MemoryScreen
import dev.klaiber.cirrus.ui.settings.SettingsScreen
import dev.klaiber.cirrus.ui.settings.SettingsSection
import dev.klaiber.cirrus.ui.settings.SettingsSectionScreen
import dev.klaiber.cirrus.ui.settings.mcp.McpServersScreen
import kotlinx.coroutines.launch

/** Content handed over from another app through a share intent. */
data class SharedPayload(
    val text: String? = null,
    val imageUri: Uri? = null,
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && imageUri == null
}

private object Routes {
    const val CHAT_ARG = "conversationId"
    const val CHAT = "chat"
    const val CHAT_PATTERN = "chat?$CHAT_ARG={$CHAT_ARG}"
    const val SETTINGS = "settings"
    const val SECTION_ARG = "section"
    const val SETTINGS_SECTION = "settings/section/{$SECTION_ARG}"
    const val MCP_SERVERS = "settings/mcp"
    const val MEMORY = "memory"
    const val AGENTS = "agents"
}

@Composable
fun CirrusApp(sharedPayload: SharedPayload = SharedPayload()) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val activeConversationId = backStackEntry?.arguments?.getString(Routes.CHAT_ARG)

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Swiping open from the transcript is only meaningful on the chat screen.
        gesturesEnabled = currentRoute == Routes.CHAT_PATTERN,
        drawerContent = {
            ConversationDrawer(
                activeConversationId = activeConversationId,
                onSelectConversation = { id ->
                    scope.launch { drawerState.close() }
                    navController.openChat(id)
                },
                onNewChat = {
                    scope.launch { drawerState.close() }
                    navController.openChat(null)
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.SETTINGS)
                },
            )
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT_PATTERN,
        ) {
            composable(
                route = Routes.CHAT_PATTERN,
                arguments = listOf(
                    navArgument(Routes.CHAT_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                ChatScreen(
                    sharedPayload = sharedPayload,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateToConversation = navController::openChat,
                    onNewChat = { navController.openChat(null) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSection = { section ->
                        navController.navigate("settings/section/${section.name}")
                    },
                    onOpenMemory = { navController.navigate(Routes.MEMORY) },
                    onOpenAgents = { navController.navigate(Routes.AGENTS) },
                )
            }

            composable(
                route = Routes.SETTINGS_SECTION,
                arguments = listOf(navArgument(Routes.SECTION_ARG) { type = NavType.StringType }),
            ) { entry ->
                SettingsSectionScreen(
                    section = SettingsSection.fromRoute(
                        entry.arguments?.getString(Routes.SECTION_ARG),
                    ),
                    onBack = { navController.popBackStack() },
                    onOpenMcpServers = { navController.navigate(Routes.MCP_SERVERS) },
                )
            }

            composable(Routes.MEMORY) {
                MemoryScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.AGENTS) {
                AgentsScreen(
                    onBack = { navController.popBackStack() },
                    // An agent's answer is an ordinary thread, so opening one is ordinary
                    // navigation: it lands in the transcript with the drawer and every action.
                    onOpenConversation = navController::openChat,
                )
            }

            composable(Routes.MCP_SERVERS) {
                McpServersScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * Replaces the current chat destination rather than stacking a new one.
 *
 * Each conversation gets its own back-stack entry and therefore its own ViewModel; without the
 * popUpTo, switching threads from the drawer would grow the back stack indefinitely.
 */
private fun NavHostController.openChat(conversationId: String?) {
    val target = if (conversationId == null) {
        Routes.CHAT
    } else {
        "${Routes.CHAT}?${Routes.CHAT_ARG}=$conversationId"
    }
    navigate(target) {
        popUpTo(Routes.CHAT_PATTERN) { inclusive = true }
    }
}

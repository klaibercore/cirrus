package dev.klaiber.cirrus.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.klaiber.cirrus.ui.onboarding.OnboardingScreen
import dev.klaiber.cirrus.ui.settings.SettingsScreen
import dev.klaiber.cirrus.ui.settings.SettingsSection
import dev.klaiber.cirrus.ui.settings.SettingsSectionScreen
import dev.klaiber.cirrus.ui.settings.SettingsViewModel
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
    const val SETUP = "setup"
}

@Composable
fun CirrusApp(
    sharedPayload: SharedPayload = SharedPayload(),
    startWithSetup: Boolean = false,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Frozen at first composition. Finishing the wizard flips the setting that produced it, and a
    // start destination that changed underneath a live NavHost would rebuild the graph mid-navigation.
    val startDestination = remember {
        if (startWithSetup) Routes.SETUP else Routes.CHAT_PATTERN
    }

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
            startDestination = startDestination,
        ) {
            // Finishing setup clears the stack rather than adding to it: nobody should be able to
            // press Back into a wizard they have already been through — and the same route is
            // reachable from Settings, where the stack underneath it is a different shape.
            composable(Routes.SETUP) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Routes.CHAT) {
                            popUpTo(navController.graph.id) { inclusive = true }
                        }
                    },
                )
            }

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
                    onRunSetup = { navController.navigate(Routes.SETUP) },
                )
            }

            composable(
                route = Routes.SETTINGS_SECTION,
                arguments = listOf(navArgument(Routes.SECTION_ARG) { type = NavType.StringType }),
            ) { entry ->
                val viewModel: SettingsViewModel = hiltViewModel()
                val context = LocalContext.current

                // The permission dialog and the browser both need an Activity, which a ViewModel
                // must not hold. Both therefore start here and report back.
                val locationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> viewModel.setLocationEnabled(granted, granted) }

                SettingsSectionScreen(
                    section = SettingsSection.fromRoute(
                        entry.arguments?.getString(Routes.SECTION_ARG),
                    ),
                    onBack = { navController.popBackStack() },
                    onOpenMcpServers = { navController.navigate(Routes.MCP_SERVERS) },
                    onLocationToggle = { wanted ->
                        if (wanted) {
                            // Asking is the switch. Android shows nothing if the permission is
                            // already held, in which case the callback answers immediately.
                            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        } else {
                            viewModel.setLocationEnabled(false, permissionGranted = false)
                        }
                    },
                    onSpotifyConnect = {
                        scope.launch {
                            viewModel.beginSpotifySignIn()?.let { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                }
                            }
                        }
                    },
                    viewModel = viewModel,
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

package dev.klaiber.cirrus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import dev.klaiber.cirrus.di.AppContainer
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.ui.chat.ChatModel
import dev.klaiber.cirrus.ui.chat.ChatScreen
import dev.klaiber.cirrus.ui.conversations.ConversationDrawer
import dev.klaiber.cirrus.ui.agents.AgentsModel
import dev.klaiber.cirrus.ui.agents.AgentsScreen
import dev.klaiber.cirrus.ui.conversations.ConversationsModel
import dev.klaiber.cirrus.ui.mcp.McpModel
import dev.klaiber.cirrus.ui.mcp.McpServersScreen
import dev.klaiber.cirrus.ui.memory.MemoryModel
import dev.klaiber.cirrus.ui.memory.MemoryScreen
import dev.klaiber.cirrus.ui.onboarding.OnboardingModel
import dev.klaiber.cirrus.ui.onboarding.OnboardingScreen
import dev.klaiber.cirrus.ui.theme.CirrusTheme
import kotlinx.coroutines.launch

/** Where the window currently is. */
sealed interface Screen {
    data object Setup : Screen

    data class Chat(val conversationId: String? = null) : Screen

    data object Settings : Screen

    data object Memory : Screen

    data object Agents : Screen

    data object McpServers : Screen
}

/**
 * The window's contents: a conversation drawer, and whichever screen is open.
 *
 * Routing is a list of screens rather than a `NavHost`. Navigation-compose exists for
 * multiplatform now, but what it buys on Android — a back stack that survives process death, deep
 * links, typed arguments across an activity boundary — is either irrelevant here or already
 * handled: a window has no process death to survive and no deep links to route. Six destinations
 * and a "go back" is a list.
 */
@Composable
fun CirrusApp(container: AppContainer) {
    val settings by container.settingsRepository.settings.collectAsState(AppSettings())

    CirrusTheme(themeMode = settings.themeMode) {
        Surface(Modifier.fillMaxSize()) {
            // Frozen at first composition, like Android's start destination: finishing the wizard
            // flips the setting that produced it, and re-reading it would send the user back in.
            val start = remember { if (settings.onboardingCompleted) Screen.Chat() else Screen.Setup }
            val backStack = remember { listOf<Screen>(start).toMutableStateList() }
            AppContent(container, backStack)
        }
    }
}

@Composable
private fun AppContent(container: AppContainer, backStack: SnapshotStateList<Screen>) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val current = backStack.last()

    fun go(screen: Screen) {
        backStack += screen
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Opening a thread replaces the chat rather than stacking a second one under it. */
    fun openChat(conversationId: String?) {
        backStack.clear()
        backStack += Screen.Chat(conversationId)
    }

    val conversationsModel = remember { ConversationsModel(container.conversationRepository, scope) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Swiping open from the transcript is only meaningful on the chat screen.
        gesturesEnabled = current is Screen.Chat,
        drawerContent = {
            ConversationDrawer(
                activeConversationId = (current as? Screen.Chat)?.conversationId,
                onSelectConversation = { id ->
                    scope.launch { drawerState.close() }
                    openChat(id)
                },
                onNewChat = {
                    scope.launch { drawerState.close() }
                    openChat(null)
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    go(Screen.Settings)
                },
                model = conversationsModel,
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            when (current) {
                is Screen.Setup -> {
                    val onboardingModel = remember {
                        OnboardingModel(
                            settings = container.settingsRepository,
                            models = container.modelRepository,
                            agents = container.agentRepository,
                            scheduler = container.agentScheduler,
                            scope = scope,
                        )
                    }
                    OnboardingScreen(
                        onFinished = { openChat(null) },
                        model = onboardingModel,
                    )
                }

                is Screen.Chat -> {
                    // Keyed on the thread: each conversation gets its own state, which is what the
                    // per-back-stack-entry ViewModel bought on Android.
                    val chatModel = remember(current.conversationId) {
                        ChatModel(
                            conversationRepository = container.conversationRepository,
                            agentRepository = container.agentRepository,
                            settingsRepository = container.settingsRepository,
                            modelRepository = container.modelRepository,
                            turnController = container.turnController,
                            speechController = container.speechController,
                            attachmentImporter = container.attachmentImporter,
                            suggestionGenerator = container.suggestionGenerator,
                            scope = scope,
                            initialConversationId = current.conversationId,
                        )
                    }
                    ChatScreen(
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSettings = { go(Screen.Settings) },
                        onNavigateToConversation = ::openChat,
                        onNewChat = { openChat(null) },
                        model = chatModel,
                    )
                }

                is Screen.Settings -> SettingsScreen(
                    container = container,
                    onClose = ::back,
                    onOpenMemory = { go(Screen.Memory) },
                    onOpenAgents = { go(Screen.Agents) },
                    onOpenMcpServers = { go(Screen.McpServers) },
                    onRunSetup = { go(Screen.Setup) },
                )

                is Screen.Memory -> {
                    val memoryModel = remember {
                        MemoryModel(
                            memories = container.memoryRepository,
                            settings = container.settingsRepository,
                            consolidation = container.consolidationScheduler,
                            scope = scope,
                        )
                    }
                    MemoryScreen(onBack = ::back, model = memoryModel)
                }

                is Screen.Agents -> {
                    val agentsModel = remember {
                        AgentsModel(
                            agents = container.agentRepository,
                            scheduler = container.agentScheduler,
                            suggestions = container.suggestionGenerator,
                            modelRepository = container.modelRepository,
                            settings = container.settingsRepository,
                            scope = scope,
                        )
                    }
                    AgentsScreen(
                        onBack = ::back,
                        onOpenConversation = ::openChat,
                        model = agentsModel,
                        notifier = container.notifier,
                    )
                }

                is Screen.McpServers -> {
                    val mcpModel = remember {
                        McpModel(repository = container.mcpServerRepository, scope = scope)
                    }
                    McpServersScreen(onBack = ::back, model = mcpModel)
                }
            }
        }
    }
}

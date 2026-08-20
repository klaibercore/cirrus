package dev.klaiber.cirrus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.di.AppContainer
import dev.klaiber.cirrus.domain.model.AppSettings
import dev.klaiber.cirrus.ui.chat.ChatModel
import dev.klaiber.cirrus.ui.chat.ChatScreen
import dev.klaiber.cirrus.ui.components.VerticalHairline
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
import dev.klaiber.cirrus.ui.window.TitleBarHeight
import dev.klaiber.cirrus.ui.window.TrafficLightWidth
import kotlinx.coroutines.launch

/**
 * The handful of things the window's menus can do.
 *
 * Passed *out* of the app rather than the menus being built inside it, because a `MenuBar` may
 * only be declared inside the window's own scope and the state these act on lives in here. The
 * three of them are the whole of what a menu on this app can usefully offer: everything else is
 * either about one screen or is a control the screen already shows.
 */
data class AppActions(
    val newChat: () -> Unit,
    val openSettings: () -> Unit,
    val toggleSidebar: () -> Unit,
)

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
 * The width below which the sidebar stops being permanent.
 *
 * Not a phone breakpoint transplanted onto a desktop: it is the width at which 268pt of sidebar
 * stops leaving the transcript enough room to be worth reading, and the conversation list is
 * better summoned than resident. A window can be dragged across this line at any moment, which is
 * why both containers exist rather than one being chosen at launch.
 */
private val SidebarBreakpoint = 880.dp

/**
 * The window's contents: a conversation list, and whichever screen is open.
 *
 * Routing is a list of screens rather than a `NavHost`. Navigation-compose exists for
 * multiplatform now, but what it buys on Android — a back stack that survives process death, deep
 * links, typed arguments across an activity boundary — is either irrelevant here or already
 * handled: a window has no process death to survive and no deep links to route. Six destinations
 * and a "go back" is a list.
 */
@Composable
fun CirrusApp(
    container: AppContainer,
    menuBar: @Composable (AppActions) -> Unit = {},
) {
    // `collectAsState()` and not `collectAsState(AppSettings())`. Passing an initial value selects
    // the plain-`Flow` overload, which shows that value for the first composition and only catches
    // up a frame later — so composition one saw a *default* `AppSettings`, with the theme set to
    // light and onboarding unfinished. The theme corrected itself on the next frame and the flash
    // read as a slow start; the start destination did not, because it is remembered from exactly
    // that frame, and a user who had finished the wizard weeks ago was posted back to step one of
    // it on every launch. `settings` is a `StateFlow` the container has already loaded before the
    // first window exists, so its current value is right there to be read.
    val settings by container.settingsRepository.settings.collectAsState()

    CirrusTheme(themeMode = settings.themeMode) {
        Surface(Modifier.fillMaxSize()) {
            // Frozen at first composition, like Android's start destination: finishing the wizard
            // flips the setting that produced it, and re-reading it would send the user back in.
            val start = remember { if (settings.onboardingCompleted) Screen.Chat() else Screen.Setup }
            val backStack = remember { listOf<Screen>(start).toMutableStateList() }
            AppContent(container, backStack, menuBar)
        }
    }
}

@Composable
private fun AppContent(
    container: AppContainer,
    backStack: SnapshotStateList<Screen>,
    menuBar: @Composable (AppActions) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val current = backStack.last()

    // Whether the user wants the sidebar, which is a different question from whether the window
    // can hold one. Both have to be true for it to show, and the toggle is remembered across a
    // resize so dragging a window narrow and wide again does not silently undo a preference.
    var sidebarWanted by remember { mutableStateOf(true) }

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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The wizard owns the whole window. It is the one screen with nothing to navigate between,
        // and a conversation list beside "step 1 of 6" offers a way out of a flow whose entire job
        // is to be finished.
        val sidebarFits = maxWidth >= SidebarBreakpoint && current !is Screen.Setup
        val sidebarVisible = sidebarWanted && sidebarFits

        // The window's menus, and with them the only owner of these three shortcuts. Handling them
        // *as well* on a root key handler was the obvious thing and the wrong one: a menu key
        // equivalent is consumed before the keystroke reaches the focused component, so a second
        // handler either never runs or — on the platforms where it does — toggles the sidebar
        // twice and looks like the key did nothing at all. One owner, and the menu is it, because
        // the menu is also where somebody finds out the shortcut exists.
        menuBar(
            AppActions(
                newChat = { openChat(null) },
                openSettings = { if (current !is Screen.Settings) go(Screen.Settings) },
                toggleSidebar = {
                    if (sidebarFits) {
                        sidebarWanted = !sidebarWanted
                    } else {
                        scope.launch {
                            if (drawerState.isOpen) drawerState.close() else drawerState.open()
                        }
                    }
                },
            ),
        )

        /** Whichever screen the back stack has arrived at, given the room left beside the list. */
        @Composable
        fun Destination() {
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
                        // With the list resident there is nothing for a hamburger to reveal, so
                        // the button becomes a collapse instead. The two insets are independent:
                        // the transparent title bar reaches across the *whole* window, so every
                        // pane owes it a strip, but the traffic lights sit at the far left and are
                        // only this pane's problem when there is no sidebar in front of them.
                        sidebarVisible = sidebarVisible,
                        onToggleSidebar = if (sidebarFits) {
                            { sidebarWanted = !sidebarWanted }
                        } else {
                            null
                        },
                        leadingInset = if (sidebarVisible) 0.dp else TrafficLightWidth,
                        topInset = TitleBarHeight,
                    )
                }

                is Screen.Settings -> SettingsScreen(
                    container = container,
                    onClose = ::back,
                    onOpenMemory = { go(Screen.Memory) },
                    onOpenAgents = { go(Screen.Agents) },
                    onOpenMcpServers = { go(Screen.McpServers) },
                    onRunSetup = { go(Screen.Setup) },
                    topInset = TitleBarHeight,
                    leadingInset = if (sidebarVisible) 0.dp else TrafficLightWidth,
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
                    MemoryScreen(
                        onBack = ::back,
                        model = memoryModel,
                        topInset = TitleBarHeight,
                        leadingInset = if (sidebarVisible) 0.dp else TrafficLightWidth,
                    )
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
                        topInset = TitleBarHeight,
                        leadingInset = if (sidebarVisible) 0.dp else TrafficLightWidth,
                    )
                }

                is Screen.McpServers -> {
                    val mcpModel = remember {
                        McpModel(repository = container.mcpServerRepository, scope = scope)
                    }
                    McpServersScreen(
                        onBack = ::back,
                        model = mcpModel,
                        topInset = TitleBarHeight,
                        leadingInset = if (sidebarVisible) 0.dp else TrafficLightWidth,
                    )
                }
            }
        }

        @Composable
        fun Sidebar(embedded: Boolean) {
            ConversationDrawer(
                activeConversationId = (current as? Screen.Chat)?.conversationId,
                onSelectConversation = { id ->
                    if (!embedded) scope.launch { drawerState.close() }
                    openChat(id)
                },
                onNewChat = {
                    if (!embedded) scope.launch { drawerState.close() }
                    openChat(null)
                },
                onOpenSettings = {
                    if (!embedded) scope.launch { drawerState.close() }
                    go(Screen.Settings)
                },
                model = conversationsModel,
                embedded = embedded,
                // Resident, the list is the pane under the traffic lights and pays the strip like
                // every other. As a drawer it is laid *over* a window whose panes have already
                // paid it, and insetting again would leave a band of nothing at the top of it.
                topInset = if (embedded) TitleBarHeight else 0.dp,
            )
        }

        Box(Modifier.fillMaxSize()) {
            if (sidebarVisible) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(embedded = true)
                    VerticalHairline(Modifier.fillMaxHeight())
                    Box(Modifier.fillMaxHeight().weight(1f)) { Destination() }
                }
            } else {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    // Swiping open from the transcript is only meaningful on the chat screen, and
                    // only when the list is not already standing beside it.
                    gesturesEnabled = current is Screen.Chat && !sidebarFits,
                    drawerContent = { Sidebar(embedded = false) },
                ) {
                    Box(Modifier.fillMaxSize()) { Destination() }
                }
            }
        }
    }
}

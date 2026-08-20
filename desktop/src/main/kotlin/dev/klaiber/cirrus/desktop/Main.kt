package dev.klaiber.cirrus.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.di.AppContainer
import dev.klaiber.cirrus.ui.CirrusApp
import dev.klaiber.cirrus.ui.window.LocalWindowTitle
import dev.klaiber.cirrus.ui.window.applyNativeChrome
import dev.klaiber.cirrus.ui.window.isMac
import dev.klaiber.cirrus.ui.window.prepareNativeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.awt.Dimension
import java.io.File

/**
 * The desktop entry point.
 *
 * The application scope is created here and outlives every window, for the same reason
 * `TurnController` runs on Android's application scope: a turn, a title request and a memory write
 * must not be cancelled because the screen showing them went away.
 */
fun main() {
    // Before anything touches AWT: the toolkit reads the application name and appearance once, at
    // initialisation, and a window created first locks in the main class's name instead.
    prepareNativeApplication()

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val container = AppContainer(dataDir = dataDirectory(), scope = applicationScope)

    // The stores are small local files and the window has nothing to show without them, so this is
    // read before the first frame rather than raced against it.
    runBlocking { container.start() }

    application {
        val windowState = rememberWindowState(size = DpSize(1180.dp, 820.dp))
        val windowTitle = remember { mutableStateOf("Cirrus") }
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = windowTitle.value,
        ) {
            LaunchedEffect(window) {
                applyNativeChrome(window)
                // Below this the sidebar and the transcript cannot both hold their measure, and
                // the composer starts wrapping its own controls. A window that cannot be dragged
                // into a broken layout is one less state to design for.
                window.minimumSize = Dimension(720, 520)
            }
            // A tray click brings the window forward; the notifier only knows that something
            // should happen, not what window there is to raise.
            container.notifier.onTrayClick = {
                window.toFront()
                window.requestFocus()
            }
            CompositionLocalProvider(LocalWindowTitle provides windowTitle) {
                CirrusApp(container) { actions ->
                    // Declared here because a `MenuBar` may only be built in the window's own
                    // scope, and driven by lambdas handed out from inside the app because that is
                    // where the state they act on lives. On macOS this lands in the system menu
                    // bar; elsewhere it draws at the top of the window. Either way it is where a
                    // user finds out these shortcuts exist, which is most of why it is here — the
                    // app worked without a menu, it simply gave nobody a way to learn it.
                    MenuBar {
                        Menu("File", mnemonic = 'F') {
                            Item(
                                text = "New Chat",
                                shortcut = accelerator(Key.N),
                                onClick = actions.newChat,
                            )
                            Separator()
                            Item(
                                text = "Settings…",
                                shortcut = accelerator(Key.Comma),
                                onClick = actions.openSettings,
                            )
                        }
                        Menu("View", mnemonic = 'V') {
                            Item(
                                text = "Toggle Sidebar",
                                shortcut = accelerator(Key.Backslash),
                                onClick = actions.toggleSidebar,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The platform's own modifier for a menu shortcut.
 *
 * Command on macOS and Control everywhere else. Getting this from the platform rather than picking
 * one is the difference between a menu that reads as native and one that reads as ported.
 */
private fun accelerator(key: Key): KeyShortcut =
    KeyShortcut(key, meta = isMac, ctrl = !isMac)

/**
 * Where Cirrus keeps its data, following each platform's own convention.
 *
 * Not the working directory: a packaged app is launched from wherever the launcher happens to be,
 * and writing conversations next to the binary means losing them on the next install.
 */
private fun dataDirectory(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = File(System.getProperty("user.home") ?: ".")
    val directory = when {
        os.contains("mac") -> File(home, "Library/Application Support/Cirrus")
        os.contains("win") -> File(System.getenv("APPDATA") ?: home.path, "Cirrus")
        else -> File(System.getenv("XDG_DATA_HOME") ?: File(home, ".local/share").path, "cirrus")
    }
    directory.mkdirs()
    return directory
}

package dev.klaiber.cirrus.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.klaiber.cirrus.di.AppContainer
import dev.klaiber.cirrus.ui.CirrusApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The desktop entry point.
 *
 * The application scope is created here and outlives every window, for the same reason
 * `TurnController` runs on Android's application scope: a turn, a title request and a memory write
 * must not be cancelled because the screen showing them went away.
 */
fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val container = AppContainer(dataDir = dataDirectory(), scope = applicationScope)

    // The stores are small local files and the window has nothing to show without them, so this is
    // read before the first frame rather than raced against it.
    runBlocking { container.start() }

    application {
        val windowState = rememberWindowState(size = DpSize(1180.dp, 820.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Cirrus",
        ) {
            // A tray click brings the window forward; the notifier only knows that something
            // should happen, not what window there is to raise.
            container.notifier.onTrayClick = {
                window.toFront()
                window.requestFocus()
            }
            CirrusApp(container)
        }
    }
}

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

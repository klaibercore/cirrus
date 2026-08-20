package dev.klaiber.cirrus.ui.window

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import javax.swing.JFrame

/** Whether this JVM is on macOS, which is the only platform whose chrome we take over. */
val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/**
 * How far the system title bar reaches down over content Cirrus has drawn.
 *
 * A standard macOS title bar is 28pt. Once [applyNativeChrome] has made it transparent the strip
 * is still *there* — it still drags the window, still zooms on a double-click, still holds the
 * traffic lights — it simply has no background of its own any more, and shows whatever the app
 * painted underneath. Everything below is a consequence of that: the app owes the strip a
 * background, and owes it a clear path for the mouse.
 */
val TitleBarHeight: Dp = if (isMac) 28.dp else 0.dp

/**
 * The gutter the traffic lights occupy at the left of that strip.
 *
 * Three 12pt buttons on 20pt centres starting 20pt in, plus room to breathe. Anything Cirrus draws
 * in the top-left has to start after this or it lands under the close button.
 */
val TrafficLightWidth: Dp = if (isMac) 78.dp else 0.dp

/**
 * The window's title, hoisted so a screen can say what the window is showing.
 *
 * On macOS the title text is hidden from the bar itself — the whole point of the exercise — but it
 * still names the window in Mission Control, in the Window menu and in the app switcher's window
 * list, so it is worth keeping honest.
 */
val LocalWindowTitle = compositionLocalOf { mutableStateOf("Cirrus") }

/**
 * Hands the title bar to the app.
 *
 * macOS draws a window's title bar in its own grey, from its own appearance, which is why Cirrus
 * looked like a black app that somebody had glued a Finder window on top of: a light bar, a
 * centred title in the system face, and a hard seam where it met the transcript. The fix is not to
 * restyle that bar — AWT gives no way to — but to stop it drawing at all:
 *
 * - `fullWindowContent` extends the content view up under the title bar, so the app's own surface
 *   is what shows through.
 * - `transparentTitleBar` stops the bar painting its background and its separator over that.
 * - `windowTitleVisible` removes the centred title text, which would otherwise sit on top of
 *   whatever the app drew there.
 *
 * The traffic lights stay, because they are the part users actually need and the part no app
 * should be reimplementing. What is left is a window whose chrome is the same colour as its
 * content in both themes, which is what every professionally finished Mac app does and what the
 * grey bar was getting in the way of.
 *
 * Nothing here is reversed on other platforms: the properties are ignored off macOS, but the
 * layout constants above are already zero there, so the app simply lays out against a real title
 * bar as before.
 */
fun applyNativeChrome(frame: JFrame) {
    if (!isMac) return
    frame.rootPane.apply {
        putClientProperty("apple.awt.fullWindowContent", true)
        putClientProperty("apple.awt.transparentTitleBar", true)
        putClientProperty("apple.awt.windowTitleVisible", false)
    }
}

/**
 * Properties that have to be set before AWT wakes up.
 *
 * The menu bar and the Dock take their name from the main class unless told otherwise, which is
 * why a development run announced itself as "MainKt". Both are read once, when the AWT toolkit
 * initialises, so setting them after the first window exists does nothing at all.
 *
 * `appearance = system` is what lets the traffic lights and the standard menus follow the desktop's
 * light/dark setting. It is deliberately not tied to Cirrus's own theme setting: those buttons are
 * the operating system's furniture, and a user who has chosen a dark desktop expects them dark
 * whichever theme an individual app is wearing.
 */
fun prepareNativeApplication() {
    if (!isMac) return
    System.setProperty("apple.awt.application.name", "Cirrus")
    System.setProperty("apple.awt.application.appearance", "system")
}

package dev.klaiber.cirrus.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Copy helper backed by the platform clipboard.
 *
 * AWT's system clipboard rather than Compose's clipboard local, for the reason the Android build
 * uses the framework service: that Compose API has churned across recent releases, and this one has
 * not moved since 1.1.
 */
class Clipboard {

    fun copy(text: String, label: String = "Cirrus") {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    /**
     * No desktop shows a copy confirmation of its own, so callers have to say so themselves —
     * the mirror image of Android 13+, where saying it would stack two notices.
     */
    val showsSystemConfirmation: Boolean get() = false
}

@Composable
fun rememberClipboard(): Clipboard = remember { Clipboard() }

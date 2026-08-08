package dev.klaiber.cirrus.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Copy helper backed by the platform clipboard.
 *
 * Uses the framework service rather than Compose's clipboard local because that API has churned
 * across recent Compose releases while this one has been stable since API 11.
 */
class Clipboard(private val context: Context) {

    fun copy(text: String, label: String = "Cirrus") {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * Android 13+ shows its own copy confirmation, so callers should stay silent there to avoid
     * stacking two notices on top of each other.
     */
    val showsSystemConfirmation: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

@Composable
fun rememberClipboard(): Clipboard {
    val context = LocalContext.current
    return remember(context) { Clipboard(context.applicationContext) }
}

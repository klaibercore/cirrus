package dev.klaiber.cirrus.ui.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The two file dialogs the app needs, as blocking calls.
 *
 * AWT's `FileDialog` rather than Swing's `JFileChooser` because it is the platform's own dialog on
 * macOS and Windows — a `JFileChooser` on macOS is instantly recognisable as not belonging, which
 * is the one thing a monochrome copy of somebody's design system cannot afford.
 *
 * These block the calling thread until the user answers, so call them off the UI dispatcher.
 */
object FileDialogs {

    /** Picks a file to attach, or null if the dialog was dismissed. */
    fun open(title: String = "Attach a file"): File? = dialog(title, FileDialog.LOAD) { dialog ->
        // A filename filter is advisory on macOS and enforced elsewhere; either way the importer
        // is the thing that decides, since it has to read the bytes to know.
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in ALLOWED }
    }

    /** Picks where to write an export, or null if the dialog was dismissed. */
    fun save(suggestedName: String, title: String = "Export conversation"): File? =
        dialog(title, FileDialog.SAVE) { dialog -> dialog.file = suggestedName }

    private fun dialog(title: String, mode: Int, configure: (FileDialog) -> Unit): File? {
        val dialog = FileDialog(null as Frame?, title, mode).apply(configure)
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(directory, name)
    }

    private val ALLOWED = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp",
        "txt", "md", "json", "csv", "log", "yaml", "yml", "xml", "html",
        "kt", "java", "py", "js", "ts", "rs", "go", "c", "h", "cpp", "sh",
    )
}

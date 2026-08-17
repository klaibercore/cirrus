package dev.klaiber.cirrus.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One JSON file, read and written atomically.
 *
 * The desktop build has no Room and no DataStore, so persistence is a plain file per store. A
 * [Mutex] serialises access because a turn can finalise a message at the same moment the user
 * renames the thread it belongs to, and two coroutines writing the same file at once would
 * interleave into JSON that no longer parses.
 */
class JsonStore(
    private val file: File,
    private val json: Json,
) {
    private val mutex = Mutex()

    /** Reads the file, or [default] when it is missing or unreadable. */
    suspend fun <T> read(serializer: KSerializer<T>, default: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext default()
            runCatching { json.decodeFromString(serializer, file.readText()) }
                .getOrElse { default() }
        }
    }

    /** Writes the file via a temp sibling, so a crash mid-write never leaves a half file. */
    suspend fun <T> write(serializer: KSerializer<T>, value: T) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}

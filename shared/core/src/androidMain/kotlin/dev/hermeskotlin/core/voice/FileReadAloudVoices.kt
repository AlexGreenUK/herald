package dev.hermeskotlin.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Kept in [dir] as well, so a reply read again is the same take after the app was closed or let go of: a folder
 * for each of the last [replies] replies, a file for each piece. Android may empty a cache folder when space runs
 * short; the reply is then voiced anew.
 */
class FileReadAloudVoices(private val dir: File, private val replies: Int = 30) : ReadAloudVoices {
    private val memory = InMemoryReadAloudVoices()

    override suspend fun piece(key: String, index: Int): SpokenAudio? = memory.piece(key, index) ?: withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(dir, name(key))
            val file = folder.listFiles()?.firstOrNull { it.name.substringBefore('.') == index.toString() } ?: return@runCatching null
            folder.setLastModified(System.currentTimeMillis())
            SpokenAudio(file.readBytes(), file.name.substringAfter('.').replaceFirst('_', '/'))
        }.getOrNull()
    }

    override suspend fun keep(key: String, index: Int, audio: SpokenAudio) {
        memory.keep(key, index, audio)
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(dir, name(key)).apply { mkdirs() }
                File(folder, "$index.${audio.mimeType.replace('/', '_').filter { it.isLetterOrDigit() || it in "_.-+;=" }}").writeBytes(audio.bytes)
                folder.setLastModified(System.currentTimeMillis())
                dir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }?.drop(replies)?.forEach { it.deleteRecursively() }
            }
        }
    }

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}

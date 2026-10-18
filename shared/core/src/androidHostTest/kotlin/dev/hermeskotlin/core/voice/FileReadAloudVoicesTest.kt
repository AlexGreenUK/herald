package dev.hermeskotlin.core.voice

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileReadAloudVoicesTest {

    @Test
    fun aReplyReadAgainAfterTheAppWasClosedIsTheSameTake() = runTest {
        val dir = Files.createTempDirectory("read-aloud").toFile()
        FileReadAloudVoices(dir).apply {
            keep("gw\nwork\nAll green.", 0, SpokenAudio(byteArrayOf(1, 2, 3), "audio/mpeg"))
            keep("gw\nwork\nAll green.", 1, SpokenAudio(byteArrayOf(4, 5), "audio/ogg;codecs=opus"))
        }

        // A new one over the same folder, as after the app was let go of.
        val again = FileReadAloudVoices(dir)
        val first = again.piece("gw\nwork\nAll green.", 0)!!
        assertContentEquals(byteArrayOf(1, 2, 3), first.bytes)
        assertEquals("audio/mpeg", first.mimeType)
        assertEquals("audio/ogg;codecs=opus", again.piece("gw\nwork\nAll green.", 1)!!.mimeType)
        assertNull(again.piece("gw\nwork\nAll green.", 2))
        assertNull(again.piece("gw\nhome\nAll green.", 0))
        dir.deleteRecursively()
    }

    @Test
    fun onlyTheLastFewRepliesAreKept() = runTest {
        val dir = Files.createTempDirectory("read-aloud").toFile()
        val voices = FileReadAloudVoices(dir, replies = 2)
        for (reply in listOf("one", "two", "three")) {
            voices.keep(reply, 0, SpokenAudio(reply.encodeToByteArray(), "audio/mpeg"))
            // Folder times are kept to the second on some file systems.
            Thread.sleep(1_100)
        }

        assertEquals(2, dir.listFiles()!!.size)
        assertNull(FileReadAloudVoices(dir).piece("one", 0))
        dir.deleteRecursively()
    }
}

package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the "New messages" line goes when a chat opens with messages since it was last read. */
class UnreadDividerTest {

    private fun prompt(key: String, at: Double?) = ChatMessage.User(key, text = key, timestamp = at)
    private fun reply(key: String, at: Double?, streaming: Boolean = false) =
        ChatMessage.Assistant(key, text = key, streaming = streaming, timestamp = at)

    @Test
    fun theLineGoesAboveTheFirstMessageAfterTheLastLook() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), prompt("p2", 200.0), reply("r2", 210.0))

        assertEquals("p2", firstUnreadKey(messages, readUpTo = 150.0))
    }

    @Test
    fun nothingNewMeansNoLine() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0))

        assertNull(firstUnreadKey(messages, readUpTo = 110.0))
    }

    @Test
    fun timesWithinTheRoundingSlackCountAsRead() {
        val messages = listOf(reply("r1", 110.5), reply("r2", 140.0))

        assertEquals("r2", firstUnreadKey(messages, readUpTo = 110.0))
    }

    @Test
    fun aReplyStillBeingWrittenIsNew() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", at = null, streaming = true))

        assertEquals("r1", firstUnreadKey(messages, readUpTo = 105.0))
    }

    @Test
    fun olderPagesLoadWhileEverythingLoadedIsStillNew() {
        val allNew = listOf(prompt("p9", 900.0), reply("r9", 910.0))

        assertTrue(needsOlderForUnread(allNew, readUpTo = 100.0, olderMessages = true))
        assertFalse(needsOlderForUnread(allNew, readUpTo = 100.0, olderMessages = false))
    }

    @Test
    fun noOlderPageIsNeededOnceTheLastLookIsInView() {
        val messages = listOf(prompt("p1", 100.0), reply("r1", 110.0), reply("r2", 300.0))

        assertFalse(needsOlderForUnread(messages, readUpTo = 150.0, olderMessages = true))
    }
}

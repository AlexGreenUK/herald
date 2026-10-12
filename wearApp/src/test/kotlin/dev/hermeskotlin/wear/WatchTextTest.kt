package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.ReplyPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchTextTest {

    @Test
    fun agoSaysHowLongAgoTheWayAListDoes() {
        val now = 1_000_000_000_000L
        val seconds = now / 1000.0
        assertNull(ago(null, now))
        assertEquals("now", ago(seconds - 30, now))
        assertEquals("5m", ago(seconds - 5 * 60, now))
        assertEquals("3h", ago(seconds - 3 * 3600, now))
        assertEquals("2d", ago(seconds - 2 * 86_400, now))
        assertEquals("3w", ago(seconds - 21 * 86_400, now))
    }

    @Test
    fun theWatchVoiceReadsWordsNotMarkdown() {
        val reply = "**Saturday** looks `sunny`. See [the forecast](https://example.com/f) or https://example.com\n```\ncode\n```"
        assertEquals("Saturday looks sunny. See the forecast or", spokenText(reply))
    }

    @Test
    fun theActivityLineFallsBackToThePhase() {
        assertEquals("Running a command", activityLine(ReplyPhase.Working, "Running a command"))
        assertEquals("Thinking…", activityLine(ReplyPhase.Working, null))
        assertEquals("Writing it down…", activityLine(ReplyPhase.Transcribing, null))
        assertEquals("Waiting for you on your phone", activityLine(ReplyPhase.WaitingOnPhone, null))
    }

    @Test
    fun aLongModelNameIsCutToFitBesideTheClock() {
        assertEquals("GPT-5", shortModel("GPT-5"))
        assertEquals("Claude Sonn…", shortModel("Claude Sonnet 4.5 Thinking"))
    }

    @Test
    fun theContextRingWarnsAsTheChatFillsUp() {
        assertEquals(HeraldBlueText, contextTint(0.5f))
        assertEquals(Amber, contextTint(0.8f))
        assertEquals(Danger, contextTint(0.95f))
    }
}

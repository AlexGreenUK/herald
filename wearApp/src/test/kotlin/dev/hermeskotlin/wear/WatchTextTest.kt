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
    fun onlyTheEventsThePhoneActsOnAreToldApart() {
        assertEquals("session.delegation.created", eventType("""{"type":"session.delegation.created","delegation":{"id":"d1"}}"""))
        assertNull(eventType("not json"))
        assertNull(eventType("""["type"]"""))
    }

    @Test
    fun muteTellsTheVoiceModel() {
        assertEquals("""{"event_id":"m1","type":"session.input_audio.mute"}""", muteEvent("m1", true))
        assertEquals("""{"event_id":"m2","type":"session.input_audio.unmute"}""", muteEvent("m2", false))
    }

    @Test
    fun aCallSaysWhereItStands() {
        val live = WatchCallState("c1", CallStage.Live)
        assertEquals("Live", callCaption(live))
        assertEquals(OrbMood.Listening, callMood(live))
        assertEquals("Muted", callCaption(live.copy(muted = true)))
        assertEquals("Hermes: terminal", callCaption(live.copy(thinking = true, working = "terminal")))
        assertEquals(OrbMood.Working, callMood(live.copy(thinking = true)))
        assertEquals(OrbMood.Speaking, callMood(live.copy(speaking = true)))
        assertEquals("Call ended", callCaption(live.copy(stage = CallStage.Ended)))
        assertEquals(OrbMood.Failed, callMood(live.copy(stage = CallStage.Ended, error = "The call dropped.")))
        assertEquals("Connecting…", callCaption(WatchCallState("c2", CallStage.Connecting)))
    }

    @Test
    fun theTimeOnACallReadsAsOnACallScreen() {
        assertEquals("00:00", callTimer(0))
        assertEquals("00:03", callTimer(3_400))
        assertEquals("04:07", callTimer(247_000))
        assertEquals("1:02:03", callTimer(3_723_000))
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

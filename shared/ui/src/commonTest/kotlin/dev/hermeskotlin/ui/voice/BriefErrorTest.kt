package dev.hermeskotlin.ui.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BriefErrorTest {

    @Test
    fun aProvidersRequestUrlIsLeftOut() {
        val raw = "TTS chunk 1 failed (edge): TTS generation failed (edge): 403, message='Invalid response status', " +
            "url='wss://speech.example.com/consumer/speech/synthesize/v1?TrustedClientToken=ABC123&ConnectionId=42'"

        assertEquals("TTS chunk 1 failed (edge): TTS generation failed (edge): 403, message='Invalid response status'", briefError(raw))
    }

    @Test
    fun anAddressKeepsItsPlaceButNotItsQuery() {
        assertEquals("Couldn't fetch the voice from https://tts.example.com/v1", briefError("Couldn't fetch the voice from https://tts.example.com/v1?key=secret"))
        assertEquals("Can't reach the gateway: Failed to connect to https://hermes.example.com", briefError("Can't reach the gateway: Failed to connect to https://hermes.example.com"))
    }

    @Test
    fun onlyTheFirstLineShows() {
        assertEquals("Speech-to-text failed.", briefError("\nSpeech-to-text failed.\nTraceback (most recent call last):\n  File \"x.py\""))
    }

    @Test
    fun aVeryLongMessageIsCut() {
        val brief = briefError("A".repeat(500))

        assertEquals(200, brief.length)
        assertTrue(brief.endsWith("…"))
    }

    @Test
    fun aLineThatIsOnlyTheUrlFallsToTheNextOne() {
        assertEquals("403 Forbidden", briefError("url='wss://speech.example.com/v1?token=ABC'\n403 Forbidden"))
        assertEquals("The voice service failed.", briefError("url='wss://speech.example.com/v1?token=ABC'"))
    }

    @Test
    fun anAddressEndsWhereTheSentenceCloses() {
        assertEquals(
            "Timed out (https://tts.example.com/v1) after 30 s",
            briefError("Timed out (https://tts.example.com/v1?key=secret) after 30 s"),
        )
    }

    @Test
    fun fragmentsAndSignInsAreLeftOut() {
        assertEquals(
            "Failed: https://tts.example.com/v1 and wss://host.example.com/s",
            briefError("Failed: https://user:pass@tts.example.com/v1#access_token=XYZ and wss://host.example.com/s"),
        )
    }

    @Test
    fun urlInProseIsntAnArgument() {
        assertEquals("Set the url = the gateway's address", briefError("Set the url = the gateway's address"))
    }

    @Test
    fun ordinaryMessagesAreUntouched() {
        assertEquals("Couldn't voice the reply.", briefError("Couldn't voice the reply."))
        assertEquals("Didn't catch anything.", briefError("Didn't catch anything."))
    }
}

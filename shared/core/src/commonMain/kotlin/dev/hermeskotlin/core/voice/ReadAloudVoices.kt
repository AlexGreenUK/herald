package dev.hermeskotlin.core.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The voice made for replies read aloud, kept so a reply read again plays the same take from its start rather
 * than a new one: a voice like ElevenLabs says the same text differently each time. Kept by [key] (the gateway,
 * profile and text) and each piece's place in the reply.
 */
interface ReadAloudVoices {
    suspend fun piece(key: String, index: Int): SpokenAudio?

    suspend fun keep(key: String, index: Int, audio: SpokenAudio)
}

/** Kept while the app runs: the voices of the last [replies] replies read aloud. */
class InMemoryReadAloudVoices(private val replies: Int = 5) : ReadAloudVoices {
    private val mutex = Mutex()
    private val voices = LinkedHashMap<String, MutableList<SpokenAudio>>()

    override suspend fun piece(key: String, index: Int): SpokenAudio? = mutex.withLock { recent(key).getOrNull(index) }

    override suspend fun keep(key: String, index: Int, audio: SpokenAudio) = mutex.withLock {
        val pieces = recent(key)
        if (index == pieces.size) pieces += audio
    }

    /** The pieces kept for [key], now the most recent; the oldest are let go past [replies]. */
    private fun recent(key: String): MutableList<SpokenAudio> {
        val pieces = voices.remove(key) ?: mutableListOf()
        voices[key] = pieces
        while (voices.size > replies) voices.remove(voices.keys.first())
        return pieces
    }
}

package dev.hermeskotlin.core.inbox

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxTest {

    // Each time its own run, as the gateway names them: a run is its job and when it finished.
    private fun run(job: String = "006998ffc458", at: Long = 1_000, text: String = "Morning", status: String = InboxRun.OK) =
        InboxRun(bot = "default", job = job, run = "2026-10-12_${(at / 3600).toString().padStart(2, '0')}-${(at / 60 % 60).toString().padStart(2, '0')}-${(at % 60).toString().padStart(2, '0')}",
            label = "morning-briefing", status = status, at = at, text = text)

    @Test
    fun aCronRunPushBecomesARun() {
        val json = """{"id":"x","ts":1760245327,"type":"cron_run","bot":"default","job":"006998ffc458",
            "run":"2026-10-12_08-02-07","label":"morning-briefing","status":"ok","notify":true,"at":1760245326,"text":"Rain later."}"""
        val message = HermesJson.decodeFromString(PushMessage.serializer(), json)
        assertTrue(message.notify)
        val run = InboxRun.from(message)!!
        assertEquals("default/006998ffc458/2026-10-12_08-02-07", run.key)
        assertEquals("morning-briefing", run.title)
        assertEquals(1760245326, run.at)
        assertFalse(run.failed)
    }

    @Test
    fun aPushMissingWhatNamesTheRunOrNamingItOddlyIsDropped() {
        fun push(job: String?, run: String?, bot: String? = "default") =
            PushMessage(id = "x", ts = 1, type = PushMessage.CRON_RUN, bot = bot, job = job, run = run)
        assertNull(InboxRun.from(push(null, "2026-10-12_08-02-07")))
        assertNull(InboxRun.from(push("../../x", "2026-10-12_08-02-07")))
        assertNull(InboxRun.from(push("job", "yesterday")))
        // An odd profile falls back to the default one rather than being trusted.
        assertEquals("default", InboxRun.from(push("job", "2026-10-12_08-02-07", bot = "a/b"))!!.bot)
    }

    @Test
    fun aRunsLabelLosesControlCharactersAndAnUnknownStatusIsOk() {
        val message = PushMessage(id = "x", ts = 5, type = PushMessage.CRON_RUN, job = "j", run = "2026-10-12_08-02-07",
            label = "Brief\u0007ing", status = "weird")
        val run = InboxRun.from(message)!!
        assertEquals("Briefing", run.title)
        assertFalse(run.failed)
        assertEquals(5, run.at)
    }

    @Test
    fun followingAddsChangesAndDropsJobs() {
        val following = Following().with("a", Follow.Notify).with("b", Follow.Quiet)
        assertEquals(Follow.Notify, following.of("a"))
        assertEquals(Follow.Off, following.of("c"))
        assertEquals(mapOf("b" to Follow.Quiet), following.with("a", Follow.Off).jobs)
        assertTrue(following.any)
        assertFalse(Following().any)
        assertTrue(Following(failures = true).any)
        assertEquals(Follow.Off, Follow.of("loud"))
        assertEquals(Follow.Off, Follow.of(null))
    }

    @Test
    fun theFirstLookStartsReadAndLaterRunsAreUnread() = runTest {
        val store = InboxStore(InMemoryKeyValueStore())
        store.bind("https://gw", now = 2_000)
        store.replace(listOf(run(at = 1_500), run(at = 2_500)))
        val state = store.state.value
        assertEquals(listOf(2_500L, 1_500L), state.runs.map { it.at })
        assertEquals(1, state.unread)

        store.markRead(state.runs.first())
        assertEquals(0, store.state.value.unread)
    }

    @Test
    fun aPushedRunTheGatewayHasntListedYetIsKept() = runTest {
        val store = InboxStore(InMemoryKeyValueStore())
        store.bind("https://gw", now = 0)
        store.add(run(job = "pushed", at = 3_000))
        store.replace(listOf(run(at = 2_000)))
        assertEquals(listOf("pushed", "006998ffc458"), store.state.value.runs.map { it.job })
        // An older pushed run the gateway no longer lists is dropped with the rest.
        store.replace(listOf(run(at = 4_000)))
        assertEquals(listOf(4_000L), store.state.value.runs.map { it.at })
    }

    @Test
    fun readingARunInFullReplacesItsShortCopy() = runTest {
        val store = InboxStore(InMemoryKeyValueStore())
        store.bind("https://gw", now = 0)
        store.add(run(text = "Short"))
        store.add(run(text = "Short and the rest"))
        assertEquals(listOf("Short and the rest"), store.state.value.runs.map { it.text })
    }

    @Test
    fun theInboxIsKeptPerGatewayAndAcrossRestarts() = runTest {
        val disk = InMemoryKeyValueStore()
        val first = InboxStore(disk)
        first.bind("https://one", now = 0)
        first.add(run(at = 10, text = "x".repeat(5_000)))
        first.markAllRead()

        val again = InboxStore(disk)
        again.bind("https://one", now = 99)
        assertEquals(1, again.state.value.runs.size)
        assertEquals(0, again.state.value.unread)
        // Only the start of a long run is kept on the phone.
        assertEquals(InboxStore.MAX_KEPT_TEXT, again.state.value.runs.single().text.length)

        val other = InboxStore(disk)
        other.bind("https://two", now = 0)
        assertTrue(other.state.value.runs.isEmpty())
    }

    @Test
    fun linksAreConsumedOnceUnlessANewerOneCame() {
        val links = InboxLinks()
        links.open("a")
        val first = links.pending.value!!
        links.open("b")
        links.consume(first)
        assertEquals("b", links.pending.value?.key)
        links.consume(links.pending.value!!)
        assertNull(links.pending.value)
    }
}

package dev.hermeskotlin.ui.inbox

import dev.hermeskotlin.core.inbox.InboxRun
import kotlin.test.Test
import kotlin.test.assertEquals

class InboxFilterTest {

    private fun run(job: String, at: Long, label: String = job, status: String = InboxRun.OK, bot: String = "default") =
        InboxRun(bot = bot, job = job, run = "2026-10-12_00-00-${(at % 60).toString().padStart(2, '0')}", label = label, status = status, at = at)

    // Newest first, as the Inbox keeps them.
    private val runs = listOf(
        run("pulse", 500, "rubix-ticket-pulse"),
        run("brief", 400, "morning-briefing"),
        run("pulse", 300, "ticket pulse (old name)", status = InboxRun.FAILED),
        run("brief", 200, "morning-briefing"),
        run("brief", 100, "morning-briefing", bot = "siaulietis"),
    )

    @Test
    fun aChipPerJobTheOneThatReportedLastFirstNamedAsItIsNow() {
        val chips = jobChips(runs) { it.at > 250 }
        assertEquals(listOf("rubix-ticket-pulse", "morning-briefing", "morning-briefing"), chips.map { it.label })
        assertEquals(listOf(2, 2, 1), chips.map { it.runs })
        assertEquals(listOf(2, 1, 0), chips.map { it.unread })
        // The same job id in another profile is another job.
        assertEquals(listOf("default", "default", "siaulietis"), chips.map { it.filter.bot })
    }

    @Test
    fun filtersKeepTheirRunsInOrder() {
        assertEquals(runs, runs.filtered(InboxFilter.All))
        assertEquals(listOf(300L), runs.filtered(InboxFilter.Failed).map { it.at })
        assertEquals(listOf(400L, 200L), runs.filtered(InboxFilter.Job("default", "brief")).map { it.at })
    }

    @Test
    fun aJobThatLeftTheInboxShowsEverythingAgain() {
        val state = InboxUiState(runs = runs, filter = InboxFilter.Job("default", "gone"))
        assertEquals(InboxFilter.All, state.shownFilter)
        assertEquals(runs.size, state.shown.size)
        assertEquals(1, state.failed)
    }
}

package dev.hermeskotlin.ui.inbox

import dev.hermeskotlin.core.inbox.ChipMode
import dev.hermeskotlin.core.inbox.InboxGroups
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

    @Test
    fun groupChipsFollowTheUsersOrderThenOther() {
        val groups = InboxGroups().add("Personal").add("RUBIX").add("Empty")
            .assign("default", "pulse", "RUBIX").assign("default", "brief", "Personal")
        val chips = groupChips(runs, groups) { it.at > 250 }
        // "Empty" has no runs; the siaulietis briefing is in no group.
        assertEquals(listOf("Personal", "RUBIX", "Other"), chips.map { it.label })
        assertEquals(listOf(2, 2, 1), chips.map { it.runs })
        assertEquals(listOf(1, 2, 0), chips.map { it.unread })
        assertEquals(listOf(400L, 200L), runs.filtered(InboxFilter.Group("Personal"), groups).map { it.at })
        assertEquals(listOf(100L), runs.filtered(InboxFilter.Group(null), groups).map { it.at })
    }

    @Test
    fun withoutGroupsThereIsNoOtherChip() {
        assertEquals(emptyList(), groupChips(runs, InboxGroups()) { false })
    }

    @Test
    fun aFilterFromTheOtherSplitOrARemovedGroupShowsEverything() {
        val groups = InboxGroups().add("RUBIX").assign("default", "pulse", "RUBIX")
        val byJobs = InboxUiState(runs = runs, groups = groups, filter = InboxFilter.Group("RUBIX"))
        assertEquals(InboxFilter.All, byJobs.shownFilter)
        val byGroups = byJobs.copy(groups = groups.copy(mode = ChipMode.Groups))
        assertEquals(InboxFilter.Group("RUBIX"), byGroups.shownFilter)
        assertEquals(listOf(500L, 300L), byGroups.shown.map { it.at })
        assertEquals(InboxFilter.All, byGroups.copy(groups = byGroups.groups.remove("RUBIX")).shownFilter)
        assertEquals(InboxFilter.All, byGroups.copy(filter = InboxFilter.Job("default", "brief")).shownFilter)
    }
}

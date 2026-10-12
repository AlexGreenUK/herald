package dev.hermeskotlin.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.FolderTree
import com.composables.icons.lucide.FolderX
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Trash2
import dev.hermeskotlin.core.inbox.ChipMode
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.SheetAction
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.BellRing
import com.composables.icons.lucide.CheckCheck
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings2
import com.composables.icons.lucide.TriangleAlert
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.cron.CronJob
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.inbox.Follow
import dev.hermeskotlin.core.inbox.InboxRun
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Chip
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textMuted
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ListSkeleton
import dev.hermeskotlin.ui.components.dayHeading
import dev.hermeskotlin.ui.components.messageTime
import dev.hermeskotlin.ui.components.uses24HourClock
import dev.hermeskotlin.ui.sessions.MessageBanner
import dev.hermeskotlin.ui.sessions.SubpageHeader
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/**
 * The sidebar's Inbox: what the cron jobs the user follows have reported, newest first, and each run read in
 * full. Picking the jobs, and which of them notify, is its own page.
 */
@Composable
internal fun InboxPage(
    gateway: SavedGateway,
    visible: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    /** A run to open, from a tapped notification ([InboxRun.key]); [onOpenedKey] once it has been. */
    openKey: String? = null,
    onOpenedKey: () -> Unit = {},
    viewModel: InboxViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(gateway) { viewModel.bind(gateway) }
    LaunchedEffect(openKey) {
        if (openKey != null) {
            viewModel.openKey(openKey)
            onOpenedKey()
        }
    }
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4_000)
            viewModel.dismissMessage()
        }
    }
    PlatformBackHandler(enabled = visible && state.open != null) { viewModel.closeRun() }
    PlatformBackHandler(enabled = visible && state.open == null && state.choosing) { viewModel.closeJobs() }
    PlatformBackHandler(enabled = visible && state.open == null && !state.choosing && state.grouping) { viewModel.closeGroups() }

    Column(Modifier.fillMaxSize()) {
        when {
            state.open != null -> RunView(state.open, state.openLoading, state.openPartial, onBack = viewModel::closeRun)
            state.choosing -> FollowView(state, viewModel)
            state.grouping -> GroupsView(state, viewModel)
            else -> InboxView(state, viewModel, onBack = onBack, onOpenSettings = onOpenSettings)
        }
        state.message?.let { MessageBanner(it, onDismiss = viewModel::dismissMessage, modifier = Modifier.padding(bottom = 96.dp)) }
    }
}

@Composable
private fun InboxView(state: InboxUiState, viewModel: InboxViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val unread = state.unread
    SubpageHeader(
        "Inbox",
        onBack = onBack,
        subtitle = when {
            state.runs.isEmpty() -> null
            state.shownFilter is InboxFilter.Job -> state.shown.size.let { "$it ${if (it == 1) "report" else "reports"} from ${state.shown.first().title}" }
            state.shownFilter is InboxFilter.Group -> state.shown.size.let { n ->
                "$n ${if (n == 1) "report" else "reports"} in ${(state.shownFilter as InboxFilter.Group).name ?: "no group"}"
            }
            unread > 0 -> "$unread unread · from the jobs you follow"
            else -> "From the jobs you follow"
        },
    ) {
        if (unread > 0) IconButton(Lucide.CheckCheck, contentDescription = "Mark all read", onClick = viewModel::markAllRead)
        IconButton(Lucide.FolderTree, contentDescription = "Groups", onClick = viewModel::openGroups)
        IconButton(Lucide.Settings2, contentDescription = "Choose jobs", onClick = viewModel::chooseJobs)
    }
    state.block?.let { BlockNotice(it, onOpenSettings) }
    FilterChips(state, onSelect = viewModel::setFilter)
    val shown = state.shown
    when {
        state.loading && shown.isEmpty() -> ListSkeleton()
        state.error != null && shown.isEmpty() -> EmptyState(Lucide.TriangleAlert, "Couldn't load the Inbox", state.error)
        shown.isEmpty() && state.shownFilter == InboxFilter.Failed -> EmptyState(Lucide.Inbox, "No failed runs", "Every run in the Inbox went fine.")
        shown.isEmpty() && state.block == null -> EmptyState(
            Lucide.Inbox,
            "Nothing here yet",
            "Pick the jobs whose reports you want here, like a morning briefing. Each one can notify you, or arrive quietly.",
        )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var lastDay: String? = null
            shown.forEach { run ->
                val day = dayHeading(run.at)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") { DayLabel(day) }
                }
                item(key = run.key) { RunCard(run, unread = state.isUnread(run), onClick = { viewModel.open(run) }) }
            }
        }
    }
}

/**
 * All, then a chip per job (its unread count beside it), then Failed when any run failed, in one row that
 * scrolls sideways. Shown once there is more than one job, or a failure, to tell apart.
 */
@Composable
private fun FilterChips(state: InboxUiState, onSelect: (InboxFilter) -> Unit) {
    // By group once there are groups to split by; by job otherwise, and always when the user picked jobs.
    val byGroup = state.groups.mode == ChipMode.Groups && state.groupChips.isNotEmpty()
    val chips: List<Triple<InboxFilter, String, Int>> = if (byGroup) {
        state.groupChips.map { Triple(it.filter, it.label, it.unread) }
    } else {
        state.chips.map { Triple(it.filter, it.label, it.unread) }
    }
    val failed = state.failed
    if (chips.size < 2 && failed == 0) return
    val selected = state.shownFilter
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip("All", selected = selected == InboxFilter.All, onClick = { onSelect(InboxFilter.All) }, count = state.unread.takeIf { it > 0 })
        chips.forEach { (filter, label, unread) ->
            Chip(
                label,
                selected = selected == filter,
                onClick = { onSelect(filter) },
                count = unread.takeIf { it > 0 },
                countColor = Theme[colors][accentText],
            )
        }
        if (failed > 0) {
            Chip(
                "Failed",
                selected = selected == InboxFilter.Failed,
                onClick = { onSelect(InboxFilter.Failed) },
                count = failed,
                countColor = Theme[colors][danger],
            )
        }
    }
}

@Composable
private fun BlockNotice(block: InboxBlock, onOpenSettings: () -> Unit) {
    val (text, action) = when (block) {
        InboxBlock.PushOff -> "The Inbox needs Notifications anywhere, which tells the gateway which phone this is." to "Open Settings"
        InboxBlock.PluginOld -> "The gateway's herald-push plugin needs ${InboxViewModel.NEEDS_PLUGIN} for the Inbox. Update it in Settings." to "Open Settings"
    }
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(onClick = onOpenSettings)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary])
        Text(action, style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.SemiBold), color = Theme[colors][accentText])
    }
}

@Composable
private fun DayLabel(day: String) {
    Text(
        day.uppercase(),
        style = Theme[typography][eyebrow],
        color = Theme[colors][textTertiary],
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** A run as a card: the job, when, the start of what it said, and a dot while it's unread. */
@Composable
private fun RunCard(run: InboxRun, unread: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    val (tile, tint) = if (run.failed) Theme[colors][dangerSoft] to Theme[colors][danger] else Theme[colors][accentSoft] to Theme[colors][accentText]
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(onClickLabel = "Read ${run.title}", onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(tile, RoundedCornerShape(Theme[radii][radiusSmall])), contentAlignment = Alignment.Center) {
            UnstyledIcon(if (run.failed) Lucide.TriangleAlert else Lucide.Inbox, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    run.title,
                    style = Theme[typography][body].copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium),
                    color = Theme[colors][text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unread) {
                    Box(Modifier.size(8.dp).background(Theme[colors][accentText], CircleShape).clearAndSetSemantics { contentDescription = "Unread" })
                }
            }
            Text(
                (if (run.failed) "Failed · " else "") + timeLabel(run.at),
                style = Theme[typography][code].copy(fontSize = 11.5.sp),
                color = if (run.failed) Theme[colors][danger] else Theme[colors][textMuted],
            )
            Text(
                run.text.toPreview(),
                style = Theme[typography][bodySmall].copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = Theme[colors][textSecondary],
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One run in full, as Markdown. */
@Composable
private fun RunView(run: InboxRun, loading: Boolean, partial: Boolean, onBack: () -> Unit) {
    SubpageHeader(run.title, onBack = onBack, subtitle = (if (run.failed) "Failed · " else "") + messageTime(run.at.toDouble(), uses24HourClock()))
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (partial) {
            Text(
                "Only the start of this report is on the phone. Connect to the gateway to read the rest.",
                style = Theme[typography][bodySmall],
                color = Theme[colors][textTertiary],
            )
        }
        if (loading && run.text.isEmpty()) ListSkeleton() else MarkdownText(run.text)
    }
}

/**
 * The chips' split (by job or by group), the user's groups (add, rename, remove), and each job's group. Groups
 * live on this phone only: they sort what it shows, nothing on the gateway.
 */
@Composable
private fun GroupsView(state: InboxUiState, viewModel: InboxViewModel) {
    val groups = state.groups
    var naming by remember { mutableStateOf<GroupNaming?>(null) }
    var picking by remember { mutableStateOf<InboxFilter.Job?>(null) }
    SubpageHeader("Chips and groups", onBack = viewModel::closeGroups, subtitle = "Kept on this phone") {
        IconButton(Lucide.FolderPlus, contentDescription = "New group", onClick = { naming = GroupNaming(null) })
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "mode") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayLabel("Chips show")
                SegmentedControl(
                    options = ChipMode.entries,
                    selected = groups.mode,
                    onSelect = viewModel::setChipMode,
                    optionLabel = { if (it == ChipMode.Jobs) "Each job" else "My groups" },
                )
                if (groups.mode == ChipMode.Groups && groups.names.isEmpty()) {
                    Text(
                        "Make a group with the folder button above, then put jobs in it. Until then the chips show each job.",
                        style = Theme[typography][bodySmall],
                        color = Theme[colors][textTertiary],
                    )
                }
            }
        }
        if (groups.names.isNotEmpty()) {
            item(key = "groups-label") { DayLabel("Groups") }
            items(groups.names, key = { "g-$it" }) { name ->
                val count = state.groupableJobs.count { it.second == name }
                GroupRow(
                    name,
                    jobs = count,
                    onRename = { naming = GroupNaming(name) },
                    onRemove = { viewModel.removeGroup(name) },
                )
            }
        }
        item(key = "jobs-label") { DayLabel("Jobs") }
        if (state.groupableJobs.isEmpty()) {
            item(key = "no-jobs") {
                Text("Jobs show here once they have reports in the Inbox.", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            }
        }
        items(state.groupableJobs, key = { "j-${it.first.filter.bot}/${it.first.filter.job}" }) { (chip, group) ->
            JobGroupRow(chip.label, group, enabled = groups.names.isNotEmpty(), onClick = { picking = chip.filter })
        }
    }
    GroupNameDialog(
        naming = naming,
        taken = groups.names,
        onDismiss = { naming = null },
        onSave = { from, to ->
            if (from == null) viewModel.addGroup(to) else viewModel.renameGroup(from, to)
            naming = null
        },
    )
    val job = picking
    DropdownSheet(
        visible = job != null,
        title = state.chips.firstOrNull { it.filter == job }?.label ?: "",
        options = listOf<String?>(null) + groups.names,
        selected = job?.let { groups.of(it.bot, it.job) },
        label = { it ?: "No group" },
        onPick = { group -> job?.let { viewModel.assignGroup(it, group) }; picking = null },
        onDismiss = { picking = null },
    )
}

/** The name dialog: a new group when [from] is null, else a rename of [from]. */
private data class GroupNaming(val from: String?)

@Composable
private fun GroupNameDialog(naming: GroupNaming?, taken: List<String>, onDismiss: () -> Unit, onSave: (from: String?, to: String) -> Unit) {
    var shown by remember { mutableStateOf(naming) }
    if (naming != null) shown = naming
    val n = shown ?: return
    val name = rememberTextFieldState(n.from.orEmpty())
    LaunchedEffect(naming) { if (naming != null) name.edit { replace(0, length, naming.from.orEmpty()) } }
    val text = name.text.toString().trim()
    val clash = taken.any { it != n.from && it.equals(text, ignoreCase = true) }
    Dialog(
        visible = naming != null,
        onDismissRequest = onDismiss,
        title = if (n.from == null) "New group" else "Rename group",
        message = if (n.from == null) "Such as RUBIX or Personal. Put jobs in it from the list below." else null,
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button(
                if (n.from == null) "Create" else "Save",
                onClick = { onSave(n.from, text) },
                size = ButtonSize.Small,
                enabled = text.isNotEmpty() && !clash,
            )
        },
    ) {
        TextField(name, placeholder = "Group name", error = if (clash) "There's a group with that name." else null)
    }
}

@Composable
private fun GroupRow(name: String, jobs: Int, onRename: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.Folder, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (jobs == 1) "1 job" else "$jobs jobs",
                style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                color = Theme[colors][textTertiary],
            )
        }
        IconButton(Lucide.Pencil, contentDescription = "Rename $name", onClick = onRename)
        IconButton(Lucide.Trash2, contentDescription = "Remove $name", onClick = onRemove)
    }
}

@Composable
private fun JobGroupRow(label: String, group: String?, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(enabled = enabled, onClickLabel = "Choose $label's group", onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Theme[typography][body], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            group ?: if (enabled) "No group" else "",
            style = Theme[typography][bodySmall].copy(fontWeight = FontWeight.Medium),
            color = if (group != null) Theme[colors][accentText] else Theme[colors][textTertiary],
            maxLines = 1,
        )
        if (enabled) UnstyledIcon(Lucide.ChevronDown, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}

/** A short list of choices in a bottom sheet, with a tick on the [selected] one. */
@Composable
private fun <T> DropdownSheet(
    visible: Boolean,
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(visible = visible, onDismiss = onDismiss) {
        SheetHeader(title, "Group")
        options.forEach { option ->
            SheetAction(
                label(option),
                if (option == selected) Lucide.Check else if (option == null) Lucide.FolderX else Lucide.Folder,
                onClick = { onPick(option) },
            )
        }
    }
}

/** Every job, each set to not follow, follow quietly, or notify; and one switch for failures. */
@Composable
private fun FollowView(state: InboxUiState, viewModel: InboxViewModel) {
    SubpageHeader("Jobs in the Inbox", onBack = viewModel::closeJobs, subtitle = "What each job's reports do on this phone")
    if (state.jobsLoading && state.jobs.isEmpty()) {
        ListSkeleton()
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "failures") {
            FailuresRow(state.following.failures, enabled = !state.saving, onChange = viewModel::setFailures)
        }
        item(key = "label") { DayLabel("Jobs") }
        items(state.jobs, key = { it.id }) { job ->
            FollowRow(job, state.following.of(job.id), enabled = !state.saving) { viewModel.setFollow(job, it) }
        }
    }
}

@Composable
private fun FailuresRow(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .clickable(enabled = enabled) { onChange(!on) }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnstyledIcon(Lucide.BellRing, contentDescription = null, tint = Theme[colors][danger], modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Tell me when any job fails", style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text])
            Text(
                "A notification for every failed run, including jobs you don't follow.",
                style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp),
                color = Theme[colors][textTertiary],
            )
        }
        Switch(on)
    }
}

@Composable
private fun FollowRow(job: CronJob, follow: Follow, enabled: Boolean, onChange: (Follow) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(
        Modifier
            .fillMaxWidth()
            .background(Theme[colors][surface], shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(job.displayName, style = Theme[typography][body].copy(fontWeight = FontWeight.Medium), color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
            job.scheduleDisplay.takeIf { it.isNotBlank() && it != "?" }?.let {
                Text(it, style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp), color = Theme[colors][textTertiary], maxLines = 1)
            }
        }
        Box(Modifier.heightIn(min = 40.dp)) {
            SegmentedControl(
                options = Follow.entries,
                selected = follow,
                onSelect = { if (enabled) onChange(it) },
                optionLabel = {
                    when (it) {
                        Follow.Off -> "Off"
                        Follow.Quiet -> "Inbox"
                        Follow.Notify -> "Notify"
                    }
                },
            )
        }
    }
}

/** A run's start for a card: Markdown markup dropped, lines joined. */
private fun String.toPreview(): String = this
    .replace(Regex("```[^\\n]*\\n?"), "")
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("(?m)^\\s*([-*]|━+|-{3,})\\s*"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

@Composable
private fun timeLabel(at: Long): String = messageTime(at.toDouble(), uses24HourClock())

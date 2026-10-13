package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.AudioLines
import com.composables.icons.lucide.Box as BoxIcon
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.FileCode
import com.composables.icons.lucide.FolderCode
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.PenLine
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Wrench
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.config.ConfigCatalog
import dev.hermeskotlin.core.config.ConfigControl
import dev.hermeskotlin.core.config.ConfigEntry
import dev.hermeskotlin.core.config.ConfigSection
import dev.hermeskotlin.core.config.NOT_SET
import dev.hermeskotlin.core.config.editText
import dev.hermeskotlin.core.config.parseConfigInput
import dev.hermeskotlin.core.config.summary
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.body
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.BottomSheet
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.Dialog
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.SheetHeader
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.Switch
import dev.hermeskotlin.designsystem.components.TextField
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.eyebrow
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusLarge
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.ui.PlatformBackHandler
import dev.hermeskotlin.ui.components.EmptyState
import dev.hermeskotlin.ui.components.ListSkeleton
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.koin.compose.viewmodel.koinViewModel

/**
 * The sidebar's Config page, after Desktop's Settings: Hermes's own settings for the profile, by section,
 * searchable across every field the gateway knows, and the whole config.yaml for anything else. Each change
 * is saved at once.
 */
@Composable
internal fun ConfigPage(
    gateway: SavedGateway,
    profile: String?,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
    title: String = "Config",
    /** A line under the title, e.g. whose settings these are. */
    note: String? = null,
    /** False while the sidebar holding it is closed, so Back is the chat's. */
    visible: Boolean = true,
    viewModel: ConfigViewModel = koinViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(gateway, profile) { viewModel.bind(gateway, profile) }
    LaunchedEffect(state.sessionExpired) {
        if (state.sessionExpired) {
            viewModel.consumeSessionExpired()
            onSessionExpired()
        }
    }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(4_000)
            viewModel.dismissMessage()
        }
    }
    // Back steps out of a section or the file before it leaves the page.
    PlatformBackHandler(enabled = visible && state.view != ConfigPlace.Overview) { viewModel.back() }
    ConfigView(state, viewModel.query, viewModel.yaml, viewModel, onBack = onBack, title = title, note = note)
}

/** What the Config page can ask for; [ConfigViewModel] does it all, previews nothing. */
interface ConfigActions {
    fun refresh()
    fun open(view: ConfigPlace)
    fun back(): Boolean
    fun set(entry: ConfigEntry, value: JsonElement)
    fun confirmChange()
    fun cancelChange()
    fun saveYaml()
    fun discardYaml()
    fun keepEditing()
    fun dismissMessage()
}

/** The Config page's layout, apart from its view model, so previews can draw it from sample data. */
@Composable
internal fun ConfigView(
    state: ConfigUiState,
    query: TextFieldState,
    yaml: TextFieldState,
    actions: ConfigActions,
    onBack: () -> Unit,
    title: String = "Config",
    note: String? = null,
) {
    // The setting whose picker or editor is open.
    var picking by remember { mutableStateOf<ConfigEntry?>(null) }
    var editing by remember { mutableStateOf<ConfigEntry?>(null) }
    val onEntry: (ConfigEntry) -> Unit = { entry ->
        when (entry.control) {
            ConfigControl.Toggle -> actions.set(entry, JsonPrimitive(!entry.isOn))
            is ConfigControl.Choice -> picking = entry
            else -> editing = entry
        }
    }

    Column(Modifier.fillMaxSize()) {
        val schema = state.schema
        val values = state.values
        when (val view = state.view) {
            ConfigPlace.Yaml -> YamlEditor(state.yaml, yaml, onBack = { actions.back() }, onSave = actions::saveYaml)
            else -> {
                when (view) {
                    is ConfigPlace.Section -> SubpageHeader(view.section.label, onBack = { actions.back() }, subtitle = sectionNote(view.section))
                    ConfigPlace.AllSettings -> SubpageHeader("All settings", onBack = { actions.back() }, subtitle = "Every setting the gateway knows, by its own grouping.")
                    else -> SubpageHeader(title, onBack = onBack, subtitle = note ?: "Hermes's own settings, saved to its config.yaml. Most apply from the next chat.") {
                        IconButton(Lucide.FileCode, contentDescription = "Edit config.yaml", onClick = { actions.open(ConfigPlace.Yaml) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        (schema == null || values == null) && state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load", state.error, error = true) {
                            Button("Try again", onClick = actions::refresh, variant = ButtonVariant.Secondary, leadingIcon = Lucide.RefreshCw)
                        }
                        schema == null || values == null -> ListSkeleton()
                        view is ConfigPlace.Section -> {
                            val entries = remember(view.section, schema, values) { ConfigCatalog.entries(view.section, schema, values) }
                            PageList {
                                when {
                                    entries.isEmpty() -> item(key = "none") { ListNotice("This gateway has none of these settings.") }
                                    view.section.category == null -> item(key = "fields") { EntryCard(entries, state.saving, showKeys = false, onEntry = onEntry) }
                                    // A category can hold a hundred fields: one card per parent, each its own list item.
                                    else -> ConfigCatalog.grouped(entries).forEachIndexed { i, (parent, group) ->
                                        if (parent.isNotEmpty()) item(key = "h-$parent") { Eyebrow(ConfigCatalog.groupLabel(parent), top = if (i == 0) 2.dp else 14.dp) }
                                        item(key = "g-$parent") { EntryCard(group, state.saving, showKeys = true, onEntry = onEntry) }
                                    }
                                }
                            }
                        }
                        view == ConfigPlace.AllSettings -> {
                            val categories = remember(schema) { ConfigCatalog.categories(schema).map { it to schema.fields.values.count { f -> f.category == it.category } } }
                            PageList {
                                item(key = "categories") {
                                    NavCard(categories) { (section, count) ->
                                        NavRow(icon = null, title = section.label, detail = "$count ${if (count == 1) "setting" else "settings"}") { actions.open(ConfigPlace.Section(section)) }
                                    }
                                }
                            }
                        }
                        else -> Overview(state, query, actions, onEntry)
                    }
                }
            }
        }
        state.message?.let { MessageBanner(it, onDismiss = actions::dismissMessage, modifier = Modifier.padding(bottom = 96.dp)) }
    }

    ChoiceSheet(picking, onDismiss = { picking = null }, onPick = { entry, value ->
        picking = null
        actions.set(entry, (entry.control as? ConfigControl.Choice)?.valueOf(value) ?: JsonPrimitive(value))
    }, onOther = { entry ->
        picking = null
        editing = entry
    })
    EditDialog(editing, onDismiss = { editing = null }, onSave = { entry, value ->
        editing = null
        actions.set(entry, value)
    })
    Dialog(
        visible = state.confirmDiscard,
        onDismissRequest = actions::keepEditing,
        title = "Discard your changes?",
        message = "config.yaml stays as it was saved.",
        actions = {
            Button("Keep editing", onClick = actions::keepEditing, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Discard", onClick = actions::discardYaml, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
    Dialog(
        visible = state.confirm != null,
        onDismissRequest = actions::cancelChange,
        title = "Turn off every toolset?",
        message = "The agent would lose memory, the terminal, web search, subagents and most of its tools until some are turned back on.",
        actions = {
            Button("Cancel", onClick = actions::cancelChange, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Turn off", onClick = actions::confirmChange, variant = ButtonVariant.Danger, size = ButtonSize.Small)
        },
    )
}

/** The sections, a search over everything, then All settings and the file. */
@Composable
private fun Overview(state: ConfigUiState, query: TextFieldState, actions: ConfigActions, onEntry: (ConfigEntry) -> Unit) {
    val schema = state.schema ?: return
    val values = state.values ?: return
    val typed = query.text.toString()
    val results = remember(typed, schema, values) { ConfigCatalog.search(typed, schema, values) }
    val sections = remember(schema, values) { ConfigCatalog.sections.map { it to ConfigCatalog.entries(it, schema, values).size }.filter { it.second > 0 } }
    PageList {
        item(key = "search") {
            TextField(
                state = query,
                placeholder = "Search ${schema.fields.size} settings",
                leadingIcon = Lucide.Search,
                clearable = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (typed.isNotBlank()) {
            if (results.isEmpty()) {
                item(key = "no-results") { ListNotice("No settings match “${typed.trim()}”.") }
            } else {
                val shown = results.take(SEARCH_LIMIT)
                item(key = "results-count") {
                    Eyebrow(
                        if (results.size > shown.size) "First ${shown.size} of ${results.size} settings"
                        else "${results.size} ${if (results.size == 1) "setting" else "settings"}",
                    )
                }
                item(key = "results") { EntryCard(shown, state.saving, showKeys = true, onEntry = onEntry) }
                if (results.size > shown.size) item(key = "narrow") { ListNotice("Type more to narrow it down.") }
            }
        } else {
            item(key = "sections") {
                NavCard(sections) { (section, count) ->
                    NavRow(sectionIcon(section.id), section.label, "$count ${if (count == 1) "setting" else "settings"}") { actions.open(ConfigPlace.Section(section)) }
                }
            }
            item(key = "more") {
                NavCard(listOf("all", "yaml"), modifier = Modifier.padding(top = 14.dp)) { which ->
                    if (which == "all") {
                        NavRow(Lucide.ListTree, "All settings", "Every field, by the gateway's own grouping") { actions.open(ConfigPlace.AllSettings) }
                    } else {
                        NavRow(Lucide.FileCode, "Edit config.yaml", "The whole file, for anything not listed") { actions.open(ConfigPlace.Yaml) }
                    }
                }
            }
        }
    }
}

/** Results shown at once; a word like "model" matches a few hundred. */
private const val SEARCH_LIMIT = 60

private fun sectionNote(section: ConfigSection): String? = when (section.id) {
    "voice" -> "Only the picked providers' own settings show."
    "model" -> "The default for new chats; a chat's model picker can still change its own."
    else -> null
}

private fun sectionIcon(id: String): ImageVector = when (id) {
    "model" -> Lucide.BoxIcon
    "chat" -> Lucide.MessageCircle
    "workspace" -> Lucide.FolderCode
    "safety" -> Lucide.ShieldCheck
    "browser" -> Lucide.Globe
    "memory" -> Lucide.Brain
    "voice" -> Lucide.AudioLines
    else -> Lucide.Wrench
}

private val ConfigEntry.isOn: Boolean get() = (value as? JsonPrimitive)?.booleanOrNull == true

@Composable
private fun PageList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    // 4dp on top leaves room for a focused search field's ring, which the list would clip.
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 96.dp), content = content)
}

@Composable
private fun Eyebrow(text: String, top: Dp = 2.dp) {
    Text(
        text.uppercase(),
        style = Theme[typography][eyebrow],
        color = Theme[colors][textTertiary],
        modifier = Modifier.padding(start = 6.dp, top = top, bottom = 8.dp).semantics {
            heading()
            contentDescription = text
        },
    )
}

/** Rows on one ringed surface card, a hairline between each. */
@Composable
private fun <T> NavCard(rows: List<T>, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(modifier.fillMaxWidth().clip(shape).background(Theme[colors][surface]).border(1.dp, Theme[colors][stroke], shape)) {
        rows.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
            row(item)
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector?, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(32.dp).background(Theme[colors][surface2], RoundedCornerShape(Theme[radii][radiusSmall])), contentAlignment = Alignment.Center) {
                UnstyledIcon(icon, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = Theme[typography][body], color = Theme[colors][text])
            Text(detail, style = Theme[typography][bodySmall], color = Theme[colors][textTertiary], maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        UnstyledIcon(Lucide.ChevronRight, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun EntryCard(entries: List<ConfigEntry>, saving: Set<String>, showKeys: Boolean, onEntry: (ConfigEntry) -> Unit) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    Column(Modifier.fillMaxWidth().clip(shape).background(Theme[colors][surface]).border(1.dp, Theme[colors][stroke], shape)) {
        entries.forEachIndexed { i, entry ->
            key(entry.key) {
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Theme[colors][stroke]))
                EntryRow(entry, saving = entry.key in saving, showKey = showKeys, onClick = { onEntry(entry) })
            }
        }
    }
}

/**
 * One setting: its name, what it's for, and its value under them, or a switch at the end for one that's
 * on or off. [showKey] adds the dotted key, for settings found outside their section.
 */
@Composable
private fun EntryRow(entry: ConfigEntry, saving: Boolean, showKey: Boolean, onClick: () -> Unit) {
    val toggle = entry.control == ConfigControl.Toggle
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (toggle) Modifier.toggleable(value = entry.isOn, role = Role.Switch, onValueChange = { onClick() })
                else Modifier.clickable(role = Role.Button, onClickLabel = "Change", onClick = onClick),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.label, style = Theme[typography][body], color = Theme[colors][text])
            if (showKey) {
                Text(entry.key, style = Theme[typography][code].copy(fontSize = 11.5.sp), color = Theme[colors][textTertiary], maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            entry.description?.let {
                Text(it, style = Theme[typography][bodySmall].copy(fontSize = 12.5.sp, lineHeight = 17.5.sp), color = Theme[colors][textTertiary], maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (!toggle) {
                val summary = entry.summary()
                Text(
                    summary,
                    style = Theme[typography][code].copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    color = if (summary == NOT_SET) Theme[colors][textTertiary] else Theme[colors][accentText],
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        when {
            saving -> Spinner(Modifier.size(16.dp))
            toggle -> Switch(entry.isOn)
            else -> UnstyledIcon(Lucide.PenLine, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(15.dp))
        }
    }
}

/** A choice's options in a sheet, the current one ticked; a long list gets a search, a free one an "Other…" row. */
@Composable
internal fun ChoiceSheet(entry: ConfigEntry?, onDismiss: () -> Unit, onPick: (ConfigEntry, String) -> Unit, onOther: (ConfigEntry) -> Unit) {
    // Kept while the sheet slides away, so it doesn't empty mid-animation.
    var shown by remember { mutableStateOf(entry) }
    if (entry != null) shown = entry
    val e = shown
    val control = e?.control as? ConfigControl.Choice
    val search = rememberTextFieldState()
    LaunchedEffect(entry) { if (entry != null) search.edit { replace(0, length, "") } }
    BottomSheet(visible = entry != null, onDismiss = onDismiss) {
        if (e == null || control == null) return@BottomSheet
        // Null is "not set", like "".
        val current = (e.value as? JsonPrimitive)?.contentOrNull.orEmpty()
        SheetHeader(e.label, e.description)
        if (control.searchable) {
            TextField(
                state = search,
                placeholder = "Search ${control.options.size}",
                leadingIcon = Lucide.Search,
                clearable = true,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        val typed = search.text.toString().trim()
        val options = remember(control, typed) { control.options.filter { typed.isEmpty() || it.contains(typed, ignoreCase = true) } }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(options, key = { "o-$it" }) { option ->
                OptionRow(if (option.isEmpty()) NOT_SET else option, selected = option == current, mono = option.isNotEmpty()) { onPick(e, option) }
            }
            if (control.other) {
                item(key = "other") { OptionRow("Other…", selected = false, mono = false) { onOther(e) } }
            }
        }
    }
}

@Composable
private fun OptionRow(option: String, selected: Boolean, mono: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            option,
            style = if (mono) Theme[typography][code].copy(fontSize = 13.5.sp) else Theme[typography][body],
            color = if (selected) Theme[colors][accentText] else Theme[colors][text],
            modifier = Modifier.weight(1f),
        )
        if (selected) UnstyledIcon(Lucide.Check, contentDescription = null, tint = Theme[colors][accentText], modifier = Modifier.size(16.dp))
    }
}

/** A text field for a setting typed in: one line for words and numbers, several for lists and JSON. */
@Composable
private fun EditDialog(entry: ConfigEntry?, onDismiss: () -> Unit, onSave: (ConfigEntry, JsonElement) -> Unit) {
    var shown by remember { mutableStateOf(entry) }
    if (entry != null) shown = entry
    val e = shown ?: return
    val input = rememberTextFieldState()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry) {
        if (entry != null) {
            input.edit { replace(0, length, entry.editText()) }
            error = null
        }
    }
    val multiline = e.control == ConfigControl.Lines || e.control == ConfigControl.Fallbacks || e.control == ConfigControl.Json ||
        (e.control == ConfigControl.Text && e.editText().length > 60)
    val submit = {
        parseConfigInput(e, input.text.toString()).fold(
            onSuccess = { onSave(e, it) },
            onFailure = { error = it.message },
        )
    }
    Dialog(
        visible = entry != null,
        onDismissRequest = onDismiss,
        title = e.label,
        message = when (e.control) {
            ConfigControl.Lines -> listOfNotNull(e.description, "One per line.").joinToString(" ")
            else -> e.description
        },
        actions = {
            Button("Cancel", onClick = onDismiss, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Save", onClick = { submit() }, size = ButtonSize.Small)
        },
    ) {
        TextField(
            state = input,
            placeholder = NOT_SET,
            singleLine = !multiline,
            maxLines = 10,
            error = error,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (e.control == ConfigControl.Number) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            onKeyboardAction = if (multiline) null else { _ -> submit() },
        )
    }
}

/**
 * The whole config.yaml in a mono field. Save replaces the file after a confirm; the gateway refuses YAML
 * that isn't a mapping, and its reason shows under the field.
 */
@Composable
private fun ColumnScope.YamlEditor(state: YamlState?, file: TextFieldState, onBack: () -> Unit, onSave: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val changed = state?.loaded != null && file.text.toString() != state.loaded
    SubpageHeader("config.yaml", onBack = onBack, subtitle = state?.path?.ifEmpty { null } ?: "The profile's whole config file.") {
        Button(
            "Save",
            onClick = { confirming = true },
            size = ButtonSize.Small,
            enabled = changed && state?.saving != true,
            loading = state?.saving == true,
        )
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            state == null || state.loading -> ListSkeleton()
            state.loaded == null && state.error != null -> EmptyState(Lucide.CloudOff, "Couldn't load", state.error, error = true)
            else -> Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 96.dp)) {
                state.error?.let { Text(it, style = Theme[typography][bodySmall], color = Theme[colors][danger], modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
                YamlField(file)
            }
        }
    }
    Dialog(
        visible = confirming,
        onDismissRequest = { confirming = false },
        title = "Replace config.yaml?",
        message = "The file becomes exactly this text. Hermes reads it again for new chats; some settings need a gateway restart.",
        actions = {
            Button("Cancel", onClick = { confirming = false }, variant = ButtonVariant.Ghost, size = ButtonSize.Small)
            Button("Replace", onClick = { confirming = false; onSave() }, size = ButtonSize.Small)
        },
    )
}

@Composable
private fun YamlField(file: TextFieldState) {
    val shape = RoundedCornerShape(Theme[radii][radiusLarge])
    androidx.compose.foundation.text.BasicTextField(
        state = file,
        textStyle = Theme[typography][code].copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Theme[colors][text]),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Theme[colors][accentText]),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .clip(shape)
            .background(Theme[colors][surface])
            .border(1.dp, Theme[colors][stroke], shape)
            .padding(12.dp)
            .semantics { contentDescription = "config.yaml" },
    )
}

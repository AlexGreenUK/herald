package dev.hermeskotlin.core.inbox

import dev.hermeskotlin.core.cron.Routines
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.rpc.RpcException
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One finished run of a cron job in the Inbox, as the herald-push plugin reads it from the gateway's cron
 * output files (`cron inbox`, `cron read`, or a `cron_run` push). [text] is the start of its output in a list,
 * the whole of it once read.
 */
@Serializable
data class InboxRun(
    /** The profile whose cron store holds the job. */
    val bot: String = "default",
    val job: String,
    /** The output file's name, `YYYY-MM-DD_HH-MM-SS`: with [bot] and [job], what names the run. */
    val run: String,
    /** The job's name. */
    val label: String = "",
    /** `ok` or `failed`. */
    val status: String = OK,
    /** When the run finished, unix seconds. */
    val at: Long = 0,
    val text: String = "",
) {
    val key: String get() = "$bot/$job/$run"
    val failed: Boolean get() = status == FAILED
    /** The job's name as the gateway has it, without a Bot Mode routine's `[bot:…]` tag. */
    val title: String get() = Routines.title(label).trim().ifBlank { job }

    companion object {
        const val OK = "ok"
        const val FAILED = "failed"

        /** The run a `cron_run` push names, or null when the push is missing what names it. */
        fun from(message: PushMessage): InboxRun? {
            val job = message.job?.takeIf { JOB_ID.matches(it) } ?: return null
            val run = message.run?.takeIf { RUN_ID.matches(it) } ?: return null
            val bot = message.bot?.takeIf { PROFILE.matches(it) } ?: "default"
            return InboxRun(
                bot = bot,
                job = job,
                run = run,
                label = message.label?.filter { !it.isISOControl() }?.trim()?.take(MAX_LABEL).orEmpty(),
                status = if (message.status == FAILED) FAILED else OK,
                at = message.at ?: message.ts,
                text = message.text.orEmpty(),
            )
        }

        private const val MAX_LABEL = 64
        private val JOB_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val PROFILE = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val RUN_ID = Regex("^\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}$")
    }
}

/** How one job reaches the Inbox: not at all, into it quietly, or with a notification. */
enum class Follow(val wire: String) {
    Off("off"),
    Quiet("quiet"),
    Notify("notify");

    companion object {
        fun of(wire: String?): Follow = entries.firstOrNull { it.wire == wire } ?: Off
    }
}

/**
 * What this phone follows on the gateway: [default] for every job (new ones too), [jobs] where a job differs
 * from it, and whether any failed run notifies.
 */
data class Following(val jobs: Map<String, Follow> = emptyMap(), val failures: Boolean = false, val default: Follow = Follow.Off) {
    fun of(jobId: String): Follow = jobs[jobId] ?: default
    val any: Boolean get() = failures || default != Follow.Off || jobs.values.any { it != Follow.Off }

    fun with(jobId: String, follow: Follow): Following =
        copy(jobs = if (follow == default) jobs - jobId else jobs + (jobId to follow))

    /** Every job set to [follow], new ones too: what was set per job is dropped. */
    fun all(follow: Follow): Following = copy(jobs = emptyMap(), default = follow)
}

@Serializable
private data class FollowReply(
    val ok: Boolean = false,
    val error: String? = null,
    val jobs: Map<String, String> = emptyMap(),
    val failures: Boolean = false,
    val default: String = "off",
)

@Serializable
private data class InboxReply(val ok: Boolean = false, val error: String? = null, val runs: List<InboxRun> = emptyList())

@Serializable
private data class ReadReply(val ok: Boolean = false, val error: String? = null, val run: InboxRun? = null)

/**
 * The Inbox's side of the herald-push plugin (`/herald-push cron …`, from 0.4.0), over the dashboard socket. This
 * phone is named by its push registration's device id, so the Inbox needs Notifications anywhere on.
 */
class InboxApi(private val push: PushApi, private val keys: PushKeys) {

    suspend fun following(): Following = follow("following") {}

    suspend fun setFollowing(following: Following): Following = follow("follow") {
        putJsonObject("jobs") { following.jobs.forEach { (job, mode) -> put(job, mode.wire) } }
        put("failures", following.failures)
        // A plugin before 0.4.1 refuses "off" and a default: say them only when they matter.
        if (following.default != Follow.Off) put("default", following.default.wire)
    }

    /** The runs this phone follows, newest first; [before] (unix seconds) pages back. */
    suspend fun runs(limit: Int = 50, before: Long = 0): List<InboxRun> {
        val body = request {
            put("limit", limit)
            if (before > 0) put("before", before)
        }
        val reply = decode(InboxReply.serializer(), push.cron("inbox $body"))
        if (!reply.ok) throw RpcException(0, reply.error ?: "The gateway couldn't list the runs.")
        return reply.runs
    }

    /** One run in full, or null when the gateway no longer has it. */
    suspend fun read(run: InboxRun): InboxRun? {
        val body = request {
            put("bot", run.bot)
            put("job", run.job)
            put("run", run.run)
        }
        return decode(ReadReply.serializer(), push.cron("read $body")).run
    }

    private suspend fun follow(op: String, more: JsonObjectBuilder.() -> Unit): Following {
        val reply = decode(FollowReply.serializer(), push.cron("$op ${request(more)}"))
        if (!reply.ok) throw RpcException(0, reply.error ?: "The gateway refused.")
        val default = Follow.of(reply.default)
        return Following(reply.jobs.mapValues { Follow.of(it.value) }.filterValues { it != default }, reply.failures, default)
    }

    private fun request(more: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("device_id", keys.deviceId)
        more()
    }

    private fun <T> decode(serializer: KSerializer<T>, output: String?): T {
        output ?: throw RpcException(0, "The gateway's herald-push plugin isn't there.")
        // A plugin from before the Inbox answers `cron …` with its usage line.
        if (output.contains("\"usage:") && !output.contains("cron ")) throw RpcException(0, TOO_OLD)
        return runCatching { HermesJson.decodeFromString(serializer, output) }.getOrElse { throw RpcException(0, TOO_OLD) }
    }

    companion object {
        /** The oldest plugin with `/herald-push cron`. */
        const val MIN_PLUGIN_VERSION = "0.4.0"

        /** The oldest plugin that follows every job by default and switches single jobs off. */
        const val ALL_JOBS_PLUGIN_VERSION = "0.4.1"

        /** What a call says when the plugin predates the Inbox; the Inbox page shows its update notice for it. */
        const val TOO_OLD = "The gateway's herald-push plugin is too old for the Inbox."
    }
}

/**
 * The Inbox kept on the phone, newest first: what the gateway listed last, plus runs that came by push while it
 * couldn't be reached, and which have been read. Kept per gateway, so it shows before (or without) a connection.
 */
class InboxStore(private val store: KeyValueStore) {

    @Serializable
    private data class Saved(
        val runs: List<InboxRun> = emptyList(),
        val read: Set<String> = emptySet(),
        /** Runs read before this (unix seconds) count as read: the Inbox's first look isn't a pile of unread. */
        @SerialName("since") val since: Long = 0,
        val groups: InboxGroups = InboxGroups(),
    )

    data class Snapshot(
        val runs: List<InboxRun> = emptyList(),
        val read: Set<String> = emptySet(),
        val since: Long = 0,
        val groups: InboxGroups = InboxGroups(),
    ) {
        fun isUnread(run: InboxRun): Boolean = run.key !in read && run.at > since
        val unread: Int get() = runs.count(::isUnread)
    }

    private val lock = Mutex()
    private var gateway: String? = null
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Loads [gatewayUrl]'s Inbox, starting it now (everything before counts as read) the first time. */
    suspend fun bind(gatewayUrl: String, now: Long) = lock.withLock {
        if (gateway == gatewayUrl) return@withLock
        gateway = gatewayUrl
        val saved = load(gatewayUrl) ?: Saved(since = now).also { save(gatewayUrl, it) }
        _state.value = Snapshot(saved.runs, saved.read, saved.since, saved.groups)
    }

    /** What the gateway listed: replaces the kept runs, keeping pushed ones it didn't list (it may lag). */
    suspend fun replace(runs: List<InboxRun>) = edit { s ->
        val listed = runs.map { it.key }.toSet()
        val newest = runs.maxOfOrNull { it.at } ?: 0
        val pushed = s.runs.filter { it.key !in listed && it.at > newest }
        s.copy(runs = merge(pushed + runs))
    }

    /** A run that came by push, or was read in full: added, or put in place of its shorter copy. */
    suspend fun add(run: InboxRun) = edit { s -> s.copy(runs = merge(listOf(run) + s.runs.filter { it.key != run.key })) }

    suspend fun markRead(run: InboxRun) = edit { s -> s.copy(read = (s.read + run.key).trimmed(s.runs)) }

    suspend fun markAllRead() = edit { s -> s.copy(read = s.runs.map { it.key }.toSet()) }

    /** Changes the user's groups (kept on this phone, per gateway). */
    suspend fun editGroups(change: (InboxGroups) -> InboxGroups) = edit { s -> s.copy(groups = change(s.groups)) }

    private suspend fun edit(change: (Snapshot) -> Snapshot) = lock.withLock {
        val url = gateway ?: return@withLock
        val next = change(_state.value)
        _state.value = next
        save(url, Saved(next.runs, next.read, next.since, next.groups))
    }

    private fun merge(runs: List<InboxRun>): List<InboxRun> =
        runs.distinctBy { it.key }.sortedByDescending { it.at }.take(MAX_KEPT)

    // A run that left the list can't be unread any more.
    private fun Set<String>.trimmed(runs: List<InboxRun>): Set<String> = intersect(runs.map { it.key }.toSet())

    private suspend fun load(url: String): Saved? =
        store.get(key(url))?.let { runCatching { HermesJson.decodeFromString(Saved.serializer(), it) }.getOrNull() }

    private suspend fun save(url: String, saved: Saved) {
        // A kept run holds the start of its text only: the whole of a long briefing is read from the gateway.
        val trimmed = saved.copy(runs = saved.runs.map { it.copy(text = it.text.take(MAX_KEPT_TEXT)) })
        store.put(key(url), HermesJson.encodeToString(Saved.serializer(), trimmed))
    }

    private fun key(url: String) = "inbox.v1.$url"

    companion object {
        const val MAX_KEPT = 100
        const val MAX_KEPT_TEXT = 2_000
    }
}

/** How the Inbox's chips split the runs: a chip per job, or per group the user made. */
@Serializable
enum class ChipMode { Jobs, Groups }

/** How the Inbox opens: a folder per job (or per group), or every report in one list. */
@Serializable
enum class InboxLayout { Folders, List }

/**
 * The user's own groups of jobs for the Inbox, such as "RUBIX" and "Personal": [names] in the order made, and
 * [jobs] mapping a job ([jobKey]) to its group. A job is in one group at most; one in none shows under "Other".
 * Kept on the phone: they only sort what the phone shows.
 */
@Serializable
data class InboxGroups(
    val names: List<String> = emptyList(),
    val jobs: Map<String, String> = emptyMap(),
    val mode: ChipMode = ChipMode.Jobs,
    /** Names the user gave jobs on this phone ([jobKey] to name), shown instead of the job's own. */
    val renamed: Map<String, String> = emptyMap(),
    val layout: InboxLayout = InboxLayout.Folders,
) {
    fun of(bot: String, job: String): String? = jobs[jobKey(bot, job)]?.takeIf { it in names }

    /** What [run]'s job is called here: the name the user gave it, else its own. */
    fun nameOf(run: InboxRun): String = renamed[jobKey(run.bot, run.job)] ?: run.title

    /** The name the user gave a job, or null when it goes by its own. */
    fun renamedOf(bot: String, job: String): String? = renamed[jobKey(bot, job)]

    /**
     * Calls a job [name] on this phone (trimmed, at most [MAX_NAME]); blank, or the job's own name [own], puts its
     * own name back.
     */
    fun renameJob(bot: String, job: String, name: String, own: String): InboxGroups {
        val clean = name.trim().take(MAX_NAME)
        val key = jobKey(bot, job)
        return if (clean.isEmpty() || clean == own) copy(renamed = renamed - key) else copy(renamed = renamed + (key to clean))
    }

    /** Adds a group called [name] (trimmed, at most [MAX_NAME]); a blank or taken name changes nothing. */
    fun add(name: String): InboxGroups {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty() || names.any { it.equals(clean, ignoreCase = true) } || names.size >= MAX_GROUPS) return this
        return copy(names = names + clean)
    }

    /** Renames [from] to [to], keeping its jobs; a blank or taken name changes nothing. */
    fun rename(from: String, to: String): InboxGroups {
        val clean = to.trim().take(MAX_NAME)
        if (clean.isEmpty() || from !in names || names.any { it != from && it.equals(clean, ignoreCase = true) }) return this
        return copy(names = names.map { if (it == from) clean else it }, jobs = jobs.mapValues { if (it.value == from) clean else it.value })
    }

    /** Removes the group [name]; its jobs go back to none. */
    fun remove(name: String): InboxGroups = copy(names = names - name, jobs = jobs.filterValues { it != name })

    /** Puts a job in [group], or in none when it is null. */
    fun assign(bot: String, job: String, group: String?): InboxGroups =
        if (group == null || group !in names) copy(jobs = jobs - jobKey(bot, job)) else copy(jobs = jobs + (jobKey(bot, job) to group))

    companion object {
        const val MAX_NAME = 40
        const val MAX_GROUPS = 20
        fun jobKey(bot: String, job: String) = "$bot/$job"
    }
}

/**
 * A tapped Inbox notification, waiting for the app to show it: the sidebar opens on the Inbox, at [key]'s run
 * when it is one ([InboxRun.key]), at the list when it is null.
 */
data class InboxLink(val key: String?)

class InboxLinks {
    private val _pending = MutableStateFlow<InboxLink?>(null)
    val pending: StateFlow<InboxLink?> = _pending.asStateFlow()

    fun open(key: String?) {
        _pending.value = InboxLink(key)
    }

    /** Takes [link] once it's shown, unless a newer one came meanwhile. */
    fun consume(link: InboxLink) = _pending.update { if (it == link) null else it }
}

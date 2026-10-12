package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.auth.secureRandomBytes
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.phone.PhoneToolsStatus.State
import dev.hermeskotlin.core.push.PhoneRequest
import dev.hermeskotlin.core.push.PluginState
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Answers the agent's phone tool calls. While connected it tells the gateway's herald-push plugin which tools
 * are switched on (the plugin offers the agent only those), then fetches and runs the calls waiting for this
 * phone: when a chat it watches starts a `phone_*` tool, when a push says one waits, and every
 * [POLL_MS] for chats it doesn't watch.
 *
 * Nothing runs that is switched off now: a call for a tool turned off, even one already on screen, is refused.
 */
class PhoneToolsBridge(
    private val api: PushApi,
    private val keys: PushKeys,
    private val store: KeyValueStore,
    private val connection: GatewayConnection,
    private val gateways: GatewayRepository,
    private val settings: SettingsStore,
    private val runner: PhoneToolRunner,
    private val scope: CoroutineScope,
    private val deviceName: () -> String,
    private val now: () -> Long = ::getTimeMillis,
) {
    private val _status = MutableStateFlow(PhoneToolsStatus())
    val status: StateFlow<PhoneToolsStatus> = _status.asStateFlow()

    private val _recent = MutableStateFlow<List<PhoneToolUse>>(emptyList())

    /** The latest uses, newest first, for as long as the app runs. */
    val recent: StateFlow<List<PhoneToolUse>> = _recent.asStateFlow()

    /** Bumped by [setUp]: the next report may install the plugin. Spent only by a report that ran. */
    private val setUps = MutableStateFlow(0)
    private var setUpsDone = 0

    /** This phone's phone-tools identity: an id and the secret that binds it on the gateway, kept together. */
    @Serializable
    private data class Identity(val id: String, val key: String)

    private class Running(val tool: String, val job: Job)

    private val running = mutableMapOf<String, Running>()

    /** Calls already answered, so a fetch made before the answer landed doesn't run them twice. */
    private val answered = ArrayDeque<String>()
    private val runningLock = Mutex()
    private val draining = Mutex()
    private val identityLock = Mutex()

    fun start() {
        scope.launch {
            val client = connection.state.map { (it as? ConnectionState.Connected)?.client }.distinctUntilChanged()
            val enabled = settings.settings.filterNotNull().map { it.enabledPhoneTools() }.distinctUntilChanged()
            combine(client, enabled, setUps) { c, on, setUp -> Triple(c, on, setUp) }.collectLatest { (c, on, setUp) ->
                refuseSwitchedOff(on)
                serve(c, on, setUp)
            }
        }
    }

    /** Installs or turns on the plugin when the gateway lacks it, then reports the tools. */
    fun setUp() = setUps.update { it + 1 }

    /** A push said a call waits: fetch it now. */
    fun wake() {
        scope.launch {
            if (connection.state.value is ConnectionState.Connected && settings.settings.value?.enabledPhoneTools().orEmpty().isNotEmpty()) {
                drain()
            }
        }
    }

    private suspend fun serve(client: JsonRpcClient?, on: Set<PhoneTool>, setUp: Int) {
        if (client == null) {
            _status.value = PhoneToolsStatus(if (on.isEmpty()) State.Off else State.Offline)
            return
        }
        if (on.isEmpty()) {
            // Told once, so the gateway stops offering them; a phone that never had any isn't announced.
            if (store.get(reportedKey()) == "1") report(on, install = false)
            _status.value = PhoneToolsStatus(State.Off)
            return
        }
        var attempt = 0
        while (true) {
            val install = setUp > setUpsDone
            val reported = report(on, install)
            // Spent once a report with it ran to the end, not when a reconnect or a switch cut it short.
            if (install) setUpsDone = setUp
            if (reported) break
            // Not installed waits for Set up; anything else (a timeout, a refusal) is tried again.
            if (_status.value.state == State.NotInstalled) return
            delay(RETRY_MS[attempt.coerceAtMost(RETRY_MS.lastIndex)])
            attempt++
        }
        coroutineScope {
            launch {
                client.events
                    .filter { it.type == "tool.start" && toolName(it.payload)?.startsWith(TOOL_PREFIX) == true }
                    .collect {
                        // The event can come before the gateway has written the call down: look again shortly.
                        launch {
                            for (wait in EVENT_DRAINS_MS) {
                                delay(wait)
                                drain()
                            }
                        }
                    }
            }
            while (true) {
                drain()
                delay(POLL_MS)
            }
        }
    }

    /** Tells the gateway what's on. False when it can't take phone tools (no plugin, refused, unreachable). */
    private suspend fun report(on: Set<PhoneTool>, install: Boolean): Boolean {
        try {
            if (on.isNotEmpty()) _status.value = PhoneToolsStatus(State.Working)
            val plugin = api.prepare(install, PushApi.MIN_PHONE_PLUGIN_VERSION) {
                if (on.isNotEmpty()) _status.value = PhoneToolsStatus(State.Working, it)
            }
            when (plugin) {
                PluginState.Missing -> {
                    _status.value = PhoneToolsStatus(if (on.isEmpty()) State.Off else State.NotInstalled)
                    return false
                }
                is PluginState.Failed -> {
                    _status.value = PhoneToolsStatus(State.Failed, plugin.reason)
                    return false
                }
                is PluginState.Ready -> Unit
            }
            val me = identity()
            val offered = api.phoneTools(me.id, me.key, deviceName(), on.map { it.id }.sorted(), pushId()) ?: return false
            store.put(reportedKey(), if (on.isEmpty()) "0" else "1")
            val missing = on.filter { it.id !in offered }
            _status.value = PhoneToolsStatus(
                State.On,
                if (missing.isEmpty()) null else "The gateway's plugin doesn't know ${missing.joinToString { it.title }} yet.",
            )
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = PhoneToolsStatus(State.Failed, e.message)
            return false
        }
    }

    /** The push registration a call can nudge: this phone's, when it's registered with this gateway. */
    private suspend fun pushId(): String? {
        if (settings.settings.value?.pushAnywhere != true) return null
        val url = gateways.current()?.gatewayUrl?.value ?: return null
        return keys.deviceId.takeIf { keys.pinnedGatewayUrl() == url }
    }

    /** Fetches the calls waiting for this phone and starts each one not already running or answered. */
    private suspend fun drain() = draining.withLock {
        val me = identity()
        val waiting = try {
            api.phonePending(me.id, me.key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withLock
        runningLock.withLock {
            for (request in waiting) {
                if (request.id in running || request.id in answered) continue
                running[request.id] = Running(request.tool, scope.launch { handle(request) })
            }
        }
    }

    private suspend fun handle(request: PhoneRequest) {
        val tool = PhoneTool.byId(request.tool)
        val (result, error) = if (tool == null || tool !in settings.settings.value?.enabledPhoneTools().orEmpty()) {
            null to SWITCHED_OFF
        } else {
            try {
                withTimeout(RUN_LIMIT_MS) { runner.run(tool, request.args, request.session) } to null
            } catch (e: TimeoutCancellationException) {
                null to "The phone took too long."
            } catch (e: CancellationException) {
                // Switched off meanwhile: refuseSwitchedOff answers it.
                throw e
            } catch (e: PhoneToolException) {
                null to (e.message ?: "The phone couldn't do it.")
            } catch (e: Exception) {
                null to "The phone couldn't do it (${e::class.simpleName})."
            }
        }
        if (tool != null && error != SWITCHED_OFF) _recent.update { (listOf(PhoneToolUse(tool, now(), error)) + it).take(MAX_RECENT) }
        // Out of the running list before answering: switching the tool off now can't send a second answer.
        if (!finish(request.id)) return
        answer(request.id, result, error)
    }

    /** Takes [requestId] out of the running list; false when switching off already did (and answered it). */
    private suspend fun finish(requestId: String): Boolean = runningLock.withLock {
        val mine = running.remove(requestId) != null
        if (mine) markAnswered(requestId)
        mine
    }

    private fun markAnswered(requestId: String) {
        answered.addLast(requestId)
        while (answered.size > MAX_ANSWERED) answered.removeFirst()
    }

    /** Stops what runs for a tool that's off now (a question on screen, say) and refuses it. */
    private suspend fun refuseSwitchedOff(on: Set<PhoneTool>) {
        val ids = on.map { it.id }.toSet()
        val stopped = runningLock.withLock {
            running.filterValues { it.tool !in ids }.keys.toList().onEach { id ->
                running.remove(id)?.job?.cancel()
                markAnswered(id)
            }
        }
        for (id in stopped) scope.launch { answer(id, null, SWITCHED_OFF) }
    }

    private suspend fun answer(requestId: String, result: JsonElement?, error: String?) {
        try {
            val me = identity()
            api.phoneAnswer(me.id, me.key, requestId, result, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The gateway will tell the agent the phone didn't answer.
        }
    }

    /**
     * The id and secret this phone uses for phone tools, made the first time they're needed. Kept in one
     * entry: losing it loses both, and a fresh pair binds again instead of being refused for a mismatch.
     */
    private suspend fun identity(): Identity = identityLock.withLock {
        store.get(KEY_IDENTITY)?.let { stored ->
            runCatching { HermesJson.decodeFromString(Identity.serializer(), stored) }.getOrNull()?.let { return@withLock it }
        }
        val made = Identity(hex(ID_BYTES), hex(KEY_BYTES))
        store.put(KEY_IDENTITY, HermesJson.encodeToString(Identity.serializer(), made))
        made
    }

    private fun hex(bytes: Int) = secureRandomBytes(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Whether this gateway was told about tools, per gateway: each keeps its own list. */
    private suspend fun reportedKey() = KEY_REPORTED + (gateways.current()?.gatewayUrl?.value ?: "")

    private fun toolName(payload: JsonElement?): String? =
        ((payload as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

    companion object {
        const val TOOL_PREFIX = "phone_"

        /** For chats this phone doesn't watch: how long a call may wait before the phone looks. */
        const val POLL_MS = 15_000L

        /** After a chat's `phone_*` tool starts: when to look for its call. */
        val EVENT_DRAINS_MS = longArrayOf(300, 700, 1_500, 3_000)

        /** Between tries of a report that failed. */
        val RETRY_MS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)

        /** A change waits for the user; the gateway gives up after two minutes. */
        const val RUN_LIMIT_MS = 125_000L
        const val MAX_RECENT = 20
        const val SWITCHED_OFF = "The user turned this tool off on the phone."
        private const val MAX_ANSWERED = 200
        private const val ID_BYTES = 16
        private const val KEY_BYTES = 32
        private const val KEY_IDENTITY = "phone.identity"
        private const val KEY_REPORTED = "phone.reported."
    }
}

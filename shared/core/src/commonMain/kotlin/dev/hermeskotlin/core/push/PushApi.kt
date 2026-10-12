package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** What the herald-push plugin answers to `/herald-push …` (JSON in the command's output). */
@Serializable
data class PushPluginReply(
    val ok: Boolean = false,
    val error: String? = null,
    val version: String? = null,
    val gateway: PushGateway? = null,
    val watching: Boolean = false,
) {
    /** True when the plugin is at least [minimum] ("0.1.1"); an unparseable version counts as too old. */
    fun versionAtLeast(minimum: String): Boolean {
        val have = version?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
        val want = minimum.split('.').map { it.toInt() }
        for (i in 0 until maxOf(have.size, want.size)) {
            val a = have.getOrElse(i) { 0 }
            val b = want.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }
}

/** Where the gateway's herald-push plugin stands after [PushApi.prepare]. */
sealed interface PluginState {
    data class Ready(val info: PushPluginReply) : PluginState
    data object Missing : PluginState
    data class Failed(val reason: String?) : PluginState
}

/** A phone tool call the agent is waiting on (`/herald-push phone pending`). */
@Serializable
data class PhoneRequest(
    val id: String,
    val tool: String,
    val args: JsonObject = JsonObject(emptyMap()),
    /** The chat the agent called it from, empty when it doesn't say. */
    val session: String = "",
    /** When the agent stops waiting, in the gateway's epoch seconds. */
    val expires: Long = 0,
)

/** What the plugin answers to `/herald-push phone …`. */
@Serializable
private data class PhoneReply(
    val ok: Boolean = false,
    val error: String? = null,
    val tools: List<String> = emptyList(),
    val requests: List<PhoneRequest> = emptyList(),
)

/**
 * The gateway side of push, over the dashboard socket: the herald-push plugin is installed with the same
 * call as Desktop's Plugins hub, and talked to through its `/herald-push` command, which only answers the
 * dashboard (never a messaging platform). Registering over this authenticated link is what makes the
 * gateway keys the phone pins trustworthy.
 */
class PushApi(private val connection: GatewayConnection) {

    private val preparing = Mutex()

    /** The plugin's state, or null when it isn't installed (or is off) on this gateway. */
    suspend fun info(): PushPluginReply? = command("info")

    suspend fun register(registration: PushRegistration): PushPluginReply =
        command("register " + HermesJson.encodeToString(PushRegistration.serializer(), registration))
            ?: throw RpcException(0, "Herald push isn't installed on the gateway.")

    suspend fun unregister(deviceId: String): Boolean = command("unregister $deviceId")?.ok == true

    /** Asks the gateway to send this phone a test notification through ntfy. */
    suspend fun test(deviceId: String): Boolean = command("test $deviceId")?.ok == true

    /**
     * Installs the plugin from its repository at the reviewed commit (git clone on the gateway, live without
     * a restart). With [force] an existing copy is replaced, which is how an old version is brought up to date.
     */
    suspend fun install(force: Boolean = false) {
        client().request(
            "plugins.manage",
            buildJsonObject {
                put("action", "install")
                put("identifier", PLUGIN_REPO)
                // A reviewed commit, never whatever the branch holds today: this code runs on the gateway.
                put("ref", PLUGIN_REF)
                put("enable", true)
                put("force", force)
            },
            timeoutMs = INSTALL_TIMEOUT_MS,
        )
    }

    /**
     * Switches an installed but disabled copy on. False when the gateway has no such plugin (then it needs
     * [install]) or refused.
     */
    suspend fun enable(): Boolean {
        val reply = try {
            client().request(
                "plugins.manage",
                buildJsonObject {
                    put("action", "toggle")
                    put("name", PLUGIN_COMMAND)
                    put("enable", true)
                },
                timeoutMs = INSTALL_TIMEOUT_MS,
            )
        } catch (e: RpcException) {
            return false
        }
        return ((reply as? JsonObject)?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
    }

    /**
     * The plugin, ready: switched on when it was only disabled, installed when missing (only with [install]),
     * and reinstalled at the reviewed commit when older than [minimum]. [onStep] says what it's doing. One at a
     * time: push and phone tools both prepare on connect, and two installs must not race on the gateway.
     */
    suspend fun prepare(
        install: Boolean,
        minimum: String = MIN_PLUGIN_VERSION,
        onStep: (String) -> Unit = {},
    ): PluginState = preparing.withLock {
        var info = info()
        if (info == null && install) {
            // Present but switched off (in Desktop's Plugins hub, say): a toggle is enough, and an
            // install would be refused as "already exists".
            onStep("Setting up on the gateway…")
            if (enable()) info = info()
            if (info == null) {
                onStep("Installing on the gateway…")
                install()
                info = info()
            }
        }
        if (info != null && info.ok && !info.versionAtLeast(minimum)) {
            onStep("Updating the gateway's plugin…")
            install(force = true)
            info = info()
            if (info != null && info.ok && !info.versionAtLeast(minimum)) {
                return@withLock PluginState.Failed("The gateway's herald-push plugin is too old (${info.version}). Update it there.")
            }
        }
        when {
            info == null -> PluginState.Missing
            !info.ok -> PluginState.Failed(info.error)
            else -> PluginState.Ready(info)
        }
    }

    /**
     * Tells the gateway which phone tools this phone has on; returns the ones it will offer, or null without the
     * plugin. [key] is this phone's secret: the first report binds the id to it, and every phone call needs it.
     */
    suspend fun phoneTools(deviceId: String, key: String, name: String, tools: List<String>, pushId: String?): List<String>? {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("key", key)
            put("name", name)
            // The push registration a call nudges; only ever a nudge, the call itself comes over this socket.
            if (pushId != null) put("push_id", pushId)
            put("tools", JsonArray(tools.map(::JsonPrimitive)))
        }
        val reply = output("phone tools $body")?.let(::phoneReply) ?: return null
        if (!reply.ok) throw RpcException(0, reply.error ?: "The gateway refused the phone tools.")
        return reply.tools
    }

    /** The tool calls waiting for this phone, or null without the plugin. */
    suspend fun phonePending(deviceId: String, key: String): List<PhoneRequest>? {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("key", key)
        }
        return output("phone pending $body")?.let(::phoneReply)?.takeIf { it.ok }?.requests
    }

    /** Hands the agent the [result] of a tool call, or the [error] it failed with. */
    suspend fun phoneAnswer(deviceId: String, key: String, requestId: String, result: JsonElement?, error: String?): Boolean {
        val body = buildJsonObject {
            put("device_id", deviceId)
            put("key", key)
            put("id", requestId)
            if (error != null) put("error", error) else put("result", result ?: JsonNull)
        }
        return output("phone answer $body")?.let(::phoneReply)?.ok == true
    }

    private fun phoneReply(output: String): PhoneReply? =
        runCatching { HermesJson.decodeFromString(PhoneReply.serializer(), output) }.getOrNull()

    private suspend fun command(arg: String): PushPluginReply? {
        val output = output(arg) ?: return null
        return runCatching { HermesJson.decodeFromString(PushPluginReply.serializer(), output) }.getOrNull()
    }

    /** What `/herald-push <arg>` printed, or null when the plugin isn't loaded. */
    private suspend fun output(arg: String): String? {
        val reply = try {
            client().request(
                "command.dispatch",
                buildJsonObject {
                    put("name", PLUGIN_COMMAND)
                    put("arg", arg)
                },
            )
        } catch (e: RpcException) {
            // 4018: no such command, i.e. the plugin isn't loaded.
            if (e.code == NOT_A_COMMAND) return null
            throw e
        }
        val obj = reply as? JsonObject ?: return null
        if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "plugin") return null
        return (obj["output"] as? JsonPrimitive)?.contentOrNull
    }

    private fun client(): JsonRpcClient = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    companion object {
        const val PLUGIN_COMMAND = "herald-push"
        const val PLUGIN_REPO = "https://github.com/Giton22/hermes-herald-push"

        /** The plugin commit this Herald was built and reviewed against. */
        const val PLUGIN_REF = "72e1d8677a9d79acae2ca591bfac288f71d12546"

        /** The oldest plugin this Herald works with; an older copy is reinstalled at [PLUGIN_REF]. */
        const val MIN_PLUGIN_VERSION = "0.1.1"

        /** The oldest plugin with phone tools as this Herald speaks them (a key per phone). */
        const val MIN_PHONE_PLUGIN_VERSION = "0.2.1"
        private const val NOT_A_COMMAND = 4018
        private const val INSTALL_TIMEOUT_MS = 180_000L
    }
}

/** This phone's push identity, as the platform keeps it (Android: keys wrapped by the AndroidKeyStore). */
interface PushKeys {
    val deviceId: String

    /** What to send the gateway; [name] is how the gateway lists this phone. */
    fun registration(name: String): PushRegistration

    /** The gateway keys trusted for [gatewayUrl], pinned when it answered a registration. */
    fun pinned(gatewayUrl: String): PushGateway?

    /** Whether [pin] would take [keys]: well-formed, on the curve. Checked before registering with them. */
    fun accepts(keys: PushGateway): Boolean

    /** Trusts [keys] for [gatewayUrl] and no other gateway: one phone identity serves one gateway. */
    fun pin(gatewayUrl: String, keys: PushGateway)

    /**
     * Drops this identity (keys and topics) because the gateway at [gatewayUrl] holds it and should stop
     * using it; the gateway is told later, when it can be reached ([retired]). A fresh identity follows.
     */
    fun retire(gatewayUrl: String)

    /** Identities given up but not yet unregistered from their gateway, by gateway URL. */
    fun retired(gatewayUrl: String): Set<String>
    fun forgetRetired(gatewayUrl: String, deviceId: String)

    /** The URL of the gateway this identity is registered with, if any. */
    fun pinnedGatewayUrl(): String?
}

/** Whether a gateway was told about this phone, as a short status line. */
data class PushStatus(val state: State = State.Unknown, val detail: String? = null) {
    enum class State { Unknown, Off, NotInstalled, Working, On, Failed }
}

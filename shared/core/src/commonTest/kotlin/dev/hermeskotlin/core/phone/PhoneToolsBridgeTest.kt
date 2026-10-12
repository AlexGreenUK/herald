package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.storage.KeyValueStore
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushGateway
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushRegistration
import dev.hermeskotlin.core.rpc.FakeTransport
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val URL = "https://a.example"

class PhoneToolsBridgeTest {

    /** A push identity, registered with [pinnedTo] when set. */
    private class Keys(val pinnedTo: String? = null) : PushKeys {
        override val deviceId = "0b6f2c1e-1111-4222-8333-944445555666"
        override fun registration(name: String) = PushRegistration(deviceId, name, "https://ntfy.sh", "hp-a", "hp-b", "E", "S")
        override fun pinned(gatewayUrl: String): PushGateway? = null
        override fun accepts(keys: PushGateway) = true
        override fun pin(gatewayUrl: String, keys: PushGateway) = Unit
        override fun retire(gatewayUrl: String) = Unit
        override fun retired(gatewayUrl: String) = emptySet<String>()
        override fun forgetRetired(gatewayUrl: String, deviceId: String) = Unit
        override fun pinnedGatewayUrl(): String? = pinnedTo
    }

    /** What the gateway's herald-push plugin answers, with its phone tool calls kept in memory. */
    private class Plugin(var installed: Boolean = true, var version: String = PushApi.MIN_PHONE_PLUGIN_VERSION) {
        val reports = mutableListOf<List<String>>()
        val reportBodies = mutableListOf<JsonObject>()
        val keys = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
        val waiting = mutableListOf<JsonObject>()
        val answers = mutableMapOf<String, JsonObject>()
        val installs = mutableListOf<JsonObject>()

        /** How many `phone tools` reports to fail before taking one. */
        var failReports = 0

        fun call(id: String, tool: String, args: JsonObject = JsonObject(emptyMap())) {
            waiting += buildJsonObject {
                put("id", id)
                put("tool", tool)
                put("args", args)
                put("session", "chat-1")
                put("expires", 9_999_999_999L)
            }
        }

        fun serve(scope: CoroutineScope, transport: FakeTransport) = scope.launch {
            var answered = 0
            transport.sent.collect { sent ->
                while (answered < sent.size) {
                    val request = sent[answered++]
                    val id = request["id"] ?: continue
                    val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
                    val reply = when ((request["method"] as JsonPrimitive).content) {
                        "plugins.manage" -> {
                            installs += params
                            installed = true
                            version = PushApi.MIN_PHONE_PLUGIN_VERSION
                            """{"ok":true}"""
                        }
                        "command.dispatch" -> if (!installed) null else command(params["arg"]!!.jsonPrimitive.content)
                        else -> "{}"
                    }
                    transport.push(
                        if (reply == null) {
                            """{"jsonrpc":"2.0","id":$id,"error":{"code":4018,"message":"not a command"}}"""
                        } else {
                            """{"jsonrpc":"2.0","id":$id,"result":$reply}"""
                        },
                    )
                }
            }
        }

        private fun command(arg: String): String {
            val words = arg.split(" ", limit = 3)
            val output: JsonObject = when {
                words[0] == "info" -> buildJsonObject {
                    put("ok", true)
                    put("version", version)
                }
                words[0] == "phone" -> {
                    val body = HermesJson.parseToJsonElement(words[2]).jsonObject
                    keys += body["key"]!!.jsonPrimitive.content
                    ids += body["device_id"]!!.jsonPrimitive.content
                    when (words[1]) {
                        "tools" -> if (failReports > 0) {
                            failReports--
                            buildJsonObject {
                                put("ok", false)
                                put("error", "busy")
                            }
                        } else {
                            val tools = body["tools"]!!.jsonArray.map { it.jsonPrimitive.content }
                            reports += tools
                            reportBodies += body
                            buildJsonObject {
                                put("ok", true)
                                put("tools", JsonArray(tools.map(::JsonPrimitive)))
                            }
                        }
                        "pending" -> buildJsonObject {
                            put("ok", true)
                            put("requests", JsonArray(waiting.filter { it["id"]!!.jsonPrimitive.content !in answers }))
                        }
                        else -> {
                            answers[body["id"]!!.jsonPrimitive.content] = body
                            buildJsonObject { put("ok", true) }
                        }
                    }
                }
                else -> buildJsonObject { put("ok", false) }
            }
            return HermesJson.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("type", "plugin")
                put("output", output.toString())
            })
        }
    }

    /** Answers each tool with its own name, except what [block] holds until cancelled. */
    private class Runner(val block: Set<PhoneTool> = emptySet()) : PhoneToolRunner {
        val cancelled = CompletableDeferred<PhoneTool>()

        override suspend fun run(tool: PhoneTool, args: JsonObject, session: String): JsonElement {
            if (tool in block) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(tool)
                }
            }
            if (args["fail"] != null) throw PhoneToolException("Battery is unknown.")
            return buildJsonObject { put("tool", tool.id) }
        }
    }

    private class Setup(val bridge: PhoneToolsBridge, val connection: GatewayConnection, val settings: SettingsStore, val store: KeyValueStore)

    private val transports = ArrayDeque<FakeTransport>()

    private suspend fun TestScope.setup(
        runner: PhoneToolRunner,
        on: List<PhoneTool> = emptyList(),
        all: Boolean = on.isNotEmpty(),
        keys: PushKeys = Keys(),
        pushAnywhere: Boolean = false,
        store: KeyValueStore = InMemoryKeyValueStore(),
    ): Setup {
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val engine = MockEngine { respond("""{"ticket":"T","ttl_seconds":30}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val connection = GatewayConnection(AuthApi(createHttpClient(engine, cookies), cookies), { _, _ -> transports.removeFirst() }, backgroundScope)
        val settings = SettingsStore(InMemoryKeyValueStore(), backgroundScope)
        settings.settings.filterNotNull().first()
        settings.update { it.copy(phoneTools = all, phoneToolsOn = on.map { t -> t.id }, pushAnywhere = pushAnywhere) }
        val gateways = GatewayRepository(InMemoryKeyValueStore()).apply { save(SavedGateway(URL)) }
        val bridge = PhoneToolsBridge(
            PushApi(connection), keys, store, connection, gateways, settings, runner, backgroundScope, { "Pixel" }, { currentTime },
        )
        bridge.start()
        return Setup(bridge, connection, settings, store)
    }

    private suspend fun TestScope.connect(s: Setup, plugin: Plugin): FakeTransport {
        val transport = FakeTransport()
        transports += transport
        plugin.serve(backgroundScope, transport)
        s.connection.start(GatewayUrl.parse(URL))
        transport.push(FakeTransport.READY)
        s.connection.state.first { it is ConnectionState.Connected }
        return transport
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(60_000) { while (!condition()) delay(10) }

    private fun Plugin.error(id: String) = answers[id]?.get("error")?.jsonPrimitive?.contentOrNull

    @Test
    fun aPhoneThatNeverTurnedToolsOnIsNotAnnounced() = runTest {
        val s = setup(Runner())
        val plugin = Plugin()
        val transport = connect(s, plugin)
        until { s.bridge.status.value.state == PhoneToolsStatus.State.Off }
        delay(PhoneToolsBridge.POLL_MS * 2)
        assertTrue(plugin.reports.isEmpty())
        assertTrue(transport.sent.value.none { it.toString().contains("phone pending") })
    }

    @Test
    fun onlySwitchedOnToolsAreReportedAndRun() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id)
        plugin.call("b".repeat(32), PhoneTool.Network.id)
        connect(s, plugin)
        until { plugin.answers.size == 2 }
        assertEquals(listOf(listOf(PhoneTool.Battery.id)), plugin.reports)
        assertEquals("""{"tool":"phone_battery"}""", plugin.answers["a".repeat(32)]!!["result"].toString())
        assertEquals(PhoneToolsBridge.SWITCHED_OFF, plugin.error("b".repeat(32)))
        assertEquals(PhoneToolsStatus.State.On, s.bridge.status.value.state)
        assertEquals(listOf(PhoneTool.Battery), s.bridge.recent.value.map { it.tool })
        // One key, made once and sent with every call.
        assertEquals(1, plugin.keys.size)
        assertEquals(64, plugin.keys.single().length)
    }

    @Test
    fun theSwitchForAllOfThemWins() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery), all = false)
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id)
        connect(s, plugin)
        delay(PhoneToolsBridge.POLL_MS * 2)
        assertTrue(plugin.answers.isEmpty())
        assertTrue(plugin.reports.isEmpty())
    }

    @Test
    fun aToolsErrorReachesTheAgent() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id, buildJsonObject { put("fail", true) })
        connect(s, plugin)
        until { plugin.answers.isNotEmpty() }
        assertEquals("Battery is unknown.", plugin.error("a".repeat(32)))
        assertEquals("Battery is unknown.", s.bridge.recent.value.single().error)
    }

    @Test
    fun aChatsToolEventFetchesTheCallAtOnce() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Network))
        val plugin = Plugin()
        val transport = connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        delay(100)
        val asked = currentTime
        plugin.call("c".repeat(32), PhoneTool.Network.id)
        transport.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"chat-1","payload":{"tool_id":"t1","name":"phone_network"}}}""")
        until { plugin.answers.isNotEmpty() }
        assertTrue(currentTime - asked < PhoneToolsBridge.POLL_MS, "answered after ${currentTime - asked} ms")
    }

    @Test
    fun turningAToolOffStopsAndRefusesItsCall() = runTest {
        val runner = Runner(block = setOf(PhoneTool.Battery))
        val s = setup(runner, on = listOf(PhoneTool.Battery, PhoneTool.Network))
        val plugin = Plugin()
        plugin.call("a".repeat(32), PhoneTool.Battery.id)
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        delay(100)
        s.settings.update { it.copy(phoneToolsOn = listOf(PhoneTool.Network.id)) }
        withTimeout(10_000) { assertEquals(PhoneTool.Battery, runner.cancelled.await()) }
        until { plugin.answers.isNotEmpty() }
        assertEquals(PhoneToolsBridge.SWITCHED_OFF, plugin.error("a".repeat(32)))
        until { plugin.reports.size >= 2 }
        assertEquals(listOf(PhoneTool.Network.id), plugin.reports.last())
    }

    @Test
    fun turningEverythingOffTellsTheGateway() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        s.settings.update { it.copy(phoneTools = false) }
        until { plugin.reports.size == 2 }
        assertEquals(emptyList(), plugin.reports.last())
        until { s.bridge.status.value.state == PhoneToolsStatus.State.Off }
    }

    @Test
    fun setUpAskedBeforeAnyToolIsOnStillInstalls() = runTest {
        // The switch for all of them comes first; every tool is still off then.
        val s = setup(Runner(), all = true)
        val plugin = Plugin(installed = false)
        connect(s, plugin)
        s.bridge.setUp()
        delay(1_000)
        assertTrue(plugin.installs.isEmpty())
        s.settings.update { it.copy(phoneToolsOn = listOf(PhoneTool.Battery.id)) }
        until { s.bridge.status.value.state == PhoneToolsStatus.State.On }
        assertEquals(1, plugin.installs.size)
    }

    @Test
    fun anOldPluginIsReplaced() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin(version = "0.2.0")
        connect(s, plugin)
        until { s.bridge.status.value.state == PhoneToolsStatus.State.On }
        assertEquals(true, plugin.installs.single()["force"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun aFailedReportIsTriedAgain() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin().apply { failReports = 2 }
        plugin.call("a".repeat(32), PhoneTool.Battery.id)
        connect(s, plugin)
        until { plugin.answers.isNotEmpty() }
        assertEquals(PhoneToolsStatus.State.On, s.bridge.status.value.state)
    }

    @Test
    fun aPushNudgeFetchesTheCall() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin()
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        delay(100)
        val asked = currentTime
        plugin.call("d".repeat(32), PhoneTool.Battery.id)
        s.bridge.wake()
        until { plugin.answers.isNotEmpty() }
        assertTrue(currentTime - asked < PhoneToolsBridge.POLL_MS)
    }

    @Test
    fun theIdentityIsKeptAndThePushRegistrationNamedOnlyWhenItsThisGateways() = runTest {
        val store = InMemoryKeyValueStore()
        val s = setup(Runner(), on = listOf(PhoneTool.Battery), keys = Keys(pinnedTo = URL), pushAnywhere = true, store = store)
        val plugin = Plugin()
        connect(s, plugin)
        until { plugin.reports.isNotEmpty() }
        assertEquals(Keys().deviceId, plugin.reportBodies.single()["push_id"]?.jsonPrimitive?.content)
        // Its own id, not the push one.
        assertTrue(Keys().deviceId !in plugin.ids)

        val other = setup(Runner(), on = listOf(PhoneTool.Battery), keys = Keys(pinnedTo = "https://b.example"), pushAnywhere = true)
        val second = Plugin()
        connect(other, second)
        until { second.reports.isNotEmpty() }
        assertNull(second.reportBodies.single()["push_id"])
    }

    @Test
    fun withoutThePluginItAsksToSetUpThenInstalls() = runTest {
        val s = setup(Runner(), on = listOf(PhoneTool.Battery))
        val plugin = Plugin(installed = false)
        connect(s, plugin)
        until { s.bridge.status.value.state == PhoneToolsStatus.State.NotInstalled }
        assertTrue(plugin.reports.isEmpty())
        s.bridge.setUp()
        until { s.bridge.status.value.state == PhoneToolsStatus.State.On }
        assertEquals(listOf(listOf(PhoneTool.Battery.id)), plugin.reports)
        assertNull(s.bridge.status.value.detail)
    }
}

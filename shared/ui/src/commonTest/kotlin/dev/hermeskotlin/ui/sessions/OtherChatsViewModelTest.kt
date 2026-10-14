package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.DraftStore
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.projects.ProjectsApi
import dev.hermeskotlin.core.rpc.RpcTransport
import dev.hermeskotlin.core.sessions.ActiveSessions
import dev.hermeskotlin.core.sessions.SeenStore
import dev.hermeskotlin.core.sessions.SessionListFilter
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.threads.ThreadsApi
import dev.hermeskotlin.core.threads.ThreadsRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The Sessions badge, the banner and Next, as [SessionsViewModel] works them out from the list and the live statuses. */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherChatsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Each listed chat's `last_active` (epoch seconds); the user last looked at every chat at [LOOKED]. */
    private val activity = linkedMapOf("a" to 500.0, "b" to 500.0, "c" to 500.0)

    /** What each chat's newest reply says, for the banner's preview. */
    private val replies = mutableMapOf("b" to "**Done**, all `tests` green.", "bot-b" to "Walked the dog.")

    /** Each preview read: the chat and the profile it was asked in. */
    private val previewReads = mutableListOf<Pair<String, String?>>()

    /** A preview read waits on this, when set. */
    private var previewGate: CompletableDeferred<Unit>? = null

    /** A gateway socket that says it's ready and answers `session.active_list` from [live] (stored id to status). */
    private class FakeSocket(val live: MutableMap<String, String> = mutableMapOf()) : RpcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED).apply {
            trySend("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override val incoming: Flow<String> = inbound.receiveAsFlow()

        override suspend fun send(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val id = message["id"] ?: return
            val result = if (message["method"]?.jsonPrimitive?.content == "session.active_list") {
                live.entries.joinToString(",", """{"sessions":[""", "]}") { (key, status) -> """{"id":"rt-$key","session_key":"$key","status":"$status"}""" }
            } else {
                "{}"
            }
            inbound.send("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }

        override suspend fun close(code: Short, reason: String) {
            inbound.close()
        }
    }

    private fun TestScope.viewModel(socket: FakeSocket = FakeSocket()): SessionsViewModel {
        val config = MockEngineConfig()
        config.dispatcher = dispatcher
        config.addHandler { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/sessions" -> respond(
                    activity.entries.joinToString(",", """{"sessions":[""", """],"total":${activity.size}}""") { (id, at) ->
                        """{"id":"$id","title":"${id.uppercase()}","last_active":$at}"""
                    },
                    HttpStatusCode.OK, json,
                )
                path.endsWith("/messages") -> {
                    val id = path.removePrefix("/api/sessions/").removeSuffix("/messages")
                    previewReads += id to request.url.parameters["profile"]
                    previewGate?.await()
                    val text = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(replies[id].orEmpty()))
                    respond("""{"session_id":"$id","messages":[{"role":"user","content":"go"},{"role":"assistant","content":$text}]}""", HttpStatusCode.OK, json)
                }
                path == "/api/auth/ws-ticket" -> respond("""{"ticket":"T","ttl_seconds":30}""", HttpStatusCode.OK, json)
                else -> respond("""{"display_name":"Me"}""", HttpStatusCode.OK, json)
            }
        }
        val cookies = PersistentCookiesStorage(InMemoryKeyValueStore())
        val client = createHttpClient(MockEngine(config), cookies)
        val auth = AuthApi(client, cookies)
        // In the background: the gateway's live list is asked every 10 s while anything collects it, so these
        // would never let the test run idle.
        val scope = CoroutineScope(backgroundScope.coroutineContext)
        val connection = GatewayConnection(auth, { _, _ -> socket }, scope)
        connection.start(gateway.gatewayUrl)
        val attention = AttentionTracker(connection, ChatHost(connection, SessionsApi(client), scope), ActiveSessions(connection, scope), scope)
        val vm = SessionsViewModel(
            SessionsApi(client), auth, connection, LastChatStore(InMemoryKeyValueStore()), ProfilesApi(client),
            attention, SeenStore(InMemoryKeyValueStore()) { LOOKED }, DraftStore(InMemoryKeyValueStore()), ProjectsApi(connection),
            ThreadsRepository(ThreadsApi(connection), SessionsApi(client), ProfilesApi(client)),
        )
        val scheduler = testScheduler
        vm.clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(START_MILLIS + scheduler.currentTime)
        }
        return vm
    }

    /** Bound, with the chat "a" on screen, past the settling: what's there now is taken stock of. */
    private fun TestScope.watching(socket: FakeSocket = FakeSocket()): SessionsViewModel {
        val vm = viewModel(socket)
        vm.bind(gateway)
        vm.setOpenChat(setOf("a"))
        vm.setChatShown(true)
        advanceTimeBy(SETTLED)
        return vm
    }

    /** [id] has a reply the user hasn't seen, as the next list read says. */
    private fun TestScope.replyIn(vm: SessionsViewModel, id: String) {
        activity[id] = LOOKED + 100
        vm.refresh()
        advanceTimeBy(QUIET)
    }

    @Test
    fun aReplyShowsAfterItSettlesWithItsPreview() {
        runTest(dispatcher) {
            val vm = watching()
            assertNull(vm.banner.value)
            activity["b"] = LOOKED + 100
            vm.refresh()
            // The list and the live statuses get a moment to agree first: a turn just starting isn't a reply.
            advanceTimeBy(1_000)
            assertNull(vm.banner.value)
            advanceTimeBy(1_000)
            val banner = assertNotNull(vm.banner.value)
            assertEquals("Reply in B", banner.message)
            assertEquals("Done, all tests green.", banner.preview)
            assertEquals(listOf<Pair<String, String?>>("b" to null), previewReads)
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
            assertEquals("b", vm.nextChat()?.id)
        }
    }

    @Test
    fun whatWasAlreadyUnreadWhenTheChatCameOnScreenIsNoBanner() {
        runTest(dispatcher) {
            activity["b"] = LOOKED + 100
            val vm = watching()
            advanceTimeBy(QUIET)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
            assertNull(vm.nextChat())
        }
    }

    @Test
    fun aBannerCanBeDismissedAsItWasBeforeItsPreviewCame() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn(vm, "b")
            val filled = assertNotNull(vm.banner.value)
            assertNotNull(filled.preview)
            // The screen still holds the event from before the preview filled in.
            vm.dismissBanner(filled.copy(preview = null))
            assertNull(vm.banner.value)
            // The badge stays: the reply is still unread.
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
        }
    }

    @Test
    fun theOpenChatNeverGetsABanner() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn(vm, "a")
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
            assertTrue(previewReads.isEmpty())
        }
    }

    @Test
    fun anotherProfileOrGatewayClearsTheBannerAndTheBadge() {
        runTest(dispatcher) {
            val vm = watching()
            replyIn(vm, "b")
            assertNotNull(vm.banner.value)
            vm.bind(gateway, profile = "work")
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
            assertNull(vm.nextChat())
            // Its own chats, as they are, are only taken stock of.
            advanceTimeBy(SETTLED)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
        }
    }

    @Test
    fun aPreviewLandingAfterASwitchIsDropped() {
        runTest(dispatcher) {
            val vm = watching()
            previewGate = CompletableDeferred()
            replyIn(vm, "b")
            assertEquals(null, assertNotNull(vm.banner.value).preview)
            vm.bind(gateway, profile = "work")
            previewGate?.complete(Unit)
            advanceTimeBy(SETTLED)
            assertNull(vm.banner.value)
        }
    }

    @Test
    fun aRequestShowsAtOnceAndOutranksAReply() {
        runTest(dispatcher) {
            val socket = FakeSocket()
            val vm = watching(socket)
            activity["c"] = LOOKED + 100
            socket.live["b"] = "waiting"
            vm.refresh()
            advanceTimeBy(15_000)
            val banner = assertNotNull(vm.banner.value)
            assertEquals("b", banner.chatId)
            assertTrue(banner.waiting)
            assertEquals(SessionsBadge(waiting = 1, replies = 1), vm.badge.value)
            assertEquals("b", vm.nextChat()?.id)
        }
    }

    private fun botRow(unread: Boolean) = OtherChatRow(
        OtherChat("bot-b", "Rex", unread = unread, activityAt = LOOKED + 100, bot = "rex"),
        ids = setOf("bot-a", "bot-b"),
    )

    @Test
    fun aBotChatsPreviewIsReadInTheBotsProfile() {
        runTest(dispatcher) {
            val vm = watching()
            vm.setBotChats(listOf(botRow(unread = false)))
            advanceTimeBy(QUIET)
            vm.setBotChats(listOf(botRow(unread = true)))
            advanceTimeBy(QUIET)
            val banner = assertNotNull(vm.banner.value)
            assertEquals("Reply in Rex", banner.message)
            assertEquals("rex", banner.bot)
            assertEquals("Walked the dog.", banner.preview)
            assertEquals(listOf<Pair<String, String?>>("bot-b" to "rex"), previewReads)
        }
    }

    @Test
    fun aBotChatOpenByItsOlderIdIsTheOpenChat() {
        runTest(dispatcher) {
            val vm = watching()
            vm.setOpenChat(setOf("bot-a"))
            vm.setBotChats(listOf(botRow(unread = false)))
            advanceTimeBy(QUIET)
            vm.setBotChats(listOf(botRow(unread = true)))
            advanceTimeBy(QUIET)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(), vm.badge.value)
        }
    }

    @Test
    fun nothingIsWatchedWhileTheChatIsOutOfSight() {
        runTest(dispatcher) {
            val vm = watching()
            vm.setChatShown(false)
            replyIn(vm, "b")
            assertNull(vm.banner.value)
            // Back: the reply came while away, so it's on the badge, though no banner tells of it.
            vm.setChatShown(true)
            advanceTimeBy(SETTLED)
            assertNull(vm.banner.value)
            assertEquals(SessionsBadge(replies = 1), vm.badge.value)
        }
    }

    private companion object {
        /** When the user last looked at every chat (epoch seconds). */
        const val LOOKED = 1_000.0

        /** The test clock's start: after [LOOKED], so time away counts from then. */
        const val START_MILLIS = 1_000_000L

        /** Past the reconnect's settling (3 s) and the other chats' (1.5 s), with room for the list read. */
        const val SETTLED = 6_000L

        /** Past the other chats' settling. */
        const val QUIET = 2_000L
    }
}

package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.capabilities.HubSkill
import dev.hermeskotlin.core.capabilities.SkillsHubApi
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsHubViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val gateway = SavedGateway("https://hermes.example.ts.net")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** A gateway that answers by path; every request is logged as `METHOD path?query`. */
    private class Rig(json: io.ktor.http.Headers) {
        val log = mutableListOf<String>()
        var sources = """{"sources":[],"featured":[{"name":"pdf","identifier":"skills-sh/acme/pdf"}],"installed":{}}"""
        var official = """{"skills":[{"name":"ascii-art","identifier":"official/creative/ascii-art","source":"official"}]}"""
        var search = """{"results":[{"name":"pdf","identifier":"skills-sh/acme/pdf"}],"timed_out":[]}"""
        var scan = """{"name":"pdf","verdict":"safe","policy":"allow"}"""
        var sourcesStatus = HttpStatusCode.OK
        var status: suspend () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"running":false,"exit_code":0,"lines":["done"]}""" }
        val config = MockEngineConfig().apply {
            addHandler { request ->
                val path = request.url.encodedPath
                log += "${request.method.value} $path?${request.url.encodedQuery}"
                when {
                    path.endsWith("hub/sources") -> respond(sources, sourcesStatus, json)
                    path.endsWith("hub/official") -> respond(official, HttpStatusCode.OK, json)
                    path.endsWith("hub/search") -> respond(search, HttpStatusCode.OK, json)
                    path.endsWith("hub/scan") -> respond(scan, HttpStatusCode.OK, json)
                    path.endsWith("/status") -> status().let { (code, body) -> respond(body, code, json) }
                    else -> respond("""{"name":"act1","ok":true}""", HttpStatusCode.OK, json)
                }
            }
        }
        fun count(part: String) = log.count { part in it }
    }

    private fun TestScope2.vm(rig: Rig): SkillsHubViewModel {
        rig.config.dispatcher = dispatcher
        return SkillsHubViewModel(SkillsHubApi(createHttpClient(MockEngine(rig.config)), pollIntervalMs = 0))
    }

    private fun rig() = Rig(json)

    private val community = HubSkill(name = "pdf", identifier = "skills-sh/acme/pdf", source = "skills-sh")
    private val official = HubSkill(name = "ascii-art", identifier = "official/creative/ascii-art", source = "official")

    @Test
    fun bindLoadsFeaturedAndOfficialForTheProfile() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        vm.bind(gateway, "ruby")
        assertTrue(rig.log.filter { "hub/sources" in it || "hub/official" in it }.all { "profile=ruby" in it })
        assertEquals(2, rig.log.size)
        assertEquals(listOf("pdf"), vm.state.value.featured.items?.map { it.name })
        assertEquals(listOf("ascii-art"), vm.state.value.official.items?.map { it.name })
    }

    @Test
    fun searchWaitsForThePause() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        vm.bind(gateway, null)
        vm.search("pd")
        advanceTimeBy(200)
        vm.search("pdf")
        advanceTimeBy(401)
        assertEquals(1, rig.count("hub/search"))
        assertTrue("q=pdf" in rig.log.single { "hub/search" in it })
        assertEquals(1, vm.state.value.results?.items?.size)
    }

    @Test
    fun blankQueryClearsResults() = runTest(dispatcher) {
        val vm = vm(rig())
        vm.bind(gateway, null)
        vm.search("pdf")
        advanceTimeBy(401)
        assertNotNull(vm.state.value.results)
        vm.search("  ")
        assertNull(vm.state.value.results)
    }

    @Test
    fun installedIdentifiersMarkRows() = runTest(dispatcher) {
        val rig = rig().apply { sources = """{"installed":{"official/creative/ascii-art":{"name":"ascii-art"}}}""" }
        val vm = vm(rig)
        vm.bind(gateway, null)
        assertTrue("official/creative/ascii-art" in vm.state.value.installed)
    }

    @Test
    fun officialInstallSkipsTheScan() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        var changed = 0
        vm.onSkillsChanged = { changed++ }
        vm.bind(gateway, null)
        vm.install(official)
        assertEquals(0, rig.count("hub/scan"))
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
        assertEquals("Installed ascii-art. It applies from the next chat.", vm.state.value.message)
        assertEquals(1, changed)
        assertTrue(vm.state.value.running.isEmpty())
    }

    @Test
    fun safeScanInstallsStraightAway() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        vm.bind(gateway, null)
        vm.install(community)
        assertEquals(1, rig.count("hub/scan"))
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
    }

    @Test
    fun cautionWaitsForConfirmation() = runTest(dispatcher) {
        val rig = rig().apply { scan = """{"name":"pdf","verdict":"caution","policy":"ask"}""" }
        val vm = vm(rig)
        vm.bind(gateway, null)
        vm.install(community)
        assertNotNull(vm.state.value.confirm)
        assertEquals(0, rig.count("POST /api/skills/hub/install"))
        vm.confirmInstall()
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
        assertNull(vm.state.value.confirm)

        vm.install(community)
        vm.cancelConfirm()
        assertNull(vm.state.value.confirm)
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
    }

    @Test
    fun blockedPolicyNeverInstalls() = runTest(dispatcher) {
        val rig = rig().apply { scan = """{"name":"pdf","verdict":"dangerous","policy":"block"}""" }
        val vm = vm(rig)
        vm.bind(gateway, null)
        vm.install(community)
        assertEquals(0, rig.count("POST /api/skills/hub/install"))
        assertEquals("block", vm.state.value.detail?.scan?.policy)
        assertNull(vm.state.value.confirm)
    }

    @Test
    fun scanBlockedInstallOffersTheScan() = runTest(dispatcher) {
        val rig = rig().apply {
            status = { HttpStatusCode.OK to """{"running":false,"exit_code":1,"lines":["Not installed: the security scan found 2 high-risk patterns"]}""" }
        }
        val vm = vm(rig)
        vm.bind(gateway, null)
        vm.install(community)
        assertEquals("pdf was blocked by the security scan.", vm.state.value.message)
        assertEquals("safe", vm.state.value.detail?.scan?.verdict)
    }

    @Test
    fun failureShowsTheLastLogLine() = runTest(dispatcher) {
        val rig = rig().apply { status = { HttpStatusCode.OK to """{"running":false,"exit_code":1,"lines":["fetching","network down"]}""" } }
        val vm = vm(rig)
        vm.bind(gateway, null)
        vm.install(community)
        assertEquals("Couldn't install pdf: network down", vm.state.value.message)
    }

    @Test
    fun secondInstallTapWhileRunningIsIgnored() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val rig = rig().apply {
            status = { hold.await(); HttpStatusCode.OK to """{"running":false,"exit_code":0,"lines":[]}""" }
        }
        val vm = vm(rig)
        vm.bind(gateway, null)
        vm.install(official)
        vm.install(official)
        assertEquals(1, rig.count("POST /api/skills/hub/install"))
        assertTrue("official/creative/ascii-art" in vm.state.value.running)
        hold.complete(Unit)
    }

    @Test
    fun uninstallCallsBackSoTheListRefetches() = runTest(dispatcher) {
        val rig = rig()
        val vm = vm(rig)
        var changed = 0
        vm.onSkillsChanged = { changed++ }
        vm.bind(gateway, null)
        vm.uninstall("ascii-art")
        assertEquals(1, changed)
        assertEquals("Removed ascii-art. It applies from the next chat.", vm.state.value.message)
    }

    @Test
    fun profileSwitchCancelsPolling() = runTest(dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val rig = rig().apply { status = { hold.await(); HttpStatusCode.OK to """{"running":false,"exit_code":0,"lines":[]}""" } }
        val vm = vm(rig)
        vm.bind(gateway, "old")
        vm.install(official)
        vm.bind(gateway, "other")
        val before = rig.count("/status")
        hold.complete(Unit)
        assertEquals(before, rig.count("/status"))
        assertNull(vm.state.value.message)
        assertTrue(vm.state.value.running.isEmpty())
    }

    @Test
    fun olderGatewayWithoutHubRoutesSaysSo() = runTest(dispatcher) {
        val rig = rig().apply { sourcesStatus = HttpStatusCode.NotFound; sources = """{"detail":"Not Found"}""" }
        val vm = vm(rig)
        vm.bind(gateway, null)
        assertTrue(vm.state.value.unsupported)
    }

    @Test
    fun expiredSessionDuringInstallRaisesTheFlag() = runTest(dispatcher) {
        val rig = rig().apply { status = { HttpStatusCode.Unauthorized to "{}" } }
        val vm = vm(rig)
        vm.bind(gateway, null)
        vm.install(official)
        assertTrue(vm.state.value.sessionExpired)
        vm.consumeSessionExpired()
        assertFalse(vm.state.value.sessionExpired)
    }
}

private typealias TestScope2 = kotlinx.coroutines.test.TestScope

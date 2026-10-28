package nl.bartvandermeeren.dudan

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import nl.bartvandermeeren.dudan.chat.SkillTags
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.HermesJson
import nl.bartvandermeeren.dudan.data.ServerCapabilities
import nl.bartvandermeeren.dudan.data.SkillCatalog
import nl.bartvandermeeren.dudan.data.SkillDetail
import nl.bartvandermeeren.dudan.data.SkillInfo
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTagsTest {
    private val todoist = SkillInfo("todoist-task-operator", "Manage Todoist tasks", "productivity")
    private val planning = SkillInfo("planning_with_files", "Plan long tasks in files", "openclaw-imports")
    private val notes = SkillInfo("Obsidian Notes", "Search and write notes in the vault", "knowledge")
    private val skills = listOf(todoist, planning, notes)

    @Test
    fun findsKnownTagsOnlyInOrderWithoutRepeats() {
        val text = "\$Todoist-task-operator add milk, then \$planning_with_files and \$todoist-task-operator again. \$unknown"
        assertEquals(listOf("todoist-task-operator", "planning_with_files"), SkillTags.find(text, skills))
    }

    @Test
    fun namesWithSpacesAreTaggedAsSlugs() {
        assertEquals("obsidian-notes", SkillTags.slug(notes.name))
        assertEquals(listOf("Obsidian Notes"), SkillTags.find("zoek dit op in \$obsidian-notes", skills))
    }

    @Test
    fun moneyAndMidWordDollarsAreNotTags() {
        assertEquals(emptyList<String>(), SkillTags.find("Dat kost \$5 en a\$todoist-task-operator of \$\$todoist-task-operator", skills))
        assertEquals(emptyList<IntRange>(), SkillTags.ranges("a\$b kost \$5", setOf("b", "todoist-task-operator")))
    }

    @Test
    fun aPickedSkillIsFoundAgainWhenItsNameStartsWithAnUnderscoreOrHyphen() {
        listOf(SkillInfo("_notes", null, null), SkillInfo("-drafts", null, null)).forEach { skill ->
            val (text, _) = SkillTags.complete("\$", SkillTags.queryAt("\$", 1)!!, skill)
            assertEquals(listOf(skill.name), SkillTags.find(text, listOf(skill)))
            assertEquals(listOf(0..skill.name.length), SkillTags.ranges(text, setOf(SkillTags.slug(skill.name))))
        }
    }

    @Test
    fun trailingPunctuationIsNotPartOfTheTag() {
        assertEquals(listOf("todoist-task-operator"), SkillTags.find("Use \$todoist-task-operator-", skills))
        val text = "Use \$todoist-task-operator."
        assertEquals(listOf(4..25), SkillTags.ranges(text, setOf("todoist-task-operator")))
        assertEquals("\$todoist-task-operator", text.substring(4, 26))
    }

    @Test
    fun queryCoversTheTagAroundTheCursor() {
        assertEquals(SkillTags.Query(0, 1, ""), SkillTags.queryAt("$", 1))
        assertEquals(SkillTags.Query(4, 9, "to"), SkillTags.queryAt("zeg \$todo nu", 7))
        assertNull(SkillTags.queryAt("zeg \$todo nu", 10))
        assertNull(SkillTags.queryAt("a\$todo", 6))
        assertNull(SkillTags.queryAt("geen tag", 4))
    }

    @Test
    fun completingReplacesTheWholeTagAndAddsOneSpace() {
        val atEnd = SkillTags.complete("hoi \$to", SkillTags.queryAt("hoi \$to", 7)!!, todoist)
        assertEquals("hoi \$todoist-task-operator " to 27, atEnd)
        val midWord = SkillTags.complete("\$todx boodschappen", SkillTags.queryAt("\$todx boodschappen", 3)!!, todoist)
        assertEquals("\$todoist-task-operator boodschappen" to 23, midWord)
    }

    @Test
    fun suggestionsPutNameStartsFirst() {
        val suggested = SkillTags.suggest("pl", skills)
        assertEquals(planning, suggested.first())
        assertEquals(listOf(notes, planning, todoist), SkillTags.suggest("", skills))
        assertEquals(listOf(notes), SkillTags.suggest("vault", skills))
    }

    @Test
    fun instructionsNameEveryTaggedSkill() {
        assertNull(SkillTags.instructions(emptyList()))
        val text = SkillTags.instructions(listOf("todoist-task-operator", "planning_with_files"))!!
        assertTrue(text, text.contains("todoist-task-operator, planning_with_files") && text.contains("skill_view"))
    }
}

class SkillDetailTest {
    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    @Test
    fun parsesTheSkillAndFlattensItsFiles() {
        val skill = SkillDetail.from(
            json(
                """{"object":"skill","name":"todoist-task-operator","description":"Manage tasks","category":"productivity",
                   "path":"productivity/todoist-task-operator/SKILL.md","content":"---\nname: x\n---\n# Todoist","body":"# Todoist",
                   "linked_files":{"references":["references/labels.md"],"scripts":["scripts/sync.py"]}}""",
            ),
            requested = "todoist-task-operator",
        )
        assertEquals("todoist-task-operator", skill.name)
        assertEquals("productivity/todoist-task-operator/SKILL.md", skill.path)
        assertEquals("# Todoist", skill.body)
        assertEquals(listOf("references/labels.md", "scripts/sync.py"), skill.files)
    }

    @Test
    fun fallsBackToTheRawFileAndTheRequestedName() {
        val skill = SkillDetail.from(json("""{"content":"# Raw","description":"","linked_files":null}"""), requested = "raw-skill")
        assertEquals("raw-skill", skill.name)
        assertEquals("# Raw", skill.body)
        assertNull(skill.description)
        assertNull(skill.path)
        assertEquals(emptyList<String>(), skill.files)
    }

    @Test
    fun capabilitiesTellWhetherTheServerSendsOneSkill() {
        val patched = ServerCapabilities.from(
            json(
                """{"model":"hermes-agent","endpoints":{"skills":{"method":"GET","path":"/v1/skills"},
                   "skill":{"method":"GET","path":"/v1/skills/{name}"}}}""",
            ),
        )
        assertEquals("hermes-agent", patched.model)
        assertTrue(patched.readsSkills)
        val stock = ServerCapabilities.from(json("""{"model":"hermes-agent","endpoints":{"skills":{"method":"GET","path":"/v1/skills"}}}"""))
        assertFalse(stock.readsSkills)
        assertTrue("skills" in stock.endpoints)
        // Older servers list no endpoints at all.
        assertFalse(ServerCapabilities.from(json("""{"model":"hermes-agent","features":{"run_submission":true}}""")).readsSkills)
    }

    @Test
    fun readsOneSkillByItsEscapedName() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"name":"todoist task","body":"# Todoist"}"""))
        server.start()
        try {
            val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "secret-key-1234567") }
            assertEquals("# Todoist", api.skill("todoist task").body)
            assertEquals("/v1/skills/todoist%20task", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}

class SkillCatalogTest {
    private fun hermes(skills: MockResponse, capabilities: String): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/skills" -> skills
                "/v1/capabilities" -> MockResponse().setBody(capabilities)
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    private fun <T> withCatalog(server: MockWebServer, block: suspend (SkillCatalog) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "secret-key-1234567") }
            return runBlocking { block(SkillCatalog(api, scope)) }
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }

    @Test
    fun aPatchedServerListsSkillsAndCanSendEachOne() {
        val server = hermes(
            MockResponse().setBody("""{"object":"list","data":[{"name":"todoist","description":"Manage Todoist tasks","category":"productivity"}]}"""),
            """{"endpoints":{"skills":{"method":"GET","path":"/v1/skills"},"skill":{"method":"GET","path":"/v1/skills/{name}"}}}""",
        )
        withCatalog(server) { catalog ->
            assertEquals(listOf("todoist"), catalog.skills().map { it.name })
            assertTrue(catalog.state.value.readsSkills)
            assertNull(catalog.state.value.error)
        }
    }

    @Test
    fun stockHermesAnswers500AndTheTagsFindNothing() {
        val server = hermes(
            MockResponse().setResponseCode(500).setBody("""{"error":{"message":"Failed to enumerate skills","type":"server_error"}}"""),
            """{"endpoints":{"skills":{"method":"GET","path":"/v1/skills"}}}""",
        )
        withCatalog(server) { catalog ->
            assertEquals(emptyList<SkillInfo>(), catalog.skills())
            val state = catalog.state.value
            assertNull(state.skills)
            assertEquals(500, state.errorStatus)
            assertFalse(state.readsSkills)
        }
    }

    /** A Hermes whose skill list answers [status] (200 lists one skill), counting list requests in [calls]. */
    private fun countingHermes(status: AtomicInteger, calls: AtomicInteger): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/skills" -> {
                    calls.incrementAndGet()
                    if (status.get() == 200) MockResponse().setBody("""{"data":[{"name":"todoist"}]}""")
                    else MockResponse().setResponseCode(status.get()).setBody("""{"error":{"message":"Failed to enumerate skills"}}""")
                }
                "/v1/capabilities" -> MockResponse().setBody("{}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    @Test
    fun afterTheKnown500OnlyOpeningTheSkillsScreenAsksAgain() {
        val status = AtomicInteger(500)
        val calls = AtomicInteger()
        withCatalog(countingHermes(status, calls)) { catalog ->
            // Each message with a `$` asks for the list; after the 500 they don't wait for another try.
            assertEquals(emptyList<SkillInfo>(), catalog.skills())
            assertEquals(emptyList<SkillInfo>(), catalog.skills())
            catalog.ensureLoaded()
            delay(300)
            assertEquals(1, calls.get())

            // Hermes got the fix; opening the Skills screen asks again.
            status.set(200)
            catalog.refresh()
            withTimeout(5_000) { while (catalog.state.value.skills == null) delay(20) }
            assertEquals(2, calls.get())
            assertEquals(listOf("todoist"), catalog.skills().map { it.name })
        }
    }

    @Test
    fun otherFailuresAreRetried() {
        val status = AtomicInteger(502)
        val calls = AtomicInteger()
        withCatalog(countingHermes(status, calls)) { catalog ->
            assertEquals(emptyList<SkillInfo>(), catalog.skills())
            status.set(200)
            assertEquals(listOf("todoist"), catalog.skills().map { it.name })
            assertEquals(2, calls.get())
        }
    }
}

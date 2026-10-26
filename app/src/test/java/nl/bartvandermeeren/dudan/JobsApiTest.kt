package nl.bartvandermeeren.dudan

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import nl.bartvandermeeren.dudan.data.DELIVER_NOWHERE
import nl.bartvandermeeren.dudan.data.DeliveryTarget
import nl.bartvandermeeren.dudan.data.HermesApi
import nl.bartvandermeeren.dudan.data.HermesJson
import nl.bartvandermeeren.dudan.data.JobChanges
import nl.bartvandermeeren.dudan.data.JobDraft
import nl.bartvandermeeren.dudan.data.JobList
import nl.bartvandermeeren.dudan.data.ServerCapabilities
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobsApiTest {
    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    // Trimmed from cron/jobs.py create_job and _normalize_job_record (Hermes 0.21).
    private val hermes = JobList.from(
        json(
            """
            {"jobs": [
              {"id": "a1b2c3d4e5f6", "name": "Morning brief", "prompt": "Summarise my agenda", "skills": [],
               "schedule": {"kind": "cron", "expr": "30 7 * * 1-5", "display": "30 7 * * 1-5"},
               "schedule_display": "30 7 * * 1-5", "repeat": {"times": null, "completed": 3},
               "enabled": true, "state": "scheduled", "next_run_at": "2026-10-27T07:30:00+01:00",
               "last_run_at": "2026-10-26T07:30:04.120391+01:00", "last_status": "ok", "deliver": "local"},
              {"id": "b1b2c3d4e5f6", "name": "Dentist", "prompt": "Remind me",
               "schedule": {"kind": "once", "run_at": "2026-10-27T09:00:00+01:00", "display": "once at 2026-10-27 09:00"},
               "enabled": false, "state": "paused", "next_run_at": null},
              {"id": "c1b2c3d4e5f6", "name": "Inbox", "prompt": "Check mail",
               "schedule": {"kind": "interval", "minutes": 30, "display": "every 30m"}, "enabled": 1}
            ]}
            """,
        ),
    )

    // What Clark's dudan channel answers (src/channels/dudan/jobs.ts).
    private val clark = JobList.from(
        json(
            """
            {"jobs": [
              {"id": "heartbeat", "name": "heartbeat",
               "schedule": {"expr": "*/30 7-22 * * *", "timezone": "Europe/Amsterdam", "display": "*/30 7-22 * * * (Europe/Amsterdam)"},
               "schedule_display": "*/30 7-22 * * * (Europe/Amsterdam)", "prompt": "Check in", "enabled": true, "paused": false,
               "state": "scheduled", "next_run_at": "2026-10-26T09:30:00+01:00", "editable": false,
               "edit_note": "Written by hand in heartbeat.yaml; change it on the server.", "name_editable": false,
               "deliver": "telegram", "timezone": "Europe/Amsterdam"},
              {"id": "dentist", "name": "dentist",
               "schedule": {"expr": "at 2026-10-27T09:00", "timezone": "Europe/Amsterdam", "display": "at 2026-10-27T09:00 (Europe/Amsterdam)"},
               "prompt": "Remind me", "enabled": true, "paused": true, "state": "paused", "editable": true,
               "name_editable": false, "deliver": null}
            ],
            "delivery_targets": [{"id": "telegram", "label": "Telegram (Bart)"}, {"id": "mail"}, {"label": "no id"}]}
            """,
        ),
    )

    @Test
    fun readsHermesJobs() {
        val (brief, dentist, inbox) = hermes.jobs
        assertEquals("30 7 * * 1-5", brief.scheduleExpr)
        assertEquals("cron", brief.scheduleKind)
        assertEquals("30 7 * * 1-5", brief.schedule)
        assertEquals("local", brief.deliver)
        assertTrue(brief.editable && brief.nameEditable)
        assertNull(brief.editNote)
        assertEquals("2026-10-27T09:00:00+01:00", dentist.scheduleExpr)
        assertEquals("once", dentist.scheduleKind)
        assertTrue(dentist.paused)
        assertFalse(dentist.enabled)
        assertEquals("every 30m", inbox.scheduleExpr)
        assertEquals("interval", inbox.scheduleKind)
        assertTrue(inbox.enabled)
        // Stock Hermes has no delivery targets: the editor shows no choice and sends no deliver.
        assertEquals(emptyList<DeliveryTarget>(), hermes.deliveryTargets)
    }

    @Test
    fun readsClarksJobs() {
        val (heartbeat, dentist) = clark.jobs
        assertEquals("*/30 7-22 * * *", heartbeat.scheduleExpr)
        assertEquals("cron", heartbeat.scheduleKind)
        assertEquals("Europe/Amsterdam", heartbeat.timezone)
        assertFalse(heartbeat.editable)
        assertFalse(heartbeat.nameEditable)
        assertEquals("Written by hand in heartbeat.yaml; change it on the server.", heartbeat.editNote)
        assertEquals("telegram", heartbeat.deliver)
        assertEquals("at 2026-10-27T09:00", dentist.scheduleExpr)
        assertEquals("once", dentist.scheduleKind)
        assertTrue(dentist.editable)
        assertNull(dentist.deliver)
        assertEquals(listOf(DeliveryTarget("telegram", "Telegram (Bart)"), DeliveryTarget("mail", "mail")), clark.deliveryTargets)
    }

    @Test
    fun readsAPlainStringSchedule() {
        val job = JobList.from(json("""{"jobs": [{"id": "x", "schedule": "0 9 * * *", "prompt": "Hi"}, {"name": "no id"}]}""")).jobs.single()
        assertEquals("0 9 * * *", job.scheduleExpr)
        assertEquals("0 9 * * *", job.schedule)
        assertEquals("cron", job.scheduleKind)
        assertEquals("Hi", job.name)
        assertEquals(JobList(emptyList()), JobList.from(null))
    }

    @Test
    fun managesJobsUnlessAnOldClarkSaysOtherwise() {
        fun caps(vararg endpoints: String) = ServerCapabilities(null, endpoints.toSet())
        // Stock Hermes advertises no job endpoints but has them all.
        assertTrue(caps("skills", "sessions").managesJobs)
        // A Clark from before job management lists jobs and job_action only.
        assertFalse(caps("jobs", "job_action").managesJobs)
        assertTrue(caps("jobs", "job_action", "job", "job_create", "job_update", "job_delete").managesJobs)
        val parsed = ServerCapabilities.from(json("""{"endpoints": {"jobs": {"method": "GET", "path": "/api/jobs"}, "job_create": {"method": "POST", "path": "/api/jobs"}}}"""))
        assertTrue(parsed.managesJobs)
    }

    @Test
    fun patchesSendOnlyWhatChanged() {
        assertEquals("""{"prompt":"New"}""", JobChanges(prompt = "New", timezone = "Europe/Amsterdam").toJson().toString())
        assertEquals(
            """{"schedule":"0 9 * * *","timezone":"Europe/Amsterdam"}""",
            JobChanges(schedule = "0 9 * * *", timezone = "Europe/Amsterdam").toJson().toString(),
        )
        // A timezone on its own would leave Hermes nothing it knows, and it answers that with a 400.
        assertTrue(JobChanges(timezone = "Europe/Amsterdam").isEmpty)
        assertEquals("{}", JobChanges(timezone = "Europe/Amsterdam").toJson().toString())
        assertEquals("""{"deliver":"none"}""", JobChanges(deliver = DELIVER_NOWHERE).toJson().toString())
        assertEquals(
            """{"name":"A","schedule":"*/5 * * * *","prompt":"B"}""",
            JobChanges(name = "A", schedule = "*/5 * * * *", prompt = "B").toJson().toString(),
        )
    }

    @Test
    fun createsChangesAndDeletesTasks() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"job": {"id": "x y", "name": "Brief", "prompt": "The whole prompt", "schedule": {"expr": "0 9 * * *"}}}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"job": {"id": "brief", "name": "brief", "schedule": {"expr": "0 9 * * *"}}}"""))
        server.enqueue(MockResponse().setBody("""{"job": {"id": "brief", "name": "brief", "prompt": "New"}}"""))
        server.enqueue(MockResponse().setBody("""{"ok": true}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error": {"code": "job_read_only", "message": "Written by hand in heartbeat.yaml; change it on the server."}}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error": "Job not found"}"""))
        server.start()
        val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "secret") }

        assertEquals("The whole prompt", api.job("x y").prompt)
        // Path segments are encoded.
        assertEquals("/api/jobs/x%20y", server.takeRequest().path)

        val created = api.createJob(JobDraft("Brief", "0 9 * * *", "Do it", "Europe/Amsterdam", deliver = null))
        assertEquals("brief", created?.id)
        val create = server.takeRequest()
        assertEquals("POST" to "/api/jobs", create.method to create.path)
        assertEquals("""{"name":"Brief","schedule":"0 9 * * *","prompt":"Do it","timezone":"Europe/Amsterdam"}""", create.body.readUtf8())

        api.updateJob("brief", JobChanges(prompt = "New", timezone = "Europe/Amsterdam"))
        val patch = server.takeRequest()
        assertEquals("PATCH" to "/api/jobs/brief", patch.method to patch.path)
        assertEquals("""{"prompt":"New"}""", patch.body.readUtf8())

        api.deleteJob("brief")
        val delete = server.takeRequest()
        assertEquals("DELETE" to "/api/jobs/brief", delete.method to delete.path)

        // Refusals carry a sentence the editor shows as it is, in Clark's envelope and in Hermes'.
        val readOnly = runCatching { api.deleteJob("heartbeat") }.exceptionOrNull() as HermesApi.HermesException
        assertEquals(409, readOnly.status)
        assertEquals("job_read_only", readOnly.code)
        assertEquals("Written by hand in heartbeat.yaml; change it on the server.", readOnly.message)
        server.takeRequest()
        val missing = runCatching { api.job("gone") }.exceptionOrNull() as HermesApi.HermesException
        assertEquals(404 to "Job not found", missing.status to missing.message)
        server.shutdown()
    }

    @Test
    fun onlyAServerWithoutTheEndpointFallsBackToTheListsPrompt() {
        // A Clark from before job management has no such route.
        assertTrue(HermesApi.lacksJobEndpoint(HermesApi.errorFrom(404, """{"error": {"code": "not_found", "message": "No such endpoint."}}""")))
        // Hermes says "Job not found" without a code, but its list carries whole prompts.
        assertTrue(HermesApi.lacksJobEndpoint(HermesApi.errorFrom(404, """{"error": "Job not found"}""")))
        // Clark knows the route but not the task; that, a server error or a dropped connection is a failure to retry,
        // since the list's prompt may be cut short.
        assertFalse(HermesApi.lacksJobEndpoint(HermesApi.errorFrom(404, """{"error": {"code": "job_not_found", "message": "Clark has no job brief."}}""")))
        assertFalse(HermesApi.lacksJobEndpoint(HermesApi.errorFrom(503, "")))
        assertFalse(HermesApi.lacksJobEndpoint(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun theListIsOneRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"jobs": [{"id": "a"}], "delivery_targets": [{"id": "t", "label": "T"}]}"""))
        server.start()
        val api = HermesApi(OkHttpClient()) { HermesApi.ServerConfig(server.url("/").toString(), "secret") }
        val list = api.jobs()
        assertEquals(listOf("a"), list.jobs.map { it.id })
        assertEquals(listOf(DeliveryTarget("t", "T")), list.deliveryTargets)
        assertEquals("/api/jobs?include_disabled=true", server.takeRequest().path)
        assertEquals(1, server.requestCount)
        server.shutdown()
    }
}

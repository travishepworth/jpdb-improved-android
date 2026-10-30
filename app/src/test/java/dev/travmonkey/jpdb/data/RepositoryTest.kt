package dev.travmonkey.jpdb.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/** Mock jpdb server + the repository end to end (Kotlin port of test.js). */
class RepositoryTest {
    private val NOW = 1_800_000_000_000L
    private val KEY = "0123456789abcdef0123456789abcdef"

    private class V(val spelling: String, val reading: String, val meaning: String, val pitch: String, var state: List<String>, var due: Long)

    private lateinit var vocab: MutableMap<String, V>
    private val decks = mapOf(7L to listOf("1,10", "2,20", "3,30"), 8L to listOf("3,30", "4,40", "5,50", "6,60"))
    private var reviewNo = 100
    private val posted = mutableListOf<String>()
    private val apiCalls = mutableListOf<String>()
    private var dropNextPostResponse = false
    private var loggedIn = true
    private var staleR = false
    private var badAuth = false

    private var clock = NOW
    private var online = true
    private val server = MockWebServer()
    private val store = FakeStore()
    private val config = MemoryConfigStore()

    @Before
    fun setUp() {
        val s = NOW / 1000
        vocab = mutableMapOf(
            "1,10" to V("猫", "ねこ", "cat", "HL", listOf("due"), s - 100),
            "2,20" to V("犬", "いぬ", "dog", "LH", listOf("failed"), s - 999),
            "3,30" to V("鳥", "とり", "bird", "LH", listOf("known"), s + 9999),
            "4,40" to V("魚", "さかな", "fish", "LHH", listOf("due"), s - 5000),
            "5,50" to V("馬", "うま", "horse", "LH", listOf("redundant", "due"), 0),
            "6,60" to V("牛", "うし", "cow", "HL", listOf("due"), s + 50), // not yet due
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = synchronized(this@RepositoryTest) { handle(request) }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: JsonElement, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun handle(req: RecordedRequest): MockResponse {
        val url = req.requestUrl!!
        val path = url.encodedPath
        if (path.startsWith("/api/v1/")) {
            val ep = path.removePrefix("/api/v1/")
            val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            apiCalls += ep
            if (req.getHeader("Authorization") != "Bearer $KEY") badAuth = true
            return when (ep) {
                "list-user-decks" -> json(buildJsonObject {
                    put("decks", buildJsonArray { decks.keys.forEach { add(buildJsonArray { add(JsonPrimitive(it)); add(JsonPrimitive("Deck $it")) }) } })
                })
                "deck/list-vocabulary" -> json(buildJsonObject {
                    val id = body["id"]!!.jsonPrimitive.long
                    put("vocabulary", buildJsonArray {
                        decks.getValue(id).forEach { k -> add(JsonArray(k.split(',').map { JsonPrimitive(it.toLong()) })) }
                    })
                })
                "lookup-vocabulary" -> {
                    val list = body["list"]!!.jsonArray
                    if (list.size > 3) return json(buildJsonObject { put("error", "bad_request"); put("error_message", "too big") }, 400)
                    val fields = body["fields"]!!.jsonArray.map { it.jsonPrimitive.content }
                    json(buildJsonObject {
                        put("vocabulary_info", JsonArray(list.map { p ->
                            val (v, s) = p.jsonArray.map { it.jsonPrimitive.long }
                            val x = vocab["$v,$s"] ?: return@map JsonNull
                            val f = mapOf(
                                "vid" to JsonPrimitive(v), "sid" to JsonPrimitive(s),
                                "spelling" to JsonPrimitive(x.spelling), "reading" to JsonPrimitive(x.reading),
                                "meanings" to JsonArray(listOf(JsonPrimitive(x.meaning))),
                                "pitch_accent" to JsonArray(listOf(JsonPrimitive(x.pitch))),
                                "part_of_speech" to JsonArray(listOf(JsonPrimitive("n"))),
                                "frequency_rank" to JsonPrimitive(500),
                                "card_state" to JsonArray(x.state.map { JsonPrimitive(it) }),
                                "due_at" to JsonPrimitive(x.due),
                            )
                            JsonArray(fields.map { f[it] ?: JsonNull })
                        }))
                    })
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        if (path == "/settings") {
            return MockResponse().setBody("<html><h2>API key</h2><p>Your key: <code>$KEY</code></p></html>")
        }
        if (path == "/review" && req.method == "GET") {
            if (!loggedIn) return MockResponse().setBody("""<html><a href="/login">Login</a></html>""")
            return MockResponse().setBody(
                """<html><a href="/settings">s</a><form action="/review#a" method="post"><input type="hidden" name="c" value="${url.queryParameter("c")}">""" +
                    """<input type="hidden" name="r" value="$reviewNo"><input type="submit" name="g" value="f"><input type="submit" name="g" value="p"></form></html>"""
            )
        }
        if (path == "/review" && req.method == "POST") {
            val p = req.body.readUtf8().split('&').associate { kv ->
                val (k, v) = kv.split('=', limit = 2)
                k to URLDecoder.decode(v, "UTF-8")
            }
            if (p["r"]!!.toInt() != reviewNo) { staleR = true; return MockResponse().setResponseCode(400) }
            posted += "${p["c"]}:${p["g"]}"
            reviewNo++
            val (_, v, s) = p.getValue("c").split(',')
            vocab.getValue("$v,$s").apply { state = if (p["g"] == "f") listOf("failed") else listOf("learning"); due = NOW / 1000 + 86400 }
            if (dropNextPostResponse) {
                dropNextPostResponse = false
                return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
            return MockResponse()
        }
        return MockResponse().setResponseCode(404)
    }

    private fun repo() = JpdbRepository(
        api = JpdbApi(OkHttpClient.Builder().retryOnConnectionFailure(false).build(), server.url("/").toString().trimEnd('/'), sleep = { clock += it }),
        store = store,
        settings = config,
        now = { clock },
        sleep = { clock += it },
        isOnline = { online },
    )

    private val JpdbRepository.vids get() = state.value.queue.map { it.vid }

    @Test
    fun endToEnd() = runBlocking {
        config.update { it.copy(chunk = 8) }
        var core = repo()
        core.sync() // no key yet: read it off /settings
        assertEquals(KEY, config.config.value.apiKey)
        assertEquals("due oldest-first, failed last, skip known/redundant/not-yet-due", listOf(4L, 1L, 2L), core.vids)
        assertEquals("魚", core.state.value.content["4,40"]?.spelling)
        assertEquals(listOf("LHH"), core.state.value.content["4,40"]?.pitch)
        assertTrue("chunk halved below the server limit", config.config.value.chunk <= 3)

        // review while offline -> outbox persists
        online = false
        core.grade(core.state.value.queue[0], "pass")
        core.grade(core.state.value.queue[0], "fail")
        assertEquals(FlushResult.Offline, core.flush())
        assertEquals(2, core.state.value.outbox)
        assertEquals(1, core.state.value.queue.size)
        assertTrue(posted.isEmpty())

        // undo the last one (still in outbox)
        assertEquals(1L, core.undo()?.vid)
        assertEquals(1L, core.state.value.queue[0].vid)
        assertEquals(1, core.state.value.outbox)
        assertNull("only one level of undo", core.undo())

        // "app killed": new repository from the same store
        core = repo()
        core.ensureLoaded()
        assertEquals(1, core.state.value.outbox)
        assertEquals(2, core.state.value.queue.size)

        // back online; the first POST's response gets lost after the server applied it
        online = true
        dropNextPostResponse = true
        assertEquals(FlushResult.Retry, core.flush())
        assertEquals(FlushResult.Done, core.flush())
        assertEquals("no double grade after lost response", listOf("vf,4,40:p"), posted)
        assertEquals(0, core.state.value.outbox)

        core.grade(core.state.value.queue[0], "fail"); assertEquals(FlushResult.Done, core.flush())
        core.grade(core.state.value.queue[0], "pass"); assertEquals(FlushResult.Done, core.flush())
        assertEquals(listOf("vf,4,40:p", "vf,1,10:f", "vf,2,20:p"), posted)
        assertEquals(GradeMode.PassFail, config.config.value.mode)

        // resync: everything graded is no longer due; deck list is cached
        apiCalls.clear()
        core.sync()
        assertEquals(0, core.state.value.queue.size)
        assertFalse("deck list cached", "list-user-decks" in apiCalls)

        assertFalse(staleR)
        assertFalse(badAuth)
    }

    @Test
    fun lostResponseDedupeWithoutGuard() = runBlocking {
        config.update { it.copy(apiKey = KEY) }
        val core = repo()
        core.sync()
        config.update { it.copy(apiKey = "") } // no key -> the state guard is skipped; only the review-number check protects us
        core.grade(core.state.value.queue[0], "pass")
        dropNextPostResponse = true
        assertEquals(FlushResult.Retry, core.flush())
        assertEquals(FlushResult.Done, core.flush())
        assertEquals(listOf("vf,4,40:p"), posted)
        assertFalse(staleR)
    }

    @Test
    fun skipsCardsReviewedElsewhere() = runBlocking {
        config.update { it.copy(apiKey = KEY) }
        val core = repo()
        core.sync()
        // card 4 gets reviewed on the website (another device) after we synced
        vocab.getValue("4,40").apply { state = listOf("learning"); due = NOW / 1000 + 86400 }
        online = false
        core.grade(core.state.value.queue[0], "pass")
        online = true
        assertEquals(FlushResult.Done, core.flush())
        assertTrue(posted.isEmpty())
        assertEquals(1, core.state.value.skipped)
    }

    @Test
    fun websiteReviewRemovesLocalCopyAndSurvivesResync() = runBlocking {
        config.update { it.copy(apiKey = KEY) }
        val core = repo()
        core.sync()
        core.markReviewedElsewhere(4, 40)
        assertEquals(listOf(1L, 2L), core.vids)
    }

    @Test
    fun undoRefusedOnceSending() = runBlocking {
        config.update { it.copy(apiKey = KEY) }
        val core = repo()
        core.sync()
        core.grade(core.state.value.queue[0], "pass")
        store.setInFlight(store.loadOutbox()[0].id, true)
        assertNull(core.undo())
        assertEquals(1, store.loadOutbox().size)
    }

    @Test
    fun notLoggedInStopsFlush() = runBlocking {
        config.update { it.copy(apiKey = KEY) }
        val core = repo()
        core.sync()
        core.grade(core.state.value.queue[0], "pass")
        loggedIn = false
        assertEquals(FlushResult.Auth, core.flush())
        assertEquals(1, core.state.value.outbox)
        assertFalse(store.loadOutbox()[0].inFlight)
    }

    @Test
    fun parsers() {
        assertEquals(7, JpdbRepository.parseReviewNo("<input value='7' type=hidden name=r>"))
        try {
            JpdbRepository.parseReviewNo("""<a href="/login">Login</a>"""); throw AssertionError("expected AuthError")
        } catch (_: AuthError) {}
        assertEquals(KEY, JpdbRepository.parseApiKey("""<div>API key</div><input readonly value="$KEY">"""))
        assertNull(JpdbRepository.parseApiKey("<link href=/static/be945648d268.css>"))
        assertEquals(GradeMode.Five, JpdbRepository.detectGradeMode((1..5).joinToString("") { """<button name="g" value="$it">""" }))
        assertEquals(GradeMode.PassFail, JpdbRepository.detectGradeMode("""<input type=submit name=g value=p><input type=submit name=g value=f>"""))
        assertNull(JpdbRepository.detectGradeMode("<input name=r value=3>"))
        assertEquals(listOf("きゃ", "く"), dev.travmonkey.jpdb.ui.moras("きゃく"))
    }
}

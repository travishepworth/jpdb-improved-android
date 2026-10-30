package dev.travmonkey.jpdb.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

enum class Net { Ok, Offline, Retrying }
enum class FlushResult { Done, Retry, Offline, Auth }

data class RepoState(
    val loaded: Boolean = false,
    val queue: List<Card> = emptyList(),
    val content: Map<String, CardContent> = emptyMap(),
    val outbox: Int = 0,
    val canUndo: Boolean = false,
    val syncing: Boolean = false,
    val progress: String = "",
    val net: Net = Net.Ok,
    val error: String? = null,
    val lastSync: Long = 0,
    /** Outbox grades dropped because the card had already been reviewed elsewhere (e.g. on the website). */
    val skipped: Int = 0,
    /** Card key ("vid,sid") -> names of the user's decks containing it. */
    val decks: Map<String, List<String>> = emptyMap(),
)

/**
 * Port of the userscript's makeCore(): fetch the due queue via the official API, review locally,
 * and send grades through the scraped web review form. DOM/Android-free so it can be unit tested.
 *
 * Sync and flush never run at the same time (ioLock): a grade sent mid-sync would otherwise be
 * re-added by a state lookup taken before it landed.
 */
class JpdbRepository(
    private val api: JpdbApi,
    private val store: Store,
    private val settings: ConfigStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val isOnline: () -> Boolean = { true },
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(RepoState())
    val state = _state.asStateFlow()

    private val ioLock = Mutex()
    private val stateLock = Mutex()
    private val loadLock = Mutex()

    @Volatile private var lastGraded: Pair<Card, OutboxItem>? = null
    private val reviewedElsewhere = ConcurrentHashMap<String, Long>()
    private val config get() = settings.config.value

    suspend fun ensureLoaded() {
        if (_state.value.loaded) return
        loadLock.withLock {
            if (_state.value.loaded) return
            store.resetInFlight() // left over from a send interrupted by process death
            val queue = store.loadQueue()
            val outbox = store.loadOutbox()
            val content = store.loadContent(queue.map { it.key })
            val lastSync = store.getKv(KV_LAST_SYNC)?.toLongOrNull() ?: 0
            val decks = loadDeckCache()?.let(::deckNamesByCard) ?: emptyMap()
            _state.update { it.copy(loaded = true, queue = queue, content = content, outbox = outbox.size, lastSync = lastSync, decks = decks) }
        }
    }

    // ------------------------------------------------------------------ sync ---

    suspend fun sync(forceDecks: Boolean = false) {
        ensureLoaded()
        ioLock.withLock {
            val started = now()
            _state.update { it.copy(syncing = true, progress = "syncing…", error = null) }
            try {
                val key = ensureApiKey()
                val pairs = allPairs(key, forceDecks)
                val t = now()
                val due = lookup(key, pairs, STATE_FIELDS).mapNotNull { v ->
                    if (v == null) return@mapNotNull null
                    val st = v.getOrNull(2).stringList()
                    if (!isReviewable(st)) return@mapNotNull null
                    val dueAt = toMs(v.getOrNull(3).long())
                    if (dueAt > t) null else Card(v[0].long()!!, v[1].long()!!, st[0], dueAt)
                }.sortedWith(compareBy<Card>({ it.state == "failed" }, { it.dueAt })) // failed after due, oldest first

                val have = store.loadContent(due.map { it.key }).toMutableMap()
                val missing = due.filter { it.key !in have }
                if (missing.isNotEmpty()) {
                    progress("downloading ${missing.size} cards")
                    val fetched = HashMap<String, CardContent>()
                    for (v in lookup(key, missing.map { it.vid to it.sid }, CONTENT_FIELDS)) {
                        if (v == null) continue
                        fetched["${v[0].long()},${v[1].long()}"] = CardContent(
                            spelling = v.getOrNull(2).str() ?: continue,
                            reading = v.getOrNull(3).str(),
                            meanings = v.getOrNull(4).stringList(),
                            pitch = v.getOrNull(5).stringList(),
                            pos = v.getOrNull(6).stringList(),
                            freq = v.getOrNull(7).long()?.toInt(),
                        )
                    }
                    store.saveContent(fetched)
                    have += fetched
                }

                stateLock.withLock {
                    val pending = store.loadOutbox().mapTo(HashSet()) { it.key }
                    val queue = due.filter {
                        it.key in have && it.key !in pending && (reviewedElsewhere[it.key] ?: 0) < started
                    }
                    store.saveQueue(queue)
                    store.putKv(KV_LAST_SYNC, t.toString())
                    _state.update { it.copy(queue = queue, content = it.content + have, lastSync = t, net = Net.Ok) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
                throw e
            } finally {
                _state.update { it.copy(syncing = false, progress = "") }
            }
        }
    }

    /** Reads the API key off jpdb.io/settings using the logged-in cookie session. */
    suspend fun fetchApiKey(): String? {
        val page = api.getPage("/settings")
        if (page.isLogin) throw AuthError(LOGIN_MSG)
        val key = parseApiKey(page.html) ?: return null
        settings.update { it.copy(apiKey = key) }
        return key
    }

    private suspend fun ensureApiKey(): String {
        config.apiKey.takeIf { it.isNotBlank() }?.let { return it }
        progress("reading API key from jpdb settings")
        return fetchApiKey() ?: throw ApiError(
            "missing_key",
            "Couldn't find your API key. Log in to jpdb in the app, or paste it from the bottom of jpdb.io/settings.",
        )
    }

    @Serializable
    private data class Deck(val id: Long, val name: String, val pairs: List<List<Long>>)

    @Serializable
    private data class DeckCache(val t: Long, val decks: List<Deck>)

    private suspend fun loadDeckCache() =
        store.getKv(KV_DECKS)?.let { runCatching { json.decodeFromString<DeckCache>(it) }.getOrNull() }

    private fun deckNamesByCard(cache: DeckCache): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (deck in cache.decks) for (p in deck.pairs) out.getOrPut("${p[0]},${p[1]}") { mutableListOf() } += deck.name
        return out
    }

    /** Every (vid, sid) in the user's decks, deduplicated. Also refreshes the card -> deck names map. */
    private suspend fun allPairs(key: String, force: Boolean): List<Pair<Long, Long>> {
        val cached = loadDeckCache()
        val cache = if (!force && cached != null && now() - cached.t < config.deckTtlHours * 3_600_000L) cached
        else fetchDecks(key).also { store.putKv(KV_DECKS, json.encodeToString(it)) }
        val names = deckNamesByCard(cache)
        _state.update { it.copy(decks = names) }
        return names.keys.map { k -> k.split(',').let { it[0].toLong() to it[1].toLong() } }
    }

    private suspend fun fetchDecks(key: String): DeckCache {
        progress("listing decks")
        val decks = api.call("list-user-decks", key, buildJsonObject {
            put("fields", JsonArray(listOf(JsonPrimitive("id"), JsonPrimitive("name"))))
        })["decks"] as? JsonArray ?: JsonArray(emptyList())
        val out = ArrayList<Deck>()
        for (deck in decks) {
            val (id, name) = when (deck) {
                is JsonArray -> deck.getOrNull(0).long() to deck.getOrNull(1).str()
                is JsonObject -> deck["id"].long() to deck["name"].str()
                else -> null to null
            }
            if (id == null) continue
            progress("reading deck “${name ?: id}”")
            val d = api.call("deck/list-vocabulary", key, buildJsonObject { put("id", id); put("fetch_occurences", false) })
            val pairs = (d["vocabulary"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { p ->
                val a = p as? JsonArray ?: return@mapNotNull null
                listOf(a.getOrNull(0).long() ?: return@mapNotNull null, a.getOrNull(1).long() ?: return@mapNotNull null)
            }
            out += Deck(id, name ?: "Deck $id", pairs)
            sleep(API_GAP_MS)
        }
        return DeckCache(now(), out)
    }

    private suspend fun lookup(key: String, pairs: List<Pair<Long, Long>>, fields: List<String>): List<JsonArray?> {
        val out = ArrayList<JsonArray?>(pairs.size)
        var size = config.chunk
        var i = 0
        while (i < pairs.size) {
            val slice = pairs.subList(i, minOf(i + size, pairs.size))
            try {
                val d = api.call("lookup-vocabulary", key, buildJsonObject {
                    put("list", JsonArray(slice.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }))
                    put("fields", JsonArray(fields.map { JsonPrimitive(it) }))
                })
                val info = d["vocabulary_info"] as? JsonArray ?: throw ApiError("bad_response", "lookup-vocabulary returned no vocabulary_info")
                info.mapTo(out) { it as? JsonArray }
                i += slice.size
                progress("checked ${minOf(i, pairs.size)}/${pairs.size} cards")
                sleep(API_GAP_MS)
            } catch (e: ApiError) {
                if (e.code == "bad_request" && size > 1) { // probably too big
                    size /= 2
                    settings.update { it.copy(chunk = size) }
                    continue
                }
                throw e
            }
        }
        return out
    }

    // --------------------------------------------------------------- grading ---

    suspend fun grade(card: Card, grade: String) {
        require(grade in GRADES) { "bad grade $grade" }
        ensureLoaded()
        stateLock.withLock {
            val item = store.gradeTx(card, OutboxItem(vid = card.vid, sid = card.sid, grade = grade, t = now()))
            lastGraded = card to item
            _state.update { s ->
                s.copy(queue = s.queue.filter { it.key != card.key }, outbox = s.outbox + 1, canUndo = true, error = null)
            }
        }
    }

    /** Puts the last graded card back at the front of the queue, unless its grade is already being sent. */
    suspend fun undo(): Card? = stateLock.withLock {
        val (card, item) = lastGraded ?: return null
        lastGraded = null
        _state.update { it.copy(canUndo = false) }
        if (!store.undoTx(item, card)) return null
        _state.update { s ->
            s.copy(queue = listOf(card) + s.queue.filter { it.key != card.key }, outbox = s.outbox - 1)
        }
        card
    }

    /** The card was just reviewed on the website itself; drop our local copy. */
    suspend fun markReviewedElsewhere(vid: Long, sid: Long) {
        val key = "$vid,$sid"
        reviewedElsewhere[key] = now()
        ensureLoaded()
        stateLock.withLock {
            store.removeFromQueue(key)
            _state.update { s -> s.copy(queue = s.queue.filter { it.key != key }) }
        }
    }

    // ----------------------------------------------------------------- flush ---

    suspend fun flush(): FlushResult {
        ensureLoaded()
        return ioLock.withLock { flushLocked() }
    }

    private suspend fun flushLocked(): FlushResult {
        var guarded = false
        while (true) {
            val items = store.loadOutbox()
            if (items.isEmpty()) {
                _state.update { it.copy(outbox = 0, net = Net.Ok) }
                return FlushResult.Done
            }
            if (!isOnline()) {
                _state.update { it.copy(net = Net.Offline) }
                return FlushResult.Offline
            }
            val item = items.first()
            try {
                if (!guarded) {
                    guard(items); guarded = true; continue
                }
                // keep the very latest grade for a few seconds so undo stays possible
                val lg = lastGraded
                if (lg != null && lg.second.id == item.id) {
                    val age = now() - item.t
                    if (age < UNDO_MS) { sleep(UNDO_MS - age); continue }
                }
                val claimed = stateLock.withLock {
                    store.setInFlight(item.id, true).also { ok ->
                        if (ok && lastGraded?.second?.id == item.id) { lastGraded = null; _state.update { s -> s.copy(canUndo = false) } }
                    }
                }
                if (!claimed) continue // undone meanwhile
                try {
                    sendOne(item)
                } catch (e: Throwable) {
                    withContext(NonCancellable) { stateLock.withLock { store.setInFlight(item.id, false) } }
                    throw e
                }
                stateLock.withLock {
                    store.deleteOutbox(item.id)
                    _state.update { s -> s.copy(outbox = (s.outbox - 1).coerceAtLeast(0), net = Net.Ok, error = null) }
                }
                sleep(SCRAPE_GAP_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthError) {
                _state.update { s -> s.copy(error = e.message) }
                return FlushResult.Auth
            } catch (e: Exception) {
                store.loadOutbox().find { o -> o.id == item.id }?.let { o -> store.updateOutbox(o.copy(tries = o.tries + 1)) }
                _state.update { s -> s.copy(net = Net.Retrying) }
                log("flush failed, will retry: $e")
                return FlushResult.Retry
            }
        }
    }

    /**
     * Drop outbox grades whose card is no longer due as of when it was graded: it was reviewed
     * on the website meanwhile, or an earlier POST of ours landed but its response was lost.
     */
    private suspend fun guard(items: List<OutboxItem>) {
        val key = config.apiKey.takeIf { it.isNotBlank() } ?: return
        val states = lookup(key, items.map { it.vid to it.sid }, STATE_FIELDS)
        var dropped = 0
        stateLock.withLock {
            for ((item, v) in items.zip(states)) {
                if (v == null) continue
                val st = v.getOrNull(2).stringList()
                val dueAt = toMs(v.getOrNull(3).long())
                if (isReviewable(st) && dueAt <= item.t) continue
                log("skipping ${item.key}: already reviewed (state=$st, due=$dueAt)")
                store.deleteOutbox(item.id)
                if (lastGraded?.second?.id == item.id) lastGraded = null
                dropped++
            }
            if (dropped > 0) _state.update { s ->
                s.copy(outbox = (s.outbox - dropped).coerceAtLeast(0), skipped = s.skipped + dropped, canUndo = lastGraded != null)
            }
        }
    }

    private suspend fun sendOne(item: OutboxItem) {
        val page = api.getReviewPage(item.vid, item.sid)
        if (page.isLogin) throw AuthError(LOGIN_MSG)
        val r = parseReviewNo(page.html)
        if (!config.modeChosen) detectGradeMode(page.html)?.let { m -> if (m != config.mode) settings.update { it.copy(mode = m) } }
        // If a previous POST went through but we lost the response, the review number moved on: don't double-grade.
        if (item.r != null && r > item.r) return
        store.updateOutbox(item.copy(r = r, inFlight = true))
        sleep(SCRAPE_GAP_MS)
        api.postReview(item.vid, item.sid, r, GRADES.getValue(item.grade))
    }

    private fun progress(msg: String) = _state.update { it.copy(progress = msg) }

    companion object {
        val GRADES = mapOf(
            "nothing" to "1", "something" to "2", "hard" to "3", "okay" to "4", "easy" to "5",
            "pass" to "p", "fail" to "f",
        )
        val STATE_FIELDS = listOf("vid", "sid", "card_state", "due_at")
        val CONTENT_FIELDS = listOf("vid", "sid", "spelling", "reading", "meanings", "pitch_accent", "part_of_speech", "frequency_rank")
        const val UNDO_MS = 4000L
        const val API_GAP_MS = 200L
        const val SCRAPE_GAP_MS = 1100L
        private const val KV_LAST_SYNC = "lastSync"
        private const val KV_DECKS = "decks"
        private const val LOGIN_MSG = "Not logged in to jpdb.io — log in on the website in this app, then uploads resume."
        private val json = Json { ignoreUnknownKeys = true }

        /** card_state is e.g. ["due"], ["failed"], ["redundant","due"], ["locked","due"]. */
        fun isReviewable(st: List<String>) = st.size == 1 && (st[0] == "due" || st[0] == "failed")

        fun toMs(t: Long?) = when {
            t == null -> 0L
            t < 1_000_000_000_000L -> t * 1000
            else -> t
        }

        fun parseReviewNo(html: String): Int {
            val hasR = Regex("""name="?r"?""").containsMatchIn(html)
            if (html.contains("href=\"/login\"") && !hasR) throw AuthError(LOGIN_MSG)
            for (tag in Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
                if (Regex("""\bname\s*=\s*["']?r["'\s>]""", RegexOption.IGNORE_CASE).containsMatchIn(tag.value)) {
                    Regex("""\bvalue\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE).find(tag.value)?.let { return it.groupValues[1].toInt() }
                }
            }
            throw IllegalStateException("Could not find review number on the review page (jpdb changed its HTML?)")
        }

        /** The API key is shown near the bottom of /settings; take the first long hex token after "API key". */
        fun parseApiKey(html: String): String? {
            val at = Regex("API key", RegexOption.IGNORE_CASE).find(html)?.range?.first ?: return null
            val tail = html.substring(at, minOf(html.length, at + 4000))
            return Regex("""(?<![0-9a-fA-F])[0-9a-fA-F]{32,64}(?![0-9a-fA-F])""").find(tail)?.value
        }

        /** Best effort: if the page carries grade buttons, tell which grading scheme the account uses. */
        fun detectGradeMode(html: String): GradeMode? {
            val values = Regex("""<(?:input|button)\b[^>]*\bname\s*=\s*["']?g(?=["'\s/>])[^>]*>""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { Regex("""\bvalue\s*=\s*["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE).find(it.value)?.groupValues?.get(1) }
                .toSet()
            return when {
                "p" in values && "f" in values -> GradeMode.PassFail
                listOf("1", "2", "3", "4", "5").count { it in values } >= 4 -> GradeMode.Five
                else -> null
            }
        }

        private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.stringList(): List<String> = (this as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
    }
}

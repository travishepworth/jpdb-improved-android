package dev.travmonkey.jpdb.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** A due vocab card (JP→EN, `vf`) in review order. */
data class Card(val vid: Long, val sid: Long, val state: String, val dueAt: Long) {
    val key get() = "$vid,$sid"
}

@Serializable
data class CardContent(
    val spelling: String,
    val reading: String? = null,
    val meanings: List<String> = emptyList(),
    val pitch: List<String> = emptyList(),
    val pos: List<String> = emptyList(),
    val freq: Int? = null,
)

/** A grade waiting to be sent. `r` is the review number seen right before the POST (lost-response dedupe). */
data class OutboxItem(
    val id: Long = 0,
    val vid: Long,
    val sid: Long,
    val grade: String,
    val t: Long,
    val r: Int? = null,
    val tries: Int = 0,
    val inFlight: Boolean = false,
) {
    val key get() = "$vid,$sid"
}

/** One of the user's decks and the (vid, sid) pairs in it. */
data class Deck(val id: Long, val name: String, val pairs: List<Pair<Long, Long>>)

/** The user's deck list as of [t] (ms). */
data class DeckCache(val t: Long, val decks: List<Deck>)

enum class GradeMode { PassFail, Five }

@Serializable
data class Config(
    val apiKey: String = "",
    val mode: GradeMode = GradeMode.Five,
    /** Set once the user picks a mode in Settings; auto-detection then leaves it alone. */
    val modeChosen: Boolean = false,
    val deckTtlHours: Int = 12,
    val slowMs: Long = 3000,
    val syncHours: Int = 2,
    val chunk: Int = 1000,
)

interface ConfigStore {
    val config: StateFlow<Config>
    fun update(f: (Config) -> Config)
}

class MemoryConfigStore(initial: Config = Config()) : ConfigStore {
    private val flow = MutableStateFlow(initial)
    override val config = flow.asStateFlow()
    override fun update(f: (Config) -> Config) = flow.update(f)
}

/** Persistence for the queue, outbox and card content. Multi-step methods must be atomic. */
interface Store {
    suspend fun loadQueue(): List<Card>
    suspend fun saveQueue(cards: List<Card>)
    suspend fun removeFromQueue(key: String)

    suspend fun loadOutbox(): List<OutboxItem>
    suspend fun updateOutbox(item: OutboxItem)
    suspend fun deleteOutbox(id: Long)
    /** Returns false if the item no longer exists (e.g. it was undone). */
    suspend fun setInFlight(id: Long, inFlight: Boolean): Boolean
    suspend fun resetInFlight()

    /** Removes the card from the queue and appends the item to the outbox; returns the item with its id. */
    suspend fun gradeTx(card: Card, item: OutboxItem): OutboxItem
    /** If the item exists and isn't being sent, deletes it and puts the card back at the front of the queue. */
    suspend fun undoTx(item: OutboxItem, card: Card): Boolean

    suspend fun loadContent(keys: Collection<String>): Map<String, CardContent>
    suspend fun saveContent(content: Map<String, CardContent>)

    /** Deck membership is stored row-per-card: one blob of it can outgrow SQLite's ~2 MB cursor window. */
    suspend fun loadDecks(): DeckCache?
    suspend fun saveDecks(cache: DeckCache)

    suspend fun getKv(key: String): String?
    suspend fun putKv(key: String, value: String)
}

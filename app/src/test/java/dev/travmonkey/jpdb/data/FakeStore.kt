package dev.travmonkey.jpdb.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeStore : Store {
    private val lock = Mutex()
    private var queue = listOf<Card>()
    private val outbox = mutableListOf<OutboxItem>()
    private val content = HashMap<String, CardContent>()
    private val kv = HashMap<String, String>()
    private var decks: DeckCache? = null
    private var nextId = 1L

    override suspend fun loadQueue() = lock.withLock { queue }
    override suspend fun saveQueue(cards: List<Card>) { lock.withLock { queue = cards.toList() } }
    override suspend fun removeFromQueue(key: String) { lock.withLock { queue = queue.filter { it.key != key } } }
    override suspend fun loadOutbox() = lock.withLock { outbox.toList() }
    override suspend fun updateOutbox(item: OutboxItem) {
        lock.withLock {
            val i = outbox.indexOfFirst { it.id == item.id }
            if (i >= 0) outbox[i] = item
        }
    }
    override suspend fun deleteOutbox(id: Long) { lock.withLock { outbox.removeAll { it.id == id } } }
    override suspend fun setInFlight(id: Long, inFlight: Boolean) = lock.withLock {
        val i = outbox.indexOfFirst { it.id == id }
        if (i >= 0) outbox[i] = outbox[i].copy(inFlight = inFlight)
        i >= 0
    }
    override suspend fun resetInFlight() { lock.withLock { outbox.replaceAll { it.copy(inFlight = false) } } }
    override suspend fun gradeTx(card: Card, item: OutboxItem) = lock.withLock {
        queue = queue.filter { it.key != card.key }
        item.copy(id = nextId++).also { outbox += it }
    }
    override suspend fun undoTx(item: OutboxItem, card: Card) = lock.withLock {
        val existing = outbox.find { it.id == item.id }
        if (existing == null || existing.inFlight) return@withLock false
        outbox.remove(existing)
        queue = listOf(card) + queue
        true
    }
    override suspend fun loadContent(keys: Collection<String>) = lock.withLock { keys.mapNotNull { k -> content[k]?.let { k to it } }.toMap() }
    override suspend fun saveContent(content: Map<String, CardContent>) { lock.withLock { this.content.putAll(content) } }
    override suspend fun loadDecks() = lock.withLock { decks }
    override suspend fun saveDecks(cache: DeckCache) { lock.withLock { decks = cache } }
    override suspend fun getKv(key: String) = lock.withLock { kv[key] }
    override suspend fun putKv(key: String, value: String) { lock.withLock { kv[key] = value } }
}

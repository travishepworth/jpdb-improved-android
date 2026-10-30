package dev.travmonkey.jpdb.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Entity(tableName = "queue")
data class QueueRow(@PrimaryKey val cardKey: String, val vid: Long, val sid: Long, val state: String, val dueAt: Long, val pos: Int)

@Entity(tableName = "outbox")
data class OutboxRow(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val vid: Long,
    val sid: Long,
    val grade: String,
    val t: Long,
    val r: Int?,
    val tries: Int,
    val inFlight: Boolean,
)

@Entity(tableName = "content")
data class ContentRow(@PrimaryKey val cardKey: String, val json: String)

@Entity(tableName = "kv")
data class KvRow(@PrimaryKey val k: String, val v: String)

@Dao
interface JpdbDao {
    @Query("SELECT * FROM queue ORDER BY pos") suspend fun queue(): List<QueueRow>
    @Query("DELETE FROM queue") suspend fun clearQueue()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertQueue(rows: List<QueueRow>)
    @Query("DELETE FROM queue WHERE cardKey = :key") suspend fun deleteQueue(key: String)
    @Query("SELECT MIN(pos) FROM queue") suspend fun minPos(): Int?

    @Query("SELECT * FROM outbox ORDER BY id") suspend fun outbox(): List<OutboxRow>
    @Insert suspend fun insertOutbox(row: OutboxRow): Long
    @Update suspend fun updateOutbox(row: OutboxRow)
    @Query("DELETE FROM outbox WHERE id = :id") suspend fun deleteOutbox(id: Long)
    @Query("DELETE FROM outbox WHERE id = :id AND inFlight = 0") suspend fun deleteIdleOutbox(id: Long): Int
    @Query("UPDATE outbox SET inFlight = :inFlight WHERE id = :id") suspend fun setInFlight(id: Long, inFlight: Boolean): Int
    @Query("UPDATE outbox SET inFlight = 0") suspend fun resetInFlight()

    @Query("SELECT * FROM content WHERE cardKey IN (:keys)") suspend fun content(keys: List<String>): List<ContentRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertContent(rows: List<ContentRow>)

    @Query("SELECT v FROM kv WHERE k = :k") suspend fun kv(k: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putKv(row: KvRow)
}

@Database(entities = [QueueRow::class, OutboxRow::class, ContentRow::class, KvRow::class], version = 1, exportSchema = false)
abstract class JpdbDb : RoomDatabase() {
    abstract fun dao(): JpdbDao

    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, JpdbDb::class.java, "jpdb.db").build()
    }
}

class RoomStore(private val db: JpdbDb) : Store {
    private val dao = db.dao()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun loadQueue() = dao.queue().map { Card(it.vid, it.sid, it.state, it.dueAt) }

    override suspend fun saveQueue(cards: List<Card>) = db.withTransaction {
        dao.clearQueue()
        dao.insertQueue(cards.mapIndexed { i, c -> c.toRow(i) })
    }

    override suspend fun removeFromQueue(key: String) = dao.deleteQueue(key)

    override suspend fun loadOutbox() = dao.outbox().map { OutboxItem(it.id, it.vid, it.sid, it.grade, it.t, it.r, it.tries, it.inFlight) }
    override suspend fun updateOutbox(item: OutboxItem) = dao.updateOutbox(item.toRow())
    override suspend fun deleteOutbox(id: Long) = dao.deleteOutbox(id)
    override suspend fun setInFlight(id: Long, inFlight: Boolean) = dao.setInFlight(id, inFlight) > 0
    override suspend fun resetInFlight() = dao.resetInFlight()

    override suspend fun gradeTx(card: Card, item: OutboxItem) = db.withTransaction {
        dao.deleteQueue(card.key)
        item.copy(id = dao.insertOutbox(item.toRow().copy(id = 0)))
    }

    override suspend fun undoTx(item: OutboxItem, card: Card) = db.withTransaction {
        if (dao.deleteIdleOutbox(item.id) == 0) return@withTransaction false
        dao.insertQueue(listOf(card.toRow((dao.minPos() ?: 0) - 1)))
        true
    }

    // SQLite caps bound parameters, so look up in chunks.
    override suspend fun loadContent(keys: Collection<String>) = keys.chunked(500).flatMap { dao.content(it) }
        .associate { it.cardKey to json.decodeFromString<CardContent>(it.json) }

    override suspend fun saveContent(content: Map<String, CardContent>) =
        dao.insertContent(content.map { (k, v) -> ContentRow(k, json.encodeToString(v)) })

    override suspend fun getKv(key: String) = dao.kv(key)
    override suspend fun putKv(key: String, value: String) = dao.putKv(KvRow(key, value))

    private fun Card.toRow(pos: Int) = QueueRow(key, vid, sid, state, dueAt, pos)
    private fun OutboxItem.toRow() = OutboxRow(id, vid, sid, grade, t, r, tries, inFlight)
}

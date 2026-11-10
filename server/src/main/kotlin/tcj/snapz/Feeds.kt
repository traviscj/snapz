package tcj.snapz

import com.google.common.util.concurrent.AbstractIdleService
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.NotFoundException
import misk.logging.getLogger

/** feed name -> backing table. Every feed table carries (id, feed_sync_id, shard). */
val FEEDS = linkedMapOf("photos" to "photos", "collections" to "collections", "records" to "records")

fun feedTable(feed: String): String = FEEDS[feed] ?: throw NotFoundException("no feed '$feed' (have: ${FEEDS.keys.joinToString()})")

/**
 * feedz-style publisher: the tables are the feed. Every mutation NULLs a row's feed_sync_id; this
 * assigns fresh, monotonically increasing ids from `sequences`, one transaction per batch.
 * Republished rows get a new, higher id, so a consumer paging `feed_sync_id > cursor` sees every
 * row's latest state at least once.
 *
 * Ids are allocated and committed under the sequence row lock, so they become visible in order:
 * a consumer can never observe id N+1 and later have N appear behind its cursor. That only holds
 * with a single publisher per database, which is what this is.
 */
@Singleton
class FeedPublisher @Inject constructor(
  private val db: Db,
  private val config: SnapzConfig,
) : AbstractIdleService() {
  private lateinit var executor: ScheduledExecutorService

  override fun startUp() {
    val period = config.snapz.feed_publish_interval_ms
    executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "feed-publisher").apply { isDaemon = true } }
    executor.scheduleWithFixedDelay({
      try {
        publishAll()
      } catch (e: Exception) {
        logger.error(e) { "feed publish failed; will retry" }
      }
    }, period, period, TimeUnit.MILLISECONDS)
  }

  override fun shutDown() {
    executor.shutdownNow()
  }

  /** Drains every feed; returns rows published. */
  @Synchronized
  fun publishAll(): Int = FEEDS.keys.sumOf { feed ->
    var total = 0
    do {
      val n = publishBatch(feed)
      total += n
    } while (n == BATCH)
    total
  }

  private fun publishBatch(feed: String): Int = db.tx { c ->
    val table = feedTable(feed)
    val pending = c.select("SELECT id FROM $table WHERE feed_sync_id IS NULL AND shard >= 0 ORDER BY id LIMIT $BATCH") { it.getLong(1) }
    if (pending.isEmpty()) return@tx 0
    c.exec("INSERT IGNORE INTO sequences (name, value) VALUES (?, 0)", listOf(feed))
    var seq = c.select("SELECT value FROM sequences WHERE name = ? FOR UPDATE", listOf(feed)) { it.getLong(1) }.first()
    c.prepareStatement("UPDATE $table SET feed_sync_id = ? WHERE id = ? AND feed_sync_id IS NULL").use { ps ->
      for (id in pending) {
        ps.setLong(1, ++seq)
        ps.setLong(2, id)
        ps.addBatch()
      }
      ps.executeBatch()
    }
    c.exec("UPDATE sequences SET value = ? WHERE name = ?", listOf(seq, feed))
    pending.size
  }

  companion object {
    private const val BATCH = 500
    private val logger = getLogger<FeedPublisher>()
  }
}

data class FeedPage(val feed: String, val entries: List<Any>, val cursor: Long)

data class CursorDto(
  val consumer: String,
  val feed: String,
  val shard: Int,
  val shard_count: Int,
  val cursor: Long,
  val lag: Long?,
  val updated_at: String,
)

data class FeedStatusDto(val feed: String, val sequence: Long, val rows: Long, val pending: Long, val unpublished: Long)

data class FeedsStatus(val feeds: List<FeedStatusDto>, val cursors: List<CursorDto>)

@Singleton
class FeedService @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val photos: PhotoStore,
  private val collections: CollectionStore,
  private val records: RecordStore,
) {
  /**
   * One page of entries with feed_sync_id > [after], ascending. `cursor` in the reply is the
   * position to pass as the next `after` (unchanged when the page is empty).
   */
  fun fetch(feed: String, after: Long, limit: Int, shard: Int?, shardCount: Int?, collection: String?, ns: String?): FeedPage {
    val n = limit.coerceIn(1, 1000)
    val (entries, last) = when (feed) {
      "photos" -> photos.feed(after, n, shard, shardCount, collection).let { it to it.lastOrNull()?.feed_sync_id }
      "collections" -> collections.feed(after, n, shard, shardCount).let { it to it.lastOrNull()?.feed_sync_id }
      "records" -> records.feed(after, n, shard, shardCount, ns).let { it to it.lastOrNull()?.feed_sync_id }
      else -> throw NotFoundException("no feed '$feed' (have: ${FEEDS.keys.joinToString()})")
    }
    return FeedPage(feed, entries, last ?: after)
  }

  fun cursor(consumer: String, feed: String, shard: Int): Long {
    feedTable(feed)
    return db.tx { c ->
      c.select("SELECT position FROM feed_cursors WHERE consumer = ? AND feed = ? AND shard = ?", listOf(consumer, feed, shard)) { it.getLong(1) }.firstOrNull() ?: 0L
    }
  }

  fun setCursor(consumer: String, feed: String, shard: Int, shardCount: Int, position: Long) {
    feedTable(feed)
    db.tx { c ->
      c.exec(
        """
        INSERT INTO feed_cursors (consumer, feed, shard, shard_count, position, updated_at_ms) VALUES (?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE position = VALUES(position), shard_count = VALUES(shard_count), updated_at_ms = VALUES(updated_at_ms)
        """.trimIndent(),
        listOf(consumer, feed, shard, shardCount, position, clock.millis()),
      )
    }
  }

  fun deleteCursor(consumer: String, feed: String): Int = db.tx { c ->
    c.exec("DELETE FROM feed_cursors WHERE consumer = ? AND feed = ?", listOf(consumer, feed))
  }

  fun status(): FeedsStatus = db.tx { c ->
    val seqs = c.select("SELECT name, value FROM sequences") { it.getString(1) to it.getLong(2) }.toMap()
    val feeds = FEEDS.map { (feed, table) ->
      c.select("SELECT COUNT(*), COALESCE(SUM(feed_sync_id IS NULL AND shard >= 0), 0), COALESCE(SUM(shard < 0), 0) FROM $table") {
        FeedStatusDto(feed, seqs[feed] ?: 0, it.getLong(1), it.getLong(2), it.getLong(3))
      }.first()
    }
    val cursors = c.select("SELECT * FROM feed_cursors ORDER BY feed, consumer, shard") { rs ->
      val feed = rs.getString("feed")
      val pos = rs.getLong("position")
      CursorDto(
        consumer = rs.getString("consumer"),
        feed = feed,
        shard = rs.getInt("shard"),
        shard_count = rs.getInt("shard_count"),
        cursor = pos,
        lag = seqs[feed]?.let { it - pos },
        updated_at = iso(rs.getLong("updated_at_ms")),
      )
    }
    FeedsStatus(feeds, cursors)
  }
}

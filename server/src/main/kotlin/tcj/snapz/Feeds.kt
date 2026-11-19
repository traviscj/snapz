package tcj.snapz

import com.google.inject.multibindings.ProvidesIntoSet
import misk.inject.KAbstractModule
import tcj.feedz.FeedQuery
import tcj.feedz.FeedSource
import tcj.feedz.feedSource

/**
 * Filters a feed's fetch understands, carried as [FeedQuery.params]. [FeedActions] builds them from
 * the caller's vault context and the named query parameters; nothing else from the request gets in.
 */
object FeedParams {
  /** Only this vault's entries. Absent = every vault. */
  const val VAULT_ID = "vault_id"
  /** The vault a `collection` slug is resolved in. */
  const val CURRENT_VAULT_ID = "current_vault_id"
  const val COLLECTION = "collection"
  const val NS = "ns"
  const val TRANSFORM = "transform"
}

private val FeedQuery.vaultId get() = params[FeedParams.VAULT_ID]?.toLong()

/** Rows with a negative shard are held back: never published, never fetched. */
private const val PUBLISHABLE = "shard >= 0"

/**
 * The feeds, on misk-feedz: each is a table whose rows carry (id, feed_sync_id, shard) and whose
 * mutations NULL feed_sync_id. The library publishes (one publisher per database), pages and keeps
 * consumer cursors; these say which tables, and how a page of each is read as its DTO.
 */
class SnapzFeedsModule : KAbstractModule() {
  override fun configure() {}

  @ProvidesIntoSet fun photos(store: PhotoStore): FeedSource =
    feedSource<PhotoDto>("photos", "photos", PUBLISHABLE, syncId = { it.feed_sync_id }) { q ->
      store.feed(q.after, q.limit, q.shard, q.shardCount, q.vaultId, q.params[FeedParams.CURRENT_VAULT_ID]?.toLong() ?: -1, q.params[FeedParams.COLLECTION])
    }

  @ProvidesIntoSet fun collections(store: CollectionStore): FeedSource =
    feedSource<CollectionDto>("collections", "collections", PUBLISHABLE, syncId = { it.feed_sync_id }) { q -> store.feed(q.after, q.limit, q.shard, q.shardCount, q.vaultId) }

  @ProvidesIntoSet fun records(store: RecordStore): FeedSource =
    feedSource<RecordDto>("records", "records", PUBLISHABLE, syncId = { it.feed_sync_id }) { q -> store.feed(q.after, q.limit, q.shard, q.shardCount, q.vaultId, q.params[FeedParams.NS]) }

  @ProvidesIntoSet fun vaults(store: VaultStore): FeedSource =
    feedSource<VaultDto>("vaults", "vaults", PUBLISHABLE, syncId = { it.feed_sync_id }) { q -> store.feed(q.after, q.limit, q.shard, q.shardCount, q.vaultId) }

  @ProvidesIntoSet fun intents(store: TransformStore): FeedSource =
    feedSource<IntentDto>("intents", "transform_intents", PUBLISHABLE, syncId = { it.feed_sync_id }) { q ->
      store.intentFeed(q.after, q.limit, q.shard, q.shardCount, q.vaultId, q.params[FeedParams.TRANSFORM])
    }

  @ProvidesIntoSet fun results(store: TransformStore): FeedSource =
    feedSource<ResultDto>("results", "transform_results", PUBLISHABLE, syncId = { it.feed_sync_id }) { q ->
      store.resultFeed(q.after, q.limit, q.shard, q.shardCount, q.vaultId, q.params[FeedParams.TRANSFORM])
    }
}

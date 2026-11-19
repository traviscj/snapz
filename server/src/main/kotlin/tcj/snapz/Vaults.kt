package tcj.snapz

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import misk.MiskCaller
import misk.exceptions.BadRequestException
import misk.exceptions.ConflictException
import misk.exceptions.NotFoundException
import misk.exceptions.UnauthenticatedException
import misk.exceptions.UnauthorizedException
import misk.scope.ActionScoped
import misk.security.authz.Authenticated
import misk.web.Delete
import misk.web.DispatchMechanism
import misk.web.Get
import misk.web.HttpCall
import misk.web.Patch
import misk.web.PathParam
import misk.web.Post
import misk.web.Put
import misk.web.QueryParam
import misk.web.RequestBody
import misk.web.RequestContentType
import misk.web.ResponseContentType
import misk.web.actions.WebAction
import misk.web.mediatype.MediaTypes

// ---------------------------------------------------------------------------------------------
// A vault is a namespace over everything else: collections (and so photos, chains, intents),
// records, and an inbox of its own. The same slug can exist in two vaults and mean two things.
//
// How a request picks one:
//   - things addressed by name (a collection slug, a record ns/k, "the inbox", a listing) resolve
//     in the request's vault: the X-Snapz-Vault header, else ?vault=, else the default vault;
//   - things addressed by token (a photo, an intent, a blob) are found wherever they live.
// So "move photo T to collection C" with vault V selected moves it into V: that is the whole
// cross-vault API.
//
// A token can be pinned to one vault. It then sees only that vault, cannot name another, and
// cannot use the admin routes (vault and token management, purge, feed switches).
//
// Blobs are content-addressed and shared across vaults: a vault separates what you see, not what
// is on disk.
// ---------------------------------------------------------------------------------------------

data class Vault(val id: Long, val slug: String)

/** Globally unique and permanent; the `v` prefix keeps it from ever looking like a slug someone chose. */
fun newVaultToken() = "v" + randomToken(20)

data class VaultDto(
  val slug: String,
  val name: String,
  val description: String?,
  val icon: String?,
  val is_default: Boolean,
  val collection_count: Long,
  val photo_count: Long,
  val bytes: Long,
  val last_photo_at: String?,
  val created_at: String,
  val updated_at: String,
  val deleted_at: String?,
  val feed_sync_id: Long?,
  /** Globally unique, permanent name of the vault. */
  val token: String? = null,
  /** The caller's role here: owner, editor, viewer; or operator for someone who runs the server. */
  val role: String? = null,
  @Transient val id: Long = 0,
)

@Singleton
class VaultStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val config: SnapzConfig,
) {
  private val select = """
    SELECT v.*, COALESCE(s.nc, 0) AS collection_count, COALESCE(s.n, 0) AS photo_count, COALESCE(s.bytes, 0) AS photo_bytes, s.last_taken
    FROM vaults v
    LEFT JOIN (SELECT c.vault_id, COUNT(DISTINCT c.id) nc, COUNT(p.id) n, SUM(p.bytes) bytes, MAX(p.taken_at_ms) last_taken
               FROM collections c LEFT JOIN photos p ON p.collection_id = c.id AND p.deleted_at_ms IS NULL
               WHERE c.deleted_at_ms IS NULL GROUP BY c.vault_id) s ON s.vault_id = v.id
  """.trimIndent()

  private fun dto(rs: ResultSet) = VaultDto(
    slug = rs.getString("slug"),
    name = rs.getString("name"),
    description = rs.getString("description"),
    icon = rs.getString("icon"),
    is_default = rs.getString("slug") == config.snapz.default_vault,
    collection_count = rs.getLong("collection_count"),
    photo_count = rs.getLong("photo_count"),
    bytes = rs.getLong("photo_bytes"),
    last_photo_at = isoOrNull(rs.longOrNull("last_taken")),
    created_at = iso(rs.getLong("created_at_ms")),
    updated_at = iso(rs.getLong("updated_at_ms")),
    deleted_at = isoOrNull(rs.longOrNull("deleted_at_ms")),
    feed_sync_id = rs.longOrNull("feed_sync_id"),
    token = rs.getString("token"),
    id = rs.getLong("id"),
  )

  fun list(only: Long? = null): List<VaultDto> = db.tx { c ->
    if (only == null) c.select("$select WHERE v.deleted_at_ms IS NULL ORDER BY v.name", map = ::dto)
    else c.select("$select WHERE v.deleted_at_ms IS NULL AND v.id = ?", listOf(only), ::dto)
  }

  private fun get(c: Connection, slug: String): VaultDto =
    c.select("$select WHERE v.slug = ? AND v.deleted_at_ms IS NULL", listOf(slug), ::dto).firstOrNull() ?: throw NotFoundException("no vault '$slug'")

  /** By slug, or by token (the name that never changes). */
  /**
   * A vault's own slug is unique on this server; a member's name for it need not be. So a vault made
   * for a member takes the name they asked for when it is free here, and the next free variant when
   * it is not ("pantry-2"): they still call it what they asked for.
   */
  fun createFree(wanted: String, name: String, description: String?, icon: String?): VaultDto {
    if (!SLUG.matches(wanted)) throw BadRequestException("bad slug '$wanted': want ${SLUG.pattern}")
    for (n in 1..200) {
      try {
        return create(if (n == 1) wanted else "${wanted.take(56)}-$n", name, description, icon)
      } catch (e: ConflictException) {
        continue
      }
    }
    throw ConflictException("no free name near '$wanted'")
  }

  fun find(c: Connection, slug: String): Vault? =
    c.select("SELECT id, slug FROM vaults WHERE (slug = ? OR token = ?) AND deleted_at_ms IS NULL", listOf(slug, slug)) { Vault(it.getLong(1), it.getString(2)) }.firstOrNull()

  fun require(slug: String): Vault = db.tx { c -> find(c, slug) } ?: throw NotFoundException("no vault '$slug'")

  fun default(): Vault = require(config.snapz.default_vault)

  /** [token]: a replica passes the source's, so the vault is the same vault on both servers. */
  fun create(slug: String?, name: String, description: String?, icon: String?, token: String? = null): VaultDto {
    val s = slug?.takeIf { it.isNotBlank() } ?: slugify(name)
    if (!SLUG.matches(s)) throw BadRequestException("bad slug '$s': want ${SLUG.pattern}")
    val now = clock.millis()
    return db.tx { c ->
      val id = try {
        c.insert(
          "INSERT INTO vaults (slug, name, description, icon, created_at_ms, updated_at_ms, shard, token) VALUES (?,?,?,?,?,?,?,?)",
          listOf(s, name, description, icon, now, now, shardOf("vault:$s", config.snapz.feed_shards), token ?: newVaultToken()),
        )
      } catch (e: SQLIntegrityConstraintViolationException) {
        throw ConflictException("vault '$s' already exists")
      }
      ensureInbox(c, id)
      get(c, s)
    }
  }

  fun update(slug: String, name: String?, description: String?, icon: String?): VaultDto = db.tx { c ->
    val v = find(c, slug) ?: throw NotFoundException("no vault '$slug'")
    val sets = mutableListOf("updated_at_ms = ?", "feed_sync_id = NULL")
    val args = mutableListOf<Any?>(clock.millis())
    name?.let { sets += "name = ?"; args += it }
    description?.let { sets += "description = ?"; args += it }
    icon?.let { sets += "icon = ?"; args += it }
    c.exec("UPDATE vaults SET ${sets.joinToString()} WHERE id = ?", args + v.id)
    get(c, slug)
  }

  /**
   * Soft delete. Refuses a vault that still holds photos or records unless [force], which trashes
   * them. The default vault can't go: requests that name no vault need somewhere to land.
   */
  fun delete(slug: String, force: Boolean) {
    if (slug == config.snapz.default_vault) throw ConflictException("'$slug' is the default vault and can't be deleted")
    val now = clock.millis()
    db.tx { c ->
      val v = find(c, slug) ?: throw NotFoundException("no vault '$slug'")
      val photos = c.select(
        "SELECT COUNT(*) FROM photos p JOIN collections c ON c.id = p.collection_id WHERE c.vault_id = ? AND p.deleted_at_ms IS NULL", listOf(v.id),
      ) { it.getLong(1) }.first()
      val records = c.select("SELECT COUNT(*) FROM records WHERE vault_id = ? AND deleted_at_ms IS NULL", listOf(v.id)) { it.getLong(1) }.first()
      if ((photos > 0 || records > 0) && !force) throw ConflictException("vault '$slug' has $photos photos and $records records; pass force=true to trash them too")
      c.exec(
        "UPDATE photos p JOIN collections c ON c.id = p.collection_id SET p.deleted_at_ms = ?, p.updated_at_ms = ?, p.feed_sync_id = NULL WHERE c.vault_id = ? AND p.deleted_at_ms IS NULL",
        listOf(now, now, v.id),
      )
      c.exec("UPDATE records SET deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE vault_id = ? AND deleted_at_ms IS NULL", listOf(now, now, v.id))
      c.exec(
        "UPDATE collections SET slug = CONCAT(slug, '.deleted.', id), deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE vault_id = ? AND deleted_at_ms IS NULL",
        listOf(now, now, v.id),
      )
      c.exec("UPDATE vaults SET slug = ?, deleted_at_ms = ?, updated_at_ms = ?, feed_sync_id = NULL WHERE id = ?", listOf("$slug.deleted.${v.id}", now, now, v.id))
    }
  }

  fun feed(after: Long, limit: Int, shard: Int?, shardCount: Int?, vaultId: Long?): List<VaultDto> = db.tx { c ->
    val (cond, args) = shardCond("v", shard, shardCount)
    val only = if (vaultId != null) "AND v.id = ?" else ""
    c.select("$select WHERE v.feed_sync_id > ? AND v.shard >= 0 $cond $only ORDER BY v.feed_sync_id LIMIT ?", listOf<Any?>(after) + args + listOfNotNull(vaultId) + limit, ::dto)
  }

  /** Startup: the default vault exists, and (if configured) every vault has its inbox. */
  fun seed() {
    val slug = config.snapz.default_vault
    val now = clock.millis()
    db.tx { c ->
      if (find(c, slug) == null) {
        c.insert(
          "INSERT INTO vaults (slug, name, icon, created_at_ms, updated_at_ms, shard, token) VALUES (?,?,?,?,?,?,?)",
          listOf(slug, slug.replaceFirstChar { it.uppercase() }, "🔐", now, now, shardOf("vault:$slug", config.snapz.feed_shards), newVaultToken()),
        )
      }
      c.select("SELECT id FROM vaults WHERE deleted_at_ms IS NULL") { it.getLong(1) }.forEach { ensureInbox(c, it) }
    }
  }

  /** "Snap first, file later" needs somewhere to snap to: one inbox per vault, ranked for triage. */
  private fun ensureInbox(c: Connection, vaultId: Long) {
    if (!config.snapz.auto_create_inbox) return
    val inbox = config.snapz.inbox_slug
    if (c.select("SELECT 1 FROM collections WHERE vault_id = ? AND slug = ? AND deleted_at_ms IS NULL", listOf(vaultId, inbox)) { 1 }.isNotEmpty()) return
    val now = clock.millis()
    val id = c.insert(
      "INSERT INTO collections (vault_id, slug, name, description, icon, created_at_ms, updated_at_ms, shard) VALUES (?,?,?,?,?,?,?,?)",
      listOf(vaultId, inbox, "Inbox", "Snap first, file later: photos here are ranked for triage.", "📥", now, now, shardOf("$vaultId:$inbox", config.snapz.feed_shards)),
    )
    c.exec("INSERT INTO collection_transforms (collection_id, position, transform, params) VALUES (?, 0, 'suggest-collection', NULL)", listOf(id))
  }
}

/** Capability carried by callers that are not pinned to a vault and not read-only. */
const val ADMIN = "snapz_admin"
const val VAULT_HEADER = "X-Snapz-Vault"
private const val PIN = "vault:"

fun vaultCapability(slug: String) = PIN + slug

/** Which vault this request is about, and whether the caller may touch a given one. */
class VaultContext @Inject constructor(
  private val httpCall: @JvmSuppressWildcards ActionScoped<HttpCall>,
  private val caller: @JvmSuppressWildcards ActionScoped<MiskCaller?>,
  private val vaults: VaultStore,
  private val photos: PhotoStore,
  private val users: UserStore,
  private val members: MemberStore,
) {
  private fun caps() = caller.get()?.capabilities.orEmpty()

  /** Slug the caller's token is pinned to, if any. */
  fun pinned(): String? = caps().firstOrNull { it.startsWith(PIN) }?.removePrefix(PIN)

  /** Runs the server: sees every vault. Everyone else is a member of some vaults. */
  fun operator(): Boolean = OPERATOR in caps()

  fun requireOperator() {
    if (!operator()) throw UnauthorizedException("this is for the people who run the server")
  }

  fun principal(): String = caller.get()?.principal ?: throw UnauthenticatedException()

  fun userId(): Long = users.ensure(principal())

  private fun requested(): String? {
    val call = httpCall.get()
    return (call.requestHeaders[VAULT_HEADER] ?: call.url.queryParameter("vault"))?.trim()?.takeIf { it.isNotEmpty() }
  }

  /** A viewer may look; anything that is not a GET changes something. */
  private fun admit(m: Membership): Vault {
    if (m.role == Role.VIEWER && httpCall.get().dispatchMechanism != DispatchMechanism.GET) {
      throw UnauthorizedException("you can view vault '${m.vault.slug}' but not change it")
    }
    return m.vault
  }

  /**
   * The vault the request is about. Operators: the one named, or null for "all of them". A pinned
   * token: its vault. A member: the one named among their own (anything else does not exist), or
   * their default; never null, so nothing a member asks for spans vaults.
   */
  fun explicit(): Vault? {
    val pin = pinned()
    val req = requested()
    if (pin != null) {
      if (req != null && req != pin) throw UnauthorizedException("this token is limited to vault '$pin'")
      return vaults.require(pin)
    }
    if (operator()) return req?.let(vaults::require)
    val uid = userId()
    return admit(if (req != null) members.find(uid, req) ?: throw NotFoundException("no vault '$req'") else members.default(principal(), uid))
  }

  fun current(): Vault = explicit() ?: vaults.default()

  /** The caller's membership of a vault named in a path; operators are owners of everything. */
  fun membership(name: String): Membership {
    if (pinned() != null) throw UnauthorizedException("a vault-pinned token can't manage vaults")
    if (operator()) return Membership(vaults.require(name), Role.OWNER)
    return members.find(userId(), name) ?: throw NotFoundException("no vault '$name'")
  }

  /** Something named by token lives in vault [slug]: 404 (not 403) unless the caller can reach that vault. */
  fun reach(slug: String, what: String) {
    val pin = pinned()
    if (pin != null) {
      if (slug != pin) throw NotFoundException(what)
      return
    }
    if (operator()) return
    val vault = vaults.require(slug)
    admit(members.of(userId(), vault.id) ?: throw NotFoundException(what))
  }

  fun photo(token: String): String {
    if (pinned() == null && operator()) return token
    reach(photos.vaultOf(token), "no photo '$token'")
    return token
  }

  fun photos(tokens: List<String>): List<String> = tokens.onEach { photo(it) }
}

data class VaultReq(val slug: String?, val name: String?, val description: String?, val icon: String?)
data class VaultList(val default: String, val vaults: List<VaultDto>)

data class MemberReq(val principal: String, val role: String?)
data class MemberList(val vault: String, val members: List<MemberDto>)

class VaultActions @Inject constructor(
  private val store: VaultStore,
  private val ctx: VaultContext,
  private val config: SnapzConfig,
  private val members: MemberStore,
) : WebAction {
  private fun owned(slug: String): Vault {
    val m = ctx.membership(slug)
    if (m.role != Role.OWNER) throw UnauthorizedException("only an owner of '$slug' can do that")
    return m.vault
  }

  /** Who can reach a vault. Any member may look; owners (and operators) change it. */
  @Get("/api/vaults/{slug}/members")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun members(@PathParam slug: String): MemberList = MemberList(slug, members.members(ctx.membership(slug).vault.id))

  /** Share: give someone (by the email they sign in with) a role, or change it. They need not have signed in yet. */
  @Put("/api/vaults/{slug}/members")
  @Authenticated(capabilities = [USER])
  @RequestContentType(MediaTypes.APPLICATION_JSON)
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun share(@PathParam slug: String, @RequestBody req: MemberReq): MemberList {
    val vault = owned(slug)
    members.put(vault, req.principal, req.role ?: Role.VIEWER)
    return MemberList(slug, members.members(vault.id))
  }

  @Delete("/api/vaults/{slug}/members")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun unshare(@PathParam slug: String, @QueryParam principal: String): MemberList {
    val vault = owned(slug)
    members.remove(vault, principal)
    return MemberList(slug, members.members(vault.id))
  }

  /** Operators see every vault; a pinned token its own; a member the ones they belong to, with their role. */
  @Get("/api/vaults")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun list(): VaultList {
    ctx.pinned()?.let { return VaultList(it, store.list(store.require(it).id)) }
    if (ctx.operator()) return VaultList(config.snapz.default_vault, store.list().map { it.copy(role = "operator") })
    val home = members.default(ctx.principal(), ctx.userId()).vault   // makes their own vault on first use
    val views = members.views(ctx.userId())
    val mine = store.list().filter { it.id in views }.map { mine(it, views.getValue(it.id), home) }
    return VaultList(mine.first { it.is_default }.slug, mine.sortedBy { it.slug })
  }

  /** A vault as one of its members sees it: under their name for it, with their role. */
  private fun mine(v: VaultDto, m: Membership, home: Vault) = v.copy(slug = m.alias, role = m.role, is_default = v.id == home.id)

  /**
   * Operators make vaults for the server (the slug must be free). Anyone else makes one of their
   * own: the slug only has to be new among their vaults.
   */
  @Post("/api/vaults")
  @Authenticated(capabilities = [USER])
  @RequestContentType(MediaTypes.APPLICATION_JSON)
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun create(@RequestBody req: VaultReq): VaultDto {
    if (ctx.pinned() != null) throw UnauthorizedException("a vault-pinned token can't make vaults")
    val name = req.name?.takeIf { it.isNotBlank() } ?: req.slug ?: throw BadRequestException("name is required")
    if (ctx.operator()) return store.create(req.slug, name, req.description, req.icon).copy(role = "operator")
    val m = members.createOwn(ctx.userId(), req.slug?.takeIf { it.isNotBlank() } ?: slugify(name), name, req.description, req.icon)
    return store.list(m.vault.id).single().copy(slug = m.alias, role = m.role)
  }

  @Patch("/api/vaults/{slug}")
  @Authenticated(capabilities = [USER])
  @RequestContentType(MediaTypes.APPLICATION_JSON)
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun update(@PathParam slug: String, @RequestBody req: VaultReq): VaultDto {
    val vault = owned(slug)
    return store.update(vault.slug, req.name, req.description, req.icon).copy(slug = slug)
  }

  @Delete("/api/vaults/{slug}")
  @Authenticated(capabilities = [USER])
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun delete(@PathParam slug: String, @QueryParam force: Boolean?): Ok {
    store.delete(owned(slug).slug, force == true)
    return Ok()
  }

  /** Call one of your vaults something else. Only your view changes; `token` is what stays the same for everyone. */
  @Put("/api/vaults/{slug}/alias")
  @Authenticated(capabilities = [USER])
  @RequestContentType(MediaTypes.APPLICATION_JSON)
  @ResponseContentType(MediaTypes.APPLICATION_JSON)
  fun alias(@PathParam slug: String, @RequestBody req: VaultReq): Ok {
    if (ctx.pinned() != null || ctx.operator()) throw BadRequestException("the people who run the server see every vault under its own slug; a member renames their view")
    val uid = ctx.userId()
    val m = members.find(uid, slug) ?: throw NotFoundException("no vault '$slug'")
    members.rename(uid, m.vault.id, req.slug?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("slug is required"))
    return Ok()
  }
}

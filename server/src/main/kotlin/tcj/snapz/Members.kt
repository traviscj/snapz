package tcj.snapz

import java.sql.Connection
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.exceptions.ConflictException
import misk.exceptions.NotFoundException

/**
 * Who may see what.
 *
 * - An **operator** runs the server (a credential whose owner is in the admin group, or a token an
 *   operator minted): every vault, the feeds' cursors, the worker and replication endpoints.
 * - Everyone else is a **member**: they reach the vaults they own or were given, and nothing else
 *   exists as far as they can tell (404, not 403). Each gets a vault of their own on first use.
 *
 * A vault has two names: its `token`, globally unique and permanent (what anything without a user
 * behind it should use), and a slug per member, unique among that member's vaults. For now a
 * member's slug is the vault's own slug.
 */
const val OPERATOR = "snapz_operator"

object Role {
  const val OWNER = "owner"
  const val EDITOR = "editor"
  const val VIEWER = "viewer"
  val all = setOf(OWNER, EDITOR, VIEWER)
}

/** [alias] is this member's own name for the vault: what they type and see. */
data class Membership(val vault: Vault, val role: String, val alias: String = vault.slug)
data class MemberDto(val principal: String, val role: String, val since: String)

@Singleton
class UserStore @Inject constructor(private val db: Db, private val clock: Clock) {
  /** The user row for a principal (an email, or whatever name a token carries), created on first sight. */
  fun ensure(principal: String): Long = db.tx { c -> ensure(c, principal) }

  fun ensure(c: Connection, principal: String): Long {
    val who = principal.trim().lowercase()   // emails compare without case; so do the other names
    c.exec("INSERT IGNORE INTO users (principal, created_at_ms) VALUES (?,?)", listOf(who, clock.millis()))
    return c.select("SELECT id FROM users WHERE principal = ?", listOf(who)) { it.getLong(1) }.first()
  }
}

@Singleton
class MemberStore @Inject constructor(
  private val db: Db,
  private val clock: Clock,
  private val users: UserStore,
  private val vaults: VaultStore,
) {
  private val select = """
    SELECT v.id, v.slug, m.role, m.slug AS alias FROM vault_members m JOIN vaults v ON v.id = m.vault_id AND v.deleted_at_ms IS NULL
    WHERE m.user_id = ?
  """.trimIndent()

  private fun row(rs: java.sql.ResultSet) = Membership(Vault(rs.getLong(1), rs.getString(2)), rs.getString(3), rs.getString(4))

  /** One of the user's vaults, by their slug for it or by its token. */
  fun find(userId: Long, name: String): Membership? = db.tx { c ->
    c.select("$select AND (m.slug = ? OR v.token = ?)", listOf(userId, name, name), ::row).firstOrNull()
  }

  fun of(userId: Long, vaultId: Long): Membership? = db.tx { c ->
    c.select("$select AND v.id = ?", listOf(userId, vaultId), ::row).firstOrNull()
  }

  /** vault id -> membership, for everything the user can reach. */
  fun views(userId: Long): Map<Long, Membership> = db.tx { c -> c.select(select, listOf(userId), ::row).associateBy { it.vault.id } }

  /** A new vault of the user's own, under the name they chose (unique among their vaults, not on the server). */
  fun createOwn(userId: Long, alias: String, name: String, description: String?, icon: String?): Membership {
    if (!SLUG.matches(alias)) throw BadRequestException("bad slug '$alias': want ${SLUG.pattern}")
    if (db.tx { c -> c.select("SELECT 1 FROM vault_members WHERE user_id = ? AND slug = ?", listOf(userId, alias)) { 1 } }.isNotEmpty()) {
      throw ConflictException("you already have a vault called '$alias'")
    }
    val vault = vaults.require(vaults.createFree(alias, name, description, icon).token!!)
    db.tx { c ->
      c.exec("INSERT INTO vault_members (vault_id, user_id, role, slug, created_at_ms) VALUES (?,?,?,?,?)", listOf(vault.id, userId, Role.OWNER, alias, clock.millis()))
    }
    return Membership(vault, Role.OWNER, alias)
  }

  /** Call a vault something else. It changes nothing for anyone else. */
  fun rename(userId: Long, vaultId: Long, alias: String) {
    if (!SLUG.matches(alias)) throw BadRequestException("bad slug '$alias': want ${SLUG.pattern}")
    db.tx { c ->
      if (c.select("SELECT 1 FROM vault_members WHERE user_id = ? AND slug = ? AND vault_id <> ?", listOf(userId, alias, vaultId)) { 1 }.isNotEmpty()) {
        throw ConflictException("you already have a vault called '$alias'")
      }
      if (c.exec("UPDATE vault_members SET slug = ? WHERE user_id = ? AND vault_id = ?", listOf(alias, userId, vaultId)) == 0 &&
        c.select("SELECT 1 FROM vault_members WHERE user_id = ? AND vault_id = ?", listOf(userId, vaultId)) { 1 }.isEmpty()
      ) throw NotFoundException("not one of your vaults")
    }
  }

  /**
   * Where a member lands when a request names no vault: the one they chose or were first given a
   * vault of their own, made now if they have none.
   */
  fun default(principal: String, userId: Long): Membership {
    db.tx { c ->
      c.select("$select AND v.id = (SELECT default_vault_id FROM users WHERE id = ?)", listOf(userId, userId), ::row).firstOrNull()
        ?: c.select("$select AND m.role = '${Role.OWNER}' ORDER BY m.id LIMIT 1", listOf(userId), ::row).firstOrNull()
    }?.let { return it }
    // No vault of their own yet: start from the name they sign in with. They can call it anything later.
    val own = createOwn(userId, slugify(principal.substringBefore('@')).take(56), principal.substringBefore('@'), null, null)
    db.tx { c -> c.exec("UPDATE users SET default_vault_id = ? WHERE id = ?", listOf(own.vault.id, userId)) }
    return own
  }

  fun members(vaultId: Long): List<MemberDto> = db.tx { c ->
    c.select(
      "SELECT u.principal, m.role, m.created_at_ms FROM vault_members m JOIN users u ON u.id = m.user_id WHERE m.vault_id = ? ORDER BY m.id", listOf(vaultId),
    ) { MemberDto(it.getString(1), it.getString(2), iso(it.getLong(3))) }
  }

  /**
   * Give [principal] a role on the vault (or change it). The person need not have signed in yet:
   * the membership waits for them. A vault keeps at least one owner.
   */
  fun put(vault: Vault, principal: String, role: String) {
    if (role !in Role.all) throw BadRequestException("role is one of ${Role.all.joinToString()}")
    val who = principal.trim().lowercase().takeIf { it.isNotEmpty() && it.length <= 255 } ?: throw BadRequestException("who? (the email they sign in with)")
    db.tx { c ->
      val userId = users.ensure(c, who)
      val current = c.select("SELECT role FROM vault_members WHERE vault_id = ? AND user_id = ? FOR UPDATE", listOf(vault.id, userId)) { it.getString(1) }.firstOrNull()
      if (current == Role.OWNER && role != Role.OWNER) requireAnotherOwner(c, vault, userId)
      if (current != null) {
        c.exec("UPDATE vault_members SET role = ? WHERE vault_id = ? AND user_id = ?", listOf(role, vault.id, userId))
      } else {
        // Their slug for it is the vault's own unless they already have one by that name.
        val taken = c.select("SELECT slug FROM vault_members WHERE user_id = ?", listOf(userId)) { it.getString(1) }.toSet()
        val slug = generateSequence(1) { it + 1 }.map { if (it == 1) vault.slug else "${vault.slug}-$it" }.first { it !in taken }
        c.exec("INSERT INTO vault_members (vault_id, user_id, role, slug, created_at_ms) VALUES (?,?,?,?,?)", listOf(vault.id, userId, role, slug, clock.millis()))
      }
    }
  }

  fun remove(vault: Vault, principal: String) {
    db.tx { c ->
      val userId = c.select("SELECT id FROM users WHERE principal = ?", listOf(principal.trim().lowercase())) { it.getLong(1) }.firstOrNull()
        ?: throw NotFoundException("'$principal' is not a member")
      val role = c.select("SELECT role FROM vault_members WHERE vault_id = ? AND user_id = ? FOR UPDATE", listOf(vault.id, userId)) { it.getString(1) }.firstOrNull()
        ?: throw NotFoundException("'$principal' is not a member")
      if (role == Role.OWNER) requireAnotherOwner(c, vault, userId)
      c.exec("DELETE FROM vault_members WHERE vault_id = ? AND user_id = ?", listOf(vault.id, userId))
      c.exec("UPDATE users SET default_vault_id = NULL WHERE id = ? AND default_vault_id = ?", listOf(userId, vault.id))
    }
  }

  private fun requireAnotherOwner(c: Connection, vault: Vault, userId: Long) {
    val others = c.select("SELECT COUNT(*) FROM vault_members WHERE vault_id = ? AND role = ? AND user_id <> ?", listOf(vault.id, Role.OWNER, userId)) { it.getLong(1) }.first()
    if (others == 0L) throw ConflictException("'${vault.slug}' would be left without an owner")
  }
}

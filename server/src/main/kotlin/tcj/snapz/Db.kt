package tcj.snapz

import com.squareup.moshi.Moshi
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.util.Base64
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import misk.exceptions.BadRequestException
import misk.jdbc.Transacter

@Singleton
class Db @Inject constructor(@SnapzDb private val transacter: Transacter) {
  fun <T> tx(block: (Connection) -> T): T =
    transacter.transactionWithSession { s -> s.useConnection { c -> block(c) } }
}

private fun PreparedStatement.bindAll(args: List<Any?>) {
  args.forEachIndexed { i, a -> setObject(i + 1, a) }
}

fun Connection.exec(sql: String, args: List<Any?> = listOf()): Int =
  prepareStatement(sql).use { it.bindAll(args); it.executeUpdate() }

fun Connection.insert(sql: String, args: List<Any?>): Long =
  prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
    ps.bindAll(args)
    ps.executeUpdate()
    ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
  }

fun <T> Connection.select(sql: String, args: List<Any?> = listOf(), map: (ResultSet) -> T): List<T> =
  prepareStatement(sql).use { ps ->
    ps.bindAll(args)
    ps.executeQuery().use { rs ->
      val out = ArrayList<T>()
      while (rs.next()) out.add(map(rs))
      out
    }
  }

fun ResultSet.longOrNull(col: String): Long? = getLong(col).takeUnless { wasNull() }
fun ResultSet.intOrNull(col: String): Int? = getInt(col).takeUnless { wasNull() }

fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()
fun isoOrNull(ms: Long?): String? = ms?.let(::iso)

/** Accepts ISO-8601 instants or epoch milliseconds. */
fun parseTime(s: String): Long =
  s.toLongOrNull() ?: try {
    Instant.parse(s).toEpochMilli()
  } catch (e: Exception) {
    throw BadRequestException("bad timestamp '$s': want ISO-8601 instant or epoch ms")
  }

private val rng = SecureRandom()
private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

fun randomToken(len: Int = 16): String = buildString(len) { repeat(len) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) } }

fun randomSecret(bytes: Int = 32): String =
  Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(rng::nextBytes))

fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun shardOf(key: String, shards: Int): Int {
  val crc = CRC32().apply { update(key.toByteArray()) }.value
  return (crc % shards).toInt()
}

/**
 * Free-form JSON (photo meta, record values) is stored as text and surfaced as plain maps/lists.
 * Moshi reads every JSON number as a Double; [normalize] turns integral ones back into Longs so
 * `{"qty": 2}` doesn't come back as `2.0`.
 */
@Singleton
class Json @Inject constructor(moshi: Moshi) {
  private val any = moshi.adapter(Any::class.java).serializeNulls()

  fun parse(s: String): Any? = try {
    normalize(any.fromJson(s))
  } catch (e: Exception) {
    throw BadRequestException("invalid JSON: ${e.message}")
  }

  @Suppress("UNCHECKED_CAST")
  fun parseObject(s: String?): Map<String, Any?> {
    if (s.isNullOrBlank()) return mapOf()
    return parse(s) as? Map<String, Any?> ?: throw BadRequestException("expected a JSON object")
  }

  fun write(v: Any?): String = any.toJson(normalize(v))

  companion object {
    fun normalize(v: Any?): Any? = when (v) {
      is Double -> if (v % 1.0 == 0.0 && abs(v) < 9.0e15) v.toLong() else v
      is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to normalize(x) }
      is List<*> -> v.map(::normalize)
      else -> v
    }
  }
}

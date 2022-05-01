import arrow.core.continuations.Effect
import arrow.core.continuations.EffectScope
import arrow.core.continuations.effect
import java.util.UUID

@JvmInline value class Content(val body: List<String>)

sealed interface FileError
@JvmInline value class SecurityError(val msg: String?) : FileError
@JvmInline value class FileNotFound(val path: String) : FileError
object EmptyPath : FileError {
  override fun toString() = "EmptyPath"
}

context(EffectScope<FileError>)
suspend fun readFile(path: String?): Content {
  TODO("All functionality from cont { } available here")
}

val res: Effect<FileError, Content> = effect {
  readFile("")
}

context(EffectScope<FileError>)
suspend fun allFiles(vararg path: String): List<Content> =
  path.map { readFile(it) }

interface Logger {
  suspend fun info(msg: String): Unit
  suspend fun debug(msg: String)
  suspend fun error(msg: String)
}

context(Logger, EffectScope<FileError>)
  suspend fun allFiles(vararg path: String): List<Content> {
  info("Processing files")
  return path.map { readFile(it) }
}

val res2: Effect<FileError, List<Content>> = with(PrintLnLogger()) {
  effect {
    allFiles("", "")
  }
}

context(Logger)
suspend fun allFilesOrEmpty(vararg path: String): List<Content> =
  effect<FileError, List<Content>> {
    allFiles(*path)
  }.orNull() ?: emptyList()

fun <A> List<A>.sort(comparator: Comparator<A>): List<A> =
  sortedWith(comparator)

context(Comparator<A>)
fun <A> List<A>.sort(): List<A> =
  sortedWith(this@Comparator)

fun PrintLnLogger(): Logger = object : Logger {
  override suspend fun info(msg: String) = println("[INFO] - $msg")
  override suspend fun debug(msg: String) = println("[DEBUG] - $msg")
  override suspend fun error(msg: String) = println("[ERROR] - $msg")
}

data class User(val uuid: UUID, val age: Int, val name: String)
data class DbError(val error: Throwable)

interface UserPersistence {
  context(EffectScope<DbError>)
    suspend fun getUser(uuid: UUID): User?
}

context(Logger, UserPersistence, EffectScope<DbError>)
  suspend fun fetchUser(uuid: UUID): User? =
  try {
    getUser(uuid)?.also { info("Fetched user $it") }
  } catch (error: Throwable) {
    error("Fetching user failed with ${error.message}")
    shift(DbError(error))
  }

data class NetworkError(val error: Throwable)
data class Order(val uuid: UUID, val buyer: UUID)

interface Orders {
  context(EffectScope<NetworkError>)
    suspend fun fetchOrdersForUser(uuid: UUID): List<Order>
}

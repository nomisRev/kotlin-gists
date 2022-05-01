import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransactionWithReturn
import arrow.core.Either
import arrow.core.continuations.EagerEffectScope
import arrow.core.continuations.EffectScope
import arrow.core.continuations.eagerEffect
import arrow.core.continuations.effect
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

suspend fun <E, T> newSuspendedEitherTransaction(
  context: CoroutineDispatcher? = null,
  db: Database? = null,
  transactionIsolation: Int? = null,
  statement: suspend context(EffectScope<E>, Transaction) (TypeWrapper<EffectScope<E>>) -> T
): Either<E, T> = effect<E, T> {
  newSuspendedTransaction(context, db, transactionIsolation) {
    statement(this@effect, this, TypeWrapper.IMPL)
  }
}.toEither()

/**
 * Either-Syntax for SqlDelight transactions.
 *
 * Upon encountering a [Either.Left] value it requests a rollback and returns the encountered [Either.Left] value.
 * Otherwise, it returns the returned value wrapped in [Either.Right].
 *
 * ```kotlin
 * object Error
 *
 * fun Database.saveOperations(operations: List<Either<Error, String>>): Either<Error, String> =
 *   transactionEither {
 *     val x = Either.Right("test-1").bind()
 *     val y = Either.Left(Error).bind<String>()
 *     val z = Either.Right("test-3").bind()
 *     "$x, $y, $z"
 *   }
 * ```
 *
 * The above snippet will first insert `test-1` into `table` since `bind` can unwrap it from `Either.Right`,
 * when it encounters `Left(Error)` it will roll back the transaction, thus the previous insert, and return `Left(Error)` from `transactionEither`.
 *
 * If `operations` was `Either.Right("test-1"), Either.Right("test-2"), Either.Right("test-3")`
 * it would insert all 3 values into the `table` and return `"test-1, test-2, test-3"`.
 */
fun <E, A> Transacter.transactionEither(f: suspend EagerEffectScope<E>.() -> A): Either<E, A> =
  transactionWithResult {
    when (val res = eagerEffect(f).toEither()) {
      is Either.Left -> rollback(res)
      is Either.Right -> res
    }
  }

suspend fun <E, A> Database.transactionEither(
  context: CoroutineDispatcher? = null,
  transactionIsolation: Int? = null,
  f: suspend EffectScope<E>.() -> A
): Either<E, A> = effect<E, A> {
  newSuspendedTransaction(
    context = context,
    transactionIsolation = transactionIsolation,
    db = this@transactionEither
  ) { f() }
}.toEither()

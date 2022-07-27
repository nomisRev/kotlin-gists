package arrow2

import arrow.core.continuations.Effect
import arrow.core.continuations.EffectScope
import arrow.core.continuations.effect
import kotlin.experimental.ExperimentalTypeInference

// New function in addition to attempt / catch proposed by @Alejandro
@OptIn(ExperimentalTypeInference::class)
@BuilderInference
fun <E, E2, A> Effect<E, A>.catch(
  recover: suspend EffectScope<E2>.(Throwable) -> A = { throw it },
  resolve: suspend EffectScope<E2>.(E) -> A,
): Effect<E2, A> = effect {
  this@catch.fold(
    { recover(it) },
    { resolve(it) },
    { it }
  )
}

fun <R, A> Effect<R, A>.handleError(recover: suspend (R) -> @UnsafeVariance A): Effect<Nothing, A> =
  catch { recover(it) }

fun <R2, R, A> Effect<R, A>.handleErrorWith(recover: suspend (R) -> Effect<R2, @UnsafeVariance A>): Effect<R2, A> =
  catch { recover(it).bind() }

fun <R2, R, A> Effect<R, A>.mapLeft(recover: suspend (R) -> R2): Effect<R2, A> =
  catch { shift(recover(it)) }

fun <R, A, B> Effect<R, A>.redeem(recover: suspend (R) -> B, transform: suspend (A) -> B): Effect<Nothing, B> =
  effect<R, B> {
    transform(bind())
  }.catch { recover(it) }

// Uses new method by Alejandro
public fun <R, R2, A, B> Effect<R, A>.redeemWith(
  recover: suspend (R) -> Effect<R2, B>,
  transform: suspend (A) -> Effect<R2, B>,
): Effect<R2, B> = effect {
  attempt {
    transform(bind())
  }.catch {
    recover(it)
  }.bind()
}

// New Throwable functionality expose by catch
fun <R, A> Effect<R, A>.handleThrowable(recover: suspend (Throwable) -> @UnsafeVariance A): Effect<R, A> =
  catch({ e -> recover(e) }, { r -> shift(r) })

fun <R, A> Effect<R, A>.handleErrorThrowable(
  resolve: suspend (R) -> @UnsafeVariance A,
  recover: suspend (Throwable) -> @UnsafeVariance A,
): Effect<R, A> =
  catch({ e -> recover(e) }, { r -> resolve(r) })

fun <R, A> Effect<R, A>.handleThrowableWith(recover: suspend (Throwable) -> Effect<R, @UnsafeVariance A>): Effect<R, A> =
  catch({ e -> recover(e).bind() }, { r -> shift(r) })

fun <R2, R, A> Effect<R, A>.handleErrorThrowableWith(
  resolve: suspend (R) -> Effect<R2, @UnsafeVariance A>,
  recover: suspend (Throwable) -> Effect<R2, @UnsafeVariance A>,
): Effect<R2, A> =
  catch({ e -> recover(e).bind() }, { r -> resolve(r).bind() })

fun <R, A> Effect<R, A>.mapThrowable(recover: suspend (Throwable) -> R): Effect<R, A> =
  catch({ e -> shift(recover(e)) }, { r -> shift(r) })

fun <R2, R, A> Effect<R, A>.mapErrorThrowable(
  resolve: suspend (R) -> R2,
  recover: suspend (Throwable) -> R2,
): Effect<R2, A> =
  catch({ e -> shift(recover(e)) }, { r -> shift(resolve(r)) })

fun <R, A, B> Effect<R, A>.redeemThrowable(
  recover: suspend (Throwable) -> B,
  transform: suspend (A) -> B,
): Effect<R, B> = effect {
  transform(bind())
}.catch({ e -> recover(e) }, { r -> shift(r) })

fun <R, A, B> Effect<R, A>.redeemErrorThrowable(
  resolve: suspend (R) -> B,
  recover: suspend (Throwable) -> B,
  transform: suspend (A) -> B,
): Effect<R, B> = effect {
  transform(bind())
}.catch({ e -> recover(e) }, { r -> resolve(r) })

fun <R, A, B> Effect<R, A>.redeemThrowableWith(
  recover: suspend (Throwable) -> Effect<R, B>,
  transform: suspend (A) -> Effect<R, B>,
): Effect<R, B> =
  effect<R, B> {
    transform(bind()).bind()
  }.catch({ e -> recover(e).bind() }, { r -> shift(r) })

fun <R2, R, A, B> Effect<R, A>.redeemErrorThrowableWith(
  resolve: suspend (R) -> Effect<R2, B>,
  recover: suspend (Throwable) -> Effect<R2, B>,
  transform: suspend (A) -> Effect<R2, B>,
): Effect<R2, B> = effect {
  // TODO we need a more convenient way of handling both Throwable & R
  // Same issue as Result<R> in other catch function, or multiple params
  attempt {
    val res = attempt().bind()
    res.fold({ transform(it) }, { recover(it) })
  }.catch {
    resolve(it)
  }.bind()
}.catch({ e -> recover(e).bind() }, { r -> shift(r) })

//import arrow.core.Either
//import arrow.core.continuations.EffectScope
//import arrow.core.continuations.effect
//import arrow.core.identity
//import arrow.core.nonFatalOrThrow
//import kotlin.time.Duration
//import kotlin.time.Duration.Companion.milliseconds
//import kotlin.time.ExperimentalTime
//import kotlinx.coroutines.currentCoroutineContext
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.ensureActive
//
//sealed interface Sched<I, O> {
//  suspend fun repeat(fa: suspend () -> I): O =
//    repeatOrElse(fa) { e, _ ->
//      println("HELLOOOOOOOOOO $e")
//      throw e
//    }
//
//  suspend fun repeatOrElse(fa: suspend () -> I, orElse: suspend (Throwable, O?) -> O): O =
//    repeatOrElseEither(fa, orElse).fold(::identity, ::identity)
//
//  suspend fun <C> repeatOrElseEither(
//    fa: suspend () -> I,
//    orElse: suspend (Throwable, O?) -> C
//  ): Either<C, O>
//
//  // compose two Schedules
//  fun <B> intersectWith(that: Schedule<I, B>): Schedule<I, Pair<O, B>> = TODO()
//
//  fun check(test: suspend (I, O) -> Boolean): Sched<I, O> =
//    when (this) {
//      is Default -> Default { input ->
//        val output = next(input)
//        if (test(input, output)) output else shift(output)
//      }
//    }
//
//  fun whileOutput(test: suspend (O) -> Boolean): Sched<I, O> =
//    check { _, o -> test(o) }
//
//  fun collect(): Sched<I, List<O>> =
//    fold(emptyList()) { acc, out -> acc + out }
//
//  fun <B> fold(b: B, f: suspend (B, O) -> B): Sched<I, B> {
//    var acc: B = b
//    return when (this) {
//      is Default<I, O> -> Default { input ->
//        val scope = this@Default
//        acc = effect<O, B> {
//          f(b, next.invoke(this@effect, input))
//        }.fold({
//          println("LEFT: $it")
//          val bb: B = f(b, it)
//          scope.shift(bb)
//        }, ::identity)
//        acc
//      }
//    }
//  }
//
//  companion object {
//    fun <A> recurs(n: Long): Sched<A, Long> {
//      var count = 0L
//      return Default {
//        if (count < n) count++ else shift(count)
//      }
//    }
//
//    fun <A> spaced(duration: Duration): Sched<A, Long> {
//      var count = 0L
//      return Default {
//        delay(duration)
//        count++
//      }
//    }
//  }
//
//  class Default<I, O>(
//    val next: suspend EffectScope<O>.(i: I) -> O
//  ) : Sched<I, O> {
//    override suspend fun <C> repeatOrElseEither(
//      fa: suspend () -> I,
//      orElse: suspend (Throwable, O?) -> C
//    ): Either<C, O> = effect<O, C> {
//      var last: O? = null // We haven't seen any input yet
//      while (true) {
//        currentCoroutineContext().ensureActive()
//        try {
//          val a = fa.invoke()
//          val o = next(this, a)
//          last = o
//        } catch (e: Throwable) {
//          println("HEERREEE: $e")
//          return@effect orElse(e.nonFatalOrThrow(), last)
//        }
//      }
//      throw RuntimeException("Unreachable")
//    }.fold({ Either.Right(it) }, { Either.Left(it) })
//  }
//}
//
//@OptIn(ExperimentalTime::class)
//suspend fun main() {
//  var count = 0
//  var start = kotlin.time.TimeSource.Monotonic.markNow()
//  Sched.spaced<Unit>(100.milliseconds)
//    .whileOutput { it < 10 }
//    .collect()
//    .repeat {
//      println("${start.elapsedNow()}, ${count++}")
//      start = kotlin.time.TimeSource.Monotonic.markNow()
//    }.let(::println)
//}
//
////fun <S, I, O> StateSched(
////  initial: suspend () -> S,
////  next: suspend EffectScope<O>.(s: S, i: I) -> Pair<S, O>
////): Sched<I, O> = object : Sched<I, O> {
////  override suspend fun <C> repeatOrElseEither(
////    fa: suspend () -> I,
////    orElse: suspend (Throwable, O?) -> C
////  ): Either<C, O> = either<O, C> {
////    var state: S = initial.invoke()
////    var last: O? = null // We haven't seen any input yet
////    while (true) {
////      currentCoroutineContext().ensureActive()
////      try {
////        val a = fa.invoke()
////        val (s, o) = next(this, state, a)
////        last = o
////        state = s
////      } catch (e: Throwable) {
////        return@either orElse(e.nonFatalOrThrow(), last)
////      }
////    }
////    throw RuntimeException("Unreachable")
////  }.swap()
////}

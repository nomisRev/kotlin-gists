//import arrow.core.Either
//import arrow.core.continuations.EffectScope
//import arrow.core.continuations.effect
//import arrow.core.continuations.either
//import arrow.core.identity
//import arrow.core.nonFatalOrThrow
//import kotlin.time.Duration
//import kotlin.time.Duration.Companion.milliseconds
//import kotlin.time.ExperimentalTime
//import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
//import kotlinx.coroutines.async
//import kotlinx.coroutines.awaitAll
//import kotlinx.coroutines.coroutineScope
//import kotlinx.coroutines.currentCoroutineContext
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.ensureActive
//
//@JvmInline
//value class Sched<I, O>(
//  val next: suspend EffectScope<O>.(i: I) -> O
//) {
//  suspend fun repeat(fa: suspend () -> I): O =
//    repeatOrElse(fa) { e, _ -> throw e }
//
//  suspend fun repeatOrElse(fa: suspend () -> I, orElse: suspend (Throwable, O?) -> O): O =
//    repeatOrElseEither<O>(fa, orElse).fold(::identity, ::identity)
//
//  suspend fun <C> repeatOrElseEither(
//    fa: suspend () -> I,
//    orElse: suspend (Throwable, O?) -> C
//  ): Either<C, O> = effect<O, C> {
//    var last: O? = null // We haven't seen any input yet
//    while (true) {
//      currentCoroutineContext().ensureActive()
//      try {
//        val a = fa.invoke()
//        val o = next(this, a)
//        last = o
//      } catch (e: Throwable) {
//        return@effect orElse(e.nonFatalOrThrow(), last)
//      }
//    }
//    throw RuntimeException("Unreachable")
//  }.fold({ Either.Right(it) }, { Either.Left(it) })
//
//  fun check(test: suspend (I, O) -> Boolean): Sched<I, O> = Sched { input ->
//    val output = next(input)
//    if (test(input, output)) output else shift(output)
//  }
//
//  fun whileOutput(test: suspend (O) -> Boolean): Sched<I, O> =
//    check { _, o -> test(o) }
//
//  fun whileInput(test: suspend (I) -> Boolean): Sched<I, O> =
//    check { i, _ -> test(i) }
//
//  /** Combines two schedules. Continues only when both continue and chooses the maximum delay.*/
//  fun <B> and2(other: Sched<I, B>): Sched<I, Pair<O, B>> = Sched { input ->
//    coroutineScope {
//      // Avoid dispatching, when running into `delay` it will dispatch
//      // If no `delay` in either `Schedule` no dispatching nor parallelism is imposed
//      val (a, b) = awaitAll(
//        async(start = UNDISPATCHED) { either<O, O> { next(this, input) } },
//        async(start = UNDISPATCHED) { either<B, B> { other.next(this, input) } }
//      )
//      when (val original = a as Either<O, O>) {
//        is Either.Left -> when (val other = b as Either<B, B>) {
//          is Either.Left -> shift(Pair(original.value, other.value))
//          is Either.Right -> shift(Pair(original.value, other.value))
//        }
//        is Either.Right -> when (val other = b as Either<B, B>) {
//          is Either.Left -> shift(Pair(original.value, other.value))
//          is Either.Right -> Pair(original.value, other.value)
//        }
//      }
//    }
//  }
//
//  fun collect(): Sched<I, List<O>> =
//    fold(emptyList()) { acc, out -> acc + out }
//
//  fun <B> fold(b: B, f: suspend (B, O) -> B): Sched<I, B> {
//    var acc: B = b
//    return Sched { input ->
//      effect<O, B> {
//        val output = next(input)
//        f(acc, output).also { b ->
//          acc = b
//        }
//      }  // if we use Effect#fold, then resulting bytecode is incorrect
//        //.fold({ shift(f(acc, it)) }, ::identity)
//        .toEither().mapLeft { f(acc, it) }.bind()
//    }
//  }
//
//  companion object {
//    fun <A> recurs(n: Long): Sched<A, Long> {
//      var count = 0L
//      return Sched {
//        if (count < n) count++ else shift(count)
//      }
//    }
//
//    fun <A> spaced(duration: Duration): Sched<A, Long> {
//      var count = 0L
//      return Sched {
//        delay(duration)
//        count++
//      }
//    }
//  }
//}
//
//@OptIn(ExperimentalTime::class)
//suspend fun main() {
//  (0..10).forEach {
//    var count = 0
//    var start = kotlin.time.TimeSource.Monotonic.markNow()
//    Sched.recurs<Unit>(10)
//      .and2(Sched.spaced(500.milliseconds))
//      .and2(Sched.recurs(4))
//      .collect()
//      .repeat {
//        println("${start.elapsedNow()}, ${count++}")
//        start = kotlin.time.TimeSource.Monotonic.markNow()
//      }.let(::println)
//    println("\n------------------------------------------------------------------------\n")
//  }
//}

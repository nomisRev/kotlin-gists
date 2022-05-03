//import arrow.core.Either
//import arrow.core.continuations.EffectScope
//import arrow.core.identity
//import arrow.core.left
//import arrow.core.nonFatalOrThrow
//import arrow.core.right
//import kotlin.coroutines.resume
//import kotlin.coroutines.startCoroutine
//import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
//import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
//import kotlin.coroutines.suspendCoroutine
//import kotlin.time.Duration
//import kotlinx.coroutines.currentCoroutineContext
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.isActive
//
//
//fun interface SchedEffect<I, O> {
//  suspend fun emit(output: O): I
//}
//
////val next: suspend EffectScope<O>.(i: I) -> O
//
//@JvmInline
//value class Sched<I, O>(
//  val next: suspend SchedEffect<I, O>.(i: I) -> Unit
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
//  ): Either<C, O> = RepeatSchedule(fa, orElse, next).run()
//
//   fun <B> fold(b: B, f: suspend (B, O) -> B): Sched<I, B> = Sched {
//     var acc: B = b
//
//   }
////    var acc: B = b
////    return Sched { input ->
////      effect<O, B> {
////        val output = next(input)
////        f(acc, output).also { b ->
////          acc = b
////        }
////      }  // if we use Effect#fold, then resulting bytecode is incorrect
////        .fold({ shift(f(acc, it)) }, ::identity)
////        .toEither().mapLeft { f(acc, it) }.bind()
////    }
////  }
//
//  companion object {
//    fun <A> recurs(n: Long): Sched<A, Long> = Sched {
//      var count = 0L
//      while (count < n) {
//        emit(count++)
//      }
//    }
//
//    fun <A> spaced(duration: Duration): Sched<A, Long> = Sched {
//      var count = 0L
//      while (true) {
//        delay(duration)
//        emit(count++)
//      }
//    }
//  }
//}
//
//suspend fun main() {
//  var count = 0
//  Sched.recurs<Unit>(10)
//    .repeat {
//      println(count++)
//    }
//}
//
//class RepeatSchedule<I, O, C>(
//  val fa: suspend () -> I,
//  val orElse: suspend (Throwable, O?) -> C,
//  val next: suspend SchedEffect<I, O>.(i: I) -> Unit
//) : SchedEffect<I, O> {
//  var last: O? = null // We haven't seen any input yet
//
//  override suspend fun emit(output: O): I =
//    suspendCoroutineUninterceptedOrReturn { cont ->
//      last = output
//      suspend { fa.invoke() }.startCoroutineUninterceptedOrReturn(cont)
//    }
//
//  suspend fun run(): Either<C, O> {
//    val input = fa.invoke()
//    return try {
//      next(input)
//      // next: suspend SchedEffect<I, O>.(i: I) -> Unit
//      // shouldBe
//      // next: suspend SchedEffect<I, O>.(i: I) -> O
//      // but this makes the API super ugly
//      requireNotNull(last) { "Emit never called..."  }.right()
//    } catch (e: Throwable) {
//      orElse(e.nonFatalOrThrow(), last).left()
//    }
//  }
//}
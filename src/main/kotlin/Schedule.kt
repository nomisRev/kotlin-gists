import Schedule.Decision.Continue
import Schedule.Decision.Done
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.identity
import arrow.core.left
import arrow.core.nonFatalOrThrow
import arrow.core.right
import arrow.core.some
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

typealias Next<Input, Output> =
  suspend (Input) -> Schedule.Decision<Input, Output>

@JvmInline
value class Schedule<Input, Output>(
  val step: Next<Input, Output>
) {

  suspend fun repeat(block: suspend () -> Input): Output =
    repeatOrElse(block) { e, _ -> throw e }

  suspend fun repeatOrElse(
    block: suspend () -> Input,
    orElse: suspend (error: Throwable, output: Output?) -> Output
  ): Output =
    repeatOrElseEither(block, orElse).fold(::identity, ::identity)

  suspend fun <A> repeatOrElseEither(
    block: suspend () -> Input,
    orElse: suspend (error: Throwable, output: Output?) -> A
  ): Either<A, Output> {
    var step: Next<Input, Output> = step
    var state: Option<Output> = None

    while (true) {
      currentCoroutineContext().ensureActive()
      try {
        val a = block.invoke()
        when (val decision = step(a)) {
          is Continue -> {
            if (decision.delay != ZERO) delay(decision.delay)
            state = decision.output.some()
            step = decision.next
          }
          is Done -> return Either.Right(decision.output)
        }
      } catch (e: Throwable) {
        return Either.Left(orElse(e.nonFatalOrThrow(), state.orNull()))
      }
    }
  }

  fun <B> map(transform: suspend (output: Output) -> B): Schedule<Input, B> {
    suspend fun loop(input: Input, self: Next<Input, Output>): Decision<Input, B> =
      when (val decision = self(input)) {
        is Continue -> Continue(transform(decision.output), decision.delay) { loop(it, decision.next) }
        is Done -> Done(transform(decision.output))
      }

    return Schedule { input -> loop(input, step) }
  }

  fun void(): Schedule<Input, Unit> =
    map { Unit }

  fun <B> andThen(other: Schedule<Input, B>): Schedule<Input, Either<Output, B>> =
    andThen(other) { it }

  fun <B, C> andThen(
    other: Schedule<Input, B>,
    transform: suspend (Either<Output, B>) -> C
  ): Schedule<Input, C> {
    suspend fun loop(input: Input, self: Next<Input, B>): Decision<Input, C> =
      when (val decision = self(input)) {
        is Continue -> Continue(transform(decision.output.right()), decision.delay) {
          loop(input, decision.next)
        }
        is Done -> Done(transform(decision.output.right()))
      }

    suspend fun loop(input: Input, self: Next<Input, Output>): Decision<Input, C> =
      when (val decision = self(input)) {
        is Continue -> Continue(transform(decision.output.left()), decision.delay) { loop(it, decision.next) }
        is Done -> Continue(transform(decision.output.left()), ZERO) {
          loop(input, other.step)
        }
      }

    return Schedule { input -> loop(input, step) }
  }

  infix fun <B> pipe(other: Schedule<Output, B>): Schedule<Input, B> {
    suspend fun loop(input: Input, self: Next<Input, Output>, other: Next<Output, B>): Decision<Input, B> =
      when (val decision = self(input)) {
        is Continue -> when (val decision2 = other(decision.output)) {
          is Continue -> Continue(decision2.output, decision.delay + decision2.delay) {
            loop(it, decision.next, decision2.next)
          }
          is Done -> Done(decision2.output)
        }
        is Done -> Done(other(decision.output).output)
      }

    return Schedule { input -> loop(input, step, other.step) }
  }

  fun check(test: suspend (Input, Output) -> Boolean): Schedule<Input, Output> {
    suspend fun loop(input: Input, self: Next<Input, Output>): Decision<Input, Output> =
      when (val decision = self(input)) {
        is Continue ->
          if (test(input, decision.output)) Continue(decision.output, decision.delay) { loop(it, decision.next) }
          else Done(decision.output)
        is Done -> decision
      }

    return Schedule { input -> loop(input, step) }
  }

  fun whileInput(f: suspend (Input) -> Boolean): Schedule<Input, Output> =
    check { input, _ -> f(input) }

  fun whileOutput(f: suspend (Output) -> Boolean): Schedule<Input, Output> =
    check { _, output -> f(output) }

  fun log(f: suspend (Input, Output) -> Unit): Schedule<Input, Output> =
    check { input, output ->
      f(input, output)
      true
    }

  fun delay(f: suspend (Duration) -> Duration): Schedule<Input, Output> =
    delayed { _, duration -> f(duration) }

  fun delayed(f: suspend (Output, Duration) -> Duration): Schedule<Input, Output> {
    suspend fun loop(input: Input, self: Next<Input, Output>): Decision<Input, Output> =
      when (val decision = self(input)) {
        is Continue -> Continue(decision.output, f(decision.output, decision.delay)) { loop(it, decision.next) }
        is Done -> decision
      }

    return Schedule { input -> loop(input, step) }
  }

  fun jittered(
    min: Double = 0.0,
    max: Double = 1.0,
    random: Random = Random.Default
  ): Schedule<Input, Output> =
    delayed { _, duration -> duration * random.nextDouble(min, max) }

  fun mapDecision(f: suspend (Decision<Input, Output>) -> Decision<Input, Output>): Schedule<Input, Output> {
    suspend fun loop(input: Input, self: Next<Input, Output>): Decision<Input, Output> =
      f(self(input))

    return Schedule { input -> loop(input, step) }
  }

  fun collect(): Schedule<Input, List<Output>> =
    fold(emptyList()) { acc, out -> acc + out }

  fun <B> fold(b: B, f: suspend (B, Output) -> B): Schedule<Input, B> {
    suspend fun loop(input: Input, b: B, self: Next<Input, Output>): Decision<Input, B> =
      when (val decision = self(input)) {
        is Continue -> f(b, decision.output).let { b2 ->
          Continue(b2, decision.delay) { loop(it, b2, decision.next) }
        }
        is Done -> Done(f(b, decision.output))
      }

    return Schedule { loop(it, b, step) }
  }

  infix fun <B> zipLeft(other: Schedule<Input, B>): Schedule<Input, Output> =
    and(other) { input, _ -> input }

  infix fun <B> zipRight(other: Schedule<Input, B>): Schedule<Input, B> =
    and(other) { _, b -> b }

  fun <B> and(other: Schedule<Input, B>): Schedule<Input, Pair<Output, B>> =
    and(other, ::Pair)

  fun <B, C> and(
    other: Schedule<Input, B>,
    transform: suspend (output: Output, b: B) -> C
  ) = and(other, transform) { a, b -> maxOf(a, b) }

  fun <B, C> and(
    other: Schedule<Input, B>,
    transform: suspend (output: Output, b: B) -> C,
    combineDuration: suspend (left: Duration, right: Duration) -> Duration
  ): Schedule<Input, C> {
    suspend fun loop(
      input: Input,
      self: Next<Input, Output>,
      that: Next<Input, B>
    ): Decision<Input, C> {
      val left = self(input)
      val right = that(input)
      return if (left is Continue && right is Continue) Continue(
        transform(left.output, right.output),
        combineDuration(left.delay, right.delay)
      ) {
        loop(it, left.next, right.next)
      } else Done(transform(left.output, right.output))
    }

    return Schedule { input ->
      loop(input, step, other.step)
    }
  }

  // LongMethodJail, cries in pattern matching
  fun <B, C> or(
    other: Schedule<Input, B>,
    transform: suspend (output: Output?, b: B?) -> C,
    combineDuration: suspend (left: Duration?, right: Duration?) -> Duration
  ): Schedule<Input, C> {
    suspend fun loop(
      input: Input,
      self: Next<Input, Output>?,
      that: Next<Input, B>?
    ): Decision<Input, C> =
      when (val left = self?.invoke(input)) {
        is Continue -> when (val right = that?.invoke(input)) {
          is Continue -> Continue(
            transform(left.output, right.output),
            combineDuration(left.delay, right.delay)
          ) {
            loop(it, left.next, right.next)
          }
          is Done -> Continue(
            transform(left.output, right.output),
            combineDuration(left.delay, null)
          ) {
            loop(it, left.next, null)
          }
          null -> Continue(
            transform(left.output, null),
            combineDuration(left.delay, null)
          ) {
            loop(it, left.next, null)
          }
        }
        is Done -> when (val right = that?.invoke(input)) {
          is Continue -> Continue(
            transform(left.output, right.output),
            combineDuration(null, right.delay)
          ) {
            loop(it, null, right.next)
          }
          is Done -> Done(transform(left.output, right.output))
          null -> Done(transform(left.output, null))
        }
        null -> when (val right = that?.invoke(input)) {
          is Continue -> Continue(
            transform(null, right.output),
            combineDuration(null, right.delay)
          ) {
            loop(it, null, right.next)
          }
          is Done -> Done(transform(null, right.output))
          null -> Done(transform(null, null))
        }
      }

    return Schedule { input ->
      loop(input, step, other.step)
    }
  }

  companion object {
    fun <A> identity(): Schedule<A, A> {
      suspend fun loop(input: A): Decision<A, A> =
        Continue(input, Duration.ZERO) { loop(it) }

      return Schedule { loop(it) }
    }

    fun <A> spaced(duration: Duration): Schedule<A, Long> {
      fun loop(input: Long): Decision<A, Long> =
        Continue(input, duration) { loop(input + 1) }

      return Schedule { loop(0L) }
    }

    fun <A> fibonacci(one: Duration): Schedule<A, Duration> {
      fun loop(prev: Duration, curr: Duration): Decision<A, Duration> =
        (prev + curr).let { next ->
          Continue(next, next) { loop(curr, next) }
        }

      return Schedule { loop(0.nanoseconds, one) }
    }

    fun <A> linear(base: Duration): Schedule<A, Duration> {
      fun loop(count: Int): Decision<A, Duration> =
        (base * count).let { next ->
          Continue(next, next) { loop(count + 1) }
        }

      return Schedule { loop(0) }
    }

    fun <A> exponential(base: Duration, factor: Double = 2.0): Schedule<A, Duration> {
      fun loop(count: Int): Decision<A, Duration> =
        (base * factor.pow(count)).let { next ->
          Continue(next, next) { loop(count + 1) }
        }

      return Schedule { loop(0) }
    }

    fun <A> recurs(n: Long): Schedule<A, Long> {
      fun loop(input: Long): Decision<A, Long> =
        if (input < n) Continue(input, ZERO) { loop(input + 1) } else Done(input)

      return Schedule { loop(0L) }
    }

    fun <Input> forever(): Schedule<Input, Long> {
      fun loop(input: Long): Decision<Input, Long> =
        Continue(input, ZERO) { loop(input + 1) }

      return Schedule { loop(0L) }
    }

    fun <Input, Output> unfold(initial: Output, next: suspend (Output) -> Output): Schedule<Input, Output> =
      Schedule({ initial }) { _, output ->
        Continue(output, ZERO, next)
      }

    // DSL
    operator fun <I, O> invoke(
      initial: suspend (I) -> O,
      f: suspend ScheduleEffect<I, O>.(input: I, output: O) -> Decision<I, O>
    ): Schedule<I, O> = Schedule { input: I ->
      DefaultScheduleEffect(f).loop(input, initial(input), f)
    }
  }

  sealed interface Decision<in Input, out Output> {
    val output: Output

    data class Done<Output>(override val output: Output) : Decision<Any?, Output>
    data class Continue<in Input, out Output>(
      override val output: Output,
      val delay: Duration,
      val next: Next<Input, Output>
    ) : Decision<Input, Output>
  }

  // DSL WIP for easier writing of combinators
  interface ScheduleEffect<Input, Output> {
    suspend fun Continue(output: Output, delay: Duration): Decision<Input, Output>
    suspend fun Continue(
      output: Output,
      delay: Duration,
      next: suspend (Output) -> Output
    ): Decision<Input, Output>

    suspend fun done(output: Output): Decision<Input, Output>
  }
}

// Impl for DSL
private class DefaultScheduleEffect<I, O>(
  private val f: suspend Schedule.ScheduleEffect<I, O>.(input: I, output: O) -> Schedule.Decision<I, O>
) : Schedule.ScheduleEffect<I, O> {
  override suspend fun Continue(output: O, delay: Duration): Schedule.Decision<I, O> =
    Schedule.Decision.Continue(output, delay) { input -> loop(input, output, f) }

  override suspend fun Continue(output: O, delay: Duration, next: suspend (O) -> O): Schedule.Decision<I, O> =
    Schedule.Decision.Continue(output, delay) { input -> loop(input, next(output), f) }

  override suspend fun done(output: O): Schedule.Decision<I, O> = Done(output)

  suspend fun loop(
    input: I,
    output: O,
    g: suspend Schedule.ScheduleEffect<I, O>.(input: I, output: O) -> Schedule.Decision<I, O>
  ): Schedule.Decision<I, O> =
    when (val decision = g(input, output)) {
      is Continue -> decision.copy { input2 -> loop(input2, decision.output) { input, _ -> decision.next(input) } }
      is Done -> decision
    }
}

suspend fun <A, B> Schedule<Throwable, B>.retry(fa: suspend () -> A): A =
  retryOrElse(fa) { e, _ -> throw e }

suspend fun <A, B> Schedule<Throwable, B>.retryOrElse(
  fa: suspend () -> A,
  orElse: suspend (Throwable, B) -> A
): A =
  retryOrElseEither(fa, orElse).fold(::identity, ::identity)

suspend fun <A, B, C> Schedule<Throwable, B>.retryOrElseEither(
  fa: suspend () -> A,
  orElse: suspend (Throwable, B) -> C
): Either<C, A> {
  var step: Next<Throwable, B> = step

  while (true) {
    currentCoroutineContext().ensureActive()
    try {
      return Either.Right(fa.invoke())
    } catch (e: Throwable) {
      when (val decision = step(e)) {
        is Continue -> {
          if (decision.delay != ZERO) delay(decision.delay)
          step = decision.next
        }
        is Done -> return Either.Left(orElse(e.nonFatalOrThrow(), decision.output))
      }
    }
  }
}

@OptIn(ExperimentalTime::class)
suspend fun main() {
  delay(5000)

  var count = 0
  var start = kotlin.time.TimeSource.Monotonic.markNow()

  Schedule.recurs<Unit>(3) // Warm up
    .andThen(Schedule.fibonacci<Unit>(150.milliseconds) zipRight Schedule.recurs(5))
    .collect()
    .repeat {
      println("${start.elapsedNow()}, ${count++}")
      start = kotlin.time.TimeSource.Monotonic.markNow()
    }.let(::println)

  println("------------------------")

  Schedule.recurs<Unit>(0)
    .repeat { println("Am I gonna print") }

  Schedule.recurs<Throwable>(2).retry {
    println("Never here")
    throw IllegalArgumentException("")
  }
}

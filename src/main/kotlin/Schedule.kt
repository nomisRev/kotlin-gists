import Schedule.Decision.Continue
import Schedule.Decision.Done
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.identity
import arrow.core.nonFatalOrThrow
import arrow.core.some
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

@JvmInline
value class Schedule<Input, Output>(val step: suspend (Input) -> Decision<Input, Output>) {

  suspend fun repeat(fa: suspend () -> Input): Output =
    repeatOrElse(fa) { e, _ -> throw e }

  /**
   * Runs this effect once and, if it succeeded, decide using the provided policy if the effect should be repeated and if so, with how much delay.
   * Also offers a function to handle errors if they are encountered during repetition.
   */
  suspend fun repeatOrElse(fa: suspend () -> Input, orElse: suspend (Throwable, Output?) -> Output): Output =
    repeatOrElseEither(fa, orElse).fold(::identity, ::identity)

  suspend fun <C> repeatOrElseEither(
    fa: suspend () -> Input,
    orElse: suspend (Throwable, Output?) -> C
  ): Either<C, Output> {
    var step: suspend (Input) -> Decision<Input, Output> = step
    var state: Option<Output> = None

    while (true) {
      currentCoroutineContext().ensureActive()
      try {
        val a = fa.invoke()
        when (val decision = step(a)) {
          is Continue -> {
            delay(decision.delay)
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

  fun <B> map(transform: suspend (Output) -> B): Schedule<Input, B> {
    suspend fun loop(input: Input, self: suspend (Input) -> Decision<Input, Output>): Decision<Input, B> =
      when (val decision = self(input)) {
        is Continue -> Continue(transform(decision.output), decision.delay) { input -> loop(input, decision.next) }
        is Done -> Done(transform(decision.output))
      }

    return Schedule { input -> loop(input, step) }
  }

  fun check(test: suspend (Input, Output) -> Boolean): Schedule<Input, Output> {
    suspend fun loop(input: Input, self: suspend (Input) -> Decision<Input, Output>): Decision<Input, Output> =
      when (val decision = self(input)) {
        is Continue ->
          if (test(input, decision.output)) Continue(decision.output, decision.delay) { input ->
            loop(input, decision.next)
          }
          else Done(decision.output)
        is Done -> decision
      }

    return Schedule { input -> loop(input, step) }
  }

  fun whileOutput(test: suspend (Output) -> Boolean): Schedule<Input, Output> =
    check { _, output -> test(output) }

  fun delayed(f: suspend (Output, Duration) -> Duration): Schedule<Input, Output> {
    suspend fun loop(input: Input, self: suspend (Input) -> Decision<Input, Output>): Decision<Input, Output> =
      when (val decision = self(input)) {
        is Continue -> Continue(decision.output, f(decision.output, decision.delay)) { input ->
          loop(input, decision.next)
        }
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
    suspend fun loop(input: Input, self: suspend (Input) -> Decision<Input, Output>): Decision<Input, Output> =
      when (val decision = self(input)) {
        is Continue -> f(decision)
        is Done -> f(decision)
      }

    return Schedule { input -> loop(input, step) }
  }

  fun <B> reconsider(
    f: suspend (Decision<Input, Output>) -> Either<B, Pair<B, Duration>>
  ): Schedule<Input, B> {
    suspend fun loop(input: Input, self: suspend (Input) -> Decision<Input, Output>): Decision<Input, B> =
      when (val decision = self(input)) {
        is Continue -> when (val either = f(decision)) {
          is Either.Left -> Done(either.value)
          is Either.Right -> Continue(either.value.first, either.value.second) { loop(it, decision.next) }
        }
        is Done -> when (val either = f(decision)) {
          is Either.Left -> Done(either.value)
          is Either.Right -> Done(either.value.first)
        }
      }

    return Schedule { input -> loop(input, step) }
  }

  fun collect(): Schedule<Input, List<Output>> =
    fold(emptyList()) { acc, out -> acc + out }

  fun <B> fold(b: B, f: suspend (B, Output) -> B): Schedule<Input, B> {
    suspend fun loop(input: Input, b: B, self: suspend (Input) -> Decision<Input, Output>): Decision<Input, B> =
      when (val decision = self(input)) {
        is Continue -> f(b, decision.output).let { b2 ->
          Continue(b2, decision.delay) { loop(it, b2, decision.next) }
        }
        is Done -> Done(f(b, decision.output))
      }

    return Schedule { loop(it, b, step) }
  }

  companion object {
    fun <A> identity(): Schedule<A, A> {
      suspend fun loop(input: A): Decision<A, A> =
        Continue(input, Duration.ZERO) { loop(it) }

      return Schedule { loop(it) }
    }

    fun <Input> recurs(n: Long): Schedule<Input, Long> =
      forever<Input>().whileOutput { it < n }

    fun <Input> forever(): Schedule<Input, Long> = unfold(0L) { it + 1 }

    fun <Input, Output> unfold(initial: Output, next: suspend (Output) -> Output): Schedule<Input, Output> {
      suspend fun loop(input: Output): Decision<Any?, Output> =
        Continue(input, Duration.ZERO) { loop(next(input)) }

      return Schedule { loop(initial) }
    }

    fun <Input, Output> invoke(f: suspend ScheduleEffect<Input, Output>.(input: Input) -> Decision<Input, Output>): Schedule<Input, Output> {
      val effect = object : ScheduleEffect<Input, Output> {
        override suspend fun Continue(output: Output, delay: Duration): Decision<Input, Output> =
          Continue(output, delay) { input -> loop(input, f) }

        override suspend fun done(output: Output): Decision<Input, Output> = Done(output)

        suspend fun loop(
          input: Input,
          g: suspend ScheduleEffect<Input, Output>.(input: Input) -> Decision<Input, Output>
        ): Decision<Input, Output> =
          when (val decision = g(input)) {
            is Continue -> decision.copy { input2 -> loop(input2) { decision.next(it) } }
            is Done -> decision
          }
      }

      return Schedule { input: Input -> effect.loop(input, f) }
    }
  }

  interface ScheduleEffect<Input, Output> {
    suspend fun Continue(output: Output, delay: Duration): Decision<Input, Output>
    suspend fun done(output: Output): Decision<Input, Output>
  }

  sealed interface Decision<in Input, out Output> {
    val output: Output

    data class Done<Output>(override val output: Output) : Decision<Any?, Output>
    data class Continue<in Input, out Output>(
      override val output: Output,
      val delay: Duration,
      val next: suspend (Input) -> Decision<Input, Output>
    ) : Decision<Input, Output>
  }
}

suspend fun main() {
  var count = 0
  Schedule.forever<Unit>()
    .whileOutput { it < 10 }
    .collect()
    .repeat {
      delay(25.milliseconds)
      println(count++)
    }.let(::println)
}
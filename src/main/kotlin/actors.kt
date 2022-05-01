import arrow.core.identity
import arrow.fx.coroutines.Atomic
import arrow.fx.coroutines.raceN
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class AuthToken(val expiresAt: Instant, val value: String) {
  fun isActive(now: Instant): Boolean = now.isBefore(expiresAt)
}

interface Clock {
  suspend fun now(): Instant

  companion object Default : Clock {
    override suspend fun now(): Instant = Instant.now()
  }
}

// loops `f` forever until error or cancellation
fun CoroutineScope.forever(f: suspend () -> Unit): Job =
  launch {
    while (true) {
      ensureActive()
      f()
    }
  }

// TODO we could abstract over `Atomic`, and plug-in a persistent cache
/** Actor will live as long as the [CoroutineScope] */
fun <S, O> CoroutineScope.actor(
  initialState: S,
  receive: suspend Atomic<S>.() -> O
): suspend () -> O {
  val atomic = Atomic.unsafe(initialState)
  val channel = Channel<CompletableDeferred<O>>(RENDEZVOUS)
  val job = forever {
    val promise = channel.receive()
    val output = receive(atomic)
    promise.complete(output)
  }
  return suspend {
    val promise = CompletableDeferred<O>()
    channel.send(promise)
    val output = raceN({ job.join() }, { promise.await() })
      .fold({ throw RuntimeException("Scope closed.") }, ::identity)
    output
  }
}

fun interface Store<Input, Output> {
  suspend operator fun invoke(input: Input): Output
  suspend fun get(input: Input): Output =
    invoke(input)
}

fun <State, Input, Output> CoroutineScope.actorWithInput(
  initialState: State,
  receive: suspend (Input, Atomic<State>) -> Output
): Store<Input, Output> {
  val atomic = Atomic.unsafe(initialState)
  val channel = Channel<Pair<Input, CompletableDeferred<Output>>>()
  val job = forever {
    val (input, promise) = channel.receive()
    val output = receive(input, atomic)
    promise.complete(output)
  }
  return Store { i: Input ->
    val promise = CompletableDeferred<Output>()
    channel.send(Pair(i, promise))
    val output = raceN({ job.join() }, { promise.await() })
      .fold({ throw RuntimeException("Scope closed.") }, ::identity)
    output
  }
}

fun CoroutineScope.requestActiveAuthToken(
  clock: Clock = Clock.Default
): suspend () -> AuthToken =
  actor<AuthToken?, AuthToken>(initialState = null) {
    val existingToken = get()
    val now = clock.now()
    existingToken
      ?.takeIf { it.isActive(now) } ?: requestNewAuthToken(clock).also { set(it) }
  }

private suspend fun requestNewAuthToken(clock: Clock = Clock.Default): AuthToken =
  AuthToken(clock.now().plusSeconds(3600), "token")

suspend fun main(): Unit = runBlocking {
  val store = requestActiveAuthToken()

  val activeAuthToken = store()
  println(activeAuthToken)
}

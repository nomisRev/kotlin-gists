import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.continuations.resource
import arrow.fx.coroutines.release
import arrow.fx.coroutines.releaseCase
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Database

class MyProcessor private constructor(
  coroutineContext: CoroutineContext,
  database: Database
) {
  private val scope = CoroutineScope(coroutineContext)

  suspend fun close(exitCase: ExitCase) {
    scope.cancel("Closing MyProcessor Scope: $exitCase")
  }

  fun myBackgroundWorker(): Resource<Job> =
    resource {
      scope.launch { delay(Long.MAX_VALUE) }
    } release Job::cancel

  companion object {
    operator fun invoke(coroutineContext: CoroutineContext, database: Database): Resource<MyProcessor> =
      resource { MyProcessor(coroutineContext, database) } releaseCase { p, ex -> p.close(ex) }
  }
}

fun myProcessor(database: Database, coroutineContext: CoroutineContext? = null): Resource<MyProcessor> =
  resource {
    MyProcessor(coroutineContext ?: currentCoroutineContext(), database).bind()
      .also { it.myBackgroundWorker().bind() }
  }

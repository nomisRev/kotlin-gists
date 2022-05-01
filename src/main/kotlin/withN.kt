import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

interface Logging {
  fun info(msg: String): Unit
  companion object Default : Logging {
    override fun info(msg: String) = println("INFO: $msg")
  }
}

interface Tracing {
  suspend fun <A> trace(span: String, f: suspend () -> A): A = f()
  companion object Default : Tracing {
    @OptIn(ExperimentalTime::class)
    override suspend fun <A> trace(span: String, f: suspend () -> A): A {
      println("TRACE: Starting operation $span")
      val (result, duration) = measureTimedValue { f() }
      println("TRACE: Operation $span took $duration")
      return result
    }
  }
}

object SomethingElse

context(Tracing, Logging)
suspend fun program() = trace("program") {
  info("Hello World Context Receivers!")
}


suspend fun main() {
  with(Logging, Tracing) {
    program()
  }
}

inline fun <A, B, R> with(a: A, b: B, block: context(A, B) (TypeWrapper<B>) -> R): R {
  return block(a, b, TypeWrapper.IMPL)
}

sealed interface TypeWrapper<out A> {
  object IMPL: TypeWrapper<Nothing>
}

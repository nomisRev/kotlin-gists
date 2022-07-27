package arrow2

import arrow.core.NonEmptyList
import arrow.core.continuations.EffectScope
import arrow.core.continuations.effect
import arrow.typeclasses.Semigroup

/**
 * In Arrow 2.x.x we want to streamline all data types much further.
 * This file experiments with an `Either` implementation that exposes an API covering both Either and Either use-cases.
 * This would effectively reduce the Arrow Core API by an entire data type.
 *
 * Additionally, we want to remove additional API surface that can be considered "unnecessary".
 * Kotlin prefers elegant DSLs over flatMap/map based code, which is very intrusive in your codebase
 * and introduces accidental complexity.
 * All APIs such as `zip`, `flatMap`, `map`, `tap`, `void`, `replicate`, `all`, `exists`, `findOrNull`, ... are removed.
 *
 * Only a couple of operators exist on `Either`, a couple of base convenience methods.
 * It's extremely tricky to decide what makes sense and what not,
 * some APIs are obviously redundant or not-useful but some just help avoid users write a lot of boilerplate.
 *
 * `mapAccumulating` and `zip` replace the functionality of `Validated`, or `Either + Parallel` from Cats world.
 *
 * Two other data types up for removal are `Const` and `Eval`. They are arguably not useful at all in Kotlin.
 * Then we're only left with:
 *  -`Option` (for generic use-cases)
 *  - Either (also covers Validated)
 *  - Ior
 *  - TupleN (currently up to 22, seems excessive? 9 or 12 might be sufficient)
 *  - Some additional syntax for Iterable, Result, Pair/Triple and other Kotlin Std types
 *
 * I would also love to remove partial application, currying from Arrow Core and replace it with a plugin.
 * It blows up the binary, and is in the eyes of Arrow maintainers not useful in Kotlin since it doesn't fit well into the language.
 * A nice and streamlined library in Arrow 2.0 leaving only the essential stuff, with 1/2 or 1/3 of the API surface of Arrow 1.x with same functionality.
 * This would be amazing considering we already made a huge cut in API surface towards 1.x.x, and it was received extremely well.
 *
 * A separate module could offer `flatMap`/`map`/ etc. for people that really like those APIs, but perhaps we should also not expose/promote it.
 */
sealed class Either<out Error, out Value> {
    data class Right<Value>(val value: Value) : Either<Nothing, Value>()
    data class Left<Error>(val error: Error) : Either<Error, Nothing>()
    
    fun orNull(): Value? = when (this) {
        is Right -> value
        is Left -> null
    }
    
    fun swap(): Either<Value, Error> = when (this) {
        is Left -> Right(error)
        is Right -> Left(value)
    }
    
    inline fun tapLeft(f: (Error) -> Unit): Either<Error, Value> = when (this) {
        is Right -> this
        is Left -> also { f(error) }
    }
    
    inline fun getOrHandle(default: (error: Error) -> @UnsafeVariance Value): Value = when (this) {
        is Right -> value
        is Left -> default(error)
    }
}

// Effect DSL, this can live inside a different module as `EffectScope` if we want to limit `EffectScope` to a kernel module.
suspend fun <Error, Value> either(block: suspend EffectScope<Error>.() -> Value): Either<Error, Value> =
    effect(block).fold({ Either.Left(it) }, { Either.Right(it) })

context(EffectScope<Error>)
        suspend fun <Error, Value> Either<Error, Value>.bind(): Value = when (this) {
    is Either.Left -> shift(error)
    is Either.Right -> value
}
// End Effect DSL

operator fun <A : Comparable<A>, B : Comparable<B>> Either<A, B>.compareTo(other: Either<A, B>): Int =
    when (this) {
        is Either.Left -> when (other) {
            is Either.Left -> error.compareTo(other.error)
            is Either.Right -> -1
        }
        
        is Either.Right -> when (other) {
            is Either.Left -> 1
            is Either.Right -> value.compareTo(other.value)
        }
    }

@PublishedApi
internal fun <T> Iterable<T>.collectionSizeOrDefault(default: Int): Int =
    if (this is Collection<*>) this.size else default

inline fun <Error, A, B> Iterable<A>.mapAccumulating(
    crossinline combine: (first: Error, second: Error) -> Error,
    transform: (A) -> Either<Error, B>,
): Either<Error, List<B>> =
    fold<A, Either<Error, ArrayList<B>>>(Either.Right(ArrayList(collectionSizeOrDefault(10)))) { acc, a ->
        when (val res = transform(a)) {
            is Either.Right -> when (acc) {
                is Either.Right -> acc.also { acc.value.add(res.value) }
                is Either.Left -> acc
            }
            
            is Either.Left -> when (acc) {
                is Either.Right -> res
                is Either.Left -> Either.Left(combine(acc.error, res.error))
            }
        }
    }

inline fun <Error, A, B> Iterable<A>.mapAccumulating(
    semigroup: Semigroup<Error>,
    transform: (A) -> Either<Error, B>,
): Either<Error, List<B>> = with(semigroup) {
    mapAccumulating({ a, b ->
        a.combine(b)
    }, transform)
}

inline fun <Error, A, B> Iterable<A>.mapAccumulating(
    transform: (A) -> Either<NonEmptyList<Error>, B>,
): Either<NonEmptyList<Error>, List<B>> =
    mapAccumulating(NonEmptyList<Error>::plus, transform)

// TODO: missing error handler functions?
// Either<E, A>.catch(block: suspend EagerEffectScope<E>.(E) -> A): Either<E, A>
// => does not support suspension
//
// Alternatives already available:
//
// - `attempt { either.bind() } catch { e: E -> handle(e) }` (both EagerEffect & Effect)
// - Use `when` to recover from `Left` (works for both suspend & non-suspend)

/* Top of zip boilerplate... All zips are accumulating, regular zipping is removed */

@PublishedApi
internal val unit: Either<Nothing, Unit> = Either.Right(Unit)

/**
 * Zip replaces Validated#zip,
 * Either#zip is replaced by either { transform(fa.bind(), fb.bind(), ... ) } which doesn't suffer from arity-n issue.
 */
public fun <E, A, B> Either<E, A>.zip(SE: Semigroup<E>, fb: Either<E, B>): Either<E, Pair<A, B>> =
    zip(SE, fb, ::Pair)

public inline fun <E, A, B, Z> Either<E, A>.zip(
    SE: Semigroup<E>,
    b: Either<E, B>,
    f: (A, B) -> Z,
): Either<E, Z> =
    zip(
        SE,
        b,
        unit,
        unit,
        unit,
        unit,
        unit,
        unit,
        unit,
        unit
    ) { a, b, _, _, _, _, _, _, _, _ ->
        f(a, b)
    }

public inline fun <E, A, B, C, Z> Either<E, A>.zip(
    SE: Semigroup<E>,
    b: Either<E, B>,
    c: Either<E, C>,
    f: (A, B, C) -> Z,
): Either<E, Z> =
    zip(
        SE,
        b,
        c,
        unit,
        unit,
        unit,
        unit,
        unit,
        unit,
        unit
    ) { a, b, c, _, _, _, _, _, _, _ ->
        f(a, b, c)
    }

public inline fun <E, A, B, C, D, Z> Either<E, A>.zip(
    SE: Semigroup<E>,
    b: Either<E, B>,
    c: Either<E, C>,
    d: Either<E, D>,
    f: (A, B, C, D) -> Z,
): Either<E, Z> =
    zip(
        SE,
        b,
        c,
        d,
        unit,
        unit,
        unit,
        unit,
        unit,
        unit
    ) { a, b, c, d, _, _, _, _, _, _ ->
        f(a, b, c, d)
    }

public inline fun <E, A, B, C, D, EE, Z> Either<E, A>.zip(
    SE: Semigroup<E>,
    b: Either<E, B>,
    c: Either<E, C>,
    d: Either<E, D>,
    e: Either<E, EE>,
    f: (A, B, C, D, EE) -> Z,
): Either<E, Z> =
    zip(
        SE,
        b,
        c,
        d,
        e,
        unit,
        unit,
        unit,
        unit,
        unit
    ) { a, b, c, d, e, _, _, _, _, _ ->
        f(a, b, c, d, e)
    }

public inline fun <E, A, B, C, D, EE, FF, Z> Either<E, A>.zip(
    SE: Semigroup<E>,
    b: Either<E, B>,
    c: Either<E, C>,
    d: Either<E, D>,
    e: Either<E, EE>,
    ff: Either<E, FF>,
    f: (A, B, C, D, EE, FF) -> Z,
): Either<E, Z> =
    zip(
        SE,
        b,
        c,
        d,
        e,
        ff,
        unit,
        unit,
        unit,
        unit
    ) { a, b, c, d, e, ff, _, _, _, _ ->
        f(a, b, c, d, e, ff)
    }

public inline fun <E, A, B, C, D, EE, F, G, Z> Either<E, A>.zip(
    SE: Semigroup<E>,
    b: Either<E, B>,
    c: Either<E, C>,
    d: Either<E, D>,
    e: Either<E, EE>,
    ff: Either<E, F>,
    g: Either<E, G>,
    f: (A, B, C, D, EE, F, G) -> Z,
): Either<E, Z> =
    zip(SE, b, c, d, e, ff, g, unit, unit, unit) { a, b, c, d, e, ff, g, _, _, _ ->
        f(a, b, c, d, e, ff, g)
    }

public inline fun <E, A, B, C, D, EE, F, G, H, Z> Either<E, A>.zip(
    SE: Semigroup<E>,
    b: Either<E, B>,
    c: Either<E, C>,
    d: Either<E, D>,
    e: Either<E, EE>,
    ff: Either<E, F>,
    g: Either<E, G>,
    h: Either<E, H>,
    f: (A, B, C, D, EE, F, G, H) -> Z,
): Either<E, Z> =
    zip(SE, b, c, d, e, ff, g, h, unit, unit) { a, b, c, d, e, ff, g, h, _, _ ->
        f(a, b, c, d, e, ff, g, h)
    }

public inline fun <E, A, B, C, D, EE, F, G, H, I, Z> Either<E, A>.zip(
    SE: Semigroup<E>,
    b: Either<E, B>,
    c: Either<E, C>,
    d: Either<E, D>,
    e: Either<E, EE>,
    ff: Either<E, F>,
    g: Either<E, G>,
    h: Either<E, H>,
    i: Either<E, I>,
    f: (A, B, C, D, EE, F, G, H, I) -> Z,
): Either<E, Z> =
    zip(SE, b, c, d, e, ff, g, h, i, unit) { a, b, c, d, e, ff, g, h, i, _ ->
        f(a, b, c, d, e, ff, g, h, i)
    }

public inline fun <E, A, B, C, D, EE, F, G, H, I, J, Z> Either<E, A>.zip(
    SE: Semigroup<E>,
    b: Either<E, B>,
    c: Either<E, C>,
    d: Either<E, D>,
    e: Either<E, EE>,
    ff: Either<E, F>,
    g: Either<E, G>,
    h: Either<E, H>,
    i: Either<E, I>,
    j: Either<E, J>,
    f: (A, B, C, D, EE, F, G, H, I, J) -> Z,
): Either<E, Z> =
    if (this is Either.Right && b is Either.Right && c is Either.Right && d is Either.Right && e is Either.Right && ff is Either.Right && g is Either.Right && h is Either.Right && i is Either.Right && j is Either.Right) {
        Either.Right(f(this.value, b.value, c.value, d.value, e.value, ff.value, g.value, h.value, i.value, j.value))
    } else SE.run {
        var accumulatedError: Any? = EmptyValue
        accumulatedError =
            if (this@zip is Either.Left) this@zip.error else accumulatedError
        accumulatedError =
            if (b is Either.Left) emptyCombine(accumulatedError, b.error) else accumulatedError
        accumulatedError =
            if (c is Either.Left) emptyCombine(accumulatedError, c.error) else accumulatedError
        accumulatedError =
            if (d is Either.Left) emptyCombine(accumulatedError, d.error) else accumulatedError
        accumulatedError =
            if (e is Either.Left) emptyCombine(accumulatedError, e.error) else accumulatedError
        accumulatedError =
            if (ff is Either.Left) emptyCombine(accumulatedError, ff.error) else accumulatedError
        accumulatedError =
            if (g is Either.Left) emptyCombine(accumulatedError, g.error) else accumulatedError
        accumulatedError =
            if (h is Either.Left) emptyCombine(accumulatedError, h.error) else accumulatedError
        accumulatedError =
            if (i is Either.Left) emptyCombine(accumulatedError, i.error) else accumulatedError
        accumulatedError =
            if (j is Either.Left) emptyCombine(accumulatedError, j.error) else accumulatedError
        
        Either.Left(accumulatedError as E)
    }

public inline fun <E, A, B, Z> Either<NonEmptyList<E>, A>.zip(
    b: Either<NonEmptyList<E>, B>,
    f: (A, B) -> Z,
): Either<NonEmptyList<E>, Z> =
    zip(Semigroup.nonEmptyList(), b, f)

public inline fun <E, A, B, C, Z> Either<NonEmptyList<E>, A>.zip(
    b: Either<NonEmptyList<E>, B>,
    c: Either<NonEmptyList<E>, C>,
    f: (A, B, C) -> Z,
): Either<NonEmptyList<E>, Z> =
    zip(Semigroup.nonEmptyList(), b, c, f)

public inline fun <E, A, B, C, D, Z> Either<NonEmptyList<E>, A>.zip(
    b: Either<NonEmptyList<E>, B>,
    c: Either<NonEmptyList<E>, C>,
    d: Either<NonEmptyList<E>, D>,
    f: (A, B, C, D) -> Z,
): Either<NonEmptyList<E>, Z> =
    zip(Semigroup.nonEmptyList(), b, c, d, f)

public inline fun <E, A, B, C, D, EE, Z> Either<NonEmptyList<E>, A>.zip(
    b: Either<NonEmptyList<E>, B>,
    c: Either<NonEmptyList<E>, C>,
    d: Either<NonEmptyList<E>, D>,
    e: Either<NonEmptyList<E>, EE>,
    f: (A, B, C, D, EE) -> Z,
): Either<NonEmptyList<E>, Z> =
    zip(Semigroup.nonEmptyList(), b, c, d, e, f)

public inline fun <E, A, B, C, D, EE, FF, Z> Either<NonEmptyList<E>, A>.zip(
    b: Either<NonEmptyList<E>, B>,
    c: Either<NonEmptyList<E>, C>,
    d: Either<NonEmptyList<E>, D>,
    e: Either<NonEmptyList<E>, EE>,
    ff: Either<NonEmptyList<E>, FF>,
    f: (A, B, C, D, EE, FF) -> Z,
): Either<NonEmptyList<E>, Z> =
    zip(Semigroup.nonEmptyList(), b, c, d, e, ff, f)

public inline fun <E, A, B, C, D, EE, F, G, Z> Either<NonEmptyList<E>, A>.zip(
    b: Either<NonEmptyList<E>, B>,
    c: Either<NonEmptyList<E>, C>,
    d: Either<NonEmptyList<E>, D>,
    e: Either<NonEmptyList<E>, EE>,
    ff: Either<NonEmptyList<E>, F>,
    g: Either<NonEmptyList<E>, G>,
    f: (A, B, C, D, EE, F, G) -> Z,
): Either<NonEmptyList<E>, Z> =
    zip(Semigroup.nonEmptyList(), b, c, d, e, ff, g, f)

public inline fun <E, A, B, C, D, EE, F, G, H, Z> Either<NonEmptyList<E>, A>.zip(
    b: Either<NonEmptyList<E>, B>,
    c: Either<NonEmptyList<E>, C>,
    d: Either<NonEmptyList<E>, D>,
    e: Either<NonEmptyList<E>, EE>,
    ff: Either<NonEmptyList<E>, F>,
    g: Either<NonEmptyList<E>, G>,
    h: Either<NonEmptyList<E>, H>,
    f: (A, B, C, D, EE, F, G, H) -> Z,
): Either<NonEmptyList<E>, Z> =
    zip(Semigroup.nonEmptyList(), b, c, d, e, ff, g, h, f)

public inline fun <E, A, B, C, D, EE, F, G, H, I, Z> Either<NonEmptyList<E>, A>.zip(
    b: Either<NonEmptyList<E>, B>,
    c: Either<NonEmptyList<E>, C>,
    d: Either<NonEmptyList<E>, D>,
    e: Either<NonEmptyList<E>, EE>,
    ff: Either<NonEmptyList<E>, F>,
    g: Either<NonEmptyList<E>, G>,
    h: Either<NonEmptyList<E>, H>,
    i: Either<NonEmptyList<E>, I>,
    f: (A, B, C, D, EE, F, G, H, I) -> Z,
): Either<NonEmptyList<E>, Z> =
    zip(Semigroup.nonEmptyList(), b, c, d, e, ff, g, h, i, f)

public inline fun <E, A, B, C, D, EE, F, G, H, I, J, Z> Either<NonEmptyList<E>, A>.zip(
    b: Either<NonEmptyList<E>, B>,
    c: Either<NonEmptyList<E>, C>,
    d: Either<NonEmptyList<E>, D>,
    e: Either<NonEmptyList<E>, EE>,
    ff: Either<NonEmptyList<E>, F>,
    g: Either<NonEmptyList<E>, G>,
    h: Either<NonEmptyList<E>, H>,
    i: Either<NonEmptyList<E>, I>,
    j: Either<NonEmptyList<E>, J>,
    f: (A, B, C, D, EE, F, G, H, I, J) -> Z,
): Either<NonEmptyList<E>, Z> =
    zip(Semigroup.nonEmptyList(), b, c, d, e, ff, g, h, i, j, f)

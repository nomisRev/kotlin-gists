package arrow2

import arrow.typeclasses.Semigroup

/**
 * This is a work-around for having nested nulls in generic code.
 * This allows for writing faster generic code instead of using `Option`.
 * This is only used as an optimisation technique in low-level code,
 * always prefer to use `Option` in actual business code when needed in generic code.
 */
@PublishedApi
internal object EmptyValue {
    @Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
    public inline fun <A> unbox(value: Any?): A =
        if (value === this) null as A else value as A
}

/**
 * Like [Semigroup.maybeCombine] but for using with [EmptyValue]
 */
@PublishedApi
internal fun <T> Semigroup<T>.emptyCombine(first: Any?, second: T): T =
    if (first == EmptyValue) second else (first as T).combine(second)
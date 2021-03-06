package scientifik.kmath.prob

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scanReduce
import scientifik.kmath.coroutines.mapParallel
import scientifik.kmath.operations.*
import scientifik.kmath.structures.Buffer
import scientifik.kmath.structures.asIterable

/**
 * A function, that transforms a buffer of random quantities to some resulting value
 */
interface Statistic<T, R> {
    suspend operator fun invoke(data: Buffer<T>): R
}

/**
 * A statistic tha could be computed separately on different blocks of data and then composed
 * @param T - source type
 * @param I - intermediate block type
 * @param R - result type
 */
interface ComposableStatistic<T, I, R> : Statistic<T, R> {
    //compute statistic on a single block
    suspend fun computeIntermediate(data: Buffer<T>): I
    //Compose two blocks
    suspend fun composeIntermediate(first: I, second: I): I
    //Transform block to result
    suspend fun toResult(intermediate: I): R

    override suspend fun invoke(data: Buffer<T>): R = toResult(computeIntermediate(data))
}

@FlowPreview
@ExperimentalCoroutinesApi
private fun <T, I, R> ComposableStatistic<T, I, R>.flowIntermediate(
    flow: Flow<Buffer<T>>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<I> = flow
    .mapParallel(dispatcher) { computeIntermediate(it) }
    .scanReduce(::composeIntermediate)


/**
 * Perform a streaming statistical analysis on a chunked data. The computation of inner representation is done in parallel
 * if [dispatcher] allows it.
 *
 * The resulting flow contains values that include the whole previous statistics, not only the last chunk.
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <T, I, R> ComposableStatistic<T, I, R>.flow(
    flow: Flow<Buffer<T>>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<R> = flowIntermediate(flow,dispatcher).map(::toResult)

/**
 * Arithmetic mean
 */
class Mean<T>(val space: Space<T>) : ComposableStatistic<T, Pair<T, Int>, T> {
    override suspend fun computeIntermediate(data: Buffer<T>): Pair<T, Int> =
        space.run { sum(data.asIterable()) } to data.size

    override suspend fun composeIntermediate(first: Pair<T, Int>, second: Pair<T, Int>): Pair<T, Int> =
        space.run { first.first + second.first } to (first.second + second.second)

    override suspend fun toResult(intermediate: Pair<T, Int>): T =
        space.run { intermediate.first / intermediate.second }

    companion object {
        //TODO replace with optimized version which respects overflow
        val real = Mean(RealField)
        val int = Mean(IntRing)
        val long = Mean(LongRing)
    }
}

/**
 * Non-composable median
 */
class Median<T>(comparator: Comparator<T>) : Statistic<T, T> {
    override suspend fun invoke(data: Buffer<T>): T {
        return data.asIterable().toList()[data.size / 2] //TODO check if this is correct
    }

    companion object {
        val real = Median(Comparator { a: Double, b: Double -> a.compareTo(b) })
    }
}
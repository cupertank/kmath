package scientifik.kmath.coroutines

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.*

val Dispatchers.Math: CoroutineDispatcher get() = Dispatchers.Default

/**
 * An imitator of [Deferred] which holds a suspended function block and dispatcher
 */
internal class LazyDeferred<T>(val dispatcher: CoroutineDispatcher, val block: suspend CoroutineScope.() -> T) {
    private var deferred: Deferred<T>? = null

    internal fun start(scope: CoroutineScope) {
        if (deferred == null) {
            deferred = scope.async(dispatcher, block = block)
        }
    }

    suspend fun await(): T = deferred?.await() ?: error("Coroutine not started")
}

class AsyncFlow<T> internal constructor(internal val deferredFlow: Flow<LazyDeferred<T>>) : Flow<T> {
    @InternalCoroutinesApi
    override suspend fun collect(collector: FlowCollector<T>) {
        deferredFlow.collect {
            collector.emit((it.await()))
        }
    }
}

@FlowPreview
fun <T, R> Flow<T>.async(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    block: suspend CoroutineScope.(T) -> R
): AsyncFlow<R> {
    val flow = map {
        LazyDeferred(dispatcher) { block(it) }
    }
    return AsyncFlow(flow)
}

@FlowPreview
fun <T, R> AsyncFlow<T>.map(action: (T) -> R) =
    AsyncFlow(deferredFlow.map { input ->
        //TODO add function composition
        LazyDeferred(input.dispatcher) {
            input.start(this)
            action(input.await())
        }
    })

@ExperimentalCoroutinesApi
@FlowPreview
suspend fun <T> AsyncFlow<T>.collect(concurrency: Int, collector: FlowCollector<T>) {
    require(concurrency >= 1) { "Buffer size should be more than 1, but was $concurrency" }
    coroutineScope {
        //Starting up to N deferred coroutines ahead of time
        val channel = produce(capacity = concurrency - 1) {
            deferredFlow.collect { value ->
                value.start(this@coroutineScope)
                send(value)
            }
        }

        (channel as Job).invokeOnCompletion {
            if (it is CancellationException && it.cause == null) cancel()
        }

        for (element in channel) {
            collector.emit(element.await())
        }

        val producer = channel as Job
        if (producer.isCancelled) {
            producer.join()
            //throw producer.getCancellationException()
        }
    }
}

@ExperimentalCoroutinesApi
@FlowPreview
suspend fun <T> AsyncFlow<T>.collect(concurrency: Int, action: suspend (value: T) -> Unit): Unit {
    collect(concurrency, object : FlowCollector<T> {
        override suspend fun emit(value: T) = action(value)
    })
}

@ExperimentalCoroutinesApi
@FlowPreview
fun <T, R> Flow<T>.mapParallel(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    transform: suspend (T) -> R
): Flow<R> {
    return flatMapMerge{ value ->
        flow { emit(transform(value)) }
    }.flowOn(dispatcher)
}



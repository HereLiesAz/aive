package com.hereliesaz.geministrator

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for a block that may suspend: a cancelled coroutine's [CancellationException] is rethrown instead of being
 * captured as a failure, so cancelling a run still stops it. Every other failure is captured as [runCatching] does.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

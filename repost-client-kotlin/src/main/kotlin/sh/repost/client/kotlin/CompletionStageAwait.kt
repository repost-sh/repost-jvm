package sh.repost.client.kotlin

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import sh.repost.client.SendOperation
import sh.repost.client.SendResult

/**
 * Awaits this send without blocking a thread and propagates coroutine cancellation to the runtime.
 *
 * Cancellation remains a native [CancellationException]. Delivery reconciliation remains available
 * from [SendOperation.outcome] on the original operation.
 *
 * @return the accepted send result
 */
public suspend fun SendOperation.awaitRepost(): SendResult =
    (this as CompletionStage<SendResult>).awaitRepost { cancel(true) }

internal suspend fun <T> CompletionStage<T>.awaitRepost(cancelUnderlying: () -> Unit): T =
    suspendCancellableCoroutine { continuation ->
        val completionDelivered = AtomicBoolean()
        val cancellationPropagated = AtomicBoolean()
        continuation.invokeOnCancellation {
            completionDelivered.compareAndSet(false, true)
            if (cancellationPropagated.compareAndSet(false, true)) {
                try {
                    cancelUnderlying()
                } catch (_: RuntimeException) {
                    // Cancellation must retain the coroutine's native CancellationException.
                }
            }
        }

        try {
            whenComplete { value, failure ->
                if (completionDelivered.compareAndSet(false, true)) {
                    if (failure == null) {
                        continuation.resumeWith(Result.success(value))
                    } else {
                        continuation.resumeFailure(unwrapCompletionFailure(failure))
                    }
                }
            }
        } catch (failure: Throwable) {
            if (completionDelivered.compareAndSet(false, true)) {
                continuation.resumeFailure(unwrapCompletionFailure(failure))
            }
        }
    }

private fun <T> kotlinx.coroutines.CancellableContinuation<T>.resumeFailure(failure: Throwable) {
    if (failure is CancellationException) {
        cancel(failure)
        return
    }
    resumeWith(Result.failure(failure))
}

private fun unwrapCompletionFailure(failure: Throwable): Throwable {
    var current = failure
    while ((current is CompletionException || current is ExecutionException) &&
        current.cause != null && current.cause !== current
    ) {
        current = current.cause!!
    }
    return current
}

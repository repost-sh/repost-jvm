package sh.repost.client.kotlin

import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiConsumer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sh.repost.client.DeliveryState
import sh.repost.client.RepostErrorCode
import sh.repost.client.RepostErrorDetails
import sh.repost.client.SendOperation
import sh.repost.client.SendResult
import sh.repost.client.error.RepostTransportException

class CompletionStageAwaitTest {
    @Test
    fun `completion before cancellation returns the value without cancelling work`() = runBlocking {
        val stage = CompletableFuture.completedFuture("accepted")
        val cancellations = AtomicInteger()

        val value = stage.awaitRepost { cancellations.incrementAndGet() }

        assertEquals("accepted", value)
        assertEquals(0, cancellations.get())
    }

    @Test
    fun `cancellation before completion cancels underlying work once`() = runBlocking {
        val stage = CompletableFuture<String>()
        val cancellations = AtomicInteger()
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            stage.awaitRepost {
                cancellations.incrementAndGet()
                stage.cancel(true)
            }
        }

        deferred.cancel(CancellationException("parent-cancelled"))
        deferred.cancelAndJoin()

        assertTrue(stage.isCancelled)
        assertEquals(1, cancellations.get())
    }

    @Test
    fun `native cancellation and original RepostException are not wrapped`() = runBlocking {
        val nativeCancellation = CancellationException("native")
        val cancelled = CompletableFuture<String>()
        cancelled.completeExceptionally(nativeCancellation)
        val observedCancellation = assertThrows(CancellationException::class.java) {
            runBlocking { cancelled.awaitRepost { } }
        }
        assertEquals(nativeCancellation.message, observedCancellation.message)

        val expected = RepostTransportException(
            RepostErrorDetails.builder(RepostErrorCode.CLOSED, DeliveryState.NOT_SENT).build(),
        )
        val failed = CompletableFuture<String>()
        failed.completeExceptionally(CompletionException(expected))
        val observedFailure = assertThrows(RepostTransportException::class.java) {
            runBlocking { failed.awaitRepost { } }
        }
        assertSame(expected, observedFailure)
    }

    @Test
    fun `simultaneous completion and cancellation settle without a double resume`() = runBlocking {
        repeat(250) { iteration ->
            val stage = CompletableFuture<Int>()
            val cancellations = AtomicInteger()
            val deferred = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                stage.awaitRepost {
                    cancellations.incrementAndGet()
                    stage.cancel(true)
                }
            }
            val barrier = CyclicBarrier(2)
            val completion = CompletableFuture.runAsync {
                barrier.await()
                stage.complete(iteration)
            }
            val cancellation = CompletableFuture.runAsync {
                barrier.await()
                deferred.cancel(CancellationException("race-$iteration"))
            }

            CompletableFuture.allOf(completion, cancellation).join()
            try {
                deferred.await()
            } catch (_: CancellationException) {
                // Either completion or cancellation is an allowed race winner.
            }

            assertTrue(deferred.isCompleted)
            assertTrue(stage.isDone)
            assertTrue(cancellations.get() in 0..1)
        }
    }

    @Test
    fun `public bridge requests interruptible SendOperation cancellation`() = runBlocking {
        val stage = CompletableFuture<SendResult>()
        val mayInterrupt = AtomicReference<Boolean>()
        val operation = Proxy.newProxyInstance(
            SendOperation::class.java.classLoader,
            arrayOf(SendOperation::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "whenComplete" -> {
                    @Suppress("UNCHECKED_CAST")
                    val action = arguments!![0] as BiConsumer<in SendResult, in Throwable>
                    stage.whenComplete(action)
                }
                "cancel" -> {
                    val requested = arguments!![0] as Boolean
                    mayInterrupt.set(requested)
                    stage.cancel(requested)
                }
                else -> error("unexpected SendOperation method: ${method.name}")
            }
        } as SendOperation
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { operation.awaitRepost() }

        deferred.cancel(CancellationException("cancel-proxy"))
        deferred.cancelAndJoin()

        assertEquals(true, mayInterrupt.get())
        assertTrue(stage.isCancelled)
    }
}

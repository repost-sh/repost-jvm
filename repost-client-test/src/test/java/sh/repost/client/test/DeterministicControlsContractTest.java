package sh.repost.client.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import sh.repost.client.TransportHeaderField;
import sh.repost.client.TransportResponse;

final class DeterministicControlsContractTest {
    @Test
    void defaultAndIdempotencyFixturesAreFixedSequencedAndFailing() {
        TestDefaultValueGenerators fixed = TestDefaultValueGenerators.fixed(
                Instant.EPOCH,
                "12345678-1234-4234-9234-123456789abc",
                "abcdefghijklmnopqrstuvwx");
        assertEquals(Instant.EPOCH, fixed.now());
        assertEquals(Instant.EPOCH, fixed.now());

        TestDefaultValueGenerators sequence = TestDefaultValueGenerators.sequence(
                Arrays.asList(Instant.EPOCH, Instant.ofEpochSecond(1)),
                Arrays.asList(
                        "12345678-1234-4234-9234-123456789abc",
                        "22345678-1234-4234-9234-123456789abc"),
                Arrays.asList("abcdefghijklmnopqrstuvwx", "bcdefghijklmnopqrstuvwxy"));
        assertEquals(Instant.EPOCH, sequence.now());
        assertEquals(Instant.ofEpochSecond(1), sequence.now());
        assertThrows(IllegalStateException.class, sequence::now);

        RuntimeException generatorFailure = new IllegalStateException("generator-failure");
        assertEquals(generatorFailure, assertThrows(
                IllegalStateException.class,
                TestDefaultValueGenerators.failing(generatorFailure)::uuid));

        TestIdempotencyKeyGenerator keys = TestIdempotencyKeyGenerator.fixed("fixed-key");
        assertEquals("fixed-key", keys.generate());
        assertEquals("fixed-key", keys.generate());
        RuntimeException keyFailure = new IllegalStateException("key-failure");
        assertEquals(keyFailure, assertThrows(
                IllegalStateException.class,
                TestIdempotencyKeyGenerator.failing(keyFailure)::generate));
    }

    @Test
    void manualExecutorAndSchedulerRunOnlyWhenAdvanced() {
        ManualExecutor executor = new ManualExecutor();
        CompletableFuture<Integer> result = CompletableFuture.supplyAsync(() -> 7, executor);
        assertFalse(result.isDone());
        assertEquals(1, executor.getPendingTaskCount());
        assertTrue(executor.runNext());
        assertEquals(7, result.join());

        ManualScheduler scheduler = new ManualScheduler();
        CompletableFuture<String> scheduled = new CompletableFuture<>();
        scheduler.schedule(() -> scheduled.complete("done"), 5L, TimeUnit.SECONDS);
        assertEquals(1, scheduler.getPendingTaskCount());
        scheduler.advanceBy(Duration.ofSeconds(4));
        assertFalse(scheduled.isDone());
        scheduler.advanceBy(Duration.ofSeconds(1));
        assertEquals("done", scheduled.join());
        assertEquals(0, scheduler.getPendingTaskCount());
    }

    @Test
    void asyncBarriersExposeExactWaitingCountsAndWeights() {
        ConcurrencyBarrier concurrency = new ConcurrencyBarrier();
        CompletableFuture<Void> first = concurrency.arrive().toCompletableFuture();
        CompletableFuture<Void> second = concurrency.arrive().toCompletableFuture();
        assertEquals(2, concurrency.getWaitingCount());
        assertTrue(concurrency.releaseOne());
        assertTrue(first.isDone());
        assertFalse(second.isDone());
        assertEquals(1, concurrency.releaseAll());

        WeightedByteBarrier bytes = new WeightedByteBarrier();
        CompletableFuture<Void> small = bytes.arrive(3L).toCompletableFuture();
        CompletableFuture<Void> large = bytes.arrive(7L).toCompletableFuture();
        assertEquals(2, bytes.getWaitingCount());
        assertEquals(10L, bytes.getWaitingBytes());
        assertTrue(bytes.releaseOne());
        assertTrue(small.isDone());
        assertFalse(large.isDone());
        assertEquals(7L, bytes.getWaitingBytes());
        assertEquals(1, bytes.releaseAll());
    }

    @Test
    void lazyMillionTailStopsAtThePublicHeaderFieldLimit() {
        LazyHeaderFields headers = LazyHeaderFields.of(
                Collections.emptyList(),
                1_000_000,
                TransportHeaderField.of("X-Repost", "value"));

        TransportResponse response = TransportResponse.of(
                202,
                headers,
                new java.io.ByteArrayInputStream(new byte[0]));
        response.close();

        assertEquals(101, headers.getAccessCount());
        assertEquals(100, headers.getHighestAccessedIndex());

        LazyHeaderFields invalidPrefix = LazyHeaderFields.of(
                Arrays.asList(
                        TransportHeaderField.of("X-Valid", "one"),
                        TransportHeaderField.of("Bad Name", "two")),
                1_000_000,
                TransportHeaderField.of("X-Tail", "value"));
        TransportResponse invalidResponse = TransportResponse.of(
                202,
                invalidPrefix,
                new java.io.ByteArrayInputStream(new byte[0]));
        invalidResponse.close();
        assertEquals(2, invalidPrefix.getAccessCount());
        assertEquals(1, invalidPrefix.getHighestAccessedIndex());
    }

    @Test
    void blockingStreamCloseReleasesThePendingReader() throws Exception {
        BlockingInputStream stream = new BlockingInputStream();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> result = reader.submit(() -> {
                return stream.read();
            });
            assertTrue(stream.awaitReadStarted(Duration.ofSeconds(2)));

            stream.close();

            assertEquals(-1, result.get(2, TimeUnit.SECONDS));
            assertEquals(1, stream.getCloseCount());
        } finally {
            reader.shutdownNow();
        }
    }
}

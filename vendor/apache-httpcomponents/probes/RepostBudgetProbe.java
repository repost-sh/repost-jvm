import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hc.core5.repost.RepostBudgetedBodyConsumer;
import org.apache.hc.core5.repost.RepostByteBudget;

public final class RepostBudgetProbe {
    private static final int BODY_BYTES = 1_048_576;
    private static final int CHUNK_BYTES = 16_384;

    private RepostBudgetProbe() {}

    public static void main(String[] arguments) throws Exception {
        exactBodyTransfersOwnershipOnlyAfterRawEnd();
        plusOneCancelsClosesAndReleases();
        everyChunkBoundaryAbortsExactlyOnce();
        arbitraryFragmentsRemainBounded();
        shortLimitUsesOneExactPartialChunk();
        earlyReaderCloseReleasesExactlyOnce();
        concurrentPostTransferCallsRemainSafe();
        producerAbortAndDoubleCloseAreIdempotent();
        ordinaryUpstreamFailuresAreCountedAndIsolated();
        fatalDuringLimitAcceptancePreservesIdentityWithoutSuppression();
        fatalUpstreamFailurePreservesIdentityAndBalancesCleanup();
        reservationCloseAnomalyCannotReplaceCompletedBody();
        nullSourceFailurePreservesIdentityAndBalancesCleanup();
        emptyAndRepeatedCompletionUseOneStream();
        concurrentReservationBound();
        System.out.println("repost-budget-probe:ok");
    }

    private static void exactBodyTransfersOwnershipOnlyAfterRawEnd() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        byte[] expected = new byte[BODY_BYTES];

        for (int chunkIndex = 0; chunkIndex < BODY_BYTES / CHUNK_BYTES; chunkIndex++) {
            byte[] sourceBytes = new byte[CHUNK_BYTES];
            for (int offset = 0; offset < sourceBytes.length; offset++) {
                byte value = (byte) (chunkIndex ^ offset);
                sourceBytes[offset] = value;
                expected[chunkIndex * CHUNK_BYTES + offset] = value;
            }
            ByteBuffer source = ByteBuffer.wrap(sourceBytes);
            consumer.accept(source);
            check(!source.hasRemaining(), "source consumed at chunk " + chunkIndex);
            Arrays.fill(sourceBytes, (byte) 0x5a);
            check(!consumer.isTerminal(), "exact limit cannot complete before raw end");
            check(budget.reservedBytes() == BODY_BYTES, "reservation retained during production");
        }

        check(consumer.consumedBytes() == BODY_BYTES, "exact byte count");
        check(upstream.cancelCalls() == 0, "exact body not cancelled");
        check(upstream.closeCalls() == 0, "upstream remains open before raw end");

        InputStream body = consumer.complete();
        check(body == consumer.complete(), "complete returns one stable stream");
        check(consumer.isTerminal(), "raw end terminalizes producer");
        check(upstream.cancelCalls() == 0, "completion does not cancel");
        check(upstream.closeCalls() == 1, "completion closes upstream once");
        check(budget.reservedBytes() == BODY_BYTES, "body owns reservation after completion");
        consumer.close();
        check(budget.reservedBytes() == BODY_BYTES, "producer close cannot release transferred body");
        check(body.available() == BODY_BYTES, "all completed bytes immediately available");

        byte[] observed = new byte[BODY_BYTES];
        int position = 0;
        while (position < observed.length) {
            int requested = Math.min(7_919, observed.length - position);
            int read = body.read(observed, position, requested);
            check(read > 0, "completed stream never blocks or returns zero");
            position += read;
        }
        check(Arrays.equals(observed, expected), "source mutation cannot alter owned chunks");
        check(budget.reservedBytes() == 0, "EOF consumption releases reservation");
        check(body.read() == -1, "EOF remains stable");
        body.close();
        body.close();
        check(budget.reservedBytes() == 0, "reader double close releases once");
    }

    private static void plusOneCancelsClosesAndReleases() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        ByteBuffer source = ByteBuffer.wrap(new byte[BODY_BYTES + 1]);
        try {
            consumer.accept(source);
            throw new AssertionError("plus one accepted");
        } catch (RepostBudgetedBodyConsumer.ResponseLimitExceededException expected) {
            check(source.remaining() == 1, "plus-one byte remains unconsumed");
            check(consumer.consumedBytes() == BODY_BYTES, "only exact limit copied");
            check(consumer.isTerminal(), "plus-one terminal");
            check(upstream.cancelCalls() == 1, "plus-one cancels once");
            check(upstream.closeCalls() == 1, "plus-one closes once");
            check(budget.reservedBytes() == 0, "plus-one releases once");
        }
        consumer.abort();
        consumer.close();
        check(upstream.cancelCalls() == 1, "post-limit cancel remains once");
        check(upstream.closeCalls() == 1, "post-limit close remains once");
    }

    private static void everyChunkBoundaryAbortsExactlyOnce() throws Exception {
        for (int boundary = 0; boundary <= BODY_BYTES / CHUNK_BYTES; boundary++) {
            RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
            CountingUpstream upstream = new CountingUpstream();
            RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
            for (int index = 0; index < boundary; index++) {
                consumer.accept(ByteBuffer.wrap(new byte[CHUNK_BYTES]));
            }
            consumer.abort();
            consumer.abort();
            consumer.close();
            check(consumer.consumedBytes() == (long) boundary * CHUNK_BYTES,
                    "boundary byte count " + boundary);
            check(upstream.cancelCalls() == 1, "boundary cancel once " + boundary);
            check(upstream.closeCalls() == 1, "boundary close once " + boundary);
            check(budget.reservedBytes() == 0, "boundary release once " + boundary);
        }
    }

    private static void arbitraryFragmentsRemainBounded() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        byte[] fragment = new byte[17];
        int remaining = BODY_BYTES;
        while (remaining > 0) {
            int length = Math.min(fragment.length, remaining);
            ByteBuffer source = ByteBuffer.wrap(fragment, 0, length);
            consumer.accept(source);
            remaining -= length;
        }
        InputStream body = consumer.complete();
        check(body.available() == BODY_BYTES, "tiny fragments coalesce into completed body");
        body.close();
        check(budget.reservedBytes() == 0, "fragmented body release");
    }

    private static void shortLimitUsesOneExactPartialChunk() throws Exception {
        int limit = (2 * CHUNK_BYTES) + 7;
        RepostByteBudget budget = new RepostByteBudget(limit);
        CountingUpstream upstream = new CountingUpstream();
        RepostByteBudget.Reservation reservation = budget.tryReserve(limit);
        check(reservation != null, "short response reservation acquired");
        RepostBudgetedBodyConsumer consumer =
                new RepostBudgetedBodyConsumer(limit, reservation, upstream);
        byte[] source = new byte[limit];
        for (int index = 0; index < source.length; index++) {
            source[index] = (byte) index;
        }
        byte[] expected = source.clone();

        ByteBuffer first = ByteBuffer.wrap(source, 0, CHUNK_BYTES + 3);
        ByteBuffer second = ByteBuffer.wrap(source, CHUNK_BYTES + 3, limit - CHUNK_BYTES - 3);
        consumer.accept(first);
        consumer.accept(second);
        check(!first.hasRemaining() && !second.hasRemaining(), "short fragments consumed exactly");

        byte[][] owned = chunksOf(consumer);
        check(owned.length == 64, "fixed chunk reference ceiling");
        check(owned[0].length == CHUNK_BYTES, "first short-limit chunk is fixed size");
        check(owned[1].length == CHUNK_BYTES, "second short-limit chunk is fixed size");
        check(owned[2].length == 7, "partial final chunk owns only the remaining limit");
        for (int index = 3; index < owned.length; index++) {
            check(owned[index] == null, "short limit cannot materialize an extra chunk");
        }

        Arrays.fill(source, (byte) 0x5a);
        InputStream body = consumer.complete();
        check(chunksOf(consumer) == null, "producer clears chunks after ownership transfer");
        byte[] observed = new byte[limit];
        int position = 0;
        while (position < observed.length) {
            int read = body.read(observed, position, Math.min(313, observed.length - position));
            check(read > 0, "short completed stream remains nonblocking");
            position += read;
        }
        check(Arrays.equals(observed, expected), "short source mutation cannot alter owned bytes");
        check(chunksOf(body) == null, "EOF clears the stream chunk graph");
        check(budget.reservedBytes() == 0, "short EOF releases the exact reservation");
    }

    private static void earlyReaderCloseReleasesExactlyOnce() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        consumer.accept(ByteBuffer.wrap(new byte[CHUNK_BYTES + 3]));
        InputStream body = consumer.complete();
        check(body.read() == 0, "first body byte");
        check(budget.reservedBytes() == BODY_BYTES, "partial reader retains reservation");
        body.close();
        body.close();
        check(body.read() == -1, "closed body is prompt EOF");
        check(body.read(new byte[1], 0, 0) == 0, "zero-length read remains valid");
        check(budget.reservedBytes() == 0, "early reader close releases once");
        check(upstream.cancelCalls() == 0, "raw-complete reader close does not cancel upstream");
        check(upstream.closeCalls() == 1, "raw-complete upstream close remains once");
        check(chunksOf(body) == null, "early close clears the stream chunk graph");
    }

    private static void concurrentPostTransferCallsRemainSafe() throws Exception {
        int length = (2 * CHUNK_BYTES) + 17;
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        byte[] source = new byte[length];
        Arrays.fill(source, (byte) 1);
        consumer.accept(ByteBuffer.wrap(source));
        InputStream body = consumer.complete();
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger bytesRead = new AtomicInteger();
        AtomicInteger byteSum = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        byte[] target = new byte[257];
                        while (true) {
                            int count = body.read(target);
                            if (count == -1) return;
                            check(count > 0, "concurrent read cannot return zero");
                            int sum = 0;
                            for (int offset = 0; offset < count; offset++) sum += target[offset];
                            bytesRead.addAndGet(count);
                            byteSum.addAndGet(sum);
                        }
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }));
            }
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                    check(consumer.complete() == body, "concurrent completion returns the same stream");
                    consumer.close();
                    consumer.abort();
                    try {
                        consumer.accept(ByteBuffer.allocate(0));
                        throw new AssertionError("post-transfer accept succeeded");
                    } catch (IllegalStateException expected) {
                        // The completed stream, not the producer, owns the reservation.
                    } catch (IOException failure) {
                        throw new AssertionError("post-transfer accept changed failure type", failure);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }));

            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            check(executor.awaitTermination(10, TimeUnit.SECONDS), "concurrent readers terminate");
        }
        check(bytesRead.get() == length, "concurrent readers consume each byte once");
        check(byteSum.get() == length, "concurrent readers preserve byte values");
        check(chunksOf(body) == null, "concurrent EOF clears the stream chunk graph");
        check(budget.reservedBytes() == 0, "concurrent EOF releases once");
        check(upstream.cancelCalls() == 0, "post-transfer producer calls cannot cancel upstream");
        check(upstream.closeCalls() == 1, "post-transfer producer calls cannot re-close upstream");
        body.close();
    }

    private static void producerAbortAndDoubleCloseAreIdempotent() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        consumer.accept(ByteBuffer.wrap(new byte[1]));
        consumer.close();
        consumer.close();
        consumer.abort();
        check(upstream.cancelCalls() == 1, "pre-transfer close cancels once");
        check(upstream.closeCalls() == 1, "pre-transfer close closes once");
        check(budget.reservedBytes() == 0, "pre-transfer close releases once");
    }

    private static void ordinaryUpstreamFailuresAreCountedAndIsolated() throws Exception {
        RepostByteBudget completionBudget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream closeFailure = new CountingUpstream();
        closeFailure.closeFailure = new IOException("close sentinel");
        RepostBudgetedBodyConsumer completed = consumer(completionBudget, closeFailure);
        completed.accept(ByteBuffer.wrap(new byte[] {7}));
        InputStream body = completed.complete();
        check(body.read() == 7, "ordinary upstream close failure cannot replace valid body");
        check(completed.upstreamCloseFailureCount() == 1, "close failure counted safely");
        check(completed.upstreamCancelFailureCount() == 0, "no cancel failure on completion");
        check(completionBudget.reservedBytes() == 0, "completion body EOF releases");

        RepostByteBudget abortBudget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream abortFailure = new CountingUpstream();
        abortFailure.cancelFailure = new IllegalStateException("cancel sentinel");
        abortFailure.closeFailure = new IOException("close sentinel");
        RepostBudgetedBodyConsumer aborted = consumer(abortBudget, abortFailure);
        aborted.abort();
        check(aborted.upstreamCancelFailureCount() == 1, "cancel failure counted safely");
        check(aborted.upstreamCloseFailureCount() == 1, "abort close failure counted safely");
        check(abortFailure.cancelCalls() == 1, "failed cancel attempted once");
        check(abortFailure.closeCalls() == 1, "close still attempted after cancel failure");
        check(abortBudget.reservedBytes() == 0, "ordinary cleanup failures cannot leak");
    }

    private static void fatalUpstreamFailurePreservesIdentityAndBalancesCleanup() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        Error fatal = new AssertionError("fatal sentinel");
        upstream.cancelFailure = fatal;
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        forceReservedBytes(budget, 0);
        try {
            consumer.abort();
            throw new AssertionError("fatal cancellation failure ignored");
        } catch (Error observed) {
            check(observed == fatal, "fatal cancellation identity");
            check(fatal.getSuppressed().length == 0, "fatal cancellation remains unmodified");
            check(upstream.cancelCalls() == 1, "fatal cancel attempted once");
            check(upstream.closeCalls() == 1, "close attempted after fatal cancel");
            check(consumer.upstreamCancelFailureCount() == 1, "fatal cancel counted once");
            check(consumer.reservationCloseFailureCount() == 1,
                    "nonfatal reservation anomaly counted once");
            check(budget.reservedBytes() == 0, "fatal cancellation cleanup balances reservation");
        }

        RepostByteBudget completionBudget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream completionUpstream = new CountingUpstream();
        Error completionFatal = new LinkageError("fatal close sentinel");
        completionUpstream.closeFailure = completionFatal;
        RepostBudgetedBodyConsumer completing = consumer(completionBudget, completionUpstream);
        completing.accept(ByteBuffer.wrap(new byte[] {1}));
        try {
            completing.complete();
            throw new AssertionError("fatal completion close ignored");
        } catch (Error observed) {
            check(observed == completionFatal, "fatal completion identity");
            check(completionFatal.getSuppressed().length == 0,
                    "fatal completion remains unmodified");
            check(completing.upstreamCloseFailureCount() == 1, "fatal close counted once");
            check(completing.reservationCloseFailureCount() == 0,
                    "successful fatal cleanup has no reservation anomaly");
            check(completionBudget.reservedBytes() == 0, "fatal completion releases reservation");
        }
    }

    private static void fatalDuringLimitAcceptancePreservesIdentityWithoutSuppression()
            throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        Error cancelFatal = new AssertionError("accept cancel fatal");
        Error closeFatal = new LinkageError("accept close fatal");
        upstream.cancelFailure = cancelFatal;
        upstream.closeFailure = closeFatal;
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        ByteBuffer source = ByteBuffer.wrap(new byte[BODY_BYTES + 1]);
        try {
            consumer.accept(source);
            throw new AssertionError("fatal limit cleanup ignored");
        } catch (Error observed) {
            check(observed == cancelFatal, "accept preserves first fatal identity");
            check(cancelFatal.getSuppressed().length == 0, "accept fatal has no SDK suppression");
            check(closeFatal.getSuppressed().length == 0, "later close fatal remains unmodified");
            check(source.remaining() == 1, "fatal limit cleanup leaves plus-one unconsumed");
            check(upstream.cancelCalls() == 1, "fatal limit cancel once");
            check(upstream.closeCalls() == 1, "fatal limit close once");
            check(consumer.upstreamCancelFailureCount() == 1, "fatal limit cancel counted");
            check(consumer.upstreamCloseFailureCount() == 1, "fatal limit close counted");
            check(consumer.reservationCloseFailureCount() == 0,
                    "fatal limit reservation closes normally");
            check(budget.reservedBytes() == 0, "fatal limit cleanup releases reservation");
        }
    }

    private static void reservationCloseAnomalyCannotReplaceCompletedBody() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        consumer.accept(ByteBuffer.wrap(new byte[] {9}));
        InputStream body = consumer.complete();
        forceReservedBytes(budget, 0);
        check(body.read() == 9, "nonfatal reservation anomaly cannot replace final body byte");
        check(body.read() == -1, "body remains stable EOF after reservation anomaly");
        body.close();
        check(consumer.reservationCloseFailureCount() == 1,
                "completed-body reservation anomaly counted once");
        check(budget.reservedBytes() == 0, "reservation anomaly restores nonnegative budget");
    }

    private static void nullSourceFailurePreservesIdentityAndBalancesCleanup() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        try {
            consumer.accept(null);
            throw new AssertionError("null source accepted");
        } catch (NullPointerException expected) {
            check(consumer.isTerminal(), "null source failure terminal");
            check(upstream.cancelCalls() == 1, "null source failure cancels");
            check(upstream.closeCalls() == 1, "null source failure closes");
            check(budget.reservedBytes() == 0, "null source failure releases");
        }
    }

    private static void emptyAndRepeatedCompletionUseOneStream() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(BODY_BYTES);
        CountingUpstream upstream = new CountingUpstream();
        RepostBudgetedBodyConsumer consumer = consumer(budget, upstream);
        InputStream first = consumer.complete();
        InputStream second = consumer.complete();
        check(first == second, "empty repeated completion stable stream");
        check(budget.reservedBytes() == BODY_BYTES, "empty body retains until EOF observation");
        check(first.read() == -1, "empty body EOF");
        check(budget.reservedBytes() == 0, "empty EOF releases");
        check(second.read() == -1, "repeated stream remains EOF");
        first.close();
        second.close();
        check(upstream.closeCalls() == 1, "repeated completion closes upstream once");
    }

    private static RepostBudgetedBodyConsumer consumer(
            RepostByteBudget budget,
            CountingUpstream upstream) {
        RepostByteBudget.Reservation reservation = budget.tryReserve(BODY_BYTES);
        check(reservation != null, "response reservation acquired");
        return new RepostBudgetedBodyConsumer(BODY_BYTES, reservation, upstream);
    }

    private static void forceReservedBytes(RepostByteBudget budget, long bytes) throws Exception {
        Field field = RepostByteBudget.class.getDeclaredField("reserved");
        field.setAccessible(true);
        ((AtomicLong) field.get(budget)).set(bytes);
    }

    private static byte[][] chunksOf(Object owner) throws Exception {
        Field field = owner.getClass().getDeclaredField("chunks");
        field.setAccessible(true);
        return (byte[][]) field.get(owner);
    }

    private static void concurrentReservationBound() throws Exception {
        RepostByteBudget budget = new RepostByteBudget(1_024);
        ExecutorService executor = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(64);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        for (int index = 0; index < 64; index++) {
            executor.execute(() -> {
                try {
                    start.await();
                    RepostByteBudget.Reservation reservation = budget.tryReserve(64);
                    if (reservation != null) {
                        successes.incrementAndGet();
                        attempted.countDown();
                        release.await();
                        reservation.close();
                    } else {
                        attempted.countDown();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
        }
        start.countDown();
        check(attempted.await(10, TimeUnit.SECONDS), "reservation attempts complete");
        check(successes.get() == 16, "exact capacity winners");
        check(budget.reservedBytes() == 1_024, "concurrent bound");
        release.countDown();
        executor.shutdown();
        check(executor.awaitTermination(10, TimeUnit.SECONDS), "executor terminated");
        check(budget.reservedBytes() == 0, "concurrent release");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class CountingUpstream
            implements RepostBudgetedBodyConsumer.UpstreamControl {
        private int cancelCalls;
        private int closeCalls;
        private Throwable cancelFailure;
        private Throwable closeFailure;

        @Override
        public void cancel() throws IOException {
            cancelCalls++;
            throwConfigured(cancelFailure);
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            throwConfigured(closeFailure);
        }

        int cancelCalls() {
            return cancelCalls;
        }

        int closeCalls() {
            return closeCalls;
        }

        private static void throwConfigured(Throwable failure) throws IOException {
            if (failure instanceof IOException) throw (IOException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
        }
    }
}

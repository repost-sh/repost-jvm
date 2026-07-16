package sh.repost.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public final class TransportValueContractTest {
    @Test
    void preservesImmutableOrderedRequestData() throws Exception {
        byte[] body = new byte[] {1, 2, 3};
        List<TransportHeaderField> fields = Arrays.asList(
                TransportHeaderField.of("X-Test", "one"),
                TransportHeaderField.of("x-test", "two"));
        TransportRequest request = new TransportRequest(
                URI.create("https://api.repost.sh/v1/messages"),
                fields,
                ByteBuffer.wrap(body),
                2,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));

        body[0] = 9;
        assertEquals((byte) 1, request.getBody().get());
        assertTrue(request.getBody().isReadOnly());
        assertEquals("X-Test", request.getHeaderFields().get(0).getName());
        assertEquals("x-test", request.getHeaderFields().get(1).getName());
        expectThrows(UnsupportedOperationException.class,
                () -> request.getHeaderFields().add(TransportHeaderField.of("x", "y")));
    }

    @Test
    void redactsHeaderRendering() {
        TransportHeaderField field = TransportHeaderField.of("Authorization", "Bearer secret");
        assertEquals("TransportHeaderField[REDACTED]", field.toString());
        assertFalse(field.toString().contains("Authorization"));
        assertFalse(field.toString().contains("secret"));
    }

    @Test
    void snapshotsValidResponseHeadersWithOneIterator() {
        AtomicInteger iteratorCalls = new AtomicInteger();
        AtomicInteger advances = new AtomicInteger();
        List<TransportHeaderField> hostile = hostileList(
                Arrays.asList(
                        TransportHeaderField.of("X-Test", "one"),
                        TransportHeaderField.of("x-test", "two")),
                iteratorCalls,
                advances);

        TransportResponse response = TransportResponse.of(
                200, hostile, new ByteArrayInputStream(new byte[0]));
        assertEquals(1, iteratorCalls.get());
        assertEquals(2, advances.get());
        assertEquals(2, response.getHeaderFields().size());
        assertFalse(response.hasHeaderFailure());
        response.close();
    }

    @Test
    void stopsAtTheFirstResponseHeaderLimitBreach() {
        AtomicInteger iteratorCalls = new AtomicInteger();
        AtomicInteger advances = new AtomicInteger();
        List<TransportHeaderField> firstHundred = Collections.nCopies(
                100, TransportHeaderField.of("X", "v"));
        List<TransportHeaderField> millionTail = new AbstractList<TransportHeaderField>() {
            @Override
            public Iterator<TransportHeaderField> iterator() {
                iteratorCalls.incrementAndGet();
                return new Iterator<TransportHeaderField>() {
                    private int index;

                    @Override
                    public boolean hasNext() {
                        return index < 1_000_000;
                    }

                    @Override
                    public TransportHeaderField next() {
                        advances.incrementAndGet();
                        int current = index++;
                        return current < firstHundred.size()
                                ? firstHundred.get(current)
                                : TransportHeaderField.of("X", "tail");
                    }
                };
            }

            @Override
            public TransportHeaderField get(int index) {
                throw new AssertionError("indexed access is forbidden");
            }

            @Override
            public int size() {
                throw new AssertionError("size access is forbidden");
            }
        };

        TransportResponse response = TransportResponse.of(
                200, millionTail, new ByteArrayInputStream(new byte[0]));
        assertEquals(1, iteratorCalls.get());
        assertEquals(101, advances.get());
        assertEquals(TransportResponse.HeaderFailureKind.LIMIT, response.getHeaderFailureKind());
        assertEquals(101, response.getObservedHeaderFields());
        assertEquals(100, response.getHeaderFields().size());
        for (int index = 0; index < 100; index++) {
            assertSame(firstHundred.get(index), response.getHeaderFields().get(index));
        }
        response.close();
    }

    @Test
    void retainsTheExactValidPrefixOnProtocolFailure() {
        TransportHeaderField first = TransportHeaderField.of("X-First", "one");
        TransportHeaderField second = TransportHeaderField.of("X-Second", "two");
        TransportResponse response = TransportResponse.of(
                200,
                Arrays.asList(first, second, TransportHeaderField.of("invalid name", "three")),
                new ByteArrayInputStream(new byte[0]));
        assertEquals(TransportResponse.HeaderFailureKind.PROTOCOL,
                response.getHeaderFailureKind());
        assertEquals(2, response.getHeaderFields().size());
        assertSame(first, response.getHeaderFields().get(0));
        assertSame(second, response.getHeaderFields().get(1));
        response.close();
    }

    @Test
    void closesTransferredBodiesOnStructuralFailure() {
        CountingInputStream body = new CountingInputStream();
        expectThrows(IllegalArgumentException.class,
                () -> TransportResponse.of(199, Collections.emptyList(), body));
        assertEquals(1, body.closeCalls.get());
    }

    @Test
    void preservesPrimaryFatalIdentityAcrossFactoryAndCloseFailures() {
        AssertionError primary = new AssertionError("primary-fatal");
        LinkageError closeFatal = new LinkageError("close-fatal");
        InputStream body = new ThrowingCloseInputStream(closeFatal);
        List<TransportHeaderField> headers = throwingIteratorList(primary);
        Throwable thrown = captureThrows(() -> TransportResponse.of(200, headers, body));
        assertSame(primary, thrown);
        assertEquals(null, thrown.getCause());
        assertEquals(0, thrown.getSuppressed().length);

        LinkageError fatalAfterNonfatal = new LinkageError("close-fatal-after-nonfatal");
        Throwable closeThrown = captureThrows(() -> TransportResponse.of(
                199,
                Collections.emptyList(),
                new ThrowingCloseInputStream(fatalAfterNonfatal)));
        assertSame(fatalAfterNonfatal, closeThrown);
        assertEquals(null, closeThrown.getCause());
        assertEquals(0, closeThrown.getSuppressed().length);

        AssertionError primaryWithNonfatalClose = new AssertionError("primary-with-nonfatal-close");
        Throwable retained = captureThrows(() -> TransportResponse.of(
                200,
                throwingIteratorList(primaryWithNonfatalClose),
                new ThrowingCloseInputStream(new IOException("nonfatal-close"))));
        assertSame(primaryWithNonfatalClose, retained);
    }

    @Test
    void closesAcquiredBodiesExactlyOnce() {
        CountingInputStream body = new CountingInputStream();
        TransportResponse response = TransportResponse.of(200, Collections.emptyList(), body);
        response.close();
        response.close();
        assertEquals(1, body.closeCalls.get());
    }

    private static List<TransportHeaderField> hostileList(
            List<TransportHeaderField> values,
            AtomicInteger iteratorCalls,
            AtomicInteger advances) {
        return new AbstractList<TransportHeaderField>() {
            @Override
            public Iterator<TransportHeaderField> iterator() {
                iteratorCalls.incrementAndGet();
                Iterator<TransportHeaderField> delegate = values.iterator();
                return new Iterator<TransportHeaderField>() {
                    @Override
                    public boolean hasNext() {
                        return delegate.hasNext();
                    }

                    @Override
                    public TransportHeaderField next() {
                        advances.incrementAndGet();
                        return delegate.next();
                    }
                };
            }

            @Override
            public TransportHeaderField get(int index) {
                throw new AssertionError("indexed access is forbidden");
            }

            @Override
            public int size() {
                throw new AssertionError("size access is forbidden");
            }
        };
    }

    private static List<TransportHeaderField> throwingIteratorList(Throwable throwable) {
        return new AbstractList<TransportHeaderField>() {
            @Override
            public Iterator<TransportHeaderField> iterator() {
                return new Iterator<TransportHeaderField>() {
                    @Override public boolean hasNext() { return true; }

                    @Override
                    public TransportHeaderField next() {
                        if (throwable instanceof Error) {
                            throw (Error) throwable;
                        }
                        throw (RuntimeException) throwable;
                    }
                };
            }

            @Override public TransportHeaderField get(int index) { throw new AssertionError(); }
            @Override public int size() { throw new AssertionError(); }
        };
    }

    private static final class CountingInputStream extends InputStream {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
        }
    }

    private static final class ThrowingCloseInputStream extends InputStream {
        private final Throwable closeFailure;

        private ThrowingCloseInputStream(Throwable closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override public int read() { return -1; }

        @Override
        public void close() throws IOException {
            if (closeFailure instanceof Error) {
                throw (Error) closeFailure;
            }
            throw (IOException) closeFailure;
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("expected false");
        }
    }

    private static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionError("expected identical objects");
        }
    }

    private static Throwable captureThrows(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            return throwable;
        }
        throw new AssertionError("expected throwable");
    }

    private static <T extends Throwable> void expectThrows(Class<T> type, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError("expected " + type.getName() + " but got " + throwable, throwable);
        }
        throw new AssertionError("expected " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

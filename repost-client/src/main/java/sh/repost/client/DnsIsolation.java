package sh.repost.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashMap;

/** Bounded, cache-owning isolation boundary around a borrowed synchronous resolver. */
final class DnsIsolation implements AutoCloseable {
    private static final int MAX_ADDRESSES = 64;
    private static final long POSITIVE_TTL_NANOS = Duration.ofSeconds(60).toNanos();
    private static final long NEGATIVE_TTL_NANOS = Duration.ofSeconds(10).toNanos();

    private final Object lock = new Object();
    private final DnsResolver resolver;
    private final MonotonicClock clock;
    private final ThreadPoolExecutor executor;
    private final Semaphore capacity;
    private final Map<String, Lookup> inFlight = new HashMap<>();
    private final Map<String, CacheEntry> cache = new HashMap<>();
    private boolean closed;
    private long epoch;

    DnsIsolation(DnsResolver resolver, int totalCapacity, MonotonicClock clock) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (totalCapacity < 1) {
            throw new IllegalArgumentException("totalCapacity must be positive");
        }
        int workers = Math.min(2, totalCapacity);
        this.capacity = new Semaphore(totalCapacity);
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(totalCapacity),
                dnsThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    CompletableFuture<List<InetAddress>> resolve(
            String asciiHost,
            Duration timeout,
            ScheduledExecutorService timer) {
        Objects.requireNonNull(asciiHost, "asciiHost");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(timer, "timer");
        if (asciiHost.isEmpty()) {
            throw new IllegalArgumentException("asciiHost must not be empty");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            return failed(DnsLookupFailure.Kind.TIMEOUT);
        }

        LookupFuture result = new LookupFuture(this);
        ScheduledFuture<?> timeoutTask;
        try {
            timeoutTask = timer.schedule(
                    () -> expire(result), timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException failure) {
            return failed(DnsLookupFailure.Kind.OVERLOADED);
        }
        result.attachTimeout(timeoutTask);
        if (result.isDone()) {
            return result;
        }

        Lookup lookupToSubmit = null;
        CacheEntry cached = null;
        DnsLookupFailure.Kind immediateFailure = null;
        synchronized (lock) {
            if (result.isDone()) {
                return result;
            }
            if (closed) {
                immediateFailure = DnsLookupFailure.Kind.CLOSED;
            } else {
                long now;
                try {
                    now = clock.nanoTime();
                } catch (RuntimeException failure) {
                    now = 0L;
                    immediateFailure = DnsLookupFailure.Kind.CLOCK;
                }
                if (immediateFailure == null) {
                    CacheEntry candidate = cache.get(asciiHost);
                    if (candidate != null && candidate.isFresh(now)) {
                        cached = candidate;
                    } else {
                        if (candidate != null) {
                            cache.remove(asciiHost);
                        }
                        Lookup lookup = inFlight.get(asciiHost);
                        if (lookup == null) {
                            if (capacity.tryAcquire()) {
                                lookup = new Lookup(asciiHost, epoch);
                                inFlight.put(asciiHost, lookup);
                                lookupToSubmit = lookup;
                            } else {
                                immediateFailure = DnsLookupFailure.Kind.OVERLOADED;
                            }
                        }
                        if (lookup != null) {
                            lookup.add(result);
                        }
                    }
                }
            }
        }

        if (cached != null) {
            if (cached.failureKind == null) {
                result.succeed(cached.addresses);
            } else {
                result.fail(cached.failureKind);
            }
        } else if (immediateFailure != null) {
            result.fail(immediateFailure);
        } else if (lookupToSubmit != null) {
            submit(lookupToSubmit);
        }
        return result;
    }

    private void submit(Lookup lookup) {
        synchronized (lock) {
            if (lookup.abandoned || closed || inFlight.get(lookup.host) != lookup) {
                releaseCapacity(lookup);
                return;
            }
        }
        ProviderTask providerTask = new ProviderTask(lookup);
        try {
            executor.execute(providerTask);
        } catch (RejectedExecutionException failure) {
            providerTask.cancel(false);
            failSubmission(lookup);
            return;
        }
        synchronized (lock) {
            lookup.providerTask = providerTask;
            if (lookup.abandoned || closed || inFlight.get(lookup.host) != lookup) {
                removeAndCancel(providerTask);
            }
        }
    }

    private void failSubmission(Lookup lookup) {
        List<LookupFuture> waiters = Collections.emptyList();
        DnsLookupFailure.Kind failureKind;
        synchronized (lock) {
            if (inFlight.get(lookup.host) != lookup) {
                return;
            }
            inFlight.remove(lookup.host);
            lookup.abandoned = true;
            waiters = lookup.detachWaiters();
            failureKind = closed
                    ? DnsLookupFailure.Kind.CLOSED : DnsLookupFailure.Kind.OVERLOADED;
        }
        failAll(waiters, failureKind);
    }

    private void run(Lookup lookup) {
        LookupResult outcome;
        try {
            outcome = LookupResult.positive(snapshot(resolver.resolve(lookup.host)));
        } catch (UnknownHostException failure) {
            outcome = LookupResult.failure(DnsLookupFailure.Kind.NOT_FOUND, true);
        } catch (RuntimeException failure) {
            outcome = LookupResult.failure(DnsLookupFailure.Kind.PROVIDER, false);
        } catch (Error failure) {
            outcome = LookupResult.error(failure);
        }

        long completionNanos;
        try {
            completionNanos = clock.nanoTime();
        } catch (RuntimeException failure) {
            outcome = LookupResult.failure(DnsLookupFailure.Kind.CLOCK, false);
            completionNanos = 0L;
        }
        releaseCapacity(lookup);
        finish(lookup, outcome, completionNanos);
    }

    private void releaseCapacity(Lookup lookup) {
        synchronized (lock) {
            if (!lookup.capacityHeld) {
                return;
            }
            lookup.capacityHeld = false;
            capacity.release();
        }
    }

    private void finish(Lookup lookup, LookupResult outcome, long completionNanos) {
        List<LookupFuture> waiters;
        synchronized (lock) {
            if (inFlight.get(lookup.host) != lookup) {
                return;
            }
            inFlight.remove(lookup.host);
            if (closed || lookup.epoch != epoch || lookup.abandoned) {
                waiters = lookup.detachWaiters();
                outcome = LookupResult.failure(DnsLookupFailure.Kind.CLOSED, false);
            } else {
                waiters = lookup.detachWaiters();
                if (outcome.cacheable) {
                    long ttl = outcome.failureKind == DnsLookupFailure.Kind.NOT_FOUND
                            ? NEGATIVE_TTL_NANOS : POSITIVE_TTL_NANOS;
                    cache.put(lookup.host, new CacheEntry(
                            outcome.addresses,
                            outcome.failureKind,
                            completionNanos,
                            ttl));
                }
            }
        }
        if (outcome.error != null) {
            for (LookupFuture waiter : waiters) {
                waiter.fail(outcome.error);
            }
        } else if (outcome.failureKind != null) {
            failAll(waiters, outcome.failureKind);
        } else {
            for (LookupFuture waiter : waiters) {
                waiter.succeed(outcome.addresses);
            }
        }
    }

    private void expire(LookupFuture waiter) {
        Lookup lookup;
        Future<?> providerTask = null;
        synchronized (lock) {
            if (waiter.isDone()) {
                return;
            }
            lookup = waiter.lookup;
            if (lookup != null) {
                lookup.waiters.remove(waiter);
                waiter.lookup = null;
                if (lookup.waiters.isEmpty()) {
                    lookup.abandoned = true;
                    inFlight.remove(lookup.host, lookup);
                    providerTask = lookup.providerTask;
                }
            }
        }
        waiter.fail(DnsLookupFailure.Kind.TIMEOUT);
        if (providerTask != null) {
            removeAndCancel(providerTask);
        }
    }

    private void cancel(LookupFuture waiter) {
        Future<?> providerTask = null;
        synchronized (lock) {
            Lookup lookup = waiter.lookup;
            if (lookup == null) {
                return;
            }
            lookup.waiters.remove(waiter);
            waiter.lookup = null;
            if (lookup.waiters.isEmpty()) {
                lookup.abandoned = true;
                inFlight.remove(lookup.host, lookup);
                providerTask = lookup.providerTask;
            }
        }
        if (providerTask != null) {
            removeAndCancel(providerTask);
        }
    }

    @Override
    public void close() {
        ArrayList<LookupFuture> waiters = new ArrayList<>();
        ArrayList<Future<?>> tasks = new ArrayList<>();
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            epoch++;
            cache.clear();
            for (Lookup lookup : inFlight.values()) {
                lookup.abandoned = true;
                waiters.addAll(lookup.detachWaiters());
                if (lookup.providerTask != null) {
                    tasks.add(lookup.providerTask);
                }
            }
            inFlight.clear();
        }
        failAll(waiters, DnsLookupFailure.Kind.CLOSED);
        for (Future<?> task : tasks) {
            removeAndCancel(task);
        }
        executor.shutdownNow();
    }

    private void removeAndCancel(Future<?> task) {
        if (task instanceof Runnable) {
            executor.remove((Runnable) task);
        }
        task.cancel(true);
    }

    private static List<InetAddress> snapshot(List<InetAddress> source) {
        if (source == null) {
            throw new IllegalArgumentException("resolver result must not be null");
        }
        LinkedHashSet<InetAddress> unique = new LinkedHashSet<>();
        Iterator<InetAddress> iterator = source.iterator();
        int observed = 0;
        while (iterator.hasNext()) {
            InetAddress address = iterator.next();
            if (address == null || ++observed > MAX_ADDRESSES) {
                throw new IllegalArgumentException("resolver result is invalid");
            }
            unique.add(address);
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("resolver result must not be empty");
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    private static void failAll(
            List<LookupFuture> waiters,
            DnsLookupFailure.Kind failureKind) {
        for (LookupFuture waiter : waiters) {
            waiter.fail(failureKind);
        }
    }

    private static CompletableFuture<List<InetAddress>> failed(
            DnsLookupFailure.Kind failureKind) {
        CompletableFuture<List<InetAddress>> result = new CompletableFuture<>();
        result.completeExceptionally(new DnsLookupFailure(failureKind));
        return result;
    }

    private static ThreadFactory dnsThreadFactory() {
        return new ThreadFactory() {
            private final AtomicLong sequence = new AtomicLong();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable);
                thread.setName("repost-dns-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                return thread;
            }
        };
    }

    private static final class Lookup {
        private final String host;
        private final long epoch;
        private final ArrayList<LookupFuture> waiters = new ArrayList<>();
        private Future<?> providerTask;
        private volatile boolean providerStarted;
        private boolean capacityHeld = true;
        private boolean abandoned;

        private Lookup(String host, long epoch) {
            this.host = host;
            this.epoch = epoch;
        }

        private void add(LookupFuture waiter) {
            waiters.add(waiter);
            waiter.lookup = this;
        }

        private List<LookupFuture> detachWaiters() {
            ArrayList<LookupFuture> result = new ArrayList<>(waiters);
            waiters.clear();
            for (LookupFuture waiter : result) {
                waiter.lookup = null;
            }
            return result;
        }
    }

    private final class ProviderTask extends FutureTask<Void> {
        private final Lookup lookup;

        private ProviderTask(Lookup lookup) {
            super(() -> {
                DnsIsolation.this.run(lookup);
                return null;
            });
            this.lookup = lookup;
        }

        @Override
        public void run() {
            lookup.providerStarted = true;
            try {
                super.run();
            } finally {
                releaseCapacity(lookup);
            }
        }

        @Override
        protected void done() {
            if (isCancelled() && !lookup.providerStarted) {
                releaseCapacity(lookup);
            }
        }
    }

    private static final class LookupFuture extends CompletableFuture<List<InetAddress>> {
        private final DnsIsolation owner;
        private volatile ScheduledFuture<?> timeoutTask;
        private Lookup lookup;

        private LookupFuture(DnsIsolation owner) {
            this.owner = owner;
        }

        private void attachTimeout(ScheduledFuture<?> task) {
            timeoutTask = task;
            if (isDone()) {
                task.cancel(false);
            }
        }

        private void succeed(List<InetAddress> addresses) {
            cancelTimeout();
            complete(addresses);
        }

        private void fail(DnsLookupFailure.Kind failureKind) {
            fail(new DnsLookupFailure(failureKind));
        }

        private void fail(Throwable failure) {
            cancelTimeout();
            completeExceptionally(failure);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                cancelTimeout();
                owner.cancel(this);
            }
            return cancelled;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private static final class CacheEntry {
        private final List<InetAddress> addresses;
        private final DnsLookupFailure.Kind failureKind;
        private final long completedAtNanos;
        private final long ttlNanos;

        private CacheEntry(
                List<InetAddress> addresses,
                DnsLookupFailure.Kind failureKind,
                long completedAtNanos,
                long ttlNanos) {
            this.addresses = addresses;
            this.failureKind = failureKind;
            this.completedAtNanos = completedAtNanos;
            this.ttlNanos = ttlNanos;
        }

        private boolean isFresh(long nowNanos) {
            long elapsed = nowNanos - completedAtNanos;
            return elapsed >= 0L && elapsed < ttlNanos;
        }
    }

    private static final class LookupResult {
        private final List<InetAddress> addresses;
        private final DnsLookupFailure.Kind failureKind;
        private final boolean cacheable;
        private final Error error;

        private LookupResult(
                List<InetAddress> addresses,
                DnsLookupFailure.Kind failureKind,
                boolean cacheable,
                Error error) {
            this.addresses = addresses;
            this.failureKind = failureKind;
            this.cacheable = cacheable;
            this.error = error;
        }

        private static LookupResult positive(List<InetAddress> addresses) {
            return new LookupResult(addresses, null, true, null);
        }

        private static LookupResult failure(
                DnsLookupFailure.Kind failureKind,
                boolean cacheable) {
            return new LookupResult(null, failureKind, cacheable, null);
        }

        private static LookupResult error(Error error) {
            return new LookupResult(null, null, false, error);
        }
    }
}

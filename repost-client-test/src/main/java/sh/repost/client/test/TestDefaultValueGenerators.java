package sh.repost.client.test;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import sh.repost.client.DefaultValueGenerators;

/** Thread-safe fixed, sequenced, or failing schema-default generators. */
public final class TestDefaultValueGenerators implements DefaultValueGenerators {
    private final Supplier<Instant> times;
    private final Supplier<String> uuids;
    private final Supplier<String> cuids;

    private TestDefaultValueGenerators(
            Supplier<Instant> times,
            Supplier<String> uuids,
            Supplier<String> cuids) {
        this.times = times;
        this.uuids = uuids;
        this.cuids = cuids;
    }

    /**
     * Creates generators that return the same values for every call.
     *
     * @param now fixed current instant
     * @param uuid fixed UUIDv4-shaped value
     * @param cuid fixed CUID2-shaped value
     * @return fixed generators
     */
    public static TestDefaultValueGenerators fixed(Instant now, String uuid, String cuid) {
        Instant fixedNow = Objects.requireNonNull(now, "now");
        String fixedUuid = Objects.requireNonNull(uuid, "uuid");
        String fixedCuid = Objects.requireNonNull(cuid, "cuid");
        return new TestDefaultValueGenerators(
                () -> fixedNow,
                () -> fixedUuid,
                () -> fixedCuid);
    }

    /**
     * Creates generators that consume each independent sequence once in order.
     *
     * @param times ordered timestamp values
     * @param uuids ordered UUID values
     * @param cuids ordered CUID values
     * @return sequenced generators
     */
    public static TestDefaultValueGenerators sequence(
            List<Instant> times,
            List<String> uuids,
            List<String> cuids) {
        return new TestDefaultValueGenerators(
                sequenceSource(times, "timestamp"),
                sequenceSource(uuids, "UUID"),
                sequenceSource(cuids, "CUID"));
    }

    /**
     * Creates generators whose methods always throw the supplied failure.
     *
     * @param failure deterministic failure
     * @return failing generators
     */
    public static TestDefaultValueGenerators failing(RuntimeException failure) {
        RuntimeException fixedFailure = Objects.requireNonNull(failure, "failure");
        Supplier<Instant> timeFailure = () -> { throw fixedFailure; };
        Supplier<String> stringFailure = () -> { throw fixedFailure; };
        return new TestDefaultValueGenerators(timeFailure, stringFailure, stringFailure);
    }

    @Override
    public Instant now() {
        return times.get();
    }

    @Override
    public String uuid() {
        return uuids.get();
    }

    @Override
    public String cuid() {
        return cuids.get();
    }

    @Override
    public String toString() {
        return "TestDefaultValueGenerators[REDACTED]";
    }

    private static <T> Supplier<T> sequenceSource(List<T> values, String name) {
        Objects.requireNonNull(values, "values");
        ConcurrentLinkedQueue<T> remaining = new ConcurrentLinkedQueue<>();
        for (T value : values) {
            remaining.add(Objects.requireNonNull(value, "values contains null"));
        }
        return () -> {
            T value = remaining.poll();
            if (value == null) {
                throw new IllegalStateException("TestDefaultValueGenerators " + name
                        + " script exhausted");
            }
            return value;
        };
    }
}

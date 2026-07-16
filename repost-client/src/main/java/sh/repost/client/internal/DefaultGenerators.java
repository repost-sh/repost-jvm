package sh.repost.client.internal;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import sh.repost.client.DefaultValueGenerators;

/** System-backed default-value generators for production runtimes. */
public final class DefaultGenerators implements DefaultValueGenerators {
    private final Clock clock;
    private final SecureRandom secureRandom;

    DefaultGenerators(Clock clock, SecureRandom secureRandom) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /**
     * Creates generators backed by the UTC system clock and cryptographic randomness.
     *
     * @return production default generators
     */
    public static DefaultGenerators system() {
        return new DefaultGenerators(Clock.systemUTC(), new SecureRandom());
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public String uuid() {
        return UUID.randomUUID().toString();
    }

    @Override
    public String cuid() {
        return Cuid2.next(secureRandom);
    }

    @Override
    public String toString() {
        return "DefaultGenerators[system]";
    }
}
